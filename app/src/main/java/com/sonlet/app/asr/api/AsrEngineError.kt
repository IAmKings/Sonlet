package com.sonlet.app.asr.api

/**
 * ASR 引擎错误码(对齐 PRD §10.4)。
 * 仅承载错误语义;面向用户的文案由 UI 层映射,禁止把技术细节拼进用户消息。
 */
enum class AsrErrorCode {
    /** 缺模型或词表(PRD MODEL_NOT_FOUND) */
    MODEL_NOT_FOUND,

    /** 模型完整性校验不符(PRD MODEL_CORRUPTED) */
    MODEL_CORRUPTED,

    /** Native 加载失败(PRD MODEL_LOAD_FAILED) */
    MODEL_LOAD_FAILED,

    /** JNI 推理异常(PRD ASR_DECODE_FAILED) */
    ASR_DECODE_FAILED,

    /** 无可用采样参数/采样率不支持(PRD UNSUPPORTED_AUDIO_FORMAT) */
    UNSUPPORTED_AUDIO_FORMAT,

    /** 引擎已释放后再被使用(实现层守卫) */
    ENGINE_RELEASED,
}

/**
 * ASR 引擎异常。
 * @property code 结构化错误码
 * @param message 技术诊断信息;允许包含 JNI/文件细节,但仅用于脱敏诊断日志,
 * 不得直接展示给用户(DESIGN.md §7)
 */
class AsrEngineException(
    val code: AsrErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
