package com.sonlet.app.dictation.core

import com.sonlet.app.asr.api.FakeAsrEngine
import com.sonlet.app.audio.capture.CaptureOptions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交付通道契约(质量整改阶段 2,R1)。
 *
 * 修前:结果与轻提示共用 `MutableSharedFlow(extraBufferCapacity = 16)` + `tryEmit`,
 * 消费者一旦慢下来(例如提示通道阻塞),`tryEmit` 直接返回 false,文本静默丢失。
 * 修后:结果走独立的无界 Channel + `send`,慢消费者只会让发送方挂起,不会丢。
 */
class ResultDeliveryTest {

    @Test
    fun `hundred transcriptions are all delivered exactly once`() = runTest {
        val engine = FakeAsrEngine().apply { transcribeText = "内容" }
        val capture = FakeAudioCapture()
        val controller = DictationController(
            engine = engine,
            capture = capture,
            captureOptions = CaptureOptions(),
            modelVerification = { ModelVerificationResult.Ok },
            scope = backgroundScope,
            styleProvider = { DictationStyle.VERBATIM },
            speechSegmenter = null,
        )
        controller.start()

        val received = mutableListOf<DictationEvent.TranscriptReady>()
        // 闸门:前 20 条必须等测试放行才收,模拟 UI 卡住时的积压
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val collector = launch {
            controller.results.collect { result ->
                if (received.size < 20) gate.receive()
                received.add(result)
            }
        }

        awaitPhase(controller, DictationPhase.READY)
        repeat(100) {
            controller.dispatch(DictationCommand.Start)
            awaitPhase(controller, DictationPhase.RECORDING)
            capture.currentDurationMsOverride = 2_000L
            controller.dispatch(DictationCommand.Finish)
            pump(2_000)
            awaitPhase(controller, DictationPhase.READY)
        }
        // 放行被闸门挡住的消费者,把积压排空
        repeat(80) { gate.send(Unit) }
        pump(10_000)

        assertEquals(100, received.size)
        assertEquals(100, received.map { it.resultId }.toSet().size)
        assertTrue(received.all { it.text == "内容" })
        collector.cancel()
    }

    @Test
    fun `consumer confirmation is acknowledged once per result`() = runTest {
        val guard = SessionGenerationGuard()
        val sessionId = guard.beginSession()

        assertTrue(guard.confirmDelivered(sessionId, "r-1"))
        assertEquals(false, guard.confirmDelivered(sessionId, "r-1"))
        assertTrue(guard.isConfirmed("r-1"))

        // 会话失效后不再接受确认
        guard.invalidateAll()
        assertEquals(false, guard.confirmDelivered(sessionId, "r-2"))
    }

    private fun TestScope.pump(timeMs: Long = 0) {
        if (timeMs > 0) advanceTimeBy(timeMs)
        runCurrent()
    }

    private fun TestScope.awaitPhase(controller: DictationController, phase: DictationPhase) {
        repeat(100) {
            if (controller.state.value.phase == phase) return
            pump(500)
        }
        error("等待 ${phase} 超时,当前 ${controller.state.value.phase}")
    }
}
