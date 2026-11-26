package de.toffano.issuemanager

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.ide.impl.ProjectUtil
import java.nio.file.Path
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.VcsTaskHandler
import com.intellij.tasks.BranchInfo
import com.intellij.tasks.LocalTask
import com.intellij.tasks.TaskListener
import com.intellij.tasks.TaskManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SearchTextField
import javax.swing.event.DocumentEvent
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.ToolTipManager
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.TransferHandler
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

enum class IssueSort(private val labelKey: String, val comparator: Comparator<GitHubIssue>) {
    NEWEST("sort.newest", compareByDescending { it.number }),
    OLDEST("sort.oldest", compareBy { it.number }),
    UPDATED("sort.updated", compareByDescending { it.updatedAt }),
    TITLE("sort.title", compareBy(String.CASE_INSENSITIVE_ORDER) { it.title });

    val label: String get() = text(labelKey)
}

/**
 * How the open issues are grouped, by milestone, type, label or assignee. An issue with several labels or assignees
 * appears in each of their groups. If no open issue belongs to a group, the list is flat.
 */
enum class IssueGrouping(private val labelKey: String, private val emptyKey: String, val groupsOf: (GitHubIssue) -> List<String>) {
    MILESTONE("group.by.milestone", "group.noMilestone", { listOfNotNull(it.milestone) }),
    TYPE("group.by.type", "group.noType", { listOfNotNull(it.type) }),
    LABEL("group.by.label", "group.noLabel", { it.labels }),
    ASSIGNEE("group.by.assignee", "group.noAssignee", { it.assignees }),
    NONE("group.by.none", "group.by.none", { emptyList() });

    val label: String get() = text(labelKey)

    /** Title of the group for issues that have no milestone, label or assignee */
    val emptyLabel: String get() = text(emptyKey)
}

/** Filled dot in the text colour of the row, marks issues of the checked-out branch. */
private object ActiveDot : Icon {
    override fun getIconWidth() = JBUI.scale(12)
    override fun getIconHeight() = JBUI.scale(12)
    override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = component.foreground
        val size = JBUI.scale(8)
        g.fillOval(x + (iconWidth - size) / 2, y + (iconHeight - size) / 2, size, size)
        g.dispose()
    }
}

