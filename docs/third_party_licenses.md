# Sonlet 第三方许可证清单

> PRD §6.4 / T0-03:分发前逐项核验;不得以主仓库许可证代替模型权重许可证。
> 状态:骨架已建立,**法务/合规负责人签字前均为 PENDING**。

## 组件清单

| 组件 | 版本 | 许可证(初步) | 来源 | 复核状态 |
|---|---|---|---|---|
| sherpa-onnx(Kotlin API + JNI `.so`) | v2.0.1 | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx | PENDING(仓库 LICENSE 待归档) |
| ONNX Runtime(`libonnxruntime.so`) | 随 sherpa-onnx v2.0.1 构建 | MIT | https://github.com/microsoft/onnxruntime | PENDING(以 sherpa-onnx 构建 tag 对应版本为准) |
| SenseVoice Small INT8 模型权重(`model.int8.onnx`) | 2024-07-17 | 见模型归档内 `LICENSE`:Ref https://github.com/modelscope/FunASR | 归档内 LICENSE / FunASR 仓库 | PENDING(需确认再分发与商用条款) |
| SenseVoice `tokens.txt` | 同上 | 同模型归档 | 同上 | PENDING |
| 官方测试样本 `zh.wav` / `en.wav` | 同归档 | 同模型归档 | 同上 | PENDING(仅测试构建使用) |

## AndroidX / Kotlin(经 Maven 解析)

AGP 8.7.3、Kotlin 2.0.21、AndroidX(core/lifecycle/activity/compose BOM 2024.12.01)、kotlinx-coroutines 1.9.0 均为 Apache-2.0;精确传递依赖以 `./gradlew :app:dependencies` 输出建立 SBOM 后归档。

## 待办(T0-03 验收项)

- [ ] 归档 sherpa-onnx v2.0.1 仓库 `LICENSE`/`NOTICE` 文本至 `docs/licenses/`
- [ ] 归档 ONNX Runtime 对应版本 LICENSE 与第三方声明
- [ ] 核验 FunASR/SenseVoice 模型许可是否允许:随 APK 再分发、商业使用、修改
- [ ] 归档模型原始来源(FunAudioLLM/SenseVoice)许可说明
- [ ] 合规负责人签字,更新 `models/manifest.json` 的 `license_review` 字段

> 在上述复核完成前,本工程产物仅限内部开发验证,不得对外分发(PRD R-09)。
