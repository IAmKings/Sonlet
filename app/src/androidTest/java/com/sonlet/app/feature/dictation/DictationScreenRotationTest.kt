package com.sonlet.app.feature.dictation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sonlet.app.dictation.core.DictationStyle
import com.sonlet.app.dictation.core.DraftStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 草稿的存活边界(质量整改 T4)。
 *
 * 正文属敏感内容,不写 saved state:转屏/深色切换靠 ViewModel 存活保留,
 * 进程被杀即丢弃。这两个用例分别锁定"该活的活着"与"该丢的丢了"。
 */
@RunWith(AndroidJUnit4::class)
class DictationScreenRotationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** 每个用例一份内存草稿:真 SharedPreferences 会把上一个用例的正文带进下一个 */
    private class MemoryDraftStore : DraftStore {
        private var draft: DraftStore.Draft? = null
        override fun load(): DraftStore.Draft? = draft
        override fun save(draft: DraftStore.Draft) { this.draft = draft }
        override fun clear() { draft = null }
    }

    private fun viewModel() = DictationViewModel(app, MemoryDraftStore())

    @Test
    fun draftSurvivesConfigurationChangeKeepingViewModel() {
        val viewModel = viewModel()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { DictationScreen(viewModel) }
        rule.waitForIdle()

        rule.onNode(hasSetTextAction()).performTextInput("你好世界")
        rule.waitForIdle()
        assertEquals("输入后 VM 必须同步到最新文本", "你好世界", viewModel.editorValue.text)

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        // 同一 VM 存活:重建后从 VM 取回草稿(而不是从 saved state)
        rule.onNode(hasSetTextAction()).assertExists()
        rule.onNode(hasText("你好世界")).assertExists()
    }

    @Test
    fun draftIsDroppedWhenViewModelIsGone() {
        // 关掉自动推进:真实 ViewModel 会停在 PREPARING,进度动画让 waitForIdle 永不返回
        rule.mainClock.autoAdvance = false

        val first = viewModel()
        val second = viewModel()
        var active by mutableStateOf(first)

        rule.setContent { DictationScreen(active) }
        rule.mainClock.advanceTimeByFrame()

        rule.onNode(hasSetTextAction()).performTextInput("机密草稿")
        rule.mainClock.advanceTimeByFrame()
        assertEquals("机密草稿", first.editorValue.text)

        // 模拟进程被杀:VM 换成全新实例,只有 saved state 能救回草稿——但它不该在
        rule.runOnIdle { active = second }
        rule.mainClock.advanceTimeByFrame()

        assertFalse(
            "正文不得随 saved state 复活",
            rule.onAllNodes(hasText("机密草稿")).fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals("新 VM 的编辑器必须是空的", "", second.editorValue.text)
    }

    @Test
    fun styleSelectionIsOwnedByViewModel() {
        val viewModel = viewModel()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { DictationScreen(viewModel) }
        rule.waitForIdle()

        viewModel.setStyle(DictationStyle.CONCISE)
        rule.waitForIdle()

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        assertEquals(
            "风格真值只在 VM:恢复后不得回落到默认值",
            DictationStyle.CONCISE,
            viewModel.styleFlow.value,
        )
    }
}