/** Content of the "Issues" tool window: open issues by milestone, with branch and sorting. */
class IssuesPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    /** One row of the list: the issue and what Rider and Git know about it. */
    private class Row(
        val issue: GitHubIssue,
        val taskBranches: List<String>,
        val detectedBranches: List<String>,
        currentBranches: Set<String>,
        otherWorktrees: Map<String, String>,
    ) {
        private val shownBranches = taskBranches.ifEmpty { detectedBranches }

        /** Does the issue belong to the checked-out branch? What counts is the branch the row shows. */
        val onCurrentBranch = shownBranches.any { it in currentBranches }

        /**
         * The branch of the issue, empty if it has none. Of several branches the one that is checked out counts,
         * here or in another worktree.
         */
        val branch: String = (
            shownBranches.firstOrNull { it in currentBranches }
                ?: shownBranches.firstOrNull { it in otherWorktrees }
                ?: shownBranches.firstOrNull()
            ).orEmpty()

        /** Path of the other worktree that has the branch of the issue checked out, otherwise `null`. */
        val worktree: String? = shownBranches.firstNotNullOfOrNull { otherWorktrees[it] }
        val worktreeName: String? = worktree?.substringAfterLast('/')

        /** Searches number, title, assignees and branches, ignoring case. */
        fun matches(text: String): Boolean =
            (listOf("#${issue.number}", issue.title) + issue.assignees + taskBranches + detectedBranches)
                .any { it.contains(text, ignoreCase = true) }
    }

    /**
     * A group heading of the list. [name] is the milestone, type, label or assignee, `null` for the group of issues
     * without one and for the closed issues.
     */
    private class Group(val name: String?, val title: String, val closed: Boolean = false)

    private val settings = PropertiesComponent.getInstance(project)
    private var onlyMine: Boolean
        get() = settings.getBoolean("issuemanager.onlyMine", true)
        set(value) = settings.setValue("issuemanager.onlyMine", value, true)
    private var onlyWithBranch: Boolean
        get() = settings.getBoolean("issuemanager.onlyWithBranch", false)
        set(value) = settings.setValue("issuemanager.onlyWithBranch", value, false)
    private var showClosed: Boolean
        get() = settings.getBoolean("issuemanager.showClosed", false)
        set(value) = settings.setValue("issuemanager.showClosed", value, false)
    private var sort: IssueSort
        get() = IssueSort.entries.firstOrNull { it.name == settings.getValue("issuemanager.sort") } ?: IssueSort.NEWEST
        set(value) = settings.setValue("issuemanager.sort", value.name)
    private var grouping: IssueGrouping
        get() = IssueGrouping.entries.firstOrNull { it.name == settings.getValue("issuemanager.grouping") } ?: IssueGrouping.MILESTONE
        set(value) = settings.setValue("issuemanager.grouping", value.name)
    /** Issues with one of these labels are not shown */
    private var hiddenLabels: List<String>
        get() = settings.getList("issuemanager.hiddenLabels").orEmpty()
        set(value) = settings.setList("issuemanager.hiddenLabels", value)
    /** Issues of these types are not shown */
    private var hiddenTypes: List<String>
        get() = settings.getList("issuemanager.hiddenTypes").orEmpty()
        set(value) = settings.setList("issuemanager.hiddenTypes", value)

    private var issues = emptyList<GitHubIssue>()
    /** Types and labels of the repository for the menus "Change type" and "Labels" */
    private var choices = RepoChoices()
    private var repoFound = false
    /** The worktrees the list was last drawn with */
    private var shownWorktrees = emptyMap<String, String>()
    /** After a "no" to logging in again, do not ask on every reload, only after the next "Refresh" */
    private var loginDeclined = false
    private val previews = mutableMapOf<Int, LightVirtualFile>()

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = object : Tree(treeModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val node = getPathForLocation(event.x, event.y)?.lastPathComponent as? DefaultMutableTreeNode
            val row = node?.userObject as? Row ?: return null
            return when {
                row.onCurrentBranch -> text("tooltip.currentBranch")
                row.worktree != null -> text("tooltip.worktree", row.worktreeName)
                else -> null
            }
        }
    }
    private val status = JBLabel(" ")
    private val search = SearchTextField(false)

    init {
        tree.isRootVisible = false
        ToolTipManager.sharedInstance().registerComponent(tree)
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
            ) {
                when (val item = (value as DefaultMutableTreeNode).userObject) {
                    is Row -> {
                        // Dot in front of issues of the checked-out branch, an empty space of the same width for all others
                        icon = if (item.onCurrentBranch) ActiveDot else EmptyIcon.create(ActiveDot)
                        val title = "#${item.issue.number} ${item.issue.title}"
                        if (item.issue.closed) append(title, SimpleTextAttributes.GRAYED_ATTRIBUTES) else append(title)
                        // Branch from the Rider task in normal text, a branch that was only detected in grey
                        if (item.taskBranches.isNotEmpty()) {
                            append("   ⎇ ${item.taskBranches.joinToString()}")
                        } else if (item.detectedBranches.isNotEmpty()) {
                            append("   ⎇ ${item.detectedBranches.joinToString()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        item.worktreeName?.let { append(" " + text("row.worktree", it), SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                        if (item.issue.assignees.isNotEmpty()) {
                            append("   ${item.issue.assignees.joinToString { "@$it" }}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                    is Group -> append(item.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
            }
        }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                showPreview()
                return true
            }
        }.installOn(tree)
        // When Rider creates or removes a worktree, there is no Git event the list listens to.
        // So check whether the worktrees have changed whenever the list gets the focus.
        tree.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) {
                if (branchesInOtherWorktrees() != shownWorktrees) rebuild()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER && selected() != null) {
                    showPreview()
                    event.consume()
                }
            }
        })

        installDragToGroup()

        val openInBrowser = action(text("action.openInBrowser"), AllIcons.General.Web, { selected() != null }) {
            selected()?.let { BrowserUtil.browse(it.url) }
        }
        val assignToMe = action(text("action.assignToMe"), AllIcons.General.Add, { selected() != null }) {
            selected()?.let { issue -> changeOnGitHub(issue, text("action.assignToMe")) { GitHubIssues.assignToMe(project, issue, it) } }
        }
        val unassignMe = action(text("action.unassignMe"), AllIcons.General.Remove, { selected() != null }) {
            selected()?.let { issue -> changeOnGitHub(issue, text("action.unassignMe")) { GitHubIssues.unassignMe(project, issue, it) } }
        }
        // The types of the repository, the current one ticked, and "No type" at the end
        val typeGroup = issueMenu(text("action.changeType"), AllIcons.Actions.Edit, { choices.types.isNotEmpty() }) { issue ->
            fun entry(name: String, type: Choice?) = toggle(name, null, { issue.type == type?.name }, closesMenu = true) {
                if (issue.type != type?.name) changeType(issue, type)
            }
            choices.types.map { entry(it.name, it) } + Separator.getInstance() + entry(text("group.noType"), null)
        }
        // All labels of the repository, the ones of the issue ticked. A click adds or removes one label.
        val labelGroup = issueMenu(text("action.labels"), AllIcons.Nodes.Tag, { choices.labels.isNotEmpty() }) { issue ->
            choices.labels.map { label ->
                toggle(label.name, null, { label.name in issue.labels }, closesMenu = true) { add -> changeLabel(issue, label, add) }
            }
        }
        val sortGroup = DefaultActionGroup(text("sort"), true).apply {
            templatePresentation.icon = AllIcons.ObjectBrowser.Sorted
            IssueSort.entries.forEach { option ->
                add(toggle(option.label, null, { sort == option }) { sort = option; rebuild() })
            }
        }
        val groupingGroup = DefaultActionGroup(text("group.by"), true).apply {
            templatePresentation.icon = AllIcons.Actions.GroupBy
            IssueGrouping.entries.forEach { option ->
                add(toggle(option.label, null, { grouping == option }) { grouping = option; rebuild() })
            }
        }
        // One switch per type and per label of the loaded issues. A ticked entry hides its issues.
        val hideGroup = object : ActionGroup(text("action.hide"), true), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun getChildren(event: AnActionEvent?): Array<AnAction> = (
                hideSwitches(text("group.by.type"), issues.mapNotNull { it.type }, { hiddenTypes }) { hiddenTypes = it } +
                    hideSwitches(text("group.by.label"), issues.flatMap { it.labels }, { hiddenLabels }) { hiddenLabels = it }
                ).toTypedArray()
        }
        hideGroup.templatePresentation.icon = AllIcons.General.Filter

        // Submenu with the commands from the settings, each with a submenu of the prompt templates.
        // A click runs the command in a new terminal tab.
        val startWithGroup = object : ActionGroup(text("action.startWith"), true), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = selected() != null
            }
            override fun getChildren(event: AnActionEvent?): Array<AnAction> = (
                IssueManagerSettings.get().commands.map { command ->
                    promptMenu(command.name, null) { prompt -> withContent { row, content -> start(command, prompt, row, content) } }
                } +
                    Separator.getInstance() +
                    action(text("action.configureCommands"), null) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, IssueManagerConfigurable::class.java)
                    }
                ).toTypedArray()
        }
        startWithGroup.templatePresentation.icon = AllIcons.Actions.Execute

        val copyAsPromptGroup = object : ActionGroup(text("action.copyAsPrompt"), true), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = selected() != null
            }
            override fun getChildren(event: AnActionEvent?): Array<AnAction> =
                IssueManagerSettings.get().prompts.map { prompt ->
                    action(prompt.name, null) {
                        withContent { row, content ->
                            copy(issuePrompt(prompt, row.issue, row.branch, content))
                            status.text = text("status.promptCopied", row.issue.number)
                        }
                    }
                }.toTypedArray()
        }
        copyAsPromptGroup.templatePresentation.icon = AllIcons.Actions.Copy

        val newBranchGroup = branchNameMenu(text("action.openInNewBranch"), AllIcons.Vcs.Branch, ::openInNewBranch)

        // Submenu with the local branches, a click checks the branch out and assigns it to the issue.
        val existingBranchGroup = object : ActionGroup(text("action.openInExistingBranch"), true), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = freeForBranching()
            }
            override fun getChildren(event: AnActionEvent?): Array<AnAction> {
                val issue = selected() ?: return emptyArray()
                val handler = VcsTaskHandler.getAllHandlers(project).firstOrNull { it.isEnabled } ?: return emptyArray()
                // The current branch is covered by "Link to current branch", branches of other worktrees cannot be used here.
                val unavailable = handler.currentTasks.map { it.name } + branchesInOtherWorktrees().keys
                val detected = detectBranches(issue, existingBranchNames())
                return handler.allExistingTasks
                    .filter { !it.isRemote && it.name !in unavailable }
                    // Branches whose name matches the issue come first
                    .sortedWith(compareBy({ it.name !in detected }, { it.name }))
                    .map { branch ->
                        action(branch.name, null) {
                            handler.switchToTask(branch, Runnable {})
                            assign(issue, listOf(branch))
                        }
                    }
                    .toTypedArray()
            }
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "IssueManager",
            DefaultActionGroup(
                action(text("action.refresh"), AllIcons.Actions.Refresh) {
                    loginDeclined = false
                    // Also have the worktrees read again, Rider otherwise only does that at intervals.
                    GitRepositoryManager.getInstance(project).repositories.forEach { it.workingTreeHolder.scheduleReload() }
                    reload()
                },
                toggle(text("action.onlyMine"), AllIcons.General.User, { onlyMine }) { onlyMine = it; reload() },
                toggle(text("action.onlyWithBranch"), AllIcons.Vcs.Branch, { onlyWithBranch }) { onlyWithBranch = it; rebuild() },
                toggle(text("action.showClosed"), AllIcons.Actions.ToggleVisibility, { showClosed }) { showClosed = it; reload() },
                sortGroup,
                groupingGroup,
                hideGroup,
                openInBrowser,
                assignToMe,
                unassignMe,
            ),
            true,
        )
        toolbar.targetComponent = tree

        PopupHandler.installPopupMenu(
            tree,
            DefaultActionGroup(
                action(text("action.showContent"), AllIcons.Actions.Preview, { selected() != null }) { showPreview() },
                action(text("action.writeComment"), AllIcons.General.Balloon, { selected() != null }) { writeComment() },
                assignToMe,
                unassignMe,
                typeGroup,
                labelGroup,
                action(text("action.switchToBranch"), AllIcons.Actions.CheckOut, { selected()?.let(::canSwitchTo) == true }) {
                    switchToBranch()
                },
                newBranchGroup,
                existingBranchGroup,
                action(text("action.openInNewWorktree"), null, { freeForBranching() }) { newWorktree() },
                action(text("action.linkToCurrentBranch"), null, { freeForBranching() }) { linkToCurrentBranch() },
                action(text("action.openWorktree"), AllIcons.Actions.MenuOpen, { selectedRow()?.worktree != null }) { openWorktree() },
                startWithGroup,
                copyAsPromptGroup,
                openInBrowser,
                action(text("action.copyUrl"), AllIcons.Actions.Copy, { selected() != null }) { selected()?.let { copy(it.url) } },
                action(text("action.copyNumber"), AllIcons.Actions.Copy, { selected() != null }) {
                    selected()?.let { copy(it.number.toString()) }
                },
            ),
            "IssueManagerPopup",
        )

        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = rebuild()
        })
        add(JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        // Task and branch changes only affect the display, GitHub is not asked again for them.
        TaskManager.getManager(project).addTaskListener(object : TaskListener {
            override fun taskActivated(task: LocalTask) = later { rebuild() }
            override fun taskDeactivated(task: LocalTask) = later { rebuild() }
            override fun taskAdded(task: LocalTask) = later { rebuild() }
            override fun taskRemoved(task: LocalTask) = later { rebuild() }
        }, parent)
        project.messageBus.connect(parent).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            // When Rider starts, Git does not know the repository yet. Load once as soon as it is there.
            GitRepositoryChangeListener { later { if (repoFound) rebuild() else reload() } },
        )

        reload()
    }

    /** Fetches the issues from GitHub again. */
    private fun reload() {
        val repo = GitHubIssues.repoOf(project)
        if (repo == null) {
            status.text = text("status.noRepo")
            return
        }
        repoFound = true
        status.text = text("status.loading", repo)
        val mine = onlyMine
        val closed = showClosed
        object : Task.Backgroundable(project, text("task.loadIssues")) {
            private var loaded = emptyList<GitHubIssue>()
            private var loadedChoices = RepoChoices()

            override fun run(indicator: ProgressIndicator) {
                loaded = GitHubIssues.load(project, repo, mine, closed, indicator)
                loadedChoices = GitHubIssues.loadChoices(project, repo, indicator)
            }

            override fun onSuccess() {
                issues = loaded
                choices = loadedChoices
                val closedCount = loaded.count { it.closed }
                status.text = text("status.open", repo, loaded.size - closedCount) +
                    if (closed) text("status.closed", closedCount) else ""
                rebuild()
            }

            override fun onThrowable(error: Throwable) = failed(text("error"), error) { reload() }
        }.queue()
    }

    /** Rebuilds the list from the loaded issues and the current state of tasks and branches. */
    private fun rebuild() {
        val tasks = TaskManager.getManager(project).localTasks.associateBy { it.id }
        val branchNames = existingBranchNames()
        val currentBranches = GitRepositoryManager.getInstance(project).repositories.mapNotNull { it.currentBranch?.name }.toSet()
        val otherWorktrees = branchesInOtherWorktrees()
        shownWorktrees = otherWorktrees
        val hiddenLabels = hiddenLabels.toSet()
        val hiddenTypes = hiddenTypes.toSet()
        val rows = issues.sortedWith(sort.comparator)
            .map { issue ->
                val task = tasks[GitHubIssueTask.idOf(issue.number)]
                Row(
                    issue,
                    taskBranches = task.assignedBranches(branchNames).map { it.name }.distinct(),
                    detectedBranches = detectBranches(issue, branchNames),
                    currentBranches,
                    otherWorktrees,
                )
            }
            .filter { !onlyWithBranch || it.taskBranches.isNotEmpty() || it.detectedBranches.isNotEmpty() }
            .filter { row -> row.issue.type !in hiddenTypes && row.issue.labels.none { it in hiddenLabels } }
            .filter { it.matches(search.text.trim()) }

        // Closed issues always come last, whatever the sorting and the grouping.
        val (closedRows, openRows) = rows.partition { it.issue.closed }

        root.removeAllChildren()
        val groupsOf = grouping.groupsOf
        val grouped = openRows.any { groupsOf(it.issue).isNotEmpty() }
        tree.showsRootHandles = grouped
        if (!grouped) {
            (openRows + closedRows).forEach { root.add(DefaultMutableTreeNode(it)) }
        } else {
            // Groups by name, issues that belong to no group at the end
            val groups = openRows
                .flatMap { row -> groupsOf(row.issue).ifEmpty { listOf(null) }.map { it to row } }
                .groupBy({ it.first }, { it.second })
            groups.keys.sortedWith(compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it }).forEach { name ->
                val group = DefaultMutableTreeNode(Group(name, "${name ?: grouping.emptyLabel} (${groups.getValue(name).size})"))
                groups.getValue(name).forEach { group.add(DefaultMutableTreeNode(it)) }
                root.add(group)
            }
            if (closedRows.isNotEmpty()) {
                val group = DefaultMutableTreeNode(Group(null, "${text("group.closed")} (${closedRows.size})", closed = true))
                closedRows.forEach { group.add(DefaultMutableTreeNode(it)) }
                root.add(group)
            }
        }
        treeModel.reload()
        TreeUtil.expandAll(tree)
    }

    private fun selectedRow(): Row? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Row

    private fun selected(): GitHubIssue? = selectedRow()?.issue

    /**
     * May a branch be assigned to the selected issue here? Not while it is being worked on in another worktree.
     * Once that worktree is gone, the issue is free again.
     */
    private fun freeForBranching(): Boolean = selectedRow()?.let { it.worktree == null } == true

    /** Brings the Rider window of the worktree to the front or opens the folder in a new window. */
    private fun openWorktree() {
        val path = Path.of(selectedRow()?.worktree ?: return)
        ProjectUtil.findAndFocusExistingProjectForPath(path) ?: ProjectUtil.openOrImport(path, null, true)
    }

    /** Loads description and comments of the issue and shows them as a Markdown file in an editor tab. */
    private fun showPreview(issue: GitHubIssue? = selected()) {
        if (issue == null) return
        val repo = GitHubIssues.repoOf(project) ?: return
        object : Task.Backgroundable(project, text("task.loadIssue", issue.number)) {
            private var markdown = ""

            override fun run(indicator: ProgressIndicator) {
                markdown = GitHubIssues.loadContent(project, repo, issue, indicator).all + "\n"
            }

            override fun onSuccess() {
                val editors = FileEditorManager.getInstance(project)
                // Close an older preview of the same issue so that no outdated tab stays open.
                previews.remove(issue.number)?.let(editors::closeFile)
                val file = LightVirtualFile("#${issue.number} ${issue.title.replace('/', '-')}.md", markdown)
                file.isWritable = false
                previews[issue.number] = file
                OpenFileDescriptor(project, file).navigate(true)
            }

            override fun onThrowable(error: Throwable) = failed(text("error"), error) { showPreview(issue) }
        }.queue()
    }

    /** Loads description and comments of the selected issue in the background and hands them to [use]. */
    private fun withContent(use: (Row, IssueContent) -> Unit) {
        val row = selectedRow() ?: return
        val repo = GitHubIssues.repoOf(project) ?: return
        object : Task.Backgroundable(project, text("task.loadIssue", row.issue.number)) {
            private var content: IssueContent? = null

            override fun run(indicator: ProgressIndicator) {
                content = GitHubIssues.loadContent(project, repo, row.issue, indicator)
            }

            override fun onSuccess() {
                content?.let { use(row, it) }
            }

            override fun onThrowable(error: Throwable) = failed(text("error"), error) { withContent(use) }
        }.queue()
    }

    /**
     * Runs the command in a new terminal tab. If the issue is being worked on in another worktree, the terminal
     * starts in the folder of that worktree, otherwise in the project folder. Commands that ask for it get the
     * branch of the issue checked out first.
     */
    private fun start(command: StartCommand, prompt: PromptTemplate, row: Row, content: IssueContent) {
        fun run(branch: String) {
            val text = issuePrompt(prompt, row.issue, branch, content)
            if (text.toByteArray().size > MAX_PROMPT_BYTES) {
                status.text = text("status.promptTooLong", row.issue.number)
                return
            }
            try {
                // The folder of the other worktree only if the issue is not worked on here
                val directory = row.worktree.takeIf { !row.onCurrentBranch } ?: project.basePath
                startInTerminal(project, command, row.issue, branch, text, directory)
            } catch (error: Exception) {
                status.text = "${text("error.terminal")}: ${error.message}"
            }
        }
        if (command.switchBranch) onIssueBranch(row, ::run) else run(row.branch)
    }

    /**
     * Checks out the branch of the issue and then calls [then] with its name. If the issue has no branch here, a popup
     * offers the same names as "Open in new branch". The chosen branch is created from the current state and
     * assigned to the issue. Nothing is checked out if the branch already is, here or in another worktree.
     * [then] is not called if the popup is closed without a choice or the checkout does not succeed.
     */
    private fun onIssueBranch(row: Row, then: (String) -> Unit) {
        if (row.onCurrentBranch || row.worktree != null) return then(row.branch)
        // A branch that only GitHub knows, such as the one of a pull request from a fork, cannot be checked out.
        if (row.branch in existingBranchNames()) return checkOut(row, row.branch, isNew = false, then)
        JBPopupFactory.getInstance().createActionGroupPopup(
            text("popup.newBranch", row.issue.number),
            DefaultActionGroup(branchNameActions(row.issue) { checkOut(row, it, isNew = true, then) }),
            DataManager.getInstance().getDataContext(tree),
            null,
            false,
        ).showCenteredInCurrentWindow(project)
    }

    /** Checks the branch out, creating it from the current state if it [isNew], and then calls [then] with its name. */
    private fun checkOut(row: Row, branch: String, isNew: Boolean, then: (String) -> Unit) {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        // Git calls this after the checkout, whether it succeeded or not.
        val whenDone = Runnable {
            val checkedOut = repositories.filter { it.currentBranchName == branch }
            if (checkedOut.isEmpty()) {
                status.text = text("status.notStarted", branch)
            } else {
                if (isNew) assign(row.issue, listOf(VcsTaskHandler.TaskInfo(branch, checkedOut.map { it.presentableUrl })))
                then(branch)
            }
        }
        val brancher = GitBrancher.getInstance(project)
        if (isNew) brancher.checkoutNewBranchStartingFrom(branch, "HEAD", repositories, whenDone)
        else brancher.checkout(branch, false, repositories, whenDone)
    }

    /** Asks for the text and, after OK, publishes it as a comment on the issue. The preview then shows the result. */
    private fun writeComment() {
        val issue = selected() ?: return
        val comment = Messages.showMultilineInputDialog(
            project,
            text("dialog.comment.message"),
            text("dialog.comment.title", issue.number, issue.title),
            "",
            null,
            null,
        )
        if (comment.isNullOrBlank()) return

        object : Task.Backgroundable(project, text("task.sendComment", issue.number)) {
            override fun run(indicator: ProgressIndicator) = GitHubIssues.addComment(project, issue, comment, indicator)

            override fun onSuccess() {
                status.text = text("status.commentSent", issue.number)
                showPreview(issue)
            }

            override fun onThrowable(error: Throwable) {
                // The text must not be lost when sending fails.
                copy(comment)
                failed(text("error.commentNotSent"), error) {}
            }
        }.queue()
    }

    /** Runs a change of the issue on GitHub in the background and then reloads the list. */
    private fun changeOnGitHub(issue: GitHubIssue, title: String, change: (ProgressIndicator) -> Unit) {
        object : Task.Backgroundable(project, "#${issue.number}: $title") {
            override fun run(indicator: ProgressIndicator) = change(indicator)

            override fun onSuccess() = reload()

            override fun onThrowable(error: Throwable) = failed(text("error.failed", title), error) {}
        }.queue()
    }

    /** Sets the type of the issue on GitHub, `null` removes it. */
    private fun changeType(issue: GitHubIssue, type: Choice?) =
        changeOnGitHub(issue, text("action.changeType")) { GitHubIssues.setType(project, issue, type, it) }

    /** Adds the label to the issue on GitHub or removes it. */
    private fun changeLabel(issue: GitHubIssue, label: Choice, add: Boolean) =
        changeOnGitHub(issue, label.name) { GitHubIssues.setLabel(project, issue, label, add, it) }

    /**
     * Lets an issue be dragged onto a group or onto an issue in a group. Grouped by type, it gets the type of the
     * group. Grouped by label, it gets the label of the group as well and keeps its other labels.
     * The other groupings and the closed issues take no drop.
     */
    private fun installDragToGroup() {
        var dragged: GitHubIssue? = null

        /** The change dropping the dragged issue at this place would make, `null` if none. */
        fun dropChange(support: TransferHandler.TransferSupport): (() -> Unit)? {
            val issue = dragged ?: return null
            var node = (support.dropLocation as? JTree.DropLocation)?.path?.lastPathComponent as? DefaultMutableTreeNode ?: return null
            if (node.userObject is Row) node = node.parent as? DefaultMutableTreeNode ?: return null
            val group = node.userObject as? Group ?: return null
            if (group.closed) return null
            return when (grouping) {
                IssueGrouping.TYPE -> {
                    // "No type" removes the type. A type that is switched off on GitHub cannot be set.
                    val type = group.name?.let { name -> choices.types.firstOrNull { it.name == name } ?: return null }
                    if (issue.type == group.name) null else ({ changeType(issue, type) })
                }
                IssueGrouping.LABEL -> {
                    val label = choices.labels.firstOrNull { it.name == group.name } ?: return null
                    if (label.name in issue.labels) null else ({ changeLabel(issue, label, true) })
                }
                else -> null
            }
        }

        tree.dragEnabled = true
        tree.dropMode = DropMode.ON
        tree.transferHandler = object : TransferHandler() {
            override fun getSourceActions(component: JComponent) = MOVE

            override fun createTransferable(component: JComponent): Transferable? {
                val issue = selected() ?: return null
                dragged = issue
                return StringSelection("#${issue.number}")
            }

            override fun exportDone(source: JComponent, data: Transferable?, action: Int) {
                dragged = null
            }

            override fun canImport(support: TransferSupport) = support.isDrop && dropChange(support) != null

            override fun importData(support: TransferSupport): Boolean {
                val change = dropChange(support) ?: return false
                change()
                return true
            }
        }
    }

    /** A submenu for the selected issue, enabled when an issue is selected and [enabled] holds. */
    private fun issueMenu(title: String, icon: Icon, enabled: () -> Boolean, children: (GitHubIssue) -> List<AnAction>): ActionGroup {
        val menu = object : ActionGroup(title, true), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = selected() != null && enabled()
            }
            override fun getChildren(event: AnActionEvent?): Array<AnAction> =
                selected()?.let(children).orEmpty().toTypedArray()
        }
        menu.templatePresentation.icon = icon
        return menu
    }

    /**
     * Submenu with the branch names for the selected issue and "Other name …". A click calls [open] right away.
     * Disabled while the issue is being worked on in another worktree.
     */
    private fun branchNameMenu(title: String, icon: Icon, open: (GitHubIssue, String) -> Unit): ActionGroup {
        val menu = object : ActionGroup(title, true), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = freeForBranching()
            }
            override fun getChildren(event: AnActionEvent?): Array<AnAction> {
                val issue = selected() ?: return emptyArray()
                return branchNameActions(issue) { open(issue, it) }.toTypedArray()
            }
        }
        menu.templatePresentation.icon = icon
        return menu
    }

    /** One entry per suggested branch name for the issue and "Other name …". A click calls [open] with the name. */
    private fun branchNameActions(issue: GitHubIssue, open: (String) -> Unit): List<AnAction> {
        val suggestions = branchNameSuggestions(issue)
        return suggestions.map { name -> action(name, null) { open(name) } } +
            Separator.getInstance() +
            action(text("action.otherName"), null) { askBranchName(issue, suggestions.first())?.let(open) }
    }

    /** Asks for a freely chosen branch name. `null` if the dialog was cancelled. */
    private fun askBranchName(issue: GitHubIssue, suggestion: String): String? {
        val handler = VcsTaskHandler.getAllHandlers(project).firstOrNull { it.isEnabled } ?: return null
        val validator = object : InputValidator {
            override fun checkInput(input: String) = handler.isBranchNameValid(input)
            override fun canClose(input: String) = checkInput(input)
        }
        return Messages.showInputDialog(
            project, text("dialog.branch.message"), text("dialog.branch.title", issue.number, issue.title), null, suggestion, validator,
        )
    }

    /**
     * Opens Rider's own "New Worktree" dialog. Worktrees are deliberately created through that dialog only, because it
     * can also copy local files. The dialog cannot be pre-filled from outside, so the suggested branch name is put on
     * the clipboard.
     */
    private fun newWorktree() {
        val issue = selected() ?: return
        val dialog = ActionManager.getInstance().getAction("Git.CreateNewWorkingTree")
        if (dialog == null) {
            status.text = text("status.noWorktreeDialog")
            return
        }
        val branchName = branchNameSuggestions(issue).first()
        copy(branchName)
        status.text = text("status.branchNameCopied", branchName)
        ActionManager.getInstance().tryToExecute(dialog, null, tree, "IssueManager", true)
    }

    /**
     * Assigns the checked-out branch to the issue. Nothing is checked out and no branch is created.
     */
    private fun linkToCurrentBranch() {
        val issue = selected() ?: return
        val handler = VcsTaskHandler.getAllHandlers(project).firstOrNull { it.isEnabled } ?: return
        assign(issue, handler.currentTasks.asList())
    }

    /** Creates the branch from the current state, checks it out and assigns it to the issue. */
    private fun openInNewBranch(issue: GitHubIssue, branchName: String) {
        val handler = VcsTaskHandler.getAllHandlers(project).firstOrNull { it.isEnabled } ?: return
        assign(issue, listOf(handler.startNewTask(branchName)))
    }

    /**
     * Remembers in Rider's task management which branch belongs to the issue, replacing an earlier assignment.
     * The task is deliberately **not** activated: on activation Rider swaps the open editor tabs for those of the
     * task, which closes every open tab including the preview.
     *
     * The earlier assignment is replaced by creating the task again: `addTask` overwrites an existing task with the
     * same ID. `LocalTask.removeBranch` is no use for this, in Rider 2026.3 it adds the branch instead of removing it
     * (`LocalTaskImpl.removeBranch` calls `myBranches.add`).
     */
    private fun assign(issue: GitHubIssue, branches: List<VcsTaskHandler.TaskInfo>) {
        val task = TaskManager.getManager(project).addTask(GitHubIssueTask(issue))
        branches.flatMap { BranchInfo.fromTaskInfo(it, false) }.forEach(task::addBranch)
        rebuild()
    }

    /** The Rider task of the issue, if a branch has ever been assigned to it. */
    private fun localTask(issue: GitHubIssue): LocalTask? =
        TaskManager.getManager(project).localTasks.firstOrNull { it.id == GitHubIssueTask.idOf(issue.number) }

    /** All local branches and those of `origin`, without the leading `origin/`. */
    private fun existingBranchNames(): Set<String> =
        GitRepositoryManager.getInstance(project).repositories.flatMap { repository ->
            repository.branches.localBranches.map { it.name } +
                repository.branches.remoteBranches.map { it.nameForRemoteOperations }
        }.toSet()

    /**
     * Branches that are assigned to the task and still exist, without the recorded branch of origin.
     * Rider keeps the assignment even after a branch has been deleted, hence the comparison.
     */
    private fun LocalTask?.assignedBranches(branchNames: Set<String>): List<BranchInfo> =
        this?.branches.orEmpty().filter { !it.original && it.name in branchNames }

    private fun assignedBranches(issue: GitHubIssue): List<BranchInfo> =
        localTask(issue).assignedBranches(existingBranchNames())

    /**
     * Branches that are checked out in another worktree of the same repository, with the path of its folder.
     * Git allows a branch in only one worktree at a time.
     */
    private fun branchesInOtherWorktrees(): Map<String, String> =
        GitRepositoryManager.getInstance(project).repositories
            .flatMap { it.workingTreeHolder.getWorkingTrees() }
            .filter { !it.isCurrent }
            .mapNotNull { tree ->
                val branch = tree.currentBranch ?: return@mapNotNull null
                branch.name to tree.path.path
            }
            .toMap()

    /** Can the assigned branch be checked out here? Not if it is missing or checked out in another worktree. */
    private fun canSwitchTo(issue: GitHubIssue): Boolean {
        val branches = assignedBranches(issue).map { it.name }
        return branches.isNotEmpty() && branches.none { it in branchesInOtherWorktrees() }
    }

    /** Checks out the branch assigned to the issue. The task is not activated, see [assign]. */
    private fun switchToBranch() {
        val issue = selected() ?: return
        val handler = VcsTaskHandler.getAllHandlers(project).firstOrNull { it.isEnabled } ?: return
        val branches = assignedBranches(issue)
        val name = branches.firstOrNull()?.name ?: return
        handler.switchToTask(VcsTaskHandler.TaskInfo(name, branches.filter { it.name == name }.map { it.repository }), Runnable {})
    }

    /**
     * Shows an error in the status line. If the GitHub account is no longer logged in, Rider offers the login once
     * and the operation is repeated afterwards.
     */
    private fun failed(what: String, error: Throwable, retry: () -> Unit) {
        status.text = "$what: ${error.message}"
        if (error !is GitHubLoginNeeded || loginDeclined) return
        val wanted = Messages.showYesNoDialog(project, text("dialog.login", error.message), "IssueManager", null) == Messages.YES
        if (wanted && GHAccountsUtil.reLogIn(error.account, project)) retry() else loginDeclined = true
    }

    /**
     * A titled section of the "Hide" menu with one switch per name. Hidden names are listed even if no loaded issue
     * has them any more, so that they can be shown again. Empty if there are no names.
     */
    private fun hideSwitches(title: String, names: List<String>, hidden: () -> List<String>, set: (List<String>) -> Unit): List<AnAction> {
        val switches = (names + hidden()).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).map { name ->
            toggle(name, null, { name in hidden() }) { hide ->
                set(if (hide) hidden() + name else hidden() - name)
                rebuild()
            }
        }
        return if (switches.isEmpty()) emptyList() else listOf(Separator.create(title)) + switches
    }

    /** A submenu with the prompt templates from the settings. With a single template the entry runs it directly. */
    private fun promptMenu(title: String, icon: Icon?, run: (PromptTemplate) -> Unit): AnAction {
        val prompts = IssueManagerSettings.get().prompts
        prompts.singleOrNull()?.let { only -> return action(title, icon) { run(only) } }
        return DefaultActionGroup(title, true).apply {
            templatePresentation.icon = icon
            prompts.forEach { prompt -> add(action(prompt.name, null) { run(prompt) }) }
        }
    }

    private fun later(run: () -> Unit) = ApplicationManager.getApplication().invokeLater(run)

    /** Puts the text on the clipboard. */
    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun action(text: String, icon: Icon?, enabled: () -> Boolean = { true }, run: () -> Unit): AnAction =
        object : AnAction(text, null, icon), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = enabled()
            }
            override fun actionPerformed(event: AnActionEvent) = run()
        }

    /**
     * A menu entry with a tick. Rider keeps the menu open after a click on such an entry, depending on its settings.
     * [closesMenu] closes it in any case, for changes on GitHub: their tick only updates after the list is reloaded.
     */
    private fun toggle(text: String, icon: Icon?, isOn: () -> Boolean, closesMenu: Boolean = false, set: (Boolean) -> Unit): AnAction =
        object : ToggleAction(text, null, icon), DumbAware {
            init {
                if (closesMenu) templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(event: AnActionEvent) = isOn()
            override fun setSelected(event: AnActionEvent, state: Boolean) = set(state)
        }
}
