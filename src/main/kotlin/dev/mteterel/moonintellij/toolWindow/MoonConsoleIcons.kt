package dev.mteterel.moonintellij.toolWindow

import com.intellij.openapi.util.IconLoader
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.Icon

object MoonConsoleIcons {
    @JvmField
    val MoonLogo = IconLoader.getIcon("/icons/logo.svg", javaClass)

    @JvmField
    val MoonLogoLarge = ScaledIcon(MoonLogo, 72)

    @JvmField
    val MoonIconPurple = IconLoader.getIcon("/icons/moon_purple.svg", javaClass)

    @JvmField
    val MoonIconFile = IconLoader.getIcon("/icons/moon_file.svg", javaClass)

    @JvmField
    val JavaScript = IconLoader.getIcon("/icons/languages/javascript.svg", javaClass)

    @JvmField
    val TypeScript = IconLoader.getIcon("/icons/languages/typescript.svg", javaClass)

    @JvmField
    val Rust = IconLoader.getIcon("/icons/languages/rust.svg", javaClass)

    @JvmField
    val CSharp = IconLoader.getIcon("/icons/languages/csharp.svg", javaClass)

    @JvmField
    val Ruby = IconLoader.getIcon("/icons/languages/ruby.svg", javaClass)

    @JvmField
    val Python = IconLoader.getIcon("/icons/languages/python.svg", javaClass)

    @JvmField
    val Java = IconLoader.getIcon("/icons/languages/java.svg", javaClass)

    @JvmField
    val Go = IconLoader.getIcon("/icons/languages/go.svg", javaClass)

    @JvmField
    val Swift = IconLoader.getIcon("/icons/languages/swift.svg", javaClass)

    @JvmField
    val Dart = IconLoader.getIcon("/icons/languages/dart.svg", javaClass)

    @JvmField
    val CPlusPlus = IconLoader.getIcon("/icons/languages/cplusplus.svg", javaClass)

    @JvmField
    val UnknownLanguage = IconLoader.getIcon("/icons/languages/unknown.svg", javaClass)
}

class ScaledIcon(
    private val delegate: Icon,
    private val size: Int,
) : Icon {
    override fun getIconWidth() = size

    override fun getIconHeight() = size

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val graphics2d = graphics.create() as Graphics2D
        try {
            val scaleX = size.toDouble() / delegate.iconWidth.coerceAtLeast(1)
            val scaleY = size.toDouble() / delegate.iconHeight.coerceAtLeast(1)
            graphics2d.translate(x.toDouble(), y.toDouble())
            graphics2d.scale(scaleX, scaleY)
            delegate.paintIcon(component, graphics2d, 0, 0)
        } finally {
            graphics2d.dispose()
        }
    }
}
