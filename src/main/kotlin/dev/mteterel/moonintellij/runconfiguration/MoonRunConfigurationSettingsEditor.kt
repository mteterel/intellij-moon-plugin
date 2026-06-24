package dev.mteterel.moonintellij.runconfiguration

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class MoonRunConfigurationSettingsEditor : SettingsEditor<MoonRunConfiguration>() {
    private val moonExecutableField = JBTextField()
    private val taskTargetField = JBTextField()
    private val editorPanel = panel {
        row("Moon executable:") {
            cell(moonExecutableField)
                .align(AlignX.FILL)
        }
        row("Task target:") {
            cell(taskTargetField)
                .align(AlignX.FILL)
        }
    }

    override fun resetEditorFrom(configuration: MoonRunConfiguration) {
        moonExecutableField.text = configuration.moonExecutable
        taskTargetField.text = configuration.taskTarget
    }

    @Throws(ConfigurationException::class)
    override fun applyEditorTo(configuration: MoonRunConfiguration) {
        configuration.moonExecutable = moonExecutableField.text.trim()
        configuration.taskTarget = taskTargetField.text.trim()
    }

    override fun createEditor(): JComponent = editorPanel

    override fun disposeEditor() = Unit
}
