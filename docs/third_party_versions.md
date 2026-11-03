# Sonlet 第三方依赖版本清单

> PRD §6.3:不允许构建脚本拉取未知分支;所有版本、哈希记录于此。更新任何条目须同步 `models/manifest.json` 与本文档。
> 记录日期:2026-11-04

## sherpa-onnx(Kotlin/JNI 端侧推理)

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/k2-fsa/sherpa-onnx |
| 锁定版本 | `v2.0.1`(2026-10-26 发布,稳定版) |
| Android 归档 | `sherpa-onnx-v2.0.1-android.tar.bz2` |
| 归档 URL | https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-v2.0.1-android.tar.bz2 |
| 归档 SHA-256 | `39003753ec293425ed56723ea9b972b98841a490af3311670c3060548d654330` |
| Kotlin API 来源 | `sherpa-onnx/kotlin-api/OfflineRecognizer.kt`、`OfflineStream.kt`(同 v2.0.1 tag,复制入 `app/src/main/java/com/k2fsa/sherpa/onnx/`) |
| 取用的 `.so`(arm64-v8a,锁定版本实际输出) | `libsherpa-onnx-jni.so`、`libonnxruntime.so`(另有 c-api/cxx-api 未取用) |
| Kotlin API 与 PRD §8.4 草案核对 | `OfflineRecognizerConfig` / `OfflineSenseVoiceModelConfig(model, language, useInverseTextNormalization)` / `createStream` / `decode` / `getResult` / `release` 全部吻合 |

## SenseVoice 模型(P0 默认,随包内置)

| 项 | 值 |
|---|---|
| 归档 | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2` |
| 归档 URL | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2 |
| 镜像 | https://huggingface.co/k2-fsa/sherpa-onnx-models/resolve/main/asr-models/ 同名文件 |
| 归档 SHA-256 | `7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e` |
| `model.int8.onnx` SHA-256 | `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`(约 241 MB) |
| `tokens.txt` SHA-256 | `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc`(约 312 KB) |
| 官方固定样本 | `test_wavs/zh.wav`、`en.wav` 已提交至 `app/src/androidTest/assets/test_wavs/`(SHA-256 见 models/manifest.json 同源归档) |
| `useInverseTextNormalization` | `true` |
| `language` | `auto` |

## 工程工具链

| 项 | 值 |
|---|---|
| Gradle | 8.11.1(发行版 SHA-256 `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21(含 `org.jetbrains.kotlin.plugin.compose` 2.0.21) |
| Compose BOM | 2024.12.01 |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| ABI | arm64-v8a(PRD D-04) |
| Gradle JVM | Android Studio JBR 21(`org.gradle.java.home`) |

## 复核规则

- sherpa-onnx 升级必须同步替换:Kotlin API 源文件、jniLibs `.so`、并重跑仪器冒烟测试。
- 模型文件不得手动更名或重打包;仅允许 `scripts/fetch_model.sh` 校验后落位。
- master/dev 可变分支内容不作为 API 契约;升级前将来源映射到固定 commit/tag(§21)。
