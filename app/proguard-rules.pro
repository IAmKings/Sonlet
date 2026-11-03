# Sonlet Release 混淆规则
# JNI 桥接:sherpa-onnx Kotlin API 通过 JNI 读写 data class 字段并声明 native 方法,
# 混淆会破坏绑定(Release Gate G08:混淆不得破坏 JNI 桥接)。
-keep class com.k2fsa.sherpa.onnx.** { *; }
