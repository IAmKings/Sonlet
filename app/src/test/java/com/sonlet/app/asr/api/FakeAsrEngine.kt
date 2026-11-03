package com.sonlet.app.asr.api

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

/**
 * 可注入故障的假引擎(PRD §16.1 FakeAsrEngineTest、AC-P0-017)。
 *
 * 仅用于 JVM 单测:模拟慢初始化、推理异常、迟到结果、固定 resultId(重复回调)
 * 与引擎释放后调用。行为遵循 [AsrEngine] 契约,便于让 Controller 状态机测试
 * 在无 Native 依赖下覆盖竞态场景(PRD §19.5)。
 */
class FakeAsrEngine(
    override val descriptor: AsrModelDescriptor = AsrModelDescriptor(
        id = "fake-asr",
        name = "Fake ASR",
        sampleRate = 16_000,
        languages = setOf("zh", "en"),
    ),
) : AsrEngine {

    /** prepare 人为耗时(虚拟时间) */
    var prepareDelayMs: Long = 0

    /** transcribe 人为耗时(虚拟时间) */
    var transcribeDelayMs: Long = 0

    /** 结果就绪后再延迟返回,模拟"取消后旧任务才回来"(CC-03) */
    var deliverDelayMs: Long = 0

    @Volatile
    var prepareError: AsrEngineException? = null

    @Volatile
    var transcribeError: AsrEngineException? = null

    @Volatile
    var transcribeText: String = "模拟转写文本"

    /** 多段转写时按调用顺序弹出;为 null 时始终使用 transcribeText */
    val transcribeTextQueue = ArrayDeque<String>()

    private fun nextText(): String =
        synchronized(transcribeTextQueue) {
            transcribeTextQueue.removeFirstOrNull() ?: transcribeText
        }

    /** 强制固定 resultId,用于重复交付去重测试(AC-P0-018) */
    @Volatile
    var fixedResultId: String? = null

    /** prepare 后是否报采样率错误(AC-P0-022 注入点) */
    @Volatile
    var rejectSampleRate: Boolean = false

    @Volatile
    private var prepared = false

    @Volatile
    private var released = false

    val prepareCount = AtomicInteger(0)
    val releaseCount = AtomicInteger(0)
    val transcribeCount = AtomicInteger(0)
    val transcribeSessionIds = mutableListOf<Long>()

    override val isReady: Boolean
        get() = prepared && !released

    override suspend fun prepare(onProgress: suspend (Float) -> Unit) {
        prepareCount.incrementAndGet()
        delay(prepareDelayMs)
        if (released) {
            throw AsrEngineException(AsrErrorCode.ENGINE_RELEASED, "engine already released")
        }
        prepareError?.let { throw it }
        prepared = true
    }

    override suspend fun transcribe(
        sessionId: Long,
        samples: FloatArray,
        sampleRate: Int,
    ): AsrResult {
        transcribeCount.incrementAndGet()
        synchronized(transcribeSessionIds) { transcribeSessionIds.add(sessionId) }
        AsrEngine.requireSupportedSampleRate(descriptor.sampleRate, sampleRate)
        if (rejectSampleRate) {
            throw AsrEngineException(
                AsrErrorCode.UNSUPPORTED_AUDIO_FORMAT,
                "injected sample rate rejection",
            )
        }
        if (!isReady) {
            throw AsrEngineException(
                if (released) AsrErrorCode.ENGINE_RELEASED else AsrErrorCode.MODEL_LOAD_FAILED,
                "engine not ready",
            )
        }
        delay(transcribeDelayMs)
        transcribeError?.let { throw it }
        val audioMs = samples.size * 1000L / sampleRate
        delay(deliverDelayMs)
        return AsrResult(
            sessionId = sessionId,
            resultId = fixedResultId ?: "fake-result-${RESULT_SEQ.incrementAndGet()}",
            text = nextText(),
            language = "zh",
            isFinal = true,
            decodeMs = transcribeDelayMs,
            audioMs = audioMs,
        )
    }

    override suspend fun release() {
        releaseCount.incrementAndGet()
        released = true
    }

    companion object {
        private val RESULT_SEQ = AtomicLong(0)
    }
}
