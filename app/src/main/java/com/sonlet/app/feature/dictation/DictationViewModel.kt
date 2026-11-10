package com.sonlet.app.feature.dictation

import android.app.Application
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sonlet.app.asr.api.AsrEngine
import com.sonlet.app.asr.sherpa.ModelInstallStatus
import com.sonlet.app.asr.sherpa.ModelRepository
import com.sonlet.app.asr.sherpa.SharedPreferencesModelVerificationCache
import com.sonlet.app.asr.sherpa.SherpaEngineFactory
import com.sonlet.app.audio.capture.CaptureOptions
import com.sonlet.app.audio.capture.PcmAudioRecorder
import com.sonlet.app.audio.vad.SileroVoiceSegmenter
import com.sonlet.app.dictation.core.DictationCommand
import com.sonlet.app.dictation.core.DictationController
import com.sonlet.app.dictation.core.DictationErrorCode
import com.sonlet.app.dictation.core.DictationEvent
import com.sonlet.app.dictation.core.DictationStyle
import com.sonlet.app.dictation.core.DictationUiState
import com.sonlet.app.dictation.core.ModelVerificationResult
import com.sonlet.app.dictation.core.TextInsertionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 听写页 ViewModel:持有编辑器状态(跨转写存活,交付时取最新 —— CC-04),
 * 将 Controller 事件接为文本插入与轻提示(PRD §5.3、DESIGN.md §6)。
 */
class DictationViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ModelRepository(
        assets = application.assets,
        verificationCache = SharedPreferencesModelVerificationCache(application),
        verificationKey = ModelRepository.verificationKeyFor(application),
    )
    private val engine: AsrEngine = SherpaEngineFactory.create(application.assets)
    private val capture = PcmAudioRecorder(application)
    private val segmenter = SileroVoiceSegmenter(application.assets)

    /**
     * 采集参数:同时是计时器上限的唯一来源,UI 不再硬编码 30s。
     */
    val captureOptions = CaptureOptions()

    /** P1 风格选择(§12.2):交付时读取;UI 通过 [styleFlow] 单向订阅,避免出现副本 */
    private val styleState = MutableStateFlow(DictationStyle.VERBATIM)
    val styleFlow: StateFlow<DictationStyle> = styleState.asStateFlow()

    private val controller = DictationController(
        engine = engine,
        capture = capture,
        captureOptions = captureOptions,
        modelVerification = { verifyModel() },
        scope = viewModelScope,
        styleProvider = { styleState.value },
        // P1 FR-103:Silero VAD 分段;无语音段跳过识别,长句切分转写
        speechSegmenter = segmenter,
        // 会话异常仅记结构化诊断(脱敏:不含音频与正文,G09)
        errorLogger = { error -> android.util.Log.e("SonletDictation", "session error", error) },
    )

    fun setStyle(style: DictationStyle) {
        styleState.value = style
    }

    val uiState: StateFlow<DictationUiState> = controller.state

    /** 编辑器状态:所有语音阶段保持可编辑(AC-P0-021) */
    var editorValue: TextFieldValue = TextFieldValue("")
        private set

    /** 编辑器状态更新回调(Compose 侧通过双向绑定写回) */
    var onEditorChanged: ((TextFieldValue) -> Unit)? = null

    fun updateEditor(value: TextFieldValue) {
        editorValue = value
    }

    /** 轻提示队列(一次性事件,Snackbar 消费) */
    sealed interface Toast {
        data class Res(val id: Int) : Toast
    }

    private val toasts = Channel<Toast>(Channel.BUFFERED)
    val toastFlow = toasts.receiveAsFlow()

    init {
        viewModelScope.launch {
            controller.events.collect { event ->
                when (event) {
                    is DictationEvent.TranscriptReady -> {
                        // 以交付当刻的最新编辑状态插入(PRD §5.3 决定性约束)
                        val updated = TextInsertionPolicy.insert(editorValue, event.text)
                        editorValue = updated
                        onEditorChanged?.invoke(updated)
                        toasts.send(Toast.Res(com.sonlet.app.R.string.msg_inserted))
                    }
                    DictationEvent.TranscriptEmpty ->
                        toasts.send(Toast.Res(com.sonlet.app.R.string.msg_empty))
                    DictationEvent.TooShort ->
                        toasts.send(Toast.Res(com.sonlet.app.R.string.msg_too_short))
                    DictationEvent.DurationCapped ->
                        toasts.send(Toast.Res(com.sonlet.app.R.string.msg_duration_capped))
                    DictationEvent.RouteChanged ->
                        toasts.send(Toast.Res(com.sonlet.app.R.string.msg_route_changed))
                }
            }
        }
        controller.start()
    }

    // ---- 用户命令 ----

    fun startDictation() = controller.dispatch(DictationCommand.Start)

    fun finishDictation() = controller.dispatch(DictationCommand.Finish)

    fun cancelDictation() = controller.dispatch(DictationCommand.Cancel)

    fun retryModel() = controller.dispatch(DictationCommand.RetryModel)

    fun dismissError() = controller.dispatch(DictationCommand.DismissError)

    fun onAppBackgrounded() = controller.dispatch(DictationCommand.AppBackgrounded)

    fun onAudioRouteInterrupted() = controller.dispatch(DictationCommand.AudioRouteInterrupted)

    private suspend fun verifyModel(): ModelVerificationResult =
        when (val status = repository.verify()) {
            is ModelInstallStatus.Ready -> ModelVerificationResult.Ok
            is ModelInstallStatus.NotInstalled ->
                ModelVerificationResult.Failed(DictationErrorCode.MODEL_NOT_FOUND)
            is ModelInstallStatus.Corrupted ->
                ModelVerificationResult.Failed(DictationErrorCode.MODEL_CORRUPTED)
            is ModelInstallStatus.Failed ->
                ModelVerificationResult.Failed(DictationErrorCode.MODEL_LOAD_FAILED)
            is ModelInstallStatus.Verifying -> ModelVerificationResult.Ok // 不会出现于 verify() 返回
        }

    /**
     * 销毁期资源收敛作用域:onCleared 时 viewModelScope 已被取消,
     * 停 AudioRecord 与释放 239MB native 模型必须落在独立于它的协程里。
     */
    private val teardownJob = SupervisorJob()
    private val teardownScope = CoroutineScope(teardownJob + Dispatchers.IO)

    override fun onCleared() {
        // 页面销毁:停止采集、失效交付、释放 native 资源(PRD §5.3、AC-P0-014)
        teardownScope.launch {
            try {
                // 顺序:先停会话(join 在途转写)→ 停采集 → 释放 VAD → 释放引擎
                // (引擎释放收敛在 controller.shutdown 内,避免漏掉 decode 互斥)
                runCatching { controller.shutdown() }
                runCatching { capture.dispose() }
                runCatching { segmenter.close() }
            } finally {
                teardownJob.complete()
            }
        }
        super.onCleared()
    }
}
