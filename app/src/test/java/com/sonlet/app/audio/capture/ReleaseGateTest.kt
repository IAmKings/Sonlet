package com.sonlet.app.audio.capture

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 停机超时路径的故障注入用例。
 *
 * 用永远不完成的 [Job] 模拟"采集线程卡在原生 read"——这个条件在设备上无法稳定复现,
 * 但状态机的行为必须确定:超时不算成功、迟到完成只收尾一次、收尾后必须复位。
 */
class ReleaseGateTest {

    private val sessionId = 7L

    @Test
    fun `timeout keeps the resource owned and reports pending`() = runTest {
        val stuck = Job()
        val released = mutableListOf<Long>()
        val gate = ReleaseGate(budgetMs = 1_000L, scope = backgroundScope) { released += it }

        val joined = gate.awaitRelease(sessionId, stuck)
        advanceTimeBy(1_500)
        runCurrent()

        assertFalse("超时不得当成已释放", joined)
        assertTrue("超时后应处于待释放状态", gate.releasePending)
        assertEquals("超时分支不能提前收尾", emptyList<Long>(), released)
    }

    @Test
    fun `late completion releases exactly once and clears pending`() = runTest {
        val stuck = Job()
        val released = mutableListOf<Long>()
        val gate = ReleaseGate(budgetMs = 1_000L, scope = backgroundScope) { released += it }

        gate.awaitRelease(sessionId, stuck)
        advanceTimeBy(1_500)
        runCurrent()

        stuck.complete()
        runCurrent()

        assertEquals("迟到完成只应收尾一次", listOf(sessionId), released)
        assertFalse("收尾后必须复位,否则下次录音会误判忙", gate.releasePending)
    }

    @Test
    fun `drain waits for the pending release to finish`() = runTest {
        val stuck = Job()
        val gate = ReleaseGate(budgetMs = 1_000L, scope = backgroundScope) { }

        gate.awaitRelease(sessionId, stuck)
        advanceTimeBy(1_500)
        runCurrent()

        val drain = launch { gate.awaitDrain() }
        advanceTimeBy(500)
        runCurrent()
        assertFalse("旧循环还没释放,新采集不得放行", drain.isCompleted)

        stuck.complete()
        runCurrent()

        assertTrue("释放后 awaitDrain 应放行", drain.isCompleted)
        assertFalse(gate.releasePending)
    }

    @Test
    fun `finished within budget leaves the release to the caller`() = runTest {
        val released = mutableListOf<Long>()
        val gate = ReleaseGate(budgetMs = 1_000L, scope = backgroundScope) { released += it }
        val done = Job().apply { complete() }

        val joined = gate.awaitRelease(sessionId, done)
        runCurrent()

        assertTrue(joined)
        assertEquals("预算内结束由调用方收尾,后台等待者不得再收一次", emptyList<Long>(), released)
        assertFalse(gate.releasePending)
    }

    @Test
    fun `repeat call during a pending release never releases twice`() = runTest {
        val stuck = Job()
        val released = mutableListOf<Long>()
        val gate = ReleaseGate(budgetMs = 1_000L, scope = backgroundScope) { released += it }

        gate.awaitRelease(sessionId, stuck)
        advanceTimeBy(1_500)
        runCurrent()

        // 旧读循环刚好结束,但上一轮的后台收尾还没被调度:此时再来一次 stop 不得再收一遍
        stuck.complete()
        val second = gate.awaitRelease(sessionId, stuck)
        advanceTimeBy(1_500)
        runCurrent()

        assertFalse("已有收尾在途时,后来的调用只等它,自己不得收尾", second)
        assertEquals("同一会话只能收尾一次", listOf(sessionId), released)
    }
}
