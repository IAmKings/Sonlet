# Hex 风格 Android 离线语音输入组件 — 产品需求与技术执行 PRD v1.0

> **项目代号**：HexVoice-Android（暂定，仅作工程内部名称）  
> **文档类型**：PRD + 技术方案 + 可执行验收基线  
> **版本**：v1.0 / Draft for Implementation  
> **编制日期**：2026-11-03  
> **目标平台**：Android（Kotlin、Jetpack Compose）  
> **实现范围**：集成至开发者自己的 App，不包含 Android 系统输入法（IME）  
> **默认语言**：普通话 / 英语；中英混说需要专项测试  
> **网络策略**：核心识别完全离线，不依赖远程 API  
> **优先级**：P0 可发布 MVP → P1 Hex 交互增强 → P2 实时与多模型

---

## 0. 一页式决策摘要

**要做什么**：在 Android App 内提供与 Hex 类似的语音输入能力，支持“开始录音—结束录音—端侧 ASR 转写—插入编辑框”，后续演进到“按住说话、松开转写、VAD 分段、语音波形、文本风格处理”。

**首选路线**：`Kotlin + Compose + AudioRecord + sherpa-onnx + SenseVoice Small INT8`。采用 Kotlin 接口隔离 ASR 引擎，以单模型单任务推理和会话代际 ID 保证结果交付安全。

**P0 明确不做**：系统级输入法、后台常驻录音、云端识别、端侧 LLM 润色、原生流式 ASR、跨进程语音服务、登录与同步、音频历史上传。

**产品主要风险**：非流式推理延迟、模型约 228 MB、设备性能分化、麦克风/Activity 生命周期竞态、JNI 对象释放、语言准确率不足。

**重要约束**：本方案属设计与代码级分析，不代表已在目标 Android 真机完成构建验证。任何 SDK 版本、ABI 库与模型文件都必须固定、核验，并通过 M1 实机技术门槛后才能进入 P0 发布流程。

### 0.1 已确认事实与待验证事项

| 事项 | 状态 | 依据/说明 |
|---|---|---|
| Hex iOS 采用 Apple Speech 与 FluidAudio/Parakeet 两种端侧后端 | 已从源码确认 | Hex `ios/README.md`、`AppleSpeechTranscriber.swift`、`ParakeetTranscriber.swift` |
| Hex iOS 使用录音/任务 generation 隔离，键盘与宿主跨扩展交互 | 已从源码确认 | `DictationController.swift`、`KeyboardBridge.swift` |
| sherpa-onnx 支持 Kotlin/JNI `OfflineRecognizer`/`OfflineStream` | 已从源码确认 | sherpa-onnx `kotlin-api/OfflineRecognizer.kt` |
| SenseVoice 2024-07-17 INT8 模型支持普通话、英文等，并可结合 ITN 产生标点 | 官方模型文档确认 | 见附录来源 S3 |
| 具体机型上的识别耗时、峰值内存、稳定性 | **待实机验证** | M1 / M4 门槛 |
| 模型权重、Native 动态库最终分发许可证/Notice | **待逐项核验** | M0 合规任务，不得以主仓库许可证代替模型许可证 |
| 中英混说准确率 | **待数据集评估** | 不应直接推定与单语表现相同 |

---

## 1. 背景、问题与机会

### 1.1 背景

Hex 桌面和 iOS 设计强调快捷语音输入、端侧转写、结果直接交付以及稳定的语音任务控制。用户希望在 Android 自有应用内实现相似体验，而非克隆 Hex iOS 的 SwiftUI/AVFAudio/Core ML 实现。Android 端已有 `AudioRecord` 和成熟的 ONNX 推理生态，具备可行性。

### 1.2 用户痛点

1. 长段文字输入慢，在移动设备上键盘输入负担明显。
2. 部分系统语音服务依赖厂商能力或网络，离线体验不可控。
3. 连续录音、取消操作、页面切换后，容易发生旧任务回填、语音资源未释放等异常。
4. 普通识别输出没有统一的格式规范，中文标点、插入位置、专业术语需要处理。
5. 现有方案往往把 UI、麦克风和模型写在同一类中，不利于 Android 项目复用与模型替换。

### 1.3 产品定位

定位为 **App 内嵌、隐私优先、可复用的离线语音输入组件**，提供可靠的转写和文本插入能力。其核心价值是输入效率与稳定性，而不是聊天 AI 或全局输入法。

---

## 2. 目标、指标和非目标

### 2.1 业务目标（P0）

- O1：用户可以在应用编辑框中通过语音完成文本输入，不必使用联网 ASR。
- O2：普通话和英文短语音可识别；中英混说能力经测试明确边界。
- O3：取消/切后台/权限撤销/音频打断时无错误结果回填，麦克风最终可靠释放。
- O4：ASR 后端可以通过接口替换，不改业务 Controller 和 Compose UI。
- O5：提供真实设备和标准音频样本的可重复测试记录，而非仅凭主观“效果不错”。

### 2.2 建议指标（目标值，**不是既有实测性能**）

| 指标 | 初步目标 | 测量口径 | 阶段 |
|---|---|---|---|
| 单次录音最大时长 | 30 s | 自开始成功采集至停止 | P0 |
| 最短有效音频 | ≥300 ms | 少于阈值判作误触，不转写 | P0 |
| 录音启动反馈 | 点击后立即进入 STARTING，启动成功后 RECORDING | UI 状态事件时间 | P0 |
| 转写 RTF | 中档测试机 P95 ≤0.5（待证实） | `decode 耗时 / 音频长度`；按机型分层 | P0 性能门槛 |
| 5 秒短音频最终转写等待 | 中档测试机 P95 ≤2 s（初始目标） | 点击“结束”到结果可见；不含初次模型加载 | P0 性能门槛 |
| 错误结果回填 | 0 次 | 取消 / 过期 / 新会话覆盖测试 | P0 硬门槛 |
| 资源稳定性 | 100 次连续会话无 JNI 崩溃及持续性麦克风占用 | 真机自动化 + 手工校验 | P0 硬门槛 |
| 完全离线 | 100% P0 识别路径可断网运行 | 飞行模式实测；不请求云端推理 | P0 硬门槛 |
| 中文 CER / 英文 WER | M1 建立基线，M4 与候选模型对比 | 固定样本与人工真值 | P0 数据验收 |

> 若 M1 测量无法达到初始 RTF/等待时延目标，不应隐藏结果。需要通过设备分档、模型对比、UX 降级或调整验收阈值作正式决策。

### 2.3 非目标

P0 **不包括**：系统 IME/其他 App 插入、悬浮窗、持续后台采集、实时 partial text、服务端 ASR、多设备同步、跨账户个人词库、端侧 LLM、录音音频长期存储、通话录音、多说话人分离、指令识别、外部麦克风设备管理 UI。

---

## 3. 目标用户与使用场景

**主要用户**：需要在自己使用的 Android 应用中频繁输入笔记、搜索内容、聊天草稿或长文本的用户。Android 开发团队为内部集成方。

### 3.1 核心用户故事

