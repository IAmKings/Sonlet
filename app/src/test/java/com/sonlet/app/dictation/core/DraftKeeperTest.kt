package com.sonlet.app.dictation.core

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftKeeperTest {

    private class FakeStore : DraftStore {
        var saved: DraftStore.Draft? = null
        var saves = 0

        override fun load(): DraftStore.Draft? = saved

        override fun save(draft: DraftStore.Draft) {
            saved = draft
            saves++
        }

        override fun clear() {
            saved = null
        }
    }

    private fun tf(text: String, start: Int, end: Int = start) =
        TextFieldValue(text, TextRange(start, end))

    @Test
    fun `clearing text deletes the draft immediately without debounce`() = runTest {
        val store = FakeStore()
        val keeper = DraftKeeper(store, backgroundScope, debounceMs = 320)
        keeper.onChange(tf("要清掉的正文", 5))
        advanceTimeBy(321)
        runCurrent()
        assertEquals("要清掉的正文", store.saved?.text)

        keeper.onChange(tf("", 0))
        // 不等防抖:清空是明确动作,立即生效
        assertNull(store.saved)
    }

    @Test
    fun `pending save cannot resurrect text after clearing`() = runTest {
        val store = FakeStore()
        val keeper = DraftKeeper(store, backgroundScope, debounceMs = 320)
        keeper.onChange(tf("旧正文", 3))
        // 防抖窗口内清空:排队中的写入必须作废
        keeper.onChange(tf("", 0))
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(store.saved)
    }

    @Test
    fun `restore returns null when nothing stored`() {
        val keeper = DraftKeeper(FakeStore(), kotlinx.coroutines.GlobalScope, debounceMs = 50)
        assertNull(keeper.restore())
    }

    @Test
    fun `restore clamps selection to restored text`() {
        val store = FakeStore().apply {
            saved = DraftStore.Draft("短", 99, 120)
        }
        val keeper = DraftKeeper(store, kotlinx.coroutines.GlobalScope, debounceMs = 50)
        val restored = keeper.restore()!!
        assertEquals("短", restored.text)
        assertEquals(1, restored.selection.start)
        assertEquals(1, restored.selection.end)
    }

    @Test
    fun `debounce waits for the quiet period before writing`() = runTest {
        val store = FakeStore()
        val keeper = DraftKeeper(store, backgroundScope, debounceMs = 320)

        keeper.onChange(tf("ab", 2))
        advanceTimeBy(319)
        runCurrent()
        assertEquals(0, store.saves)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, store.saves)
        assertEquals("ab", store.saved?.text)
    }

    @Test
    fun `typing again resets the debounce window`() = runTest {
        val store = FakeStore()
        val keeper = DraftKeeper(store, backgroundScope, debounceMs = 320)

        keeper.onChange(tf("a", 1))
        advanceTimeBy(200)
        keeper.onChange(tf("ab", 2))
        advanceTimeBy(200)
        runCurrent()
        assertEquals(0, store.saves)

        advanceTimeBy(130)
        runCurrent()
        assertEquals(1, store.saves)
        assertEquals("ab", store.saved?.text)
    }

    @Test
    fun `immediate writes without waiting`() = runTest {
        val store = FakeStore()
        val keeper = DraftKeeper(store, backgroundScope, debounceMs = 320)

        keeper.onChange(tf("done", 4), immediate = true)
        runCurrent()
        assertEquals(1, store.saves)
    }

    @Test
    fun `flush writes the pending value at once`() = runTest {
        val store = FakeStore()
        val keeper = DraftKeeper(store, backgroundScope, debounceMs = 320)

        keeper.onChange(tf("pending", 7))
        keeper.flush(tf("pending", 7))
        runCurrent()
        assertEquals("pending", store.saved?.text)
    }
}
