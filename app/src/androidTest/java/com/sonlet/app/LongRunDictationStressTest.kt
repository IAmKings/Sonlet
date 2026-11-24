package com.sonlet.app

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sonlet.app.testing.AudioPermissionRule
import com.k2fsa.sherpa.onnx.WaveReader
import com.sonlet.app.asr.sherpa.ModelInstallStatus
import com.sonlet.app.asr.sherpa.ModelRepository
import com.sonlet.app.asr.sherpa.SherpaSenseVoiceEngine
import com.sonlet.app.audio.capture.CaptureOptions
import com.sonlet.app.audio.capture.AudioCapture
import com.sonlet.app.audio.capture.CaptureResult
import com.sonlet.app.audio.capture.PcmAudioRecorder
import com.sonlet.app.audio.vad.SileroVoiceSegmenter
import com.sonlet.app.dictation.core.DictationCommand
import com.sonlet.app.dictation.core.DictationController
import com.sonlet.app.dictation.core.DictationEvent
import com.sonlet.app.dictation.core.DictationPhase
import com.sonlet.app.dictation.core.ModelVerificationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 长稳测试(PRD AC-P0-019、§16.1 LongRunDictationStressTest):
 * 连续 100 次 3-10 秒量级录音/识别/取消组合,验证无 JNI 崩溃、
 * 录音资源可复用、无重复回填;每 20 轮记录 native 堆和 PSS。
 * 运行:./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sonlet.app.LongRunDictationStressTest
 */
@RunWith(AndroidJUnit4::class)
class LongRunDictationStressTest {

    @get:Rule
    val permissionRule: AudioPermissionRule = AudioPermissionRule()

    companion object {
        const val TOTAL_ROUNDS = 100
        const val TAG = "SonletStress"
    }

    @Test
    fun hundredSessionsWithoutResourceLeak(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        val repo = ModelRepository(context.assets)
        assertTrue("模型应就绪", repo.verify() is ModelInstallStatus.Ready)

        val engine = SherpaSenseVoiceEngine(context.assets)
        val capture = PcmAudioRecorder(context)
        val segmenter = SileroVoiceSegmenter(context.assets)
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val speech = WaveReader.readWave(testAssets, "test_wavs/zh.wav")
        val speechMs = speech.samples.size * 1000L / speech.sampleRate
        val captureWithKnownSpeech = object : AudioCapture by capture {
            override suspend fun stop(sessionId: Long): CaptureResult {
                val recorded = capture.stop(sessionId)
                assertTrue("真机采集时长不足：${recorded.durationMs}ms", recorded.durationMs >= 2_500)
                // 真机反复采集及清理，识别使用可重复的语音样本。
                return CaptureResult(speech.samples, speechMs, recorded.durationCapped)
            }
        }
        val scope = CoroutineScope(coroutineContext + Job())
        val delivered = mutableListOf<Long>()
        val finished = mutableSetOf<Long>()

        val controller = DictationController(
            engine = engine,
            capture = captureWithKnownSpeech,
            captureOptions = CaptureOptions(),
            modelVerification = { ModelVerificationResult.Ok },
            speechSegmenter = segmenter,
            scope = scope,
        )
        scope.launch {
            // 正文走无损通道:轻提示事件允许丢弃,不能用它统计交付
            controller.results.collect { delivered.add(it.sessionId) }
        }
        try {
            controller.start()
            awaitPhase(controller, DictationPhase.READY, 60_000)
            repeat(TOTAL_ROUNDS) { round ->
                controller.dispatch(DictationCommand.Start)
                awaitPhase(controller, DictationPhase.RECORDING, 15_000)
                val sessionId = requireNotNull(controller.state.value.sessionId)
                val captureMs = when (round % 20) {
                    17 -> 5_000L
                    18 -> 10_000L
                    else -> 3_000L
                }
                delay(captureMs)
                if (round % 5 == 4) {
                    controller.dispatch(DictationCommand.Cancel)
                } else {
                    finished.add(sessionId)
                    controller.dispatch(DictationCommand.Finish)
                }
                awaitPhase(controller, DictationPhase.READY, 30_000)
                assertEquals(null, controller.state.value.sessionId)
                if (round % 20 == 19) {
                    val memory = Debug.MemoryInfo()
                    Debug.getMemoryInfo(memory)
                    val nativeKb = Debug.getNativeHeapAllocatedSize() / 1024
                    Log.i(TAG, "stress_memory round=${round + 1} nativeKb=$nativeKb pssKb=${memory.totalPss}")
                }
            }
            yield()
            assertEquals("每次会话最多交付一次", delivered.toSet().size, delivered.size)
            assertEquals("每个正常结束的会话应交付，取消会话不能交付", finished, delivered.toSet())
            Log.i(TAG, "stress_ok rounds=$TOTAL_ROUNDS delivered=${delivered.size}")
        } finally {
            withContext(NonCancellable) {
                controller.shutdown()
                capture.dispose()
                segmenter.close()
                scope.cancel()
            }
        }
    }

    private suspend fun awaitPhase(
        controller: DictationController,
        phase: DictationPhase,
        timeoutMs: Long,
    ) {
        withTimeout(timeoutMs) {
            while (controller.state.value.phase != phase) {
                delay(50)
            }
        }
    }
}
