package com.sonlet.app.asr.sherpa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import com.sonlet.app.asr.api.AsrEngineException
import com.sonlet.app.asr.api.AsrErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1 引擎级仪器测试(T1-02/T1-03):通过 AsrEngine 抽象验证真实模型
 * 初始化、转写、释放与互斥契约。
 */
@RunWith(AndroidJUnit4::class)
class SherpaEngineInstrumentedTest {

    private val appContext = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext

    @Test
    fun prepareTranscribeReleaseCycleWorks() = runBlocking {
        val repo = ModelRepository(appContext.assets)
        val status = repo.verify()
        assertTrue("模型应为 Ready: $status", status is ModelInstallStatus.Ready)

        val engine = SherpaEngineFactory.create(appContext.assets)
        assertFalse(engine.isReady)
        engine.prepare()
        assertTrue(engine.isReady)
        try {
            val wave = WaveReader.readWave(
                InstrumentationRegistry.getInstrumentation().context.assets,
                "test_wavs/zh.wav",
            )
            val result = engine.transcribe(1L, wave.samples, wave.sampleRate)
            assertTrue("转写文本不应为空", result.text.isNotBlank())
            assertEquals(1L, result.sessionId)
            assertTrue(result.isFinal)
            assertTrue(result.resultId.isNotBlank())
            assertTrue("audioMs 应与样本一致", result.audioMs > 4_000)
        } finally {
            engine.release()
        }
        assertFalse(engine.isReady)
    }

    @Test
    fun sampleRateMismatchThrowsUnsupported() = runBlocking {
        val engine = SherpaEngineFactory.create(appContext.assets)
        engine.prepare()
        try {
            val thrown = runCatching {
                engine.transcribe(1L, FloatArray(16_000), 48_000)
            }
            val ex = thrown.exceptionOrNull()
            assertTrue("应抛 AsrEngineException: $ex", ex is AsrEngineException)
            assertEquals(AsrErrorCode.UNSUPPORTED_AUDIO_FORMAT, (ex as AsrEngineException).code)
        } finally {
            engine.release()
        }
    }

    @Test
    fun transcribeAfterReleaseThrowsEngineReleased() = runBlocking {
        val engine = SherpaEngineFactory.create(appContext.assets)
        engine.prepare()
        engine.release()
        val thrown = runCatching {
            engine.transcribe(1L, FloatArray(16_000), 16_000)
        }
        assertEquals(
            AsrErrorCode.ENGINE_RELEASED,
            (thrown.exceptionOrNull() as AsrEngineException).code,
        )
    }

    @Test
    fun concurrentTranscribesSerializeWithoutCrash() = runBlocking {
        val engine = SherpaEngineFactory.create(appContext.assets)
        engine.prepare()
        try {
            val wave = WaveReader.readWave(
                InstrumentationRegistry.getInstrumentation().context.assets,
                "test_wavs/en.wav",
            )
            val jobs = List(3) { index ->
                async(Dispatchers.Default) { engine.transcribe(index.toLong(), wave.samples, wave.sampleRate) }
            }
            val results = jobs.map { it.await() }
            results.forEach { assertTrue(it.text.isNotBlank()) }
        } finally {
            engine.release()
        }
    }

    @Test
    fun repeatedPrepareIsIdempotent() = runBlocking {
        val engine = SherpaEngineFactory.create(appContext.assets)
        engine.prepare()
        engine.prepare()
        assertTrue(engine.isReady)
        engine.release()
        engine.release() // 幂等
        assertFalse(engine.isReady)
    }
}
