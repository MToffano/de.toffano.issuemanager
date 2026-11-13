package de.toffano.issuemanager

import com.intellij.icons.AllIcons
import com.intellij.tasks.Comment
import com.intellij.tasks.Task
import com.intellij.tasks.TaskType
import java.util.Date
import javax.swing.Icon

/**
 * A GitHub issue as a task for Rider's task management.
 * The ID is `issue123`, the ID shown is `#123`. Rider also builds its branch name suggestion from the shown ID
 * (placeholder `${id}`), the placeholder `${number}` yields the plain number.
 * There is deliberately no server (`getRepository()` stays `null`).
 */
class GitHubIssueTask(private val issue: GitHubIssue) : Task() {

    override fun getId(): String = idOf(issue.number)
    override fun getPresentableId(): String = "#${issue.number}"
    override fun getNumber(): String = issue.number.toString()
    override fun getSummary(): String = issue.title
    override fun getDescription(): String? = null
    override fun getComments(): Array<Comment> = emptyArray()
    override fun getIcon(): Icon = AllIcons.Vcs.Vendors.Github
    override fun getType(): TaskType = TaskType.OTHER
    override fun getUpdated(): Date? = null
    override fun getCreated(): Date? = null
    override fun isClosed(): Boolean = false
    override fun isIssue(): Boolean = true
    override fun getIssueUrl(): String = issue.url

    companion object {
        /** ID of the Rider task for an issue number */
        fun idOf(number: Int): String = "issue$number"
    }
}
