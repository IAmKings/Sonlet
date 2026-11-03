package com.sonlet.app.asr.api

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 假引擎契约验证(PRD §16.1):慢初始化、抛异常、迟到结果、释放后拒绝服务。
 * 这些语义是 M2 状态机竞态测试(AC-P0-009/010/017/018)的注入基础。
 */
class FakeAsrEngineTest {

    private val samples = FloatArray(16_000 * 2) { 0f } // 2s 静音

    @Test
    fun `prepare completes after configured delay and marks ready`() = runTest {
        val engine = FakeAsrEngine().apply { prepareDelayMs = 500 }
        assertFalse(engine.isReady)

        val job = launch { engine.prepare() }
        advanceTimeBy(499)
        assertFalse(engine.isReady)
        advanceTimeBy(1)
        job.join()
        assertTrue(engine.isReady)
        assertEquals(1, engine.prepareCount.get())
    }

    @Test
    fun `prepare surfaces injected error and stays not ready`() = runTest {
        val engine = FakeAsrEngine().apply {
            prepareError = AsrEngineException(AsrErrorCode.MODEL_CORRUPTED, "injected")
        }
        val thrown = runCatching { engine.prepare() }
        assertTrue(thrown.exceptionOrNull() is AsrEngineException)
        assertEquals(
            AsrErrorCode.MODEL_CORRUPTED,
            (thrown.exceptionOrNull() as AsrEngineException).code,
        )
        assertFalse(engine.isReady)
    }

    @Test
    fun `transcribe before prepare fails with not-ready error`() = runTest {
        val engine = FakeAsrEngine()
        val thrown = runCatching { engine.transcribe(1L, samples, 16_000) }
        assertEquals(AsrErrorCode.MODEL_LOAD_FAILED, (thrown.exceptionOrNull() as AsrEngineException).code)
    }

    @Test
    fun `transcribe returns final result with unique resultId by default`() = runTest {
        val engine = FakeAsrEngine().apply { transcribeDelayMs = 100 }
        engine.prepare()
        val r1 = engine.transcribe(7L, samples, 16_000)
        val r2 = engine.transcribe(7L, samples, 16_000)
        assertEquals("模拟转写文本", r1.text)
        assertTrue(r1.isFinal)
        assertEquals(7L, r1.sessionId)
        assertTrue(r1.resultId != r2.resultId)
        assertEquals(2_000L, r1.audioMs)
        assertEquals(100L, r1.decodeMs)
        assertEquals(2, engine.transcribeCount.get())
    }

    @Test
    fun `late delivery delay is applied after result is built`() = runTest {
        val engine = FakeAsrEngine().apply {
            transcribeDelayMs = 50
            deliverDelayMs = 200
        }
        engine.prepare()
        var deliveredAt = -1L
        val job = launch { engine.transcribe(3L, samples, 16_000); deliveredAt = 1 }
        advanceTimeBy(50)
        // 结果构造完成但尚未交付:此时取消应由交付层丢弃(语义由 Controller 测试覆盖)
        assertEquals(-1L, deliveredAt)
        advanceTimeBy(200)
        job.join()
        assertEquals(1, deliveredAt)
    }

    @Test
    fun `transcribe surfaces injected error`() = runTest {
        val engine = FakeAsrEngine().apply {
            transcribeError = AsrEngineException(AsrErrorCode.ASR_DECODE_FAILED, "injected")
        }
        engine.prepare()
        val thrown = runCatching { engine.transcribe(1L, samples, 16_000) }
        assertEquals(
            AsrErrorCode.ASR_DECODE_FAILED,
            (thrown.exceptionOrNull() as AsrEngineException).code,
        )
    }

    @Test
    fun `fixed result id allows duplicate-delivery simulation`() = runTest {
        val engine = FakeAsrEngine().apply { fixedResultId = "dup-1" }
        engine.prepare()
        val r1 = engine.transcribe(1L, samples, 16_000)
        val r2 = engine.transcribe(1L, samples, 16_000)
        assertEquals(r1.resultId, r2.resultId)
    }

    @Test
    fun `rejectSampleRate injects unsupported format error`() = runTest {
        val engine = FakeAsrEngine().apply { rejectSampleRate = true }
        engine.prepare()
        val thrown = runCatching { engine.transcribe(1L, samples, 48_000) }
        assertEquals(
            AsrErrorCode.UNSUPPORTED_AUDIO_FORMAT,
            (thrown.exceptionOrNull() as AsrEngineException).code,
        )
    }

    @Test
    fun `release rejects subsequent transcribe and prepare`() = runTest {
        val engine = FakeAsrEngine()
        engine.prepare()
        engine.release()
        assertFalse(engine.isReady)
        val t = runCatching { engine.transcribe(1L, samples, 16_000) }
        assertEquals(AsrErrorCode.ENGINE_RELEASED, (t.exceptionOrNull() as AsrEngineException).code)
        val p = runCatching { engine.prepare() }
        assertEquals(AsrErrorCode.ENGINE_RELEASED, (p.exceptionOrNull() as AsrEngineException).code)
        assertEquals(1, engine.releaseCount.get())
    }

    @Test
    fun `sample rate mismatch fails fast against descriptor`() = runTest {
        val engine = FakeAsrEngine()
        engine.prepare()
        val t = runCatching { engine.transcribe(1L, samples, 48_000) }
        assertEquals(
            AsrErrorCode.UNSUPPORTED_AUDIO_FORMAT,
            (t.exceptionOrNull() as AsrEngineException).code,
        )
        assertNull(t.exceptionOrNull()?.cause)
    }

    @Test
    fun `concurrent transcribes are recorded with their sessions`() = runTest {
        val engine = FakeAsrEngine()
        engine.prepare()
        val jobs = List(5) { index -> async { engine.transcribe(index.toLong(), samples, 16_000) } }
        jobs.forEach { it.await() }
        assertEquals(5, engine.transcribeCount.get())
        synchronized(engine.transcribeSessionIds) {
            assertEquals(listOf(0L, 1L, 2L, 3L, 4L), engine.transcribeSessionIds)
        }
    }
}