| ID | 用户故事 | 优先级 | 成功结果 |
|---|---|---|---|
| US-001 | 作为用户，我想点击麦克风后说话，并在结束后自动得到文本 | P0 | 能在当前编辑框看到最终文本 |
| US-002 | 作为用户，我想在完全断网时继续使用语音输入 | P0 | 离线识别成功 |
| US-003 | 作为用户，我想取消正在进行的录音或转写 | P0 | 不产生无意的文本回填 |
| US-004 | 作为用户，我希望文本出现在当前光标处，选中文字被正确替换 | P0 | 编辑语义与手工输入一致 |
| US-005 | 作为用户，我希望电话、权限变化或切后台时，麦克风能够停止 | P0 | 不存在隐性采集 |
| US-006 | 作为用户，我想按住说话、松开立即提交 | P1 | 手势与录音状态一致 |
| US-007 | 作为用户，我想知道 App 是否在聆听 | P1 | 动态波形/音量反馈 |
| US-008 | 作为用户，我想把口语整理成简洁或笔记形式 | P1 | 可选规则和显式原文保留 |
| US-009 | 作为集成开发者，我想替换 ASR 模型，而不用修改页面代码 | P0 | ASR 接口与数据契约稳定 |
| US-010 | 作为用户，我想边说边看到逐步确认的文本 | P2 | 流式模型的 partial/final 交付语义正确 |

### 3.2 典型流程

- A：新安装首次启动：打开页面 → 检查模型完整性 → 模型预热成功 → 提示可以语音输入 → 点击麦克风 → 申请授权 → 开始录音。
- B：普通短语音：点击录音 → 说话 5 秒 → 点击结束 → 显示“转写中” → 最终结果进入当前编辑框。
- C：误触：点击录音，<300 ms 结束 → 忽略这段音频，不破坏当前编辑内容。
- D：取消：录音/推理中点击取消 → session 失效 → 释放/等候安全清理 → 不交付该 session 的结果。
- E：离开页面：切后台或页面被销毁 → 触发取消 → 停止采集 → 不继续隐性录音。

---

## 4. 版本范围与交付定义

### 4.1 P0 — 最小可发布闭环

| FR ID | 功能 | 具体行为 | 优先级 |
|---|---|---|---|
| FR-001 | 模型可用性检查 | 检查模型/词表存在、可读、正确配对；初始化状态与错误展示 | Must |
| FR-002 | 离线引擎初始化 | 非主线程初始化，单例/共享受控生命周期，模型预热 | Must |
| FR-003 | 麦克风权限 | 权限申请、拒绝说明、恢复路径，禁止无授权采集 | Must |
| FR-004 | 音频采集 | 前台 `AudioRecord`，16 kHz mono PCM16，30 s 上限 | Must |
| FR-005 | 开始/结束转写 | 允许 READY→STARTING→RECORDING→TRANSCRIBING→READY | Must |
| FR-006 | 结果交付 | 仅最终文本；光标插入/选区替换；空识别不修改编辑框 | Must |
| FR-007 | 取消/过期控制 | generation / sessionId 校验；不交付旧结果；取消后恢复 | Must |
| FR-008 | 生命周期恢复 | 切后台、中断、撤权、录音启动失败等资源清理 | Must |
| FR-009 | 错误反馈 | 提供可解释错误类型和可恢复操作 | Must |
| FR-010 | 离线验证 | 断网可识别；不依赖云端 API | Must |
| FR-011 | 兼容性与性能记录 | 实机冷/热启动、RTF、峰值内存、错误率 | Must |
| FR-012 | 核心自动化测试 | 状态机、重复提交、取消、资源释放测试 | Must |

### 4.2 P1 — 体验增强

- FR-101：Push-to-Talk（按住录音、松开转写、滑动取消），含“按下未启动完成即松开”的 pending finish。
- FR-102：录音时间与波形/音量反馈；无需常驻录音。
- FR-103：Silero VAD 端侧检测：语音预缓冲、静音阈值、长句分段。
- FR-104：基础文本风格：原文 / 自然 / 精简 / 笔记；对语言敏感，处理可逆或原文可见。
- FR-105：本地自定义词汇和替换规则（不承诺声学层热词提升）。
- FR-106：输出去重、段落拼接与末尾静音处理。

### 4.3 P2 — 延伸能力

- FR-201：多 ASR 引擎选择（SenseVoice / Whisper / 其他流式模型）。
- FR-202：真实流式 partial/final 转写，`OnlineRecognizer` 或适配模型。
- FR-203：按需模型包下载、断点续传、签名/哈希验证、回滚。
- FR-204：可选端侧 LLM 文本润色（与 ASR 资源调度隔离）。
- FR-205：独立 Android Library/AAR 交付、对外 Kotlin SDK API。

---

## 5. 用户交互与页面设计

### 5.1 页面清单

| 页面/组件 | 元素 | 行为 |
|---|---|---|
| `DictationEditorScreen` | 多行编辑框、光标、录音按钮、状态文案、取消按钮 | 主入口 |
| `ModelPreparationState` | 初始化进度或加载说明、失败重试 | 模型状态 |
| `PermissionExplanation` | 需要麦克风原因、系统授权入口 | 拒绝/永久拒绝恢复 |
| `ErrorSnackbar` | 错误摘要、重试或取消 | 非阻塞反馈 |
| `RecordingSurface`（P1） | 波形、计时、结束/取消手势 | Hex 风格输入 |
| `StyleSelector`（P1） | 原文/自然/精简/笔记 | 规则处理开关 |

### 5.2 P0 交互规则

1. 进入页面即检查模型并进行后台初始化；加载时禁止录音，编辑框可以继续手工输入。
2. READY 时录音按钮可点击；点击后先检查 `RECORD_AUDIO`，授权成功才创建 `AudioRecord`。
3. STARTING 显示明显反馈，此时重复点击不得创建第二个麦克风对象。
4. RECORDING 显示计时/“正在聆听”，按钮变为“结束并转写”；同时提供“取消”。
5. 点击结束后立即停止采集，进入 TRANSCRIBING；此状态不允许启动新录音（P0），但用户可继续编辑文本。
6. 转写成功：若 session 仍有效，将文本插入**当前**光标处，或替换当前选区；不自动清空已有文本。
7. 转写为空：显示“未检测到可识别语音”或轻提示，不修改编辑框。
8. 取消操作：失效该任务的交付权；停止录音，或等待不可中断 Native 调用完成后清理。取消后不会交付结果。
9. 错误状态：明确错误（模型损坏/权限不足/麦克风占用/采集失败/识别失败），提供重试路径。
10. `ON_STOP` 触发录音停止与安全取消。首版不维持后台会话。

### 5.3 文字插入与编辑冲突规则（决定性约束）

- 默认采用 **完成识别时当前编辑框的文本与光标/选区** 作为插入位置，不使用录音开始时的 `TextFieldValue` 快照，避免覆盖用户在识别期间的编辑。
- 使用 `TextFieldValue`（或等价的新 Compose TextField 状态），保留 selection 与光标语义；操作须为单次状态原子更新。
- 对最终识别结果生成 `resultId`，同一个 `resultId` 最多交付一次；重复回调不能重复插入。
- 关闭页面/丢失目标编辑器时不在其他页面自动回填；根据产品决定是否显示可复制的最终文本（P0 默认丢弃并提示）。
- 中文与英文邻接空格规则在格式化层处理；必须覆盖已有标点、选区替换、emoji、组合 Unicode、光标处于文本中间等情况。
- 原始识别结果可在运行内存中短期保留供错误说明；默认不写入永久历史。

### 5.4 视觉状态文案

| 状态 | 主文案 | 主按钮 | 次操作 |
|---|---|---|---|
| PREPARING | 正在准备离线语音模型 | 禁用 | — |
| READY | 可以开始语音输入 | 开始语音输入 | — |
| STARTING | 正在开启麦克风 | 禁用 | 取消 |
| RECORDING | 正在聆听… | 结束并转写 | 取消 |
| TRANSCRIBING | 正在本地转写… | 禁用 | 取消 |
| CANCELLING | 正在停止… | 禁用 | — |
| ERROR | 无法完成语音输入：原因 | 重试 | 返回编辑 |

