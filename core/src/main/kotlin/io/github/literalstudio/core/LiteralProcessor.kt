package io.github.literalstudio.core

import java.math.BigDecimal

/** 自动与手动入口共用同一服务；无状态实例可以安全地用于后台线程。 */
class LiteralProcessor(private val limits: ProcessingLimits = ProcessingLimits()) {
    fun process(
        input: String,
        inputFormat: LiteralFormat = LiteralFormat.AUTO,
        outputFormat: LiteralFormat = LiteralFormat.AUTO,
        checkOnly: Boolean = false,
    ): ProcessingResult {
        val start = SourcePosition(0, 1, 1)
        if (input.length > limits.maxInputChars) {
            return ProcessingResult.Failure(Diagnostic("输入超过 ${limits.maxInputChars} 字符限制", start))
        }
        val budget = WorkBudget(limits)
        return try {
            val formats = if (inputFormat == LiteralFormat.AUTO) {
                listOf(LiteralFormat.JSON, LiteralFormat.JAVASCRIPT, LiteralFormat.PYTHON)
            } else listOf(inputFormat)
            val successes = mutableListOf<Pair<LiteralFormat, LiteralNode>>()
            val failures = mutableListOf<Diagnostic>()
            for (format in formats) {
                budget.check(start)
                try {
                    successes.add(format to LiteralParser(input, format, limits, budget).parse())
                } catch (problem: LiteralProblem) {
                    failures.add(problem.diagnostic)
                    // 超时不能被当作普通语法失败继续尝试，否则请求可能超过总预算。
                    budget.check(problem.diagnostic.position)
                }
            }
            if (successes.isEmpty()) {
                // 自动识别优先报告解析进度最远的错误，避免只显示 JSON 对单引号的早期拒绝。
                throw LiteralProblem(failures.maxBy { it.position.offset })
            }
            val (detected, root) = successes.first()
            val target = if (outputFormat == LiteralFormat.AUTO) detected else outputFormat
            val warnings = mutableListOf<Diagnostic>()
            validate(root, detected, target, warnings, budget)
            val output = if (checkOnly) null else LiteralRenderer(target, limits, budget).render(root)
            budget.check(start)
            ProcessingResult.Success(detected, successes.map { it.first }, target, output, warnings.distinctBy { it.message })
        } catch (problem: LiteralProblem) {
            ProcessingResult.Failure(problem.diagnostic)
        }
    }

    /** 校验目标表示能力先于输出；不能通过改 key、丢类型或替换非有限数来凑出结果。 */
    private fun validate(
        node: LiteralNode,
        source: LiteralFormat,
        target: LiteralFormat,
        warnings: MutableList<Diagnostic>,
        budget: WorkBudget,
    ) {
        budget.check(node.position)
        when (node) {
            is LiteralNode.ObjectNode -> node.members.forEach {
                if (target != LiteralFormat.PYTHON && it.key !is LiteralNode.StringNode) {
                    throw LiteralProblem(Diagnostic("Python 非字符串 key 不能无损转换为 ${target.label}", it.key.position))
                }
                if (target == LiteralFormat.JAVASCRIPT && (it.key as? LiteralNode.StringNode)?.value == "__proto__") {
                    throw LiteralProblem(Diagnostic("JS __proto__ 属性具有原型语义，不能无损输出", it.key.position))
                }
                validate(it.key, source, target, warnings, budget)
                validate(it.value, source, target, warnings, budget)
            }
            is LiteralNode.ArrayNode -> node.items.forEach { validate(it, source, target, warnings, budget) }
            is LiteralNode.StringNode -> if (target == LiteralFormat.PYTHON) {
                // 检查模式也必须验证字符串可输出性，不能等格式化时才拒绝同一份输入。
                var index = 0
                while (index < node.value.length) {
                    val c = node.value[index++]
                    if (c.isHighSurrogate() && index < node.value.length && node.value[index].isLowSurrogate()) {
                        index++
                    } else if (c.isSurrogate()) {
                        throw LiteralProblem(Diagnostic("孤立代理项不能无损转换为首版 Python 字符串", node.position))
                    }
                    if (index % 1024 == 0) budget.check(node.position)
                }
            }
            is LiteralNode.NumberNode -> {
                val decimal = BigDecimal(node.text)
                val sourceFloat = source == LiteralFormat.JAVASCRIPT || (source == LiteralFormat.PYTHON && node.decimal)
                val targetFloat = target == LiteralFormat.JAVASCRIPT || (target == LiteralFormat.PYTHON && node.decimal)
                // Double 仅用于表示范围检测，输出和内部精度仍始终使用原数字词面。
                if (sourceFloat || targetFloat) {
                    val approximate = node.text.toDouble()
                    if (!approximate.isFinite() || (approximate == 0.0 && decimal.signum() != 0)) {
                        throw LiteralProblem(Diagnostic("数字在 JS Number 或 Python float 中溢出或下溢，不能无损处理", node.position))
                    }
                }
                if ((source == LiteralFormat.JAVASCRIPT || target == LiteralFormat.JAVASCRIPT) &&
                    decimal.stripTrailingZeros().scale() <= 0 && decimal.abs() > JS_SAFE_INTEGER) {
                    warn(warnings, "整数超出 JS 安全整数范围 ±9007199254740991；已保留原词面，JS 使用时可能丢失精度", node.position)
                }
                if (node.decimal && (sourceFloat || targetFloat)) {
                    warn(warnings, "已保留十进制数字词面；JS Number / Python float 的二进制浮点表示可能产生精度差异", node.position)
                }
            }
            else -> Unit
        }
    }

