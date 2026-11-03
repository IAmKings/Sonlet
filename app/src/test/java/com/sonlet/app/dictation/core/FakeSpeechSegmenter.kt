package com.sonlet.app.dictation.core

import com.sonlet.app.audio.vad.SpeechSegmenter
import com.sonlet.app.audio.vad.SpeechSegments

/** P1 分段注入:返回预置段列表;空列表模拟"无有效语音" */
class FakeSpeechSegmenter(
    var segments: List<FloatArray> = emptyList(),
) : SpeechSegmenter {

    var calls = 0
        private set
    var lastSamples: FloatArray? = null
        private set

    override fun segment(samples: FloatArray, sampleRate: Int): SpeechSegments {
        calls++
        lastSamples = samples
        return SpeechSegments(segments)
    }
}
