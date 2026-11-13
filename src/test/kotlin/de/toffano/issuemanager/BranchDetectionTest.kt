package de.toffano.issuemanager

import org.junit.Assert.assertEquals
import org.junit.Test

class BranchDetectionTest {

    private fun issue(
        number: Int,
        vararg branches: String,
        title: String = "Title",
        labels: List<String> = emptyList(),
    ) = GitHubIssue(
        "id$number", number, title, "https://example.invalid/$number", false, null, "2026-11-13T00:00:00Z", emptyList(), labels, branches.toList(),
    )

    @Test
    fun `feature, fix and hotfix with the number match`() {
        val branches = listOf("main", "feature/13-export-pdf", "fix/13-x", "hotfix/13", "feature/13")
        assertEquals(branches - "main", detectBranches(issue(13), branches))
    }

    @Test
    fun `number 13 does not match 130 or 113`() {
        val branches = listOf("feature/130-x", "fix/113-x", "feature/1-x", "bugfix/13-x", "feature/13x")
        assertEquals(emptyList<String>(), detectBranches(issue(13), branches))
    }

    @Test
    fun `old names issue123 and hotfix-issue123 still match`() {
        assertEquals(listOf("issue123", "hotfix/issue123"), detectBranches(issue(123), listOf("main", "issue123", "hotfix/issue123")))
    }

    @Test
    fun `issue12 does not match issue123`() {
        assertEquals(emptyList<String>(), detectBranches(issue(12), listOf("issue123", "hotfix/issue123", "issue1")))
    }

    @Test
    fun `branch of an open pull request with a free name`() {
        assertEquals(listOf("sprint-12"), detectBranches(issue(400, "sprint-12"), listOf("main", "sprint-12")))
    }

    @Test
    fun `naming rule comes before the pull request branch`() {
        assertEquals(
            listOf("fix/400-export", "sprint-12"),
            detectBranches(issue(400, "sprint-12"), listOf("sprint-12", "fix/400-export")),
        )
    }

    @Test
    fun `local and on origin gives one match`() {
        assertEquals(listOf("issue123"), detectBranches(issue(123, "issue123"), listOf("issue123", "issue123")))
    }

    @Test
    fun `nothing found`() {
        assertEquals(emptyList<String>(), detectBranches(issue(123), listOf("main", "feature/x")))
    }

    @Test
    fun `slug takes four words, lower case, without umlauts and special characters`() {
        assertEquals("offer-export-as-pdf", branchSlug("Offer export as PDF and print it"))
        assertEquals("login-fails-after-timeout", branchSlug("Login - fails after timeout again"))
        assertEquals("aenderungsverfolgung-groesse-fuer-strasse", branchSlug("Änderungsverfolgung: Größe für Straße „Test“"))
        assertEquals("", branchSlug("???"))
    }

    @Test
    fun `suggestions start with feature when there is no bug label`() {
        assertEquals(
            listOf("feature/16-speed-up-search", "fix/16-speed-up-search", "hotfix/16-speed-up-search"),
            branchNameSuggestions(issue(16, title = "Speed up search", labels = listOf("enhancement"))),
        )
    }

    @Test
    fun `suggestions start with fix when there is a bug label`() {
        val names = branchNameSuggestions(issue(21, title = "Crash on save", labels = listOf("type: bugfix")))
        assertEquals(listOf("fix/21-crash-on-save", "feature/21-crash-on-save", "hotfix/21-crash-on-save"), names)
    }

    @Test
    fun `image URLs are replaced by the signed ones from the HTML`() {
        val id = "11111111-2222-3333-4444-555555555555"
        val other = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val markdown = """Text <img width="921" src="https://github.com/user-attachments/assets/$id" /> and """ +
            "![Image](https://github.com/user-attachments/assets/$other)"
        val html = """<p><a href="x"><img width="921" src="https://private-user-images.githubusercontent.com/1/2-$id.png?jwt=abc&amp;x=1"></a></p>"""

        assertEquals(
            """Text <img width="921" src="https://private-user-images.githubusercontent.com/1/2-$id.png?jwt=abc&x=1" /> and """ +
                "![Image](https://github.com/user-attachments/assets/$other)",
            signImageUrls(markdown, html),
        )
    }

    @Test
    fun `suggestion without a usable title consists of the number only`() {
        assertEquals(listOf("feature/7", "fix/7", "hotfix/7"), branchNameSuggestions(issue(7, title = "!!!")))
    }
}
