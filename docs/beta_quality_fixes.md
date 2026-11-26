# Sonlet 内测前复审修复清单

**复审基线**　2026-11-25，`main@42cd698`。三项 P1 已于同日修复（`06a2e79`），验收记录见文末。此前的整改记录见 [代码质量整改计划](quality_remediation_plan.md)，验收依据见 [离线听写 PRD](Hex_Android_Offline_ASR_PRD_v1.0.md)。

## 1. 录音 30 秒截止与资源清理

**复审证据**　录音计时随 PCM 进度刷新。复审中，每过 10 秒送入 1 秒 PCM，经过 40 秒后仍是 `RECORDING`。采集线程卡在原生读取时无法运行同一循环里的停滞检查。常规停录等待读取任务结束没有超时，现有 2 秒限制只覆盖 `dispose()`。

**修复范围**　从实际开始采集时设置固定的单调时钟截止，由独立于读取循环的逻辑触发自动停录，保留 PCM 缓冲上限。给结束、取消和切后台的常规清理路径设置 2 秒等待预算。超时后保留对旧录音资源的跟踪，确认释放后才允许重新打开麦克风；迟到的清理回调不得改变新会话的状态。涉及 [DictationController](../app/src/main/java/com/sonlet/app/dictation/core/DictationController.kt) 和 [PcmAudioRecorder](../app/src/main/java/com/sonlet/app/audio/capture/PcmAudioRecorder.kt)。

**验收**

- [x] 从开始采集算起，满 30 秒触发停录。用可控时钟覆盖正常采样、慢速持续出样、连续零读和读取阻塞。有效音频按现有规则转写，PCM 缓冲有界。
- [x] 结束、取消、切后台及其与自动停录并发时，2 秒内结束对同步清理的等待。验证慢清理时没有并行占用麦克风，旧会话释放后可重新录音，取消或切后台后无结果回填。
- [x] 在 arm64 真机验证满 30 秒自动停录、录音中切后台、返回后再次录音，并补测录完进入转写后断开音频设备仍可交付。对应 PRD AC-P0-007、008、014、015、CC-07。

## 2. 拒绝切后台或取消后的旧听写结果

**复审证据**　结果已进入队列而消费者尚未接收时，控制器可先返回 `READY`。此时切后台后仍能取到旧结果。当前 ViewModel 先写入正文、更新原文状态并保存草稿，随后才确认交付，并忽略确认失败。

**修复范围**　跟踪待写入结果的有效性，使正常结束的会话返回 `READY` 后仍可在前台交付。切后台、离开听写页或取消该次会话时，撤销尚未写入的结果。将校验、去重和编辑器写入放在同一串行边界内。有效结果按交付当时的正文和选区写入。涉及 [DictationController](../app/src/main/java/com/sonlet/app/dictation/core/DictationController.kt)、[SessionGenerationGuard](../app/src/main/java/com/sonlet/app/dictation/core/SessionGenerationGuard.kt) 和 [DictationViewModel](../app/src/main/java/com/sonlet/app/feature/dictation/DictationViewModel.kt)。

**验收**

- [x] 暂停消费，等结果入队且状态回到 `READY`，再分别触发切后台、离开页面或取消，恢复消费后检查编辑器、原文状态和持久化草稿均未写入旧结果。
- [x] 同样延迟消费，页面持续在前台且会话正常完成时，仍按最新选区插入一次。重复结果、迟到结果和新会话并发时，确认旧结果不会污染正文。
- [x] 提示消费者停滞时，验证正常结果仍逐条进入编辑器。回归覆盖实际正文和草稿变更。对应 PRD AC-P0-009、011、012、014。

## 3. 清空正文后立即删除草稿

**复审证据**　清空正文仍走 320 ms 防抖，复审在清空后 100 ms 重建草稿读取实例时读到了旧正文。现有设备测试在生产 `editor_draft` 存储上执行清理，可能删除测试设备上的用户草稿。

**修复范围**　编辑器变空时取消排队中的草稿写入，立即删除私有存储记录，并保证之前的写入不会恢复旧正文。非空正文继续防抖保存，保留进程重建后的正文和光标恢复。设备测试使用独立的测试存储。涉及 [DraftKeeper](../app/src/main/java/com/sonlet/app/dictation/core/DraftKeeper.kt)、[SharedPreferencesDraftStore](../app/src/main/java/com/sonlet/app/dictation/core/SharedPreferencesDraftStore.kt) 和 [DraftPersistenceTest](../app/src/androidTest/java/com/sonlet/app/feature/dictation/DraftPersistenceTest.kt)。

