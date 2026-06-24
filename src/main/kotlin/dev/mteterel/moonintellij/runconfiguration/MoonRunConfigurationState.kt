package dev.mteterel.moonintellij.runconfiguration

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment

class MoonRunConfigurationState(
    private val environment: ExecutionEnvironment,
    private val moonExecutable: String,
    private val taskTarget: String,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val commandLine = GeneralCommandLine(listOf(moonExecutable, "run", taskTarget))
            .withWorkDirectory(environment.project.basePath)
            .withCharset(Charsets.UTF_8)
            .withRedirectErrorStream(true)
            .also {
                it.environment["FORCE_COLOR"] = "1"
            }

        val handler = ColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}
