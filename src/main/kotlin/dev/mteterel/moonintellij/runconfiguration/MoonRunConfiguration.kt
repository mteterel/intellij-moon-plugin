package dev.mteterel.moonintellij.runconfiguration

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import org.jdom.Element

class MoonRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<com.intellij.execution.configurations.RunConfigurationOptions>(project, factory, name) {

    var moonExecutable: String = "moon"
    var taskTarget: String = ""

    override fun getConfigurationEditor(): SettingsEditor<MoonRunConfiguration> = MoonRunConfigurationSettingsEditor()

    @Throws(ExecutionException::class)
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        MoonRunConfigurationState(environment, moonExecutable, taskTarget)

    override fun checkConfiguration() {
        if (moonExecutable.isBlank()) {
            throw RuntimeConfigurationError("Moon executable is required")
        }

        if (taskTarget.isBlank()) {
            throw RuntimeConfigurationError("Moon task target is required")
        }
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        moonExecutable = element.getAttributeValue("moonExecutable", "moon")
        taskTarget = element.getAttributeValue("taskTarget", "")
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("moonExecutable", moonExecutable)
        element.setAttribute("taskTarget", taskTarget)
    }
}
