package com.sonlet.app

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import com.sonlet.app.asr.eval.TextMetrics
import com.sonlet.app.asr.sherpa.ModelInstallStatus
import com.sonlet.app.asr.sherpa.ModelRepository
import com.sonlet.app.asr.sherpa.SherpaEngineFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.ceil

/**
 * AC-P0-020 准确率评测脚本(PRD T1-05/T4-03 工装)。
 *
 * 语料放置:app/src/androidTest/assets/corpus/
 *   - manifest.csv:每行 `file,lang,ground_truth`(lang ∈ zh/en/mixed)
 *   - 对应 wav 文件(16kHz mono PCM16)
 * 运行:
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.sonlet.app.AsrAccuracyEvalTest
 * 结果:logcat `accuracy_result` 行 + 设备 files/accuracy_results.csv,adb pull 归档至 docs/benchmarks/。
 * 语料缺失时测试自动跳过,不影响常规回归。
 * 门槛:zh CER ≤ 10%、en WER ≤ 10%、mixed CER ≤ 12%(实测基线见 docs/benchmarks/),
 * 超限即失败并打印该语言最差的 5 条,避免"跑完没人看日志"。
 */
@RunWith(AndroidJUnit4::class)
class AsrAccuracyEvalTest {

    private val appContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val testContext: Context =
        InstrumentationRegistry.getInstrumentation().context

    @Test
    fun evaluateCorpusIfPresent(): Unit = runBlocking {
        val files = testContext.assets.list(CORPUS_DIR) ?: emptyArray()
        val manifestPresent = files.contains(MANIFEST)
        assumeTrue("corpus/manifest.csv 不存在,跳过评测(放置语料后运行)", manifestPresent)

        val entries = readManifest()
        assumeTrue("corpus 无有效语料行,跳过评测", entries.isNotEmpty())
        for (language in LANGUAGES) {
            require(entries.count { it.lang == language } >= 30) { "$language 需要至少 30 条样本" }
        }
        val repo = ModelRepository(appContext.assets)
        assertTrue("模型应就绪", repo.verify() is ModelInstallStatus.Ready)
        val engine = SherpaEngineFactory.create(appContext.assets)
        val results = mutableListOf<SampleResult>()
        try {
            engine.prepare()
            entries.forEachIndexed { index, entry ->
                val wave = WaveReader.readWave(
                    testContext.assets,
                    "$CORPUS_DIR/${entry.file}",
                )
                val started = System.nanoTime()
                val result = engine.transcribe(index.toLong(), wave.samples, wave.sampleRate)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                require(result.audioMs > 0) { "样本 ${index + 1} 音频时长无效" }
                val metric = when (entry.lang) {
                    "en" -> TextMetrics.wer(entry.groundTruth, result.text)
                    else -> TextMetrics.cer(entry.groundTruth, result.text)
                }
                results.add(SampleResult(index + 1, entry.lang, metric, result.decodeMs.toDouble() / result.audioMs, elapsedMs))
            }
        } finally {
            engine.release()
        }

        writeCsv(results)
        val byLang = results.groupBy { it.lang }
        byLang.forEach { (lang, group) ->
            val avg = group.map { it.metric }.average()
            val rtf = group.map { it.rtf }.sorted()
            val elapsed = group.map { it.elapsedMs.toDouble() }.sorted()
            Log.i(
                TAG,
                "accuracy_summary lang=$lang n=${group.size} " +
                    "${if (lang == "en") "WER" else "CER"}_avg=%.4f rtf_p50=%.3f rtf_p95=%.3f "
                        .format(Locale.ROOT, avg, rtf.percentile(0.50), rtf.percentile(0.95)) +
                    "engine_ms_p50=%.0f engine_ms_p95=%.0f"
                        .format(Locale.ROOT, elapsed.percentile(0.50), elapsed.percentile(0.95)),
            )
        }
        val violations = byLang.mapNotNull { (lang, group) ->
            val limit = THRESHOLDS.getValue(lang)
            val avg = group.map { it.metric }.average()
            if (avg <= limit) {
                null
            } else {
                val worst = group.sortedByDescending { it.metric }.take(5)
                    .joinToString("; ") { "#${it.sampleId}=%.3f".format(Locale.ROOT, it.metric) }
                "%s 平均 %.4f 超过门槛 %.2f，最差 5 条: %s"
                    .format(Locale.ROOT, if (lang == "en") "WER" else "CER", avg, limit, worst)
            }
        }
        assertTrue(
            "准确率低于回归门槛:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
        Log.i(TAG, "accuracy_done total=${results.size}")
    }

    private data class Entry(
        val file: String,
        val lang: String,
        val groundTruth: String,
    )

    private data class SampleResult(
        val sampleId: Int,
        val lang: String,
        val metric: Double,
        val rtf: Double,
        val elapsedMs: Long,
    )

    private fun List<Double>.percentile(rank: Double): Double = this[ceil(size * rank).toInt() - 1]

    private fun readManifest(): List<Entry> {
        val available = testContext.assets.list(CORPUS_DIR)?.toSet() ?: emptySet()
        val rows = testContext.assets.open("$CORPUS_DIR/$MANIFEST").bufferedReader().use { it.readLines() }
        val entries = rows.map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .filterNot { it == "file,lang,ground_truth" }
            .mapIndexed { index, line ->
                val parts = line.split(",", limit = 3)
                require(parts.size == 3) { "manifest.csv 样本 ${index + 1} 格式错误" }
                val file = parts[0].trim()
                val language = parts[1].trim()
                val truth = parts[2].trim()
                require(file.endsWith(".wav") && file in available) { "找不到样本 ${index + 1} 的 WAV" }
                require(language in LANGUAGES && truth.isNotEmpty()) { "样本 ${index + 1} 的语言或真值无效" }
                Entry(file, language, truth)
            }
        require(entries.map { it.file }.toSet().size == entries.size) { "语料包含重复 WAV" }
        return entries
    }

    private fun writeCsv(results: List<SampleResult>) {
        val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "eval")
        dir.mkdirs()
        val out = File(dir, "accuracy_results.csv")
        val header = "sample_id,lang,metric,rtf,engine_ms"
        val rows = results.joinToString("\n") { entry ->
            "%d,%s,%.4f,%.3f,%d".format(Locale.ROOT, entry.sampleId, entry.lang, entry.metric, entry.rtf, entry.elapsedMs)
        }
        out.writeText(header + "\n" + rows + "\n")
        Log.i(TAG, "csv_written ${out.absolutePath}")
    }

    private companion object {
        const val TAG = "SonletEval"
        val THRESHOLDS = mapOf("zh" to 0.10, "en" to 0.10, "mixed" to 0.12)
        const val CORPUS_DIR = "corpus"
        const val MANIFEST = "manifest.csv"
        val LANGUAGES = setOf("zh", "en", "mixed")
    }
}
