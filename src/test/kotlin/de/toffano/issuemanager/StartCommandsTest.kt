package de.toffano.issuemanager

import org.junit.Assert.assertEquals
import org.junit.Test

class StartCommandsTest {

    @Test
    fun `placeholders are filled, unknown ones stay`() {
        assertEquals(
            "Issue #13: Offer export as PDF {unknown}",
            renderPrompt("Issue #{number}: {title} {unknown}", mapOf("number" to "13", "title" to "Offer export as PDF")),
        )
    }

    @Test
    fun `a placeholder inside an inserted value is not filled again`() {
        assertEquals(
            "See {title} and {number}",
            renderPrompt("{content}", mapOf("content" to "See {title} and {number}", "title" to "Title", "number" to "13")),
        )
    }
}
