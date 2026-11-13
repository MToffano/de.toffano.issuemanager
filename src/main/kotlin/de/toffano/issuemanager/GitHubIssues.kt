package de.toffano.issuemanager

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import org.jetbrains.plugins.github.api.GithubApiRequest
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class GitHubIssue(
    /** Internal ID from GitHub, needed for commenting */
    val id: String,
    val number: Int,
    val title: String,
    val url: String,
    val closed: Boolean,
    val milestone: String?,
    /** Time of the last change as GitHub delivers it (ISO 8601, sortable as text) */
    val updatedAt: String,
    /** GitHub user names of the assignees */
    val assignees: List<String>,
    val labels: List<String>,
    /** Branches of open pull requests that close the issue, and branches linked on GitHub */
    val branches: List<String>,
)

/** The GitHub account in Rider no longer has a valid token and needs a new login. */
class GitHubLoginNeeded(val account: GithubAccount, cause: Throwable) :
    Exception(text("error.loginNeeded", account.name), cause)

private val IMAGE_ID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

/**
 * Makes uploaded images loadable without a login: in the text they appear as
 * `https://github.com/user-attachments/assets/<id>`, which only loads when logged in. In the rendered HTML GitHub
 * delivers the same image with a signed URL that is valid for a few minutes without a login. That URL is put into
 * the text.
 */
