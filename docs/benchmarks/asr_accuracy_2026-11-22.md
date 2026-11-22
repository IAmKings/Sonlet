# 准确率与延迟基线（2026-11-22，v0.1.3 同源代码）

语料：`app/src/androidTest/assets/corpus/`，90 条合成语音（zh / en / mixed 各 30 条，16kHz 单声道 PCM16，平均 2.52 秒）。
设备：Redmi 23078PND5G（corot），Android 14，SenseVoice INT8 + Silero VAD，全离线。

| 语言 | 样本 | 指标 | 平均 | 最差单条 | RTF p50 | RTF p95 | 引擎耗时 p50 | p95 |
|---|---|---|---|---|---|---|---|---|
| zh | 30 | CER | **1.66%** | 16.7%（#10） | 0.058 | 0.074 | 159 ms | 259 ms |
| en | 30 | WER | **2.08%** | 33.3%（#53） | 0.066 | 0.088 | 135 ms | 180 ms |
| mixed | 30 | CER | **2.74%** | 25.0%（#84） | 0.058 | 0.082 | 164 ms | 235 ms |

90 条总耗时 17.1 秒（含模型加载与逐条解码），RTF = 解码毫秒 / 音频毫秒。

## 复现方式

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.sonlet.app.AsrAccuracyEvalTest \
  com.sonlet.app.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d | grep SonletEval
adb pull /storage/emulated/0/Android/data/com.sonlet.app/files/eval/accuracy_results.csv
```

语料可由 `python3 scripts/gen_corpus_tts.py` 重新生成（macOS，`say` + `afconvert`）。

## 这个数字能说明什么

能说明：模型与 JNI 链路、量化模型、解码路径在当前设备上工作正常，误差在个位数百分比，延迟远低于实时（RTF ≈ 0.06，30 秒音频约 1.8 秒解码）。

不能说明：真人语音的分布。合成语料没有口音、语速变化、口吃、重复、背景噪声、远场混响，也没有麦克风频响差异——这些恰恰是端侧听写最容易翻车的地方。最差单条（英文 33.3%、中文 16.7%）都出现在合成语音的连读处，真人环境大概率更多。

因此本基线只作**回归门槛**：后续改动若让三项指标大幅变差，说明链路退化了；但它不能替代真人验收，真人语料仍需按计划补充。

## 回归门槛

`AsrAccuracyEvalTest` 在测完后按语言断言上限（约为实测值的 3–5 倍，留出设备与语料差异的余量）：

| 语言 | 门槛 |
|---|---|
| zh | CER ≤ 10% |
| en | WER ≤ 10% |
| mixed | CER ≤ 12% |

超过门槛直接失败并打印该语言最差的 5 条，避免"跑完了但没人看日志"。
