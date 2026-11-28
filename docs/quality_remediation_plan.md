# Sonlet 代码质量整改计划

**状态**　完成，2026-11-25：第 1–3 节与第 4 节（含 4-4 合规签字）全部完成。审查基线为 `main@32f6c63`，变更比较起点为 `8fb9a04`。GitHub 最新公开版为 [v0.1.6](https://github.com/IAmKings/Sonlet/releases/tag/v0.1.6)。

本轮目标是恢复可核验的发布流程，解决听写结果丢失和录音停不下来的问题，并补齐正文隐私与界面验收。既有性能目标、待验证项和受限内测批准沿用 [PRD](Hex_Android_Offline_ASR_PRD_v1.0.md)、[发布决策](release_decision.md)与[发布检查清单](release_checklist.md)。[上次候选包记录](release_readiness_plan.md)仅对应其中记录的文件摘要。

## 完成情况（2026-11-25）

| 节 | 工作 | 状态 | 落地 |
|---|---|---|---|
| 1 | 挡住未批准的公开发布 | 完成 | `719bf10`（PR #29）：候选构建只产出 artifact，`publish.yml` 需 `environment: release` 人工批准 + `approved_by` + 精确 SHA-256，且校验 run 的 headSha 与 tag 指向同一提交；v0.1.3 实跑（Publish run 44879687970） |
| 1 | 保护已发布附件 | 完成 | 同上：同名 Release 先比附件摘要，一致即成功、不一致即失败，不再覆盖上传 |
| 1 | 新版本必须高于已公开版本 | 完成 | 同上：`--published-version-code` + `--list-published`，`tests/test_release_apk.py` 覆盖低于 / 等于 / 高于三种情况 |
| 2 | 解耦结果与提示交付 | 完成 | `f09ef3b`（PR #30）：结果走 `Channel(UNLIMITED)`，消费者确认后才计入；`ResultDeliveryTest` 在提示停滞下 100 次转写全部恰好插入一次 |
| 2 | 落实 30 秒录音截止 | 完成 | `372ad52` + `f5c139f`：截止改用单调时钟，读循环与状态机都按"连续 30 秒没有新样本"判定。清理超 `DISPOSE_TIMEOUT_MS` 时静默放行，见下方设计决定 |
| 2 | 录完后的路由变化保留转写 | 完成 | `372ad52`：路由失效只在 STARTING/RECORDING 中止，TRANSCRIBING 不取消 |
| 3 | 明确草稿生命周期 | 完成 | `e456e0b`：正文不进 saved state；`7798997`：改为应用私有存储 + 防抖落盘，杀进程可恢复。隐私约定已同步（PRD §14）与验收条目（`acceptance_guide.md` 2.4–2.6），`beb9e56` |
| 3 | 修正英文分段句界 | 完成 | `c13f782`（PR #33）：`Hello.` + `World` → `Hello. World`，中文全角、小数与标识符一并覆盖 |
| 3 | 补齐无障碍和键盘反馈 | 完成 | `c13f782`：分段选中态加勾选标记、按住模式补 TalkBack 等同动作、Snackbar 避让导航栏与软键盘；`b5fbd0b` 补外接键盘回车/空格切换 + 仪器用例 |
| 4-1 | 单测 / Python / 双变体 Lint / actionlint / 签名构建 | 完成 | `ci.yml` 跑 `lintDebug lintRelease` 与 `actionlint`；签名构建在 Release Candidate 工作流内 |
| 4-2 | 真机验收 | 完成 | 两台各一轮全量 `OK (42 tests)`：Redmi 23078PND5G（MediaTek MT6985 / Android 14 / HyperOS）、OnePlus PJZ110（Qualcomm SM8750 / Android 17 / ColorOS）。覆盖安装冷启、0.1.2→0.1.3 升级、真实录音、200% 字体/深色/横屏、外接键盘、权限四态、长稳与转屏；证据见 `docs/device_matrix.md`，跑测见 `scripts/run_device_matrix.sh`（`a159b37`、`f31c6b5`、`68fac25`、`0b9e059`）。**未覆盖**：Android 26–33、其他厂商 ROM |
| 4-3 | CER/WER 与 P95 延迟 | 完成 | 合成语料 90 条（zh/en/mixed 各 30），基线 zh CER 1.66% / en WER 2.08% / mixed CER 2.74%，RTF p95 ≤ 0.10，记录于 `docs/benchmarks/asr_accuracy_2026-11-22.md`；`AsrAccuracyEvalTest` 加回归门槛（10% / 10% / 12%）。**局限**：语料是 TTS 合成，缺口音、口吃、噪声与远场，不能替代真人验收 |
| 4-4 | G07 签字 / NOTICE / SBOM | 完成 | `ae8e8a9`：权重许可更正为 Apache-2.0 并随包附带原文，NOTICE 同步；签字材料 `docs/g07_signoff.md`，**2026-11-25 由 Kuluoluo 签署同意分发**；`models/manifest.json` 的 `license_review` 已转 `CONFIRMED` |


### 设计决定：清理超预算静默放行

`PcmAudioRecorder.dispose()` 用 `withTimeoutOrNull(DISPOSE_TIMEOUT_MS) { shutdown() }` 包住停机，超时后继续执行 `scope.cancel()`。这**不是**遗漏，是有意的取舍：

- 超时意味着 `readLoop` 卡在 `record.read()` 里没有返回。此时 `scope` 已取消、`AudioRecord` 的引用随 job 一起被丢弃，麦克风归属由系统在进程内参数重建后回收，App 侧没有任何可以"修复"它的动作。
- 给用户报一个"录音设备没释放"的错误，既不指向可执行的下一步，又会在下次进入页面时留下一个无法消除的提示。
- 真正需要保证的是"不会挂着旧会话继续交付"和"新录音不被旧的持久占用"：前者由会话代际（`SessionGenerationGuard`）保证，后者由 `beginSession()` 拒绝并发会话保证。

因此该路径保持静默。若将来出现设备侧确有麦克风未释放的可复现案例，再补"重启应用"级别的引导，而不是新增错误码。

## 处理顺序

| 顺序 | 工作 | 完成标准 |
|---|---|---|
| 1 | 恢复发布门禁及制品来源校验 | tag 构建可验证。公开操作校验提交、批准记录和制品摘要。已发布文件无法被重跑覆盖。 |
| 2 | 修复听写交付、录音截止与路由中断 | 提示积压时正文仍逐条交付。停滞采集到期收敛。录完后断开音频设备仍可完成转写。 |
| 3 | 收口草稿保存、英文拼接和无障碍 | 草稿生命周期符合产品约定。中英文拼接规则有回归。键盘和 TalkBack 可用，提示可见。 |
| 4 | 验收并决定公开范围 | 对实际签名 APK 记录真机、性能和分发证据。负责人确认准入后发布。 |

发布流程和听写修复可以并行。合规复核、测试设备安排和语料准备可以从第一阶段开始。

## 1. 发布门禁与制品一致性

| 优先级 | 工作 | 实施与验收 |
|---|---|---|
| P1 | 挡住未经批准的公开发布 | 拆开候选构建和公开操作。候选包通过受控渠道交付，公开操作要求负责人批准和精确 SHA-256。缺批准、摘要不符或远端 tag 指向的提交与构建提交不同时，公开步骤失败。干跑保持只构建与校验。 |
| P1 | 保护已发布附件 | 去掉覆盖上传路径。已有同名附件时，摘要相同的重跑直接成功，摘要不同则停止并保留原附件。模拟上传失败后确认已有附件保持可下载。 |
| P2 | 新版本必须高于已公开版本 | 分开历史 APK 校验和新版本准入。新发布时按公开过的最高 versionCode 做递增检查，历史包仍可离线复核。覆盖低于、等于和高于公开上限三种情况。 |

涉及 [.github/workflows/release.yml](../.github/workflows/release.yml)、[APK 校验脚本](../scripts/verify_release_apk.py)、[校验测试](../tests/test_release_apk.py)和 [发布说明](development.md)。先用替身或干跑验证拒绝路径，再验证真实签名构建。核对现有公开附件摘要与已有验收记录，差异交负责人确认。公开版本需要修正时走新的递增版本。

## 2. 听写可靠性

| 优先级 | 工作 | 实施与验收 |
|---|---|---|
| P1 | 解耦结果与提示交付 | 编辑器响应最终结果，Snackbar 使用独立的非阻塞提示通路。结果实际接收后再确认交付。在提示消费者停滞时完成 100 次模拟转写，100 条均恰好插入一次。补取消、重复结果和用户继续编辑的回归。审查时同场景仅收到 81 条。 |
| P1 | 落实 30 秒录音截止 | 以单调时钟计算录音截止时间，PCM 样本上限继续用于限制缓冲。覆盖采集持续零读、读取阻塞、用户取消与超时同时发生。30 秒触发停录，清理预算沿用现有 `DISPOSE_TIMEOUT_MS` 的 2 秒。超出预算时静默放行：界面不给错误、不上报新错误码（理由见「设计决定：清理超预算静默放行」）。 |
| P2 | 录完后的路由变化保留转写 | 只在启动和正在采集时因路由失效中止。录完进入转写后移除设备，已有 PCM 应继续转写并交付一次。显式取消和切后台仍保证零交付。 |

涉及 [DictationController](../app/src/main/java/com/sonlet/app/dictation/core/DictationController.kt)、[PcmAudioRecorder](../app/src/main/java/com/sonlet/app/audio/capture/PcmAudioRecorder.kt)、[DictationViewModel](../app/src/main/java/com/sonlet/app/feature/dictation/DictationViewModel.kt)及状态机、采集生命周期和长稳测试。先用现有 Fake 在 JVM 固定边界，再用真机验证真实录音资源和路由变化。对应 AC-P0-007、008、009、011、014、015、018。

## 3. 隐私、文本和交互

| 优先级 | 工作 | 实施与验收 |
|---|---|---|
| P1 | 明确草稿生命周期 | 沿用现行 PRD 时，由 ViewModel 在配置变化间保留草稿与光标，正文不进入 Android saved state。验证转屏、深浅切换后草稿仍在，系统杀进程再恢复后正文为空。若产品决定保留跨进程恢复，先更新隐私约定与验收规则。 |
| P2 | 修正英文分段句界 | 在分段拼接路径修复 `Hello.` 和 `World` 得到 `Hello.World` 的问题。用例覆盖英文句末标点、中文全角标点、原有空格、小数和标识符。编辑框插入规则和格式化原文保留一并回归。 |
| P2 | 补齐无障碍和键盘反馈 | 在分段选中项增加非颜色标记。为按住模式提供 TalkBack 和外接键盘可操作的开始、结束或等效入口。Snackbar 避让软键盘及导航栏。验证中英文、深浅色、200% 字号与键盘打开时的成功、空结果和过短提示。 |

涉及 [DictationScreen](../app/src/main/java/com/sonlet/app/feature/dictation/DictationScreen.kt)、[P1Components](../app/src/main/java/com/sonlet/app/feature/dictation/P1Components.kt)、[TextInsertionPolicy](../app/src/main/java/com/sonlet/app/dictation/core/TextInsertionPolicy.kt)和 [DESIGN](../DESIGN.md)。同批处理研发日志末尾空行和 `actionlint` 报告的重定向样式，确保提交差异检查通过。

## 4. 候选包验收与发布决定

1. 运行 JVM 单测、Python 测试、双变体 Lint、`actionlint` 和签名构建。记录提交、版本、证书和 APK 的 SHA-256。
2. 用实际拟发布的签名 APK 在目标 arm64 真机上验证升级、断网冷启动、真实录音、转写和编辑框回填。补齐取消、切后台、权限恢复、路由变化及目标机型矩阵的证据。记录设备、系统和测试结果。
3. 用带标注的中文、英文、中英混说语料输出 CER/WER，并单独记录结束录音至文字可见的 P95。语料数量及执行方法见 [评测指南](accuracy_eval.md)。已有固定音频引擎 RTF 和长稳记录继续保留其原有测量口径。
4. 由合规负责人完成 G07 分发签字和 NOTICE、SBOM 复核。发布负责人确认验收结果及可豁免项，核对 tag 对应提交和实际分发 APK 摘要。验收后重新构建的包需要重新校验摘要并补相应证据。

## 待负责人确认

- 草稿按现行 PRD 仅保留在应用运行期，还是修改隐私约定以保留跨进程恢复。规划以现行 PRD 为默认。
- 签名包验收使用哪些真机，语料何时可用。若本次发布范围或 G06 存在例外，记录负责人决定、用户可见限制与补测条件。G07 沿用公开分发前置要求。
- 现有公开附件缺少与验收摘要一致的记录。先核对已有批准与制品来源，再决定补充记录或通过后续版本处理。
