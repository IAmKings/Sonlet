package com.sonlet.app.dictation.core

/**
 * 听写域类型(PRD §8.3、§9.1)。
 * UI 状态不携带本地化文案;错误码由界面层映射到中英文资源(DESIGN.md §7)。
 */

sealed interface DictationCommand {
    /** 开始一次听写(仅 READY 有效) */
    data object Start : DictationCommand

    /** 结束并转写(仅 RECORDING 有效) */
    data object Finish : DictationCommand

    /** 取消(STARTING/RECORDING/TRANSCRIBING 有效) */
    data object Cancel : DictationCommand

    /** 错误后重试模型准备(ERROR 且模型类错误有效) */
    data object RetryModel : DictationCommand

    /** 收起错误说明,返回编辑(ERROR 有效;不改变资源/模型状态) */
    data object DismissError : DictationCommand

    /** App 进入后台(PRD AppBackgrounded) */
    data object AppBackgrounded : DictationCommand

    /** 音频输入设备被移除(蓝牙断连/有线拔出;AC-P0-015) */
    data object AudioRouteInterrupted : DictationCommand
}

enum class DictationPhase {
    PREPARING, READY, STARTING, RECORDING,
    TRANSCRIBING, CANCELLING, ERROR,
}

/** 听写层错误码(PRD §10.4 全集) */
enum class DictationErrorCode {
    MIC_PERMISSION_DENIED,
    MIC_PERMISSION_REVOKED,
    MIC_BUSY,
    AUDIO_INIT_FAILED,
    AUDIO_READ_FAILED,
    AUDIO_ROUTE_CHANGED,
    MODEL_NOT_FOUND,
    MODEL_CORRUPTED,
    MODEL_LOAD_FAILED,
    ASR_EMPTY_RESULT,
    ASR_DECODE_FAILED,
    AUDIO_TOO_SHORT,
    AUDIO_TOO_LONG,
    SESSION_CANCELLED,
    UNSUPPORTED_AUDIO_FORMAT,
}

data class DictationUiState(
    val phase: DictationPhase = DictationPhase.PREPARING,
    val sessionId: Long? = null,
    val durationMs: Long = 0L,
    val errorCode: DictationErrorCode? = null,
    /** 达到 30s 上限自动转写(界面据此提示”已达到录音上限”) */
    val durationCapped: Boolean = false,
    /** 实时输入电平 0..1(真实采样 RMS;P1 波形展示,不作为说话判定) */
    val volumeLevel: Float = 0f,
) {
    val isBusy: Boolean
        get() = phase == DictationPhase.PREPARING ||
            phase == DictationPhase.STARTING ||
            phase == DictationPhase.TRANSCRIBING ||
            phase == DictationPhase.CANCELLING
}

/**
 * 按住说话模式下主按钮必须保持挂载的阶段集合(UI 不变量)。
 *
 * STARTING 必须在内:按下即触发 Start 命令,状态机几乎立刻进入 STARTING。
 * 若此时把按钮移出组合(或让手势 key 随阶段变化),pointerInput 节点被销毁,
 * 手指抬起事件永久丢失,录音只能等到 30s 上限自动结束
 * (见 issue-11-10-hold-to-talk-release 的回归测试)。
 */
val HOLD_PHASES: Set<DictationPhase> = setOf(
    DictationPhase.READY,
    DictationPhase.STARTING,
    DictationPhase.RECORDING,
)

/**
 * 主按钮是否应以"按住说话"形态呈现。
 * 只要手势还在进行就必须保留挂载([gestureInProgress]),避免按住中途切换交互模式
 * 把节点摘掉——那是同一类"节点消失 ⇒ 事件丢失"的缺陷。
 */
fun holdButtonVisible(
    phase: DictationPhase,
    holdMode: Boolean,
    gestureInProgress: Boolean,
): Boolean = phase in HOLD_PHASES && (holdMode || gestureInProgress)

/** 一次性事件:结果交付与轻提示;不进入常驻状态 */
sealed interface DictationEvent {
    /** 识别成功;交付层已通过 generation 与 resultId 校验 */
    data class TranscriptReady(val sessionId: Long, val resultId: String, val text: String) :
        DictationEvent

    /** 未检测到可识别语音 */
    data object TranscriptEmpty : DictationEvent

    /** 录音太短( <300ms ),已忽略 */
    data object TooShort : DictationEvent

    /** 达到录音上限,自动进入转写 */
    data object DurationCapped : DictationEvent

    /** 音频设备变化,当前会话已安全结束 */
    data object RouteChanged : DictationEvent
}
