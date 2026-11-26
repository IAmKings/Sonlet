package com.sonlet.app.feature.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateNotesTest {

    @Test
    fun dropsHeadingsLinksAndAuthorTail() {
        val raw = """
            ## What's Changed
            * 修复按住说话丢事件 by @IAmKings in https://github.com/IAmKings/Sonlet/pull/19
            * 文档: 同步提交号 by @IAmKings in https://github.com/IAmKings/Sonlet/pull/66

            **Full Changelog**: https://github.com/IAmKings/Sonlet/compare/v0.1.5...v0.1.6
        """.trimIndent()

        assertEquals(
            "修复按住说话丢事件\n文档: 同步提交号",
            summarizeNotes(raw),
        )
    }

    @Test
    fun dropsPlainTextChangelogFooter() {
        // 库已把 markdown 转成纯文本，星号消失后仍要能识别噪音行
        val raw = """
            * 修复 A
            Full Changelog: https://github.com/IAmKings/Sonlet/compare/v0.1.6...v0.1.7
            * 修复 B
        """.trimIndent()

        assertEquals("修复 A\n修复 B", summarizeNotes(raw))
    }

    @Test
    fun capsAtMaxLines() {
        val raw = (1..10).joinToString("\n") { "* 条目$it" }
        assertEquals("条目1\n条目2\n条目3", summarizeNotes(raw, maxLines = 3))
    }

    @Test
    fun blankNotesStayBlank() {
        assertEquals("", summarizeNotes("   \n\n"))
    }
}
