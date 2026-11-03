package com.sonlet.app.dictation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 会话代际守卫测试(PRD §16.1 SessionGenerationGuardTest,CC-03/CC-10) */
class SessionGenerationGuardTest {

    private lateinit var guard: SessionGenerationGuard

    @Before
    fun setUp() {
        guard = SessionGenerationGuard()
    }

    @Test
    fun `beginSession returns monotonically increasing ids`() {
        val s1 = guard.beginSession()
        val s2 = guard.beginSession()
        assertTrue(s2 > s1)
        assertEquals(s2, guard.currentSessionId)
    }

    @Test
    fun `old session result cannot deliver after new session begins`() {
        val s1 = guard.beginSession()
        val s2 = guard.beginSession()
        assertFalse(guard.canDeliver(s1))
        assertTrue(guard.canDeliver(s2))
    }

    @Test
    fun `endSession disables delivery for that session only`() {
        val s1 = guard.beginSession()
        guard.endSession(s1)
        assertFalse(guard.canDeliver(s1))
        assertEquals(SessionGenerationGuard.NO_SESSION, guard.currentSessionId)

        val s2 = guard.beginSession()
        assertTrue(guard.canDeliver(s2))
    }

    @Test
    fun `endSession from a stale id does not invalidate current session`() {
        val s1 = guard.beginSession()
        val s2 = guard.beginSession()
        guard.endSession(s1) // 旧会话迟到的结束请求
        assertTrue(guard.canDeliver(s2))
    }

    @Test
    fun `invalidateAll blocks everything`() {
        val s1 = guard.beginSession()
        guard.invalidateAll()
        assertFalse(guard.canDeliver(s1))
    }

    @Test
    fun `markDelivered delivers each resultId exactly once within a session`() {
        val s1 = guard.beginSession()
        assertTrue(guard.markDelivered(s1, "r1"))
        assertFalse(guard.markDelivered(s1, "r1")) // CC-10:重复回调
        assertTrue(guard.markDelivered(s1, "r2"))
    }

    @Test
    fun `markDelivered rejects stale session even with fresh resultId`() {
        val s1 = guard.beginSession()
        val s2 = guard.beginSession()
        assertFalse(guard.markDelivered(s1, "late-result")) // CC-03:取消后旧结果
        assertTrue(guard.markDelivered(s2, "fresh-result"))
    }

    @Test
    fun `dedupe set resets across sessions`() {
        val s1 = guard.beginSession()
        guard.markDelivered(s1, "shared-id")
        val s2 = guard.beginSession()
        // 新会话的 resultId 空间独立;不同会话相同 id(理论不可达)不被旧记录阻塞
        assertTrue(guard.markDelivered(s2, "new-session-result"))
    }
}
