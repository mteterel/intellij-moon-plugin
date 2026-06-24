package dev.mteterel.moonintellij.services

import dev.mteterel.moonintellij.toolWindow.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.EventDispatcher
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class MyProjectService(private val project: Project) {
    private val dispatcher = EventDispatcher.create(WorkspaceRefreshListener::class.java)
    private val refreshQueued = AtomicBoolean(false)

    @Volatile
    private var snapshot = WorkspaceRefreshSnapshot(
        workspaceInitialized = hasMoonWorkspace(),
        workspaceState = WorkspaceState.placeholder(),
        refreshInProgress = false,
        refreshErrorMessage = null,
    )

    init {
        if (snapshot.workspaceInitialized) {
            requestWorkspaceRefresh()
        }
    }

    fun getSnapshot(): WorkspaceRefreshSnapshot = snapshot

    fun addListener(disposable: com.intellij.openapi.Disposable, listener: WorkspaceRefreshListener) {
        dispatcher.addListener(listener, disposable)
        listener.workspaceChanged(snapshot)
    }

    fun requestWorkspaceRefresh() {
        if (snapshot.refreshInProgress) {
            refreshQueued.set(true)
            return
        }

        updateSnapshot(snapshot.copy(refreshInProgress = true, refreshErrorMessage = null))
        refreshQueued.set(false)

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Refreshing moon workspace", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Fetching moon projects"
                        val projectsOutput = requireNotNull(runMoonJsonCommand(listOf("moon", "projects", "--json"), required = true))

                        indicator.text = "Fetching moon tasks"
                        val tasksOutput = requireNotNull(runMoonJsonCommand(listOf("moon", "tasks", "--json"), required = true))

                        indicator.text = "Fetching moon templates"
                        val templatesOutput = runMoonJsonCommand(listOf("moon", "templates", "--json"), required = false)

                        val nextState = buildWorkspaceState(
                            projectsJson = projectsOutput.stdout,
                            tasksJson = tasksOutput.stdout,
                            templatesJson = templatesOutput?.stdout.orEmpty(),
                        )

                        ToolWindowManager.getInstance(project).invokeLater {
                            updateSnapshot(
                                snapshot.copy(
                                    workspaceInitialized = true,
                                    workspaceState = nextState,
                                    refreshInProgress = false,
                                    refreshErrorMessage = null,
                                )
                            )
                            if (refreshQueued.getAndSet(false)) {
                                requestWorkspaceRefresh()
                            }
                        }
                    } catch (error: Exception) {
                        ToolWindowManager.getInstance(project).invokeLater {
                            updateSnapshot(
                                snapshot.copy(
                                    workspaceInitialized = hasMoonWorkspace(),
                                    refreshInProgress = false,
                                    refreshErrorMessage = error.message ?: error::class.java.simpleName,
                                )
                            )
                            if (refreshQueued.getAndSet(false)) {
                                requestWorkspaceRefresh()
                            }
                        }
                    }
                }
            }
        )
    }

    private fun updateSnapshot(nextSnapshot: WorkspaceRefreshSnapshot) {
        snapshot = nextSnapshot
        val publishedSnapshot = nextSnapshot
        ToolWindowManager.getInstance(project).invokeLater {
            dispatcher.multicaster.workspaceChanged(publishedSnapshot)
        }
    }

    private fun hasMoonWorkspace(): Boolean {
        val basePath = project.basePath ?: return false
        return Files.exists(Paths.get(basePath, ".moon"))
    }

    private fun createCommandLine(command: List<String>, forceColor: Boolean) =
        com.intellij.execution.configurations.GeneralCommandLine(command)
            .withWorkDirectory(project.basePath)
            .withCharset(Charsets.UTF_8)
            .withRedirectErrorStream(true)
            .also {
                if (forceColor) {
                    it.environment["FORCE_COLOR"] = "1"
                }
            }

    private fun runMoonJsonCommand(command: List<String>, required: Boolean): CommandResult? {
        val output = CapturingProcessHandler(createCommandLine(command, forceColor = false)).runProcess()
        if (output.exitCode == 0) {
            return CommandResult(output.stdout.trim())
        }

        if (required) {
            throw IllegalStateException(
                buildString {
                    append(command.joinToString(" "))
                    append(" failed with exit code ")
                    append(output.exitCode)
                    if (output.stderr.isNotBlank()) {
                        append("\n")
                        append(output.stderr.trim())
                    }
                }
            )
        }

        return null
    }

    private fun buildWorkspaceState(projectsJson: String, tasksJson: String, templatesJson: String): WorkspaceState {
        val projectGroups = parseProjects(projectsJson)
        val tasksByTarget = parseTasks(tasksJson)
        val templates = runCatching { parseTemplates(templatesJson) }.getOrDefault(emptyList())

        return WorkspaceState(
            projectGroups = projectGroups,
            tasksByTarget = tasksByTarget,
            templates = templates,
        )
    }

    private fun parseProjects(json: String): List<ProjectGroup> {
        val root = JsonParser.parseString(json)
        val projectElements = if (root.isJsonArray) root.asJsonArray else return emptyList()

        val projects = projectElements.mapNotNull { parseProject(it) }
        if (projects.isEmpty()) return emptyList()

        return projects
            .groupBy { it.groupName }
            .toSortedMap()
            .map { (groupName, groupedProjects) ->
                ProjectGroup(
                    name = groupName,
                    projects = groupedProjects.map { parsed ->
                        dev.mteterel.moonintellij.toolWindow.MoonProject(
                            name = parsed.name,
                            tags = parsed.tags,
                            language = parsed.language,
                            layer = parsed.layer,
                            stack = parsed.stack,
                            projectInfo = parsed.projectInfo,
                            taskTargets = parsed.taskTargets,
                            groupName = parsed.groupName,
                            projectRootPath = parsed.projectRootPath,
                            projectFilePath = parsed.projectFilePath,
                        )
                    },
                )
            }
    }

    private fun parseTasks(json: String): Map<String, dev.mteterel.moonintellij.toolWindow.MoonTask> {
        val root = JsonParser.parseString(json)
        val taskElements = if (root.isJsonArray) root.asJsonArray else return emptyMap()

        return taskElements.mapNotNull { parseTask(it) }.associateBy { it.target }
    }

    private fun parseTemplates(json: String): List<MoonTemplate> {
        if (json.isBlank()) return emptyList()

        val root = JsonParser.parseString(json)
        val templates = if (root.isJsonObject) root.asJsonObject else return emptyList()
        return templates.entrySet().mapNotNull { (templateId, templateElement) ->
            parseTemplate(templateElement, templateId)
        }
    }

    private fun parseProject(element: JsonElement): dev.mteterel.moonintellij.toolWindow.ParsedProject? {
        val obj = element.asJsonObjectOrNull() ?: return null
        val name = obj.stringValue("id") ?: return null
        val language = obj.stringValue("language") ?: "unknown"
        val tags = extractProjectTags(obj)
        val layer = obj.stringValue("layer")
        val stack = obj.stringValue("stack")
        val projectRootPath = obj.stringValue("root")
        val config = obj.objectValue("config")
        val projectMetadata = config?.objectValue("project") ?: obj.objectValue("project")
        val projectInfo = ProjectInfo(
            title = projectMetadata?.stringValue("title"),
            description = projectMetadata?.stringValue("description"),
            rootFile = null,
        )
        val taskTargets = obj.stringArray("taskTargets")

        return ParsedProject(
            name = name,
            groupName = buildProjectGroupName(projectRootPath),
            language = language,
            tags = tags,
            layer = layer,
            stack = stack,
            projectInfo = projectInfo,
            taskTargets = taskTargets,
            projectRootPath = projectRootPath,
            projectFilePath = resolveProjectFilePath(projectRootPath),
        )
    }

    private fun buildProjectGroupName(projectRootPath: String?): String {
        val rootPath = projectRootPath?.takeIf { it.isNotBlank() } ?: return "Projects"
        val basePath = project.basePath ?: return "Projects"
        val baseDirectory = Paths.get(basePath).toAbsolutePath().normalize()
        val resolvedRootPath = runCatching { Paths.get(rootPath) }.getOrNull() ?: return "Projects"
        val resolvedPath = if (resolvedRootPath.isAbsolute) resolvedRootPath else baseDirectory.resolve(resolvedRootPath)
        val relativePath = runCatching { baseDirectory.relativize(resolvedPath.normalize()) }.getOrNull()
            ?: resolvedPath.normalize()
        val groupPath = if (relativePath.nameCount > 0) relativePath.getName(0).toString() else relativePath.fileName?.toString()
        return groupPath?.takeIf { it.isNotBlank() } ?: "Projects"
    }

    private fun extractProjectTags(project: JsonObject): List<String> {
        return project.objectValue("config")?.stringArray("tags").orEmpty()
    }

    private fun resolveProjectFilePath(rootLocation: String?): String? {
        val projectRootLocation = rootLocation?.takeIf { it.isNotBlank() }
        val basePath = project.basePath ?: return null
        val baseDirectory = Paths.get(basePath).toAbsolutePath().normalize()
        val candidates = buildList {
            if (projectRootLocation != null) {
                val rootPath = Paths.get(projectRootLocation)
                add(if (rootPath.isAbsolute) rootPath else baseDirectory.resolve(rootPath))
            }
        }

        candidates.forEach { candidate ->
            val normalizedPath = candidate.normalize()
            if (Files.isDirectory(normalizedPath)) {
                val moonFile = normalizedPath.resolve("moon.yml")
                if (Files.exists(moonFile)) {
                    return moonFile.toString()
                }
            }

            if (Files.exists(normalizedPath) && normalizedPath.fileName?.toString() == "moon.yml") {
                return normalizedPath.toString()
            }
        }

        return null
    }

    private fun parseTask(element: JsonElement): dev.mteterel.moonintellij.toolWindow.MoonTask? {
        val obj = element.asJsonObjectOrNull() ?: return null
        val id = obj.stringValue("id") ?: return null
        val target = obj.stringValue("target") ?: id
        val command = obj.stringValue("command") ?: ""
        val args = obj.stringArray("args")
        val description = obj.stringValue("description")

        return dev.mteterel.moonintellij.toolWindow.MoonTask(
            id = id,
            target = target,
            command = command,
            args = args,
            description = description,
        )
    }

    private fun parseTemplate(element: JsonElement, fallbackId: String? = null): MoonTemplate? {
        val obj = element.asJsonObjectOrNull() ?: return null
        val config = obj.objectValue("config")
        val id = obj.stringValue("id") ?: fallbackId ?: return null

        return MoonTemplate(
            id = id,
            title = config?.stringValue("title"),
            description = config?.stringValue("description"),
        )
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.stringValue(key: String): String? {
        val value = get(key)
        if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            val text = value.asString.trim()
            if (text.isNotBlank()) return text
        }
        return null
    }

    private fun JsonObject.stringArray(key: String): List<String> {
        val value = get(key)
        if (value != null && value.isJsonArray) {
            return value.asJsonArray.mapNotNull { item ->
                if (item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                    item.asString.trim().takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }
        return emptyList()
    }

    private fun JsonObject.objectValue(key: String): JsonObject? {
        val value = get(key)
        return if (value != null && value.isJsonObject) value.asJsonObject else null
    }
}

interface WorkspaceRefreshListener : EventListener {
    fun workspaceChanged(snapshot: WorkspaceRefreshSnapshot)
}

data class WorkspaceRefreshSnapshot(
    val workspaceInitialized: Boolean,
    val workspaceState: WorkspaceState,
    val refreshInProgress: Boolean,
    val refreshErrorMessage: String?,
)
