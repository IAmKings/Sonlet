package com.sonlet.app.dictation.core

/**
 * 编辑器草稿的持久化契约(跨进程保留)。
 *
 * 只保存正文与光标位置,不保存音频、不离开本机——写的是应用私有目录,
 * 与 PRD "不上传、不留存音频" 的边界一致。
 */
interface DraftStore {

    /** 上次退出时的草稿;没有或内容为空时返回 null */
    fun load(): Draft?

    fun save(draft: Draft)

    fun clear()

    data class Draft(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    ) {
        companion object {
            /**
             * 规范化:光标越界时夹到正文范围内,起止反了就交换。
             * 持久化内容可能比当前正文旧(例如用户清空后又写),直接信任会抛 IAE。
             */
            fun normalized(text: String, start: Int, end: Int): Draft {
                val min = start.coerceIn(0, text.length)
                val max = end.coerceIn(0, text.length)
                return if (min <= max) Draft(text, min, max) else Draft(text, max, min)
            }
        }
    }
}
