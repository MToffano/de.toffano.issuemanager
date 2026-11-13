package de.toffano.issuemanager

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
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
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
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

        /** Path of the other worktree that has the branch of the issue checked out, otherwise `null`. */
        val worktree: String? = shownBranches.firstNotNullOfOrNull { otherWorktrees[it] }
        val worktreeName: String? = worktree?.substringAfterLast('/')

        /** Searches number, title, assignees and branches, ignoring case. */
        fun matches(text: String): Boolean =
            (listOf("#${issue.number}", issue.title) + issue.assignees + taskBranches + detectedBranches)
                .any { it.contains(text, ignoreCase = true) }
    }

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

    private var issues = emptyList<GitHubIssue>()
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
                    is String -> append(item, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
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

        val openInBrowser = action(text("action.openInBrowser"), AllIcons.General.Web, { selected() != null }) {
            selected()?.let { BrowserUtil.browse(it.url) }
        }
        val assignToMe = action(text("action.assignToMe"), AllIcons.General.Add, { selected() != null }) {
            changeMyAssignment(text("action.assignToMe"), GitHubIssues::assignToMe)
        }
        val unassignMe = action(text("action.unassignMe"), AllIcons.General.Remove, { selected() != null }) {
            changeMyAssignment(text("action.unassignMe"), GitHubIssues::unassignMe)
        }
        val sortGroup = DefaultActionGroup(text("sort"), true).apply {
            templatePresentation.icon = AllIcons.ObjectBrowser.Sorted
            IssueSort.entries.forEach { option ->
                add(toggle(option.label, null, { sort == option }) { sort = option; rebuild() })
            }
        }
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
                // The current branch is covered by "Open in current branch", branches of other worktrees cannot be used here.
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
                action(text("action.switchToBranch"), AllIcons.Actions.CheckOut, { selected()?.let(::canSwitchTo) == true }) {
                    switchToBranch()
                },
                newBranchGroup,
                existingBranchGroup,
                action(text("action.openInNewWorktree"), null, { freeForBranching() }) { newWorktree() },
                action(text("action.openInCurrentBranch"), null, { freeForBranching() }) { openInCurrentBranch() },
                action(text("action.openWorktree"), AllIcons.Actions.MenuOpen, { selectedRow()?.worktree != null }) { openWorktree() },
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

            override fun run(indicator: ProgressIndicator) {
                loaded = GitHubIssues.load(project, repo, mine, closed, indicator)
            }

            override fun onSuccess() {
                issues = loaded
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
            .filter { it.matches(search.text.trim()) }

        // Closed issues always come last, whatever the sorting and the milestone.
        val (closedRows, openRows) = rows.partition { it.issue.closed }

        root.removeAllChildren()
        val grouped = openRows.any { it.issue.milestone != null }
        tree.showsRootHandles = grouped
        if (!grouped) {
            (openRows + closedRows).forEach { root.add(DefaultMutableTreeNode(it)) }
        } else {
            // Milestones by name, issues without a milestone at the end
            val groups = openRows.groupBy { it.issue.milestone }
            groups.keys.sortedWith(compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it }).forEach { milestone ->
                val group = DefaultMutableTreeNode("${milestone ?: text("group.noMilestone")} (${groups.getValue(milestone).size})")
                groups.getValue(milestone).forEach { group.add(DefaultMutableTreeNode(it)) }
                root.add(group)
            }
            if (closedRows.isNotEmpty()) {
                val group = DefaultMutableTreeNode("${text("group.closed")} (${closedRows.size})")
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
                markdown = GitHubIssues.loadMarkdown(project, repo, issue, indicator)
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

    /** Adds or removes the logged-in user as assignee of the selected issue and reloads the list. */
    private fun changeMyAssignment(title: String, change: (Project, GitHubIssue, ProgressIndicator) -> Unit) {
        val issue = selected() ?: return
        object : Task.Backgroundable(project, "#${issue.number}: $title") {
            override fun run(indicator: ProgressIndicator) = change(project, issue, indicator)

            override fun onSuccess() = reload()

            override fun onThrowable(error: Throwable) = failed(text("error.assignment", title), error) {}
        }.queue()
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
                val suggestions = branchNameSuggestions(issue)
                return (
                    suggestions.map { name -> action(name, null) { open(issue, name) } } +
                        Separator.getInstance() +
                        action(text("action.otherName"), null) { askBranchName(issue, suggestions.first())?.let { open(issue, it) } }
                    ).toTypedArray()
            }
        }
        menu.templatePresentation.icon = icon
        return menu
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
    private fun openInCurrentBranch() {
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

    private fun toggle(text: String, icon: Icon?, isOn: () -> Boolean, set: (Boolean) -> Unit): AnAction =
        object : ToggleAction(text, null, icon), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(event: AnActionEvent) = isOn()
            override fun setSelected(event: AnActionEvent, state: Boolean) = set(state)
        }
}