---

## 6. 技术选型与替代方案

### 6.1 选型结论

- Android UI：Jetpack Compose + Material 3；ViewModel/StateFlow 管理 UI 状态。
- 麦克风：Android `AudioRecord`；首版前台采集，使用 `RECORD_AUDIO` 运行时权限。
- 端侧推理：sherpa-onnx Kotlin/JNI + CPU provider；先仅 `arm64-v8a`。
- 默认模型：`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`，`model.int8.onnx` 约 228 MB，`tokens.txt` 约 308 KB；`useInverseTextNormalization = true`。
- 音频预处理：P0 使用 16 kHz mono PCM16 转 `FloatArray`；VAD P1 扩展。
- 并发：Kotlin Coroutines + 单独推理调度队列/Mutex；解耦采集 Job 与推理 Job。
- 日志：结构化的匿名事件（session 内部 ID、耗时和错误码），**不记录原始音频/正文**。

### 6.2 模型选择矩阵

| 候选 | 长处 | 短板 | 决策 |
|---|---|---|---|
| SenseVoice Small 2024 INT8 | 普通话/英文等，端侧已有 sherpa-onnx 路线；ITN 可产生标点 | 非原生流式；模型体积较大；中英混说需测 | **P0 默认** |
| SenseVoice Small 2025 INT8 | 粤语场景可关注 | 该版官方文档对标点能力有限制 | 对照实验 |
| Paraformer 中文模型 | 中文优先、可能更轻 | 英文混说/标点能力另行评测 | M1 备选 |
| Whisper tiny/base 多语言 | 多语言生态成熟 | 速度、体积、能耗与字词准确率因机型变化 | P2 备选 |
| Parakeet TDT V2 | Hex iOS 英文路线相近 | 主要面向英文；对中文需求不适合 | 仅英文专项 |
| Android 系统 `SpeechRecognizer` | 系统集成简单 | 离线与语言支持取决于设备、地区和服务 | 降级候选，非强依赖 |

### 6.3 Native 版本固定原则

- 不允许 Gradle/构建脚本从未知分支拉取“最新” JNI 库；在 `versions.toml`、构建文档和第三方依赖清单中记录精确版本与 SHA-256。
- sherpa-onnx Kotlin 桥接源码、JNI `.so`、ONNX Runtime `.so` 和模型配置应为经过验证的兼容组合。
- `.so` 文件列表以 **锁定版本实际输出** 为准，不能假定只需要两枚库。
- 首个集成任务应从官方 Android 示例验证固定模型，再复制确切依赖组合进目标工程。
- 选择 `arm64-v8a` 作为首版 ABI；不支持设备需在安装/发布配置中明确排除或给出兼容说明。

### 6.4 许可证与模型来源

- Hex 本仓库 MIT；sherpa-onnx 仓库 Apache-2.0；不能由此推断每份模型权重、ONNX Runtime 或二进制组件都采用同一许可证。
- 在正式分发前检查模型归档内 `LICENSE`、原始 SenseVoice 模型许可、转换模型附加条款，以及 ONNX Runtime、相关原生库 License/NOTICE。
- 记录模型来源 URL、下载日期、校验值、用途与是否允许商用/再分发；法务或开源合规负责人签字后才能随包分发。

---

## 7. 软件分层与模块边界

### 7.1 架构草图

```text
┌──────────────────────────────────────────────────────────┐
│ App / feature:dictation                                 │
│ Compose Screen  ── DictationViewModel ── UiState          │
└──────────────────────────┬───────────────────────────────┘
                           │ 用户命令与状态流
┌──────────────────────────▼───────────────────────────────┐
│ dictation:core                                            │
│ DictationController / SessionCoordinator / OutputPolicy  │
│ GenerationGuard / TranscriptProcessor                    │
└──────────────┬─────────────────────────┬─────────────────┘
               │                         │
┌──────────────▼─────────────┐ ┌─────────▼────────────────┐
│ audio:capture             │ │ asr:api                  │
│ AudioRecord / RouteMonitor│ │ AsrEngine / ModelSpec    │
│ PCM Buffer / AudioStats   │ │ AsrResult / AsrError     │
└────────────────────────────┘ └─────────┬────────────────┘
                                       │
                             ┌─────────▼─────────────────┐
                             │ asr:sherpa                │
                             │ Kotlin JNI / ONNX / Model  │
                             │ ModelRepository           │
                             └───────────────────────────┘
```

### 7.2 建议包结构

```text
app/
  MainActivity.kt
feature/dictation/
  DictationScreen.kt
  DictationViewModel.kt
  DictationUiState.kt
  VoiceInputButton.kt
  PermissionUi.kt
dictation/core/
  DictationController.kt
  DictationSession.kt
  SessionGenerationGuard.kt
  TextInsertionPolicy.kt
  TranscriptProcessor.kt
audio/capture/
  PcmAudioRecorder.kt
  AudioCaptureConfig.kt
  AudioCaptureEvent.kt
  AudioInterruptionObserver.kt
asr/api/
  AsrEngine.kt
  AsrResult.kt
  AsrEngineError.kt
  ModelDescriptor.kt
asr/sherpa/
  SherpaSenseVoiceEngine.kt
  SherpaEngineFactory.kt
  ModelRepository.kt
models/
  manifest.json

src/test/              # 业务状态机、编辑逻辑、格式化单测
src/androidTest/       # 麦克风、JNI、实际模型运行的仪器测试
```

首版可按上述 package 组织在同一个 Gradle app 模块中；确认复用需求后再拆为 `:asr:api`、`:asr:sherpa`、`:audio:capture`、`:dictation:core`、`:feature:dictation`。

### 7.3 依赖方向与原则

- `feature` → `dictation:core`、`asr:api`；不得直接依赖 JNI 细节。
- `dictation:core` → `audio:capture` 抽象、`asr:api` 抽象；不读取具体模型文件。
- `asr:sherpa` → `asr:api`；负责 Native 初始化、推理与释放。
- 同一时刻至多一个 active recorder、一个 active dictation session、一个 active native decode（P0）。
- 每个 `sessionId` 必须拥有清晰的状态与结果所有权。

---

## 8. API / 数据契约（Kotlin 草案）

> 以下为工程契约草案（可据此落地），**并非已验证可直接编译的完整 SDK**。实际 sherpa-onnx 类型以锁定版本 Kotlin API 为准。

### 8.1 ASR API

```kotlin
data class AsrModelDescriptor(
    val id: String,
    val name: String,
    val sampleRate: Int = 16_000,
    val languages: Set<String>,
    val supportsStreaming: Boolean = false
)

data class AsrResult(
    val sessionId: Long,
    val resultId: String,
    val text: String,
    val language: String? = null,
    val isFinal: Boolean = true,
    val decodeMs: Long,
    val audioMs: Long
)

interface AsrEngine {
    val descriptor: AsrModelDescriptor
    suspend fun prepare(onProgress: (Float) -> Unit = {})
    suspend fun transcribe(
        sessionId: Long,
        samples: FloatArray,
        sampleRate: Int
    ): AsrResult
    suspend fun release()
}
```

### 8.2 采集 API

