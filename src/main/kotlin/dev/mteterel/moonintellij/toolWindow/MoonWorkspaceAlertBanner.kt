package dev.mteterel.moonintellij.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent

class MoonWorkspaceAlertBanner {
    private val label = JBLabel().apply {
        foreground = JBColor(0xB3261E, 0xFF8A80)
        border = JBUI.Borders.emptyLeft(6)
    }

    val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = true
        border = JBUI.Borders.empty(6, 8)
        background = JBColor(0xFDECEA, 0x4A1F24)
        add(
            JBLabel().apply {
                icon = AllIcons.General.ErrorDialog
                foreground = JBColor(0xB3261E, 0xFF8A80)
            },
            BorderLayout.WEST
        )
        add(label, BorderLayout.CENTER)
        isVisible = false
    }

    fun setMessage(message: String?) {
        label.text = message.orEmpty()
        component.isVisible = message != null
    }
}
