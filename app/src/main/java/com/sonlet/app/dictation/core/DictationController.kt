package com.sonlet.app.dictation.core

import com.sonlet.app.asr.api.AsrEngine
import com.sonlet.app.asr.api.AsrEngineException
import com.sonlet.app.asr.api.AsrErrorCode
import com.sonlet.app.audio.capture.AudioCapture
import com.sonlet.app.audio.capture.AudioCaptureErrorCode
import com.sonlet.app.audio.capture.AudioCaptureException
import com.sonlet.app.audio.capture.CaptureFailure
import com.sonlet.app.audio.capture.CaptureOptions
import com.sonlet.app.audio.vad.SpeechSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 听写控制器(PRD §9 状态机、T2-02/T2-03)。
 *
 * 并发模型:
 * - 所有命令经 [dispatch] 进入 Channel,由单一命令循环串行处理(CC-01/CC-05);
 * - modelPrepareJob / sessionJob / cleanupJob 分持,互不覆盖(§9.4);
 * - 取消语义:cancel 只取消 Kotlin Job;native decode 不受影响,结果因
 *   generation 失效而丢弃(CC-03);cleanup 使用 [NonCancellable](§9.4)。
 *
 * 不变量(§9.2):采集互斥由 capture 实现;结果交付唯一由 [SessionGenerationGuard]
 * 的 canDeliver + markDelivered 保证;后台禁录由 AppBackgrounded 命令实现。
 */
