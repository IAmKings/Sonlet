package com.sonlet.app.dictation.core

import com.sonlet.app.asr.api.AsrEngineException
import com.sonlet.app.asr.api.AsrErrorCode
import com.sonlet.app.asr.api.FakeAsrEngine
import com.sonlet.app.audio.capture.AudioCaptureErrorCode
import com.sonlet.app.audio.capture.AudioCaptureException
import com.sonlet.app.audio.capture.CaptureOptions
import com.sonlet.app.audio.vad.SpeechSegmenter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 听写状态机测试(PRD §16.1 DictationStateMachineTest、§9.1 转移表、§9.3 竞态清单)。
 *
 * 调度说明:Controller 运行在 backgroundScope;kotlinx-coroutines-test 1.9 的
 * advanceUntilIdle 只推进前台任务,因此这里统一用 advanceTimeBy + runCurrent
 * 驱动后台协程(pump 辅助函数)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationStateMachineTest {

    private fun TestScope.newController(
        engine: FakeAsrEngine = FakeAsrEngine(),
        capture: FakeAudioCapture = FakeAudioCapture(),
        modelVerification: suspend () -> ModelVerificationResult = { ModelVerificationResult.Ok },
        styleProvider: () -> DictationStyle = { DictationStyle.VERBATIM },
        speechSegmenter: SpeechSegmenter? = null,
    ): Triple<DictationController, FakeAsrEngine, FakeAudioCapture> {
        val controller = DictationController(
            engine = engine,
            capture = capture,
            captureOptions = CaptureOptions(),
            modelVerification = modelVerification,
            scope = backgroundScope,
            styleProvider = styleProvider,
            speechSegmenter = speechSegmenter,
        )
        controller.start()
        return Triple(controller, engine, capture)
    }

    /** 推进虚拟时间并驱动后台任务;timeMs=0 时只执行当前到期的任务 */
    private fun TestScope.pump(timeMs: Long = 0) {
        if (timeMs > 0) advanceTimeBy(timeMs)
        runCurrent()
    }

    private fun TestScope.awaitPhase(
        controller: DictationController,
        phase: DictationPhase,
    ) {
        repeat(100) {
            if (controller.state.value.phase == phase) return
            pump(500)
        }
        assertEquals("等待进入 $phase 超时", phase, controller.state.value.phase)
    }

    // ---- AC-P0-005:正常录音→转写→交付 ----

    @Test
    fun `happy path transitions through all phases and delivers once`() = runTest {
        val (controller, engine, capture) = newController(
            engine = FakeAsrEngine().apply {
                prepareDelayMs = 100
                transcribeDelayMs = 200
                transcribeText = "测试语音输入"
            },
            capture = FakeAudioCapture().apply { startDelayMs = 200 },
        )
        awaitPhase(controller, DictationPhase.PREPARING)
        pump(100)
        awaitPhase(controller, DictationPhase.READY)

        controller.dispatch(DictationCommand.Start)
        pump(0)
        assertEquals(DictationPhase.STARTING, controller.state.value.phase)
        pump(200)
        assertEquals(DictationPhase.RECORDING, controller.state.value.phase)
        assertEquals(1, capture.startedCount)

        capture.currentDurationMsOverride = 5_000L
        pump(200) // ticker 推进计时
        assertTrue(
            "计时应推进,实际 ${controller.state.value.durationMs}",
            controller.state.value.durationMs >= 100,
        )

        controller.dispatch(DictationCommand.Finish)
        pump(0)
        assertEquals(DictationPhase.TRANSCRIBING, controller.state.value.phase)
        pump(200)
        awaitPhase(controller, DictationPhase.READY)
        assertNull(controller.state.value.sessionId)
        assertEquals(1, engine.transcribeCount.get())
        assertEquals(1, capture.stoppedCount)
    }

    // ---- 不变量 2:TranscriptReady 恰好一次 ----

    @Test
    fun `transcript ready event is emitted exactly once with processed text`() = runTest {
        val (controller, _, capture) = newController(
            engine = FakeAsrEngine().apply { transcribeText = "  你好世界  " },
        )
        val readyEvents = mutableListOf<DictationEvent.TranscriptReady>()
        val collector = launch {
            controller.events.collect {
                if (it is DictationEvent.TranscriptReady) readyEvents.add(it)
            }
        }
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        capture.currentDurationMsOverride = 4_000L
        controller.dispatch(DictationCommand.Finish)
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
        assertEquals(1, readyEvents.size)
        assertEquals("你好世界", readyEvents[0].text) // VERBATIM:仅去首尾空白
        collector.cancel()
    }

    // ---- AC-P0-006:300ms 误触 ----

    @Test
    fun `too short recording is dropped without transcription`() = runTest {
        val (controller, engine, _) = newController()
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        controller.dispatch(DictationCommand.Finish) // fake 时长默认 0 < 300ms
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
        assertEquals(0, engine.transcribeCount.get())
    }

    // ---- AC-P0-007:30 秒上限自动转写 ----

    @Test
    fun `max duration auto finishes with capped flag`() = runTest {
        val (controller, engine, capture) = newController(
            engine = FakeAsrEngine().apply { transcribeText = "长句" },
        )
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        capture.currentDurationMsOverride = 30_000L // 达到上限
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
        assertEquals(1, engine.transcribeCount.get())
        assertEquals(1, capture.stoppedCount)
    }

    // ---- AC-P0-008:录音中取消 ----

    @Test
    fun `cancel during recording discards audio`() = runTest {
        val (controller, engine, capture) = newController()
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        controller.dispatch(DictationCommand.Cancel)
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
        assertEquals(0, engine.transcribeCount.get())
        assertTrue(capture.cancelledCount >= 1)
        assertNull(controller.state.value.sessionId)
    }

    // ---- AC-P0-009 / CC-03:推理中取消,旧结果绝不交付 ----

    @Test
    fun `cancel during transcription prevents late delivery`() = runTest {
        val (controller, engine, capture) = newController(
            engine = FakeAsrEngine().apply { transcribeDelayMs = 5_000 },
        )
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        capture.currentDurationMsOverride = 3_000L
        controller.dispatch(DictationCommand.Finish)
        pump(0)
        assertEquals(DictationPhase.TRANSCRIBING, controller.state.value.phase)
        pump(1_000) // decode 进行中(虚拟时间 1s / 共 5s)
        controller.dispatch(DictationCommand.Cancel)
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
        // 引擎已进入转写但结果被代际失效丢弃
        assertEquals(1, engine.transcribeCount.get())
        assertNull(controller.state.value.sessionId)
    }

    // ---- AC-P0-010 / CC-01:连续快速点击 ----

    @Test
    fun `rapid start finish cancel cycles never create concurrent recorders`() = runTest {
        val (controller, _, capture) = newController()
        awaitPhase(controller, DictationPhase.READY)
        repeat(20) { round ->
            controller.dispatch(DictationCommand.Start)
            controller.dispatch(DictationCommand.Finish)
            controller.dispatch(DictationCommand.Cancel)
            pump(300)
            assertEquals(
                "第 ${round + 1} 轮结束时应有且仅有一个采集器实例状态收敛",
                null,
                capture.activeSessionId,
            )
        }
        awaitPhase(controller, DictationPhase.READY)
        assertEquals(20, capture.startedCount)
    }

    // ---- AC-P0-013:空识别结果 ----

    @Test
    fun `empty recognition completes without insertion`() = runTest {
        val (controller, engine, capture) = newController(
            engine = FakeAsrEngine().apply { transcribeText = "   " },
        )
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        capture.currentDurationMsOverride = 2_000L
        controller.dispatch(DictationCommand.Finish)
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
        assertEquals(1, engine.transcribeCount.get())
        assertEquals(DictationPhase.READY, controller.state.value.phase)
    }

    // ---- AC-P0-014:切后台 ----

    @Test
    fun `app backgrounded during recording stops silently`() = runTest {
        val (controller, engine, capture) = newController()
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        controller.dispatch(DictationCommand.AppBackgrounded)
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
        assertEquals(0, engine.transcribeCount.get())
        assertTrue("资源应至少被取消一次", capture.cancelledCount >= 1)
    }

    // ---- AC-P0-015 / CC-08:路由变化 ----

    @Test
    fun `audio route interruption cancels session`() = runTest {
        val (controller, engine, _) = newController()
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        controller.dispatch(DictationCommand.AudioRouteInterrupted)
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
        assertEquals(0, engine.transcribeCount.get())
    }

    // ---- AC-P0-016:模型缺失/损坏 ----

    @Test
    fun `model verification failure enters error and retry recovers`() = runTest {
        var shouldFail = true
        val (controller, _, _) = newController(
            modelVerification = {
                if (shouldFail) {
                    ModelVerificationResult.Failed(DictationErrorCode.MODEL_CORRUPTED)
                } else {
                    ModelVerificationResult.Ok
                }
            },
        )
        awaitPhase(controller, DictationPhase.ERROR)
        assertEquals(DictationErrorCode.MODEL_CORRUPTED, controller.state.value.errorCode)
        shouldFail = false
        controller.dispatch(DictationCommand.RetryModel)
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
    }

    @Test
    fun `model error state rejects start until retry`() = runTest {
        val (controller, engine, _) = newController(
            modelVerification = {
                ModelVerificationResult.Failed(DictationErrorCode.MODEL_NOT_FOUND)
            },
        )
        awaitPhase(controller, DictationPhase.ERROR)
        controller.dispatch(DictationCommand.Start) // 非法:未就绪
        pump(1_000)
        assertEquals(DictationPhase.ERROR, controller.state.value.phase)
        assertEquals(0, engine.prepareCount.get())
    }

    // ---- AC-P0-017:推理异常 ----

    @Test
    fun `engine failure during transcription enters recoverable error`() = runTest {
        val (controller, engine, capture) = newController(
            engine = FakeAsrEngine().apply {
                transcribeError =
                    AsrEngineException(AsrErrorCode.ASR_DECODE_FAILED, "injected")
            },
        )
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        capture.currentDurationMsOverride = 2_000L
        controller.dispatch(DictationCommand.Finish)
        pump(1_000)
        awaitPhase(controller, DictationPhase.ERROR)
        assertEquals(DictationErrorCode.ASR_DECODE_FAILED, controller.state.value.errorCode)
        // 错误后可返回编辑并重新开始
        controller.dispatch(DictationCommand.DismissError)
        pump(0)
        awaitPhase(controller, DictationPhase.READY)
        // 恢复正常后可再次录音
        engine.transcribeError = null
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        capture.currentDurationMsOverride = 2_000L
        controller.dispatch(DictationCommand.Finish)
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
    }

    // ---- AC-P0-022:采样格式不支持 ----

    @Test
    fun `unsupported audio format surfaces dedicated error`() = runTest {
        val (controller, _, _) = newController(
            capture = FakeAudioCapture().apply {
                startError = AudioCaptureException(
                    AudioCaptureErrorCode.UNSUPPORTED_AUDIO_FORMAT,
                    "injected",
                )
            },
        )
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        pump(1_000)
        awaitPhase(controller, DictationPhase.ERROR)
        assertEquals(
            DictationErrorCode.UNSUPPORTED_AUDIO_FORMAT,
            controller.state.value.errorCode,
        )
    }

    // ---- AC-P0-003 兜底:未授权即采集 ----

    @Test
    fun `permission denied at capture start surfaces permission error`() = runTest {
        val (controller, _, _) = newController(
            capture = FakeAudioCapture().apply {
                startError = AudioCaptureException(
                    AudioCaptureErrorCode.PERMISSION_DENIED,
                    "injected",
                )
            },
        )
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        pump(1_000)
        awaitPhase(controller, DictationPhase.ERROR)
        assertEquals(DictationErrorCode.MIC_PERMISSION_DENIED, controller.state.value.errorCode)
    }

    // ---- CC-07:读取失败不转写残缺音频 ----

    @Test
    fun `read failure mid recording aborts without transcription`() = runTest {
        val (controller, engine, capture) = newController()
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        capture.emitReadFailure(1L)
        pump(1_000)
        awaitPhase(controller, DictationPhase.ERROR)
        assertEquals(DictationErrorCode.AUDIO_READ_FAILED, controller.state.value.errorCode)
        assertEquals(0, engine.transcribeCount.get())
    }

    // ---- 非法转移(§9.1) ----

    @Test
    fun `illegal commands are ignored without state corruption`() = runTest {
        val (controller, _, _) = newController()
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Finish) // READY 时无意义
        controller.dispatch(DictationCommand.Cancel) // READY 时无意义
        controller.dispatch(DictationCommand.DismissError) // 非 ERROR
        pump(0)
        assertEquals(DictationPhase.READY, controller.state.value.phase)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        controller.dispatch(DictationCommand.Start) // RECORDING 时忽略重复 Start
        pump(300)
        assertEquals(DictationPhase.RECORDING, controller.state.value.phase)
        controller.dispatch(DictationCommand.Cancel)
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
    }

    // ---- 模型准备中禁止录音(CC-09) ----

    @Test
    fun `start during preparing is rejected`() = runTest {
        val (controller, _, _) = newController(
            engine = FakeAsrEngine().apply { prepareDelayMs = 1_000 },
        )
        awaitPhase(controller, DictationPhase.PREPARING)
        controller.dispatch(DictationCommand.Start)
        pump(500)
        assertEquals(DictationPhase.PREPARING, controller.state.value.phase)
        pump(500)
        awaitPhase(controller, DictationPhase.READY)
    }

    // ---- P1 CC-02:按下未启动完成即松开(pendingFinish) ----

    @Test
    fun `finish during starting completes right after recording starts`() = runTest {
        val (controller, engine, capture) = newController(
            engine = FakeAsrEngine().apply { transcribeText = "抢跑" },
            capture = FakeAudioCapture().apply { startDelayMs = 1_000 },
        )
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        pump(0)
        assertEquals(DictationPhase.STARTING, controller.state.value.phase)
        // 按住说话:启动未完成即松开(实际按住时长 ≥300ms)
        capture.currentDurationMsOverride = 500L
        controller.dispatch(DictationCommand.Finish)
        // 启动完成后 pendingFinish 立即结束会话(链路在虚拟时间内连续完成)
        pump(1_100)
        awaitPhase(controller, DictationPhase.READY)
        assertEquals("应恰好转写一次", 1, engine.transcribeCount.get())
        assertEquals(1, capture.stoppedCount)
        assertNull(controller.state.value.sessionId)
    }

    // ---- P1 FR-103:VAD 分段 ----

    @Test
    fun `vad with no speech segments skips transcription`() = runTest {
        val segmenter = FakeSpeechSegmenter(segments = emptyList())
        val (controller, engine, capture) = newController(speechSegmenter = segmenter)
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        capture.currentDurationMsOverride = 2_000L
        capture_finish(controller)
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
        assertEquals("VAD 判无语音时不应调用识别", 0, engine.transcribeCount.get())
        assertEquals(1, segmenter.calls)
    }

    @Test
    fun `vad segments are transcribed in order and joined`() = runTest {
        val segmenter = FakeSpeechSegmenter(
            segments = listOf(FloatArray(16_000), FloatArray(8_000)),
        )
        val readyEvents = mutableListOf<DictationEvent.TranscriptReady>()
        val (controller, engine, capture) = newController(
            engine = FakeAsrEngine().apply {
                synchronized(transcribeTextQueue) {
                    transcribeTextQueue.addLast("今天天气")
                    transcribeTextQueue.addLast("nice")
                }
            },
            speechSegmenter = segmenter,
        )
        val collector = launch {
            controller.events.collect {
                if (it is DictationEvent.TranscriptReady) readyEvents.add(it)
            }
        }
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        capture.currentDurationMsOverride = 2_000L
        capture_finish(controller)
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
        assertEquals(2, engine.transcribeCount.get())
        assertEquals(1, readyEvents.size)
        // 汉字↔拉丁邻接补一个空格
        assertEquals("今天天气 nice", readyEvents[0].text)
        collector.cancel()
    }

    // ---- P1 FR-104:风格处理 ----

    @Test
    fun `concise style is applied to delivered text`() = runTest {
        val readyEvents = mutableListOf<DictationEvent.TranscriptReady>()
        val (controller, _, capture) = newController(
            engine = FakeAsrEngine().apply { transcribeText = "嗯 这个 这个 方案" },
            styleProvider = { DictationStyle.CONCISE },
        )
        val collector = launch {
            controller.events.collect {
                if (it is DictationEvent.TranscriptReady) readyEvents.add(it)
            }
        }
        awaitPhase(controller, DictationPhase.READY)
        controller.dispatch(DictationCommand.Start)
        awaitPhase(controller, DictationPhase.RECORDING)
        capture.currentDurationMsOverride = 2_000L
        capture_finish(controller)
        pump(1_000)
        awaitPhase(controller, DictationPhase.READY)
        assertEquals(1, readyEvents.size)
        assertEquals("这个 方案", readyEvents[0].text)
        collector.cancel()
    }

    private fun capture_finish(controller: DictationController) {
        controller.dispatch(DictationCommand.Finish)
    }
}
