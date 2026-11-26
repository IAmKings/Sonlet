package com.sonlet.app.dictation.core

import android.content.Context
import android.util.Log

/**
 * 用应用私有 [android.content.SharedPreferences] 保存草稿。
 *
 * 用 `commit()` 而不是 `apply()`:草稿量小(单条文本),同步写入才能保证
 * 进程在下一帧被杀时内容已经落盘;调用方已做防抖,不会按每个按键写一次。
 */
class SharedPreferencesDraftStore(
    context: Context,
    /** 存储名;设备测试传独立名字,避免读写用户的真实草稿 */
    prefsName: String = PREFS_NAME,
) : DraftStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun load(): DraftStore.Draft? {
        val text = prefs.getString(KEY_TEXT, null) ?: return null
        if (text.isEmpty()) return null
        val draft = DraftStore.Draft.normalized(
            text = text,
            start = prefs.getInt(KEY_SELECTION_START, text.length),
            end = prefs.getInt(KEY_SELECTION_END, text.length),
        )
        return draft
    }

    override fun save(draft: DraftStore.Draft) {
        if (draft.text.isEmpty()) {
            clear()
            return
        }
        val committed = prefs.edit()
            .putString(KEY_TEXT, draft.text)
            .putInt(KEY_SELECTION_START, draft.selectionStart)
            .putInt(KEY_SELECTION_END, draft.selectionEnd)
            .commit()
        if (!committed) {
            // 磁盘满/存储损坏时 commit() 返回 false;不抛异常以免打断输入,但要留下痕迹
            Log.w(TAG, "draft_save_failed chars=${draft.text.length}")
        }
    }

    override fun clear() {
        val committed = prefs.edit()
            .remove(KEY_TEXT)
            .remove(KEY_SELECTION_START)
            .remove(KEY_SELECTION_END)
            .commit()
        if (!committed) Log.w(TAG, "draft_clear_failed")
    }

    companion object {
        private const val TAG = "SonletDraft"

        /** 生产存储名;测试不要用它 */
        const val PREFS_NAME = "editor_draft"
        const val KEY_TEXT = "text"
        const val KEY_SELECTION_START = "selection_start"
        const val KEY_SELECTION_END = "selection_end"
    }
}
