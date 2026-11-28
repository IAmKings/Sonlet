# Sonlet

**说完就落字。全部离线。**

Sonlet 是一个 Android 端侧的语音输入工具：按一下（或按住）说话，文字直接落在光标处。
不需要切换输入法，不依赖网络，音频不出设备。

一句 30 秒的话，从松手到文字出现，通常在一秒以内——因为识别就发生在你手机上。

[下载 APK](https://github.com/IAmKings/Sonlet/releases/latest) · [设计规范](DESIGN.md) · [产品需求](docs/Hex_Android_Offline_ASR_PRD_v1.0.md) · [开发与发布](docs/development.md)

---

## 为什么不是又一个语音输入法

系统输入法的语音功能要把音频送到云端。网络不好时转圈，隐私敏感时不敢用，飞行模式下直接不可用。

Sonlet 把整条链路放在端侧：

| | 常见云输入法 | Sonlet |
|---|---|---|
| 音频去向 | 上传服务器 | 只在内存里，转写完即丢 |
| 断网 | 不可用 | 完全可用 |
| 首字延迟 | 受网络抖动 | RTF 0.058，30 秒音频约 1.7 秒纯计算 |
| 你的口音数据 | 进入厂商语料 | 不离开设备 |

唯一会联网的地方是「检查更新」——访问 GitHub Releases，可关闭（见[应用内更新](#应用内更新)）。

## 能做什么

- **两种录音方式**：点击切换，或按住说话、松手即转写；上滑可取消。
- **四种文本风格**：原文照录、自然表达、精简、要点笔记。同一段话，四种产出。
- **自动切句**：先由 VAD 找出真正有语音的片段，静音部分不送进模型，长句自动分段。
- **光标处插入**：结果落在光标位置，替换选区；中英混排自动补空格，不会粘成 `你好World`。
- **不留痕迹地退出**：转写结果只写进编辑框；草稿保存在应用私有存储，杀进程后可恢复，清空即删除。
- **无障碍**：TalkBack 可读、外接键盘空格/回车可完成开始与结束。

## 它是怎么工作的

```
  16 kHz / PCM16 麦克风输入
        │  AudioRecord，30 秒单调时钟硬上限
        ▼
  Silero VAD 离线分段
        │  512 样本为一窗（≈32 ms）：阈值 0.5、静音 0.6 s 断句、
        │  最短语音 0.25 s、单段上限 10 s；静音段直接丢弃
        ▼
  SenseVoice-Small INT8（239 MB，sherpa-onnx，CPU）
        │  zh / en / ja / ko / yue，自带标点与逆文本规整
        ▼
  文本后处理
        │  去口头语、折叠三连重复、按句末标点切行、中英混排空格
        ▼
  插入策略
           按「交付当刻」的光标与选区计算，代理对边界吸附，句界补空格
```

三个值得说明的设计：

**先分段，再识别。** 30 秒音频要跑约 937 次 VAD 窗口推理，但换来的是静音不进入模型——长段静音不会拖慢识别，也不会让模型在空白处「幻听」出标点。

**一次录音只交付一次。** 会话代际 ID 贯穿采集、识别、插入三层：取消、切后台、重复收尾都会让排队中的结果作废。这条不变量比任何单点优化都更难写对，也更值得写对。

**跳过不必要的哈希。** 模型 239 MB，完整 SHA-256 约 220 ms。校验结果以「包更新时间 + 模型摘要」为 key 缓存，只有安装或升级后才重算。

## 性能与准确率

在 Redmi 23078PND5G（天玑 9200+）实测，语料为 90 条合成语音（每条平均 2.52 秒）：

| 指标 | 中文 | 英文 | 中英混合 |
|---|---|---|---|
| 字/词错误率 | 1.66% | 2.08% | 2.74% |
| RTF（p50 / p95） | 0.058 / 0.074 | 0.066 / 0.088 | 0.058 / 0.082 |
| 引擎耗时（p50 / p95） | 159 / 259 ms | 135 / 180 ms | 164 / 235 ms |

RTF 0.058 意味着处理 1 秒音频平均花 0.058 秒。回归门槛设在 10% / 10% / 12%，超过即 CI 失败。

**这两行数字要打个折**：语料是 TTS 合成的，不是真人录音。它能守住「没有明显退化」，但代表不了嘈杂环境、口音和远场。真人语料仍在收集。

## 代码结构

```
app/src/main/java/com/sonlet/app/
  asr/            引擎抽象 + sherpa-onnx 实现
  audio/          采集、VAD 分段、音频路由中断
  dictation/core/ 听写状态机、会话守卫、文本后处理、插入策略、草稿
  feature/        Compose 编辑页、权限、更新弹窗
  ui/theme/       设计系统映射
```

引擎契约（`AsrEngine`）规定：native decode 同一时刻只允许一个，调用方取消不代表解码中断，释放必须等在途解码结束。听写状态机把「采集 / 转写 / 取消 / 切后台」的竞争收敛成一条命令队列，界面只订阅状态。

### 从按下到落字

```
按下 / 松手
     │  Start · Finish · Cancel · AppBackgrounded · AudioRouteInterrupted
     ▼
dictation/core  DictationController（单命令队列，串行处理）
     │  beginSession() 分配代际 id 并打开交付窗口
     │  STARTING → RECORDING → TRANSCRIBING → READY
     ▼
audio/capture   16 kHz PCM16，30 s 单调时钟看门狗
     │  到点置 stopRequested 并停 AudioRecord，解除阻塞读
     ▼
audio/vad       Silero：512 样本一窗，静音段丢弃，单段 ≤ 10 s
     ▼
asr/sherpa      SenseVoice INT8，Mutex 串行 decode（后台线程）
     │  多段结果 joinTranscripts 合并
     ▼
dictation/core  交付（通道携带 sessionId + resultId）
     │  confirmDelivered 关窗 → TextInsertionPolicy 插入 → 草稿落盘
     ▼
编辑器
```

这条链上有三个不变量，代码里比注释更严格地守着它们：

**① 一次会话只交付一次。** `markDelivered` 负责「已发射」去重，`confirmDelivered` 负责「已消费」关窗；取消、切后台、进入错误态都会 `revoke`，让还在通道里排队的结果作废。所以不会出现「取消了，两秒后文字又冒出来」。

**② 迟到的清理不留痕。** 采集线程阻塞在 `AudioRecord.read()` 时，2 秒预算只够发出停机请求；超预算的那次清理会被 `releasePending` 记着，读循环真正退出后才收尾，且不会写新会话的状态。新录音要么等它退出，要么明确拒绝（`RECORDER_BUSY`）。

**③ 插入按「交付当刻」计算。** 不用开始录音时的快照，而是交付瞬间的光标与选区；代理对中间的光标会被吸附到字符边界，句末补空格只在需要时发生。

完整的状态迁移（命令 × 阶段）与各层契约见[开发与发布](docs/development.md#听写状态机)。

## 安装

从 [Releases](https://github.com/IAmKings/Sonlet/releases/latest) 下载最新的 `Sonlet-<版本>-arm64-v8a.apk`：

- Android 8.0（API 26）及以上，**arm64-v8a**（当前不包含其他 ABI）
- 首次启动做一次模型校验，随后直接进入待录状态
- 升级沿用同一签名证书，覆盖安装即可，草稿与设置保留

首次运行需要授予麦克风权限；拒绝后会停在权限说明页，可从系统设置重新授权。

## 应用内更新

0.1.7 起接入 [ReleaseKite](https://github.com/IAmKings/ReleaseKite)：进入待录状态时检查一次（6 小时内最多一次），也可点页头「检查更新」手动触发。

- 网络权限只有 `INTERNET`，且只访问 `api.github.com` / `github.com`
- 仅走 stable 通道，校验 GitHub 资产摘要后才安装，不强制更新
- 断网时静默失败，不影响听写

## 已知限制

- 仅 arm64-v8a；x86 模拟器只能验证构建，跑不了 JNI。
- 单次录音上限 30 秒；超过部分不再采集，不是「截断一段长音频」。
- 中英混说的真实准确率尚未量化，现有数字来自合成语料。
- 通话/蓝牙切换的音频焦点协作未做，依赖采集读取失败兜底。
- 模型权重 Apache-2.0（ModelScope），sherpa-onnx 与 Silero 许可见 [第三方许可证清单](docs/third_party_licenses.md)。

## 文档

**产品与设计**：[设计系统与交互规范](DESIGN.md) · [产品需求与技术方案](docs/Hex_Android_Offline_ASR_PRD_v1.0.md) · [真人验收指南](docs/acceptance_guide.md)

**质量与验收**：[准确率评测](docs/benchmarks/asr_accuracy_2026-11-22.md) · [真机矩阵](docs/device_matrix.md) · [发布门检查清单](docs/release_checklist.md) · [质量整改计划](docs/quality_remediation_plan.md)

**开发与合规**：[开发与发布](docs/development.md) · [第三方版本锁定](docs/third_party_versions.md) · [第三方许可证](docs/third_party_licenses.md) · [依赖安全定性](docs/dependency_security.md) · [G07 签字表](docs/g07_signoff.md)

## 许可

随包分发的第三方组件均为宽松许可，已在发布前逐项核验并签字（2026-11-25）：

| 组件 | 许可证 |
|---|---|
| SenseVoice Small INT8 权重 | Apache-2.0（ModelScope 模型页） |
| sherpa-onnx v2.0.1（Kotlin API + JNI） | Apache-2.0 |
| ONNX Runtime（随 sherpa-onnx 分发） | MIT |
| Silero VAD v4 | MIT |

完整原文与再分发义务见 [第三方许可证清单](docs/third_party_licenses.md) 与包内 [NOTICE](docs/licenses/NOTICE.txt)。

应用自身的源代码以 [MIT](LICENSE) 许可发布。

> 注意：`LICENSE` 只覆盖本仓库的代码，不覆盖随包分发的模型权重与 native 库——它们各自的许可见上表，且不可由 MIT 取代。
