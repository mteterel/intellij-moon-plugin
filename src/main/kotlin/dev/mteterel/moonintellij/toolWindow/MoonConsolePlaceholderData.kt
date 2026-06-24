package dev.mteterel.moonintellij.toolWindow

import java.time.Instant

data class ProjectGroup(
    val name: String,
    val projects: List<MoonProject>,
)

data class MoonProject(
    val name: String,
    val tags: List<String>,
    val language: String,
    val layer: String? = null,
    val stack: String? = null,
    val projectInfo: ProjectInfo? = null,
    val taskTargets: List<String>,
    val groupName: String? = null,
    val projectRootPath: String? = null,
    val projectFilePath: String? = null,
)

data class ProjectInfo(
    val title: String? = null,
    val description: String? = null,
    val rootFile: String? = null,
)

data class MoonTask(
    val id: String,
    val target: String,
    val command: String,
    val args: List<String> = emptyList(),
    val description: String? = null,
)

data class LastRunItem(
    val label: String,
    val status: LastRunStatus,
    val duration: String? = null,
    val detail: String? = null,
)

enum class LastRunStatus {
    None,
    Success,
    Cached,
    Skipped,
    Failed,
}

data class LastRunReport(
    val status: String,
    val duration: String?,
    val targets: List<String>,
    val changedFiles: List<String>,
    val items: List<LastRunItem>,
    val completedAt: Instant? = null,
)

data class WorkspaceState(
    val projectGroups: List<ProjectGroup>,
    val tasksByTarget: Map<String, MoonTask>,
    val templates: List<MoonTemplate>,
) {
    companion object {
        fun placeholder() = WorkspaceState(
            projectGroups = emptyList(),
            tasksByTarget = emptyMap(),
            templates = emptyList(),
        )
    }
}

data class CommandResult(
    val stdout: String,
)

data class MoonTemplate(
    val id: String,
    val title: String? = null,
    val description: String? = null,
)

data class ParsedProject(
    val name: String,
    val groupName: String,
    val language: String,
    val tags: List<String>,
    val layer: String? = null,
    val stack: String? = null,
    val projectInfo: ProjectInfo,
    val taskTargets: List<String>,
    val projectRootPath: String? = null,
    val projectFilePath: String? = null,
)
