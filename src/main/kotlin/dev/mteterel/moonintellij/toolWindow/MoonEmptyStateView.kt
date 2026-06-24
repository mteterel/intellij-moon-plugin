package dev.mteterel.moonintellij.toolWindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

class MoonEmptyStateView(
    private val project: Project,
    private val onRefresh: () -> Unit,
    private val onInitializeMoon: () -> Unit,
) {
    fun create(): JComponent =
        object : JBPanel<JBPanel<*>>(BorderLayout()) {
            init {
                border = JBUI.Borders.empty(24)
            }
        }.apply {
            add(createContent(), BorderLayout.CENTER)
        }

    private fun createContent() = JBPanel<JBPanel<*>>().apply {
        isOpaque = false
        border = JBUI.Borders.empty(24)
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        alignmentX = Component.CENTER_ALIGNMENT

        add(JBLabel(MoonConsoleIcons.MoonLogoLarge).apply {
            alignmentX = Component.CENTER_ALIGNMENT
        })
        add(Box.createVerticalStrut(16))
        add(JBLabel("Moon isn’t initialized here").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            font = font.deriveFont(Font.BOLD, 18f)
            foreground = UIUtil.getLabelForeground()
        })
        add(Box.createVerticalStrut(8))
        add(JBLabel("This workspace does not contain a `.moon` folder yet.").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = JBColor(0x666666, 0xA9A9A9)
        })
        add(JBLabel("You can initialize Moon, or try refreshing once the workspace is ready.").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = JBColor(0x666666, 0xA9A9A9)
        })
        add(Box.createVerticalStrut(20))
        add(JBPanel<JBPanel<*>>().apply {
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
            layout = FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0)
            add(initMoonButton())
            add(refreshWorkspaceButton())
        })
        add(Box.createVerticalStrut(18))
        add(createDocsLink())
    }

    private fun refreshWorkspaceButton() =
        JButton().apply {
            text = "Refresh"
            addActionListener { onRefresh() }
        }

    private fun initMoonButton() =
        JButton().apply {
            text = "Initialize moon"
            addActionListener { onInitializeMoon() }
        }

    private fun createDocsLink(): JComponent {
        val link = JLabel("<html><a href='https://moonrepo.dev/docs'>Open Moonrepo docs</a></html>").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = JBColor(0x4B5563, 0xAAB2C0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        link.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                BrowserUtil.browse("https://moonrepo.dev/docs")
            }
        })
        return link
    }
}
