package de.toffano.issuemanager

import java.util.Locale
import java.util.ResourceBundle

/**
 * Texts of the user interface. The language follows the system language, not the language of Rider: Rider ships
 * without a German language pack, so a German system gets German texts and every other system gets English ones.
 */
private val bundle: ResourceBundle = ResourceBundle.getBundle(
    "messages.IssueManager",
    if (Locale.getDefault().language == "de") Locale.GERMAN else Locale.ROOT,
)

/** The text for [key] from `messages/IssueManager.properties`, with [args] filled in for `%s`. */
fun text(key: String, vararg args: Any?): String = bundle.getString(key).format(*args)
