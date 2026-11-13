# 依赖安全说明:构建期告警与运行时依赖

> 状态:定性文档,2026-11-11 建立;2026-11-13 完成阶段 2 迁移并复核,结果见 §8。复核条件见 §6。
> 结论先写:**GitHub Dependabot 报告的 61 条依赖告警全部位于构建工具链(classpath),不进入 APK,不影响安装到用户设备上的产物。**

## 1. 现象

仓库开启 Dependabot 后累计 61 条开放告警:3 critical / 24 high / 32 medium / 2 low,分布在 15 个包。初判容易误读为"App 依赖了有漏洞的 netty / bouncycastle",实际不是。

告警的 `manifest_path` 全部指向 `settings.gradle.kts`,即插件解析的 `classpath` 配置。

## 2. 证据

### 2.1 告警全部落在构建期 classpath

```bash
./gradlew buildEnvironment
```

输出根节点:

```
classpath
+--- com.android.application:com.android.application.gradle.plugin:8.7.3
     \--- com.android.tools.build:gradle:8.7.3        # → Android Gradle Plugin
          +--- com.android.tools:sdk-common:31.7.3    # → bouncycastle / guava / commons-io …
          +--- com.android.tools.utp:android-test-plugin-result-listener-gradle-proto:31.7.3
               \--- io.grpc:grpc-netty:1.57.0         # → netty 全家
```

完整链路(节选):

- netty(codec / codec-http / codec-http2 / handler / handler-proxy,共 41 条)
  ← `com.android.tools.utp:*:31.7.3` ← `io.grpc:grpc-netty:1.57.0`。UTP 是 AGP 跑仪器测试用的测试平台,只在构建机上运行。
- bouncycastle(bcprov-jdk18on 1.77 / bcpkix-jdk18on,共 12 条)← AGP `sdk-common:31.7.3`。
- guava 32.0.1-jre、protobuf-java 3.22.3、commons-io 2.13.0、commons-compress 1.21、jose4j 0.9.5、jdom2 2.0.6 ← 同样来自 AGP tools 31.7.3。
- kotlin-gradle-plugin 2.0.21 自身 1 条,来自 Kotlin Gradle 插件。

### 2.2 运行时依赖中没有这些库

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep -cE 'netty|bouncycastle|jose4j|jdom2|commons-compress'
# → 0
```

`releaseRuntimeClasspath` 是真正会被打进 APK、随应用分发的配置。上面对这些库命中 0 条。

### 2.3 Compose BOM 与 coroutines 不在告警清单内

61 条告警中没有任何一条属于 `androidx.compose:*` 或 `kotlinx-coroutines-*`。升级 Compose BOM 2024.12 或 coroutines 1.9 **不会减少任何一条告警**,它们属于常规版本维护。

## 3. 明细

| 包 | 条数 | critical | high | 引入者 |
|---|---:|---:|---:|---|
| org.bouncycastle:bcprov-jdk18on | 10 | 2 | 2 | AGP sdk-common 31.7.3 |
| io.netty:netty-codec-http | 19 | 0 | 6 | AGP UTP → grpc-netty 1.57.0 |
| io.netty:netty-codec-http2 | 9 | 0 | 5 | 同上 |
| io.netty:netty-handler | 7 | 1 | 4 | 同上 |
| io.netty:netty-codec | 3 | 0 | 2 | 同上 |
| io.netty:netty-common | 2 | 0 | 0 | 同上 |
| io.netty:netty-handler-proxy | 1 | 0 | 0 | 同上 |
| org.bouncycastle:bcpkix-jdk18on | 2 | 0 | 0 | AGP sdk-common 31.7.3 |
| com.google.guava:guava | 1 | 0 | 1 | AGP tools 31.7.3 |
| com.google.protobuf:protobuf-java | 1 | 0 | 1 | 同上 |
| commons-io:commons-io | 1 | 0 | 1 | 同上 |
| org.apache.commons:commons-compress | 2 | 0 | 0 | 同上 |
| org.bitbucket.b_c:jose4j | 1 | 0 | 1 | 同上 |
| org.jdom:jdom2 | 1 | 0 | 1 | 同上 |
| org.jetbrains.kotlin:kotlin-gradle-plugin | 1 | 0 | 0 | Kotlin Gradle 插件 |

## 4. 风险定级

| 对象 | 风险 | 说明 |
|---|---|---|
| 用户设备 | **零** | 这些库不编译进 APK,不存在于分发产物中 |
| CI runner | 低 | 临时机器,任务结束即销毁 |
| 开发机 | 中低 | Gradle daemon 长期运行这批工具,但利用前提是构建期被喂入恶意输入(恶意依赖或恶意被测 APK),不是"放着就会被攻击" |
| 供应链卫生 | 中等 | 构建工具长期带着已知 CVE,不利于审计与合规 |

结论:不是"必须当天修"的运行时漏洞,而是"随构建工具升级一并清理"的卫生问题。真正的修复只有一条路——升级 AGP 与 Kotlin Gradle 插件,tools 31.7.3 换掉后这批传递依赖才会消失。App 侧代码无法单独 pin 这些版本(AGP 内部使用,强行 constraint 会导致构建工具自身行为异常)。

## 5. 处置路径

1. **阶段 0(本文档)**:完成定性,避免把构建期告警误判为运行时漏洞。
2. **阶段 1**:逐个评审 Dependabot PR 的 CI 结果,只合并真正可合的维护性升级。
3. **阶段 2(已完成,见 §8)**:Gradle 9.6 → AGP 9.5.1 → compileSdk 37 → Compose BOM 2026.09,以及 Kotlin 2.4 + coroutines 1.11,在单独分支完成,通过完整回归后合并(PR #11)。
4. **阶段 3**:给 `.github/dependabot.yml` 加分组与 major 版本屏蔽,降低评审噪音。

阶段 2 的验收标准:`testDebugUnitTest` 全绿、真机 4 个测试类全绿、长稳测试通过、`assembleRelease` 产物通过 `apksigner verify`、CI 全绿;合并后用 `./gradlew buildEnvironment` 复核 61 条归零。

## 6. 复核条件(本文档何时失效)

出现以下任一情况,需要重做定性:

- AGP / Kotlin Gradle 插件升级后重新读取 `buildEnvironment`,确认告警来源是否已消失;
- `app/build.gradle.kts` 引入新的、会把工具链依赖带入运行时配置的插件(例如把某工具库误写成 `implementation`);
- `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 出现第 2.2 节所列库的任何一条;
- 新增单元/仪器测试框架,使 UTP 之外的测试依赖进入 APK。

