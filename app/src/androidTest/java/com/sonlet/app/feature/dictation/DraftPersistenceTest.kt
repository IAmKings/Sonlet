package com.sonlet.app.feature.dictation

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sonlet.app.dictation.core.DraftStore
import com.sonlet.app.dictation.core.SharedPreferencesDraftStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 草稿持久化的真实存储路径(私有 SharedPreferences + commit 落盘)。
 * 防抖调度在 JVM 侧由 DraftKeeperTest 覆盖,这里验证"写下去能读回来、进程重建能恢复"。
 */
@RunWith(AndroidJUnit4::class)
class DraftPersistenceTest {

    private val app: Application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private lateinit var store: SharedPreferencesDraftStore

    /** 独立存储:设备测试不得读写用户真实的 editor_draft */
    private val testPrefs = "editor_draft_test"

    @Before
    fun setUp() {
        store = SharedPreferencesDraftStore(app, prefsName = testPrefs)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun draftSurvivesNewStoreInstance() {
        store.save(DraftStore.Draft("跨进程的草稿", 3, 3))

        val reopened = SharedPreferencesDraftStore(app, prefsName = testPrefs).load()
        assertEquals("跨进程的草稿", reopened?.text)
        assertEquals(3, reopened?.selectionStart)
        assertEquals(3, reopened?.selectionEnd)
    }

    @Test
    fun clearingRemovesDraft() {
        store.save(DraftStore.Draft("要清掉的", 2, 2))
        store.clear()
        assertNull(SharedPreferencesDraftStore(app, prefsName = testPrefs).load())
    }

    @Test
    fun emptyTextIsNotPersisted() {
        store.save(DraftStore.Draft("", 0, 0))
        assertNull(store.load())
    }

    @Test
    fun viewModelRestoresDraftOnCreation() {
        store.save(DraftStore.Draft("上次退出时的正文", 4, 4))

        val viewModel = DictationViewModel(app, store)

        assertEquals("上次退出时的正文", viewModel.editorValue.text)
        assertEquals(4, viewModel.editorValue.selection.start)
    }

    @Test
    fun deviceTestDoesNotTouchTheProductionDraftStore() {
        val production = SharedPreferencesDraftStore(app)
        val before = production.load()

        store.save(DraftStore.Draft("测试草稿", 1, 1))
        store.clear()

        val after = SharedPreferencesDraftStore(app).load()
        assertEquals("设备测试改动了生产草稿", before?.text, after?.text)
    }
}
