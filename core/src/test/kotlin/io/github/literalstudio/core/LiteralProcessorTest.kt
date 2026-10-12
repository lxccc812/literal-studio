package io.github.literalstudio.core

import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 全部用例使用真实纯 JVM 核心；夹具均为虚构文本，无 IDE、数据库、网络、存储或解释器，也无 mock。 */
class LiteralProcessorTest {
    private val processor = LiteralProcessor()
    private fun success(text: String, source: LiteralFormat = LiteralFormat.AUTO, target: LiteralFormat = LiteralFormat.AUTO) =
        assertIs<ProcessingResult.Success>(processor.process(text, source, target))
    private fun failure(text: String, source: LiteralFormat = LiteralFormat.AUTO, target: LiteralFormat = LiteralFormat.AUTO) =
        assertIs<ProcessingResult.Failure>(processor.process(text, source, target)).diagnostic

    @Test
    fun commonValuesRoundTripAcrossAllFormats() {
        // 三类输入包含嵌套、标量、空容器和非 BMP 字符；九种转换读回 JSON 后应得到同一结构。
        val fixtures = mapOf(
            LiteralFormat.JSON to """{"name":"示例😀","active":true,"items":[1,2.5,null,{"empty":[]}]}""",
            LiteralFormat.JAVASCRIPT to """{name:'示例😀',active:true,items:[1,2.5,null,{empty:[]},],}""",
            LiteralFormat.PYTHON to """{'name':'示例😀','active':True,'items':[1,2.5,None,{'empty':[]},],}""",
        )
        val expected = success(fixtures.getValue(LiteralFormat.JSON), LiteralFormat.JSON).output
        for ((source, input) in fixtures) for (target in fixtures.keys) {
            val converted = success(input, source, target).output!!
            assertEquals(expected, success(converted, target, LiteralFormat.JSON).output, "$source → $target")
        }
    }

    @Test
    fun detectionHasStablePriorityAndReportsAmbiguity() {
        // 共同语法不能唯一推断语言；顺序必须为 JSON、JS、Python，同时允许手动覆盖。
        val result = success("{\"items\": [1, 2]}")
        assertEquals(LiteralFormat.JSON, result.inputFormat)
        assertEquals(listOf(LiteralFormat.JSON, LiteralFormat.JAVASCRIPT, LiteralFormat.PYTHON), result.matchingFormats)
        assertEquals(LiteralFormat.PYTHON, success("[]", LiteralFormat.PYTHON).inputFormat)
        assertEquals(LiteralFormat.JAVASCRIPT, success("{key: 'value'}").inputFormat)
        assertEquals(LiteralFormat.PYTHON, success("{'key': None}").inputFormat)
    }

    @Test
    fun strictJsonRejectsRelaxedSyntax() {
        // 手动 JSON 模式不能偷偷降级成宽松解析；引号、逗号、注释、数字和空白必须严格验证。
        for (input in listOf("{'x':1}", "{x:1}", "[1,]", "/*注释*/[]", "True", "01", "+1", ".5", "1.", "\u00a0[]")) {
            failure(input, LiteralFormat.JSON)
        }
    }

    @Test
    fun commentsAndTrailingCommasAreLanguageSpecific() {
        // JS 注释与 Python 注释在各自语言中可移除；格式化不应改动字符串中的注释标记。
        assertEquals("[\n  \"// # /* */\",\n  true\n]", success("/*开头*/['// # /* */', // 行注释\n true,]", LiteralFormat.JAVASCRIPT).output)
        assertEquals("[\n  True,\n  None\n]", success("[True, #注释\n None,]", LiteralFormat.PYTHON).output)
        failure("[#注释\n1]", LiteralFormat.JAVASCRIPT)
        failure("[1, /*注释*/2]", LiteralFormat.PYTHON)
        assertTrue(failure("/*未关闭", LiteralFormat.JAVASCRIPT).message.contains("块注释"))
    }

    @Test
    fun keywordsCannotMixLanguages() {
        // 同一结构混合语言常量必须失败，自动识别不承担修补责任。
        for (input in listOf("[True, false]", "[None, null]", "{key: True}", "[true, False]")) failure(input)
    }

    @Test
    fun expressionsAndProgramsAreNeverEvaluated() {
        // 使用虚构变量及函数名，确认表达式、声明和扩展语法都只产生诊断，没有执行入口。
        for (input in listOf("{x: variable}", "[call()]", "{...other}", "const value = {}", "data = []", "[x for x in []]", "(1,2)", "{1,2}", "b'abc'", "`x${'$'}{value}`", "[undefined]", "[NaN]", "[Infinity]", "[1,,2]", "{short}")) failure(input)
    }

