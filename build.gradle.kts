// Sonlet 根构建脚本:插件版本统一由 gradle/libs.versions.toml 锁定
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
