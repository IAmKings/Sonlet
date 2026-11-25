package com.sonlet.app.audio.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import android.Manifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock

/**
 * 前台 AudioRecord 采集实现(PRD §10、T2-01)。
 *
 * 关键约束:
 * - 16 kHz mono PCM16;启动后校验 [AudioRecord.getSampleRate],不符即报
 *   UNSUPPORTED_AUDIO_FORMAT,禁止向引擎提供错误采样率数据(§8.2、AC-P0-022)。
 * - 有界缓冲:上限 = maxDurationMs 对应样本数(30s = 480,000 样本),不落盘(§10.1)。
 * - 读取循环独立 Job(§9.4 audioReadJob);stop/cancel 通过停止标志 + AudioRecord.stop()
 *   解阻塞,循环 finally 中统一释放,幂等(CC-07)。
 * - 短读/零读/负值分别处理(§10.2)。
 */
class PcmAudioRecorder(
    context: Context,
    private val defaultOptions: CaptureOptions = CaptureOptions(),
) : AudioCapture {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** 串行化停机:stop 与 cancel 并发时只允许一个真正执行 */
    private val stopMutex = Mutex()

    private var audioRecord: AudioRecord? = null
    private var readJob: Job? = null

    /** 独立于读取循环的截止看门狗:读阻塞时也能到点解阻塞(PRD AC-P0-007) */
    private var watchdogJob: Job? = null

    /**
     * 停机等待的状态机:预算内等不到读循环就转后台收尾,期间 [start] 不得并发开麦。
     * 抽成独立类是为了让"超时/迟到完成/复位"三条契约能在 JVM 上被测(PcmAudioRecorder 依赖 AudioRecord)。
     */
    private val releaseGate = ReleaseGate(
        budgetMs = CLEANUP_BUDGET_MS,
        scope = scope,
        onReleased = { sessionId -> finalizeRelease(sessionId, keepSamples = false) },
    )

    private var activeSessionId: Long? = null
    @Volatile
    private var options: CaptureOptions = defaultOptions
    private var buffer: FloatArray = FloatArray(0)

    @Volatile
    private var stopRequested = false

    @Volatile
    private var sampleCount = 0

    @Volatile
    private var durationCapped = false

    /** 最近音频块 RMS(0..1);读取循环内更新,T3-02 波形展示用 */
    @Volatile
    private var currentRms = 0f

    /** 取消-启动竞态防护:cancel 标记的会话,若其 start 尚未完成则中止启动(CC-01) */
    @Volatile
    private var pendingCancelSessionId: Long = -1L

    private val _errorEvents = MutableSharedFlow<CaptureFailure>(extraBufferCapacity = 8)
    override val errorEvents: SharedFlow<CaptureFailure> = _errorEvents

    override val currentDurationMs: Long
        get() = sampleCount * 1000L / options.sampleRate

    override val currentLevel: Float
        get() = currentRms

    override suspend fun start(sessionId: Long, options: CaptureOptions) {
        // 旧循环尚未释放设备:等一个清理预算,仍不释放就明确拒绝,绝不并发开麦
        releaseGate.awaitDrain()
        mutex.withLock {
            if (activeSessionId != null) {
                throw AudioCaptureException(
                    AudioCaptureErrorCode.RECORDER_BUSY,
                    "recorder busy with session=$activeSessionId",
                )
            }
            if (pendingCancelSessionId == sessionId) {
                pendingCancelSessionId = -1L
                throw AudioCaptureException(
                    AudioCaptureErrorCode.CANCELLED,
                    "start aborted: session $sessionId was cancelled before start completed",
                )
            }
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                throw AudioCaptureException(
                    AudioCaptureErrorCode.PERMISSION_DENIED,
                    "RECORD_AUDIO not granted",
                )
            }
            val newBuffer = FloatArray(capacitySamples(options))
            val record = createRecord(options)
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw AudioCaptureException(
                        AudioCaptureErrorCode.INIT_FAILED,
                        "startRecording did not enter recording state",
                    )
                }
            } catch (failure: Exception) {
                // 读取循环尚未接管资源。
                releaseQuietly(record)
                if (failure is IllegalStateException) {
                    throw AudioCaptureException(AudioCaptureErrorCode.INIT_FAILED, "startRecording failed", failure)
                }
                throw failure
            }
            this.options = options
            audioRecord = record
            sampleCount = 0
            durationCapped = false
            stopRequested = false
            buffer = newBuffer
            activeSessionId = sessionId

            Log.i(TAG, "capture_started sessionId=$sessionId sampleRate=${record.sampleRate}")
            readJob = scope.launch { readLoop(sessionId, record) }
            // 截止由看门狗负责:readLoop 可能长期阻塞在 record.read() 里,自己数不到时间
            watchdogJob = scope.launch {
                val deadline = SystemClock.elapsedRealtime() + options.maxDurationMs
                while (!stopRequested && SystemClock.elapsedRealtime() < deadline) {
                    delay(WATCHDOG_TICK_MS)
                }
                if (stopRequested) return@launch

                // 到点:再给一个宽限,让最后几块把缓冲自然填到上限
                // (硬切在块边界上会让样本数略少于上限)
                val lingerUntil = SystemClock.elapsedRealtime() + PROGRESS_GRACE_MS
                val capacity = capacitySamples(options)
                while (!stopRequested &&
                    SystemClock.elapsedRealtime() < lingerUntil &&
                    sampleCount < capacity
                ) {
                    delay(WATCHDOG_TICK_MS)
                }
                if (stopRequested) return@launch

                durationCapped = sampleCount > 0
                stopRequested = true
                Log.i(
                    TAG,
                    "capture_deadline sessionId=$sessionId maxMs=${options.maxDurationMs} " +
                        "samples=$sampleCount capped=$durationCapped",
                )
                // 解阻塞:stop 会让阻塞中的 read 立即返回
                runCatching { record.stop() }
            }
        }
    }

    override suspend fun stop(sessionId: Long): CaptureResult {
        if (activeSessionId != null && activeSessionId != sessionId) {
            throw AudioCaptureException(
                AudioCaptureErrorCode.RECORDER_BUSY,
                "stop: session $sessionId does not own active session $activeSessionId",
            )
        }
        val collected = shutdown(keepSamples = true)
        val durationMs = collected.samples.size * 1000L / options.sampleRate
        Log.i(
            TAG,
            "capture_stopped sessionId=$sessionId durationMs=$durationMs capped=${collected.capped}",
        )
        return CaptureResult(collected.samples, durationMs, collected.capped)
    }

    override suspend fun cancel(sessionId: Long) {
        if (activeSessionId != null && activeSessionId != sessionId) {
            // 幂等:非归属会话的取消不操作资源(不变量 4)
            Log.w(TAG, "cancel ignored: session $sessionId != active $activeSessionId")
            return
        }
        mutex.withLock {
            pendingCancelSessionId = sessionId
        }
        shutdown()
        Log.i(TAG, "capture_cancelled sessionId=$sessionId")
    }

    private class Collected(val samples: FloatArray, val capped: Boolean)

    /**
     * 停止标志 + 解阻塞 + 等待读取循环收敛;重复停止返回空结果。
     *
     * 等待有上限([CLEANUP_BUDGET_MS]):读取循环卡在原生 read 时不能无限挂住调用方。
     * 超时不强清状态——资源仍由旧读循环持有,置 [releasePending],由后台等待者
     * 在其真正结束后收尾;期间 [start] 会拒绝并发打开麦克风。
     */
    private suspend fun shutdown(keepSamples: Boolean = false): Collected = stopMutex.withLock {
        stopRequested = true
        watchdogJob?.cancel()
        // 读取循环可能已自行停止并释放 record;stop 仅用于解阻塞,异常忽略
        runCatching { audioRecord?.stop() }
        val sessionId = activeSessionId
        // 超时分支里 gate 会起后台等待者,真正的释放仍只发生在 finalizeRelease()
        if (!releaseGate.awaitRelease(sessionId ?: -1L, readJob)) {
            Log.w(TAG, "capture_cleanup_timeout sessionId=$sessionId; 等待旧读循环释放后再放行")
            return@withLock Collected(FloatArray(0), capped = false)
        }
        finalizeRelease(sessionId, keepSamples)
    }

    /** 真正清状态;调用方保证读取循环已结束 */
    private suspend fun finalizeRelease(sessionId: Long?, keepSamples: Boolean): Collected {
        val collected = mutex.withLock {
            val samples = if (keepSamples) buffer.copyOf(sampleCount) else FloatArray(0)
            val result = Collected(samples, durationCapped)
            buffer = FloatArray(0)
            audioRecord = null
            readJob = null
            watchdogJob = null
            activeSessionId = null
            sampleCount = 0
            durationCapped = false
            stopRequested = false
            currentRms = 0f
            result
        }
        if (sessionId != null) Log.i(TAG, "capture_released sessionId=$sessionId")
        return collected
    }

    private fun createRecord(options: CaptureOptions): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            options.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw AudioCaptureException(
                AudioCaptureErrorCode.UNSUPPORTED_AUDIO_FORMAT,
                "getMinBufferSize=$minBuffer for ${options.sampleRate}Hz",
            )
        }
        val bufferSize = maxOf(minBuffer, CHUNK_SAMPLES * 4)
        @SuppressLint("MissingPermission") // 权限已在 start() 显式校验
        val record = try {
            AudioRecord(
                options.source,
                options.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: IllegalArgumentException) {
            throw AudioCaptureException(
                AudioCaptureErrorCode.UNSUPPORTED_AUDIO_FORMAT,
                "AudioRecord rejected ${options.sampleRate}Hz",
                e,
            )
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            val detail = "state=${record.state}"
            record.release()
            throw AudioCaptureException(AudioCaptureErrorCode.INIT_FAILED, detail)
        }
        if (record.sampleRate != options.sampleRate) {
            val actual = record.sampleRate
            record.release()
            throw AudioCaptureException(
                AudioCaptureErrorCode.UNSUPPORTED_AUDIO_FORMAT,
                "actual sampleRate=$actual != requested=${options.sampleRate}",
            )
        }
        return record
    }

    private suspend fun readLoop(sessionId: Long, record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = ShortArray(CHUNK_SAMPLES)
        try {
            while (!stopRequested) {
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) {
                    Log.w(TAG, "capture_read_error sessionId=$sessionId ret=$n")
                    _errorEvents.tryEmit(
                        CaptureFailure(sessionId, AudioCaptureErrorCode.READ_FAILED, "read ret=$n"),
                    )
                    break
                }
                if (n == 0) continue
                appendPcm(chunk, n)
                if (sampleCount >= capacitySamples(options)) {
                    durationCapped = true
                    Log.i(TAG, "capture_duration_capped sessionId=$sessionId")
                    break
                }
            }
        } finally {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "record stop ignored: ${e.message}")
            }
            releaseQuietly(record)
        }
    }

    /** PCM16 → Float32,写入有界缓冲并更新块 RMS;调用方保证 n ≥ 0 */
    private fun appendPcm(chunk: ShortArray, n: Int) {
        val bufferLocal = buffer
        var index = sampleCount
        val capacity = bufferLocal.size
        val writable = minOf(n, capacity - index)
        var i = 0
        var squareSum = 0.0
        while (i < writable) {
            val sample = chunk[i] / 32768f
            squareSum += sample.toDouble() * sample
            bufferLocal[index++] = sample
            i++
        }
        sampleCount = index
        if (writable > 0) {
            val rms = kotlin.math.sqrt(squareSum / writable)
            // 归一化:语音正常峰值约 0.3 RMS,放大到 0..1 区间便于展示
            currentRms = (rms.toFloat() * LEVEL_GAIN).coerceIn(0f, 1f)
        }
    }

    private fun capacitySamples(options: CaptureOptions): Int =
        (options.maxDurationMs * options.sampleRate / 1000L).toInt()

    private fun releaseQuietly(record: AudioRecord) {
        try {
            record.release()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "record release ignored: ${e.message}")
        }
    }

    /**
     * 页面销毁:停止读循环并释放 [AudioRecord],随后关闭自有 scope。
     *
     * 只 cancel scope 不够:readLoop 阻塞在 record.read(),取消协程不会打断阻塞调用,
     * AudioRecord 会继续录音直到缓冲写满或撞上 30s 上限。
     */
    suspend fun dispose() {
        // 解阻塞 + 等读取循环释放设备;超时也保证录音已停(scope 兜底)
        withTimeoutOrNull(DISPOSE_TIMEOUT_MS) { runCatching { shutdown() } }
        scope.cancel()
    }

    companion object {
        private const val TAG = "SonletCapture"

        /** 100ms @16kHz(PRD §10.1 音频块) */
        const val CHUNK_SAMPLES = 1_600

        /** RMS→展示电平增益;语音近讲典型 RMS 0.05~0.3 */
        const val LEVEL_GAIN = 4f

        /** dispose 等待读取循环收敛的上限;超时后仍会关 scope */
        private const val DISPOSE_TIMEOUT_MS = 2_000L

        /** stop/cancel 等待读取循环收敛的预算(与 dispose 同口径) */
        private const val CLEANUP_BUDGET_MS = 2_000L

        /** 截止看门狗的轮询粒度 */
        private const val WATCHDOG_TICK_MS = 100L

        /** 到点后等最后几块写入的宽限(避免在块边界硬切,样本数略少于上限) */
        private const val PROGRESS_GRACE_MS = 300L
    }
}
