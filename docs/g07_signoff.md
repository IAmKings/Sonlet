# G07 分发合规签字表

> 本表由工程侧准备，用于一次性完成 PRD G07（模型与第三方 Native 组件的许可证、版本、哈希、Notice 确认）。
> **签字须由合规负责人完成**；工程侧只提供材料与自检结论，不代替签字。

## 1. 签字范围

| 项 | 值 |
|---|---|
| 产品 | Sonlet（Android，端侧离线听写） |
| 拟分发版本 | v0.1.4（versionCode 104）及之后的同一签名链版本 |
| 分发形态 | GitHub Release 附件 APK，无应用商店、无后端服务 |
| 包含的第三方内容 | 4 个组件 + 模型权重，见下表 |
| 不包含 | FunASR 源代码、Qualcomm/NNAPI 专有库、任何联网 SDK |

## 2. 组件与义务

| 组件 | 版本 | 许可证 | 随包文件 | 义务 |
|---|---|---|---|---|
| sherpa-onnx（Kotlin API + JNI） | v2.0.1 | Apache-2.0 | `assets/sherpa-onnx-LICENSE` | 保留 LICENSE/NOTICE 与版权声明；标注修改 |
| ONNX Runtime（`libonnxruntime.so`） | 随 sherpa-onnx v2.0.1 | MIT | `assets/onnxruntime-LICENSE` | 保留版权与许可声明 |
| SenseVoice Small INT8 权重 + 词表 | 2024-07-17 | **Apache-2.0** | `assets/sensevoice-weights-LICENSE` | 保留声明与 NOTICE |
| Silero VAD v4 | 归档版本 | MIT | `assets/silero-vad-LICENSE` | 保留版权与许可声明 |
| AndroidX Graphics Path | 1.0.1 | Apache-2.0 | `META-INF/.../LICENSE.txt` | 由 AAR 自带 |

**权重许可的更正**：早期记录写作「MIT（经 FunASR 仓库）」，是把代码许可当成了权重许可。上游模型页 `iic/SenseVoiceSmall` 标注 Apache License 2.0，故按 Apache-2.0 处理并随包附带许可原文。FunASR 代码未随包分发，仅作为溯源记录保留。

## 3. 工程侧自检（已完成）

- [x] 四项组件许可原文归档至 `docs/licenses/`
- [x] APK 内确认包含 `assets/NOTICE.txt` 与四份许可原文（`unzip -l` 核对）
- [x] 包内无 `android.permission.INTERNET`，运行期不联网
- [x] 包内无 Qualcomm `libQnn*.so` 等专有二进制；ORT 固定 `provider = cpu`
- [x] 模型与词表 SHA-256 固定于 `models/manifest.json`，构建时校验
- [x] `targetSdk = 35`、`minSdk = 26`、仅 `arm64-v8a`

## 4. 已知限制（供签字时评估）

1. 语料与准确率数据来自 TTS 合成语音，非真人分布；已在 `docs/benchmarks/` 写明局限。
2. Silero VAD 归档 README 请求在学术工作中引用原作者——属引用惯例，非再分发条件。
3. 模型权重来自 ModelScope 与 sherpa-onnx 镜像；镜像本身无 model card 许可声明，许可依据取上游模型页。

## 5. 签字

| 字段 | 内容 |
|---|---|
| 结论 | ☐ 同意分发　☐ 有条件同意（附条件：__________）　☐ 不同意 |
| 合规负责人 | __________ |
| 日期 | __________ |
| 备注 | __________ |

签字后请同步：
1. `models/manifest.json` 的 `license_review` 改为 `CONFIRMED(YYYY-MM-DD)`；
2. `docs/quality_remediation_plan.md` 第 4-4 项改判「完成」。
