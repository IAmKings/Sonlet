package com.sonlet.app.dictation.core

import com.sonlet.app.audio.capture.AudioCapture
import com.sonlet.app.audio.capture.AudioCaptureErrorCode
import com.sonlet.app.audio.capture.AudioCaptureException
import com.sonlet.app.audio.capture.CaptureFailure
import com.sonlet.app.audio.capture.CaptureOptions
import com.sonlet.app.audio.capture.CaptureResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 可控采集假实现:无 Android 依赖,支持注入启动延迟、启动错误、
 * 读取失败事件与自定义停止结果;时长由测试手动推进。
 */
class FakeAudioCapture(
    private val defaultOptions: CaptureOptions = CaptureOptions(),
) : AudioCapture {

    var startDelayMs: Long = 0
    var stopDelayMs: Long = 0

    @Volatile
    var startError: AudioCaptureException? = null

    /** stop() 返回值;默认按 currentDurationMs 生成静音样本 */
    @Volatile
    var injectedResult: CaptureResult? = null

    @Volatile
    var currentDurationMsOverride: Long? = null

    @Volatile
    private var activeSession: Long? = null

    var startedCount = 0
        private set
    var stoppedCount = 0
        private set
    var cancelledCount = 0
        private set

    private val _errorEvents = MutableSharedFlow<CaptureFailure>(extraBufferCapacity = 8)
    override val errorEvents: SharedFlow<CaptureFailure> = _errorEvents

    val activeSessionId: Long?
        get() = activeSession

    override val currentDurationMs: Long
        get() = currentDurationMsOverride ?: 0L

    override suspend fun start(sessionId: Long, options: CaptureOptions) {
        delay(startDelayMs)
        startError?.let { throw it }
        if (activeSession != null) {
            throw AudioCaptureException(
                AudioCaptureErrorCode.RECORDER_BUSY,
                "fake busy with $activeSession",
            )
        }
        activeSession = sessionId
        startedCount++
    }

    override suspend fun stop(sessionId: Long): CaptureResult {
        if (activeSession != null && activeSession != sessionId) {
            throw AudioCaptureException(
                AudioCaptureErrorCode.RECORDER_BUSY,
                "fake stop session mismatch",
            )
        }
        delay(stopDelayMs)
        activeSession = null
        stoppedCount++
        injectedResult?.let { return it }
        val duration = currentDurationMsOverride ?: 0L
        val samples = FloatArray((duration * defaultOptions.sampleRate / 1000L).toInt())
        return CaptureResult(
            samples = samples,
            durationMs = duration,
            durationCapped = duration >= defaultOptions.maxDurationMs,
        )
    }

    override suspend fun cancel(sessionId: Long) {
        if (activeSession != null && activeSession != sessionId) return
        activeSession = null
        cancelledCount++
    }

    /** 注入读取失败(模拟 CC-07/设备异常) */
    fun emitReadFailure(sessionId: Long) {
        _errorEvents.tryEmit(
            CaptureFailure(sessionId, AudioCaptureErrorCode.READ_FAILED, "injected"),
        )
    }
}
