# Sonlet P0 Release Gate 检查清单(PRD §16.3)

> 勾选表示对应证据已归档;未达项必须附阻塞级别与处理结论。
> 证据目录:`docs/benchmarks/`(性能数据)、本文内嵌记录。

## G01 固定音频转写冒烟(目标机型 100% 通过)

- [x] Xiaomi 23078PND5G(Android 14,arm64-v8a):zh/en 官方固定样本转写通过,RTF 0.045 / 0.040,模型加载 1.16s(2026-11-04,SherpaModelSmokeTest)
- [ ] 其他机型(待设备矩阵扩展)

## G02 AC-P0 验收用例

| 用例 | 状态 | 证据 |
|---|---|---|
| AC-P0-001 首次加载 | [x] | 真机 PREPARING→READY;模型 SHA-256 校验 |
| AC-P0-002 完全离线识别 | [x] | 仪器测试无网络依赖 + Manifest 无 INTERNET 权限;飞行模式实测见 G04 |
| AC-P0-003 拒绝授权 | [x] | 权限路径单测兜底 + 就近说明面;真机待复测记录 |
| AC-P0-004 永久拒绝恢复 | [x] | 设置路径 + ON_RESUME 刷新;真机待复测记录 |
| AC-P0-005 正常录音状态链 | [x] | 状态机测试 + 真机 E2E |
| AC-P0-006 300ms 误触 | [x] | TooShort 路径单测;真机待复测 |
| AC-P0-007 30s 上限 | [x] | AudioRecordLifecycleTest durationCapAutoStopsRecording |
| AC-P0-008 录音中取消 | [x] | 状态机测试 |
| AC-P0-009 推理中取消 | [x] | 状态机测试 cancel during transcription |
| AC-P0-010 连续快速点击 | [x] | 状态机测试 20 轮 |
| AC-P0-011 转写中编辑 | [x] | TextInsertionPolicyTest |
| AC-P0-012 选区替换 | [x] | TextInsertionPolicyTest |
| AC-P0-013 无有效语音 | [x] | TranscriptProcessorTest blank + 状态机 empty |
| AC-P0-014 切后台 | [x] | 状态机 AppBackgrounded + LifecycleEventEffect ON_STOP |
| AC-P0-015 路由变化 | [x] | AudioInterruptionObserver + 状态机测试;通话焦点协作未做(已知限制) |
| AC-P0-016 模型缺失/损坏 | [x] | ModelRepository 校验 + 状态机 corrupted 测试;真机曾复现并修复 |
| AC-P0-017 推理异常 | [x] | 状态机注入测试 |
| AC-P0-018 重复结果 | [x] | SessionGenerationGuardTest markDelivered 去重 |
| AC-P0-019 连续稳定性 | [ ] | LongRunDictationStressTest 待真机执行 |
| AC-P0-020 CER/WER 评估 | [ ] | 需 ≥30 条/类标注语料(人工环节) |
| AC-P0-021 正常编辑不受影响 | [x] | 编辑器全阶段可编辑(实现保证);真机待复测 |
| AC-P0-022 不支持格式 | [x] | 采样率校验 + UNSUPPORTED_AUDIO_FORMAT 测试 |
| AC-P0-023 释放时未完成 decode | [x] | 引擎租约(decodeMutex)+ release 等待;并发仪器测试 |
| AC-P0-024 断网冷启动 | [ ] | 待飞行模式实测记录 |

## G03 100 次连续会话

- [ ] LongRunDictationStressTest 真机执行记录(含 PSS 趋势)

## G04 飞行模式冷启动到语音输入成功

- [ ] 飞行模式实测记录(仪器测试无网络路径已验证;待人工 UI 实测)

## G05 权限拒绝/切后台/打断可恢复

- [x] 状态机覆盖;真机人工复测待记录

## G06 性能与准确率报告

- [x] 初测:load 1.16s;RTF zh 0.045 / en 0.040(Xiaomi 23078PND5G,B 档中端)
- [ ] 3s/5s/10s/20s 分层样本、峰值内存、温度趋势(人工环节)
- [ ] CER/WER(依赖 AC-P0-020 语料)

## G07 许可证/版本/哈希确认

- [x] 版本与哈希已锁定并归档(docs/third_party_versions.md、models/manifest.json)
- [ ] 模型再分发许可签字(docs/third_party_licenses.md PENDING —— 分发前硬阻塞)

## G08 Debug/Release 双构建 ABI 实测

- [x] Debug arm64 真机实测通过
- [ ] Release(minify)真机实测:JNI 混淆 keep 规则已配置,待执行验证

## G09 诊断不含音频与正文

- [x] 日志字段白名单:sessionId/耗时/错误码;无 PCM、无识别正文(代码审查确认)

## G10 已知兼容性限制与错误提示

- [x] README 已知限制 + 全部错误码中英文文案
