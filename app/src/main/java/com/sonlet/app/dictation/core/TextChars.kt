package com.sonlet.app.dictation.core

/**
 * 汉字判定:插入空格规则与填充词切分共用同一实现。
 *
 * 覆盖基本区、扩展 A/B 与兼容区;不做全 Unicode 汉字覆盖(超出本产品语料范围)。
 */
internal fun Char.isHanIdeograph(): Boolean {
    val block = Character.UnicodeBlock.of(this) ?: return false
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
}