## 7. 未做的事(如实记录)

- 本文档未 dismiss 任何 Dependabot 告警。告警保持可见,作为阶段 2 的待办清单存在;若需要清理告警面板,可用 `vulnerable_code_not_in_execute_path` 理由批量 dismiss,但必须在本文档登记与复核条件,否则等于把风险藏起来。
- 依赖仅做了 Dependabot 层面的漏洞比对,未运行独立的 SBOM / CVE 扫描工具。

## 8. 2026-11-13 迁移后的复核

阶段 2 已合并(PR #11,main `18fd1b9`),工具链变为 Gradle 9.6.0 / AGP 9.5.1 / compileSdk 37 / Compose BOM 2026.09.00,并按 AGP 9 要求移除了 `org.jetbrains.kotlin.android` 插件(内置 Kotlin)。

Dependabot 重新扫描后面板从 **61 条降到 8 条**(1 critical / 4 high / 3 medium),8 条的 `manifest_path` 仍全部是 `settings.gradle.kts`,`releaseRuntimeClasspath` 命中 0 条。即:这次迁移关掉了 53 条,剩余部分仍全部位于构建期。

`./gradlew buildEnvironment` 与告警面板对照:

| 包 | 迁移前 | 迁移后 | 剩余告警 | 修复版本线 |
|---|---|---|---|---:|---|
| io.netty:*(7 个坐标) | 4.1.x | 已不在依赖图中 | 0(原 41) | — |
| org.bouncycastle:bcprov-jdk18on | 1.77 | 1.80.2 | 2 | 1.85 |
| org.bouncycastle:bcpkix-jdk18on | 1.77 | 1.80.2 | 1 | 1.84 |
| com.google.guava:guava | 32.0.1-jre | 33.4.0-jre | 1 | 33.7.2 |
| org.apache.commons:commons-lang3 | — | 3.16.0 | 1 | 3.18.0 |
| org.apache.httpcomponents:httpclient | — | 4.5.6(图内另见 4.5.14) | 1 | 4.5.13 |
| org.bitbucket.b_c:jose4j | 0.9.5 | 0.9.5 | 1 | 0.9.6 |
| org.jdom:jdom2 | 2.0.6 | 2.0.6 | 1 | 2.0.6.1 |
| com.google.protobuf:protobuf-java | 3.22.3 | 3.25.5 | 0 | — |
| commons-io:commons-io | 2.13.0 | 2.16.1 | 0 | — |
| org.apache.commons:commons-compress | 1.21 | 1.27.1 | 0 | — |
| org.jetbrains.kotlin:kotlin-gradle-plugin | 2.0.21 | 2.4.21 | 0 | — |

其中 commons-lang3、httpclient 与 bcprov 的新告警是**新披露的公告**命中 AGP 9.5.1 的新依赖图,不是迁移引入的问题。

**未对残留做版本强制**:这些版本由 AGP 自身传递引入,用 `resolutionStrategy.force` 顶上去,收益是关掉 8 条构建期告警,风险是构建工具行为异常(通常表现为难以定位的增量构建或打包失败)。待 AGP 后续版本自行跟进,或改为 dismiss 并在本文档登记。

运行时结论不变:上述库仍不出现在 `releaseRuntimeClasspath` 中,不进 APK。

后续可选动作(未做):dismiss 这 8 条并附 `vulnerable_code_not_in_execute_path` 理由;或按上方修复线在 AGP 升级时一并观察。

**2026-11-13 决定:不强制改版本,也不 dismiss,等 AGP 后续版本把 bouncycastle 带到 1.85。**

其中 critical 一条是 `bcprov-jdk18on` 1.80.2(CVE-2026-8763,CVSS 9.1,Name Constraints 通过 rfc822Name/URI 末尾的点绕过)。在本仓库的实际触发面很小:`sdk-common-32.5.1.jar` 的 575 个类里只有 `com.android.tools.ide.common.signing.KeystoreHelper` 引用 bouncycastle,只用到 asn1/cert 的构造与解析,不涉及证书链校验——这是静态推断,不是运行时验证。

复核方法:

```bash
./gradlew buildEnvironment | grep bcprov-jdk18on   # 看到 1.85 或更高即自动关闭
```

该决定只影响构建机,产物侧不变(`releaseRuntimeClasspath` 仍命中 0 条)。
