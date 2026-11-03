package com.sonlet.app.asr.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CER/WER 度量口径测试(AC-P0-020 工装) */
class TextMetricsTest {

    @Test
    fun `cer is zero for identical text`() {
        assertEquals(0.0, TextMetrics.cer("开饭时间早上9点至下午5点。", "开饭时间早上9点至下午5点"), 1e-9)
    }

    @Test
    fun `cer counts character substitutions`() {
        // 参考 8 字,错 1 字 → 0.125
        assertEquals(0.125, TextMetrics.cer("开饭时间早上九点", "开饭时间早上九店"), 1e-9)
    }

    @Test
    fun `cer ignores punctuation and spaces`() {
        assertEquals(0.0, TextMetrics.cer("你好，世界。", "你好 世界"), 1e-9)
    }

    @Test
    fun `cer empty hypothesis scores one`() {
        assertEquals(1.0, TextMetrics.cer("有内容", ""), 1e-9)
        assertEquals(0.0, TextMetrics.cer("", ""), 1e-9)
    }

    @Test
    fun `wer counts word errors case-insensitively`() {
        // 6 词错 1 词
        assertEquals(1.0 / 6, TextMetrics.wer("The boy met the tribal chieftain", "The boy met the tribal chief"), 1e-9)
    }

    @Test
    fun `wer handles insertions`() {
        // 参考 2 词,识别多出 1 词(插入)→ 1/2
        assertEquals(0.5, TextMetrics.wer("你好 世界", "你好 的 世界"), 1e-9)
    }

    @Test
    fun `normalize strips fullwidth punctuation`() {
        // 全角字母/数字转半角;标点移除(不引入空格)
        assertEquals("abc123你好", TextMetrics.normalize("ＡＢＣ１２３，你好。"))
        assertEquals("hello world", TextMetrics.normalize("Hello, World!"))
    }

    @Test
    fun `levenshtein matches known distances`() {
        assertEquals(3, TextMetrics.levenshtein("kitten", "sitting"))
        assertEquals(1, TextMetrics.levenshtein("上午", "上店")) // 一次替换
        assertTrue(TextMetrics.levenshtein("abc", "abc") == 0)
    }
}
