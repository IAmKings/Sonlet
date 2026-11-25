# Sonlet 第三方许可证清单

> PRD §6.4 / T0-03:分发前逐项核验;不得以主仓库许可证代替模型权重许可证。
> 更新:2026-11-04,许可证原文已归档至 `docs/licenses/`;**正式对外分发前仍需合规负责人对下表签字确认**。

## 组件清单(许可证原文已归档)

| 组件 | 版本 | 许可证 | 原文归档 | 再分发要点 | 复核状态 |
|---|---|---|---|---|---|
| sherpa-onnx(Kotlin API + JNI `.so`) | v2.0.1 | Apache-2.0 | `docs/licenses/sherpa-onnx-LICENSE` | 保留 LICENSE/NOTICE 与版权声明;注明修改 | 原文已核,签字 PENDING |
| ONNX Runtime(`libonnxruntime.so`) | 随 sherpa-onnx v2.0.1 | MIT | `docs/licenses/onnxruntime-LICENSE` | 保留版权与许可声明 | 原文已核,签字 PENDING |
| SenseVoice Small INT8 模型权重 | 2024-07-17 | **Apache-2.0**(模型页 `iic/SenseVoiceSmall`) | `docs/licenses/sensevoice-weights-LICENSE`(权重);`docs/licenses/funasr-LICENSE`(FunASR 代码,未随包分发) | Apache-2.0 允许商用与再分发,保留声明与 NOTICE | 原文已核,签字 PENDING |
| SenseVoice `tokens.txt` | 同上 | 同模型归档 | 同上 | 同上 | PENDING |
| Silero VAD 模型(v4) | asr-models 归档 | MIT | `docs/licenses/silero-vad-LICENSE` | 保留版权与许可声明 | 原文已核,签字 PENDING |
| 官方测试样本 `zh.wav` / `en.wav` | 同模型归档 | 同模型归档 | — | 仅测试构建使用,不随发布产物分发 | PENDING |

## AndroidX / Kotlin(经 Maven 解析)

当前版本见 `gradle/libs.versions.toml`。候选 APK 还包含 AndroidX Graphics Path 1.0.1 的原生库。运行时传递依赖以 `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 的解析结果为准，连同内置模型和原生库纳入 SBOM 复核。

## 初步合规结论(供法务复核,非最终结论)

1. 四项核心组件均为宽松许可证(MIT / Apache-2.0),均**允许商用与再分发**,前提是保留版权与许可声明。
2. 分发动作要求:发布产物附带第三方声明文件(汇总上表),App"关于"页或随包 NOTICE 引用。
3. 已核查项(2026-11-25):
   - **模型权重的许可不是 MIT**:`modelscope.cn/api/v1/models/iic/SenseVoiceSmall` 的 `License` 字段为 `Apache License 2.0`;FunASR 仓库的 MIT 只覆盖代码,不能代表权重。两者同为宽松许可,义务差异在 Apache-2.0 需要附带 NOTICE。归档的 `funasr-LICENSE` 应标注为「代码许可」,权重许可指向模型页。
   - HuggingFace 镜像 `k2-fsa/sherpa-onnx-sense-voice-...-int8-2024-07-17` 无 model card 许可声明,故不作为许可依据,只作为分发来源。
   - sherpa-onnx 内置的 ONNX Runtime 为多执行提供者构建(strings 可见 `NnapiExecutionProvider`、`QnnExecutionProvider`、`XnnpackExecutionProvider`、`CoreMLExecutionProvider`),但 APK 未打包 Qualcomm `libQnn*.so`,运行期固定 `provider = cpu`,因此分发物不含 QNN/NNAPI 专有二进制。
4. 待确认残留项(仅一条,非许可义务):
   - Silero v4 归档 README 请求在学术工作中引用原作者;这是引用惯例,不构成再分发条件,建议在 README 遵守。

## 待办(T0-03 收口)

- [x] 归档四项组件 LICENSE 原文至 `docs/licenses/`
- [ ] 法务/合规负责人签字确认上表
- [x] 候选 APK 已打包 `assets/NOTICE.txt` 和归档许可原文，随分发签字复核完整性
- [ ] 更新 `models/manifest.json` 的 `license_review` 字段为 CONFIRMED