    @Test
    fun escapesPreserveStringsInsteadOfReplacingKeywords() {
        // 字符串中的 True/None、引号、反斜杠、控制字符应按转义解释，不能全局替换。
        val input = """{'text': 'True None false "quoted" \\ \n \t \x41 \u4e2d'}"""
        val converted = success(input, LiteralFormat.PYTHON, LiteralFormat.JSON).output!!
        assertTrue(converted.contains("True None false \\\"quoted\\\""))
        assertTrue(converted.contains("\\\\ \\n \\t A 中"))
        assertEquals(converted, success(success(converted, LiteralFormat.JSON, LiteralFormat.PYTHON).output!!, LiteralFormat.PYTHON, LiteralFormat.JSON).output)
    }

    @Test
    fun unicodeCodePointsRoundTripThroughPython() {
        // JSON 代理对和 JS 码点转义表示同一个非 BMP 字符；Python 输出应使用单码点形式。
        val json = "\"\\ud83d\\ude00\""
        val python = success(json, LiteralFormat.JSON, LiteralFormat.PYTHON).output!!
        assertEquals("\"\\U0001f600\"", python)
        assertEquals(json, success(python, LiteralFormat.PYTHON, LiteralFormat.JSON).output)
        assertEquals(json, success("'\\u{1f600}'", LiteralFormat.JAVASCRIPT, LiteralFormat.JSON).output)
        failure("'\\U00110000'", LiteralFormat.PYTHON)
        failure("'\\u{110000}'", LiteralFormat.JAVASCRIPT)
    }

    @Test
    fun isolatedSurrogatesAreNotSilentlyChanged() {
        // JSON 中的孤立代理项可按原值保留；转 Python 无法保证相同码点语义时必须明确拒绝。
        assertEquals("\"\\ud800\"", success("\"\\ud800\"", LiteralFormat.JSON).output)
        failure("\"\\ud800\"", LiteralFormat.JSON, LiteralFormat.PYTHON)
        assertIs<ProcessingResult.Failure>(processor.process("\"\\ud800\"", LiteralFormat.JSON, LiteralFormat.PYTHON, true))
        failure("'\\ud83d\\ude00'", LiteralFormat.PYTHON)
    }

    @Test
    fun unknownEscapesRespectLanguageRules() {
        // JS 的普通字符转义丢弃反斜杠，Python 保留；JSON 拒绝，不能共用一种宽松规则。
        assertEquals("\"q\"", success("'\\q'", LiteralFormat.JAVASCRIPT, LiteralFormat.JSON).output)
        assertEquals("\"\\\\q\"", success("'\\q'", LiteralFormat.PYTHON, LiteralFormat.JSON).output)
        failure("\"\\q\"", LiteralFormat.JSON)
        failure("'\\1'", LiteralFormat.JAVASCRIPT)
        assertEquals("\"A\"", success("'\\101'", LiteralFormat.PYTHON, LiteralFormat.JSON).output)
    }

    @Test
    fun duplicateKeysAreDetectedBeforeMapInsertion() {
        // 重复原文、转义后相同的字符串以及 Python 相等标量 key 均不得被覆盖。
        for ((source, input) in listOf(
            LiteralFormat.JSON to "{\"x\":1,\"x\":2}",
            LiteralFormat.JAVASCRIPT to "{x:1,'x':2}",
            LiteralFormat.PYTHON to "{'x':1,'\\x78':2}",
            LiteralFormat.PYTHON to "{True:1,1:2}",
            LiteralFormat.PYTHON to "{1:1,1.0:2}",
            LiteralFormat.PYTHON to "{-0.0:1,False:2}",
            LiteralFormat.PYTHON to "{0.1:1,0.10000000000000001:2}",
        )) assertTrue(failure(input, source).message.contains("重复 key"))
    }

    @Test
    fun pythonScalarKeysStayTypedAndCannotConvertToObjects() {
        // Python 数字、布尔和 None key 可留在 Python；跨格式不能把 key 字符串化。
        val input = "{2: 'number', False: 'boolean', None: 'empty', '2': 'string'}"
        val formatted = success(input, LiteralFormat.PYTHON).output!!
        assertTrue(formatted.contains("2: \"number\""))
        assertTrue(formatted.contains("False: \"boolean\""))
        for (target in listOf(LiteralFormat.JSON, LiteralFormat.JAVASCRIPT)) {
            assertTrue(failure(input, LiteralFormat.PYTHON, target).message.contains("非字符串 key"))
        }
        failure("{[]: 1}", LiteralFormat.PYTHON)
    }

