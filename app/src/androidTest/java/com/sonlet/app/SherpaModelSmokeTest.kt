package com.sonlet.app

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M0 Gate 仪器冒烟测试(PRD §17 M0、T0-05):
 * 目标真机加载 JNI、校验随包模型、完成官方固定样本(zh/en)转写。
 * 同时输出 load/decode/RTF 计时行,作为 T1-04 性能基线数据源。
 */
@RunWith(AndroidJUnit4::class)
class SherpaModelSmokeTest {

    private val appContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val testContext: Context =
        InstrumentationRegistry.getInstrumentation().context

    private fun newRecognizer(): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = MODEL_ASSET,
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = TOKENS_ASSET,
                numThreads = 2,
                provider = "cpu",
            ),
        )
        return OfflineRecognizer(appContext.assets, config)
    }

    private fun transcribe(recognizer: OfflineRecognizer, asset: String): Pair<String, Double> {
        val wave = WaveReader.readWave(testContext.assets, asset)
        val audioMs = wave.samples.size * 1000.0 / wave.sampleRate
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(wave.samples, wave.sampleRate)
            val decodeStart = System.nanoTime()
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000
            val rtf = decodeMs / audioMs
            Log.i(
                TAG,
                "asr_benchmark asset=$asset audioMs=%.0f decodeMs=%d rtf=%.3f lang=%s text=%s"
                    .format(audioMs, decodeMs, rtf, result.lang, result.text),
            )
            return result.text to rtf
        } finally {
            stream.release()
        }
    }

    /** 官方固定样本转写:zh/en 均非空,RTF 记录供基线使用 */
    @Test
    fun modelLoadsAndTranscribesFixedSamples() {
        logDeviceInfo()
        val loadStart = System.nanoTime()
        val recognizer = newRecognizer()
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        Log.i(TAG, "asr_benchmark phase=load loadMs=$loadMs")
        try {
            val (zhText, zhRtf) = transcribe(recognizer, "test_wavs/zh.wav")
            val (enText, enRtf) = transcribe(recognizer, "test_wavs/en.wav")
            assertTrue("中文固定样本转写为空", zhText.isNotBlank())
            assertTrue("英文固定样本转写为空", enText.isNotBlank())
            assertTrue("RTF 异常(zh=${zhRtf})", zhRtf > 0 && zhRtf < 50)
            Log.i(TAG, "smoke_ok zh=\"${zhText}\" en=\"$enText\"")
        } finally {
            recognizer.release()
        }
    }

    /** 重复创建/释放无 JNI 崩溃(PRD T1-03 前置) */
    @Test
    fun repeatedInitReleaseDoesNotCrash() {
        logDeviceInfo()
        repeat(3) { round ->
            val recognizer = newRecognizer()
            try {
                val wave = WaveReader.readWave(testContext.assets, "test_wavs/zh.wav")
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(wave.samples, wave.sampleRate)
                    recognizer.decode(stream)
                    assertTrue(
                        "第 ${round + 1} 轮转写为空",
                        recognizer.getResult(stream).text.isNotBlank(),
                    )
                } finally {
                    stream.release()
                }
            } finally {
                recognizer.release()
            }
        }
    }

    /** 官方测试样本采样率应与模型一致(PRD §10.1) */
    @Test
    fun fixedSampleSampleRateMatchesModelSpec() {
        val wave = WaveReader.readWave(testContext.assets, "test_wavs/zh.wav")
        assertEquals(16_000, wave.sampleRate)
    }

    private fun logDeviceInfo() {
        Log.i(
            TAG,
            "device_info model=${Build.MODEL} manufacturer=${Build.MANUFACTURER} " +
                "sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull()}",
        )
    }

    private companion object {
        const val TAG = "SonletSmoke"
        const val MODEL_ASSET = "sensevoice/model.int8.onnx"
        const val TOKENS_ASSET = "sensevoice/tokens.txt"
    }
}
