package com.sonlet.app.dictation.core

import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * 会话代际守卫(PRD §9.2 不变量 2/3、§9.3 CC-03/CC-10、T2-03)。
 *
 * - [beginSession] 自增代际并返回新 sessionId;旧会话的 sessionId 立即失去交付权。
 * - [canDeliver]:结果 sessionId 必须等于当前 activeSession。
 * - [markDelivered]:同一 resultId 至多发射一次(去重集合随会话切换清理)。
 * - [confirmDelivered]:UI 真正插入正文后的确认,与发射去重分开记录。
 * - [invalidateAll]:取消/后台场景整体失效。
 *
 * 线程安全:全部方法可在任意线程调用;Controller 命令循环串行使用,
 * 原子类型仅作为跨线程可见性保障。
 */
class SessionGenerationGuard {

    private val generation = AtomicLong(0)

    @Volatile
    private var activeSessionId: Long = NO_SESSION

    /**
     * 交付窗口:会话结束后结果可能仍在队列里,消费者尚未取走。
     * 窗口只为该会话保持,直到 [confirmDelivered] 成功或 [revoke] 撤销;
     * 切后台/取消/离开页面时关闭,未写入的旧结果随之作废(复审 P1-2)。
     */
    @Volatile
    private var deliverableSessionId: Long = NO_SESSION

    private val deliveredResultIds: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    private val confirmedResultIds: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    val currentSessionId: Long
        get() = activeSessionId

    /** 开启新会话:旧会话结果全部失效 */
    fun beginSession(): Long {
        deliveredResultIds.clear()
        confirmedResultIds.clear()
        val sessionId = generation.incrementAndGet()
        activeSessionId = sessionId
        deliverableSessionId = sessionId
        return sessionId
    }

    /** 结束当前会话(正常完成或取消);此后旧 sessionId 不可交付 */
    fun endSession(sessionId: Long) {
        if (activeSessionId == sessionId) {
            activeSessionId = NO_SESSION
        }
    }

    /**
     * 会话正常结束:流水线不再交付,但队列里已发出的结果仍可被消费者写入。
     * 与 [endSession] 的区别是它保留交付窗口。
     */
    fun finishSession(sessionId: Long) {
        if (activeSessionId == sessionId) {
            activeSessionId = NO_SESSION
        }
        if (deliverableSessionId != sessionId) {
            deliverableSessionId = NO_SESSION
        }
    }

    /** 撤销该会话尚未写入的结果(切后台/取消/离开页面) */
    fun revoke(sessionId: Long) {
        if (deliverableSessionId == sessionId) {
            deliverableSessionId = NO_SESSION
        }
        if (activeSessionId == sessionId) {
            activeSessionId = NO_SESSION
        }
        deliveredResultIds.clear()
        confirmedResultIds.clear()
    }

    /** 取消/后台:无条件失效全部在途结果 */
    fun invalidateAll() {
        activeSessionId = NO_SESSION
        deliverableSessionId = NO_SESSION
        deliveredResultIds.clear()
        confirmedResultIds.clear()
    }

    /** 该会话的结果现在是否允许写入编辑器 */
    fun canApply(sessionId: Long, resultId: String): Boolean =
        deliverableSessionId != NO_SESSION &&
            deliverableSessionId == sessionId &&
            !confirmedResultIds.contains(resultId)

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

    /**
     * UI 已把结果插入编辑器后的确认;true = 首次确认,false = 重复或会话已失效。
     * 与 [markDelivered] 分开:发射去重发生在流水线,确认发生在消费者。
     */
    fun confirmDelivered(sessionId: Long, resultId: String): Boolean {
        if (!canApply(sessionId, resultId)) return false
        val added = confirmedResultIds.add(resultId)
        if (added) {
            // 一次会话只有一个结果,确认后立即关窗,避免迟到结果再进来
            deliverableSessionId = NO_SESSION
        }
        return added
    }

    /** 测试与诊断用:该 resultId 是否已被消费者确认 */
    fun isConfirmed(resultId: String): Boolean = confirmedResultIds.contains(resultId)

    companion object {
        const val NO_SESSION = 0L
    }
}
