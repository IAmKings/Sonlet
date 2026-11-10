package com.sonlet.app.feature.dictation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sonlet.app.dictation.core.DictationStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1-1:状态恢复(转屏/深色切换/进程重建)不得丢失草稿。
 *
 * 用 [StateRestorationTester] 复现"保存并重建"这条路径:它会把 rememberSaveable
 * 的注册表存盘后重新进入组合,效果等同 Activity 重建,但不依赖设备朝向与
 * ActivityScenario 的重启时序(后者在 Compose 测试里拿不到新的 semantics 树)。
 */
@RunWith(AndroidJUnit4::class)
class DictationScreenRotationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun draftSurvivesStateRestoration() {
        val viewModel = DictationViewModel(app)
        val restoration = StateRestorationTester(rule)
        restoration.setContent { DictationScreen(viewModel) }
        rule.waitForIdle()

        rule.onNode(hasSetTextAction()).performTextInput("你好世界")
        rule.waitForIdle()
        assertEquals("输入后 VM 必须同步到最新文本", "你好世界", viewModel.editorValue.text)

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        // 界面侧:文本必须从保存的状态里恢复出来(旧实现用 remember,这里会变成空)
        rule.onNode(hasSetTextAction()).assertExists()
        rule.onNode(hasText("你好世界")).assertExists()
        assertEquals(
            "恢复后 VM 与界面必须一致,交付时才不会插进旧值",
            "你好世界",
            viewModel.editorValue.text,
        )
    }

    @Test
    fun styleSelectionIsOwnedByViewModel() {
        val viewModel = DictationViewModel(app)
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
