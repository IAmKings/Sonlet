# AC-P0-020 准确率评测操作指引

PRD 要求:每类(中/英/混合)至少 30 条带标注真值的样本,输出 CER/WER、分类错误分析与 RTF 分位数,不以主观感受代替结论。

## 语料录制要求

| 项 | 要求 |
|---|---|
| 数量 | zh / en / mixed 各 ≥30 条(PRD §16.2 建议另含安静/噪声/近讲/远讲维度) |
| 格式 | 16 kHz、单声道、PCM16 wav(与模型输入一致) |
| 真值 | 人工听写标注,存入 `manifest.csv` 第三列 |
| 归档 | 原始音频与真值属于用户数据,**不入 git**;评估产物 CSV 脱敏后归档 |

## 放置与运行

```sh
# 1. 将 wav 与填写好的 manifest.csv 放入:
#    app/src/androidTest/assets/corpus/
# 2. 真机运行(语料缺失时该测试自动跳过):
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sonlet.app.AsrAccuracyEvalTest
# 3. 取回结果并归档:
adb pull /sdcard/Android/data/com.sonlet.app/files/eval/accuracy_results.csv docs/benchmarks/
```

## 输出说明

- `accuracy_result` 行:每条样本的 CER(中)/ WER(英)、RTF、识别文本(仅本地日志)
- `accuracy_summary` 行:分类平均 CER/WER 与 RTF P95 → 填入 `docs/release_checklist.md` G06
- 归一化口径:去标点、小写、全角转半角(见 `TextMetrics.normalize`),与 PRD §2.2 测量口径对齐

## 已知边界

- mixed 类按 CER 计(字符级对中英混说更稳);如需分语言指标,在 manifest 中细分 lang。
- 评测使用 `useInverseTextNormalization=true` 的模型原始输出,与产品交付路径一致。
