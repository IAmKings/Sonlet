# Sonlet

**Speak locally. Build freely.**

面向 Android 的轻量端侧语音工具集。P0 闭环与 P1 交互增强均已实现并在真机/模拟器验证:点击或按住录音、本地 SenseVoice INT8 转写、Silero VAD 分段、文本风格处理、结果插入当前编辑框;完全离线,不申请 INTERNET 权限。

- [设计系统与交互规范](DESIGN.md)
- [产品需求与技术方案](docs/Hex_Android_Offline_ASR_PRD_v1.0.md)
- [第三方版本锁定清单](docs/third_party_versions.md)
- [第三方许可证清单](docs/third_party_licenses.md)
- [发布门检查清单](docs/release_checklist.md)
- [标识使用指南](docs/brand/sonlet-logo-kit/使用指南.md)

## 工程结构

单 Gradle app 模块(`com.sonlet.app`),按 PRD §7.2 分包:

```
app/src/main/java/com/sonlet/app/
  asr/api/       AsrEngine 抽象、结果与错误契约
  asr/sherpa/    sherpa-onnx 引擎实现与模型仓库校验
  audio/capture/ AudioRecord 采集、路由中断观察
  dictation/core/ 听写状态机、会话守卫、文本插入策略、格式化
  feature/dictation/ Compose 编辑页、ViewModel、权限交互
  ui/theme/      设计规范映射的 Material 3 主题
```

## 环境要求

- Android Studio(含 JDK 21 JBR;`gradle.properties` 已指定 `org.gradle.java.home`)
- Android SDK Platform 35
- arm64-v8a 真机(API 26+);模拟器仅可验证构建,JNI 冒烟需真机

## 模型与依赖

模型二进制不入库,由脚本校验下载(PRD §11):

```sh
scripts/fetch_model.sh
```

脚本固定下载 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`(约 163MB),校验 SHA-256 后解压 `model.int8.onnx` / `tokens.txt` 到 `app/src/main/assets/sensevoice/`,并放置 zh/en 固定样本到 androidTest assets。sherpa-onnx v2.0.1 的 Kotlin API 与 jniLibs 已随仓库提交,版本与哈希记录见 [docs/third_party_versions.md](docs/third_party_versions.md)。

## 构建、测试与运行

```sh
# 构建
./gradlew assembleDebug

# JVM 单测(状态机 / 守卫 / 插入策略 / 格式化 / Fake 引擎)
./gradlew testDebugUnitTest

# 真机仪器测试(JNI 冒烟、引擎契约、采集生命周期)
./gradlew connectedDebugAndroidTest

# 100 次会话长稳测试(AC-P0-019,单独运行)
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sonlet.app.LongRunDictationStressTest
```

安装到真机:`adb install -r app/build/outputs/apk/debug/app-debug.apk`,或直接在 Android Studio 运行。首次启动自动进行模型 SHA-256 校验(约 1–2 秒),通过后进入 READY。

## 已知限制(P0/P1,如实记录)

- 仅 arm64-v8a;其他 ABI 设备未包含原生库。
- 通话打断的系统性检测未实现音频焦点协作,依赖采集读取失败路径兜底(蓝牙/拔线场景已覆盖)。
- 模型许可证(FunASR/SenseVoice/Silero VAD)与 ONNX Runtime 的再分发条款复核仍为 PENDING,产物仅限内部验证使用(见 docs/third_party_licenses.md)。
- 中英混说准确率未做数据集评估(AC-P0-020 需人工语料)。
- P2(流式/多引擎/模型下载/AAR SDK)未实现。

## Git 提交

提交标题和说明使用中文,必要的技术名称可保留原文。首次克隆后启用仓库提交钩子(需要 Python 3):

```sh
git config core.hooksPath .githooks
```

运行提交检查测试:

```sh
python3 -m unittest discover -s tests -v
```
