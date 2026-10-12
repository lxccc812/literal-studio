package io.github.literalstudio.plugin

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import io.github.literalstudio.core.LiteralFormat
import io.github.literalstudio.core.LiteralProcessor
import io.github.literalstudio.core.Diagnostic
import io.github.literalstudio.core.ProcessingResult
import io.github.literalstudio.core.SourcePosition
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.Icon
import javax.swing.ScrollPaneConstants
import javax.swing.Timer

/** 窗口按需创建且不依赖索引，PyCharm 索引期间也能处理纯文本数据。 */
class LiteralToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LiteralPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        // 内容释放是编辑器、监听器和后台任务的统一生命周期边界，支持插件动态卸载。
        content.setDisposer(panel)
        content.preferredFocusableComponent = panel.inputFocus
        toolWindow.contentManager.addContent(content)
    }
}

private data class FormatChoice(val format: LiteralFormat, val label: String = format.label) {
    override fun toString() = label
}

/** EDT 只管理控件和请求快照；核心处理在共享线程池执行，不保存输入历史。 */
private class LiteralPanel(private val project: Project) : JBPanel<LiteralPanel>(BorderLayout(0, JBUI.scale(10))), Disposable {
    private val editorFactory = EditorFactory.getInstance()
    private val input = createEditor(false)
    private val output = createEditor(true)
    val inputFocus get() = input.contentComponent
    private val inputMode = ComboBox(LiteralFormat.entries.map { FormatChoice(it) }.toTypedArray())
    private val outputMode = ComboBox(LiteralFormat.entries.map {
        FormatChoice(it, if (it == LiteralFormat.AUTO) "跟随输入" else it.label)
    }.toTypedArray())
    private val detected = secondaryLabel("等待输入")
    private val inputBadge = secondaryLabel("自动识别")
    private val outputBadge = secondaryLabel("跟随输入")
    private val statusIcon = JBLabel(StatusTone.INFO.icon)
    private val status = JBTextArea(2, 0).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        // 提示区属于界面文案，使用 UI 字体；代码编辑器继续使用用户的编辑器字体。
        font = JBFont.label()
        foreground = StatusTone.INFO.color
        isOpaque = false
        border = JBUI.Borders.empty()
        text = "粘贴 JSON、JS/TS 数据字面量或 Python dict/list，停止输入后自动格式化。"
    }
    private val copy = actionButton("复制输出", AllIcons.Actions.Copy, "复制下方格式化结果").apply { isEnabled = false }
    private val processor = LiteralProcessor()
    private var revision = 0L
    private var pending: Future<*>? = null
    @Volatile private var disposed = false
    private val debounce = Timer(400) { submit(false) }.apply { isRepeats = false }
    private val copyFeedback = Timer(2000) { resetCopyFeedback() }.apply { isRepeats = false }

    init {
        // 使用主题与 DPI 感知留白；控件不拉满窗口，宽窗口并排，窄侧栏自动折行。
        border = JBUI.Borders.empty(12)
        val formats = WrappingRow().apply {
            add(formatRow("输入格式", inputMode))
            add(formatRow("输出格式", outputMode))
        }
        val actions = WrappingRow().apply {
            add(actionButton("格式检查", AllIcons.General.InspectionsOK, "只检查格式，不生成输出").apply {
                addActionListener { submit(true) }
            })
            add(actionButton("格式化", AllIcons.Actions.Edit, "格式化并转换到所选输出格式").apply {
                addActionListener { submit(false) }
            })
            add(secondaryLabel("停止输入后自动格式化"))
        }
        val controls = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(formats, BorderLayout.NORTH)
            add(actions, BorderLayout.CENTER)
        }
        val header = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(controls, BorderLayout.CENTER)
            add(detected, BorderLayout.SOUTH)
        }
        val splitter = OnePixelSplitter(true, 0.5f).apply {
            firstComponent = editorSection("输入", "保留原文", input, inputBadge)
            secondComponent = editorSection("输出", "只读预览", output, outputBadge, copy)
            border = JBUI.Borders.empty()
        }
        add(header, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        add(statusFooter(), BorderLayout.SOUTH)
        input.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule()
        }, this)
        inputMode.addActionListener { schedule() }
        outputMode.addActionListener { schedule() }
        copy.addActionListener {
            if (copy.isEnabled) {
                CopyPasteManager.getInstance().setContents(StringSelection(output.document.text))
                // 剪贴板写入完成后再确认成功；反馈放在操作按钮上，保留下方的精度与转换提示。
                copy.text = "复制成功"
                copy.icon = AllIcons.General.InspectionsOK
                copy.toolTipText = "输出已复制到剪贴板"
                copyFeedback.restart()
            }
        }
    }

    private fun createEditor(viewer: Boolean): Editor = editorFactory.createEditor(
        editorFactory.createDocument(""), project, PlainTextFileType.INSTANCE, viewer,
    ).apply {
        settings.isLineNumbersShown = true
        settings.isFoldingOutlineShown = false
        settings.isUseSoftWraps = true
    }

    private fun editorSection(title: String, caption: String, editor: Editor, badge: JBLabel, action: JButton? = null) =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border())
            val heading = WrappingRow(alignTrailing = true).apply {
                border = JBUI.Borders.empty(8, 10)
                add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(JBLabel(title).apply { font = JBFont.label().asBold() })
                    add(secondaryLabel(caption).apply { if (editor.isViewer) icon = AllIcons.Ide.Readonly })
                })
                add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(badge)
                    action?.let { add(it) }
                })
            }
            add(heading, BorderLayout.NORTH)
            add(editor.component, BorderLayout.CENTER)
        }

    private fun formatRow(label: String, choice: JComboBox<FormatChoice>) = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        choice.preferredSize = Dimension(JBUI.scale(180), choice.preferredSize.height)
        add(secondaryLabel(label), BorderLayout.WEST)
        add(choice, BorderLayout.CENTER)
    }

    private fun actionButton(label: String, icon: Icon, tooltip: String) = JButton(label, icon).apply {
        toolTipText = tooltip
        iconTextGap = JBUI.scale(6)
    }

    private fun statusFooter() = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(8, 2, 0, 2))
        add(statusIcon.apply { verticalAlignment = JBLabel.TOP }, BorderLayout.WEST)
        add(JBScrollPane(status).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
    }

    private fun showStatus(message: String, tone: StatusTone = StatusTone.INFO) {
        status.text = message
        status.foreground = tone.color
        statusIcon.icon = tone.icon
        statusIcon.toolTipText = message.lineSequence().firstOrNull()
    }

    private fun showDetection(message: String) {
        // 窄窗口允许标签省略显示，完整识别与歧义说明始终可通过 tooltip 查看。
        detected.text = message
        detected.toolTipText = message
    }

    private fun refreshBadges(source: LiteralFormat? = null, target: LiteralFormat? = null) {
        inputBadge.text = shortFormat(source ?: (inputMode.selectedItem as FormatChoice).format)
        outputBadge.text = target?.let { shortFormat(it) } ?: (outputMode.selectedItem as FormatChoice).let {
            if (it.format == LiteralFormat.AUTO) "跟随输入" else shortFormat(it.format)
        }
    }

    private fun shortFormat(format: LiteralFormat) = when (format) {
        LiteralFormat.AUTO -> "自动识别"
        LiteralFormat.JSON -> "JSON"
        LiteralFormat.JAVASCRIPT -> "JS/TS"
        LiteralFormat.PYTHON -> "Python"
    }

    private fun resetCopyFeedback() {
        // 连续复制延长反馈时间；输入变化时立即恢复按钮，避免旧成功提示对应新的输入。
        copyFeedback.stop()
        copy.text = "复制输出"
        copy.icon = AllIcons.Actions.Copy
        copy.toolTipText = "复制下方格式化结果"
    }

    /** 输入或模式改变时立即废弃旧输出，不能让旧成功结果看起来属于新的输入。 */
    private fun invalidatePreview() {
        revision++
        resetCopyFeedback()
        pending?.cancel(true)
        pending = null
        copy.isEnabled = false
        WriteAction.run<RuntimeException> { output.document.setText("") }
    }

    private fun schedule() {
        if (disposed) return
        invalidatePreview()
        refreshBadges()
        showDetection("等待检查")
        showStatus("输入已变化，等待自动检查…")
        debounce.restart()
    }

    private fun submit(checkOnly: Boolean) {
        if (disposed) return
        debounce.stop()
        invalidatePreview()
        // 大输入先检查长度，不在 EDT 复制超限文档；处理错误不会包含原始用户数据。
        if (input.document.textLength > 1_000_000) {
            showDetection("输入超限")
            showStatus("输入超过 1000000 字符限制，请缩小输入。", StatusTone.ERROR)
            return
        }
        val text = input.document.text
        if (text.isBlank()) {
            showDetection("等待输入")
            showStatus("请输入数据。")
            return
        }
        val requestRevision = revision
        val source = (inputMode.selectedItem as FormatChoice).format
        val target = (outputMode.selectedItem as FormatChoice).format
        showStatus("正在${if (checkOnly) "检查" else "格式化"}…")
        pending = AppExecutorUtil.getAppExecutorService().submit task@{
            val result = try {
                processor.process(text, source, target, checkOnly)
            } catch (_: CancellationException) {
                return@task
            } catch (error: RuntimeException) {
                // 内部异常也要结束“正在处理”状态；仅记异常类型，不将用户输入或异常原文写入日志。
                Logger.getInstance(LiteralPanel::class.java).warn("Literal Studio 内部处理异常：${error.javaClass.simpleName}")
                ProcessingResult.Failure(Diagnostic("处理发生内部错误，请缩小输入后重试。", SourcePosition(0, 1, 1)))
            }
            if (disposed || project.isDisposed || Thread.currentThread().isInterrupted) return@task
            // 平台统一调度工具窗口 EDT 更新；修订号防止已完成但尚未显示的旧任务回写。
            ToolWindowManager.getInstance(project).invokeLater {
                if (!disposed && !project.isDisposed && revision == requestRevision) show(result, checkOnly)
            }
        }
    }

    private fun show(result: ProcessingResult, checkOnly: Boolean) {
        when (result) {
            is ProcessingResult.Failure -> {
                showDetection("检查失败")
                val error = result.diagnostic
                showStatus("第 ${error.position.line} 行，第 ${error.position.column} 列：${error.message}", StatusTone.ERROR)
            }
            is ProcessingResult.Success -> {
                refreshBadges(result.inputFormat, result.outputFormat)
                showDetection("识别为 ${result.inputFormat.label}" + if (result.matchingFormats.size > 1) {
                    "（也符合 ${result.matchingFormats.drop(1).joinToString { it.label }}；可手动指定）"
                } else "")
                result.output?.let {
                    WriteAction.run<RuntimeException> { output.document.setText(it) }
                    output.caretModel.moveToOffset(0)
                    copy.isEnabled = true
                }
                showStatus((if (checkOnly) "格式检查通过。" else "已输出为 ${result.outputFormat.label}。") +
                    result.warnings.joinToString(separator = "\n", prefix = if (result.warnings.isEmpty()) "" else "\n") {
                        "提示（第 ${it.position.line} 行，第 ${it.position.column} 列）：${it.message}"
                    }, if (result.warnings.isEmpty()) StatusTone.SUCCESS else StatusTone.WARNING)
            }
        }
    }

    override fun dispose() {
        // 不保存文档、不记录内容日志；取消任务后释放两个独立的 IDE 编辑器。
        disposed = true
        revision++
        debounce.stop()
        copyFeedback.stop()
        pending?.cancel(true)
        editorFactory.releaseEditor(input)
        editorFactory.releaseEditor(output)
    }
}
