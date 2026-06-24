package dev.mteterel.moonintellij.runconfiguration

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

class MoonRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "MoonRunConfiguration"

    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return MoonRunConfiguration(project, this, "Moon Task")
    }
}
