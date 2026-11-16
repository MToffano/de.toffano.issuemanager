package de.toffano.issuemanager

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.TableUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** Settings | Tools | IssueManager: the commands of the "Start with" menu and the prompt templates. */
class IssueManagerConfigurable : Configurable {

    private val commands = ListTableModel<StartCommand>(
        column<StartCommand>(text("settings.name"), { it.name }) { command, value -> command.name = value },
        column<StartCommand>(text("settings.command"), { it.command }) { command, value -> command.command = value },
        // A Boolean column is shown as a checkbox
        object : ColumnInfo<StartCommand, Boolean>(text("settings.switchBranch")) {
            override fun valueOf(item: StartCommand): Boolean = item.switchBranch
            override fun getColumnClass(): Class<*> = Boolean::class.javaObjectType
            override fun isCellEditable(item: StartCommand): Boolean = true
            override fun setValue(item: StartCommand, value: Boolean) {
                item.switchBranch = value
            }
        },
    )
    private val commandTable = TableView(commands)

    private val prompts = ListTableModel<PromptTemplate>(
        column<PromptTemplate>(text("settings.name"), { it.name }) { prompt, value -> prompt.name = value },
    )
    private val promptTable = TableView(prompts)
    /** Text of the template selected in [promptTable] */
    private val promptText = JBTextArea(8, 60)

    // Read here, because inside a row `text(…)` means a function of the layout builder
    private val commandsTitle = text("settings.commands")
    private val commandsHint = text("settings.commands.hint")
    private val promptsTitle = text("settings.prompts")
    private val promptsHint = text("settings.prompts.hint")

    init {
        promptTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        promptTable.selectionModel.addListSelectionListener { showSelectedPrompt() }
        promptText.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                promptTable.selectedObject?.text = promptText.text
            }
        })
    }

    override fun getDisplayName(): String = "IssueManager"

    override fun createComponent(): JComponent = panel {
        group(commandsTitle) {
            row { cell(editableList(commandTable, commands) { StartCommand() }).align(AlignX.FILL) }
            row { comment(commandsHint) }
        }
        group(promptsTitle) {
            row { cell(editableList(promptTable, prompts) { PromptTemplate() }).align(AlignX.FILL) }
            row { cell(JBScrollPane(promptText)).align(AlignX.FILL) }
            row { comment(promptsHint) }
        }
    }

    override fun isModified(): Boolean {
        val settings = IssueManagerSettings.get()
        return commands.items != settings.commands || prompts.items != settings.prompts
    }

    override fun apply() {
        TableUtil.stopEditing(commandTable)
        TableUtil.stopEditing(promptTable)
        val named = prompts.items.filter { it.name.isNotBlank() }
        // Without a template the "Start with" menu has nothing to offer
        if (named.isEmpty()) throw ConfigurationException(text("settings.prompts.empty"))
        val settings = IssueManagerSettings.get()
        settings.commands = commands.items.filter { it.name.isNotBlank() && it.command.isNotBlank() }.map { it.copy() }.toMutableList()
        settings.prompts = named.map { it.copy() }.toMutableList()
        // Shows what was saved: rows without a name or a command are gone. The selected template stays selected.
        val selected = promptTable.selectedRow
        reset()
        if (selected in 0 until prompts.rowCount) promptTable.setRowSelectionInterval(selected, selected)
    }

    override fun reset() {
        val settings = IssueManagerSettings.get()
        // Copies, so that edits only reach the settings on "Apply"
        commands.items = settings.commands.map { it.copy() }
        prompts.items = settings.prompts.map { it.copy() }
        if (prompts.rowCount > 0) promptTable.setRowSelectionInterval(0, 0)
        showSelectedPrompt()
    }

    private fun showSelectedPrompt() {
        val selected = promptTable.selectedObject
        promptText.isEnabled = selected != null
        // Only on a real change: setting the text moves the cursor to the end
        if (promptText.text != selected?.text.orEmpty()) promptText.text = selected?.text.orEmpty()
    }

    /** The table with buttons to add, remove and move rows. A new row is selected and its name is edited right away. */
    private fun <T : Any> editableList(table: TableView<T>, model: ListTableModel<T>, create: () -> T): JComponent =
        ToolbarDecorator.createDecorator(table)
            .setAddAction {
                model.addRow(create())
                val row = model.rowCount - 1
                table.setRowSelectionInterval(row, row)
                TableUtil.editCellAt(table, row, 0)
            }
            .createPanel()

    private fun <T : Any> column(title: String, get: (T) -> String, set: (T, String) -> Unit) =
        object : ColumnInfo<T, String>(title) {
            override fun valueOf(item: T): String = get(item)
            override fun isCellEditable(item: T): Boolean = true
            override fun setValue(item: T, value: String) = set(item, value)
        }
}
