package com.sonlet.app.feature.dictation

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sonlet.app.dictation.core.SharedPreferencesDraftStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 交付位置取"交付当刻"的编辑状态,而不是按下录音时的快照(PRD §5.3)。
 *
 * 场景:用户在转写期间继续编辑——移动光标、改写已有内容。结果必须落到那一刻的光标处,
 * 落在旧位置等于把用户刚写的东西截断。
 */
@RunWith(AndroidJUnit4::class)
class ResultInsertionCursorTest {

    private val app: Application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private lateinit var store: SharedPreferencesDraftStore
    private lateinit var viewModel: DictationViewModel

    @Before
    fun setUp() {
        store = SharedPreferencesDraftStore(app, prefsName = "editor_draft_test")
        store.clear()
        viewModel = DictationViewModel(app, store)
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun insertUsesCursorMovedDuringTranscription() {
        // 录音开始时是空文档;转写期间用户打了半句并把光标留在词中间
        viewModel.updateEditor(TextFieldValue("Hello world", TextRange(5)))

        viewModel.applyDeliveredText("big", original = null)

        assertEquals("Hello big world", viewModel.editorValue.text)
        assertEquals("光标应停在插入内容之后", 9, viewModel.editorValue.selection.start)
    }

    @Test
    fun insertFollowsTextReplacedDuringTranscription() {
        // 用户在等待期间把正文整个换掉,光标停在中文标点之后
        viewModel.updateEditor(TextFieldValue("笔记：", TextRange(3)))

        viewModel.applyDeliveredText("今天要下雨", original = null)
        viewModel.applyDeliveredText("记得带伞", original = null)

        assertEquals("笔记：今天要下雨记得带伞", viewModel.editorValue.text)
        assertEquals(12, viewModel.editorValue.selection.start)
    }

    @Test
    fun insertAtDocumentStartKeepsExistingText() {
        viewModel.updateEditor(TextFieldValue("第二段", TextRange(0)))

        viewModel.applyDeliveredText("第一段", original = null)

        assertEquals("第一段第二段", viewModel.editorValue.text)
        assertEquals(3, viewModel.editorValue.selection.start)
    }
}
