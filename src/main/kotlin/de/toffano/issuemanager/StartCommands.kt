package de.toffano.issuemanager

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager

/**
 * An entry of the "Start with" menu: the command line is run in a new terminal tab.
 *
 * @param switchBranch check out the branch of the issue before the start. Off for commands that work in a worktree
 * of their own.
 */
data class StartCommand(var name: String = "", var command: String = "", var switchBranch: Boolean = false)

/**
 * A named prompt for an issue. The text may contain the placeholders `{number}`, `{title}`, `{url}`, `{branch}`,
 * `{description}` and `{content}`, see [issuePrompt].
 */
data class PromptTemplate(var name: String = "", var text: String = "")

// A POSIX shell expands the variable in double quotes into exactly one argument, so the text of the issue never
// becomes part of the command line.
private const val PROMPT = "\"\$ISSUE_PROMPT\""

/**
 * The longest prompt that is passed on, in bytes. Linux allows 128 KiB per environment variable and per argument,
 * Windows about 32,000 characters per environment variable.
 */
val MAX_PROMPT_BYTES = if (SystemInfo.isWindows) 30_000 else 100_000

/** Settings of the plugin, the same for all projects. Edited under Settings | Tools | IssueManager. */
@Service
@State(name = "IssueManagerSettings", storages = [Storage("issuemanager.xml")])
class IssueManagerSettings : PersistentStateComponent<IssueManagerSettings.Settings> {

    class Settings {
        // No commands on Windows: PowerShell does not reliably keep a text with quotation marks in one argument,
        // so a command has to be set up there deliberately.
        var commands: MutableList<StartCommand> = if (SystemInfo.isWindows) mutableListOf() else mutableListOf(
            StartCommand("Claude Code", "claude $PROMPT"),
            StartCommand("Claude Code (branch)", "claude $PROMPT", switchBranch = true),
            StartCommand("Claude Code (worktree)", "claude --worktree \"issue-\$ISSUE_NUMBER\" $PROMPT"),
            StartCommand("Codex", "codex $PROMPT"),
            StartCommand("Codex (branch)", "codex $PROMPT", switchBranch = true),
            StartCommand("Codex (worktree)", "codex --worktree $PROMPT"),
        )
        var prompts: MutableList<PromptTemplate> = mutableListOf(
            PromptTemplate("Development", "Work on the following GitHub issue.\n\n{content}"),
            PromptTemplate(
                "Review",
                "Review the current state of the work on the following GitHub issue: compare the changes on this branch " +
                    "with what the issue asks for and report what is missing or wrong. Do not change any files.\n\n{content}",
            ),
            PromptTemplate(
                "Research",
                "Research the following GitHub issue: find the code it concerns and describe possible solutions. " +
                    "Do not change any files.\n\n{content}",
            ),
        )
    }

    private var settings = Settings()

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        settings = state
    }

    companion object {
        fun get(): Settings = service<IssueManagerSettings>().state
    }
}

private val PLACEHOLDER = Regex("""\{(\w+)}""")

/**
 * Fills the placeholders `{name}` of the prompt template. Unknown placeholders stay as they are, and a placeholder
 * inside an inserted value is not filled again.
 */
fun renderPrompt(template: String, values: Map<String, String>): String =
    PLACEHOLDER.replace(template) { values[it.groupValues[1]] ?: it.value }

/**
 * Opens a terminal tab in [directory] and runs the command there. The data of the issue is passed as environment
 * variables (`ISSUE_NUMBER`, `ISSUE_TITLE`, `ISSUE_URL`, `ISSUE_BRANCH`, `ISSUE_PROMPT`). Texts of an issue are
 * written by others, so they are never put into the command line itself.
 *
 * @param prompt the finished prompt, see [issuePrompt]
 */
fun startInTerminal(project: Project, command: StartCommand, issue: GitHubIssue, branch: String, prompt: String, directory: String?) {
    val tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
        .workingDirectory(directory)
        .tabName("#${issue.number} ${command.name}")
        .envVariables(
            mapOf(
                "ISSUE_NUMBER" to issue.number.toString(),
                "ISSUE_TITLE" to issue.title,
                "ISSUE_URL" to issue.url,
                "ISSUE_BRANCH" to branch,
                "ISSUE_PROMPT" to prompt,
            ),
        )
        // The variables only make sense for this start, so the tab is not opened again with the project.
        .restoreOnProjectReopen(false)
        .createTab()
    tab.view.createSendTextBuilder().shouldExecute().send(command.command)
}

/** The prompt for the issue, built from the template. */
fun issuePrompt(template: PromptTemplate, issue: GitHubIssue, branch: String, content: IssueContent): String =
    renderPrompt(
        template.text,
        mapOf(
            "number" to issue.number.toString(),
            "title" to issue.title,
            "url" to issue.url,
            "branch" to branch,
            "description" to content.description,
            "content" to content.all,
        ),
    )
