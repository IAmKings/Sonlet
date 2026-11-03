package com.sonlet.app.dictation.core

/**
 * 转写文本处理(PRD §12)。
 * P0 仅 VERBATIM:规范化首尾空白,保留模型输出(含 ITN 标点);
 * 不做可能删除语义词的"自动精简"(§12.2 原则)。
 */
enum class DictationStyle { VERBATIM }

data class ProcessedTranscript(
    val raw: String,
    val formatted: String,
    val transformations: List<String>,
)

interface TranscriptProcessor {
    fun process(raw: String, style: DictationStyle = DictationStyle.VERBATIM): ProcessedTranscript
}

class VerbatimTranscriptProcessor : TranscriptProcessor {

    override fun process(raw: String, style: DictationStyle): ProcessedTranscript {
        require(style == DictationStyle.VERBATIM) { "P0 仅支持 VERBATIM" }
        if (raw.isEmpty()) {
            return ProcessedTranscript(raw, raw, emptyList())
        }
        var formatted = raw.trim()
        val transformations = mutableListOf<String>()
        if (formatted != raw) {
            transformations += "trim_edges"
        }
        // 全空白(如只识别到一串空格)视为空结果
        if (formatted.isBlank()) {
            formatted = ""
            transformations += "blank_result"
        }
        return ProcessedTranscript(raw, formatted, transformations)
    }
}