    private fun warn(warnings: MutableList<Diagnostic>, message: String, position: SourcePosition) {
        // 同类风险仅保留首个位置，避免大数组为相同提示分配数万个诊断对象。
        if (warnings.none { it.message == message }) warnings.add(Diagnostic(message, position))
    }

    companion object {
        private val JS_SAFE_INTEGER = BigDecimal("9007199254740991")
    }
}

/** 渲染只遍历统一结构，顺序和数值保持不变；独立输出限额阻止深度缩进膨胀。 */
internal class LiteralRenderer(
    private val format: LiteralFormat,
    private val limits: ProcessingLimits,
    private val budget: WorkBudget,
) {
    private val output = StringBuilder()
    private var currentPosition = SourcePosition(0, 1, 1)

    fun render(root: LiteralNode): String {
        write(root, 0)
        return output.toString()
    }

    private fun append(text: String) {
        if (text.length > limits.maxOutputChars - output.length) {
            throw LiteralProblem(Diagnostic("输出超过 ${limits.maxOutputChars} 字符限制", currentPosition))
        }
        output.append(text)
        budget.check(currentPosition)
    }

    private fun indent(depth: Int) = append("  ".repeat(depth))

    private fun write(node: LiteralNode, depth: Int) {
        currentPosition = node.position
        budget.check(node.position)
        when (node) {
            is LiteralNode.ObjectNode -> {
                append("{")
                node.members.forEachIndexed { index, member ->
                    append(if (index == 0) "\n" else ",\n")
                    indent(depth + 1)
                    write(member.key, depth + 1)
                    append(": ")
                    write(member.value, depth + 1)
                }
                if (node.members.isNotEmpty()) { append("\n"); indent(depth) }
                append("}")
            }
            is LiteralNode.ArrayNode -> {
                append("[")
                node.items.forEachIndexed { index, item ->
                    append(if (index == 0) "\n" else ",\n")
                    indent(depth + 1)
                    write(item, depth + 1)
                }
                if (node.items.isNotEmpty()) { append("\n"); indent(depth) }
                append("]")
            }
            is LiteralNode.StringNode -> string(node.value)
            is LiteralNode.NumberNode -> append(node.text)
            is LiteralNode.BooleanNode -> append(if (format == LiteralFormat.PYTHON) {
                if (node.value) "True" else "False"
            } else if (node.value) "true" else "false")
            is LiteralNode.NullNode -> append(if (format == LiteralFormat.PYTHON) "None" else "null")
        }
    }

    private fun string(value: String) {
        append("\"")
        // 控制字符、代理项和 JS 行分隔符统一转义，确保各语言读回时字符串值一致。
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            if (format == LiteralFormat.PYTHON && c.isHighSurrogate() && index < value.length && value[index].isLowSurrogate()) {
                // Python 用单个 Unicode 码点表示非 BMP 字符，不能输出两段代理项转义改变字符串值。
                val code = Character.toCodePoint(c, value[index++])
                append("\\U${code.toString(16).padStart(8, '0')}")
                continue
            }
            if (format == LiteralFormat.PYTHON && c.isSurrogate()) {
                throw LiteralProblem(Diagnostic("孤立代理项不能无损转换为首版 Python 字符串", currentPosition))
            }
            append(when (c) {
            '"' -> "\\\""
            '\\' -> "\\\\"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            '\b' -> "\\b"
            '\u000c' -> "\\f"
            else -> if (c.code < 32 || c.isSurrogate() || c in "\u2028\u2029") {
                "\\u${c.code.toString(16).padStart(4, '0')}"
            } else c.toString()
            })
        }
        append("\"")
    }
}
