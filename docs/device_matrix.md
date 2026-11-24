# 真机机型矩阵与验收记录

本文件记录 Sonlet 在真实设备上的验收覆盖情况、已发现的设备相关问题，以及补齐矩阵的操作方式。

## 1. 环境

| 项 | 值 |
|---|---|
| 设备 A | Redmi 23078PND5G（corot），Android 14（API 34） |
| 设备 B | OnePlus PJZ110，Android 17（API 37），ColorOS |
| CPU/ABI | arm64-v8a（APK 只打包该 ABI） |
| 屏幕 | 1220×2712，竖屏 + 横屏（`user_rotation`） |
| 版本 | v0.1.3（versionCode 103）测矩阵；v0.1.4（104）已装真机验证启动与界面 |
| 时间 | 2026-11-22 至 2026-11-23 |

## 2. 覆盖情况

| 维度 | 状态 | 证据 |
|---|---|---|
| 安装 / 冷启动 | 通过 | `am start -W` 首启 2.6s、二启 2.3s，logcat 无 FATAL（`ba02dc3` 记录） |
| 0.1.2 → 0.1.3 覆盖升级 | 通过 | 不卸载、dataDir/appId/权限保持，`install -r` Success（`ba02dc3`） |
| 语音主流程（录音 → 转写 → 插入） | 通过 | 真人手动验收，草稿跨进程恢复已确认 |
| 准确率与延迟 | 通过 | 90 条语料：CER 1.66% / WER 2.08% / 混 2.74%，RTF p95 ≤ 0.10（`docs/benchmarks/`） |
| 大字体 200% / 深色 / 横屏 | 通过 | 截图验收（`6ce2bf9`、PR #26） |
| 外接键盘（回车/空格切换录音） | 通过 | `VoiceActionRowGestureTest` OK (6 tests) |
| 权限：首次授予、拒绝、永久拒绝、设置页恢复 | 通过 | `MicPermissionStateTest` 4 例 + 人工复核 |
| 完全离线 | 通过（静态） | APK 无 `android.permission.INTERNET`（`dumpsys package` 无该权限行） |
| 系统版本 26 / 30 | **未覆盖** | 手上最低是 API 34 |
| 系统版本 35+（Android 17 / API 37） | 通过 | PJZ110：安装、冷启动 354ms、界面、全量仪器 **42 项 `OK (42 tests)`**，见 §3.4 |
| 其他 SoC / 厂商 ROM | 部分通过 | 已覆盖高通 + ColorOS（PJZ110，42/42）；联发科未覆盖 |
| 长稳（100 次会话） | 通过（单独运行） | 单独派发 `OK (1 test)`；与评测同进程会崩，见 §3.1 |
| 转屏用例（3 例） | 通过 | 修复用例本身的两个缺陷后 `OK (3 tests)`，见 §3.2 |
| 全量仪器套件（42 项，就地跑） | 通过 | 2026-11-24 `--in-place` 一轮：41 + 1，0 失败，462 秒 |

## 3. 已发现的设备相关问题

### 3.1 全量仪器套件会触发系统重启

2026-11-23 02:41 跑全量 42 项时，设备在长稳用例阶段重启（`uptime` 归零，`INSTRUMENTATION_ABORTED: System has crashed`）。同一批用例单独运行（评测、音频生命周期、分段器、模型冒烟）全部正常。

两次复现都停在同一个位置：`AsrAccuracyEvalTest.evaluateCorpusIfPresent` 跑完（90 条模型推理正常）之后，进程进入 `LongRunDictationStressTest.hundredSessionsWithoutResourceLeak` 就崩，`logcat` 末尾只有 `INSTRUMENTATION_ABORTED: System has crashed`，没有 Java 异常。

单独运行的结果：长稳 `OK (1 test)`、评测 `OK (1 test)`，各自都没问题。所以不是"长稳本身跑不动"。更可能是**同一进程里先跑 90 条推理、再做 100 次录音会话**的累积内存压力（239MB 模型常驻 + 每次会话的音频缓冲）。在定位之前，两件事：不要把长稳用例放进默认全量流程，需要单独派发；以及先单独跑一次长稳（不含评测前置）确认它本身是否仍然可过。

### 3.2 转屏用例曾挂起并串场（已修复）

