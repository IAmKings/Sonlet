package com.sonlet.app.dictation.core

/**
 * 转写文本处理(PRD §12)。
 *
 * 原则(§12.2):规则语言敏感、可关闭、可测试;不盲目删除可能影响语义的
 * 词;所有变换记录于 [ProcessedTranscript.transformations],原文始终保留在
 * raw 交付层,处理可逆(DESIGN.md P1:不把规则处理描述为语义润色)。
 */
enum class DictationStyle { VERBATIM, NATURAL, CONCISE, NOTES }

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
            return ProcessedTranscript(raw, formatted, transformations)
        }

        when (style) {
            DictationStyle.VERBATIM -> Unit
            DictationStyle.NATURAL -> formatted = applyNatural(formatted, transformations)
            DictationStyle.CONCISE -> {
                formatted = applyNatural(formatted, transformations)
                formatted = removeFillers(formatted, transformations)
            }
            DictationStyle.NOTES -> {
                formatted = applyNatural(formatted, transformations)
                formatted = removeFillers(formatted, transformations)
                formatted = toNoteLines(formatted, transformations)
            }
        }
        if (formatted.isBlank()) {
            formatted = ""
            transformations += "blank_after_style"
        }
        return ProcessedTranscript(raw, formatted, transformations)
    }

    /**
     * NATURAL:口语重复折叠。
     * - 相邻重复 token("这个 这个"→"这个"),仅按空白分隔的整词折叠
     * - 同一 CJK 字符 3 次以上连续重复("我我我"→"我");2 次重复不处理,
     *   避免"笑笑/天天"等叠词名词受损
     */
    private fun applyNatural(text: String, transformations: MutableList<String>): String {
        var result = text
        val collapsedTokens = result.split(' ')
            .filter { it.isNotEmpty() }
            .fold(mutableListOf<String>()) { acc, token ->
                if (acc.isEmpty() || acc.last() != token) acc.add(token)
                acc
            }
        val tokenJoined = collapsedTokens.joinToString(" ")
        if (tokenJoined != result) {
            transformations += "collapse_repeated_tokens"
            result = tokenJoined
        }
        val collapsedChars = collapseTripleStutter(result)
        if (collapsedChars != result) {
            transformations += "collapse_char_stutter"
            result = collapsedChars
        }
        return result
    }

    private fun collapseTripleStutter(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            var run = 1
            while (i + run < text.length && text[i + run] == ch) run++
            if (run >= 3 && isCollapsibleStutterChar(ch)) {
                sb.append(ch)
            } else {
                repeat(run) { sb.append(ch) }
            }
            i += run
        }
        return sb.toString()
    }

    private fun isCollapsibleStutterChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch.isHanIdeograph()

    /**
     * CONCISE:移除显式填充词表中的独立 token(空白分隔或标点邻接)。
     * 词表封闭且不含可能承载语义的叹词组合;仅中英文通用填充单音节词。
     */
    private fun removeFillers(text: String, transformations: MutableList<String>): String {
        val tokens = text.split(' ')
        val kept = tokens.filter { token ->
            val core = token.trim { !it.isLetterOrDigit() && !it.isHanIdeograph() }
            val coreLower = core.lowercase()
            !(core in FILLERS_ZH || coreLower in FILLERS_EN)
        }
        if (kept.size != tokens.size) {
            transformations += "remove_fillers"
        }
        return kept.joinToString(" ")
    }

    /**
     * NOTES:句末标点转行,逐行裁剪;不新增语义、不改写内容。
     */
    private fun toNoteLines(text: String, transformations: MutableList<String>): String {
        val lines = text.split('。', '！', '？', '!', '?', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { line ->
                if (line.endsWith('，') || line.endsWith(',')) line.dropLast(1) else line
            }
        if (lines != text) {
            transformations += "split_to_lines"
        }
        return lines
    }

    companion object {
        /** 封闭填充词表:单音节、无独立语义承载(PRD §12.2 保守原则) */
        private val FILLERS_ZH = setOf("嗯", "呃", "唉", "诶", "噢", "喔", "嘞")
        private val FILLERS_EN = setOf("um", "uh", "er", "erm", "hmm", "mm")
    }
}