    @Test
    fun memberOrderIsPreserved() {
        // 非字母顺序的 key 不应被排序，嵌套成员同样保持原始插入顺序。
        val result = success("{z:1,a:{y:2,b:3}}", LiteralFormat.JAVASCRIPT, LiteralFormat.JSON).output!!
        assertTrue(result.indexOf("\"z\"") < result.indexOf("\"a\""))
        assertTrue(result.indexOf("\"y\"") < result.indexOf("\"b\""))
    }

    @Test
    fun numbersKeepPrecisionAndNegativeZero() {
        // 大整数和长小数词面不经浮点中转；Python 1.0 和负零也不应被缩成整数 1 或正零。
        val input = "[123456789012345678901234567890,0.12345678901234567890123456789,-0,-0.0,1.0]"
        val output = success(input, LiteralFormat.JSON).output!!
        for (number in listOf("123456789012345678901234567890", "0.12345678901234567890123456789", "-0", "-0.0", "1.0")) assertTrue(output.contains(number))
        assertEquals(output, success(output, LiteralFormat.PYTHON, LiteralFormat.JSON).output)
        assertTrue(success(input, LiteralFormat.JSON, LiteralFormat.JAVASCRIPT).warnings.any { it.message.contains("安全整数") })
        assertTrue(success("9007199254740991", LiteralFormat.JSON, LiteralFormat.JAVASCRIPT).warnings.isEmpty())
    }

    @Test
    fun decimalSpellingsNormalizeWithoutChangingValues() {
        // 语言允许的前导零、小数省略位和分隔符需输出合法 JSON，不能修改实际数值。
        assertEquals("[\n  1.5,\n  0.5,\n  -0.5,\n  1.0,\n  1000\n]", success("[001.5,.5,-.5,1.,1_000]", LiteralFormat.PYTHON, LiteralFormat.JSON).output)
        assertEquals("[\n  255,\n  8,\n  3\n]", success("[0xff,0o10,0b11]", LiteralFormat.JAVASCRIPT, LiteralFormat.JSON).output)
        failure("01", LiteralFormat.PYTHON)
        failure("1__0", LiteralFormat.PYTHON)
        failure("1_", LiteralFormat.JAVASCRIPT)
        failure("0xff", LiteralFormat.JSON)
    }

    @Test
    fun nonFiniteAndUnderflowingNumbersCannotConvertSilently() {
        // JSON 可保存有限十进制词面，JS/Python float 超出范围时必须拒绝，不替换成 Infinity 或零。
        assertEquals("1e999", success("1e999", LiteralFormat.JSON).output)
        failure("1e999", LiteralFormat.JSON, LiteralFormat.JAVASCRIPT)
        failure("1e999", LiteralFormat.PYTHON)
        failure("1e-999", LiteralFormat.JAVASCRIPT)
        failure("1e10001", LiteralFormat.JSON)
        assertEquals(listOf(LiteralFormat.JSON), success("1e999").matchingFormats)
    }

    @Test
    fun prototypePropertyDoesNotPretendToBePlainData() {
        // __proto__ 在 JS 对象字面量具有特殊语义；普通 JSON 可保留，但 JS 解析或输出要拒绝。
        success("{\"__proto__\": {}}", LiteralFormat.JSON)
        failure("{__proto__: {}}", LiteralFormat.JAVASCRIPT)
        failure("{\"__proto__\": {}}", LiteralFormat.JSON, LiteralFormat.JAVASCRIPT)
    }

    @Test
    fun diagnosticsUseOriginalLineAndColumn() {
        // LF、CRLF 和 CR 均计作一次换行；诊断必须定位第二行错误值，自动模式采用最远错误。
        for (newline in listOf("\n", "\r\n", "\r")) {
            val diagnostic = failure("[$newline  @]", LiteralFormat.JSON)
            assertEquals(2, diagnostic.position.line)
            assertEquals(3, diagnostic.position.column)
        }
        val auto = failure("{'x': [1, @]}")
        assertEquals(11, auto.position.column)
    }

    @Test
    fun malformedStringsAndIncompleteStructuresFail() {
        // 缺失结束符、逗号、冒号、无效转义或额外尾部内容都不能自动修补。
        for (input in listOf("", "[", "{", "{\"x\" 1}", "[1 2]", "\"unterminated", "\"a\nb\"", "\"\\u12zz\"", "[] []", "{\"x\":}")) failure(input, LiteralFormat.JSON)
        failure("'''multi'''", LiteralFormat.PYTHON)
    }

    @Test
    fun checkOnlyValidatesTargetWithoutGeneratingOutput() {
        // 手动检查与格式化共用校验；检查模式不生成输出，但仍拒绝目标格式无法表示的 key。
        val checked = assertIs<ProcessingResult.Success>(processor.process("{'x':True}", LiteralFormat.PYTHON, LiteralFormat.JSON, true))
        assertNull(checked.output)
        assertEquals(LiteralFormat.JSON, checked.outputFormat)
        assertIs<ProcessingResult.Failure>(processor.process("{1:True}", LiteralFormat.PYTHON, LiteralFormat.JSON, true))
    }

