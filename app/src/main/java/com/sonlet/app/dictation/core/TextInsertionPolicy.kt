package com.sonlet.app.dictation.core

/**
 * 文本插入策略(PRD §5.3 决定性约束、§12.1、AC-P0-011/012)。
 *
 * 规则:
 * - 始终基于调用当刻传入的 [androidx.compose.ui.text.input.TextFieldValue] 计算,
 *   不使用录音开始时的快照(CC-04)。
 * - 光标插入:结果落在光标处,光标移动到插入文本之后。
 * - 选区替换:选区被替换,光标落在替换文本之后(AC-P0-012)。
 * - 代理对/组合字符边界:选区端点落在代理对中间时回退到字符边界。
 * - 邻接空格:仅在"字母数字 ↔ 字母数字"或"汉字 ↔ 字母数字"邻接时补一个空格;
 *   已有空格、CJK 标点不补(§12.1)。
 * - 句界空格:前文以英文句末标点(.!?)结尾、后文以文字字符开头时补空格,
 *   小数/版本号(前一个字符是数字且后文以数字开头)例外;中文句末标点后接拉丁才补。
 * - 空文本:原样返回,不产生任何修改(AC-P0-013)。
 */
object TextInsertionPolicy {

    fun insert(current: androidx.compose.ui.text.input.TextFieldValue, transcript: String): androidx.compose.ui.text.input.TextFieldValue {
        if (transcript.isEmpty()) return current
        val text = current.text
        var start = current.selection.min.coerceIn(0, text.length)
        var end = current.selection.max.coerceIn(0, text.length)
        if (start == end) {
            // 折叠光标:整体吸附到同一字符边界,避免被拉成意外选区
            val snapped = snapForward(text, start)
            start = snapped
            end = snapped
        } else {
            // 选区端点:起点回退到对首,终点推进到对尾
            start = snapBackward(text, start)
            end = snapForward(text, end)
        }

        val prefix = text.substring(0, start)
        val suffix = text.substring(end)
        val sb = StringBuilder(prefix.length + transcript.length + suffix.length + 2)
        sb.append(prefix)
        if (needsSpaceBefore(prefix, transcript)) sb.append(' ')
        sb.append(transcript)
        if (needsSpaceAfter(transcript, suffix)) sb.append(' ')
        sb.append(suffix)

        val cursor = sb.length - suffix.length
        return current.copy(
            text = sb.toString(),
            selection = androidx.compose.ui.text.TextRange(cursor, cursor),
        )
    }

    /** 端点落在代理对中间(低代理)时推进到对尾 */
    private fun snapForward(text: String, index: Int): Int {
        if (index in 1 until text.length && Character.isLowSurrogate(text[index])) {
            return index + 1
        }
        return index
    }

    /** 端点落在代理对中间(低代理)时回退到对首 */
    private fun snapBackward(text: String, index: Int): Int {
        if (index in 1 until text.length && Character.isLowSurrogate(text[index])) {
            return index - 1
        }
        return index
    }

    /**
     * 前文与转录开头之间是否需要空格:
     * - 前文以空白结尾 → 不需要
     * - 前文末字符与转录首字符同为"文字字符"(拉丁字母/数字/汉字)且至少一方为拉丁 → 需要
     * - 汉字邻接汉字、任一方为标点 → 不需要
     */
    private fun needsSpaceBefore(prefix: String, transcript: String): Boolean {
        if (prefix.isEmpty()) return false
        if (prefix.last().isWhitespace()) return false
        return spacingBetween(prefix.last(), transcript.first()) || sentenceGap(prefix, transcript)
    }

    private fun needsSpaceAfter(transcript: String, suffix: String): Boolean {
        if (suffix.isEmpty()) return false
        if (suffix.first().isWhitespace()) return false
        return spacingBetween(transcript.last(), suffix.first()) || sentenceGap(transcript, suffix)
    }

    /**
     * 句界是否需要空格(仅看 [before] 末字符与 [after] 首字符):
     * - 英文句末标点 `.!?`:后接文字字符时补空格;`3.` + `5` 这类小数/版本号不补
     * - 中文句末标点 `。！？`:只在后接拉丁字母或数字时补空格
     */
    private fun sentenceGap(before: String, after: String): Boolean {
        val prev = before.last()
        val next = after.first()
        return when (prev) {
            '.', '!', '?' -> {
                val decimal = prev == '.' &&
                    before.getOrNull(before.length - 2)?.isDigit() == true &&
                    next.isDigit()
                !decimal && (next.isLatinLetterOrDigit() || next.isHanIdeograph())
            }
            '。', '！', '？' -> next.isLatinLetterOrDigit()
            else -> false
        }
    }

    /**
     * 两字符邻接处是否需要一个空格(P1 分段拼接与插入共用):
     * 汉字↔汉字不加;至少一方为拉丁且双方均为文字字符时加;标点不加。
     */
    internal fun spacingBetween(prev: Char, next: Char): Boolean {
        val prevLatin = prev.isLatinLetterOrDigit()
        val nextLatin = next.isLatinLetterOrDigit()
        val prevHan = prev.isHanIdeograph()
        val nextHan = next.isHanIdeograph()
        return when {
            prevHan && nextHan -> false
            (prevLatin || prevHan) && (nextLatin || nextHan) -> true
            else -> false
        }
    }

    /** P1 分段转写结果拼接(FR-106):按邻接空格规则连接,忽略空段 */
    fun joinTranscripts(a: String, b: String): String {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val space = spacingBetween(a.last(), b.first()) || sentenceGap(a, b)
        return if (space) "$a $b" else a + b
    }

    private fun Char.isLatinLetterOrDigit(): Boolean =
        (this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9')
}
