# 0.1.2 → 0.1.3 升级路径验证方案

## 为什么值得单独验

0.1.3 的安装实测是「卸载后全新安装」，升级路径没走过。而 0.1.3 恰好第一次把草稿写进了应用私有存储、又改了模型校验缓存的 key 构成，这两处都只在升级时才会暴露问题：

- 草稿：`SharedPreferences("editor_draft")`，若升级被系统当成新安装，内容就没了；
- 模型校验缓存：key 是 `"${lastUpdateTime}:$MODEL_SHA256"`（`ModelRepository.kt:118-121`），升级后 `lastUpdateTime` 必变 → 缓存必然失效 → 首次启动会重新对 239MB 做一次 SHA-256。这是设计意图，但要确认它只发生一次、且不拖垮首屏。

## 升级会动到的数据（先列清，再验）

| 位置 | 内容 | 升级后预期 |
|---|---|---|
| `shared_prefs/editor_draft.xml` | 正文与光标 | **保留** |
| `shared_prefs/model_verification.xml` | 校验缓存 key | **失效一次**，重新校验后写回 |
| 运行时权限 RECORD_AUDIO | 授权状态 | **保留** |
| 系统 app ops（MIUI「后台弹出界面」） | uid 维度 | **保留**（升级不改 uid） |
| 界面状态（输出风格） | 未持久化 | **回到「原文」**，属预期 |
| APK 内 assets 模型 | 随包替换 | 由摘要校验保证一致 |

## 一处必须先说清的前提

**0.1.2 没有草稿持久化。** 草稿写入 `SharedPreferences("editor_draft")` 是 0.1.3 才有的能力（`DraftStore` / `DraftKeeper`），0.1.2 里正文只在 ViewModel 内存中。

所以「升级后草稿还在」对 0.1.2 → 0.1.3 **不成立且不可验证**：升级会杀进程，内存里的正文必然消失。这不是回归，是能力边界。这份方案里的草稿检查项，只对 **0.1.3 及之后的版本**（第一次真正有持久化）有意义，用于防止未来版本把已保存的草稿弄丢。

换个说法：0.1.2 → 0.1.3 真正能验的是——升级不丢"已经存在的持久化数据与授权状态"、不崩溃、首次启动不会因为缓存失效而卡住。

## 前置条件

1. 两份 APK 同一证书（否则 `install -r` 直接因签名不一致失败）：

```bash
BT=$HOME/Library/Android/sdk/build-tools/36.0.0
$BT/apksigner verify --print-certs /tmp/Sonlet-0.1.2.apk | grep "SHA-256 digest"
$BT/apksigner verify --print-certs /tmp/Sonlet-0.1.3.apk | grep "SHA-256 digest"
# 两者都应为 22fa1ab87ca46f2d6003b95983b9156b7e8b35d3810d91a6af4010d23339049b
```

2. versionCode 递增：0.1.2 = 102，0.1.3 = 103。

3. 设备已解锁。锁屏状态下 `am instrument` 与部分 shell 命令会被系统拒绝。

## 步骤

### 1. 铺基线（会清空现有数据）

```bash
./scripts/verify_upgrade.sh baseline /tmp/Sonlet-0.1.2.apk --yes
```

记录三件事：`versionCode=101`、`dataDir`、`userId`。

### 2. 在 0.1.2 上造数据（手动）

1. 打开 App，允许麦克风权限；
2. 编辑器里手打一段正文，末尾留一个光标位置（例如文本中间）；
3. 选一个非默认风格（如「自然」）——它不持久化，用来对照升级后的重置；
4. 等首页进入「可以开始语音输入」（此时模型校验完成，缓存写入）。

### 3. 记录升级前快照

```bash
./scripts/verify_upgrade.sh snapshot > /tmp/before.txt
```

### 4. 覆盖安装 0.1.3（不卸载）

```bash
./scripts/verify_upgrade.sh upgrade /tmp/Sonlet-0.1.3.apk
```

脚本会打印 `Success`、新 versionCode、dataDir 与 userId，并自动比对快照。

### 5. 升级后的人工检查

| 检查 | 通过条件 |
|---|---|
| 冷启动 | 无崩溃；日志无 `FATAL`/`AndroidRuntime` |
| 首次启动耗时 | 明显长于平时（重算 239MB 摘要），但应在数秒内进入 READY |
| 草稿 | 0.1.2 起点升上来应为空（无持久化能力）；0.1.3 及之后的升级，正文与光标应保留 |
| 麦克风 | 直接可录，不弹权限对话框 |
| 风格 | 显示「原文」（预期重置，不算缺陷） |
| 二次启动 | 回到正常速度（缓存已写回，不再重算） |

### 6. 收尾检查

```bash
./scripts/verify_upgrade.sh snapshot > /tmp/after.txt
diff /tmp/before.txt /tmp/after.txt
```

预期差异只有 `versionCode`/`versionName`/`lastUpdateTime` 三项；`dataDir`、`userId`、授权状态、app ops 条目应完全一致。

## 预期失败（不是缺陷，需在记录里写明）

- **降级被拒**：`adb install -r /tmp/Sonlet-0.1.2.apk` 应报 `INSTALL_FAILED_VERSION_DOWNGRADE`。
- **风格重置**：界面不持久化输出风格，升级后回到「原文」。

## 本次实测（2026-11-21，0.1.2 → 0.1.3）

| 项 | 结果 |
|---|---|
| `install -r` | Success，未卸载 |
| versionCode / versionName | 102 → 103，0.1.2 → 0.1.3 |
| dataDir | `/data/user/0/com.sonlet.app`，前后一致 |
| appId | 10547，前后一致（升级不改 uid） |
| RECORD_AUDIO | 升级前后均 `granted=true`，未重新弹窗 |
| app ops 记录 | 前后一致，仅时间戳相对值变化 |
| 冷启动 | `am start -W` TotalTime 331ms |
| 首次进 READY | 2.6s（缓存 key 因 `lastUpdateTime` 变化而失效，需重算摘要） |
| 第二次进 READY | 2.3s |
| 崩溃 | logcat 无 FATAL / AndroidRuntime |
| 草稿 | 空（0.1.2 无持久化，预期） |
| 输出风格 | 回到「原文」（未持久化，预期） |

缓存是否真的失效并只重算一次，本轮只有间接证据（首启比二启慢约 0.3 秒，与一次 239MB 摘要的量级相符）；应用非 debuggable，读不到 `model_verification.xml` 里的 key。

## 判定

全部通过即认为升级路径可交付。任一项不符都要当场取信（`logcat`、`dumpsys`、截图），并按"升级丢数据 / 升级后卡顿 / 权限被清"三类归因，不要用"重新装一次就好了"带过。
