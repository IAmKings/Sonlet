package com.sonlet.app.audio.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal interface PcmAudioInput {
    fun configureReader() {}
    suspend fun read(chunk: ShortArray): Int
    suspend fun stop()
    fun release()
}

/** 前台 AudioRecord 采集，PCM 缓冲按会话持有（PRD §9.2、§10.2）。 */
class PcmAudioRecorder internal constructor(
    private val openInput: (CaptureOptions) -> PcmAudioInput,
    workerDispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long,
    private val log: (Int, String) -> Unit = { _, _ -> },
) : AudioCapture {

    constructor(context: Context) : this(
        openInput = context.applicationContext.let { app -> { options -> openAudioRecord(app, options) } },
        workerDispatcher = Dispatchers.IO,
        nowMs = SystemClock::elapsedRealtime,
        log = { priority, message -> Log.println(priority, TAG, message) },
    )

    private class Recording(
        val id: Long,
        val input: PcmAudioInput,
        val options: CaptureOptions,
        val buffer: FloatArray,
    ) {
        lateinit var reader: Job
        lateinit var watchdog: Job
        var cleanup: Deferred<Unit>? = null
        var released = false
        var retired = false

        @Volatile var stopRequested = false
        @Volatile var sampleCount = 0
        @Volatile var capped = false
        @Volatile var level = 0f

        val durationMs: Long
            get() = sampleCount * 1000L / options.sampleRate
    }

    private val workerJob = SupervisorJob()
    private val scope = CoroutineScope(workerJob + workerDispatcher)
    private val mutex = Mutex()

    @Volatile private var active: Recording? = null
    @Volatile private var disposed = false
    private var pendingCancelSessionId: Long? = null

    private val _errorEvents = MutableSharedFlow<CaptureFailure>(extraBufferCapacity = 8)
    override val errorEvents: SharedFlow<CaptureFailure> = _errorEvents

    override val currentDurationMs: Long
        get() = active?.durationMs ?: 0L

    override val currentLevel: Float
        get() = active?.level ?: 0f

    override suspend fun start(sessionId: Long, options: CaptureOptions) {
        val pendingRelease = mutex.withLock { active?.takeIf { it.retired }?.cleanup }
        withTimeoutOrNull(CLEANUP_BUDGET_MS) { pendingRelease?.await() }
        mutex.withLock {
            if (disposed || pendingCancelSessionId == sessionId) {
                if (pendingCancelSessionId == sessionId) pendingCancelSessionId = null
                throw AudioCaptureException(AudioCaptureErrorCode.CANCELLED, "start aborted: session $sessionId")
            }
            if (active != null) {
                throw AudioCaptureException(AudioCaptureErrorCode.RECORDER_BUSY, "recorder busy with session=${active?.id}")
            }

            val buffer = FloatArray((options.maxDurationMs * options.sampleRate / 1000L).toInt())
            val recording = Recording(sessionId, openInput(options), options, buffer)
            val deadline = nowMs() + options.maxDurationMs
            recording.reader = scope.launch(start = CoroutineStart.LAZY) { readLoop(recording) }
            recording.watchdog = scope.launch(start = CoroutineStart.LAZY) { watchDeadline(recording, deadline) }
            active = recording
            log(Log.INFO, "capture_started sessionId=$sessionId sampleRate=${options.sampleRate}")
            recording.reader.start()
            recording.watchdog.start()
        }
    }

    override suspend fun stop(sessionId: Long): CaptureResult {
        val result = shutdown(sessionId, keepSamples = true)
        log(Log.INFO, "capture_stopped sessionId=$sessionId durationMs=${result.durationMs} capped=${result.durationCapped}")
        return result
    }

    override suspend fun cancel(sessionId: Long) {
        shutdown(sessionId, keepSamples = false)
        log(Log.INFO, "capture_cancelled sessionId=$sessionId")
    }

    private suspend fun shutdown(sessionId: Long, keepSamples: Boolean): CaptureResult {
        val result = withTimeoutOrNull(CLEANUP_BUDGET_MS) {
            // 即使调用方超时，已提交的停机请求也要等启动临界区结束后执行。
            val request = scope.async(start = CoroutineStart.UNDISPATCHED) { beginShutdown(sessionId, keepSamples) }
            val (recording, cleanup, collectSamples) = request.await() ?: return@withTimeoutOrNull emptyCapture
            cleanup.await()
            if (collectSamples) {
                CaptureResult(recording.buffer.copyOf(recording.sampleCount), recording.durationMs, recording.capped)
            } else {
                emptyCapture
            }
        }
        if (result != null) return result
        log(Log.WARN, "capture_cleanup_timeout sessionId=$sessionId")
        return emptyCapture
    }

    private suspend fun beginShutdown(sessionId: Long, keepSamples: Boolean) = mutex.withLock {
        val current = active
        if (current == null) {
            if (!keepSamples) pendingCancelSessionId = sessionId
            return@withLock null
        }
        if (current.id != sessionId) {
            if (keepSamples) {
                throw AudioCaptureException(
                    AudioCaptureErrorCode.RECORDER_BUSY,
                    "stop: session $sessionId does not own active session ${current.id}",
                )
            }
            return@withLock null
        }
        val collect = keepSamples && !current.retired
        Triple(current, retire(current), collect)
    }

    // 在同一把锁下确认归属并取得对应的清理任务。等待清理时不持有锁。
    private fun retire(recording: Recording): Deferred<Unit> {
        recording.retired = true
        if (recording.released && active === recording) active = null
        return requestCleanup(recording)
    }

    // 看门狗、读循环和调用方均复用此任务。调用方超时后任务仍持有录音资源直至释放。
    private fun requestCleanup(recording: Recording): Deferred<Unit> {
        recording.cleanup?.let { return it }
        recording.stopRequested = true
        recording.watchdog.cancel()
        return scope.async {
            runCatching { recording.input.stop() }.onFailure {
                log(Log.WARN, "capture_stop_failed sessionId=${recording.id}")
            }
            recording.reader.join()
            recording.input.release()
            mutex.withLock {
                recording.released = true
                if (recording.retired && active === recording) active = null
            }
            log(Log.INFO, "capture_released sessionId=${recording.id}")
        }.also { recording.cleanup = it }
    }

    private suspend fun watchDeadline(recording: Recording, deadline: Long) {
        while (nowMs() < deadline) {
            delay(minOf(WATCHDOG_TICK_MS, (deadline - nowMs()).coerceAtLeast(1L)))
        }

        val lingerUntil = nowMs() + PROGRESS_GRACE_MS
        while (nowMs() < lingerUntil && recording.sampleCount < recording.buffer.size) {
            delay(WATCHDOG_TICK_MS)
        }

        mutex.withLock {
            if (recording.stopRequested) return
            recording.capped = recording.sampleCount > 0
            log(Log.INFO, "capture_deadline sessionId=${recording.id} samples=${recording.sampleCount} capped=${recording.capped}")
            requestCleanup(recording)
        }
    }

    private suspend fun readLoop(recording: Recording) {
        val chunk = ShortArray(CHUNK_SAMPLES)
        try {
            recording.input.configureReader()
            while (!recording.stopRequested) {
                val n = recording.input.read(chunk)
                if (n < 0) {
                    log(Log.WARN, "capture_read_error sessionId=${recording.id} ret=$n")
                    _errorEvents.tryEmit(
                        CaptureFailure(recording.id, AudioCaptureErrorCode.READ_FAILED, "read ret=$n"),
                    )
                    break
                }
                if (n == 0) continue
                appendPcm(recording, chunk, n)
                if (recording.sampleCount >= recording.buffer.size) {
                    recording.capped = true
                    log(Log.INFO, "capture_duration_capped sessionId=${recording.id}")
                    break
                }
            }
        } finally {
            mutex.withLock { requestCleanup(recording) }
        }
    }

    private fun appendPcm(recording: Recording, chunk: ShortArray, n: Int) {
        val buffer = recording.buffer
        var index = recording.sampleCount
        val writable = minOf(n, buffer.size - index)
        var squareSum = 0.0
        for (i in 0 until writable) {
            val sample = chunk[i] / 32768f
            squareSum += sample.toDouble() * sample
            buffer[index++] = sample
        }
        recording.sampleCount = index
        if (writable > 0) {
            val rms = kotlin.math.sqrt(squareSum / writable)
            recording.level = (rms.toFloat() * LEVEL_GAIN).coerceIn(0f, 1f)
        }
    }

    /** 退出后继续持有尚未释放的录音，调用方只等待一个预算。 */
    suspend fun dispose() {
        disposed = true
        val disposal = scope.launch {
            try {
                val cleanup = mutex.withLock { active?.let(::retire) }
                cleanup?.await()
            } finally {
                workerJob.complete()
            }
        }
        withTimeoutOrNull(CLEANUP_BUDGET_MS) { disposal.join() }
    }

    companion object {
        private const val TAG = "SonletCapture"
        const val CHUNK_SAMPLES = 1_600
        const val LEVEL_GAIN = 4f
        private const val CLEANUP_BUDGET_MS = 2_000L
        private const val WATCHDOG_TICK_MS = 100L
        private const val PROGRESS_GRACE_MS = 300L
        private val emptyCapture = CaptureResult(FloatArray(0), durationMs = 0, durationCapped = false)
    }
}

private fun openAudioRecord(context: Context, options: CaptureOptions): PcmAudioInput {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        throw AudioCaptureException(AudioCaptureErrorCode.PERMISSION_DENIED, "RECORD_AUDIO not granted")
    }

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
    val bufferSize = maxOf(minBuffer, PcmAudioRecorder.CHUNK_SAMPLES * 4)
    @SuppressLint("MissingPermission") // 权限已在上方显式校验
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
    try {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw AudioCaptureException(AudioCaptureErrorCode.INIT_FAILED, "startRecording did not enter recording state")
        }
    } catch (failure: Exception) {
        runCatching { record.release() }
        if (failure is IllegalStateException) {
            throw AudioCaptureException(AudioCaptureErrorCode.INIT_FAILED, "startRecording failed", failure)
        }
        throw failure
    }

    return object : PcmAudioInput {
        override fun configureReader() = Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        override suspend fun read(chunk: ShortArray): Int = record.read(chunk, 0, chunk.size)

        override suspend fun stop() = record.stop()
        override fun release() = record.release()
    }
}
