package com.sonlet.app.dictation.core

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文本插入策略测试(PRD §12.3 特殊样本、§5.3、AC-P0-011/012/013)。
 */
class TextInsertionPolicyTest {

    private fun tf(text: String, cursor: Int) =
        TextFieldValue(text, TextRange(cursor))

    // ---- 光标插入(AC-P0-011) ----

    @Test
    fun `insert at start`() {
        val result = TextInsertionPolicy.insert(tf("已有文本", 0), "你好")
        assertEquals("你好已有文本", result.text)
        assertEquals(2, result.selection.start)
    }

    @Test
    fun `insert at end`() {
        val result = TextInsertionPolicy.insert(tf("已有文本", 4), "你好")
        assertEquals("已有文本你好", result.text)
        assertEquals(6, result.selection.end)
    }

    @Test
    fun `insert in middle`() {
        val result = TextInsertionPolicy.insert(tf("开始结束", 2), "中间的话")
        assertEquals("开始中间的话结束", result.text)
        assertEquals(6, result.selection.start)
    }

    // ---- 选区替换(AC-P0-012) ----

    @Test
    fun `selection is replaced and cursor lands after replacement`() {
        val value = TextFieldValue("开始错误结束", TextRange(2, 4))
        val result = TextInsertionPolicy.insert(value, "正确")
        assertEquals("开始正确结束", result.text)
        assertEquals(4, result.selection.start)
        assertEquals(4, result.selection.end)
    }

    @Test
    fun `selection replacement with surrounding english keeps spacing`() {
        val value = TextFieldValue("hello world", TextRange(6, 11))
        val result = TextInsertionPolicy.insert(value, "there")
        assertEquals("hello there", result.text)
    }

    // ---- 编辑竞争(AC-P0-011:转写期间用户继续输入) ----

    @Test
    fun `insert uses the latest editor state passed in`() {
        // 模拟:录音开始时为"开始结束",转写期间用户加了后缀;
        // 交付时以最新状态插入 → 不覆盖新增内容
        val latest = tf("开始结束(用户新加)", 10)
        val result = TextInsertionPolicy.insert(latest, "语音")
        assertEquals("开始结束(用户新加)语音", result.text)
    }

    // ---- 邻接空格规则(§12.1) ----

    @Test
    fun `latin to latin adjacency adds single space`() {
        val result = TextInsertionPolicy.insert(tf("hello", 5), "world")
        assertEquals("hello world", result.text)
    }

    @Test
    fun `han to latin adjacency adds space`() {
        val result = TextInsertionPolicy.insert(tf("今天天气", 4), "nice")
        assertEquals("今天天气 nice", result.text)
    }

    @Test
    fun `latin to han adjacency adds space`() {
        val result = TextInsertionPolicy.insert(tf("say ", 4), "你好")
        assertEquals("say 你好", result.text)
    }

    @Test
    fun `han to han adjacency adds no space`() {
        val result = TextInsertionPolicy.insert(tf("今天", 2), "天气好")
        assertEquals("今天天气好", result.text)
    }

    @Test
    fun `existing trailing space is not doubled`() {
        val result = TextInsertionPolicy.insert(tf("hello ", 6), "world")
        assertEquals("hello world", result.text)
    }

    @Test
    fun `no space before cjk punctuation`() {
        val result = TextInsertionPolicy.insert(tf("结束。", 3), "明天")
        assertEquals("结束。明天", result.text)
    }

    @Test
    fun `transcript starting with punctuation gets no space`() {
        val result = TextInsertionPolicy.insert(tf("今天", 2), "。")
        assertEquals("今天。", result.text)
    }

    @Test
    fun `newline neighbor needs no space`() {
        val result = TextInsertionPolicy.insert(tf("第一行\n", 4), "第二行")
        assertEquals("第一行\n第二行", result.text)
    }

    // ---- Unicode 边界(emoji / 代理对) ----

    @Test
    fun `emoji in text is preserved on insertion at end`() {
        val result = TextInsertionPolicy.insert(tf("好的😀", 4), "开心")
        assertEquals("好的😀开心", result.text)
    }

    @Test
    fun `cursor inside surrogate pair snaps to boundary before inserting`() {
        // "😀" 占两个 char 单元,光标位于代理对中间(index 2 处于对内)
        // 吸附到对尾后插入;X 与 b 为拉丁邻接,按规则补一个空格
        val value = TextFieldValue("a😀b", TextRange(2))
        val result = TextInsertionPolicy.insert(value, "X")
        assertEquals("a😀X b", result.text)
    }

    @Test
    fun `selection covering emoji replaces whole pair`() {
        val value = TextFieldValue("a😀b", TextRange(1, 3))
        val result = TextInsertionPolicy.insert(value, "星")
        // a(拉丁)↔星(汉字)与 星↔b(拉丁)均按规则补空格
        assertEquals("a 星 b", result.text)
    }

    // ---- 空结果(AC-P0-013) ----

    @Test
    fun `empty transcript leaves value unchanged`() {
        val value = tf("原文", 2)
        val result = TextInsertionPolicy.insert(value, "")
        assertEquals(value, result)
    }

    @Test
    fun `insert into empty editor`() {
        val result = TextInsertionPolicy.insert(tf("", 0), "你好")
        assertEquals("你好", result.text)
        assertEquals(2, result.selection.end)
    }

    // ---- P1 分段拼接(FR-106) ----

    @Test
    fun `joinTranscripts applies adjacency spacing rules`() {
        // 汉字↔汉字:无空格
        assertEquals("我。我", TextInsertionPolicy.joinTranscripts("我。", "我"))
        // 拉丁↔拉丁:一个空格
        assertEquals("hello world", TextInsertionPolicy.joinTranscripts("hello", "world"))
        // 汉字↔拉丁:一个空格
        assertEquals("今天 nice", TextInsertionPolicy.joinTranscripts("今天", "nice"))
    }

    @Test
    fun `joinTranscripts passes through empty sides`() {
        assertEquals("只有一段", TextInsertionPolicy.joinTranscripts("", "只有一段"))
        assertEquals("只有一段", TextInsertionPolicy.joinTranscripts("只有一段", ""))
        assertEquals("", TextInsertionPolicy.joinTranscripts("", ""))
    }
}