```kotlin
data class CaptureOptions(
    val sampleRate: Int = 16_000,
    val maxDurationMs: Long = 30_000,
    val minDurationMs: Long = 300,
    val source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION
)

interface AudioCapture {
    suspend fun start(sessionId: Long, options: CaptureOptions)
    suspend fun stop(sessionId: Long): FloatArray
    suspend fun cancel(sessionId: Long)
}
```

如果设备不支持 16 kHz 直接采集，选择：A）以支持的采样率采集并高质量重采样至 16 kHz；B）明确报 `UNSUPPORTED_AUDIO_FORMAT`；禁止“标称 16 kHz、实际音频为 48 kHz”而不转换。

### 8.3 Controller API

```kotlin
sealed interface DictationCommand {
    data object Start : DictationCommand
    data object Finish : DictationCommand
    data object Cancel : DictationCommand
    data object RetryModel : DictationCommand
    data object AppBackgrounded : DictationCommand
}

enum class DictationPhase {
    PREPARING, READY, STARTING, RECORDING,
    TRANSCRIBING, CANCELLING, ERROR
}

data class DictationUiState(
    val phase: DictationPhase,
    val sessionId: Long? = null,
    val durationMs: Long = 0L,
    val errorCode: DictationErrorCode? = null,
    val statusMessage: String = ""
)

interface DictationController {
    val state: StateFlow<DictationUiState>
    suspend fun dispatch(command: DictationCommand)
}
```

### 8.4 Kotlin JNI 调用点（已在官方 API 确认）

```kotlin
val config = OfflineRecognizerConfig(
    modelConfig = OfflineModelConfig(
        senseVoice = OfflineSenseVoiceModelConfig(
            model = "sensevoice/model.int8.onnx",
            language = "auto",
            useInverseTextNormalization = true
        ),
        tokens = "sensevoice/tokens.txt",
        numThreads = 2,
        provider = "cpu"
    )
)

val recognizer = OfflineRecognizer(
    assetManager = context.assets,
    config = config
)
val stream = recognizer.createStream()
try {
    stream.acceptWaveform(pcmFloatArray, 16_000)
    recognizer.decode(stream)
    val text = recognizer.getResult(stream).text
    // 仅在 session/generation 仍有效时交付 text
} finally {
    stream.release()
}
// 受控关闭时：recognizer.release()
```

要求：`recognizer` 初始化/推理/释放在受控后台上下文执行；Native decode 不一定响应 Kotlin 取消，不能对运行中的 decode 强制释放其 `recognizer`。

---

## 9. 状态机、并发与任务所有权

### 9.1 状态转换表

| 当前状态 | 事件 | 目标状态 | 附加动作 |
|---|---|---|---|
| PREPARING | 模型准备成功 | READY | 建立可用引擎 |
| PREPARING | 模型准备失败 | ERROR | 记录错误码；允许重试 |
| READY | Start | STARTING | sessionId/generation 自增；申请采集 |
| STARTING | 采集成功 | RECORDING | 开始计时；音频数据归当前 session |
| STARTING | Cancel | CANCELLING | 待启动操作收敛后立即关闭 |
| STARTING | Finish（P1） | STARTING + pendingFinish | 启动成功即完成，避免丢事件 |
| RECORDING | Finish 且有效时长≥300ms | TRANSCRIBING | 停止采集，提交 ASR |
| RECORDING | Finish 且不足300ms | READY | 丢弃 PCM，不识别 |
| RECORDING | Cancel / 后台 | CANCELLING | 停止采集，失效 session |
| TRANSCRIBING | 最终结果且 id 有效 | READY | 恰好一次插入文本 |
| TRANSCRIBING | Cancel / 后台 | CANCELLING | 不再交付；安全等待 native 完结 |
| 任意运行状态 | 失败/权限撤销 | ERROR 或 READY | 释放可释放资源；展示操作 |
| CANCELLING | 资源与任务收敛 | READY | 恢复按钮；旧结果无交付权 |

### 9.2 必须保持的不变量

1. **采集互斥**：`activeRecorderCount ≤ 1`。
2. **结果交付唯一**：每个 `resultId` 最多插入一次。
3. **任务代际匹配**：`result.sessionId == activeSession.id` 且 `generation` 未改变，才允许插入。
4. **音频归属**：采集 PCM 不可被另一 session 读取/清除；如复用缓冲区则需要加所有权锁。
5. **Native 生存期**：decode 持有 recognizer 租约；lease 未释放前不得 `recognizer.release()`。
6. **UI 原子性**：插入文本以最新编辑状态为基础，保留 Selection，不能用旧 TextFieldValue 覆写。
7. **后台禁录**：首版 App 进入后台后，必须尽快停录，不能因为模型仍在推理而保持麦克风。
8. **有界资源**：30 秒硬上限；每次会话缓存可预估；并发会话被拒绝或串行化。

### 9.3 重点竞态清单

| ID | 竞态 | 规避策略 | 测试 |
|---|---|---|---|
| CC-01 | 频繁开始/结束导致 2 个 AudioRecord | 将状态变化与采集启动归统一 Controller 串行入口 | 快速点击 20 次 |
| CC-02 | STARTING 时按下又松开 | `pendingFinishAfterStart`，或 P0 禁止 Finish 按钮直至录音成功 | 注入启动延迟 |
| CC-03 | 取消后旧 Native 任务仍返回 | `generation` 与 session 验证，丢弃结果 | 假引擎延迟回调 |
| CC-04 | 新输入修改编辑框，旧结果覆盖 | 只使用最终交付时的编辑状态 | 推理期间输入文字 |
| CC-05 | 切后台与用户点击结束同时发生 | 所有命令单线程串行化，资源清理幂等 | 人工快速复现 |
| CC-06 | Native decode 中立即 release | 引擎 lease / Mutex 持有至 decode 结束 | 注入慢推理与释放 |
| CC-07 | 读音频阻塞，stop 后无法 join | `AudioRecord.stop()` 解阻塞；超时/中断处理；必要时设备隔离 | 模拟设备异常 |
| CC-08 | 蓝牙路由变更改变实际采样率 | 采样参数校验、重采样或失败提示 | 断连/重连 |
| CC-09 | 模型加载尚未成功即尝试录音 | PREPARING 状态禁用 Start | 慢初始化测试 |
| CC-10 | 完成转写回调重复触发 | resultId 去重集合（限定生命周期） | 双回调注入 |

### 9.4 Coroutine 设计要求

- `modelPrepareJob`、`audioReadJob`、`inferenceJob`、`cleanupJob` **分别持有**，而非单个 `work` 引用被覆盖。
- Controller 使用 `Mutex` 或 actor 顺序执行状态变更；不要让录音线程直接修改 UI 状态。
- 对 Native decode 的 Kotlin Job cancel 只意味着不再交付结果；不保证立即中断底层 C++ 运算。
- 引擎和 AudioRecord 必须有清晰的所有者（推荐 app/session scoped coordinator）。
- 使用 `try/finally` 清理 PCM 会话与 `OfflineStream`；cleanup 不得被普通 UI 取消无意中终止。
- 任何资源关闭都应幂等，尤其是 `stop()`/`cancel()`/`onStop()`/异常路径并发发生时。

---

## 10. 麦克风、生命周期与异常处理

### 10.1 PCM 规格

