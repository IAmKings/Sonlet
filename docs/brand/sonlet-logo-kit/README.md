# Sonlet · Split S 标识包

默认使用 digital/sonlet-horizontal-color.svg。全部标识为填充路径，不依赖字体。

- source/：常规、小尺寸、光学校正反显和 favicon 母版。
- digital/：横排、竖排、字标、带口号、符号；color、dark、black、white、mono 配色；核心 PNG 和 1024 px 应用图标。
- web/：favicon SVG/ICO/PNG、Apple Touch、PWA 192/512、maskable 图标、manifest 与 head 片段。默认路径从网站根目录引用，子路径部署时调整。
- android/：将 drawable/ 和 mipmap-anydpi-v26/、mipmap-anydpi-v33/ 下的资源复制到目标 app 的 res/；在 application 上设置 android:icon="@mipmap/ic_sonlet"。v33 包含 monochrome 主题层；背景全幅，前景不预裁圆角。低于 API 26 的项目需另配 legacy fallback。未修改任何现有应用。
- presentation/：品牌概览、单文件应用演示与逐页图片。所有 UI 是示意。
- 使用指南.md / presentation/usage-guide.html：使用规范。
- palette.json：数字配色和近似 CMYK；checks/：结构审计和交付检查。

Android 图层依据官方规范设计： https://developer.android.com/develop/ui/compose/system/icon_design_adaptive
尚未进行 Android 构建或真机启动器验证。未提供 AI/EPS、印刷色彩管理 PDF 或 Pantone 实样。
