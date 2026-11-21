package com.sonlet.app.dictation.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftStoreTest {

    @Test
    fun `normalized keeps in-range selection`() {
        val draft = DraftStore.Draft.normalized("你好", 1, 2)
        assertEquals("你好", draft.text)
        assertEquals(1, draft.selectionStart)
        assertEquals(2, draft.selectionEnd)
    }

    @Test
    fun `normalized clamps selection beyond text`() {
        val draft = DraftStore.Draft.normalized("abc", 5, 9)
        assertEquals(3, draft.selectionStart)
        assertEquals(3, draft.selectionEnd)
    }

    @Test
    fun `normalized swaps reversed selection`() {
        val draft = DraftStore.Draft.normalized("abcdef", 4, 2)
        assertEquals(2, draft.selectionStart)
        assertEquals(4, draft.selectionEnd)
    }

    @Test
    fun `normalized accepts empty text`() {
        val draft = DraftStore.Draft.normalized("", 3, 7)
        assertEquals("", draft.text)
        assertEquals(0, draft.selectionStart)
        assertEquals(0, draft.selectionEnd)
    }
}
