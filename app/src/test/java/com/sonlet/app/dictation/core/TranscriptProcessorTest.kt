package com.sonlet.app.dictation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 转写格式化测试(PRD §16.1 TranscriptProcessorTest、§12.1) */
class TranscriptProcessorTest {

    private val processor = VerbatimTranscriptProcessor()

    @Test
    fun `verbatim keeps model output including punctuation`() {
        val result = processor.process("开饭时间早上9点至下午5点。")
        assertEquals("开饭时间早上9点至下午5点。", result.formatted)
        assertTrue(result.transformations.isEmpty())
    }

    @Test
    fun `leading and trailing whitespace trimmed`() {
        val result = processor.process("  你好世界 \n")
        assertEquals("你好世界", result.formatted)
        assertEquals(listOf("trim_edges"), result.transformations)
    }

    @Test
    fun `blank-only result becomes empty`() {
        val result = processor.process("   ")
        assertEquals("", result.formatted)
    }

    @Test
    fun `empty input stays empty without transformations`() {
        val result = processor.process("")
        assertEquals("", result.formatted)
        assertTrue(result.transformations.isEmpty())
    }

    @Test
    fun `internal content preserved verbatim - no semantic words removed`() {
        val input = "嗯 这个 这个 方案 我觉得 可以"
        val result = processor.process(input)
        // §12.2:不盲目删除“嗯/啊”等可能影响语义的词
        assertEquals(input, result.formatted)
    }

    @Test
    fun `raw is always preserved alongside formatted`() {
        val result = processor.process("  hi  ")
        assertEquals("  hi  ", result.raw)
        assertEquals("hi", result.formatted)
    }

    // ---- P1 风格处理(§12.2:语言敏感、保守、变换可追溯) ----

    @Test
    fun `natural collapses repeated adjacent tokens`() {
        val result = processor.process("嗯 这个 这个 方案 我觉得 可以", DictationStyle.NATURAL)
        assertEquals("嗯 这个 方案 我觉得 可以", result.formatted)
        assertTrue("collapse_repeated_tokens" in result.transformations)
        // 原文保留,处理可逆
        assertEquals("嗯 这个 这个 方案 我觉得 可以", result.raw)
    }

    @Test
    fun `natural collapses triple char stutter but keeps two char reduplication`() {
        assertEquals("我 觉得 好", processor.process("我我我 觉得 好", DictationStyle.NATURAL).formatted)
        // 叠词名词不受损("笑笑"不折叠)
        assertEquals("笑笑 说话", processor.process("笑笑 说话", DictationStyle.NATURAL).formatted)
    }

    @Test
    fun `concise removes closed-set fillers`() {
        val result = processor.process("嗯 这个 方案 呃 可以", DictationStyle.CONCISE)
        assertEquals("这个 方案 可以", result.formatted)
        assertTrue("remove_fillers" in result.transformations)
    }

    @Test
    fun `concise keeps potentially semantic interjections`() {
        // PRD §12.2:不盲目删除可能影响语义的词;"啊"不在封闭填充词表
        assertEquals("啊 这样", processor.process("啊 这样", DictationStyle.CONCISE).formatted)
    }

    @Test
    fun `concise is language sensitive for english fillers`() {
        val result = processor.process("um this is uh good", DictationStyle.CONCISE)
        assertEquals("this is good", result.formatted)
    }

    @Test
    fun `notes splits sentences to lines`() {
        val result = processor.process("第一句。第二句!第三句", DictationStyle.NOTES)
        assertEquals("第一句\n第二句\n第三句", result.formatted)
        assertTrue("split_to_lines" in result.transformations)
    }

    @Test
    fun `all styles leave verbatim untouched`() {
        val input = "嗯 这个 这个 原样 保留。"
        val result = processor.process(input, DictationStyle.VERBATIM)
        assertEquals(input, result.formatted)
        assertTrue(result.transformations.isEmpty())
    }

    @Test
    fun `style result becoming blank yields empty formatted`() {
        val result = processor.process("嗯 呃", DictationStyle.CONCISE)
        assertEquals("", result.formatted)
        assertTrue("blank_after_style" in result.transformations)
    }
}
