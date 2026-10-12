package io.github.literalstudio.plugin

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Icon
import javax.swing.JPanel

/** 状态使用 IDE 主题色与平台图标，并保留文字说明，避免只靠颜色表达结果。 */
internal enum class StatusTone(val icon: Icon, val color: JBColor) {
    INFO(AllIcons.General.Information, JBColor.namedColor("Label.infoForeground", UIUtil.getContextHelpForeground())),
    SUCCESS(AllIcons.General.InspectionsOK, JBColor.namedColor("Label.successForeground", JBColor(0x287B42, 0x73BD79))),
    WARNING(AllIcons.General.Warning, JBColor.namedColor("Label.warningForeground", JBColor(0x946000, 0xD6AE58))),
    ERROR(AllIcons.General.Error, JBColor.namedColor("Label.errorForeground", JBColor(0xC13B3B, 0xE88383))),
}

internal fun secondaryLabel(text: String) = JBLabel(text).apply {
    font = JBFont.small()
    foreground = UIUtil.getContextHelpForeground()
}

/** 控件保持自然宽度；窄侧栏自动换行，并让父布局获得真实行高，避免按钮被裁掉。 */
internal class WrappingRow(alignTrailing: Boolean = false) : JPanel(WrappingRowLayout(alignTrailing)) {
    init {
        isOpaque = false
        addComponentListener(object : ComponentAdapter() {
            private var previousWidth = -1
            override fun componentResized(event: ComponentEvent) {
                if (width != previousWidth) {
                    previousWidth = width
                    revalidate()
                }
            }
        })
    }
}

private class WrappingRowLayout(private val alignTrailing: Boolean) : LayoutManager {
    override fun addLayoutComponent(name: String?, component: Component) = Unit
    override fun removeLayoutComponent(component: Component) = Unit
    override fun minimumLayoutSize(parent: Container) = Dimension(0, preferredLayoutSize(parent).height)
    override fun preferredLayoutSize(parent: Container) = measure(parent, false)
    override fun layoutContainer(parent: Container) { measure(parent, true) }

    private fun measure(parent: Container, layout: Boolean): Dimension {
        val insets = parent.insets
        val gap = JBUI.scale(8)
        // 首次挂载可能还没有自身宽度，先参考父容器；后续尺寸事件会重新计算折行高度。
        val containerWidth = parent.width.takeIf { it > 0 } ?: parent.parent?.width?.takeIf { it > 0 }
        val available = containerWidth?.let { (it - insets.left - insets.right).coerceAtLeast(0) } ?: Int.MAX_VALUE
        var y = insets.top
        var rowWidth = 0
        var rowHeight = 0
        var widest = 0
        val row = mutableListOf<Pair<Component, Dimension>>()
        fun finishRow() {
            if (row.isEmpty()) return
            var x = insets.left
            if (layout) for ((index, item) in row.withIndex()) {
                val (component, size) = item
                // 标题栏宽度足够时让最后一组靠右；窄窗口仍正常换行，不能挤压或遮挡复制按钮。
                if (alignTrailing && row.size > 1 && index == row.lastIndex && available != Int.MAX_VALUE) {
                    x = insets.left + available - size.width
                }
                component.setBounds(x, y + (rowHeight - size.height) / 2, size.width, size.height)
                x += size.width + gap
            }
            widest = maxOf(widest, rowWidth)
            y += rowHeight + gap
            row.clear()
            rowWidth = 0
            rowHeight = 0
        }
        for (component in parent.components.filter { it.isVisible }) {
            val preferred = component.preferredSize
            val size = Dimension(minOf(preferred.width, available), preferred.height)
            if (row.isNotEmpty() && rowWidth + gap + size.width > available) finishRow()
            rowWidth += (if (row.isEmpty()) 0 else gap) + size.width
            rowHeight = maxOf(rowHeight, size.height)
            row.add(component to size)
        }
        finishRow()
        return Dimension(widest + insets.left + insets.right, (y - gap).coerceAtLeast(insets.top) + insets.bottom)
    }
}
