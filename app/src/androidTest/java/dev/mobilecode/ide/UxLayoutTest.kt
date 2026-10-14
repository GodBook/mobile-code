package dev.mobilecode.ide

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.EditorSettings
import io.github.rosemoe.sora.widget.MobileCodeEditor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File

class UxLayoutTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as MobileCodeApp
    private lateinit var model: IdeViewModel
    private lateinit var old: EditorSettings
    @Before fun prepare() {
        old=runBlocking { app.settings.flow.first() }
        rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); model=ViewModelProvider(rule.activity)[IdeViewModel::class.java] }
        runBlocking { app.sessions.stop() }
        action { it.home() }
    }
    @After fun finish() { runBlocking { app.sessions.stop() }; action { it.home() }; runBlocking { app.settings.update(old) } }
    private fun action(block: (IdeViewModel)->Job) { lateinit var job: Job; rule.runOnUiThread { job=block(model) }; runBlocking { withTimeout(45_000) { job.join() } }; rule.waitForIdle() }
    private fun editor(view: View=rule.activity.window.decorView): MobileCodeEditor? {
        if(view is MobileCodeEditor) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) editor(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    @Test fun homeSearchFilterPinAndTreeKeepChineseProjectAccessible() {
        action { it.createProject("布局筛选验收中文😀 ${java.util.UUID.randomUUID().toString().take(6)}",CodeLanguage.PYTHON,true) }
        val project=model.state.value.project!!
        action { it.pin(project) }; action { it.home() }
        rule.onNodeWithTag("project-search").performTextReplacement(project.config.name)
        rule.onNodeWithTag("project-${project.config.id}").assertExists()
        rule.onNodeWithText("Java",useUnmergedTree=true).performScrollTo().performClick()
        rule.onNodeWithText("没有匹配的项目").assertExists()
        rule.onNodeWithText("全部",useUnmergedTree=true).performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("project-${project.config.id}").fetchSemanticsNodes().isNotEmpty() }
        screenshot("ux-home.png")
        rule.onNodeWithTag("project-${project.config.id}").performClick()
        rule.waitUntil(15_000) { model.state.value.project?.config?.id==project.config.id }
        rule.onNodeWithTag("项目文件").performClick()
        rule.onNodeWithText("src",useUnmergedTree=true).assertExists()
        screenshot("ux-tree.png")
        rule.onNodeWithText("utils.py",useUnmergedTree=true).performClick()
        rule.waitUntil(15_000) { model.state.value.activePath=="src/utils.py" }
    }
    @Test fun systemThemeAndLandscapeKeepNonzeroEditorAndFullPanels() {
        action { it.createProject("横屏主题体验验收",CodeLanguage.PYTHON,true) }
        action { it.updateSettings(old.copy(followSystem=true,consoleFontSize=15f)) }
        assertTrue(runBlocking { app.settings.flow.first() }.followSystem)
        rule.runOnUiThread { rule.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(15_000) { rule.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        rule.runOnUiThread { model=ViewModelProvider(rule.activity)[IdeViewModel::class.java] }
        rule.waitUntil(15_000) { rule.activity.window.decorView.let { it.width>it.height } && editor()?.let { it.width>0 && it.height>0 }==true }
        screenshot("ux-landscape.png")
        rule.onNodeWithTag("展开控制台").performClick()
        rule.onNodeWithTag("output-expanded").assertExists()
        rule.onAllNodesWithTag("折叠控制台").onLast().performClick()
        rule.waitUntil(10_000) { editor()?.height?.let { it>0 }==true }
        rule.runOnUiThread { rule.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitUntil(15_000) { rule.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT }
    }
}
