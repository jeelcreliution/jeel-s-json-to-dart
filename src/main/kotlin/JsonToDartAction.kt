package com.example.jeels_json_to_dart

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel

// =============================================================================
//  JsonToDartAction
//  Right-click a folder → New → Dart Model from JSON
// =============================================================================
class JsonToDartAction : AnAction() {

    // Required in IntelliJ 2022.3+ — runs update() on a background thread
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    // Only show the action when the right-clicked item is a directory
    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file?.isDirectory == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project   = e.project ?: return
        val directory = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?.takeIf { it.isDirectory }
            ?: run {
                Messages.showErrorDialog(project, "Please right-click a folder.", "Dart Model from JSON")
                return
            }

        // ── Show dialog ───────────────────────────────────────────────────
        val dialog = JsonToDartDialog(project)
        if (!dialog.showAndGet()) return

        val jsonText  = dialog.jsonText.trim()
        val rootClass = dialog.rootClassName.trim().ifEmpty { "RootModel" }
        val options   = dialog.options

        if (jsonText.isEmpty()) {
            Messages.showErrorDialog(project, "JSON input is empty.", "Dart Model from JSON")
            return
        }

        // ── Convert JSON → Dart ───────────────────────────────────────────
        val dartCode: String
        try {
            dartCode = JsonToDartConverter.convert(jsonText, rootClass, options)
        } catch (ex: Exception) {
            Messages.showErrorDialog(
                project,
                "Failed to parse JSON:\n${ex.message}",
                "Dart Model from JSON"
            )
            return
        }

        // ── Create file inside the selected directory ──────────────────────
        val fileName = toSnakeCase(rootClass) + ".dart"

        WriteCommandAction.runWriteCommandAction(project, "Generate Dart Model", null, Runnable {
            try {
                val vFile = directory.createChildData(this, fileName)
                VfsUtil.saveText(vFile, dartCode)
                FileEditorManager.getInstance(project).openFile(vFile, true)
            } catch (ex: Exception) {
                Messages.showErrorDialog(
                    project,
                    "Could not create '$fileName':\n${ex.message}",
                    "Dart Model from JSON"
                )
            }
        })
    }

    private fun toSnakeCase(s: String): String =
        s.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
         .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
         .lowercase()
}

// =============================================================================
//  JsonToDartDialog
// =============================================================================
private class JsonToDartDialog(project: Project) : DialogWrapper(project) {

    var jsonText:      String                      = ""
        private set
    var rootClassName: String                      = "RootModel"
        private set
    var options:       JsonToDartConverter.Options = JsonToDartConverter.Options()
        private set

    private val jsonArea = JBTextArea(18, 60).apply {
        lineWrap      = true
        wrapStyleWord = true
        font          = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13)
        toolTipText   = "Paste your JSON here"
    }
    private val classNameField  = JBTextField("RootModel", 30)
    private val chkToJson       = JBCheckBox("Generate toJson()",               true)
    private val chkNullSafe     = JBCheckBox("Null-safe type checks",            true)
    private val chkSnakeToCamel = JBCheckBox("Convert snake_case to camelCase", true)

    init {
        title = "Dart Model from JSON"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc   = GridBagConstraints().apply {
            insets = Insets(5, 6, 5, 6)
            fill   = GridBagConstraints.HORIZONTAL
        }

        // Row 0 — class name
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.WEST
        panel.add(JBLabel("Root class name:"), gbc)
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0
        panel.add(classNameField, gbc)

        // Row 1 — JSON area
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.NORTHWEST
        panel.add(JBLabel("JSON:"), gbc)
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0; gbc.weighty = 1.0
        gbc.fill  = GridBagConstraints.BOTH
        panel.add(
            JBScrollPane(jsonArea).apply {
                preferredSize = Dimension(660, 340)
                minimumSize   = Dimension(480, 200)
            }, gbc
        )

        // Row 2 — checkboxes
        val optPanel = JPanel(GridBagLayout())
        val ogbc = GridBagConstraints().apply { anchor = GridBagConstraints.WEST; insets = Insets(2, 0, 2, 0) }
        ogbc.gridy = 0; optPanel.add(chkToJson,       ogbc)
        ogbc.gridy = 1; optPanel.add(chkNullSafe,     ogbc)
        ogbc.gridy = 2; optPanel.add(chkSnakeToCamel, ogbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2
        gbc.weightx = 1.0; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(optPanel, gbc)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = jsonArea

    override fun doOKAction() {
        jsonText      = jsonArea.text
        rootClassName = classNameField.text.trim().ifEmpty { "RootModel" }
        options = JsonToDartConverter.Options(
            generateToJson = chkToJson.isSelected,
            nullSafeChecks = chkNullSafe.isSelected,
            snakeToCamel   = chkSnakeToCamel.isSelected
        )
        super.doOKAction()
    }
}
