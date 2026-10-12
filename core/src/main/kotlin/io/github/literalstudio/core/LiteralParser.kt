package io.github.literalstudio.core

import java.math.BigDecimal
import java.math.BigInteger

/** 递归下降解析纯数据子集；扫描字符串时处理转义，绝不执行代码或替换整段输入。 */
internal class LiteralParser(
    private val source: String,
    private val format: LiteralFormat,
    private val limits: ProcessingLimits,
    private val budget: WorkBudget,
) {
    private var offset = 0
    private var line = 1
    private var column = 1
    private var nodes = 0
    private val python get() = format == LiteralFormat.PYTHON
    private val javascript get() = format == LiteralFormat.JAVASCRIPT
    private val json get() = format == LiteralFormat.JSON

    fun parse(): LiteralNode {
        skipTrivia()
        val result = value(0)
        skipTrivia()
        if (peek() != null) fail("数据结束后仍有内容；首版不支持赋值声明或完整程序")
        budget.check(position())
        return result
    }

    private fun value(depth: Int): LiteralNode {
        skipTrivia()
        val start = position()
        budget.check(start)
        if (++nodes > limits.maxNodes) fail("节点数超过 ${limits.maxNodes} 限制", start)
        return when (peek()) {
            '{' -> objectValue(depth, start)
            '[' -> arrayValue(depth, start)
            '\'', '"' -> LiteralNode.StringNode(stringValue(), start)
            '-', '+', '.' -> numberValue(start)
            in '0'..'9' -> numberValue(start)
            null -> fail("需要数据值，输入却已结束")
            else -> {
                val word = identifier()
                when {
                    word == if (python) "True" else "true" -> LiteralNode.BooleanNode(true, start)
                    word == if (python) "False" else "false" -> LiteralNode.BooleanNode(false, start)
                    word == if (python) "None" else "null" -> LiteralNode.NullNode(start)
                    else -> fail("不支持的值或表达式；变量、函数、undefined、NaN/Infinity 不会被求值", start)
                }
            }
        }
    }

    private fun enter(depth: Int, start: SourcePosition) {
        if (depth >= limits.maxDepth) fail("嵌套深度超过 ${limits.maxDepth} 限制", start)
        advance()
    }

    private fun objectValue(depth: Int, start: SourcePosition): LiteralNode {
        enter(depth, start)
        skipTrivia()
        val members = mutableListOf<Member>()
        val keys = hashSetOf<String>()
        if (take('}')) return LiteralNode.ObjectNode(members, start)
        while (true) {
            skipTrivia()
            val keyPosition = position()
            val key = if (python) {
                val parsed = value(depth + 1)
                if (parsed is LiteralNode.ObjectNode || parsed is LiteralNode.ArrayNode) {
                    fail("Python dict 的 key 必须是可哈希的标量，首版不支持 tuple", keyPosition)
                }
                parsed
            } else {
                if (++nodes > limits.maxNodes) fail("节点数超过 ${limits.maxNodes} 限制", keyPosition)
                val text = when {
                    peek() == '"' || (!json && peek() == '\'') -> stringValue()
                    javascript && identifierStart() -> identifier()
                    else -> fail("需要字符串 key${if (javascript) " 或未引号属性名" else "（双引号）"}")
                }
                // 对象字面量的 __proto__ 是原型设置语法，不是普通数据成员，不能假装无副作用。
                if (javascript && text == "__proto__") fail("JS __proto__ 属性具有原型语义，不能作为普通数据转换", keyPosition)
                LiteralNode.StringNode(text, keyPosition)
            }
            if (!keys.add(keyIdentity(key))) fail("重复 key；不允许静默覆盖成员", keyPosition)
            skipTrivia()
            expect(':', "key 后需要冒号 ':'")
            members.add(Member(key, value(depth + 1)))
            skipTrivia()
            if (take('}')) break
            expect(',', "成员之间需要逗号 ',' 或结束符 '}'")
            skipTrivia()
            if (peek() == '}') {
                if (json) fail("严格 JSON 不允许末尾逗号")
                advance()
                break
            }
        }
        return LiteralNode.ObjectNode(members, start)
    }

    private fun arrayValue(depth: Int, start: SourcePosition): LiteralNode {
        enter(depth, start)
        skipTrivia()
        val items = mutableListOf<LiteralNode>()
        if (take(']')) return LiteralNode.ArrayNode(items, start)
        while (true) {
            items.add(value(depth + 1))
            skipTrivia()
            if (take(']')) break
            expect(',', "元素之间需要逗号 ',' 或结束符 ']'")
            skipTrivia()
            if (peek() == ']') {
                if (json) fail("严格 JSON 不允许末尾逗号")
                advance()
                break
            }
        }
        return LiteralNode.ArrayNode(items, start)
    }

    /** Python 将 True、1、1.0 视为相等的 key，校验也采用相同数值等价关系。 */
    private fun keyIdentity(key: LiteralNode): String = when (key) {
        is LiteralNode.StringNode -> "s:${key.value}"
        is LiteralNode.NumberNode -> {
            // Python float key 按运行时浮点相等性比较，但节点仍保存原词面以便明确提示精度风险。
            val number = if (key.decimal) {
                val approximate = key.text.toDouble()
                if (!approximate.isFinite()) fail("Python float key 超出有限数范围", key.position)
                BigDecimal(approximate)
            } else BigDecimal(key.text)
            "n:${number.stripTrailingZeros()}"
        }
        is LiteralNode.BooleanNode -> "n:${if (key.value) "1" else "0"}"
        is LiteralNode.NullNode -> "null"
        else -> error("复合 key 已在解析阶段拒绝")
    }

    private fun numberValue(start: SourcePosition): LiteralNode.NumberNode {
        val begin = offset
        // 收集完整数字候选后按该语言的词法验证，避免将 1foo、1+2 等截断成合法数据。
        while (peek()?.let { it.isLetterOrDigit() || it in ".+-_" } == true) {
            advance()
            if (offset - begin > 4096) fail("数字词面超过 4096 字符限制", start)
        }
        val token = source.substring(begin, offset)
        val unsigned = if (token.startsWith('+') || token.startsWith('-')) token.substring(1) else token
        val radix = when {
            unsigned.startsWith("0x", true) -> 16
            unsigned.startsWith("0o", true) -> 8
            unsigned.startsWith("0b", true) -> 2
            else -> null
        }
        if (radix != null && !json) {
            if (javascript && (token.startsWith('-') || token.startsWith('+'))) fail("JS 非十进制字面量不支持一元符号", start)
            val digits = unsigned.substring(2)
            if (!radixDigitsValid(digits, radix)) fail("非法的进制数字或分隔符", start)
            val integer = BigInteger(digits.replace("_", ""), radix)
            return checkedNumber(LiteralNode.NumberNode(if (token.startsWith('-')) "-${integer}" else integer.toString(), false, start))
        }
        if (!decimalSyntaxValid(token)) fail("不符合 ${format.label} 的数字语法", start)
        val clean = token.replace("_", "").removePrefix("+")
        val decimal = clean.contains('.') || clean.contains('e', true)
        val mantissa = clean.substringBefore('e').substringBefore('E')
        if (!decimal && !json && mantissa.removePrefix("-").let { it.length > 1 && it.startsWith('0') && it.any { c -> c != '0' } }) {
            fail("整数不允许含非零数字的前导零", start)
        }
        val exponent = clean.substringAfter('e', clean.substringAfter('E', "0"))
        if (exponent.toIntOrNull()?.let { it in -10_000..10_000 } != true) fail("指数超过首版允许的 ±10000 范围", start)
        // 只规范词面，不转换为浮点数；Python 多个零整数规范为单零，保留负零符号。
        var normalized = when {
            clean.startsWith("-.") -> "-0${clean.substring(1)}"
            clean.startsWith('.') -> "0$clean"
            else -> clean
        }
        if (mantissa.endsWith('.')) normalized = normalized.replaceFirst(".", ".0")
        if (!decimal) normalized = (if (clean.startsWith('-')) "-" else "") + clean.removePrefix("-").trimStart('0').ifEmpty { "0" }
        if (python && decimal) {
            // Python 允许 001.5；输出 JSON 必须去除前导零，而不改变小数或指数词面。
            val sign = if (normalized.startsWith('-')) "-" else ""
            val unsignedText = normalized.removePrefix("-")
            val integerEnd = unsignedText.indexOfFirst { it == '.' || it == 'e' || it == 'E' }.let {
                if (it < 0) unsignedText.length else it
            }
            normalized = sign + unsignedText.substring(0, integerEnd).trimStart('0').ifEmpty { "0" } + unsignedText.substring(integerEnd)
        }
        return checkedNumber(LiteralNode.NumberNode(normalized, decimal, start))
    }

    private fun checkedNumber(node: LiteralNode.NumberNode): LiteralNode.NumberNode {
        // 识别阶段也检查源语言的数值范围，避免将 Infinity 词面列为可手动切换的有效格式。
        if (javascript || (python && node.decimal)) {
            val approximate = node.text.toDouble()
            if (!approximate.isFinite() || (approximate == 0.0 && BigDecimal(node.text).signum() != 0)) {
                fail("数字在 JS Number 或 Python float 中溢出或下溢，不能无损处理", node.position)
            }
        }
        return node
    }

    private fun stringValue(): String {
        val start = position()
        val quote = peek()!!
        if (json && quote != '"') fail("严格 JSON 字符串必须使用双引号", start)
        advance()
        if (python && peek() == quote && source.getOrNull(offset + 1) == quote) fail("首版不支持 Python 三引号字符串", start)
        val result = StringBuilder()
        while (true) {
            val charPosition = position()
            val c = peek() ?: fail("字符串缺少结束引号", start)
            advance()
            when {
                c == quote -> return result.toString()
                c == '\\' -> escape(result, charPosition)
                c == '\r' || c == '\n' -> fail("单行字符串不能包含未转义换行", charPosition)
                json && c.code < 32 -> fail("JSON 字符串不能包含未转义控制字符", charPosition)
                python && c == '\u0000' -> fail("Python 原文不能包含 NUL 字符，请使用转义", charPosition)
                else -> result.append(c)
            }
        }
    }

    /** 每种语言单独解释转义，保留字符串内的 True/None 和引号，不触碰外层结构。 */
    private fun escape(result: StringBuilder, start: SourcePosition) {
        val escaped = peek() ?: fail("反斜杠后缺少转义字符", start)
        advance()
        when (escaped) {
            '"', '\\' -> result.append(escaped)
            '\'' -> if (!json) result.append('\'') else fail("JSON 不支持单引号转义", start)
            '/' -> if (!python) result.append('/') else result.append("\\/")
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'v' -> if (!json) result.append('\u000b') else fail("JSON 不支持 \\v 转义", start)
            'a' -> if (python) result.append('\u0007') else if (javascript) result.append('a') else fail("非法转义", start)
            'u' -> {
                if (javascript && take('{')) {
                    val begin = offset
                    while (peek()?.let { hexDigit(it) } != null && offset - begin < 6) advance()
                    val code = source.substring(begin, offset).toIntOrNull(16) ?: fail("非法 Unicode 转义", start)
                    expect('}', "Unicode 转义缺少 '}'")
                    if (code > 0x10ffff) fail("Unicode 码点超出范围", start)
                    result.appendCodePoint(code)
                } else {
                    val code = readHex(4, start)
                    // Python 的代理项是独立码点，与 UTF-16 代理对含义不同；首版明确拒绝歧义。
                    if (python && code in 0xd800..0xdfff) fail("首版不支持 Python 字符串中的代理项转义，请使用 Unicode 码点", start)
                    result.append(code.toChar())
                }
            }
            'U' -> if (python) {
                val code = readHex(8, start)
                if (code > 0x10ffff) fail("Unicode 码点超出范围", start)
                if (code in 0xd800..0xdfff) fail("首版不支持 Python 字符串中的代理项转义", start)
                result.appendCodePoint(code)
            } else if (javascript) result.append('U') else fail("JSON 不支持 \\U 转义", start)
            'x' -> if (!json) result.append(readHex(2, start).toChar()) else fail("JSON 不支持 \\x 转义", start)
            '\n', '\r' -> if (!json) {
                if (escaped == '\r' && peek() == '\n') advance()
            } else fail("JSON 不支持续行", start)
            '\u2028', '\u2029' -> if (!javascript) {
                if (python) result.append('\\').append(escaped) else fail("非法 JSON 转义", start)
            }
            in '0'..'7' -> when {
                python -> {
                    var code = escaped.digitToInt()
                    repeat(2) { if (peek() in '0'..'7') { code = code * 8 + peek()!!.digitToInt(); advance() } }
                    result.append(code.toChar())
                }
                javascript && escaped == '0' && peek()?.isDigit() != true -> result.append('\u0000')
                else -> fail("不支持八进制或数字转义", start)
            }
            else -> when {
                json -> fail("非法 JSON 转义", start)
                python && escaped == 'N' -> fail("首版不支持 Unicode 名称转义 \\N", start)
                python -> result.append('\\').append(escaped)
                javascript && escaped.isDigit() -> fail("不支持 JS 旧式数字转义", start)
                else -> result.append(escaped)
            }
        }
    }

    private fun readHex(count: Int, start: SourcePosition): Int {
        var value = 0L
        repeat(count) {
            val digit = peek()?.let { hexDigit(it) } ?: fail("转义需要 $count 位十六进制数字", start)
            value = value * 16 + digit
            advance()
        }
        if (value > 0x10ffff && count == 8) fail("Unicode 码点超出范围", start)
        return value.toInt()
    }

    private fun identifierStart(): Boolean {
        if (offset >= source.length) return false
        val code = source.codePointAt(offset)
        return code == '$'.code || code == '_'.code || Character.isUnicodeIdentifierStart(code)
    }

    private fun identifier(): String {
        val begin = offset
        if (!identifierStart()) fail("需要数据字面量；首版不支持此语法")
        do {
            val width = Character.charCount(source.codePointAt(offset))
            repeat(width) { advance() }
        } while (offset < source.length && source.codePointAt(offset).let {
                it == '$'.code || (Character.isUnicodeIdentifierPart(it) && !Character.isIdentifierIgnorable(it)) || it == 0x200c || it == 0x200d
            })
        return source.substring(begin, offset)
    }

    private fun skipTrivia() {
        while (true) {
            val c = peek() ?: return
            when {
                c in " \t\r\n" || (python && c == '\u000c') || (javascript && javascriptWhitespace(c)) -> advance()
                python && c == '#' -> while (peek()?.let { it !in "\r\n" } == true) advance()
                javascript && c == '/' && source.getOrNull(offset + 1) == '/' -> {
                    while (peek()?.let { it !in "\r\n\u2028\u2029" } == true) advance()
                }
                javascript && c == '/' && source.getOrNull(offset + 1) == '*' -> {
                    val start = position()
                    advance(); advance()
                    while (!(peek() == '*' && source.getOrNull(offset + 1) == '/')) {
                        if (peek() == null) fail("块注释缺少结束符 '*/'", start)
                        advance()
                    }
                    advance(); advance()
                }
                else -> return
            }
        }
    }

    private fun position() = SourcePosition(offset, line, column)
    private fun peek(): Char? = source.getOrNull(offset)
    private fun advance() {
        val c = source[offset++]
        when {
            c == '\r' -> { line++; column = 1 }
            c == '\n' -> { if (offset < 2 || source[offset - 2] != '\r') line++; column = 1 }
            javascript && c in "\u2028\u2029" -> { line++; column = 1 }
            else -> column++
        }
        if (offset % 1024 == 0) budget.check(position())
    }
    private fun take(c: Char): Boolean = if (peek() == c) { advance(); true } else false
    private fun expect(c: Char, message: String) { if (!take(c)) fail(message) }
    private fun fail(message: String, position: SourcePosition = position()): Nothing = throw LiteralProblem(Diagnostic(message, position))

    /** 数字也使用线性扫描，避免超长 token 触发正则回溯或递归栈溢出。 */
    private fun decimalSyntaxValid(token: String): Boolean {
        var cursor = 0
        if (token.getOrNull(cursor) in listOf('+', '-')) {
            if (json && token[cursor] == '+') return false
            cursor++
        }
        val integerStart = cursor
        fun digits(): Boolean {
            if (token.getOrNull(cursor) !in '0'..'9') return false
            cursor++
            while (true) {
                when {
                    token.getOrNull(cursor) in '0'..'9' -> cursor++
                    !json && token.getOrNull(cursor) == '_' && token.getOrNull(cursor + 1) in '0'..'9' -> cursor += 2
                    else -> return true
                }
            }
        }
        val hasInteger = digits()
        val integerEnd = cursor
        if (!python && hasInteger && token[integerStart] == '0' && integerEnd - integerStart > 1) return false
        var hasFraction = false
        if (token.getOrNull(cursor) == '.') {
            cursor++
            val hasDigits = digits()
            if (json && !hasDigits) return false
            hasFraction = hasDigits
        } else if (!hasInteger) return false
        if (!hasInteger && (json || !hasFraction)) return false
        if (token.getOrNull(cursor) in listOf('e', 'E')) {
            cursor++
            if (token.getOrNull(cursor) in listOf('+', '-')) cursor++
            if (!digits()) return false
        }
        return cursor == token.length
    }

    private fun radixDigitsValid(text: String, radix: Int): Boolean {
        val digits = if (python) text.removePrefix("_") else text
        if (digits.isEmpty()) return false
        for ((index, c) in digits.withIndex()) {
            if (c == '_') {
                if (index == 0 || index == digits.lastIndex || digits[index - 1] == '_' || digits[index + 1] == '_') return false
            } else if (hexDigit(c)?.let { it < radix } != true) return false
        }
        return true
    }

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    private fun javascriptWhitespace(c: Char): Boolean =
        c in "\u000b\u000c\uFEFF\u2028\u2029" || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
}