class DictationController(
    private val engine: AsrEngine,
    private val capture: AudioCapture,
    private val captureOptions: CaptureOptions = CaptureOptions(),
    private val modelVerification: suspend () -> ModelVerificationResult = {
        ModelVerificationResult.Ok
    },
    private val processor: TranscriptProcessor = VerbatimTranscriptProcessor(),
    private val scope: CoroutineScope,
    /** P1 风格选择(§12.2);交付时读取,运行中切换对当次交付生效 */
    private val styleProvider: () -> DictationStyle = { DictationStyle.VERBATIM },
    /** P1 FR-103 语音分段;null = 整段直送(行为与 P0 一致) */
    private val speechSegmenter: SpeechSegmenter? = null,
    /** 会话异常诊断出口;core 保持 android-free(PRD §7.3),宿主接 Log/SBOM 白名单 */
    private val errorLogger: (Throwable) -> Unit = {},
) {

    private val commands = Channel<DictationCommand>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(DictationUiState())
    val state: StateFlow<DictationUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<DictationEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DictationEvent> = _events.asSharedFlow()

    private val guard = SessionGenerationGuard()

    private var modelPrepareJob: Job? = null
    private var sessionJob: Job? = null
    private var cleanupJob: Job? = null

    @Volatile
    private var finishSignal: CompletableDeferred<Unit>? = null

    /**
     * 按住说话的"按下未启动完成即松开"(PRD §9.1 CC-02):
     * STARTING 期间收到 Finish 时置位,启动成功进入 RECORDING 后立即结束。
     */
    @Volatile
    private var pendingFinish = false

    private var started = false

    @Volatile
    private var shutdownDone = false

    /** 启动命令循环与模型准备;重复调用忽略 */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            for (command in commands) {
                runCatching { handle(command) }
                    .onFailure { failure ->
                        if (failure !is CancellationException) {
                            enterError(DictationErrorCode.ASR_DECODE_FAILED)
                        }
                    }
            }
        }
        dispatch(DictationCommand.RetryModel) // 复用模型准备入口进入 PREPARING
    }

    fun dispatch(command: DictationCommand) {
        commands.trySend(command)
    }

    /**
     * 页面销毁时的资源收敛:停命令循环、取消在途会话、释放引擎与 native 内存。
     *
     * 必须在**独立于 [scope] 的协程**里调用:ViewModel.onCleared 时 viewModelScope
     * 已经取消,挂在它下面的清理一条都不会执行。幂等。
     */
    suspend fun shutdown() {
        if (shutdownDone) return
        shutdownDone = true
        started = false
        commands.close()
        guard.invalidateAll()
        pendingFinish = false
        // 先取消会话(join 在途转写与 VAD),再收敛采集,最后放引擎
        modelPrepareJob?.cancelAndJoin()
        sessionJob?.cancelAndJoin()
        cleanupJob?.cancelAndJoin()
        _state.value.sessionId?.let { sessionId ->
            runCatching { capture.cancel(sessionId) }
        }
        runCatching { engine.release() }
    }

    private fun handle(command: DictationCommand) {
        val phase = _state.value.phase
        when (command) {
            DictationCommand.Start -> if (phase == DictationPhase.READY) startSession()
            DictationCommand.Finish -> when (phase) {
                DictationPhase.RECORDING -> finishSignal?.complete(Unit)
                // P1 按住说话:启动未完成时松开 → 启动成功后立即结束(CCID CC-02)
                DictationPhase.STARTING -> pendingFinish = true
                else -> Unit
            }
            DictationCommand.Cancel -> if (phase in CANCELLABLE_PHASES) cancelActive()
            DictationCommand.AppBackgrounded -> if (phase in CANCELLABLE_PHASES) cancelActive()
            DictationCommand.AudioRouteInterrupted -> if (phase in CANCELLABLE_PHASES) {
                cancelActive()
                _events.tryEmit(DictationEvent.RouteChanged)
            }
            DictationCommand.RetryModel -> if (
                phase == DictationPhase.PREPARING ||
                (phase == DictationPhase.ERROR && _state.value.errorCode.isModelError())
            ) {
                prepareModel()
            }
            DictationCommand.DismissError -> if (phase == DictationPhase.ERROR) {
                // 仅收起错误说明;资源/模型状态不变(DESIGN.md §6)
                _state.update { it.copy(phase = DictationPhase.READY, errorCode = null) }
            }
        }
    }

    // ---- 模型准备(FR-001/FR-002) ----

    private fun prepareModel() {
        _state.update { it.copy(phase = DictationPhase.PREPARING, errorCode = null) }
        modelPrepareJob?.cancel()
        modelPrepareJob = scope.launch {
            val verification = runCatching { modelVerification() }
                .getOrElse { ModelVerificationResult.Failed(DictationErrorCode.MODEL_CORRUPTED) }
            if (verification is ModelVerificationResult.Failed) {
                enterError(verification.code)
                return@launch
            }
            runCatching { engine.prepare() }
                .onSuccess {
                    _state.update { it.copy(phase = DictationPhase.READY, errorCode = null) }
                }
                .onFailure { failure -> enterError(failure.toDictationError()) }
        }
    }

    // ---- 会话生命周期(§9.1) ----

    private fun startSession() {
        val sessionId = guard.beginSession()
        pendingFinish = false
        _state.update {
            it.copy(
                phase = DictationPhase.STARTING,
                sessionId = sessionId,
                durationMs = 0,
                errorCode = null,
                durationCapped = false,
                volumeLevel = 0f,
            )
        }
        sessionJob = scope.launch { runSession(sessionId) }
    }

    private suspend fun runSession(sessionId: Long) {
        val finish = CompletableDeferred<Unit>()
        finishSignal = finish
        var captureFailure: CaptureFailure? = null
        // 先订阅、再启动:读取循环在 start() 内部就已开跑,晚订阅会永久丢失启动期的读失败。
        // UNDISPATCHED 保证 collect 在 start() 之前真正注册上,不依赖调度器的"立即执行"语义。
        val failureCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            capture.errorEvents.collect { failure ->
                if (failure.sessionId != sessionId) return@collect
                captureFailure = failure
                finish.complete(Unit) // 读取失败 → 终止会话,不转写残缺音频
            }
        }
        try {
            capture.start(sessionId, captureOptions)
            _state.update { state ->
                if (state.sessionId == sessionId) state.copy(phase = DictationPhase.RECORDING)
                else state
            }
            // pendingFinish:按下时启动尚未完成,松开已在 STARTING 置位 → 立即结束
            if (pendingFinish) {
                pendingFinish = false
                finish.complete(Unit)
            }
            val ticker = scope.launch {
                while (true) {
                    delay(DURATION_TICK_MS)
                    val duration = capture.currentDurationMs
                    if (duration >= captureOptions.maxDurationMs) finish.complete(Unit)
                    val level = capture.currentLevel
                    _state.update { state ->
                        if (state.sessionId == sessionId) {
                            state.copy(durationMs = duration, volumeLevel = level)
                        } else {
                            state
                        }
                    }
                }
            }
            try {
                finish.await()
            } finally {
                ticker.cancel()
            }

            if (captureFailure != null) {
                // AUDIO_READ_FAILED:丢弃部分音频,给出可恢复错误(PRD §10.4)
                runCatching { capture.cancel(sessionId) }
                enterError(DictationErrorCode.AUDIO_READ_FAILED)
                return
            }

            val captured = capture.stop(sessionId)
            if (captured.durationMs < captureOptions.minDurationMs && !captured.durationCapped) {
                // 误触:丢弃 PCM,不识别(AC-P0-006)
                _events.tryEmit(DictationEvent.TooShort)
                finishToReady(sessionId)
                return
            }
            if (captured.durationCapped) {
                _events.tryEmit(DictationEvent.DurationCapped)
            }
            _state.update { state ->
                if (state.sessionId == sessionId) {
                    state.copy(
                        phase = DictationPhase.TRANSCRIBING,
                        durationMs = captured.durationMs,
                        durationCapped = captured.durationCapped,
                    )
                } else {
                    state
                }
            }
            deliverTranscription(sessionId, captured.samples)
            finishToReady(sessionId)
        } catch (e: CancellationException) {
            // 取消/后台:capture.cancel 保证 AudioRecord 必然释放(不变量 7)
            runCatching { capture.cancel(sessionId) }
            throw e
        } catch (e: AudioCaptureException) {
            runCatching { capture.cancel(sessionId) }
            // 启动前被取消:属正常取消路径,不进入 ERROR(cleanup 会收敛到 READY)
            if (e.code != AudioCaptureErrorCode.CANCELLED) {
                errorLogger(e)
                enterError(e.toDictationError())
            }
        } catch (e: Exception) {
            runCatching { capture.cancel(sessionId) }
            errorLogger(e)
            enterError(e.toDictationError())
        } finally {
            failureCollector.cancelAndJoin()
            if (finishSignal === finish) finishSignal = null
        }
    }

    private suspend fun deliverTranscription(sessionId: Long, samples: FloatArray) {
        // P1 FR-103:VAD 分段;无语音段时跳过识别直接走空结果路径
        val segments = speechSegmenter
            ?.segment(samples, captureOptions.sampleRate)
            ?.segments
            ?.filter { it.isNotEmpty() }
            ?: listOf(samples)
        if (segments.isEmpty()) {
            _events.tryEmit(DictationEvent.TranscriptEmpty)
            return
        }
        // 逐段转写;任一交付点被取消(会话失效)则中止,不再消耗推理
        var joined = ""
        var lastResultId = ""
        for (segment in segments) {
            if (!guard.canDeliver(sessionId)) return
            val part = engine.transcribe(sessionId, segment, captureOptions.sampleRate)
            joined = TextInsertionPolicy.joinTranscripts(joined, part.text)
            lastResultId = part.resultId
        }
        val processed = processor.process(joined, styleProvider())
        if (processed.formatted.isEmpty()) {
            // 空结果:不改编辑框(AC-P0-013)
            _events.tryEmit(DictationEvent.TranscriptEmpty)
            return
        }
        // 结果交付唯一:代际 + resultId 双重校验(不变量 2/3,CC-03/CC-10)
        if (guard.canDeliver(sessionId) && guard.markDelivered(sessionId, lastResultId)) {
            _events.tryEmit(
                DictationEvent.TranscriptReady(sessionId, lastResultId, processed.formatted),
            )
        }
    }

    private fun cancelActive() {
        val sessionId = _state.value.sessionId ?: return
        guard.invalidateAll()
        pendingFinish = false
        val job = sessionJob
        _state.update { it.copy(phase = DictationPhase.CANCELLING) }
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            withContext(NonCancellable) {
                job?.cancelAndJoin()
                runCatching { capture.cancel(sessionId) }
                _state.update {
                    it.copy(
                        phase = DictationPhase.READY,
                        sessionId = null,
                        durationMs = 0,
                        durationCapped = false,
                    )
                }
            }
        }
    }

    private fun finishToReady(sessionId: Long) {
        guard.endSession(sessionId)
        pendingFinish = false
        _state.update { state ->
            if (state.sessionId == sessionId) {
                state.copy(phase = DictationPhase.READY, sessionId = null, durationMs = 0)
            } else {
                state
            }
        }
    }

    private fun enterError(code: DictationErrorCode) {
        val sessionId = _state.value.sessionId
        if (sessionId != null) {
            guard.endSession(sessionId)
        }
        sessionJob?.cancel()
        sessionJob = null
        _state.update {
            it.copy(phase = DictationPhase.ERROR, errorCode = code, sessionId = null)
        }
    }

    private fun DictationErrorCode?.isModelError(): Boolean = this in setOf(
        DictationErrorCode.MODEL_NOT_FOUND,
        DictationErrorCode.MODEL_CORRUPTED,
        DictationErrorCode.MODEL_LOAD_FAILED,
    )

    // ---- 错误映射(PRD §10.4) ----

    private fun Throwable.toDictationError(): DictationErrorCode = when (this) {
        is AudioCaptureException -> when (code) {
            AudioCaptureErrorCode.PERMISSION_DENIED -> DictationErrorCode.MIC_PERMISSION_DENIED
            AudioCaptureErrorCode.UNSUPPORTED_AUDIO_FORMAT -> DictationErrorCode.UNSUPPORTED_AUDIO_FORMAT
            AudioCaptureErrorCode.READ_FAILED -> DictationErrorCode.AUDIO_READ_FAILED
            AudioCaptureErrorCode.INIT_FAILED -> DictationErrorCode.AUDIO_INIT_FAILED
            AudioCaptureErrorCode.RECORDER_BUSY -> DictationErrorCode.MIC_BUSY
            AudioCaptureErrorCode.CANCELLED -> DictationErrorCode.SESSION_CANCELLED
        }
        is AsrEngineException -> when (code) {
            AsrErrorCode.MODEL_NOT_FOUND -> DictationErrorCode.MODEL_NOT_FOUND
            AsrErrorCode.MODEL_CORRUPTED -> DictationErrorCode.MODEL_CORRUPTED
            AsrErrorCode.MODEL_LOAD_FAILED -> DictationErrorCode.MODEL_LOAD_FAILED
            AsrErrorCode.ASR_DECODE_FAILED -> DictationErrorCode.ASR_DECODE_FAILED
            AsrErrorCode.UNSUPPORTED_AUDIO_FORMAT -> DictationErrorCode.UNSUPPORTED_AUDIO_FORMAT
            AsrErrorCode.ENGINE_RELEASED -> DictationErrorCode.ASR_DECODE_FAILED
        }
        is SecurityException -> DictationErrorCode.MIC_PERMISSION_REVOKED
        else -> DictationErrorCode.ASR_DECODE_FAILED
    }

    companion object {
        private val CANCELLABLE_PHASES = setOf(
            DictationPhase.STARTING,
            DictationPhase.RECORDING,
            DictationPhase.TRANSCRIBING,
        )
        const val DURATION_TICK_MS = 100L
    }
}

/** 模型校验结果;Failed 携带用户可见错误分类 */
sealed interface ModelVerificationResult {
    data object Ok : ModelVerificationResult
    data class Failed(val code: DictationErrorCode) : ModelVerificationResult
}