| 属性 | P0 规格 |
|---|---|
| 音频源 | 优先 `VOICE_RECOGNITION`，设备不兼容再测试 `MIC` |
| 采样率 | 16,000 Hz，必要时显式重采样 |
| 声道 | 单声道 |
| 编码 | PCM 16-bit little-endian |
| 模型输入 | `FloatArray`，每样本 `short / 32768f` |
| 音频块 | 100ms：1600 samples；真实 read 可返回少于请求长度 |
| 容量上限 | 30s = 480,000 samples = 960,000 bytes（原始 PCM16）；转 Float32 约 1,920,000 bytes |
| 缓冲策略 | 有界缓存；不允许无限增长；内存首版不落盘 |

### 10.2 采集生命周期

1. 权限确认（来自 Activity Result）；拒绝立即退出，不构造 AudioRecord。
2. 检查 `getMinBufferSize() > 0`；使用不小于系统最小要求的实际缓冲。
3. 构造 AudioRecord，验证 `STATE_INITIALIZED`。
4. `startRecording()`，验证 recordingState，进入 RECORDING。
5. 后台 IO 线程循环 read；处理短读、零读、负值错误与上限。
6. Finish：先停止采集，再等待读循环收敛，复制已录数据，释放 AudioRecord。
7. Cancel/异常：安全停止与释放，PCM 不交付；停止结束后再进入 READY。

### 10.3 生命周期和平台权限

- 只在前台可见场景录音。切后台后取消并停录；不引入 microphone foreground service。
- 请求 `android.permission.RECORD_AUDIO` 运行时权限；不要求 `INTERNET` 权限。
- 权限永久拒绝时显示系统设置路径，不循环弹授权对话框。
- 启动失败时避免假设设备一定支持 `16k/mono/PCM16`；必要时提供受控降级。
- 避免麦克风与其他 App 的录音冲突。收到录音状态错误或音频设备切换，正常终止或提供重试。
- Android 不同版本的后台麦克风限制不一致，P0 避免此能力；P1 若增加前台服务，必须另开平台合规设计与专项测试。

### 10.4 错误码规范

> 本表是错误码全集规范。其中 `AUDIO_TOO_SHORT`、`AUDIO_TOO_LONG`、`ASR_EMPTY_RESULT`、`AUDIO_ROUTE_CHANGED`、`SESSION_CANCELLED` 的恢复动作是轻提示、自动转写或无回填，运行时由 `DictationEvent` 承载、不进入常驻 `ERROR` 状态（DESIGN.md §6），因此不在 `DictationErrorCode` 枚举内；其余错误码进入 `ERROR` 态并向用户展示对应消息。

| 错误码 | 原因 | 用户消息 | 恢复动作 |
|---|---|---|---|
| `MIC_PERMISSION_DENIED` | 未授予录音权限 | 请允许麦克风权限 | 重新授权/去设置 |
| `MIC_PERMISSION_REVOKED` | 使用中撤销 | 麦克风权限已关闭 | 停录并重新授权 |
| `MIC_BUSY` | 录音资源被占用 | 麦克风暂时不可用 | 稍后重试 |
| `AUDIO_INIT_FAILED` | AudioRecord 创建失败 | 无法启动录音 | 重试/设备诊断 |
| `AUDIO_READ_FAILED` | read 返回错误 | 录音中断 | 重试 |
| `AUDIO_ROUTE_CHANGED` | 音频路由改变且不可恢复 | 音频设备已变化 | 重新开始 |
| `MODEL_NOT_FOUND` | 缺模型或词表 | 离线模型不可用 | 检查安装包 |
| `MODEL_CORRUPTED` | 模型完整性不符 | 模型文件损坏 | 恢复已知良好版本 |
| `MODEL_LOAD_FAILED` | Native 加载失败 | 无法加载离线模型 | 重试/回滚 |
| `ASR_EMPTY_RESULT` | 无有效识别文本 | 没有识别出语音 | 重试，文本不变 |
| `ASR_DECODE_FAILED` | JNI 推理异常 | 识别失败 | 重试/诊断 |
| `AUDIO_TOO_SHORT` | 少于 300ms | 录音太短 | 无需错误弹窗 |
| `AUDIO_TOO_LONG` | 达到 30s 上限 | 已达到录音上限 | 自动结束/转写 |
| `SESSION_CANCELLED` | 用户取消/后台取消 | 已取消语音输入 | 无回填 |
| `UNSUPPORTED_AUDIO_FORMAT` | 无可用采样参数 | 设备暂不支持录音格式 | 受控降级/提示 |

---

## 11. 模型管理与离线分发

### 11.1 P0 部署方式

建议在测试 APK/AAB 的 `assets/sensevoice/` 中内置模型，省去下载模块复杂度：

```text
app/src/main/assets/sensevoice/
  model.int8.onnx
  tokens.txt
```

模型归档官方目录名：

`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`

模型文件必须通过官方提供的归档提取；不要将模型文件手动更名为其他格式。

### 11.2 `ModelRepository` 应承担的职责

- `verify(modelId)`：存在性、文件大小与可信哈希校验。
- `prepare(modelId)`：提供可读模型路径/AssetManager，确保同一时刻不重复初始化。
- `status(modelId)`：`NotInstalled / Verifying / Ready / Corrupted / Failed`。
- `describe(modelId)`：模型语言、版本、来源、许可、预期体积、采样率。
- P2 扩展 `install()`：临时下载 + 校验 + fsync/原子重命名 + 旧版本回滚。

### 11.3 模型元数据示例

```json
{
  "id": "sensevoice-small-int8-2024-07-17",
  "engine": "sherpa-onnx",
  "model_file": "sensevoice/model.int8.onnx",
  "tokens_file": "sensevoice/tokens.txt",
  "sample_rate": 16000,
  "languages": ["zh", "en", "ja", "ko", "yue"],
  "sha256_model": "REPLACE_WITH_VERIFIED_HASH",
  "sha256_tokens": "REPLACE_WITH_VERIFIED_HASH",
  "source": "https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html",
  "license_review": "PENDING"
}
```

**禁止**将占位哈希当作有效校验值；M0 需要获取实际 SHA-256 并提交到项目记录中。

---

## 12. 文本格式化与风格处理

### 12.1 P0

- 默认转写模式：`VERBATIM`（尽量保留模型输出；允许 ITN 的标点和规范化）。
- 规范化首尾空白；不进行危险的“自动删除语义词”操作。
- 对输入框周围字符判断英文空格是否必要；不要对 CJK 标点添加多余空格。
- 原文为空时不插入。

### 12.2 P1

```kotlin
enum class DictationStyle {
    VERBATIM, NATURAL, CONCISE, NOTES
}

interface TranscriptProcessor {
    fun process(raw: String, style: DictationStyle): ProcessedTranscript
}

data class ProcessedTranscript(
    val raw: String,
    val formatted: String,
    val transformations: List<String>
)
```

规则原则：语言敏感、可关闭、可测试；不可盲目删除“嗯/啊”等可能影响语义的词；不承诺在没有 LLM 的情况下完成高质量语义润色。

### 12.3 编辑器验收特殊样本

- 光标位于文本开头、末尾、中间。
- 已有选区，识别结果覆盖且光标落在替换后末尾。
- 用户在转写期间继续输入文字，结果不覆盖用户新增内容。
- 前后是中文、英文、中文逗号、英文句号、emoji、括号、换行。
- 空识别结果/重复 resultId/取消结果。

---

## 13. 性能、稳定性与观测

### 13.1 埋点（仅本地调试或在用户授权的匿名诊断下）

