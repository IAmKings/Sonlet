package com.sonlet.app.asr.api

/**
 * 端侧 ASR 引擎抽象(PRD §8.1、FR-002、US-009)。
 *
 * 实现契约:
 * 1. [prepare] 必须在 [transcribe] 之前完成;幂等,重复调用直接返回。
 * 2. [transcribe] 在 prepare 成功后调用;同一引擎同一时刻至多一个 native decode
 *    (PRD 不变量 1/5:实现应以 Mutex 串行化)。
 * 3. 取消语义:调用方协程被取消只意味着结果不再需要;native decode 不保证可中断
 *    (PRD §9.4)。实现必须在取消时仍保证 decode 安全完成后才允许 release。
 * 4. [release] 幂等;等待在途 decode 结束后再释放 native 资源;释放后调用
 *    [transcribe] 抛 [AsrEngineException](code = [AsrErrorCode.ENGINE_RELEASED])。
 * 5. 实现不得在主线程执行初始化或推理(PRD §19.3)。
 */
interface AsrEngine {
    val descriptor: AsrModelDescriptor

    /** 引擎是否已完成 prepare 且未被释放。 */
    val isReady: Boolean

    /**
     * 初始化引擎(加载模型)。非主线程执行;[onProgress] 仅在有真实进度信号时回调,
     * P0 无可靠比例信号,调用方应显示不确定进度(DESIGN.md §6)。
     */
    suspend fun prepare(onProgress: suspend (Float) -> Unit = {})

    /**
     * 对一段完整音频执行离线转写,返回最终结果。
     * @param sessionId 会话代际 ID;结果原样携带,由交付层做有效性校验
     * @param samples PCM 浮点样本,范围 [-1, 1]
     * @param sampleRate 采样率;与 [AsrModelDescriptor.sampleRate] 不符时抛
     *   [AsrEngineException](code = [AsrErrorCode.UNSUPPORTED_AUDIO_FORMAT])
     */
    suspend fun transcribe(
        sessionId: Long,
        samples: FloatArray,
        sampleRate: Int,
    ): AsrResult

    /** 释放引擎;幂等。 */
    suspend fun release()

    companion object {
        /** 采样率一致性校验的公共实现 */
        fun requireSupportedSampleRate(expected: Int, actual: Int) {
            if (expected != actual) {
                throw AsrEngineException(
                    AsrErrorCode.UNSUPPORTED_AUDIO_FORMAT,
                    "sampleRate mismatch: expected=$expected actual=$actual",
                )
            }
        }
    }
}
