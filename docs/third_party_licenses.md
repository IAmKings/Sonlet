# Sonlet 第三方许可证清单

> PRD §6.4 / T0-03:分发前逐项核验;不得以主仓库许可证代替模型权重许可证。
> 更新:2026-11-04,许可证原文已归档至 `docs/licenses/`;**正式对外分发前仍需合规负责人对下表签字确认**。

## 组件清单(许可证原文已归档)

| 组件 | 版本 | 许可证 | 原文归档 | 再分发要点 | 复核状态 |
|---|---|---|---|---|---|
| sherpa-onnx(Kotlin API + JNI `.so`) | v2.0.1 | Apache-2.0 | `docs/licenses/sherpa-onnx-LICENSE` | 保留 LICENSE/NOTICE 与版权声明;注明修改 | 原文已核,签字 PENDING |
| ONNX Runtime(`libonnxruntime.so`) | 随 sherpa-onnx v2.0.1 | MIT | `docs/licenses/onnxruntime-LICENSE` | 保留版权与许可声明 | 原文已核,签字 PENDING |
| SenseVoice Small INT8 模型权重 | 2024-07-17 | MIT(经 FunASR 仓库 LICENSE) | `docs/licenses/funasr-LICENSE` | MIT 允许商用与再分发,保留声明 | 原文已核,签字 PENDING |
| SenseVoice `tokens.txt` | 同上 | 同模型归档 | 同上 | 同上 | PENDING |
| Silero VAD 模型(v4) | asr-models 归档 | MIT | `docs/licenses/silero-vad-LICENSE` | 保留版权与许可声明 | 原文已核,签字 PENDING |
| 官方测试样本 `zh.wav` / `en.wav` | 同模型归档 | 同模型归档 | — | 仅测试构建使用,不随发布产物分发 | PENDING |

## AndroidX / Kotlin(经 Maven 解析)

AGP 8.7.3、Kotlin 2.0.21、AndroidX(core/lifecycle/activity/compose BOM 2024.12.01)、kotlinx-coroutines 1.9.0、androidx.test 均为 Apache-2.0;精确传递依赖以 `./gradlew :app:dependencies` 输出建立 SBOM 后归档。

## 初步合规结论(供法务复核,非最终结论)

1. 四项核心组件均为宽松许可证(MIT / Apache-2.0),均**允许商用与再分发**,前提是保留版权与许可声明。
2. 分发动作要求:发布产物附带第三方声明文件(汇总上表),App"关于"页或随包 NOTICE 引用。
3. 待确认残留项:
   - FunASR 模型页(ModelScope)是否有附加条款超出仓库 LICENSE;
   - sherpa-onnx 构建的 ONNX Runtime 是否含第三方编译选项(初步无 RKNN/NNAPI 特化,`provider=cpu`);
   - Silero v4 归档内 README 的引用要求(非许可义务,建议遵守)。

## 待办(T0-03 收口)

- [x] 归档四项组件 LICENSE 原文至 `docs/licenses/`
- [ ] 法务/合规负责人签字确认上表
- [ ] 发布产物 NOTICE 文件生成(签字后执行)
- [ ] 更新 `models/manifest.json` 的 `license_review` 字段为 CONFIRMED
