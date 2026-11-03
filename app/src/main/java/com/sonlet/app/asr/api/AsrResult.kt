package com.sonlet.app.asr.api

/**
 * 一次转写的结果(PRD §8.1)。
 *
 * @property sessionId 归属会话;交付层须校验其仍为当前会话(不变量 3)
 * @property resultId 结果唯一 ID;同一 resultId 至多交付一次(不变量 2)
 * @property text 最终文本;P0 仅交付 isFinal = true 的结果
 * @property decodeMs native 解码耗时
 * @property audioMs 音频时长(毫秒)
 */
data class AsrResult(
    val sessionId: Long,
    val resultId: String,
    val text: String,
    val language: String? = null,
    val isFinal: Boolean = true,
    val decodeMs: Long,
    val audioMs: Long,
)
