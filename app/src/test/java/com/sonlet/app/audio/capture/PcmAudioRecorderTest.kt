package com.sonlet.app.audio.capture

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PcmAudioRecorderTest {
    @Test
    fun `an old stop resuming after restart leaves new audio and device untouched`() = runTest {
        val old = BlockingInput(sample = 11)
        val next = BlockingInput(sample = 22)
        val recorder = recorder(old, next)
        val callbacks = ArrayDeque<Runnable>()
        val delayedCaller = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                callbacks.addLast(block)
            }
        }

        recorder.start(11L, CaptureOptions())
        runCurrent()
        val first = async { recorder.stop(11L) }
        runCurrent()
        val late = backgroundScope.async(delayedCaller, start = CoroutineStart.UNDISPATCHED) { recorder.stop(11L) }
        assertFalse(late.isCompleted)

        old.allowStop.complete(Unit)
        runCurrent()
        val oldSamples = first.await().samples
        assertEquals(PcmAudioRecorder.CHUNK_SAMPLES, oldSamples.size)
        assertTrue(oldSamples.all { it == 11 / 32768f })
        assertEquals(1, old.releases)
        recorder.start(12L, CaptureOptions())
        runCurrent()

        while (callbacks.isNotEmpty()) callbacks.removeFirst().run()
        runCurrent()
        assertTrue(late.isCompleted)
        assertTrue(late.await().samples.isEmpty())
        assertEquals(0, next.stopCalls)
        assertEquals(100L, recorder.currentDurationMs)

        next.allowStop.complete(Unit)
        val result = recorder.stop(12L)
        assertEquals(PcmAudioRecorder.CHUNK_SAMPLES, result.samples.size)
        assertTrue(result.samples.all { it == 22 / 32768f })
        recorder.dispose()
    }

    @Test
    fun `overlapping finish and cancel both return within their own two seconds`() = runTest {
        val old = BlockingInput()
        val next = BlockingInput()
        val recorder = recorder(old, next)
        recorder.start(41L, CaptureOptions())
        runCurrent()

        val finish = async { recorder.stop(41L) }
        runCurrent()
        advanceTimeBy(100L)
        val cancel = async { recorder.cancel(41L) }
        runCurrent()
        advanceTimeBy(1_899L)
        runCurrent()
        assertFalse(finish.isCompleted)
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(finish.isCompleted)
        assertFalse(cancel.isCompleted)
        advanceTimeBy(100L)
        runCurrent()
        assertTrue(cancel.isCompleted)
        assertEquals(1, old.stopCalls)
        assertEquals(0, old.releases)

        val busy = async { runCatching { recorder.start(42L, CaptureOptions()) }.exceptionOrNull() }
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(AudioCaptureErrorCode.RECORDER_BUSY, (busy.await() as AudioCaptureException).code)

        old.allowStop.complete(Unit)
        runCurrent()
        assertEquals(1, old.releases)
        recorder.start(43L, CaptureOptions())
        runCurrent()
        next.allowStop.complete(Unit)
        recorder.cancel(43L)
        assertEquals(1, next.releases)
        recorder.dispose()
    }

    @Test
    fun `automatic sample cap releases the device and keeps pcm for finish`() = runTest {
        val input = BlockingInput().apply { allowStop.complete(Unit) }
        val recorder = recorder(input)
        recorder.start(1L, CaptureOptions(maxDurationMs = 100))
        runCurrent()

        assertEquals(1, input.releases)
        val result = recorder.stop(1L)
        assertTrue(result.durationCapped)
        assertEquals(100L, result.durationMs)
        assertEquals(1, input.stopCalls)
        recorder.dispose()
    }

    @Test
    fun `dispose times out while preserving the in flight release`() = runTest {
        val input = BlockingInput()
        val recorder = recorder(input)
        recorder.start(1L, CaptureOptions())
        runCurrent()

        val disposal = async { recorder.dispose() }
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(disposal.isCompleted)
        assertEquals(0, input.releases)

        input.allowStop.complete(Unit)
        runCurrent()
        assertEquals(1, input.releases)
    }

    private fun TestScope.recorder(vararg inputs: BlockingInput): PcmAudioRecorder {
        val queue = ArrayDeque(inputs.toList())
        return PcmAudioRecorder({ queue.removeFirst() }, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
    }

    private class BlockingInput(private val sample: Short = 1) : PcmAudioInput {
        val allowStop = CompletableDeferred<Unit>()
        private val stopped = CompletableDeferred<Unit>()
        private var firstRead = true
        var stopCalls = 0
            private set
        var releases = 0
            private set

        override suspend fun read(chunk: ShortArray): Int {
            if (!firstRead) {
                stopped.await()
                return 0
            }
            firstRead = false
            chunk.fill(sample)
            return chunk.size
        }

        override suspend fun stop() {
            stopCalls++
            allowStop.await()
            stopped.complete(Unit)
        }

        override fun release() {
            releases++
        }
    }
}
