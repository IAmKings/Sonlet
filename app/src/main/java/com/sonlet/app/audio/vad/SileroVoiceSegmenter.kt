package com.sonlet.app.audio.vad

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 语音分段结果 */
class SpeechSegments(val segments: List<FloatArray>) {
    val isEmpty: Boolean get() = segments.isEmpty()
    val totalSamples: Long get() = segments.sumOf { it.size.toLong() }
}

/**
 * 语音活动检测抽象(P1 FR-103)。
 * 实现负责:静音阈值、最短语音时长、长句切分([SpeechSegments] 内多段)。
 */
interface SpeechSegmenter {
    /**
     * 对完整 PCM 输入做离线分段;无语音时返回空列表(可提前跳过识别)。
     *
     * 实现必须在后台线程完成:30s 音频要跑约 937 次窗口推理,放在调用方线程
     * (通常是主线程)会直接卡住 UI。
     */
    suspend fun segment(samples: FloatArray, sampleRate: Int): SpeechSegments
}

/**
 * Silero VAD 实现(PRD T3-03)。
 * 模型:vad/silero_vad.onnx(1.7MB,随包内置,哈希见 models/manifest.json)。
 * 离线用法:按窗口喂入 → 边喂边排空 → flush 收尾 → 再排空。
 * 生命周期:实例可跨多次 [segment] 复用;显式 [close] 释放 native 资源。
 */
class SileroVoiceSegmenter(
    assets: AssetManager,
    threshold: Float = DEFAULT_THRESHOLD,
    minSilenceDuration: Float = DEFAULT_MIN_SILENCE,
    minSpeechDuration: Float = DEFAULT_MIN_SPEECH,
    private val maxSpeechDuration: Float = DEFAULT_MAX_SPEECH,
) : SpeechSegmenter, AutoCloseable {

    private val vad = Vad(
        assetManager = assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = MODEL_ASSET,
                threshold = threshold,
                minSilenceDuration = minSilenceDuration,
                minSpeechDuration = minSpeechDuration,
                maxSpeechDuration = maxSpeechDuration,
                windowSize = WINDOW_SIZE,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    @Volatile
    private var closed = false

    // 30s 音频≈937 次窗口推理,必须离开主线程(引擎侧同样自带 Default 派发)
    override suspend fun segment(samples: FloatArray, sampleRate: Int): SpeechSegments =
        withContext(Dispatchers.Default) { segmentBlocking(samples, sampleRate) }

    private fun segmentBlocking(samples: FloatArray, sampleRate: Int): SpeechSegments {
        check(!closed) { "SileroVoiceSegmenter already closed" }
        require(sampleRate == SAMPLE_RATE) {
            "VAD sampleRate mismatch: $sampleRate != $SAMPLE_RATE"
        }
        // 每次调用处理相互独立的音频:清空 LSTM/缓冲状态,避免跨调用污染
        // (实测:silero v4 长静音后状态残留会吞掉后续语音段)
        vad.reset()
        val maxSamples = (maxSpeechDuration * SAMPLE_RATE).toInt()
        val out = mutableListOf<FloatArray>()
        // 按窗口循环喂入(一次性喂整段在 v2.0.1 JNI 下仅处理开头部分,实测)
        var offset = 0
        while (offset + WINDOW_SIZE <= samples.size) {
            vad.acceptWaveform(samples.copyOfRange(offset, offset + WINDOW_SIZE))
            offset += WINDOW_SIZE
            drain(out, maxSamples)
        }
        if (offset < samples.size) {
            // 尾部不足一窗口:补零对齐后喂入
            val tail = samples.copyOfRange(offset, samples.size)
            val padded = tail + FloatArray(WINDOW_SIZE - tail.size)
            vad.acceptWaveform(padded)
        }
        vad.flush()
        drain(out, maxSamples)
        return SpeechSegments(out)
    }

    /** 释放 native VAD;幂等。释放后不可再调用 [segment] */
    override fun close() {
        if (closed) return
        closed = true
        vad.release()
    }

    private fun drain(out: MutableList<FloatArray>, maxSamples: Int) {
        while (!vad.empty()) {
            val samples = vad.front().samples.copyOf()
            // 兜底切分:实现层保证长句分段(T3-03 验收)
            if (samples.size > maxSamples) {
                var start = 0
                while (start < samples.size) {
                    val end = minOf(start + maxSamples, samples.size)
                    out.add(samples.copyOfRange(start, end))
                    start = end
                }
            } else {
                out.add(samples)
            }
            vad.pop()
        }
    }

    companion object {
        const val MODEL_ASSET = "vad/silero_vad.onnx"
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 512

        // 与 sherpa-onnx Silero 默认一致;针对听写场景微调:尾静音 0.6s 才切分,
        // 避免句间停顿被切碎(PRD R-12:VAD 截断风险)
        const val DEFAULT_THRESHOLD = 0.5f
        const val DEFAULT_MIN_SILENCE = 0.6f
        const val DEFAULT_MIN_SPEECH = 0.25f
        const val DEFAULT_MAX_SPEECH = 10.0f
    }
}
