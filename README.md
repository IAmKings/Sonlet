# Sonlet

**Speak locally. Build freely.**

面向 Android 的轻量端侧语音工具集。P0 闭环与 P1 交互增强均已实现并在真机/模拟器验证:点击或按住录音、本地 SenseVoice INT8 转写、Silero VAD 分段、文本风格处理、结果插入当前编辑框;完全离线,不申请 INTERNET 权限。

- [设计系统与交互规范](DESIGN.md)
- [产品需求与技术方案](docs/Hex_Android_Offline_ASR_PRD_v1.0.md)
- [真人验收指南](docs/acceptance_guide.md)
- [第三方版本锁定清单](docs/third_party_versions.md)
- [第三方许可证清单](docs/third_party_licenses.md)
- [发布门检查清单](docs/release_checklist.md)
- [升级路径验证方案](docs/upgrade_verification_plan.md)
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
- Android SDK Platform 37、Build Tools 36.0.0 和 Python 3
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

## 发布

发布分两步：**候选构建**与**公开动作**。推 tag 只产生候选包，不会自动出现在 Releases 页面。

### 1. 构建候选包

推一个 `vMAJOR.MINOR.PATCH` 形式的 tag，`.github/workflows/release.yml`（Release Candidate）会构建并校验：

```sh
git tag -a v0.1.5 -m "v0.1.5"
git push origin v0.1.5
```

工作流依次：读取已公开版本 → 解析版本 → 还原签名密钥 → 拉取并校验模型 → `testDebugUnitTest`、`lintRelease`、`assembleRelease` → 用 `scripts/verify_release_apk.py` 校验版本、签名证书、模型资产与离线权限 → 上传 artifact `sonlet-release-apk`。

跑完在该 run 的 Summary 里拿到三样东西：`tag`、`run_id`、候选包 `SHA-256`。

只想演练、不产出候选包以外的副作用：

```sh
gh workflow run "Release Candidate" --ref main -f tag=v0.1.5
```

模型归档约 460MB，按归档 SHA 缓存，重跑不会重复下载。

### 2. 公开发布

在 Actions → Publish 手动触发，填入上一步的 `tag`、`run_id`、`expected_sha256`，以及批准人。该工作流绑定 `release` environment，需要仓库 Settings → Environments → `release` 里配置的 Required reviewers 批准后才会执行。

执行时依次校验：`release` environment 存在 → tag 格式 → **tag 指向的提交等于该 run 的 headSha** → 下载 artifact 并重算 **SHA-256 与输入一致** → 创建 Release 并上传附件。

已存在的 Release 不会被覆盖：同名附件摘要相同则直接成功（幂等重跑），摘要不同则失败并保留原附件。

版本号只从 tag 取：versionCode 按 `major*10000 + minor*100 + patch` 计算，次版本与修订号限 0～99，且必须大于**已公开发布**的最大 versionCode（由 `gh release list` 求出，不再写死）。`app/build.gradle.kts` 里的默认值只影响本地构建，CI 由 tag 注入。

复核历史包或离线核对时可跳过递增门：

```sh
python3 scripts/verify_release_apk.py v0.1.0 --apk <path> --build-tools <sdk>/build-tools/36.0.0 --published-version-code 0
```

公开发行前，按[发布准备计划](docs/release_readiness_plan.md)与[发布门检查清单](docs/release_checklist.md)记录设备验收、APK 哈希与分发批准。

## Release 签名

Release 包用专用 keystore 签名,参数放在仓库根目录的 `keystore.properties`(已 gitignore,不入库)。首次在本地执行:

```sh
scripts/create_release_keystore.sh
```

脚本在终端里索要口令(输入不回显,也不进命令行参数),生成 `release.jks` 并回填 `keystore.properties`。也可以自己建好 keystore,再按下面格式手填:

```properties
storeFile=release.jks
storePassword=你的口令
keyAlias=sonlet
keyPassword=你的口令
```

没有可用密钥时 `./gradlew assembleRelease` 产出 `app-release-unsigned.apk` 并打印提示,不会回落到 debug 证书。

`release.jks` 必须备份，升级包沿用已发布证书。CI 运行需要四个仓库 secret（Settings → Secrets and variables → Actions）：

| secret | 内容 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -i release.jks` 的输出 |
| `RELEASE_STORE_PASSWORD` | keystore 口令 |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥口令 |

工作流使用 `scripts/verify_release_apk.py` 校验版本、签名、模型、NOTICE、原生库和离线权限。

## 已知限制(P0/P1,如实记录;T4-05 已签署 Conditional Go,2026-11-04)

- 仅 arm64-v8a;其他 ABI 设备未包含原生库。
- 中英混说准确率未量化(AC-P0-020,评测工装就绪,语料提供后可一键补测,见 docs/accuracy_eval.md)。
- 通话打断无音频焦点协作,依赖采集读取失败兜底(蓝牙/拔线场景已覆盖)。
- 许可证原文已核(均为 MIT/Apache-2.0),法务签字为对外分发前置条件;当前限内部验证与受限内测(docs/release_decision.md)。
- Dependabot 报告的 61 条依赖告警全部位于构建工具链(classpath),不进入 APK;随 AGP/Kotlin 插件升级清理,定性见 docs/dependency_security.md。
- P2(流式/多引擎/模型下载/端侧 LLM/AAR)经 2026-11-04 决策不启动。

## Git 提交

提交标题和说明使用中文,必要的技术名称可保留原文。首次克隆后启用仓库提交钩子(需要 Python 3):

```sh
git config core.hooksPath .githooks
```

运行提交检查测试:

```sh
python3 -m unittest discover -s tests -v
```