    @Test
    fun inputDepthNodeAndOutputLimitsAreEnforced() {
        // 注入小限额便于稳定触发边界，实际解析器和渲染器不 mock；达到限额可成功，超限必须失败。
        fun withLimits(limits: ProcessingLimits, input: String) = LiteralProcessor(limits).process(input, LiteralFormat.JSON)
        assertIs<ProcessingResult.Success>(withLimits(ProcessingLimits(maxInputChars = 2), "[]"))
        assertIs<ProcessingResult.Failure>(withLimits(ProcessingLimits(maxInputChars = 2), "[1]"))
        assertIs<ProcessingResult.Success>(withLimits(ProcessingLimits(maxDepth = 2), "[[]]"))
        assertIs<ProcessingResult.Failure>(withLimits(ProcessingLimits(maxDepth = 2), "[[[]]]"))
        assertIs<ProcessingResult.Success>(withLimits(ProcessingLimits(maxNodes = 2), "[1]"))
        assertIs<ProcessingResult.Failure>(withLimits(ProcessingLimits(maxNodes = 2), "[1,2]"))
        assertIs<ProcessingResult.Success>(withLimits(ProcessingLimits(maxOutputChars = 2), "[]"))
        assertIs<ProcessingResult.Failure>(withLimits(ProcessingLimits(maxOutputChars = 2), "[1]"))
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingSyntaxFailure() {
        // 真实线程中断模拟旧任务取消；不能吞掉取消并返回语法错误，且测试结束后清除中断状态。
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { processor.process("[]") }
        } finally {
            Thread.interrupted()
        }
        assertFalse(Thread.currentThread().isInterrupted)
        success("[]")
    }

    @Test
    fun timeoutAppliesToTheWholeRequest() {
        // 使用真实时钟与极小预算处理大字符串，验证长 token 扫描能中途退出而非一直占用线程。
        val result = LiteralProcessor(ProcessingLimits(maxMillis = 1)).process("\"" + "a".repeat(900_000) + "\"", LiteralFormat.JSON)
        assertTrue(assertIs<ProcessingResult.Failure>(result).diagnostic.message.contains("时间限制"))
    }

    @Test
    fun longNumericTokensCannotOverflowTheParserStack() {
        // 使用最大允许长度的真实数字，验证扫描是线性的；再多一个字符必须按限额报错。
        val input = "9".repeat(4096)
        assertEquals(input, success(input, LiteralFormat.PYTHON).output)
        assertTrue(failure(input + "9", LiteralFormat.PYTHON).message.contains("4096"))
    }

    @Test
    fun invalidNumericSeparatorsAndUnicodeDigitsAreRejected() {
        // 分隔符不能挨着边界或另一分隔符，数字及 Unicode 转义只接受语言规定的 ASCII 数位。
        for (source in listOf(LiteralFormat.JAVASCRIPT, LiteralFormat.PYTHON)) {
            for (input in listOf("1_.2", "1._2", "1e_2", "1e2_", "0x__1", "0x1_", "0b2", "0o8", "１", "+-0x1")) failure(input, source)
        }
        failure("\"\\u１２３４\"", LiteralFormat.JSON)
        assertEquals("16", success("0x_10", LiteralFormat.PYTHON, LiteralFormat.JSON).output)
        failure("0x_10", LiteralFormat.JAVASCRIPT)
    }

    @Test
    fun whitespaceUsesLanguageSpecificSets() {
        // Unicode 分隔符不能被笼统当作任意语言空白；JS 与 Python 的 formfeed 按各自规则接受。
        success("\u00a0\uFEFF[]", LiteralFormat.JAVASCRIPT)
        success("\u000c[]", LiteralFormat.PYTHON)
        failure("\u0085[]", LiteralFormat.JAVASCRIPT)
        failure("\u001c[]", LiteralFormat.JAVASCRIPT)
        failure("\u00a0[]", LiteralFormat.PYTHON)
        failure("'a\u0000b'", LiteralFormat.PYTHON)
    }

    @Test
    fun javascriptLineSeparatorsHaveCorrectStringSemantics() {
        // 现代 JS 允许字符串内的 Unicode 行分隔符；反斜杠续行则移除分隔符，不改变相邻字符。
        assertEquals("\"a\\u2028b\\u2029c\"", success("'a\u2028b\u2029c'", LiteralFormat.JAVASCRIPT, LiteralFormat.JSON).output)
        assertEquals("\"abc\"", success("'a\\\u2028b\\\u2029c'", LiteralFormat.JAVASCRIPT, LiteralFormat.JSON).output)
    }
}
