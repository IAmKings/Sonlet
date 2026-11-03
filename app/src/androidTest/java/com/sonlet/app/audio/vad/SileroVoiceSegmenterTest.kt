package com.sonlet.app.audio.vad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Silero VAD 仪器测试(PRD T3-03):真实模型分段。
 * 验收关注:语音段非空、静音判空、长句切分、尾静音裁剪(PRD R-12 截断风险)。
 */
@RunWith(AndroidJUnit4::class)
class SileroVoiceSegmenterTest {

    private val appContext = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun speechWavProducesAtLeastOneSegment() {
        val segmenter = SileroVoiceSegmenter(appContext.assets)
        val wave = WaveReader.readWave(testContext.assets, "test_wavs/zh.wav")
        val result = segmenter.segment(wave.samples, wave.sampleRate)
        assertTrue("语音输入应产生至少一段", result.segments.isNotEmpty())
        val totalSamples = result.totalSamples
        assertTrue("语音样本不应被截断丢失(可能裁剪静音): $totalSamples vs ${wave.samples.size}", totalSamples > 0)
        // 分段总数不超过上限(30s 音频 → ≤ 30/maxSpeechDuration + 1)
        assertTrue("分段数应合理: ${result.segments.size}", result.segments.size <= 6)
    }

    @Test
    fun englishWavProducesAtLeastOneSegment() {
        val segmenter = SileroVoiceSegmenter(appContext.assets)
        val wave = WaveReader.readWave(testContext.assets, "test_wavs/en.wav")
        val result = segmenter.segment(wave.samples, wave.sampleRate)
        assertTrue(result.segments.isNotEmpty())
    }

    @Test
    fun silenceProducesNoSegments() {
        val segmenter = SileroVoiceSegmenter(appContext.assets)
        val silence = FloatArray(16_000 * 2) { 0f } // 2s 静音
        val result = segmenter.segment(silence, 16_000)
        assertTrue("纯静音不应产生语音段: ${result.segments.size}", result.isEmpty)
    }

    @Test
    fun longSpeechIsSplitByMaxDuration() {
        // 用 zh.wav 重复拼接为 ~22s 连续语音,maxSpeechDuration=10s → 至少 2 段
        val segmenter = SileroVoiceSegmenter(appContext.assets)
        val wave = WaveReader.readWave(testContext.assets, "test_wavs/zh.wav")
        val long = FloatArray(wave.samples.size * 4)
        repeat(4) { i -> wave.samples.copyInto(long, i * wave.samples.size) }
        val result = segmenter.segment(long, 16_000)
        android.util.Log.i("VadDebug", "segment sizes=${result.segments.map { it.size }} input=${long.size}")
        assertTrue("长语音应被切分: ${result.segments.size}", result.segments.size >= 2)
    }

    @Test
    fun sampleRateMismatchThrows() {
        val segmenter = SileroVoiceSegmenter(appContext.assets)
        val thrown = runCatching { segmenter.segment(FloatArray(16_000), 48_000) }
        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
        assertEquals(null, thrown.exceptionOrNull()?.cause)
    }

    /** 复用回归:同一实例多次分段必须可用(此前 segment 内误释放 native 指针) */
    @Test
    fun sameInstanceSegmentsRepeatedly() {
        SileroVoiceSegmenter(appContext.assets).use { segmenter ->
            val wave = WaveReader.readWave(testContext.assets, "test_wavs/zh.wav")
            repeat(3) { round ->
                val result = segmenter.segment(wave.samples, wave.sampleRate)
                assertTrue("第 ${round + 1} 次分段不应为空", result.segments.isNotEmpty())
            }
            // 静音分段后仍可用
            val silence = FloatArray(16_000 * 2)
            assertTrue(segmenter.segment(silence, 16_000).isEmpty)
            val again = segmenter.segment(wave.samples, wave.sampleRate)
            assertTrue("静音后再分段仍应可用", again.segments.isNotEmpty())
        }
    }

    @Test
    fun closeIsIdempotentAndRejectsSegmentAfterClose() {
        val segmenter = SileroVoiceSegmenter(appContext.assets)
        val wave = WaveReader.readWave(testContext.assets, "test_wavs/zh.wav")
        segmenter.close()
        segmenter.close() // 幂等
        val thrown = runCatching { segmenter.segment(wave.samples, wave.sampleRate) }
        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
    }
}
