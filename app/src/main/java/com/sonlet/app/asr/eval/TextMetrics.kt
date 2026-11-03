package com.sonlet.app.asr.eval

/**
 * 文本评测度量(AC-P0-020):中文按字符级 CER,英文按词级 WER。
 * 纯函数、无 Android 依赖,供 JVM 单测与仪器评测脚本共用。
 *
 * 归一化规则(评测口径,PRD §2.2):
 * - 去除首尾与连续空白
 * - 统一小写(仅影响英文)
 * - 全角标点转半角后,剔除全部标点与符号
 */
object TextMetrics {

    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            val mapped = fullWidthToHalf(ch)
            // 保留文字字符与空白(空白是英文词级度量的分词依据)
            if (mapped.isLetterOrDigit() || mapped.isWhitespace() || isIdeo(mapped)) {
                sb.append(mapped)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** 中文 CER:字符级编辑距离 / 参考字符数 */
    fun cer(reference: String, hypothesis: String): Double {
        val ref = normalize(reference).replace(" ", "")
        val hyp = normalize(hypothesis).replace(" ", "")
        if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0
        return levenshtein(ref, hyp).toDouble() / ref.length
    }

    /** 英文 WER:词级编辑距离 / 参考词数 */
    fun wer(reference: String, hypothesis: String): Double {
        val ref = normalize(reference).split(' ').filter { it.isNotEmpty() }
        val hyp = normalize(hypothesis).split(' ').filter { it.isNotEmpty() }
        if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0
        return levenshtein(ref.joinToString("\u0001"), hyp.joinToString("\u0001"))
            .let { tokenDistance(ref, hyp).toDouble() } / ref.size
    }

    private fun tokenDistance(ref: List<String>, hyp: List<String>): Int {
        val dp = Array(ref.size + 1) { IntArray(hyp.size + 1) }
        for (i in 0..ref.size) dp[i][0] = i
        for (j in 0..hyp.size) dp[0][j] = j
        for (i in 1..ref.size) {
            for (j in 1..hyp.size) {
                dp[i][j] = if (ref[i - 1] == hyp[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
                }
            }
        }
        return dp[ref.size][hyp.size]
    }

    fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
                }
            }
        }
        return dp[a.length][b.length]
    }

    /** 全角 ASCII 区(FF01-FF5E)转半角;其余常见全角标点逐个映射 */
    private fun fullWidthToHalf(ch: Char): Char {
        val code = ch.code
        if (code in 0xFF01..0xFF5E) return (code - 0xFEE0).toChar()
        return when (ch) {
            '，' -> ','; '。' -> '.'; '！' -> '!'; '？' -> '?'; '；' -> ';'
            '：' -> ':'; '（' -> '('; '）' -> ')'; '“' -> '"'; '”' -> '"'
            '‘' -> '\''; '’' -> '\''; '　' -> ' '
            else -> ch
        }
    }

    private fun isIdeo(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
    }
}
