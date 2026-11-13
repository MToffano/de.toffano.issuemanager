package de.toffano.issuemanager

private val BUG_LABELS = setOf("bug", "type: bugfix")

/**
 * Suggestions for a new branch for the issue: `feature/13-short-title`, `fix/13-short-title`,
 * `hotfix/13-short-title`. If the issue has a bug label, `fix/` comes first, otherwise `feature/`. `hotfix/` always
 * comes last, it is meant for repositories whose pull request workflow treats such branches separately.
 */
fun branchNameSuggestions(issue: GitHubIssue): List<String> {
    val slug = branchSlug(issue.title)
    val name = if (slug.isEmpty()) "${issue.number}" else "${issue.number}-$slug"
    val prefixes = if (issue.labels.any { it in BUG_LABELS }) listOf("fix", "feature", "hotfix") else listOf("feature", "fix", "hotfix")
    return prefixes.map { "$it/$name" }
}

/** The first words of the title, in lower case, joined by hyphens, without umlauts and special characters. */
fun branchSlug(title: String, words: Int = 4): String =
    title.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() }
        .take(words)
        .joinToString("-")

/**
 * Branches that probably belong to an issue, in this order:
 * first the naming rules (`feature/123-…`, `fix/123-…`, `hotfix/123-…`, and the older `issue123` and
 * `hotfix/issue123`), then the branches GitHub knows for the issue.
 *
 * @param branchNames all local branches and those of `origin`, without the leading `origin/`
 */
fun detectBranches(issue: GitHubIssue, branchNames: Collection<String>): List<String> {
    val byName = Regex("""(feature|fix|hotfix)/${issue.number}(-.*)?|(hotfix/)?issue${issue.number}""")
    return (branchNames.filter(byName::matches) + issue.branches).distinct()
}
