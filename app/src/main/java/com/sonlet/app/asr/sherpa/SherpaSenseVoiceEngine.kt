package com.sonlet.app.asr.sherpa

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.sonlet.app.asr.api.AsrEngine
import com.sonlet.app.asr.api.AsrEngineException
import com.sonlet.app.asr.api.AsrErrorCode
import com.sonlet.app.asr.api.AsrResult
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SenseVoice 引擎实现(PRD §8.4、T1-02/T1-03)。
 *
 * 生命周期与并发契约:
 * - [prepare]/[transcribe]/[release] 均不依赖主线程;native 操作集中在
 *   [Dispatchers.Default] 执行(PRD §19.3)。
 * - [decodeMutex] 即 recognizer 租约(不变量 5):decode 全程持有;
 *   [release] 必须拿到同一把锁才能释放 native 对象,保证在途 decode 安全结束。
 * - 协程取消不会中断 native decode(PRD §9.4);decode 完成后流被
 *   try/finally 释放,结果由交付层按 session/generation 决定是否采用。
 * - [release] 内部使用 [NonCancellable],清理不可被普通取消终止(§9.4)。
 */
class SherpaSenseVoiceEngine(
    private val assets: AssetManager,
    private val numThreads: Int = DEFAULT_NUM_THREADS,
) : AsrEngine {

    override val descriptor = ModelRepository.MODEL_DESCRIPTOR

    private val lifecycleMutex = Mutex()
    private val decodeMutex = Mutex()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var released = false

    override val isReady: Boolean
        get() = !released && recognizer != null

    override suspend fun prepare(onProgress: suspend (Float) -> Unit) {
        lifecycleMutex.withLock {
            if (released) {
                throw AsrEngineException(AsrErrorCode.ENGINE_RELEASED, "engine already released")
            }
            if (recognizer != null) return
            val config = buildConfig(numThreads)
            val loaded = withContext(Dispatchers.Default) {
                runCatching { OfflineRecognizer(assets, config) }
                    .onFailure { cause ->
                        Log.e(TAG, "model load failed", cause)
                    }
                    .getOrNull()
            }
            recognizer = loaded ?: throw AsrEngineException(
                AsrErrorCode.MODEL_LOAD_FAILED,
                "native OfflineRecognizer creation returned null",
            )
            Log.i(TAG, "asr_model_load success threads=$numThreads")
        }
    }

    override suspend fun transcribe(
        sessionId: Long,
        samples: FloatArray,
        sampleRate: Int,
    ): AsrResult {
        AsrEngine.requireSupportedSampleRate(descriptor.sampleRate, sampleRate)
        // 锁外快速失败;锁内二次校验,避免与 release 竞争
        requireUsable()
        val audioMs = samples.size * 1000L / sampleRate
        return decodeMutex.withLock {
            val current = requireUsable()
            withContext(Dispatchers.Default) {
                val stream = current.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    val decodeStartNanos = System.nanoTime()
                    current.decode(stream)
                    val result = current.getResult(stream)
                    val decodeMs = (System.nanoTime() - decodeStartNanos) / 1_000_000
                    Log.i(
                        TAG,
                        "asr_finished sessionId=$sessionId decodeMs=$decodeMs audioMs=$audioMs",
                    )
                    AsrResult(
                        sessionId = sessionId,
                        resultId = UUID.randomUUID().toString(),
                        text = result.text,
                        language = result.lang,
                        isFinal = true,
                        decodeMs = decodeMs,
                        audioMs = audioMs,
                    )
                } catch (e: Exception) {
                    if (e is AsrEngineException) throw e
                    throw AsrEngineException(AsrErrorCode.ASR_DECODE_FAILED, "native decode failed", e)
                } finally {
                    stream.release()
                }
            }
        }
    }

    override suspend fun release() {
        withContext(NonCancellable) {
            lifecycleMutex.withLock { released = true }
            decodeMutex.withLock {
                recognizer?.release()
                recognizer = null
                Log.i(TAG, "asr_engine_released")
            }
        }
    }

    private fun requireUsable(): OfflineRecognizer {
        if (released) {
            throw AsrEngineException(AsrErrorCode.ENGINE_RELEASED, "engine already released")
        }
        return recognizer ?: throw AsrEngineException(
            AsrErrorCode.MODEL_LOAD_FAILED,
            "engine not prepared",
        )
    }

    private fun buildConfig(numThreads: Int): OfflineRecognizerConfig = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = ModelRepository.MODEL_ASSET,
                language = "auto",
                useInverseTextNormalization = true,
            ),
            tokens = ModelRepository.TOKENS_ASSET,
            numThreads = numThreads,
            provider = "cpu",
        ),
    )

    companion object {
        private const val TAG = "SonletAsr"
        const val DEFAULT_NUM_THREADS = 2
    }
}

/** 工厂:按 PRD §7.2 的 asr/sherpa 装配点;替换引擎时仅改动此处(PRD US-009) */
object SherpaEngineFactory {
    fun create(assets: AssetManager): AsrEngine = SherpaSenseVoiceEngine(assets)
}