**验收**

- [x] 写入非空草稿后经编辑器清空，不等待防抖计时，从新实例读取时记录已删除。模拟进程重建后编辑器为空。
- [x] 先排队保存非空内容，再清空。推进时钟超过防抖窗口后，旧正文仍未恢复。非空草稿在进程重建后可恢复正文和光标。
- [x] 设备测试只读写独立的测试存储。运行前后验证应用的原有草稿未被清除或覆盖。对应 PRD 第 14 节。

## 验收记录

三项按清单逐一复测，记录修复提交、自动化测试结果，以及签名候选包的 SHA-256、真机型号、系统版本和实测结果。三项通过后，结合 [发布检查清单](release_checklist.md) 评估内测准入。

## 验收记录（2026-11-25）

修复提交 `06a2e79`（PR #65，分支提交 `28398c4`）。

| 项 | 结果 |
|---|---|
| JVM 单测 | **124 例 0 失败**，新增 6 例：慢速持续出样 30 秒收敛、交付窗口 3 例（确认关窗/撤销/新会话不继承）、清空即删与「排队的旧写入不复活」 |
| 真机仪器 | Redmi 23078PND5G（Android 14，debug 包）：**`OK (43 tests)`**，含新增的「设备测试不触碰生产草稿」 |
| 原始复现条件 | 每 10 秒送 1 秒 PCM，第 30 秒收敛（修复前 40 秒仍在 RECORDING）；清空后立刻用新实例读取为空；结果入队后切后台/离开，恢复消费不写入正文 |
| 自动停录 | `AudioRecordLifecycleTest.durationCapAutoStopsRecording` 通过：1.5 秒上限时样本恰好 24,000 且有界 |
| 未覆盖 | 读循环永久卡在原生 `read` 的「2 秒预算超时」路径未能故障注入，仅由代码审查覆盖；`releasePending` 期间的 `MIC_BUSY` 行为以单元测试中的 Fake 语义说明，未在真机复现 |

内测准入仍需按[发布检查清单](release_checklist.md)用签名候选包复测。

## 第三方复审（2026-11-26）

复审针对 `main@37e1b3a`，结论与处置：

| 复审项 | 复审结论 | 处置 |
|---|---|---|
| 30 秒录音截止与资源清理 | 部分通过：慢速出样用例通过；同一会话可重复收尾；原生停录未纳入 2 秒等待预算 | **已修**：`ReleaseGate.awaitRelease()` 发现已有在途收尾时只等待并返回 `false`，不再二次收尾；`finalizeRelease()` 增加会话守卫（`activeSessionId` 与入参不一致直接返回空结果）；`AudioRecord.stop()` 改为投递到 IO 协程执行，调用方只受 join 预算约束 |
| 切后台或取消后撤销旧结果 | 未通过：回到待机后再切后台或取消，排队中的结果仍能通过交付确认 | **已修**：`Cancel` / `AppBackgrounded` 在非可取消阶段（含 READY）调用 `revokePending()`，作废排队结果 |
| 清空正文后立即删除草稿 | 正常路径通过；真机清空后重启、磁盘写入失败未验证 | **已补**：`commit()` 返回值检查并记 `SonletDraft` 日志；真机整套包含清空后用新实例读取（等价进程重启） |

验证：JVM 单测 **132 例 0 失败**（新增「待机切后台/取消作废排队结果」「已有在途收尾只等待不重复收尾」）；真机 Redmi 23078PND5G（Android 14，debug 包）**`OK (46 tests)`**。

红检记录：把 `Cancel`/`AppBackgrounded` 的 `revokePending()` 去掉，两条新用例立即失败；把 `ReleaseGate` 的「已有收尾」分支去掉，「重复调用不得二次收尾」用例失败。

未覆盖：磁盘写入失败无法在设备上注入，仅由代码路径与日志覆盖；原生 `read` 永久卡死仍只有状态机层面的证明（见 2026-11-25 记录）。
