package io.github.literalstudio.core

import java.util.concurrent.CancellationException

/** 输入模式与输出语言共用枚举；AUTO 仅用于输入，不能作为实际数据语言。 */
enum class LiteralFormat(val label: String) {
    AUTO("自动识别"), JSON("JSON"), JAVASCRIPT("JS/TS Object/Array"), PYTHON("Python dict/list")
}

/** 位置基于原始输入，行列从 1 开始，偏移量使用 IDE 文档相同的 UTF-16 单位。 */
data class SourcePosition(val offset: Int, val line: Int, val column: Int)

/** 成员使用列表保序；绝不先写入 Map，以免在校验前覆盖重复 key。 */
sealed interface LiteralNode {
    val position: SourcePosition
    data class ObjectNode(val members: List<Member>, override val position: SourcePosition) : LiteralNode
    data class ArrayNode(val items: List<LiteralNode>, override val position: SourcePosition) : LiteralNode
    data class StringNode(val value: String, override val position: SourcePosition) : LiteralNode
    // 数字保存规范化词面，不经 Double 中转；decimal 区分 Python int 与 float 的输出形式。
    data class NumberNode(val text: String, val decimal: Boolean, override val position: SourcePosition) : LiteralNode
    data class BooleanNode(val value: Boolean, override val position: SourcePosition) : LiteralNode
    data class NullNode(override val position: SourcePosition) : LiteralNode
}

data class Member(val key: LiteralNode, val value: LiteralNode)
data class Diagnostic(val message: String, val position: SourcePosition)

/** 失败时没有输出，调用方必须清空旧预览；识别结果包含所有可接受的语言而非唯一猜测。 */
sealed interface ProcessingResult {
    data class Success(
        val inputFormat: LiteralFormat,
        val matchingFormats: List<LiteralFormat>,
        val outputFormat: LiteralFormat,
        val output: String?,
        val warnings: List<Diagnostic>,
    ) : ProcessingResult
    data class Failure(val diagnostic: Diagnostic) : ProcessingResult
}

/** 限额覆盖一次完整请求，自动识别的多个尝试也共享时间预算。 */
data class ProcessingLimits(
    val maxInputChars: Int = 1_000_000,
    val maxDepth: Int = 128,
    val maxNodes: Int = 100_000,
    val maxOutputChars: Int = 4_000_000,
    val maxMillis: Long = 2_000,
) {
    init {
        require(maxInputChars > 0 && maxDepth > 0 && maxDepth <= 256 && maxNodes > 0 && maxOutputChars > 0)
        require(maxMillis in 1..60_000)
    }
}

internal class LiteralProblem(val diagnostic: Diagnostic) : RuntimeException(diagnostic.message)

/** 解析和渲染循环合作取消，不吞掉中断；位置由调用方提供，便于指出超限发生处。 */
internal class WorkBudget(private val limits: ProcessingLimits) {
    private val started = System.nanoTime()
    fun check(position: SourcePosition) {
        if (Thread.currentThread().isInterrupted) throw CancellationException("处理已取消")
        if ((System.nanoTime() - started) / 1_000_000 >= limits.maxMillis) {
            throw LiteralProblem(Diagnostic("处理超过 ${limits.maxMillis} ms 时间限制", position))
        }
    }
}
