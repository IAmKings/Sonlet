package com.sonlet.app.dictation.core

import com.sonlet.app.asr.api.FakeAsrEngine
import com.sonlet.app.audio.capture.CaptureOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话收敛与生命周期测试(issue: 页面销毁不释放资源 / 启动期读失败事件丢失)。
 *
 * 调度说明与 [DictationStateMachineTest] 一致:Controller 跑在 backgroundScope,
 * 用 advanceTimeBy + runCurrent 驱动后台协程。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationTeardownTest {

    private fun TestScope.newController(
        engine: FakeAsrEngine = FakeAsrEngine(),
        capture: FakeAudioCapture = FakeAudioCapture(),
    ): Pair<DictationController, FakeAsrEngine> {
        val controller = DictationController(
            engine = engine,
            capture = capture,
            captureOptions = CaptureOptions(),
            modelVerification = { ModelVerificationResult.Ok },
            scope = backgroundScope,
        )
        controller.start()
        return controller to engine
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
        assertEquals("等待进入 $phase 超时", phase, controller.state.value.phase)
    }

    // ---- P0-3:读循环在 start() 返回前就报错,事件不得丢失 ----

    @Test
    fun readFailureEmittedDuringStartStillAbortsSession() = runTest {
        val capture = FakeAudioCapture().apply { failDuringStart = true }
        val (controller, engine) = newController(capture = capture)
        awaitPhase(controller, DictationPhase.READY)

        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.ERROR)

        assertEquals(
            "启动期读失败必须收敛为 AUDIO_READ_FAILED,而不是继续等到 30s 上限",
            DictationErrorCode.AUDIO_READ_FAILED,
            controller.state.value.errorCode,
        )
        assertEquals("失败会话必须被取消以释放 AudioRecord", 1, capture.cancelledCount)
        assertEquals("残缺音频不得送去识别", 0, engine.transcribeCount.get())
    }

    // ---- P0-2:页面销毁必须释放引擎与 AudioRecord ----

    @Test
    fun shutdownReleasesEngineAndStopsCapture() = runTest {
        val capture = FakeAudioCapture()
        val (controller, engine) = newController(capture = capture)
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)

        controller.shutdown()

        assertEquals("销毁后必须释放 native 引擎(239MB)", 1, engine.releaseCount.get())
        assertTrue(
            "销毁后必须取消在途采集,实际 cancel 次数: ${capture.cancelledCount}",
            capture.cancelledCount >= 1,
        )
        assertEquals("销毁后不得留下 active 采集会话", null, capture.activeSessionId)
    }

    @Test
    fun shutdownIsIdempotent() = runTest {
        val capture = FakeAudioCapture()
        val (controller, engine) = newController(capture = capture)
        awaitPhase(controller, DictationPhase.READY)

        controller.shutdown()
        controller.shutdown()

        assertEquals("重复销毁只释放一次", 1, engine.releaseCount.get())
    }
}
