package io.github.literalstudio.plugin

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 使用真实无窗口 Swing 组件和 EDT；固定逻辑尺寸为虚构布局夹具，无 IDE、数据库、网络或 mock。 */
class WrappingRowTest {
    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun control(width: Int, height: Int = 32) = JPanel().apply {
        preferredSize = JBUI.size(width, height)
    }
    private fun fit(row: WrappingRow, width: Int) {
        row.setSize(JBUI.scale(width), 0)
        row.size = Dimension(row.width, row.preferredSize.height)
        row.doLayout()
    }

    @Test
    fun wideRowsKeepControlsCompact() = onEdt {
        // 宽窗口的选择控件应并排且保持自然宽度，不能再次拉满窗口。
        val row = WrappingRow().apply { add(control(240)); add(control(240)) }
        fit(row, 1200)
        assertEquals(row.components[0].y, row.components[1].y)
        assertEquals(JBUI.scale(240), row.components[0].width)
        assertEquals(JBUI.scale(240), row.components[1].width)
        assertTrue(row.preferredSize.width < row.width)
    }

    @Test
    fun narrowRowsExposeEveryControlWithinBounds() = onEdt {
        // 窄侧栏及带边框区域必须自动折行；所有控件完整处于真实首选高度内，互不重叠。
        val row = WrappingRow().apply {
            border = JBUI.Borders.empty(8, 10)
            add(control(120)); add(control(180)); add(control(90))
        }
        fit(row, 260)
        assertTrue(row.components[1].y > row.components[0].y)
        for (component in row.components) {
            assertTrue(component.x >= row.insets.left)
            assertTrue(component.x + component.width <= row.width - row.insets.right)
            assertTrue(component.y + component.height <= row.height - row.insets.bottom)
        }
        for ((index, component) in row.components.withIndex()) for (other in row.components.drop(index + 1)) {
            assertTrue(!component.bounds.intersects(other.bounds))
        }
    }

    @Test
    fun trailingActionsAlignAndSurviveWindowResizing() = onEdt {
        // 输出标题栏宽时复制操作靠右，缩窄后整体折行，重新放大后恢复对齐而不隐藏操作。
        val row = WrappingRow(alignTrailing = true).apply { add(control(120)); add(control(180)) }
        fit(row, 800)
        assertEquals(row.width, row.components[1].x + row.components[1].width)
        val wideHeight = row.height
        fit(row, 220)
        assertTrue(row.height > wideHeight)
        assertTrue(row.components[1].y > row.components[0].y)
        assertTrue(row.components[1].x + row.components[1].width <= row.width)
        fit(row, 800)
        assertEquals(wideHeight, row.height)
        assertEquals(row.width, row.components[1].x + row.components[1].width)
    }
}
