package dev.mteterel.moonintellij.toolWindow

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import javax.swing.Icon

class CommandLineRunProfile(
    private val commandLine: GeneralCommandLine,
    private val title: String
) : RunProfile {

    override fun getState(executor: com.intellij.execution.Executor, environment: ExecutionEnvironment): RunProfileState {
        return object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                val handler = ColoredProcessHandler(commandLine)
                ProcessTerminatedListener.attach(handler)
                return handler
            }
        }
    }

    override fun getName(): String = title
    override fun getIcon(): Icon? = null
}