fun signImageUrls(markdown: String, html: String): String {
    val signedById = Regex("""<img[^>]*src="([^"]+)"""").findAll(html)
        .map { it.groupValues[1].replace("&amp;", "&") }
        .mapNotNull { url -> IMAGE_ID.find(url)?.let { it.value to url } }
        .toMap()
    return Regex("""https://github\.com/user-attachments/assets/(${IMAGE_ID.pattern})""")
        .replace(markdown) { signedById[it.groupValues[1]] ?: it.value }
}

/**
 * Access to the issues on GitHub through the account configured in Rider.
 * Everything here only reads, with two exceptions: [addComment] publishes a comment,
 * [assignToMe] and [unassignMe] add or remove the logged-in user as assignee.
 */
object GitHubIssues {

    private const val QUERY = """
        query(${'$'}q: String!, ${'$'}after: String) {
          search(type: ISSUE, query: ${'$'}q, first: 100, after: ${'$'}after) {
            pageInfo { hasNextPage endCursor }
            nodes { ... on Issue {
              id number title url state updatedAt
              milestone { title }
              assignees(first: 10) { nodes { login } }
              labels(first: 20) { nodes { name } }
              linkedBranches(first: 5) { nodes { ref { name } } }
              closedByPullRequestsReferences(first: 5) { nodes { headRefName state } }
            } }
          }
        }"""

    private const val DETAILS_QUERY = """
        query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
          repository(owner: ${'$'}owner, name: ${'$'}name) { issue(number: ${'$'}number) {
            body bodyHTML createdAt author { login }
            comments(first: 100) { nodes { body bodyHTML createdAt author { login } } }
          } }
        }"""

    private const val ADD_COMMENT = """
        mutation(${'$'}id: ID!, ${'$'}body: String!) {
          addComment(input: { subjectId: ${'$'}id, body: ${'$'}body }) { clientMutationId }
        }"""

    private val ORIGIN_URL = Regex("""github\.com[:/]([^/]+)/([^/]+?)(\.git)?/?$""")

    /** `owner/repo` from the `origin` remote of the open project, or `null`. */
    fun repoOf(project: Project): String? {
        val remotes = GitRepositoryManager.getInstance(project).repositories.flatMap { it.remotes }
        val url = remotes.firstOrNull { it.name == "origin" }?.firstUrl ?: return null
        val match = ORIGIN_URL.find(url) ?: return null
        return "${match.groupValues[1]}/${match.groupValues[2]}"
    }

    /**
     * All open issues of the repository, followed by all closed ones if requested.
     * Blocks, so call it in the background only.
     */
    fun load(project: Project, repo: String, onlyMine: Boolean, withClosed: Boolean, indicator: ProgressIndicator): List<GitHubIssue> {
        val account = account(project)
        val filter = "repo:$repo is:issue" + if (onlyMine) " assignee:@me" else ""
        val open = search(account, "$filter is:open", indicator)
        return if (withClosed) open + search(account, "$filter is:closed sort:updated-desc", indicator) else open
    }

    /** Fetches all results of a search, 100 per page. The GitHub search itself stops at 1000 results. */
    private fun search(account: GithubAccount, search: String, indicator: ProgressIndicator): List<GitHubIssue> {
        val issues = mutableListOf<GitHubIssue>()
        var after: String? = null
        do {
            indicator.checkCanceled()
            val page = query(account, QUERY, mapOf("q" to search, "after" to after), indicator).child("search")
            issues += page.nodes().filter { it["number"] != null }.map(::toIssue)
            after = if (page.child("pageInfo")["hasNextPage"] == true) page.child("pageInfo")["endCursor"] as String? else null
        } while (after != null)
        return issues
    }

    /**
     * Description and comments of an issue as Markdown, for the preview in the editor.
     * Blocks, so call it in the background only.
     */
    fun loadMarkdown(project: Project, repo: String, issue: GitHubIssue, indicator: ProgressIndicator): String {
        val (owner, name) = repo.split("/")
        val details = query(account(project), DETAILS_QUERY, mapOf("owner" to owner, "name" to name, "number" to issue.number), indicator)
            .child("repository").child("issue")

        fun byline(entry: Map<*, *>) =
            text("preview.byline", entry.child("author")["login"] ?: text("preview.unknownAuthor"), date(entry["createdAt"] as String))

        val facts = listOfNotNull(
            "[${text("preview.openOnGitHub")}](${issue.url})",
            text(if (issue.closed) "preview.closed" else "preview.open"),
            issue.milestone?.let { "Milestone $it" },
            issue.assignees.takeIf { it.isNotEmpty() }?.joinToString { "@$it" },
            issue.labels.takeIf { it.isNotEmpty() }?.joinToString { "`$it`" },
        )
        fun text(entry: Map<*, *>) = signImageUrls(entry["body"] as String, entry["bodyHTML"] as String)

        val comments = details.child("comments").nodes().joinToString("") { "\n\n---\n\n${byline(it)}\n\n${text(it)}" }
        return "# #${issue.number} ${issue.title}\n\n${facts.joinToString(" · ")}\n\n${byline(details)}\n\n${text(details)}$comments\n"
    }

    /**
     * Publishes a comment on the issue. Changes something on GitHub.
     * Blocks, so call it in the background only.
     */
    fun addComment(project: Project, issue: GitHubIssue, text: String, indicator: ProgressIndicator) {
        query(account(project), ADD_COMMENT, mapOf("id" to issue.id, "body" to text), indicator)
    }

    /**
     * Assigns the issue to the logged-in user as well, existing assignees stay.
     * Changes something on GitHub. Blocks, so call it in the background only.
     */
    fun assignToMe(project: Project, issue: GitHubIssue, indicator: ProgressIndicator) =
        changeMyAssignment("addAssigneesToAssignable", project, issue, indicator)

    /**
     * Removes the logged-in user as assignee of the issue, the others stay.
     * Changes something on GitHub. Blocks, so call it in the background only.
     */
    fun unassignMe(project: Project, issue: GitHubIssue, indicator: ProgressIndicator) =
        changeMyAssignment("removeAssigneesFromAssignable", project, issue, indicator)

    private fun changeMyAssignment(mutation: String, project: Project, issue: GitHubIssue, indicator: ProgressIndicator) {
        val account = account(project)
        val me = query(account, "query { viewer { id } }", emptyMap(), indicator).child("viewer")["id"] as String
        query(
            account,
            "mutation(\$id: ID!, \$me: ID!) { $mutation(input: { assignableId: \$id, assigneeIds: [\$me] }) { clientMutationId } }",
            mapOf("id" to issue.id, "me" to me),
            indicator,
        )
    }

    private fun date(iso: String): String =
        LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern(text("preview.datePattern")))

    private fun account(project: Project): GithubAccount =
        GHAccountsUtil.getSingleOrDefaultAccount(project)
            ?: error(text("error.noAccount"))

    /** Sends a GraphQL query through the Rider account and returns the content of `data`. */
    private fun query(account: GithubAccount, query: String, variables: Map<String, Any?>, indicator: ProgressIndicator): Map<*, *> {
        val request = GithubApiRequest.Post.Json(
            account.server.toGraphQLUrl(),
            mapOf("query" to query, "variables" to variables),
            // Map::class.java does not work: the GitHub plugin builds the type without type parameters and fails on
            // Map<K, V>. With Any, Jackson returns a Map for a JSON object by itself.
            Any::class.java,
        )
        val response = try {
            GithubApiRequestExecutor.Factory.getInstance().create(account).execute(indicator, request) as Map<*, *>
        } catch (e: GithubAuthenticationException) {
            // Token is missing or rejected by GitHub
            throw GitHubLoginNeeded(account, e)
        }
        response["errors"]?.let { error(text("error.github", it)) }
        return response.child("data")
    }

    private fun toIssue(issue: Map<*, *>): GitHubIssue {
        val prBranches = issue.child("closedByPullRequestsReferences").nodes()
            .filter { it["state"] == "OPEN" }
            .map { it["headRefName"] as String }
        val linkedBranches = issue.child("linkedBranches").nodes()
            .mapNotNull { it.child("ref")["name"] as String? }
        return GitHubIssue(
            id = issue["id"] as String,
            number = (issue["number"] as Number).toInt(),
            title = issue["title"] as String,
            url = issue["url"] as String,
            closed = issue["state"] == "CLOSED",
            milestone = issue.child("milestone")["title"] as String?,
            updatedAt = issue["updatedAt"] as String,
            assignees = issue.child("assignees").nodes().map { it["login"] as String },
            labels = issue.child("labels").nodes().map { it["name"] as String },
            branches = (prBranches + linkedBranches).distinct(),
        )
    }

    private fun Map<*, *>.child(key: String): Map<*, *> = this[key] as? Map<*, *> ?: emptyMap<Any, Any>()

    private fun Map<*, *>.nodes(): List<Map<*, *>> = (this["nodes"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
}
