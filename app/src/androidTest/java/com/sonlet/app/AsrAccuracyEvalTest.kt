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

        val repo = ModelRepository(appContext.assets)
        assertTrue("模型应就绪", repo.verify() is ModelInstallStatus.Ready)
        val engine = SherpaEngineFactory.create(appContext.assets)
        engine.prepare()

        val entries = readManifest()
        // 模板态(无有效语料行)跳过而非失败,避免污染常规回归
        assumeTrue("corpus 无有效语料行,跳过评测", entries.isNotEmpty())
        val results = mutableListOf<Entry>()
        try {
            entries.forEachIndexed { index, entry ->
                val wave = WaveReader.readWave(
                    testContext.assets,
                    "$CORPUS_DIR/${entry.file}",
                )
                val started = System.nanoTime()
                val result = engine.transcribe(index.toLong(), wave.samples, wave.sampleRate)
                val decodeMs = (System.nanoTime() - started) / 1_000_000
                val metric = when (entry.lang) {
                    "en" -> TextMetrics.wer(entry.groundTruth, result.text)
                    else -> TextMetrics.cer(entry.groundTruth, result.text)
                }
                results.add(entry.copy(metric = metric, rtf = result.decodeMs.toDouble() / result.audioMs))
                val current = results.last()
                Log.i(
                    TAG,
                    "accuracy_result file=${current.file} lang=${current.lang} " +
                        "metric=%.4f rtf=%.3f".format(current.metric!!, current.rtf!!) +
                        " hyp=\"${result.text}\"",
                )
            }
        } finally {
            engine.release()
        }

        writeCsv(results)
        val byLang = results.groupBy { it.lang }
        byLang.forEach { (lang, group) ->
            val avg = group.map { it.metric!! }.average()
            val rtfP95 = group.map { it.rtf!! }.sorted()[((group.size - 1) * 95) / 100]
            Log.i(
                TAG,
                "accuracy_summary lang=$lang n=${group.size} " +
                    "${if (lang == "en") "WER" else "CER"}_avg=%.4f rtf_p95=%.3f".format(avg, rtfP95),
            )
        }
        Log.i(TAG, "accuracy_done total=${results.size}")
    }

    private data class Entry(
        val file: String,
        val lang: String,
        val groundTruth: String,
        val metric: Double? = null,
        val rtf: Double? = null,
    )

    private fun readManifest(): List<Entry> {
        val available = testContext.assets.list(CORPUS_DIR)?.toSet() ?: emptySet()
        val entries = testContext.assets.open("$CORPUS_DIR/$MANIFEST").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(",", limit = 3)
                require(parts.size == 3) { "manifest.csv 行格式错误: $line" }
                val file = parts[0].trim()
                // 跳过表头与缺失 wav 的行(native WaveReader 对缺失资产不可恢复)
                if (file == "file" || file !in available) {
                    Log.w(TAG, "skip manifest row (no wav): $line")
                    null
                } else {
                    Entry(file, parts[1].trim(), parts[2].trim())
                }
            }
        return entries
    }

    private fun writeCsv(results: List<Entry>) {
        val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "eval")
        dir.mkdirs()
        val out = File(dir, "accuracy_results.csv")
        val header = "file,lang,metric,rtf,ground_truth"
        val rows = results.joinToString("\n") { entry ->
            val gt = entry.groundTruth.replace("\"", "\"\"")
            val metric = "%.4f".format(entry.metric ?: 0.0)
            val rtf = "%.3f".format(entry.rtf ?: 0.0)
            "${entry.file},${entry.lang},$metric,$rtf,\"$gt\""
        }
        out.writeText(header + "\n" + rows + "\n")
        Log.i(TAG, "csv_written ${out.absolutePath}")
    }

    private companion object {
        const val TAG = "SonletEval"
        const val CORPUS_DIR = "corpus"
        const val MANIFEST = "manifest.csv"
    }
}
