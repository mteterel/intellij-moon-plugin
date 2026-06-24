package dev.mteterel.moonintellij.toolWindow

import dev.mteterel.moonintellij.graph.MoonGraphEditorManager
import dev.mteterel.moonintellij.graph.MoonGraphFocus
import dev.mteterel.moonintellij.graph.MoonGraphFocusType
import dev.mteterel.moonintellij.graph.MoonGraphKind
import dev.mteterel.moonintellij.runconfiguration.MoonRunConfiguration
import dev.mteterel.moonintellij.runconfiguration.MoonRunConfigurationType
import dev.mteterel.moonintellij.services.MoonConsoleSettingsService
import dev.mteterel.moonintellij.services.MyProjectService
import dev.mteterel.moonintellij.services.WorkspaceRefreshSnapshot
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBPanel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTree
import javax.swing.ToolTipManager
import javax.swing.event.TreeSelectionEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath


class MoonConsoleToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val moonConsoleToolWindow = MoonConsoleToolWindow(project)
        val content = toolWindow.contentManager.factory.createContent(
            moonConsoleToolWindow.createContent(),
            null,
            false
        )
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class MoonConsoleToolWindow(private val project: Project) {
        private val layerOrder = listOf("automation", "application", "tool", "library", "scaffolding", "configuration")
        private val stackOrder = listOf("frontend", "backend", "infrastructure", "systems")
        private var selectedView = ViewKind.Projects
        private var currentTree: Tree? = null
        private val treeViews = mutableMapOf<ViewKind, TreeViewState>()
        private var mainActionToolbar: ActionToolbar? = null
        private var refreshInProgress = false
        private var refreshErrorMessage: String? = null
        private var workspaceInitialized = hasMoonWorkspace()
        private var showLastRunSection = true
        private var workspaceState = WorkspaceState.placeholder()
        private var renderedWorkspaceState = workspaceState
        private var renderedPreferProjectMetadata = false
        private val workspaceAlertBanner = MoonWorkspaceAlertBanner()
        private val lastRunPanel = MoonLastRunReportPanel { loadRunReport() }
        private val mainContentPanel = JBPanel<JBPanel<*>>(BorderLayout())
        private val viewContentPanel = JBPanel<JBPanel<*>>(BorderLayout())
        private val settingsService = project.service<MoonConsoleSettingsService>()
        private val workspaceService = project.service<MyProjectService>()

        init {
            showLastRunSection = settingsService.showLastRunSection
            renderedPreferProjectMetadata = settingsService.preferProjectMetadata
            installMoonWorkspaceWatcher()
            installRunReportWatcher()
            loadRunReport()
            workspaceService.addListener(project, object : dev.mteterel.moonintellij.services.WorkspaceRefreshListener {
                override fun workspaceChanged(snapshot: WorkspaceRefreshSnapshot) {
                    syncFromSnapshot(snapshot)
                }
            })
            syncFromSnapshot(workspaceService.getSnapshot())
        }

        fun createContent() = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(460))
            minimumSize = Dimension(0, 0)
            add(createActionBar(this), BorderLayout.NORTH)
            add(createMainContent(), BorderLayout.CENTER)
            add(lastRunPanel.component, BorderLayout.SOUTH)
            updateMainContentLayout()
        }

        private fun createActionBar(targetComponent: JComponent) =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(createMainActionToolbar(targetComponent), BorderLayout.CENTER)
                add(createAdvancedActionsToolbar(targetComponent), BorderLayout.EAST)
            }

        private fun createMainActionToolbar(targetComponent: JComponent) =
            ActionManager.getInstance()
                .createActionToolbar("MoonConsole.ActionBar", createActionGroup(), true)
                .also {
                    it.targetComponent = targetComponent
                    mainActionToolbar = it
                }
                .component

        private fun createAdvancedActionsToolbar(targetComponent: JComponent) =
            ActionManager.getInstance()
                .createActionToolbar(
                    "MoonConsole.AdvancedActions",
                    DefaultActionGroup(advancedActionsTriggerAction()),
                    true
                )
                .also { it.targetComponent = targetComponent }
                .component

        private fun createActionGroup() = DefaultActionGroup().apply {
            add(refreshProjectsAction())
            add(Separator.getInstance())
            add(generateAction())
            add(graphActionsTriggerAction())
            add(Separator.getInstance())
            add(switchViewActionGroup())
            add(expandAllAction())
            add(collapseAllAction())
        }

        // TODO: Relocate somewhere else
        fun executeOSCommand(project: Project, command: List<String>) {
            val commandLine = createCommandLine(command, forceColor = true)
            val runProfile = CommandLineRunProfile(commandLine, "Command Execution")
            val environment = ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), runProfile)
                .build()

            ExecutionManager.getInstance(project).restartRunProfile(environment)
        }

        // TODO: Relocate somewhere else
        fun executeMoonTarget(project: Project, target: String) {
            val configurationName = target
            val runManager = RunManager.getInstance(project)
            val factory = MoonRunConfigurationType.getInstance().configurationFactories.first()
            val settings = runManager.allSettings.firstOrNull {
                it.name == configurationName && it.configuration is MoonRunConfiguration
            } ?: runManager.createConfiguration(configurationName, factory).also { runManager.addConfiguration(it) }

            val runConfiguration = settings.configuration as MoonRunConfiguration
            runConfiguration.moonExecutable = "moon"
            runConfiguration.taskTarget = target
            runManager.setTemporaryConfiguration(settings)
            runManager.selectedConfiguration = settings
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        }

        private fun createCommandLine(command: List<String>, forceColor: Boolean) =
            GeneralCommandLine(command)
                .withWorkDirectory(project.basePath)
                .withCharset(Charsets.UTF_8)
                .withRedirectErrorStream(true)
                .also {
                    if (forceColor) {
                        it.environment["FORCE_COLOR"] = "1"
                    }
                }

        private fun updateWorkspaceDisplay() {
            workspaceAlertBanner.setMessage(refreshErrorMessage)
            refreshTreeViewsIfNeeded()
            updateSelectedView()
            updateMainContentLayout()
            mainActionToolbar?.updateActionsAsync()
            ActivityTracker.getInstance().inc()
        }

        private fun refreshTreeViewsIfNeeded() {
            val workspaceChanged = renderedWorkspaceState != workspaceState
            val preferProjectMetadataChanged = renderedPreferProjectMetadata != settingsService.preferProjectMetadata
            if (!workspaceChanged && !preferProjectMetadataChanged) {
                return
            }

            treeViews.values.forEach { it.refresh() }
            renderedWorkspaceState = workspaceState
            renderedPreferProjectMetadata = settingsService.preferProjectMetadata
            currentTree = treeViews[selectedView]?.tree
        }

        private fun syncFromSnapshot(snapshot: WorkspaceRefreshSnapshot) {
            workspaceInitialized = snapshot.workspaceInitialized
            workspaceState = snapshot.workspaceState
            refreshInProgress = snapshot.refreshInProgress
            refreshErrorMessage = snapshot.refreshErrorMessage
            updateWorkspaceDisplay()
        }

        private fun installMoonWorkspaceWatcher() {
            val basePath = project.basePath ?: return
            val baseDirectory = Paths.get(basePath).toAbsolutePath().normalize()
            val moonRoot = baseDirectory.resolve(".moon").normalize()
            val moonCacheRoot = baseDirectory.resolve(".moon/cache").normalize()

            project.messageBus.connect(project).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    if (events.any { event ->
                            !eventIsUnderPath(event, moonCacheRoot) &&
                                (eventIsUnderMoonRoot(event, moonRoot) || eventIsMoonYamlFile(event, baseDirectory))
                        }) {
                        ToolWindowManager.getInstance(project).invokeLater {
                            requestWorkspaceRefresh()
                        }
                    }
                }
            })
        }

        private fun installRunReportWatcher() {
            val basePath = project.basePath ?: return
            val runReportPath = runReportPath(Paths.get(basePath).toAbsolutePath().normalize())

            project.messageBus.connect(project).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    if (events.any { event -> eventIsRunReportChange(event, runReportPath) }) {
                        ToolWindowManager.getInstance(project).invokeLater {
                            loadRunReport()
                        }
                    }
                }
            })
        }

        private fun runReportPath(baseDirectory: Path): Path = baseDirectory.resolve(".moon/cache/runReport.json").normalize()

        private fun eventIsRunReportChange(event: VFileEvent, runReportPath: Path): Boolean {
            val eventPath = runCatching { Paths.get(event.path).normalize() }.getOrNull() ?: return false
            val fileName = eventPath.fileName?.toString() ?: return false
            return eventPath == runReportPath ||
                (eventPath.parent == runReportPath.parent && fileName.startsWith("runReport.json"))
        }

        private fun eventIsUnderMoonRoot(event: VFileEvent, moonRoot: Path): Boolean {
            val eventPath = runCatching { Paths.get(event.path).normalize() }.getOrNull() ?: return false
            return eventPath == moonRoot || eventPath.startsWith(moonRoot)
        }

        private fun eventIsUnderPath(event: VFileEvent, parentPath: Path): Boolean {
            val eventPath = runCatching { Paths.get(event.path).normalize() }.getOrNull() ?: return false
            return eventPath == parentPath || eventPath.startsWith(parentPath)
        }

        private fun eventIsMoonYamlFile(event: VFileEvent, baseDirectory: Path): Boolean {
            val eventPath = runCatching { Paths.get(event.path).normalize() }.getOrNull() ?: return false
            val fileName = eventPath.fileName?.toString() ?: return false
            return fileName == "moon.yml" && eventPath.startsWith(baseDirectory)
        }

        private fun hasMoonWorkspace(): Boolean {
            val basePath = project.basePath ?: return false
            return java.nio.file.Files.exists(Paths.get(basePath, ".moon"))
        }

        private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

        private fun JsonObject.stringValue(vararg keys: String): String? {
            keys.forEach { key ->
                val value = get(key)
                if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    val text = value.asString.trim()
                    if (text.isNotBlank()) return text
                }
            }
            return null
        }

        private fun JsonObject.stringArray(vararg keys: String): List<String> {
            keys.forEach { key ->
                val value = get(key)
                if (value != null && value.isJsonArray) {
                    return value.asJsonArray.mapNotNull { item ->
                        when {
                            item.isJsonPrimitive && item.asJsonPrimitive.isString ->
                                item.asString.trim().takeIf { it.isNotBlank() }

                            else -> item.asJsonObjectOrNull()?.stringValue("id", "name", "target")
                        }
                    }
                }
            }
            return emptyList()
        }

        private fun JsonObject.arrayValue(vararg keys: String): JsonArray? {
            keys.forEach { key ->
                val value = get(key)
                if (value != null && value.isJsonArray) {
                    return value.asJsonArray
                }
            }
            return null
        }

        private fun JsonObject.objectValue(vararg keys: String): JsonObject? {
            keys.forEach { key ->
                val value = get(key)
                if (value != null && value.isJsonObject) {
                    return value.asJsonObject
                }
            }
            return null
        }

        fun requestWorkspaceRefresh() {
            workspaceService.requestWorkspaceRefresh()
        }

        private fun refreshProjectsAction() =
            object : DumbAwareAction("Refresh", "Refresh workspace from Moon", AllIcons.Actions.Refresh) {
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !refreshInProgress
                }

                override fun actionPerformed(e: AnActionEvent) {
                    requestWorkspaceRefresh()
                }
            }

        private fun generateAction() =
            object : DumbAwareAction("Generate", "Generate template", AllIcons.Actions.AddList) {
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !refreshInProgress && workspaceState.templates.isNotEmpty()
                }

                override fun actionPerformed(e: AnActionEvent) {
                    showGenerateTemplatesPopup(e.inputEvent?.component as? JComponent)
                }
            }

        private fun advancedActionsGroup() =
            object : DefaultActionGroup("Advanced", true) {
                init {
                    add(purgeCacheAction())
                    add(Separator.getInstance())
                    add(togglePreferProjectMetadataAction())
                    add(toggleLastRunSectionAction())
                    add(Separator.getInstance())
                    add(daemonRestartAction())
                    add(daemonStopAction())
                    add(daemonViewLogsAction())
                    add(Separator.getInstance())
                    add(syncCodeOwnersAction())
                    add(syncConfigurationSchemasAction())
                    add(syncVcsHooksAction())
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.description = "Advanced actions"
                }
            }

        private fun advancedActionsTriggerAction() =
            object : DumbAwareAction("", "Advanced actions", AllIcons.Actions.More) {
                override fun actionPerformed(e: AnActionEvent) {
                    val component = e.inputEvent?.component as? JComponent ?: return
                    createAdvancedActionsPopupMenu().show(component, 0, component.height)
                }
            }

        private fun createAdvancedActionsPopupMenu() =
            ActionManager.getInstance()
                .createActionPopupMenu(
                    "MoonConsole.AdvancedActionsPopup",
                    advancedActionsGroup(),
                )
                .component

        private fun purgeCacheAction() =
            object : DumbAwareAction("Purge Cache") {
                override fun actionPerformed(e: AnActionEvent) {
                    executeOSCommand(project, listOf("moon", "clean", "--all"))
                }
            }

        private fun daemonRestartAction() =
            object : DumbAwareAction("Daemon: Restart", "", AllIcons.Run.Restart) {
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = false
                }

                override fun actionPerformed(e: AnActionEvent) = Unit
            }

        private fun daemonStopAction() =
            object : DumbAwareAction("Daemon: Stop") {
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = false
                }

                override fun actionPerformed(e: AnActionEvent) = Unit
            }

        private fun daemonViewLogsAction() =
            object : DumbAwareAction("Daemon: View Logs") {
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = false
                }

                override fun actionPerformed(e: AnActionEvent) = Unit
            }

        private fun syncCodeOwnersAction() =
            object : DumbAwareAction("Sync: CODEOWNERS") {
                override fun actionPerformed(e: AnActionEvent) {
                    executeOSCommand(project, listOf("moon", "sync", "code-owners", "--clean"))
                }
            }

        private fun syncConfigurationSchemasAction() =
            object : DumbAwareAction("Sync: Configuration Schemas") {
                override fun actionPerformed(e: AnActionEvent) {
                    executeOSCommand(project, listOf("moon", "sync", "config-schemas"))
                }
            }

        private fun syncVcsHooksAction() =
            object : DumbAwareAction("Sync: VCS Hooks") {
                override fun actionPerformed(e: AnActionEvent) {
                    executeOSCommand(project, listOf("moon", "sync", "vcs-hooks", "--clean"))
                }
            }

        private fun graphActionsTriggerAction() =
            object : DefaultActionGroup("View Graph", true) {
                init {
                    templatePresentation.icon = AllIcons.Graph.Layout
                    add(viewActionGraphAction())
                    add(viewProjectGraphAction())
                    add(viewTaskGraphAction())
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.text = "View Graph"
                    e.presentation.description = "View Graph"
                    e.presentation.icon = AllIcons.Graph.Layout
                    e.presentation.isEnabled = workspaceInitialized
                }
            }

        private fun viewActionGraphAction() =
            object : DumbAwareAction("Action Graph") {
                override fun actionPerformed(e: AnActionEvent) {
                    MoonGraphEditorManager.open(project, MoonGraphKind.Action)
                }
            }

        private fun viewProjectGraphAction() =
            object : DumbAwareAction("Project Graph") {
                override fun actionPerformed(e: AnActionEvent) {
                    MoonGraphEditorManager.open(project, MoonGraphKind.Project)
                }
            }

        private fun viewTaskGraphAction() =
            object : DumbAwareAction("Task Graph") {
                override fun actionPerformed(e: AnActionEvent) {
                    MoonGraphEditorManager.open(project, MoonGraphKind.Task)
                }
            }

        private fun expandAllAction() =
            object : DumbAwareAction("Expand All", "Expand All", AllIcons.Actions.Expandall) {
                override fun actionPerformed(e: AnActionEvent) {
                    currentTree?.expandAllRows()
                }
            }

        private fun collapseAllAction() =
            object : DumbAwareAction("Collapse All", "Collapse All", AllIcons.Actions.Collapseall) {
                override fun actionPerformed(e: AnActionEvent) {
                    currentTree?.collapseAllRows()
                }
            }

        private fun switchViewActionGroup() =
            object : DefaultActionGroup("Switch View", true) {
                init {
                    templatePresentation.icon = AllIcons.General.Show
                    add(viewSelectionAction(ViewKind.Projects))
                    add(viewSelectionAction(ViewKind.Tags))
                    add(viewSelectionAction(ViewKind.Tasks))
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.text = "Switch View"
                    e.presentation.description = "Switch View"
                    e.presentation.icon = AllIcons.General.Show
                }
            }

        private fun viewSelectionAction(viewKind: ViewKind) =
            object : DumbAwareToggleAction(viewKind.title) {
                override fun isSelected(e: AnActionEvent) = selectedView == viewKind

                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (!state || selectedView == viewKind) return
                    selectedView = viewKind
                    updateWorkspaceDisplay()
                }
            }

        private fun createMainContent() = mainContentPanel.apply {
            minimumSize = Dimension(0, 0)
            border = JBUI.Borders.empty(0, 4, 4, 4)
            updateSelectedView()
            add(workspaceAlertBanner.component, BorderLayout.NORTH)
            add(viewContentPanel, BorderLayout.CENTER)
        }

        private fun toggleLastRunSectionAction() =
            object : DumbAwareToggleAction("Show Last Run Report") {
                override fun isSelected(e: AnActionEvent) = showLastRunSection

                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (showLastRunSection == state) return
                    showLastRunSection = state
                    settingsService.showLastRunSection = state
                    updateWorkspaceDisplay()
                }
            }

        private fun togglePreferProjectMetadataAction() =
            object : DumbAwareToggleAction("Prefer Projects Titles") {
                override fun isSelected(e: AnActionEvent) = settingsService.preferProjectMetadata

                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (settingsService.preferProjectMetadata == state) return
                    settingsService.preferProjectMetadata = state
                    updateWorkspaceDisplay()
                }
            }

        private fun updateSelectedView() {
            viewContentPanel.removeAll()
            val content = if (workspaceInitialized) {
                val selectedTreeView = ensureTreeView(selectedView)
                currentTree = selectedTreeView.tree
                selectedTreeView.panel
            } else {
                currentTree = null
                createEmptyStateView()
            }

            viewContentPanel.add(content, BorderLayout.CENTER)
            viewContentPanel.revalidate()
            viewContentPanel.repaint()
            updateMainContentLayout()
        }

        private fun updateMainContentLayout() {
            val showLastRunPane = workspaceInitialized && showLastRunSection
            lastRunPanel.component.isVisible = showLastRunPane
            mainContentPanel.revalidate()
            mainContentPanel.repaint()
        }

        private fun createSelectedView() = ensureTreeView(selectedView).panel

        private fun createEmptyStateView(): JComponent =
            MoonEmptyStateView(
                project = project,
                onRefresh = { requestWorkspaceRefresh() },
                onInitializeMoon = { openMoonInitTerminal() },
            ).create()

        private fun openMoonInitTerminal() {
            val workingDirectory = project.basePath ?: return executeOSCommand(project, listOf("moon", "init"))
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            val shellWidget: TerminalWidget = terminalManager.createShellWidget("moon init", workingDirectory, true, true)
            shellWidget.sendCommandToExecute("moon init")
        }

        private fun openMoonGenerateTerminal(templateId: String) {
            val workingDirectory = project.basePath ?: return executeOSCommand(project, listOf("moon", "generate", templateId))
            val command = "moon generate $templateId"
            val terminalTitle = "moon generate: $templateId"
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            val shellWidget: TerminalWidget = terminalManager.createShellWidget(terminalTitle, workingDirectory, true, true)
            shellWidget.sendCommandToExecute(command)
        }

        private fun ensureTreeView(viewKind: ViewKind): TreeViewState =
            treeViews.getOrPut(viewKind) { createTreeViewState(viewKind) }

        private fun createTreeViewState(viewKind: ViewKind): TreeViewState {
            val root = DefaultMutableTreeNode(ProjectTreeNode("", ProjectTreeNode.Kind.Root))
            val model = DefaultTreeModel(root)
            val tree = object : Tree(model) {
                override fun getToolTipText(event: MouseEvent): String? {
                    val path = getPathForLocation(event.x, event.y) ?: return null
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
                    val projectTreeNode = node.userObject as? ProjectTreeNode ?: return null

                    return projectTreeNode.tooltip
                }
            }.apply {
                isRootVisible = false
                showsRootHandles = true
                cellRenderer = ProjectTreeCellRenderer()
                background = UIUtil.getTreeBackground()
                addTreeSelectionListener(::ignoreEmptyNodeSelection)
                addMouseListener(createTaskDoubleClickListener())
                addMouseListener(createProjectContextMenuListener(this))
            }

            ToolTipManager.sharedInstance().registerComponent(tree)

            val scrollPane = ScrollPaneFactory.createScrollPane(tree, true).apply {
                border = JBUI.Borders.empty()
                background = UIUtil.getTreeBackground()
                viewport.background = UIUtil.getTreeBackground()
                preferredSize = Dimension(JBUI.scale(420), JBUI.scale(340))
                minimumSize = Dimension(0, 0)
            }

            val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.empty(2, 0, 0, 0)
                add(scrollPane, BorderLayout.CENTER)
            }

            return TreeViewState(
                viewKind = viewKind,
                tree = tree,
                model = model,
                panel = panel,
            ).also { it.refresh(initialBuild = true) }
        }

        private fun refreshTreeView(treeViewState: TreeViewState) {
            treeViewState.refresh()
        }

        private fun buildTreeRoot(viewKind: ViewKind): DefaultMutableTreeNode =
            when (viewKind) {
                ViewKind.Projects -> buildProjectsTreeRoot()
                ViewKind.Tags -> buildTagsTreeRoot()
                ViewKind.Tasks -> buildTasksTreeRoot()
            }

        private fun buildProjectsTreeRoot(): DefaultMutableTreeNode {
            val root = DefaultMutableTreeNode(ProjectTreeNode("", ProjectTreeNode.Kind.Root))
            val projects = workspaceState.projectGroups.flatMap { it.projects }
            if (projects.none { hasMeaningfulLayerOrStack(it) }) {
                projects.forEach { project ->
                    root.add(createProjectNode(project))
                }
            } else {
                projects
                    .groupBy { compositeProjectGroupKey(it) }
                    .toList()
                    .sortedWith(
                        compareBy<Pair<CompositeGroupKey, List<MoonProject>>>(
                            { it.first.sortLayerIndex },
                            { it.first.sortStackIndex },
                            { it.first.label.lowercase() },
                        )
                    )
                    .forEach { (key, groupProjects) ->
                        root.add(createProjectGroupNode(key.label, groupProjects))
                    }
            }

            return root
        }

        private fun buildTagsTreeRoot(): DefaultMutableTreeNode {
            val root = DefaultMutableTreeNode(ProjectTreeNode("", ProjectTreeNode.Kind.Root))
            workspaceState.projectGroups
                .flatMap { it.projects }
                .flatMap { project -> project.tags.map { tag -> tag to project } }
                .groupBy({ (tag, _) -> tag }, { (_, project) -> project })
                .toSortedMap()
                .forEach { (tag, projects) ->
                    root.add(createProjectGroupNode("#$tag", projects))
                }

            return root
        }

        private fun buildTasksTreeRoot(): DefaultMutableTreeNode {
            val root = DefaultMutableTreeNode(ProjectTreeNode("", ProjectTreeNode.Kind.Root))
            val tasks = workspaceState.projectGroups
                .flatMap { it.projects }
                .flatMap { project ->
                    project.taskTargets.mapNotNull { target ->
                        workspaceState.tasksByTarget[target]?.let { task ->
                            TaskProjectEntry(
                                project = project,
                                taskId = task.id,
                                runTarget = target,
                                effectiveCommand = buildEffectiveCommand(task.command, task.args),
                            )
                        }
                    }
                }

            tasks
                .groupBy { it.taskId }
                .toSortedMap()
                .forEach { (taskId, taskProjects) ->
                    val taskGroupNode = DefaultMutableTreeNode(
                        ProjectTreeNode(
                            label = taskId,
                            kind = ProjectTreeNode.Kind.TaskGroup,
                            taskGroupId = taskId,
                            taskTarget = taskProjects.firstOrNull()?.runTarget,
                        )
                    )
                    taskProjects
                        .distinctBy { projectDisplayName(it.project) to it.runTarget }
                        .sortedBy { projectDisplayName(it.project).lowercase() }
                        .forEach { entry ->
                            taskGroupNode.add(
                                createProjectNode(
                                    project = entry.project,
                                    showTasks = false,
                                    runTarget = entry.runTarget,
                                    effectiveCommand = entry.effectiveCommand,
                                )
                            )
                        }
                    root.add(taskGroupNode)
                }

            return root
        }

        private fun captureTreeState(tree: JTree): TreeStateSnapshot {
            val expandedPaths = buildSet {
                for (row in 0 until tree.rowCount) {
                    if (!tree.isExpanded(row)) continue
                    val path = tree.getPathForRow(row) ?: continue
                    add(path.toKey())
                }
            }
            val selectedPath = tree.selectionPath?.toKey()
            return TreeStateSnapshot(expandedPaths = expandedPaths, selectedPath = selectedPath)
        }

        private fun restoreTreeState(tree: JTree, root: DefaultMutableTreeNode, snapshot: TreeStateSnapshot) {
            snapshot.expandedPaths.forEach { pathKey ->
                findTreePath(root, pathKey)?.let(tree::expandPath)
            }

            val selectedPath = snapshot.selectedPath?.let { findTreePath(root, it) }
            if (selectedPath != null) {
                tree.selectionPath = selectedPath
            } else {
                tree.clearSelection()
            }
        }

        private fun TreePath.toKey(): TreePathKey {
            val segments = path.mapNotNull { node ->
                val treeNode = node as? DefaultMutableTreeNode ?: return@mapNotNull null
                val projectTreeNode = treeNode.userObject as? ProjectTreeNode ?: return@mapNotNull null
                TreePathSegment(projectTreeNode.kind, projectTreeNode.identityKey())
            }
            return TreePathKey(segments)
        }

        private fun findTreePath(root: DefaultMutableTreeNode, pathKey: TreePathKey): TreePath? {
            if (pathKey.segments.isEmpty()) return null

            fun matches(node: DefaultMutableTreeNode, segment: TreePathSegment): Boolean {
                val projectTreeNode = node.userObject as? ProjectTreeNode ?: return false
                return projectTreeNode.kind == segment.kind && projectTreeNode.identityKey() == segment.identity
            }

            fun recurse(node: DefaultMutableTreeNode, depth: Int): TreePath? {
                if (!matches(node, pathKey.segments[depth])) return null
                if (depth == pathKey.segments.lastIndex) {
                    return TreePath(node.path)
                }

                for (index in 0 until node.childCount) {
                    val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
                    recurse(child, depth + 1)?.let { return it }
                }

                return null
            }

            return recurse(root, 0)
        }

        private fun ProjectTreeNode.identityKey(): String =
            when (kind) {
                ProjectTreeNode.Kind.Root -> "root"
                ProjectTreeNode.Kind.Group -> "group:${label.lowercase()}"
                ProjectTreeNode.Kind.TaskGroup -> "task-group:${taskGroupId ?: label.lowercase()}"
                ProjectTreeNode.Kind.Project -> "project:${projectId?.lowercase() ?: label.lowercase()}|target:${runTarget.orEmpty().lowercase()}"
                ProjectTreeNode.Kind.Task -> "task:${taskTarget?.lowercase() ?: label.lowercase()}"
                ProjectTreeNode.Kind.Empty -> "empty:${label.lowercase()}"
            }


        private fun createProjectsTree(): JComponent {
            val root = DefaultMutableTreeNode(ProjectTreeNode("", ProjectTreeNode.Kind.Root))
            val projects = workspaceState.projectGroups.flatMap { it.projects }
            if (projects.none { hasMeaningfulLayerOrStack(it) }) {
                projects.forEach { project ->
                    root.add(createProjectNode(project))
                }
            } else {
                projects
                    .groupBy { compositeProjectGroupKey(it) }
                    .toList()
                    .sortedWith(
                        compareBy<Pair<CompositeGroupKey, List<MoonProject>>>(
                            { it.first.sortLayerIndex },
                            { it.first.sortStackIndex },
                            { it.first.label.lowercase() },
                        )
                    )
                    .forEach { (key, groupProjects) ->
                        root.add(createProjectGroupNode(key.label, groupProjects))
                    }
            }

            return createProjectTreeComponent(root, expandGroups = true)
        }

        private fun hasMeaningfulLayerOrStack(project: MoonProject): Boolean {
            return project.layer.isMeaningfulCategoryValue() || project.stack.isMeaningfulCategoryValue()
        }

        private fun createTagsTree(): JComponent {
            val root = DefaultMutableTreeNode(ProjectTreeNode("", ProjectTreeNode.Kind.Root))
            workspaceState.projectGroups
                .flatMap { it.projects }
                .flatMap { project -> project.tags.map { tag -> tag to project } }
                .groupBy({ (tag, _) -> tag }, { (_, project) -> project })
                .toSortedMap()
                .forEach { (tag, projects) ->
                    root.add(createProjectGroupNode("#$tag", projects))
            }

            return createProjectTreeComponent(root, expandGroups = false)
        }

        private fun createTasksTree(): JComponent {
            val root = DefaultMutableTreeNode(ProjectTreeNode("", ProjectTreeNode.Kind.Root))
            val tasks = workspaceState.projectGroups
                .flatMap { it.projects }
                .flatMap { project ->
                    project.taskTargets.mapNotNull { target ->
                        workspaceState.tasksByTarget[target]?.let { task ->
                            TaskProjectEntry(
                                project = project,
                                taskId = task.id,
                                runTarget = target,
                                effectiveCommand = buildEffectiveCommand(task.command, task.args),
                            )
                        }
                    }
                }

            tasks
                .groupBy { it.taskId }
                .toSortedMap()
                .forEach { (taskId, taskProjects) ->
                    val taskGroupNode = DefaultMutableTreeNode(
                        ProjectTreeNode(
                            label = taskId,
                            kind = ProjectTreeNode.Kind.TaskGroup,
                            taskGroupId = taskId,
                            taskTarget = taskProjects.firstOrNull()?.runTarget,
                        )
                    )
                    taskProjects
                        .distinctBy { projectDisplayName(it.project) to it.runTarget }
                        .sortedBy { projectDisplayName(it.project).lowercase() }
                        .forEach { entry ->
                            taskGroupNode.add(
                                createProjectNode(
                                    project = entry.project,
                                    showTasks = false,
                                    runTarget = entry.runTarget,
                                    effectiveCommand = entry.effectiveCommand,
                                )
                            )
                        }
                    root.add(taskGroupNode)
                }

            return createProjectTreeComponent(root, expandGroups = false)
        }

        private fun normalizeCategoryKey(value: String?, allowedValues: List<String>): String {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return if (normalized in allowedValues) normalized else "other"
        }

        private fun String?.isMeaningfulCategoryValue(): Boolean {
            val normalized = this?.trim()?.lowercase().orEmpty()
            return normalized.isNotBlank() && normalized != "unknown"
        }

        private data class CompositeGroupKey(
            val stack: String?,
            val layer: String,
            val label: String,
            val sortLayerIndex: Int,
            val sortStackIndex: Int,
        )

        private data class TaskProjectEntry(
            val project: MoonProject,
            val taskId: String,
            val runTarget: String,
            val effectiveCommand: String?,
        )

        private fun compositeProjectGroupKey(project: MoonProject): CompositeGroupKey {
            val stackKey = normalizeStackKey(project.stack)
            val layerKey = normalizeCategoryKey(project.layer, layerOrder)
            val label = compositeProjectGroupLabel(stackKey, layerKey)
            return CompositeGroupKey(
                stack = stackKey,
                layer = layerKey,
                label = label,
                sortLayerIndex = layerSortIndex(layerKey),
                sortStackIndex = stackSortIndex(stackKey),
            )
        }

        private fun normalizeStackKey(value: String?): String? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return normalized.takeIf { it in stackOrder }
        }

        private fun compositeProjectGroupLabel(stack: String?, layer: String): String {
            val layerLabelText = when (layer) {
                "automation" -> "Automation"
                "application" -> "Applications"
                "tool" -> "Tooling"
                "library" -> "Libraries"
                "scaffolding" -> "Scaffolding"
                "configuration" -> "Configuration"
                else -> "Other"
            }

            val stackLabelText = when (stack) {
                "frontend" -> "Frontend"
                "backend" -> "Backend"
                "infrastructure" -> "Infrastructure"
                "systems" -> "Systems"
                else -> null
            }

            return if (stackLabelText == null) {
                layerLabelText
            } else if (layerLabelText == "Other") {
                "$stackLabelText Other"
            } else {
                "$stackLabelText $layerLabelText"
            }
        }

        private fun layerSortIndex(layer: String): Int =
            if (layer == "other") layerOrder.size else layerOrder.indexOf(layer).takeIf { it >= 0 } ?: layerOrder.size

        private fun stackSortIndex(stack: String?): Int =
            stack?.let { stackOrder.indexOf(it).takeIf { index -> index >= 0 } } ?: stackOrder.size

        private fun createProjectGroupNode(groupName: String, projects: List<MoonProject>): DefaultMutableTreeNode {
            val groupNode = DefaultMutableTreeNode(ProjectTreeNode(groupName, ProjectTreeNode.Kind.Group))

            projects.forEach { project ->
                groupNode.add(createProjectNode(project))
            }

            return groupNode
        }

        private fun createProjectNode(project: MoonProject): DefaultMutableTreeNode {
            return createProjectNode(project, showTasks = true, runTarget = null, effectiveCommand = null)
        }

        private fun createProjectNode(
            project: MoonProject,
            showTasks: Boolean,
            runTarget: String?,
            effectiveCommand: String?,
        ): DefaultMutableTreeNode {
            val projectLabel = projectDisplayName(project)
            val projectNode = DefaultMutableTreeNode(
                ProjectTreeNode(
                    label = projectLabel,
                    kind = ProjectTreeNode.Kind.Project,
                    language = project.language,
                    projectInfo = project.projectInfo,
                    projectId = project.name,
                    projectFilePath = project.projectFilePath,
                    runTarget = runTarget,
                    effectiveCommand = effectiveCommand,
                )
            )
            if (!showTasks) {
                return projectNode
            }

            val tasks = project.taskTargets.mapNotNull { target -> workspaceState.tasksByTarget[target] }
            if (tasks.isEmpty()) {
                projectNode.add(DefaultMutableTreeNode(ProjectTreeNode("No tasks in project", ProjectTreeNode.Kind.Empty)))
            } else {
                tasks.forEach { task ->
                    projectNode.add(
                        DefaultMutableTreeNode(
                            ProjectTreeNode(
                                label = task.id,
                                kind = ProjectTreeNode.Kind.Task,
                                command = task.command,
                                args = task.args,
                                taskTarget = task.target,
                                description = task.description,
                            )
                        )
                    )
                }
            }
            return projectNode
        }

        private fun projectDisplayName(project: MoonProject): String {
            if (!settingsService.preferProjectMetadata) {
                return project.name
            }

            return project.projectInfo?.title?.takeIf { it.isNotBlank() } ?: project.name
        }

        private fun buildEffectiveCommand(command: String?, args: List<String>): String? {
            val parts = buildList {
                command?.takeIf { it.isNotBlank() }?.let { add(it) }
                args.filter { it.isNotBlank() }.forEach { add(it) }
            }
            return parts.joinToString(" ").takeIf { it.isNotBlank() }
        }

        private fun loadRunReport() {
            lastRunPanel.loadFrom(project.basePath)
        }

        private fun showGenerateTemplatesPopup(anchorComponent: JComponent?) {
            val templates = workspaceState.templates.sortedBy { it.id.lowercase() }

            if (templates.isEmpty()) return

            val popup = JBPopupFactory.getInstance()
                .createPopupChooserBuilder(templates)
                .setTitle("Generate")
                .setRenderer(object : ColoredListCellRenderer<MoonTemplate>() {
                    override fun customizeCellRenderer(
                        list: JList<out MoonTemplate>,
                        value: MoonTemplate,
                        index: Int,
                        selected: Boolean,
                        hasFocus: Boolean,
                    ) {
                        append(value.id, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        value.description?.takeIf { it.isNotBlank() }?.let {
                            append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                })
                .setItemChosenCallback { template ->
                    openMoonGenerateTerminal(template.id)
                }
                .createPopup()

            val anchor = anchorComponent ?: mainActionToolbar?.component ?: return
            popup.show(RelativePoint(anchor, Point(0, anchor.height)))
        }

        private fun createProjectTreeComponent(root: DefaultMutableTreeNode, expandGroups: Boolean): JComponent {
            val tree = object : Tree(DefaultTreeModel(root)) {
                override fun getToolTipText(event: MouseEvent): String? {
                    val path = getPathForLocation(event.x, event.y) ?: return null
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
                    val projectTreeNode = node.userObject as? ProjectTreeNode ?: return null

                    return projectTreeNode.tooltip
                }
            }.apply {
                isRootVisible = false
                showsRootHandles = true
                cellRenderer = ProjectTreeCellRenderer()
                background = UIUtil.getTreeBackground()
                addTreeSelectionListener(::ignoreEmptyNodeSelection)
                addMouseListener(createTaskDoubleClickListener())
                addMouseListener(createProjectContextMenuListener(this))
                if (expandGroups) {
                    expandGroupRows()
                }
            }
            ToolTipManager.sharedInstance().registerComponent(tree)
            currentTree = tree

            return ScrollPaneFactory.createScrollPane(tree, true).apply {
                border = JBUI.Borders.empty()
                background = UIUtil.getTreeBackground()
                viewport.background = UIUtil.getTreeBackground()
                preferredSize = Dimension(JBUI.scale(420), JBUI.scale(340))
                minimumSize = Dimension(0, 0)
            }
        }

        private fun JTree.expandAllRows() {
            var row = 0
            while (row < rowCount) {
                expandRow(row)
                row++
            }
        }

        private fun JTree.expandGroupRows() {
            var row = 0
            while (row < rowCount) {
                val node = (getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ProjectTreeNode
                if (node?.kind == ProjectTreeNode.Kind.Group || node?.kind == ProjectTreeNode.Kind.TaskGroup) {
                    expandRow(row)
                }
                row++
            }
        }

        private fun JTree.collapseAllRows() {
            for (row in rowCount - 1 downTo 0) {
                collapseRow(row)
            }
        }

        private fun ignoreEmptyNodeSelection(event: TreeSelectionEvent) {
            val tree = event.source as? JTree ?: return
            val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
            val projectTreeNode = selectedNode.userObject as? ProjectTreeNode ?: return

            if (projectTreeNode.kind == ProjectTreeNode.Kind.Empty) {
                tree.clearSelection()
            }
        }

        private fun createTaskDoubleClickListener() =
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount != 2) return

                    val tree = event.source as? JTree ?: return
                    val path = tree.getPathForLocation(event.x, event.y) ?: return
                    val selectedNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val selectedItem = selectedNode.userObject as? ProjectTreeNode ?: return

                    when (selectedItem.kind) {
                        ProjectTreeNode.Kind.Task -> {
                            val projectNode = selectedNode.parent as? DefaultMutableTreeNode ?: return
                            val selectedProject = projectNode.userObject as? ProjectTreeNode ?: return
                            if (selectedProject.kind != ProjectTreeNode.Kind.Project) return

                            selectedProject.runTarget?.let {
                                executeMoonTarget(project, it)
                                return
                            }

                            executeMoonTarget(project, selectedItem.taskTarget ?: return)
                        }

                        ProjectTreeNode.Kind.Project -> {
                            selectedItem.runTarget?.let {
                                executeMoonTarget(project, it)
                            }
                        }

                        else -> Unit
                    }
                }
            }

        private fun createProjectContextMenuListener(tree: JTree) =
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    maybeShowPopup(tree, event)
                }

                override fun mouseReleased(event: MouseEvent) {
                    maybeShowPopup(tree, event)
                }
            }

        private fun maybeShowPopup(tree: JTree, event: MouseEvent) {
            if (!event.isPopupTrigger) return

            val path = tree.getPathForLocation(event.x, event.y) ?: return
            val selectedNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return
            val selectedItem = selectedNode.userObject as? ProjectTreeNode ?: return

            tree.selectionPath = path
            when (selectedItem.kind) {
                ProjectTreeNode.Kind.Project -> createProjectPopupMenu(selectedItem).show(tree, event.x, event.y)
                ProjectTreeNode.Kind.TaskGroup -> createTaskGroupPopupMenu(selectedItem).show(tree, event.x, event.y)
                else -> Unit
            }
        }

        private fun createProjectPopupMenu(selectedProject: ProjectTreeNode) =
            ActionManager.getInstance()
                .createActionPopupMenu(
                    "MoonConsole.ProjectContextMenu",
                    createProjectContextMenuActions(selectedProject),
                )
                .component

        private fun createProjectContextMenuActions(selectedProject: ProjectTreeNode) =
            DefaultActionGroup().apply {
                add(openProjectFileAction(selectedProject))
                add(runMoonCheckAction(selectedProject))
                add(focusInProjectGraphAction(selectedProject))
                add(Separator.getInstance())
                add(generateDockerfileAction(selectedProject))
                add(Separator.getInstance())
                add(inspectProjectAction(selectedProject))
            }

        private fun createTaskGroupPopupMenu(selectedTaskGroup: ProjectTreeNode) =
            ActionManager.getInstance()
                .createActionPopupMenu(
                    "MoonConsole.TaskGroupContextMenu",
                    createTaskGroupContextMenuActions(selectedTaskGroup),
                )
                .component

        private fun createTaskGroupContextMenuActions(selectedTaskGroup: ProjectTreeNode) =
            DefaultActionGroup().apply {
                add(runTaskGroupForAllProjectsAction(selectedTaskGroup))
                add(runTaskGroupForAffectedProjectsAction(selectedTaskGroup))
                add(focusInTaskGraphAction(selectedTaskGroup))
            }

        private fun openProjectFileAction(selectedProject: ProjectTreeNode) =
            object : DumbAwareAction("Open project file") {
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedProject.projectFilePath != null
                }

                override fun actionPerformed(e: AnActionEvent) {
                    val projectFilePath = selectedProject.projectFilePath ?: return
                    val projectFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(projectFilePath) ?: return
                    FileEditorManager.getInstance(project).openFile(projectFile, true)
                }
            }

        private fun runMoonCheckAction(selectedProject: ProjectTreeNode) =
            object : DumbAwareAction("Run moon check", "Run moon check", greenPlayIcon()) {
                override fun actionPerformed(e: AnActionEvent) {
                    runMoonCommand("check", selectedProject.projectId ?: selectedProject.label)
                }
            }

        private fun focusInProjectGraphAction(selectedProject: ProjectTreeNode) =
            object : DumbAwareAction("Focus in Project Graph") {
                override fun actionPerformed(e: AnActionEvent) {
                    val projectId = selectedProject.projectId ?: selectedProject.label
                    MoonGraphEditorManager.open(
                        project,
                        MoonGraphKind.Project,
                        MoonGraphFocus(MoonGraphFocusType.Project, projectId)
                    )
                }
            }

        private fun generateDockerfileAction(selectedProject: ProjectTreeNode) =
            object : DumbAwareAction("Generate Dockerfile") {
                override fun actionPerformed(e: AnActionEvent) {
                    runMoonCommand("docker", "file", selectedProject.projectId ?: selectedProject.label)
                }
            }

        private fun inspectProjectAction(selectedProject: ProjectTreeNode) =
            object : DumbAwareAction("Inspect") {
                override fun actionPerformed(e: AnActionEvent) {
                    runMoonCommand("project", selectedProject.projectId ?: selectedProject.label)
                }
            }

        private fun runTaskGroupForAllProjectsAction(selectedTaskGroup: ProjectTreeNode) =
            object : DumbAwareAction("Run on All Projects", "Run this task for all projects", runAllIcon()) {
                override fun actionPerformed(e: AnActionEvent) {
                    val taskGroupId = selectedTaskGroup.taskGroupId ?: return
                    runMoonCommand("run", ":$taskGroupId")
                }
            }

        private fun runTaskGroupForAffectedProjectsAction(selectedTaskGroup: ProjectTreeNode) =
            object : DumbAwareAction("Run on Affected Projects") {
                override fun actionPerformed(e: AnActionEvent) {
                    val taskGroupId = selectedTaskGroup.taskGroupId ?: return
                    runMoonCommand("run", ":$taskGroupId", "--affected")
                }
            }

        private fun focusInTaskGraphAction(selectedTaskGroup: ProjectTreeNode) =
            object : DumbAwareAction("Focus in Task Graph") {
                override fun actionPerformed(e: AnActionEvent) {
                    val taskId = selectedTaskGroup.taskTarget ?: selectedTaskGroup.taskGroupId ?: selectedTaskGroup.label
                    MoonGraphEditorManager.open(
                        project,
                        MoonGraphKind.Task,
                        MoonGraphFocus(MoonGraphFocusType.Task, taskId)
                    )
                }
            }

        private fun runMoonCommand(vararg args: String) {
            executeOSCommand(project, listOf("moon", *args))
        }

        private fun runAllIcon() = AllIcons.Actions.RunAll

        private fun greenPlayIcon() = AllIcons.Actions.Execute


        private data class ProjectTreeNode(
            val label: String,
            val kind: Kind,
            val command: String? = null,
            val args: List<String> = emptyList(),
            val taskTarget: String? = null,
            val language: String? = null,
            val projectInfo: ProjectInfo? = null,
            val projectId: String? = null,
            val description: String? = null,
            val projectFilePath: String? = null,
            val runTarget: String? = null,
            val effectiveCommand: String? = null,
            val taskGroupId: String? = null,
        ) {
            val tooltip: String?
                get() {
                    return when (kind) {
                        Kind.Project -> listOfNotNull(
                            projectInfo?.title?.takeIf { it.isNotBlank() },
                            projectInfo?.description?.takeIf { it.isNotBlank() },
                        ).joinToString(" — ").takeIf { it.isNotBlank() }

                        Kind.Task -> description?.takeIf { it.isNotBlank() }
                        else -> null
                    }
                }

            enum class Kind {
                Root,
                Group,
                TaskGroup,
                Project,
                Task,
                Empty,
            }

            override fun toString() = label
        }

        private data class TreePathSegment(
            val kind: ProjectTreeNode.Kind,
            val identity: String,
        )

        private data class TreePathKey(
            val segments: List<TreePathSegment>,
        )

        private data class TreeStateSnapshot(
            val expandedPaths: Set<TreePathKey>,
            val selectedPath: TreePathKey?,
        )

        private inner class TreeViewState(
            val viewKind: ViewKind,
            val tree: Tree,
            val model: DefaultTreeModel,
            val panel: JComponent,
        ) {
            private var initialized = false

            fun refresh(initialBuild: Boolean = false) {
                val snapshot = if (initialized && !initialBuild) captureTreeState(tree) else TreeStateSnapshot(emptySet(), null)
                val root = buildTreeRoot(viewKind)

                model.setRoot(root)
                model.reload()

                if (initialized && !initialBuild) {
                    restoreTreeState(tree, root, snapshot)
                } else if (viewKind == ViewKind.Projects) {
                    tree.expandGroupRows()
                }

                initialized = true
            }
        }

        private inner class ProjectTreeCellRenderer : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                val projectTreeNode = (value as? DefaultMutableTreeNode)?.userObject as? ProjectTreeNode

                icon = when (projectTreeNode?.kind) {
                    ProjectTreeNode.Kind.Project -> languageIcon(projectTreeNode.language)
                    ProjectTreeNode.Kind.Task -> AllIcons.General.GearPlain
                    ProjectTreeNode.Kind.TaskGroup -> AllIcons.General.GearPlain
                    else -> null
                }

                val attributes = when (projectTreeNode?.kind) {
                    ProjectTreeNode.Kind.Empty -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                    else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                }

                append(projectTreeNode?.label.orEmpty(), attributes)

                if (
                    projectTreeNode?.kind == ProjectTreeNode.Kind.Project &&
                    settingsService.preferProjectMetadata &&
                    !projectTreeNode.projectId.isNullOrBlank() &&
                    projectTreeNode.projectId != projectTreeNode.label
                ) {
                    append("  ${projectTreeNode.projectId}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                if (projectTreeNode?.kind == ProjectTreeNode.Kind.Project && projectTreeNode.effectiveCommand != null) {
                    append("  ${projectTreeNode.effectiveCommand}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                if (projectTreeNode?.kind == ProjectTreeNode.Kind.Task && projectTreeNode.command != null) {
                    val commandText = listOf(projectTreeNode.command, *projectTreeNode.args.toTypedArray())
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    append("  $commandText", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }

            private fun languageIcon(language: String?) = when (language?.lowercase()) {
                "typescript" -> MoonConsoleIcons.TypeScript
                "javascript" -> MoonConsoleIcons.JavaScript
                "rust" -> MoonConsoleIcons.Rust
                "csharp" -> MoonConsoleIcons.CSharp
                "ruby" -> MoonConsoleIcons.Ruby
                "python" -> MoonConsoleIcons.Python
                "java" -> MoonConsoleIcons.Java
                "go", "golang" -> MoonConsoleIcons.Go
                "swift" -> MoonConsoleIcons.Swift
                "dart" -> MoonConsoleIcons.Dart
                "c++", "cpp", "cplusplus" -> MoonConsoleIcons.CPlusPlus
                else -> MoonConsoleIcons.UnknownLanguage
            }
        }

        private enum class ViewKind(val title: String) {
            Projects("Projects"),
            Tags("Tags"),
            Tasks("Tasks"),
        }

    }
}