| 事件 | 字段 | 禁止项 |
|---|---|---|
| `asr_model_load` | modelId、loadMs、success、errorCode | 不上传模型文件路径包含的个人信息 |
| `dictation_started` | sessionId（匿名瞬时）、timestamp、deviceClass | 不记录录音正文 |
| `dictation_finished` | audioMs、captureError、pcmSamples | 不记录 PCM 数据 |
| `asr_finished` | modelId、decodeMs、rtf、success、errorCode | 不记录识别文本 |
| `dictation_cancelled` | phase、reason、cleanupMs | 不记录文本或用户输入 |

### 13.2 性能测量

- **冷启动模型时间**：进程启动至模型首次 ready。
- **热启动模型时间**：引擎已在内存时，从返回页面到 ready。
- **RTF**：`decode duration / audio duration`，按 3s、5s、10s、20s 样本分别记录。
- **用户可感知时延**：结束录音至输入框实际展示文本；包含 stop、PCM 转换、decode、文本插入。
- **内存**：模型加载前后 PSS/RSS、推理峰值、连续 100 次会话的增长趋势。
- **发热/耗电**：中端与低端机连续交互 10 分钟采样，记录功耗与温升趋势。

### 13.3 设备分层建议

| 组别 | 代表设备类别 | 目标 |
|---|---|---|
| A：中高端 | 近两年 ARM64 旗舰或次旗舰 | 主体验与性能基准 |
| B：中端 | 3–5 年内主流 ARM64，6–8 GB RAM | 发布底线的主要测试对象 |
| C：低端 | 较弱 ARM64，4 GB RAM 左右 | 评估内存、RTF 与可否支持 |
| D：系统差异 | Android 8/10/12/14/15/16 可获得的设备 | 权限、音频路由、生命周期 |

实际厂商/型号、Android Build、芯片、RAM、温度、CPU 调度等均应记录在测试报告中。

---

## 14. 隐私、安全与可信性要求

- P0 **默认不要求网络权限**；模型随包提供或在安装前完成离线部署。可用 adb/代理工具验证识别流程不发网络请求。
- 不存储原始音频、识别正文或用户输入内容；仅应用运行期内存暂存。日志和崩溃报告脱敏。
- 麦克风采集必须与用户可见 UI 状态一致；用户取消后不得保持暗中采集。
- 读取模型仅使用 App 私有 assets/内部存储；禁止任意不可信路径和未经校验的模型包。
- 引入第三方依赖时建立 SBOM、版本固定与许可证归档。
- 不使用 AccessibilityService 模拟键盘；P0 仅修改自己的 Compose 编辑框。
- 处理 Android 系统权限变更、音频焦点和录音资源争用，不越过系统约束。
- 如未来添加语音历史保存、模型远程下载或云端润色，必须单独更新隐私声明与授权设计。

---

## 15. 详细功能验收用例（Given / When / Then）

> 用例编号可直接映射到测试仓库。**所有 AC-P0 必须通过**；没有测试的能力不可标记完成。

### AC-P0-001：首次加载

- **Given** 安装包含完整模型与匹配 Native 库的 App。
- **When** 首次进入语音输入页面。
- **Then** 模型在后台准备；UI 显示 PREPARING；成功后进入 READY；页面无 ANR，错误时有重试入口。

### AC-P0-002：完全离线识别

- **Given** 模型已经准备完毕、设备开启飞行模式且 Wi-Fi 关闭。
- **When** 录入 5 秒普通话并结束。
- **Then** 离线完成转写并插入文本；无网络失败提示、无远程 ASR 请求。

### AC-P0-003：麦克风拒绝授权

- **Given** 应用尚未获得 `RECORD_AUDIO`。
- **When** 点击开始并在权限弹窗中拒绝。
- **Then** 未创建 AudioRecord，状态可恢复，提示权限用途；不会无休止请求。

### AC-P0-004：永久拒绝恢复

- **Given** 用户通过系统设置永久拒绝或关闭权限。
- **When** 再次点击麦克风。
- **Then** 展示清晰设置引导；授权恢复后可重新录音。

### AC-P0-005：正常录音

- **Given** 模型 READY 且有麦克风权限。
- **When** 点击开始、说话 5 秒、点击结束。
- **Then** 状态按 READY→STARTING→RECORDING→TRANSCRIBING→READY 转换，只产生一个结果。

### AC-P0-006：300 ms 误触

- **Given** 录音已成功开始。
- **When** 在 300 ms 内结束。
- **Then** 不调用 ASR，不修改编辑框，录音资源释放。

### AC-P0-007：达到 30 秒上限

- **Given** 正在录音。
- **When** 录音持续达到 30 秒上限。
- **Then** 自动停止并按策略转写；数据缓存有界，不继续读取无限音频。

### AC-P0-008：录音中取消

- **Given** 用户处于 RECORDING。
- **When** 点击取消。
- **Then** 麦克风停止、PCM 丢弃、状态恢复 READY、不插入文字。

### AC-P0-009：推理中取消

- **Given** 用户进入 TRANSCRIBING，Native decode 未返回。
- **When** 点击取消后 Native decode 才返回。
- **Then** 旧结果绝不插入，Native 引擎最终安全清理且可以再次录音。

### AC-P0-010：连续快速点击

- **Given** 页面处于 READY。
- **When** 连续快速触发 Start/Finish/Cancel 20 次。
- **Then** 不产生两个并发 AudioRecord、死锁、崩溃和旧会话回填。

### AC-P0-011：转写过程中继续编辑

- **Given** 用户编辑框已有“开始结束”，光标位于中间。
- **When** 转写期间用户又插入了别的字符，随后结果返回。
- **Then** 基于交付当刻的真实编辑状态插入文本，不覆盖用户已修改部分。

### AC-P0-012：选区替换

- **Given** 编辑框选中一段文字。
- **When** 语音结果成功交付。
- **Then** 选中范围被替换，其他文本不变，光标位置正确。

### AC-P0-013：无有效语音

- **Given** 录音中仅有静音或不可识别音频。
- **When** ASR 输出空字符串。
- **Then** 不更改编辑框，显示非阻塞提示，可继续录音。

### AC-P0-014：切后台

- **Given** 用户正在录音。
- **When** 按 Home 键或切换到其他 App。
- **Then** 会话取消、麦克风停止、不会后台继续采集或回填结果。

### AC-P0-015：通话/设备路由变化

- **Given** 正在录音。
- **When** 收到通话打断或蓝牙路由异常。
- **Then** 不崩溃；采集按错误恢复策略终止；资源最终释放。

### AC-P0-016：模型缺失/损坏

- **Given** 模型或 tokens 被人为移走/替换为无效文件（测试构建）。
- **When** 进入页面或触发初始化。
- **Then** 进入明确的错误状态，不调用空指针 JNI、不进入 RECORDING。

### AC-P0-017：Native 推理异常

- **Given** 使用可注入故障的假引擎返回异常。
- **When** 完成录音并触发识别。
- **Then** 错误可见、音频资源释放、不插入错误内容，可安全重试。

### AC-P0-018：重复结果

- **Given** 同一 `resultId` 的回调被触发两次（测试注入）。
- **When** 两次结果都到达交付层。
- **Then** 只插入一次。

### AC-P0-019：连续稳定性

- **Given** 设备已完成模型初始化。
- **When** 连续执行 100 次 3–10 秒录音/识别/取消组合。
- **Then** 无 JNI 崩溃、不可恢复录音错误、持续增长的音频资源泄漏或重复回填。

### AC-P0-020：中英文样本评价

- **Given** 有标注真值的中文/英文/混合语言音频集。
- **When** 对至少每类 30 条样本完成推理（初始测试集建议，后续扩大）。
- **Then** 输出 CER/WER、逐类别错误分析、RTF/P50/P95，不以“用户感觉不错”代替客观结论。

