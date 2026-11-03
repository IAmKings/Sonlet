# Sonlet

**Speak locally. Build freely.**

面向 Android 的轻量端侧语音工具集。本仓库目前完成需求、品牌标识与界面设计基线，尚未建立 Android 工程或验证识别能力。

- [设计系统与交互规范](DESIGN.md)
- [产品需求与技术方案](docs/Hex_Android_Offline_ASR_PRD_v1.0.md)
- [标识使用指南](docs/brand/sonlet-logo-kit/使用指南.md)
- [品牌展示](docs/brand/sonlet-logo-kit/presentation/sonlet-overview.png)

P0 范围为 App 内前台录音、本地转写和当前编辑框插入；实现阶段需按 PRD 验证取消、生命周期、数据交付与设备兼容性。

## Git 提交

提交标题和说明使用中文，必要的技术名称可保留原文，例如：

```text
初始化仓库并建立设计规范
修复取消转写后旧结果回填的问题
```

首次克隆后启用仓库提交钩子（需要 Python 3）：

```sh
git config core.hooksPath .githooks
```

钩子校验标题包含汉字；完整语言规范见 [AGENTS.md](AGENTS.md)，正文仍须由提交者保持中文。本地钩子不代替未来远程仓库的提交策略。

运行提交检查测试：

```sh
python3 -m unittest discover -s tests -v
```
