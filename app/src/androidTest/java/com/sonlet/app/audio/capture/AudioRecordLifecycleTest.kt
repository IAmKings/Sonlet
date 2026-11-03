package com.sonlet.app.audio.capture

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 采集生命周期仪器测试(T2-01、AC-P0-006/007/022 兼容路径):
 * 真实 AudioRecord 的启动、有界缓冲、幂等取消与重复启动互斥。
 * 使用 1.5s 短上限避免长时间占用。
 */
@RunWith(AndroidJUnit4::class)
class AudioRecordLifecycleTest {

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext

    private val options = CaptureOptions(maxDurationMs = 1_500)

    @Test
    fun startStopReturnsBoundedMonoSamples() = runBlocking {
        val recorder = PcmAudioRecorder(context, options)
        recorder.start(1L, options)
        Thread.sleep(600)
        val result = recorder.stop(1L)
        assertTrue("应采集到样本: ${result.samples.size}", result.samples.size in 1..24_000)
        // durationMs 由样本数换算:16kHz 下 samples ≈ durationMs × 16
        val expected = result.durationMs * 16
        assertTrue(
            "时长与样本数应一致: ${result.samples.size} vs $expected",
            kotlin.math.abs(result.samples.size - expected) <= 16,
        )
        assertTrue("未达上限", !result.durationCapped)
        assertEquals(0L, recorder.currentDurationMs)
        recorder.dispose()
    }

    @Test
    fun cancelDiscardsAudioAndReleases() = runBlocking {
        val recorder = PcmAudioRecorder(context, options)
        recorder.start(2L, options)
        Thread.sleep(300)
        recorder.cancel(2L)
        // 取消后可立即重新启动(幂等释放)
        recorder.start(3L, options)
        Thread.sleep(400)
        val result = recorder.stop(3L)
        assertTrue(result.samples.isNotEmpty())
        recorder.dispose()
    }

    @Test
    fun doubleStartIsRejectedAsBusy() = runBlocking {
        val recorder = PcmAudioRecorder(context, options)
        recorder.start(4L, options)
        val thrown = runCatching { recorder.start(5L, options) }
        assertTrue(thrown.exceptionOrNull() is AudioCaptureException)
        assertEquals(
            AudioCaptureErrorCode.RECORDER_BUSY,
            (thrown.exceptionOrNull() as AudioCaptureException).code,
        )
        recorder.cancel(4L)
        recorder.dispose()
    }

    @Test
    fun cancelIsIdempotentAndToleratesUnknownSession() = runBlocking {
        val recorder = PcmAudioRecorder(context, options)
        recorder.cancel(99L) // 无活动会话:静默幂等
        recorder.start(6L, options)
        recorder.cancel(99L) // 非归属会话:忽略(不变量 4)
        recorder.cancel(6L)
        recorder.cancel(6L)
        recorder.dispose()
    }

    @Test
    fun durationCapAutoStopsRecording() = runBlocking {
        val recorder = PcmAudioRecorder(context, options)
        recorder.start(7L, options)
        // 上限 1.5s;给足 2.5s 让读取循环触发自动停止
        Thread.sleep(2_500)
        val result = recorder.stop(7L)
        assertTrue("应达到上限自动停止", result.durationCapped)
        assertEquals("样本应有界", (1_500 * options.sampleRate / 1000).toLong(), result.samples.size.toLong())
        recorder.dispose()
    }

    @Test
    fun stopFromForeignSessionIsRejected() = runBlocking {
        val recorder = PcmAudioRecorder(context, options)
        recorder.start(8L, options)
        val thrown = runCatching { recorder.stop(9L) }
        assertTrue(thrown.exceptionOrNull() is AudioCaptureException)
        recorder.cancel(8L)
        recorder.dispose()
    }

    @Test
    fun actualSampleRateMatchesRequest() = runBlocking {
        val recorder = PcmAudioRecorder(context, options)
        // createRecord 内部校验 getSampleRate == 16k,启动成功即代表格式受支持(AC-P0-022)
        recorder.start(10L, options)
        recorder.cancel(10L)
        recorder.dispose()
    }
}
