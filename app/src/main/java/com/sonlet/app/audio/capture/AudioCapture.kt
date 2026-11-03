package com.sonlet.app.audio.capture

import android.media.MediaRecorder.AudioSource

/**
 * 采集参数(PRD §8.2、§10.1):16 kHz 单声道 PCM16,30s 上限,300ms 最短有效。
 */
data class CaptureOptions(
    val sampleRate: Int = 16_000,
    val maxDurationMs: Long = 30_000,
    val minDurationMs: Long = 300,
    val source: Int = AudioSource.VOICE_RECOGNITION,
)

/** 采集错误(对齐 PRD §10.4 音频类错误码) */
enum class AudioCaptureErrorCode {
    /** AudioRecord 创建/初始化失败(PRD AUDIO_INIT_FAILED) */
    INIT_FAILED,

    /** read 返回错误(PRD AUDIO_READ_FAILED) */
    READ_FAILED,

    /** 设备不支持请求的采样参数(PRD UNSUPPORTED_AUDIO_FORMAT) */
    UNSUPPORTED_AUDIO_FORMAT,

    /** 权限不足,无法创建采集(PRD MIC_PERMISSION_DENIED 兜底) */
    PERMISSION_DENIED,

    /** 本组件已有采集在进行,拒绝并发(不变量 1) */
    RECORDER_BUSY,

    /** 会话在启动完成前被取消(启动-取消竞态防护) */
    CANCELLED,
}

/** 采集中途失败事件(读取循环异步上报;启动失败走 start 抛错) */
data class CaptureFailure(
    val sessionId: Long,
    val code: AudioCaptureErrorCode,
    val detail: String,
)

/**
 * stop() 的返回:已采集音频与会话时长。
 * samples 归调用会话独占(不变量 4);[durationCapped] 表示达到上限自动结束。
 */
class CaptureResult(
    val samples: FloatArray,
    val durationMs: Long,
    val durationCapped: Boolean,
)

/**
 * 音频采集抽象(PRD §8.2)。
 *
 * 实现契约:
 * 1. 同一时刻至多一个 active recorder(不变量 1);重复 start 抛
 *    [AudioCaptureException](code = RECORDER_BUSY)。
 * 2. [stop]/[cancel] 幂等,可与读取循环并发调用(§9.4);停止后音频数据归属
 *    发起调用的会话,不得被其他会话读取(不变量 4)。
 * 3. [start] 返回即代表 AudioRecord 已进入录音态;失败抛 [AudioCaptureException]。
 * 4. 中途读取失败通过 [errorEvents] 上报;此后 stop() 返回已收到的部分。
 */
interface AudioCapture {
    val errorEvents: kotlinx.coroutines.flow.SharedFlow<CaptureFailure>

    /** 启动采集;阻塞至录音态确认或抛错 */
    suspend fun start(sessionId: Long, options: CaptureOptions)

    /** 当前已采集时长(毫秒);用于录音计时展示与上限判断 */
    val currentDurationMs: Long

    /** 最近一个音频块的 RMS 电平,归一化到 0..1(P1 波形展示;非说话判定依据) */
    val currentLevel: Float

    /** 停止并返回已采集音频;阻塞至读取循环收敛 */
    suspend fun stop(sessionId: Long): CaptureResult

    /** 取消并丢弃音频;幂等 */
    suspend fun cancel(sessionId: Long)
}

class AudioCaptureException(
    val code: AudioCaptureErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
