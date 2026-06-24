package dev.mteterel.moonintellij.toolWindow

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.max

class MoonLastRunReportPanel(
    private val onRefreshRequested: () -> Unit,
) {
    private var report: LastRunReport? = null

    private val contentPanel = JBPanel<JBPanel<*>>(BorderLayout())

    val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.empty()
        background = UIUtil.getPanelBackground()
        add(contentPanel, BorderLayout.CENTER)
    }

    fun loadFrom(basePath: String?) {
        report = basePath?.let(::loadReport)
        updateDisplay()
    }

    private fun updateDisplay() {
        contentPanel.removeAll()
        contentPanel.add(createStatusStrip(), BorderLayout.CENTER)
        contentPanel.revalidate()
        component.revalidate()
        component.repaint()
    }

    private fun createSummary(currentReport: LastRunReport): JComponent =
        SimpleColoredComponent().apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(4)
            val summaryText = buildSummaryText(currentReport)
            toolTipText = buildSummaryTooltip(currentReport)
            append(summaryText, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

    private fun createStatusStrip(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 4, 8)

        val currentReport = report
        if (currentReport == null) {
            add(
                JBLabel("No run report yet").apply {
                    foreground = UIUtil.getLabelForeground().darker()
                },
                BorderLayout.WEST,
            )
            return@apply
        }

        val counts = buildStatusCounts(currentReport.items)
        val row = createRowContent(counts, currentReport)

        add(row, BorderLayout.CENTER)
    }

    private fun createRefreshButton(): JComponent =
        object : JComponent() {
            private var hovered = false

            init {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Refresh Last Run Report"
                preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
                minimumSize = preferredSize
                maximumSize = preferredSize

                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        hovered = false
                        repaint()
                    }

                    override fun mouseClicked(e: MouseEvent) {
                        if (e.button == MouseEvent.BUTTON1) {
                            onRefreshRequested()
                        }
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    if (hovered) {
                        g2.color = JBColor(Color(0, 0, 0, 10), Color(255, 255, 255, 18))
                        g2.fillRoundRect(0, 0, width - 1, height - 1, JBUI.scale(10), JBUI.scale(10))
                    }
                    val icon = AllIcons.Actions.Refresh
                    val iconX = (width - icon.iconWidth) / 2
                    val iconY = (height - icon.iconHeight) / 2
                    icon.paintIcon(this, g2, iconX, iconY)
                } finally {
                    g2.dispose()
                }
            }
        }

    private fun createRowContent(counts: LastRunCounts, currentReport: LastRunReport): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(createClickableStatusBadges(currentReport, counts), BorderLayout.WEST)
            add(createSummary(currentReport), BorderLayout.CENTER)
            add(createRefreshButton(), BorderLayout.EAST)
        }

    private fun createClickableStatusBadges(report: LastRunReport, counts: LastRunCounts): JComponent {
        val badges = buildList {
            add(BadgeData(counts.success, BadgePalette.Green))
            add(BadgeData(counts.cached, BadgePalette.Purple))
            if (counts.failed > 0) add(BadgeData(counts.failed, BadgePalette.Red))
        }

        return object : JComponent() {
            private var hovered = false

            init {
                isOpaque = false
                toolTipText = "View Last Run Report"
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                val anchor = this
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        hovered = false
                        repaint()
                    }

                    override fun mouseClicked(e: MouseEvent) {
                        if (e.button == MouseEvent.BUTTON1) {
                            showLastRunReportPopup(anchor, report, e.point)
                        }
                    }
                })
            }

            override fun getPreferredSize(): Dimension {
                val badgeSize = JBUI.scale(18)
                val spacing = JBUI.scale(3)
                val horizontalPadding = JBUI.scale(6)
                val verticalPadding = JBUI.scale(2)
                val badgeWidth = badges.sumOf { badgeSize } + spacing * max(0, badges.size - 1)
                return Dimension(horizontalPadding * 2 + badgeWidth, verticalPadding * 2 + badgeSize)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                    if (hovered) {
                        g2.color = JBColor(Color(0, 0, 0, 10), Color(255, 255, 255, 18))
                        g2.fillRoundRect(0, 0, width - 1, height - 1, JBUI.scale(12), JBUI.scale(12))
                        g2.color = JBColor(Color(0, 0, 0, 24), Color(255, 255, 255, 40))
                        g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(12), JBUI.scale(12))
                    }

                    val badgeSize = JBUI.scale(18)
                    val spacing = JBUI.scale(3)
                    val startX = JBUI.scale(6)
                    val y = (height - badgeSize) / 2

                    badges.forEachIndexed { index, badge ->
                        val x = startX + index * (badgeSize + spacing)
                        paintBadge(g2, x, y, badgeSize, badge.count, badge.palette)
                    }
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    private fun paintBadge(g2: Graphics2D, x: Int, y: Int, size: Int, count: Int, palette: BadgePalette) {
        val diameter = max(JBUI.scale(14), size - JBUI.scale(2))
        val circleX = x + (size - diameter) / 2
        val circleY = y + (size - diameter) / 2

        g2.color = palette.fill
        g2.fillOval(circleX, circleY, diameter, diameter)
        g2.color = palette.stroke
        g2.drawOval(circleX, circleY, diameter, diameter)

        g2.font = g2.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(9f).toFloat())
        val metrics = g2.fontMetrics
        val text = count.toString()
        val textX = circleX + (diameter - metrics.stringWidth(text)) / 2
        val textY = circleY + (diameter - metrics.height) / 2 + metrics.ascent
        g2.color = palette.text
        g2.drawString(text, textX, textY)
    }

    private fun buildStatusCounts(items: List<LastRunItem>): LastRunCounts {
        var white = 0
        var success = 0
        var cached = 0
        var failed = 0

        items.forEach { item ->
            when (item.status) {
                LastRunStatus.None, LastRunStatus.Skipped -> white++
                LastRunStatus.Success -> success++
                LastRunStatus.Cached -> cached++
                LastRunStatus.Failed -> failed++
            }
        }

        return LastRunCounts(
            white = white,
            success = success,
            cached = cached,
            failed = failed,
        )
    }

    private fun buildSummaryText(report: LastRunReport): String =
        report.duration?.let { "Completed in $it" } ?: "Completed"

    private fun buildSummaryTooltip(report: LastRunReport, completedAt: Instant? = report.completedAt): String {
        val statusSummary = when {
            report.status.isNotBlank() && !report.duration.isNullOrBlank() -> "${report.status} in ${report.duration}"
            report.status.isNotBlank() -> report.status
            !report.duration.isNullOrBlank() -> "Completed in ${report.duration}"
            else -> "Completed"
        }
        val relativeTime = completedAt?.let(::formatRelativeTime)?.let { "($it)" }
        return listOfNotNull(statusSummary, relativeTime).joinToString(" ")
    }

    private fun loadReport(basePath: String): LastRunReport? {
        val reportPath = runReportPath(basePath)
        if (!java.nio.file.Files.exists(reportPath)) return null
        return runCatching { parseRunReport(java.nio.file.Files.readString(reportPath)) }.getOrNull()
    }

    private fun runReportPath(basePath: String): java.nio.file.Path =
        java.nio.file.Paths.get(basePath).toAbsolutePath().normalize().resolve(".moon/cache/runReport.json").normalize()

    private fun parseRunReport(json: String): LastRunReport? {
        if (json.isBlank()) return null

        val root = JsonParser.parseString(json).asJsonObjectOrNull() ?: return null
        val context = root.objectValue("context")
        val actions = root.arrayValue("actions")?.toList().orEmpty()

        return LastRunReport(
            status = root.stringValue("status")?.replaceFirstChar { it.uppercase() }.orEmpty(),
            duration = root.objectValue("duration")?.formatDuration(),
            targets = context?.stringArray("primaryTargets").orEmpty().ifEmpty {
                context?.stringArray("initialTargets").orEmpty()
            },
            changedFiles = context?.stringArray("changedFiles").orEmpty(),
            items = actions.mapNotNull { parseRunAction(it) },
            completedAt = root.stringValue("finishedAt")?.toInstantOrNull()
                ?: actions.mapNotNull { it.asJsonObjectOrNull()?.stringValue("finishedAt")?.toInstantOrNull() }.maxOrNull(),
        )
    }

    private fun parseRunAction(element: JsonElement): LastRunItem? {
        val action = element.asJsonObjectOrNull() ?: return null
        val status = action.stringValue("status").orEmpty()
        val duration = action.objectValue("duration")?.formatDuration()
        val details = buildList {
            add(lastRunDetail(status, duration))
            if (action.booleanValue("flaky") == true) add("flaky")
            if (action.booleanValue("allowFailure") == true) add("allowed to fail")
        }

        return LastRunItem(
            label = action.stringValue("label") ?: return null,
            status = lastRunStatus(status),
            duration = duration,
            detail = details.joinToString(" | ").takeIf { it.isNotBlank() },
        )
    }

    private fun showLastRunReportPopup(anchor: JComponent, currentReport: LastRunReport, point: java.awt.Point) {
        val popupContent = createLastRunReportPopupContent(currentReport)
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupContent, null)
            .setTitle("Last Run Report")
            .setResizable(true)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .createPopup()

        popup.show(com.intellij.ui.awt.RelativePoint(anchor, point))
    }

    private fun createLastRunReportPopupContent(report: LastRunReport): JComponent {
        val listPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)

            report.items.forEachIndexed { index, item ->
                add(createLastRunTaskRow(item))
                if (index != report.items.lastIndex) {
                    add(JBPanel<JBPanel<*>>().apply {
                        isOpaque = false
                        preferredSize = Dimension(0, JBUI.scale(6))
                    })
                }
            }
        }

        return ScrollPaneFactory.createScrollPane(listPanel, true).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(JBUI.scale(360), JBUI.scale(240))
            background = UIUtil.getPanelBackground()
            viewport.background = UIUtil.getPanelBackground()
        }
    }

    private fun createLastRunTaskRow(item: LastRunItem): JComponent {
        val durationText = when (item.status) {
            LastRunStatus.Skipped -> "skipped"
            else -> item.duration.orEmpty()
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)

            add(
                JBLabel(statusEmoji(item.status)).apply {
                    border = JBUI.Borders.emptyRight(8)
                    val baseFont = this.font
                    this.font = baseFont.deriveFont(Font.PLAIN, JBUI.scaleFontSize(12f).toFloat())
                },
                BorderLayout.WEST,
            )

            add(
                JBLabel(item.label).apply {
                    font = font.deriveFont(Font.PLAIN)
                },
                BorderLayout.CENTER,
            )

            add(
                JBLabel(durationText).apply {
                    foreground = JBColor(0x7A818D, 0x9298A4)
                },
                BorderLayout.EAST,
            )
        }
    }

    private fun lastRunDetail(status: String, duration: String?): String {
        val normalizedStatus = when (status.lowercase()) {
            "completed" -> "passed"
            "failure", "errored", "aborted" -> "failed"
            else -> status.lowercase().ifBlank { "unknown" }
        }
        return listOfNotNull(duration, normalizedStatus).joinToString(" | ")
    }

    private fun lastRunStatus(status: String) = when (status.lowercase()) {
        "passed", "completed" -> LastRunStatus.Success
        "cached" -> LastRunStatus.Cached
        "skipped" -> LastRunStatus.Skipped
        "failed", "failure", "errored", "aborted" -> LastRunStatus.Failed
        else -> LastRunStatus.None
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

    private fun JsonObject.arrayValue(vararg keys: String): com.google.gson.JsonArray? {
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

    private fun JsonObject.booleanValue(key: String): Boolean? {
        val value = get(key)
        return if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) value.asBoolean else null
    }

    private fun JsonObject.longValue(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L

    private fun JsonObject.formatDuration(): String {
        val seconds = longValue("secs")
        val nanos = longValue("nanos")
        val millis = seconds * 1000.0 + nanos / 1_000_000.0
        return if (millis >= 1000) {
            "%.1fs".format(millis / 1000.0)
        } else {
            "%.1fms".format(millis)
        }
    }

    private fun String.toInstantOrNull(): Instant? =
        runCatching { Instant.parse(this) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(this).atOffset(ZoneOffset.UTC).toInstant() }.getOrNull()

    private fun formatRelativeTime(instant: Instant): String {
        val elapsed = Duration.between(instant, Instant.now())
        if (elapsed.isNegative) return "just now"

        val seconds = elapsed.seconds
        return when {
            seconds < 60 -> "just now"
            seconds < 3_600 -> formatElapsedUnit(seconds / 60, "minute")
            seconds < 86_400 -> formatElapsedUnit(seconds / 3_600, "hour")
            else -> formatElapsedUnit(seconds / 86_400, "day")
        }
    }

    private fun formatElapsedUnit(value: Long, unit: String): String {
        val suffix = if (value == 1L) unit else "${unit}s"
        return "$value $suffix ago"
    }

    private data class LastRunCounts(
        val white: Int,
        val success: Int,
        val cached: Int,
        val failed: Int,
    )

    private enum class BadgePalette(
        val fill: JBColor,
        val stroke: JBColor,
        val text: JBColor,
    ) {
        White(
            fill = JBColor(0xFFFFFF, 0xFFFFFF),
            stroke = JBColor(0xC7CDD8, 0x767E8E),
            text = JBColor(0x364152, 0x252B36),
        ),
        Green(
            fill = JBColor(0x3BA55D, 0x4CC46F),
            stroke = JBColor(0x2E8B51, 0x389757),
            text = JBColor(0xFFFFFF, 0xFFFFFF),
        ),
        Purple(
            fill = JBColor(0x8B5CF6, 0xA78BFA),
            stroke = JBColor(0x6D28D9, 0x7C3AED),
            text = JBColor(0xFFFFFF, 0xFFFFFF),
        ),
        Red(
            fill = JBColor(0xE25555, 0xF16C6C),
            stroke = JBColor(0xB83A3A, 0xC94A4A),
            text = JBColor(0xFFFFFF, 0xFFFFFF),
        );

        fun tooltip(count: Int): String = "${name.lowercase().replaceFirstChar { it.uppercase() }}: $count"
    }

    private data class BadgeData(
        val count: Int,
        val palette: BadgePalette,
    )

    private fun statusEmoji(status: LastRunStatus): String = when (status) {
        LastRunStatus.None, LastRunStatus.Skipped -> "⚪"
        LastRunStatus.Success -> "🟢"
        LastRunStatus.Cached -> "🟣"
        LastRunStatus.Failed -> "🔴"
    }
}
