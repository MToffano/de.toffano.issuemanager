package de.toffano.issuemanager

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Properties

class TextsTest {

    private fun keys(file: String): Set<Any> =
        Properties().apply { File("src/main/resources/messages/$file").reader().use(::load) }.keys

    @Test
    fun `English and German texts have the same keys`() {
        assertEquals(keys("IssueManager.properties"), keys("IssueManager_de.properties"))
    }
}
