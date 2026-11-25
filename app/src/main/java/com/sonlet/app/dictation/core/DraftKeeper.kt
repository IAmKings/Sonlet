package com.sonlet.app.dictation.core

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 草稿落盘的调度:防抖写入 + 关键节点立即写。
 *
 * 独立于 ViewModel 是为了能在 JVM 上验证防抖边界——ViewModel 依赖 Android 上下文,
 * 而这层只有协程与 [DraftStore],用 TestScope 就能断言"没到点不写、到点写一次"。
 */
class DraftKeeper(
    private val store: DraftStore,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {

    private var pending: Job? = null

    /**
     * 单调写入序号:清空或新一轮编辑会自增,已排队的旧任务据此放弃,
     * 保证"清空之后旧的防抖写入不会把正文复活"(复审 P1-3)。
     */
    private var seq = 0L

    /** 启动时恢复:文本为空或没有记录时返回 null */
    fun restore(): TextFieldValue? {
        val draft = store.load() ?: return null
        if (draft.text.isEmpty()) return null
        val start = draft.selectionStart.coerceIn(0, draft.text.length)
        val end = draft.selectionEnd.coerceIn(start, draft.text.length)
        return TextFieldValue(draft.text, TextRange(start, end))
    }

    /**
     * 编辑时调用:防抖后写盘。
     * [immediate] 用于"转写结果刚插入"这类不可丢失的节点。
     */
    fun onChange(value: TextFieldValue, immediate: Boolean = false) {
        pending?.cancel()
        val draft = DraftStore.Draft.normalized(value.text, value.selection.start, value.selection.end)
        val token = ++seq
        if (draft.text.isEmpty()) {
            // 清空是用户明确动作:不等防抖,立即删除,避免重建实例读到旧正文
            store.clear()
            return
        }
        if (immediate) {
            store.save(draft)
            return
        }
        pending = scope.launch {
            delay(debounceMs)
            if (token != seq) return@launch // 期间已清空或又有新编辑,旧内容作废
            store.save(draft)
        }
    }

    /** 页面销毁等无法等待的场景:同步落盘一次 */
    fun flush(value: TextFieldValue) {
        pending?.cancel()
        seq++
        store.save(DraftStore.Draft.normalized(value.text, value.selection.start, value.selection.end))
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 320L
    }
}
