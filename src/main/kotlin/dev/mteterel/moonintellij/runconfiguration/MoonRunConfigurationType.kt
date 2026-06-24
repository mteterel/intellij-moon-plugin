package dev.mteterel.moonintellij.runconfiguration

import dev.mteterel.moonintellij.toolWindow.MoonConsoleIcons
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import javax.swing.Icon

class MoonRunConfigurationType : ConfigurationType {
    override fun getDisplayName(): String = "Moon Task"

    override fun getConfigurationTypeDescription(): String = "Run Moon tasks from the current project."

    override fun getId(): String = "MoonRunConfigurationType"

    override fun getIcon(): Icon = MoonConsoleIcons.MoonIconPurple

    private val factory = MoonRunConfigurationFactory(this)

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)

    companion object {
        fun getInstance(): MoonRunConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(MoonRunConfigurationType::class.java)
    }
}
