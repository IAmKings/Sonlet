package com.sonlet.app.dictation.core

import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * 会话代际守卫(PRD §9.2 不变量 2/3、§9.3 CC-03/CC-10、T2-03)。
 *
 * - [beginSession] 自增代际并返回新 sessionId;旧会话的 sessionId 立即失去交付权。
 * - [canDeliver]:结果 sessionId 必须等于当前 activeSession。
 * - [markDelivered]:同一 resultId 至多交付一次(去重集合随会话切换清理)。
 * - [invalidateAll]:取消/后台场景整体失效。
 *
 * 线程安全:全部方法可在任意线程调用;Controller 命令循环串行使用,
 * 原子类型仅作为跨线程可见性保障。
 */
class SessionGenerationGuard {

    private val generation = AtomicLong(0)

    @Volatile
    private var activeSessionId: Long = NO_SESSION

    private val deliveredResultIds: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    val currentSessionId: Long
        get() = activeSessionId

    /** 开启新会话:旧会话结果全部失效 */
    fun beginSession(): Long {
        deliveredResultIds.clear()
        activeSessionId = generation.incrementAndGet()
        return activeSessionId
    }

    /** 结束当前会话(正常完成或取消);此后旧 sessionId 不可交付 */
    fun endSession(sessionId: Long) {
        if (activeSessionId == sessionId) {
            activeSessionId = NO_SESSION
        }
    }

    /** 取消/后台:无条件失效全部在途结果 */
    fun invalidateAll() {
        activeSessionId = NO_SESSION
        deliveredResultIds.clear()
    }

    /** 结果代际是否匹配当前会话 */
    fun canDeliver(sessionId: Long): Boolean = activeSessionId != NO_SESSION &&
        activeSessionId == sessionId

    /**
     * 标记结果已交付;true = 首次(允许插入),false = 重复回调(丢弃,CC-10)。
     * 仅在 [canDeliver] 通过后调用。
     */
    fun markDelivered(sessionId: Long, resultId: String): Boolean {
        if (!canDeliver(sessionId)) return false
        return deliveredResultIds.add(resultId)
    }

    companion object {
        const val NO_SESSION = 0L
    }
}
