package dev.mteterel.moonintellij.graph

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent
import javax.swing.JTextArea

enum class MoonGraphKind(val displayName: String) {
    Action("Action Graph"),
    Project("Project Graph"),
    Task("Task Graph"),
}

enum class MoonGraphFocusType(val label: String) {
    Project("project"),
    Task("task"),
}

data class MoonGraphFocus(
    val type: MoonGraphFocusType,
    val value: String,
)

class MoonGraphVirtualFile(
    val kind: MoonGraphKind,
    focus: MoonGraphFocus? = null,
    initialHtml: String,
) : LightVirtualFile(kind.displayName) {
    private val changeSupport = PropertyChangeSupport(this)
    var focus: MoonGraphFocus? = focus
        private set
    var html: String = initialHtml
        private set

    init {
        isWritable = false
    }

    override fun getPath(): String = "moon-graph://${kind.name.lowercase()}"

    fun updateContent(newFocus: MoonGraphFocus?, newHtml: String) {
        focus = newFocus
        val oldHtml = html
        html = newHtml
        changeSupport.firePropertyChange("html", oldHtml, newHtml)
    }

    fun addHtmlListener(listener: PropertyChangeListener) {
        changeSupport.addPropertyChangeListener("html", listener)
    }

    fun removeHtmlListener(listener: PropertyChangeListener) {
        changeSupport.removePropertyChangeListener("html", listener)
    }
}

object MoonGraphEditorManager {
    fun open(project: Project, kind: MoonGraphKind, focus: MoonGraphFocus? = null) {
        val command = buildCommand(kind, focus)
        val fileEditorManager = FileEditorManager.getInstance(project)
        val file = fileEditorManager.openFiles
            .filterIsInstance<MoonGraphVirtualFile>()
            .firstOrNull { it.kind == kind }
            ?: MoonGraphVirtualFile(kind, focus, MoonGraphHtml.loading(kind, focus, command)).also {
                fileEditorManager.openFile(it, true)
            }

        file.updateContent(focus, MoonGraphHtml.loading(kind, focus, command))
        fileEditorManager.openFile(file, true)

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = loadGraphJson(project, command)
            val html = MoonGraphHtml.render(kind, focus, command, result)
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && file.isValid) {
                    file.updateContent(focus, html)
                }
            }
        }
    }

    private fun buildCommand(kind: MoonGraphKind, focus: MoonGraphFocus?): List<String> =
        buildList {
            add("moon")
            add(
                when (kind) {
                    MoonGraphKind.Action -> "action-graph"
                    MoonGraphKind.Project -> "project-graph"
                    MoonGraphKind.Task -> "task-graph"
                }
            )

            when (kind) {
                MoonGraphKind.Project -> {
                    if (focus?.type == MoonGraphFocusType.Project) {
                        add(focus.value)
                    }
                }
                MoonGraphKind.Task -> {
                    if (focus?.type == MoonGraphFocusType.Task) {
                        add(focus.value)
                    }
                }
                MoonGraphKind.Action -> {
                    if (focus != null) {
                        add(focus.value)
                    }
                }
            }

            add("--json")
        }

    private fun loadGraphJson(project: Project, command: List<String>): GraphLoadResult {
        val workingDirectory = project.basePath ?: return GraphLoadResult.Error("Project has no base path.")
        val commandLine = GeneralCommandLine(command)
            .withWorkDirectory(workingDirectory)
            .withCharset(Charsets.UTF_8)
            .withRedirectErrorStream(true)
        val output = CapturingProcessHandler(commandLine).runProcess(30_000)

        return if (output.exitCode == 0) {
            GraphLoadResult.Success(output.stdout.trim())
        } else {
            GraphLoadResult.Error(output.stdout.ifBlank { "Command failed with exit code ${output.exitCode}." }.trim())
        }
    }
}

class MoonGraphFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is MoonGraphVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        MoonGraphFileEditor(file as MoonGraphVirtualFile)

    override fun getEditorTypeId(): String = "moon-graph-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class MoonGraphFileEditor(
    private val file: MoonGraphVirtualFile,
) : UserDataHolderBase(), FileEditor, Disposable {
    private val htmlListener = PropertyChangeListener {
        if (browser != null) {
            browser.loadHTML(file.html)
        } else {
            fallbackTextArea.text = file.html
            fallbackTextArea.caretPosition = 0
        }
    }

    private val browser = if (JBCefApp.isSupported()) JBCefBrowser() else null

    private val fallbackTextArea = JTextArea(file.html).apply {
        isEditable = false
        border = JBUI.Borders.empty(24)
        background = UIUtil.getPanelBackground()
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
    }

    private val component: JComponent =
        browser?.getComponent() ?: JBScrollPane(fallbackTextArea).apply {
            border = JBUI.Borders.empty()
            viewport.background = UIUtil.getPanelBackground()
        }

    init {
        browser?.loadHTML(file.html)
        file.addHtmlListener(htmlListener)
    }

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent = browser?.component ?: fallbackTextArea

    override fun getName(): String = file.kind.displayName

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() {
        file.removeHtmlListener(htmlListener)
        browser?.dispose()
    }
}

object MoonGraphHtml {
    fun loading(
        kind: MoonGraphKind,
        focus: MoonGraphFocus? = null,
        command: List<String>,
    ): String {
        return renderBody(
            kind = kind,
            focus = focus,
            command = command,
            payload = "Loading graph JSON...",
        )
    }

    fun render(
        kind: MoonGraphKind,
        focus: MoonGraphFocus? = null,
        command: List<String>,
        result: GraphLoadResult,
    ): String {
        val payload = when (result) {
            is GraphLoadResult.Success -> result.json.ifBlank { "{}" }
            is GraphLoadResult.Error -> result.message
        }

        return renderBody(kind, focus, command, payload)
    }

    private fun renderBody(
        kind: MoonGraphKind,
        focus: MoonGraphFocus? = null,
        command: List<String>,
        payload: String,
    ): String {
        return """
            <!DOCTYPE html>
            <html>
              <head>
              <meta charset="UTF-8" />
              		<meta name="viewport" content="width=device-width, initial-scale=1.0" />
              		<link rel="preconnect" href="https://fonts.googleapis.com" />
              		<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
              		<link
              			href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:ital,wght@0,200;0,300;0,400;0,500;0,600;0,700;0,800;1,400;1,500&display=swap"
              			rel="stylesheet"
              		/>
              		<script type="module" src="https://unpkg.com/@vscode/webview-ui-toolkit@latest"></script>
              		<script type="module" src="https://unpkg.com/@moonrepo/visualizer@latest"></script>
                <style>
                body {
                	padding: 0;
                	margin: 0;
                }

                .body {
                	padding: 14px 20px;
                }

                table {
                	border: 1px solid transparent;
                	border-collapse: collapse;
                	width: 100%;
                }

                td {
                	padding: 6px;
                	padding-left: 0;
                	border: 1px solid transparent;
                	border-bottom: 1px solid var(--divider-background);
                	vertical-align: top;
                }

                td:last-child {
                	padding-right: 0;
                }

                .action-label {
                	font-weight: bold;
                	color: var(--list-active-selection-foreground);
                }

                .action-icon {
                	padding-top: 3px;
                }
                </style>
              </head>
              <body class="dark bg-slate-800 text-gray-50">
                <script>
		            window.PAGE_TITLE = ${toJavaScriptStringLiteral(kind.displayName)};
                    window.GRAPH_DATA = ${toJavaScriptStringLiteral(payload)};
		        </script>
                <div id="app"></div>
              </body>
            </html>
        """.trimIndent()
    }

    private fun escape(value: String): String =
        buildString(value.length) {
            value.forEach { character ->
                append(
                    when (character) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&#39;"
                        else -> character
                    }
                )
            }
        }

    private fun toJavaScriptStringLiteral(value: String): String =
        buildString(value.length + 2) {
            append('"')
            value.forEach { character ->
                append(
                    when (character) {
                        '\\' -> "\\\\"
                        '"' -> "\\\""
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        '\t' -> "\\t"
                        '\u2028' -> "\\u2028"
                        '\u2029' -> "\\u2029"
                        else -> character
                    }
                )
            }
            append('"')
        }
}

sealed interface GraphLoadResult {
    data class Success(val json: String) : GraphLoadResult
    data class Error(val message: String) : GraphLoadResult
}