### AC-P0-021：用户正常编辑不受影响

- **Given** ASR 正在 PREPARING 或 TRANSCRIBING。
- **When** 用户直接手动输入、删除、选中和移动光标。
- **Then** Compose 编辑器正常响应，不因 ASR UI 状态被禁用或回滚。

### AC-P0-022：机型不支持录音格式

- **Given** 测试设备/模拟器不支持所请求采样格式。
- **When** 开始录音。
- **Then** 走明确的重采样/降级或错误提示，不向 ASR 输入错误采样率数据。

### AC-P0-023：引擎释放时存在未完成 decode

- **Given** Native decode 尚未返回。
- **When** 页面销毁或请求引擎关闭。
- **Then** 引擎关闭等待 decode 安全结束，不出现 use-after-free、JNI 崩溃或重复释放。

### AC-P0-024：彻底断网后的冷启动

- **Given** 用户从未联网打开过 App，但模型已随安装包包含。
- **When** 首次冷启动、授予麦克风权限并录音。
- **Then** 识别完成，无在线模型下载或激活依赖。

---

## 16. 测试计划与发布门槛

### 16.1 自动化测试分层

| 层级 | 测试类 | 核心覆盖 |
|---|---|---|
| JVM Unit | `DictationStateMachineTest` | 合法/非法状态转移、超时 |
| JVM Unit | `SessionGenerationGuardTest` | 取消后旧回调、session 覆盖、去重 |
| JVM Unit | `TextInsertionPolicyTest` | 光标/选区/Unicode/编辑竞争 |
| JVM Unit | `TranscriptProcessorTest` | 标点、空白、风格、纯原文不变 |
| JVM Unit | `FakeAsrEngineTest` | 慢初始化、抛异常、迟到结果 |
| Instrumentation | `SherpaModelSmokeTest` | JNI 可加载、固定音频转写、release |
| Instrumentation | `AudioRecordLifecycleTest` | 权限、录音停止、异常中断 |
| UI Automation | `DictationScreenTest` | 按钮状态、错误提示、可访问性 |
| 设备长稳 | `LongRunDictationStressTest` | 100 次会话、内存趋势、音频资源 |

### 16.2 设备与音频样本矩阵

至少覆盖三个档次 arm64 实体设备。每台设备记录 Android 版本、芯片、RAM、系统语言、AudioRecord 实际配置、JNI 版本；每档录制/导入中文、英文、中英混合、安静、背景噪声、近讲、远讲样本。固定音频推理与现场采集分别测评，以区分模型问题与麦克风问题。

### 16.3 Release Gate（P0）

必须全部满足才能给出“可内测”结论：

- [ ] G01 目标机型 100% 通过固定音频转写冒烟测试。
- [ ] G02 AC-P0-001 ～ AC-P0-024 中所有硬门槛通过；不通过项有阻塞级别与明确处理结论。
- [ ] G03 100 次连续会话无 JNI 崩溃、音频资源长占用、丢失取消保护。
- [ ] G04 飞行模式从冷启动到语音输入成功。
- [ ] G05 权限拒绝、切后台、通话/蓝牙打断等路径不造成 App 无法恢复。
- [ ] G06 中端设备 RTF、P95 交付延迟、峰值内存、准确率报告已产出；未满足目标必须有降级/决策记录。
- [ ] G07 模型与所有第三方 Native 组件许可证、版本、哈希、Notice 经过确认。
- [ ] G08 Debug/Release 构建都在相应 ABI 实测；混淆与打包方式不破坏 JNI 桥接。
- [ ] G09 核心崩溃/错误诊断不含音频及识别原文。
- [ ] G10 已记录已知设备兼容性限制和用户可理解的错误提示。

---

## 17. 里程碑与可执行工作拆分

### M0：依赖与工程基线（必须先完成）

| 任务 | 产物 | 依赖 | 验收 |
|---|---|---|---|
| T0-01 锁定 sherpa-onnx Git tag/commit | `third_party_versions.md` | 无 | JNI/Kotlin 版本一致 |
| T0-02 获取 SenseVoice INT8 模型及 tokens | 受控模型资产 | T0-01 | 官方音频测试识别成功 |
| T0-03 License/SBOM 审查 | `third_party_licenses.md` | T0-01/02 | 合规负责人认可 |
| T0-04 建立最简 Compose Android 工程 | Debug/Release 构建 | 无 | Android 真机安装启动 |
| T0-05 固定 `arm64-v8a` 依赖装配 | JNI smoke test | T0-01/04 | 无 `UnsatisfiedLinkError` |

**M0 Gate**：最简项目能在目标真机加载 JNI、完整校验模型，并完成官方固定样本转写。

### M1：纯引擎可用性验证

| 任务 | 产物 | 验收 |
|---|---|---|
| T1-01 `AsrEngine`/`AsrResult`/错误码 | `asr:api` | 假引擎单测通过 |
| T1-02 `SherpaSenseVoiceEngine` | `asr:sherpa` | 固定中/英文 PCM 识别成功 |
| T1-03 初始化/互斥/释放 | 引擎生命周期 | 重复创建/关闭无 JNI 崩溃 |
| T1-04 性能基线 | `benchmarks/m1.csv` | 记录 load/RTF/PSS/P95 |
| T1-05 初始语料集 | `testdata/README.md` + 音频真值（访问受控） | 中文/英文/混合至少各 30 条建议 |

**M1 Gate**：实际设备已测得性能与正确性；确认继续使用 SenseVoice 还是启动备选模型对比。

### M2：App 内输入 MVP

| 任务 | 产物 | 验收 |
|---|---|---|
| T2-01 `AudioCapture` + `PcmAudioRecorder` | 音频采集实现 | 16k PCM、30s 有界、短读/中断 |
| T2-02 Controller 状态机 | `DictationController` | 状态转移单测全部通过 |
| T2-03 并发 Session 守卫 | GenerationGuard / dedupe | 取消、重复提交均安全 |
| T2-04 ViewModel + Compose 编辑器 | 可用 Demo 页面 | 光标插入/选区替换 |
| T2-05 权限与生命周期处理 | 体验闭环 | 拒绝授权、切后台可恢复 |
| T2-06 真实设备 E2E | Demo APK + 测试记录 | AC-P0 冒烟测试通过 |

**M2 Gate**：飞行模式下完整录音→转写→插入文本闭环，旧结果不可误交付。

### M3：Hex 交互增强（P1）

| 任务 | 产物 | 验收 |
|---|---|---|
| T3-01 按住/松开/取消手势 | `VoiceInputButton` | pendingFinish 等竞态测试 |
| T3-02 录音计时、音量波形 | `RecordingSurface` | 30fps 内流畅、不阻塞采集 |
| T3-03 Silero VAD 和分段合并 | `VoiceSegmenter` | 截断率、长句正确率专项测试 |
| T3-04 文本风格处理 | `TranscriptProcessor` | 语言敏感规则单测 |
| T3-05 性能回归 | P1 对比报告 | VAD/波形不显著影响 RTF |

### M4：稳定性、内测准备

| 任务 | 产物 | 验收 |
|---|---|---|
| T4-01 设备矩阵测试 | `compatibility_report.md` | A/B/C/D 机型测试记录 |
| T4-02 稳定性测试 | `stress_report.md` | 100 次连续会话 |
| T4-03 性能与准确率分析 | `asr_benchmark_report.md` | CER/WER、RTF/P95、内存 |
| T4-04 安全/隐私/许可证复核 | `release_checklist.md` | G01–G10 完整检查 |
| T4-05 内测准入审查 | `release_decision.md` | 明确 Go / Conditional Go / No-Go |

