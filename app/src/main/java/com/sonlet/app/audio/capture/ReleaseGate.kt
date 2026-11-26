package com.sonlet.app.audio.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 停机等待的状态机。
 *
 * 采集线程可能卡在原生 [android.media.AudioRecord.read] 里，调用方不能无限等；
 * 这里把「有预算地等 → 超时转后台收尾 → 收尾后复位」收成一处，既能复用也能在 JVM 上测。
 *
 * 三条契约：
 * 1. 超时不等于已释放：返回 false 时 [onReleased] 还没跑，资源仍归旧读循环所有；
 * 2. 后台等待者只在读取任务真正结束后收尾，且只会收尾一次；
 * 3. 收尾后立即复位，下一次 [awaitDrain] 不会误判忙。
 */
internal class ReleaseGate(
    private val budgetMs: Long,
    private val scope: CoroutineScope,
    private val onReleased: suspend (sessionId: Long) -> Unit,
) {

    private val lock = Any()

    /** 有待后台收尾的任务时非空；值本身用于让 [awaitDrain] 能被唤醒。 */
    private var drainSignal: CompletableDeferred<Unit>? = null

    /** 上一轮超时后是否仍在收尾。 */
    val releasePending: Boolean
        get() = synchronized(lock) { drainSignal != null }

    /**
     * 预算内等待 [job] 结束并立即收尾。
     *
     * @return true 表示已在本轮完成收尾；false 表示超时并已转后台收尾。
     */
    suspend fun awaitRelease(sessionId: Long, job: Job?): Boolean {
        // 已经有一轮在后台收尾时,这一轮只等它,绝不再收一次(同一会话只能收尾一次)
        val pendingRelease = synchronized(lock) { drainSignal }
        if (pendingRelease != null) {
            withTimeoutOrNull(budgetMs) { pendingRelease.await() }
            return false
        }
        val joined = withTimeoutOrNull(budgetMs) { job?.join() } != null || job?.isCompleted == true
        // 预算内结束:收尾交给调用方做,避免和后台等待者各收一次(会把刚采到的样本清空)
        if (joined) return true

        val signal = synchronized(lock) {
            // 已有后台等待者时不重复起一个，避免同一会话被收尾两次
            drainSignal ?: CompletableDeferred<Unit>().also { drainSignal = it }
        }
        if (!signal.isCompleted) {
            scope.launch {
                job?.join()
                onReleased(sessionId)
                synchronized(lock) {
                    if (drainSignal === signal) drainSignal = null
                }
                signal.complete(Unit)
            }
        }
        return false
    }

    /** [PcmAudioRecorder.start] 前调用：上一轮仍在收尾时给它一个预算。 */
    suspend fun awaitDrain() {
        val signal = synchronized(lock) { drainSignal } ?: return
        withTimeoutOrNull(budgetMs) { signal.await() }
    }
}
