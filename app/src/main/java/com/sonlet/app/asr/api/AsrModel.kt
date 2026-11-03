package com.sonlet.app.asr.api

/**
 * ASR 模型描述(PRD §8.1)。
 * 用于 UI 展示与引擎能力查询;实现方不得在其中携带文件路径等敏感信息。
 */
data class AsrModelDescriptor(
    val id: String,
    val name: String,
    val sampleRate: Int = 16_000,
    val languages: Set<String>,
    val supportsStreaming: Boolean = false,
)