### 17.1 任务依赖链

`M0 Native/模型装配 → M1 引擎与性能基线 → M2 录音与业务闭环 → M4 P0 内测准入`。

`M3` 是 P1 体验增强分支；如果 P0 的稳定性尚未达到 Release Gate，**不应**因为想做波形、VAD 或按住说话而推迟修复基础竞态。

---

## 18. 风险登记与应对策略

| 风险 ID | 风险 | 概率/影响 | 预防/应对 | Go/No-Go |
|---|---|---|---|---|
| R-01 | 低端机 CPU RTF 偏高 | 中/高 | M1 真机 benchmark；降级模型、减少线程竞争 | 体验门槛 |
| R-02 | 模型/库造成 APK 体积过大 | 高/中 | AAB ABI split、P2 下载包、评估小模型 | 商店分发 |
| R-03 | JNI/Kotlin 文件版本不一致 | 中/高 | 锁 tag、CI smoke、匹配 SO | 硬阻塞 |
| R-04 | 取消 Native decode 时 use-after-free | 中/极高 | 引擎租约、Mutex、状态收敛；仪器测试 | 硬阻塞 |
| R-05 | AudioRecord 在特定厂商上阻塞/报错 | 中/高 | 超时、设备矩阵、录音层清理策略 | 硬阻塞 |
| R-06 | 中英混说错误率高 | 高/中 | 标注数据集；专业词表/模型比较 | 需明确限制 |
| R-07 | 默认标点对特定语言效果差 | 中/低 | ITN 开关 + 格式化规则 | 可降级 |
| R-08 | 用户切后台后残留麦克风 | 中/极高 | 前台限制、生命周期强制停录 | 硬阻塞 |
| R-09 | 模型许可证限制分发 | 未知/高 | M0 独立审查原始模型及转换权重许可 | 硬阻塞 |
| R-10 | UI 编辑竞争导致内容丢失 | 中/极高 | 交付时最新编辑状态；自动化测试 | 硬阻塞 |
| R-11 | 错误日志意外记录敏感音频文本 | 中/高 | 数据字段白名单、日志检查 | 硬阻塞 |
| R-12 | P1 分段 VAD 截断语音 | 中/中 | 预缓冲/尾缓冲、短句数据集测试 | P1 阻塞 |

---

## 19. 开发执行规范与提交要求

1. 每个任务应包含：Issue ID、负责人、验收用例、对应测试、结果截图或日志（脱敏）。
2. 进入 M2 之前必须固定模型/ABI/版本，不允许在同一 PR 中同时升级 JNI 和重写状态机。
3. 禁止在 `@Composable` 作用域直接创建/释放 `OfflineRecognizer` 或 `AudioRecord`。
4. 单次会话以 `sessionId`、`resultId` 追踪，采用幂等 stop/cancel；所有可观察状态转换有测试。
5. JVM 逻辑单测不得依赖真机 Native，注入 FakeAsrEngine/FakeAudioCapture。
6. 真机 Instrumentation 单测使用固定音频样本，并记录设备信息。
7. 发布报告必须注明未达到的初始目标与潜在设备限制；不能把示例 Demo 的成功当成发布可靠性证据。

### 19.1 定义完成（DoD）模板

```markdown
- [ ] 需求 / 接口 / 错误处理已实现
- [ ] 主流程与异常流程单测已添加
- [ ] 至少 1 台 arm64 真机集成验证
- [ ] 不阻塞 UI 线程
- [ ] 无音频 / 正文敏感日志
- [ ] 明确释放 Native/AudioRecord 资源
- [ ] 对应 AC 用例有通过记录
- [ ] PR 描述包含性能或兼容性影响
```

---

## 20. 开放问题与待决策清单

| 决策 ID | 问题 | 本 PRD 暂定答案 | 决策时机 |
|---|---|---|---|
| D-01 | 只做中文还是中英双语？ | 双语；混说列为重点验证 | M1 |
| D-02 | P0 模型随包还是首次下载？ | 随包，严格离线 | M0 |
| D-03 | `minSdk` 具体值？ | API 26 工程建议 | 工程基线确认 |
| D-04 | 首发 ABI？ | arm64-v8a | M0 |
| D-05 | 原始文本是否允许永久存储？ | 不允许，运行期即可 | 产品决策 |
| D-06 | 文本插入锚点？ | 完成时当前光标/选区 | M2 |
| D-07 | 取消推理是否允许后台安全清理？ | 允许必要的 native cleanup，但不再录音/交付 | M2 |
| D-08 | 性能不达标如何处理？ | 测试后选择更小模型/降低体验门槛/限定设备 | M1 |
| D-09 | 是否优先做系统 IME？ | 明确不做 | P0 |
| D-10 | 模型是否有合法再分发权？ | 待许可证验证，不假定 | M0 |

---

## 21. 技术参考与源码索引

**Hex 参考实现**

- S1 Hex iOS README（模块说明、录音与模型、键盘扩展）：https://github.com/anomalyco/hex/blob/dev/ios/README.md
- S2 Hex iOS DictationController（generation、模型初始化、任务与中断）：https://github.com/anomalyco/hex/blob/dev/ios/HEXMobile/DictationController.swift
- S2a Hex 语音格式化：https://github.com/anomalyco/hex/blob/dev/ios/HEXShared/TranscriptFormatter.swift
- S2b Hex Parakeet 后端：https://github.com/anomalyco/hex/blob/dev/ios/HEXMobile/ParakeetTranscriber.swift
- S2c Hex Apple Speech 后端：https://github.com/anomalyco/hex/blob/dev/ios/HEXMobile/AppleSpeechTranscriber.swift

**sherpa-onnx 与模型**

- S3 SenseVoice 官方预训练模型、包名、权重大小、语言与 ITN：https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html
- S4 Kotlin OfflineRecognizer 源码：https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OfflineRecognizer.kt
- S5 Kotlin OfflineStream 源码：https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OfflineStream.kt
- S6 Android 模拟流式 ASR 示例：https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxSimulateStreamingAsr
- S7 Android 构建说明：https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html
- S8 sherpa-onnx 主仓库：https://github.com/k2-fsa/sherpa-onnx

**Android 平台**

- S9 AudioRecord API：https://developer.android.com/reference/android/media/AudioRecord
- S10 请求运行时权限：https://developer.android.com/training/permissions/requesting
- S11 使用麦克风前台服务/后台限制：https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

> 链接供开发时核查。`master`/`dev` 是可变分支，**发布实施时应将来源映射到审查过的固定 commit/tag**。文档中的接口和模型路径需要对齐同一固定版本，不能直接把当前分支内容当成永远不变的 API 契约。

---

## 22. 下一步建议

1. 先以 **M0/M1** 建立最小独立 Android Demo，不提前投入 P1 界面与波形；提供硬件上识别成功的证据和性能数据。
2. 将 **AC-P0-001～024** 与错误码直接导入 Issue/测试计划，优先实现会话竞态和资源清理。
3. 验证结果达标后进入 M2 集成现有 Compose 输入页面；通过 Release Gate 后决定是否启动 P1 的 Hex 风格交互。

**最终交付判据**：不是“可以按按钮录音”，而是“在指定 Android 真机上，完全断网、可重复、不会丢失用户输入、取消和中断不会造成旧结果回填或麦克风泄漏的可复用端侧 ASR 能力”。