`draftIsDroppedWhenViewModelIsGone` 单独跑也会挂住（无失败、无超时，instrument 不返回）：用例用真实 `DictationViewModel` 起界面，PREPARING 阶段的进度动画让 `waitForIdle` 永不返回。

修掉两处后 `OK (3 tests)`：一是用例开头 `rule.mainClock.autoAdvance = false`，用 `advanceTimeByFrame()` 手动推帧；二是这套用例原先共享真实 `SharedPreferences`，上一个用例输入的正文被下一个用例的 ViewModel 恢复出来（失败信息 `expected:<[]>你好世界> but was:<[机密草稿]你好世界>`），改为每个用例注入内存版 `DraftStore`。

### 3.3 MIUI 的后台启动权限绑 UID

`com.sonlet.app` 重装后 appId 会变（10547 → 10550 → 10554 → 10557），MIUI 的「后台弹出界面」权限按 UID 授权，随之失效。表现不是报错，而是 `am instrument` **挂住**：测试 Activity 被系统拦在后台（`Abort background activity starts from <uid>`），没有任何输出。

因此每次重装后，凡是要起 Activity 的用例（手势、转屏、权限）都跑不了，只有无 UI 的用例能过。换非 MIUI 设备可避免；在该设备上每次重装后都要手动重新授权。

脚本对此有两条防线：跑全量前先用一个 Activity 用例做探针（约 90 秒），起不来就直接记 `background_start_denied` 并跳过，不再白跑一轮；每个批次还有「用例标记 150 秒不增长即中止」的停滞判定。实测在权限失效的设备上，整轮 156 秒就返回 `blocked / background_start_denied`，而不是挂半小时。

### 3.4 ColorOS 会回收没有窗口的 instrumentation 进程（已修复）

在 PJZ110（Android 17 / ColorOS）上跑 `AsrAccuracyEvalTest` 与 `LongRunDictationStressTest`，进程在模型加载后被系统杀掉，`am instrument` 只报 `shortMsg=Process crashed`，没有 Java 异常也没有 native tombstone：

```
OsenseKillAction: KillAction: Kill ProcessInfo{...:com.sonlet.app oom:FGS/102/0/instrumentation mem:0/403/7} : Cached(nirvana)
ActivityManager: Process com.sonlet.app (pid 5271) has died: fg  FGS
```

判别依据不是内存占用——Doze 白名单、活跃桶、亮屏、周期性触摸都试过，无效——而是**窗口可见性**。这两个用例只做引擎推理、不启动 Activity，进程没有可见窗口，ColorOS 据此判为可回收，`oom:FGS` 与 instrumentation 标记都护不住。UI 用例自带窗口，所以从未受影响。

修法：用例在推理前 `ActivityScenario.launch(MainActivity::class.java)` 挂一个可见窗口，结束后关闭。只动测试，业务代码不变；代价是重负载用例会顺带把首页渲染一遍。修复后两例各自 `OK (1 test)`，全量 `OK (42 tests)`。

Android 17 另需两处依赖侧适配：espresso 3.6.1 会调用已移除的 `android.hardware.input.inputmanager.getInstance()`（升到 3.7.0）；shell 不再持有 `GRANT_RUNTIME_PERMISSIONS`，权限改由 `AudioPermissionRule` 尽力授予并在失败时记账，首次运行前需在界面上手动允许一次麦克风。

## 4. 补齐矩阵的操作方式

```bash
./gradlew assembleDebug assembleDebugAndroidTest
scripts/run_device_matrix.sh build/device-matrix              # 卸载重装后再测(会重置 MIUI 权限)
scripts/run_device_matrix.sh build/device-matrix 1800 --in-place   # 就地测,不动安装状态
```

**MIUI 设备建议用 `--in-place`**：重装会换 appId，`后台弹出界面` 权限随之失效，UI 用例必然挂。就地模式只确认包在，不碰安装状态。

脚本会逐台设备卸载重装、跑全量仪器测试、把型号/系统/ABI/结果/耗时写进 `summary.tsv`。新增设备只要 `adb connect` 后重跑，无需改代码。

按当前已知问题，跑之前请确认：设备已解锁、MIUI 后台启动权限已授予、无关的自动化类 App 已 `pm disable-user`（它们会弹 ANR 抢焦点，同样导致挂起）。
