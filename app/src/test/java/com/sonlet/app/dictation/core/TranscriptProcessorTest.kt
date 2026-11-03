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
}
