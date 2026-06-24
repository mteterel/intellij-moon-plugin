package dev.mteterel.moonintellij

import dev.mteterel.moonintellij.graph.MoonGraphVirtualFile
import dev.mteterel.moonintellij.toolWindow.MoonConsoleIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class MoonFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        return if (file is MoonGraphVirtualFile) {
            MoonConsoleIcons.MoonIconFile
        } else if (!file.isDirectory && file.name == "moon.yml") {
            MoonConsoleIcons.MoonIconFile
        } else {
            null
        }
    }
}
