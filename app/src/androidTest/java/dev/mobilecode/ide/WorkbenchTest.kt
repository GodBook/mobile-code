package dev.mobilecode.ide

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.os.Debug
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.editor.NativeSyntax
import io.github.rosemoe.sora.widget.MobileCodeEditor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WorkbenchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as MobileCodeApp
    private lateinit var model: IdeViewModel

    @Test fun createEditComposeUndoFoldAutosaveAndRunFromWorkbench() {
        val old = runBlocking { app.settings.flow.first() }
        try {
            lateinit var homing: Job
            rule.runOnUiThread {
                rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                model = ViewModelProvider(rule.activity)[IdeViewModel::class.java]
                homing=model.home()
            }
            rule.waitUntil(30_000) { homing.isCompleted && model.state.value.project==null }
            rule.onNodeWithTag("new-project").performClick()
            rule.onNodeWithTag("project-name").performTextReplacement("手机编辑验收")
            rule.onNodeWithTag("language-PYTHON").performClick()
            rule.onNodeWithTag("create-project").performClick()
            rule.waitUntil(15_000) { model.state.value.document!=null }
            rule.onNodeWithTag("code-editor").assertExists()
            rule.runOnUiThread {
                val editor = findEditor(rule.activity.window.decorView)!!
                val original=editor.text.toString()
                repeat(30) { editor.setText("x"); editor.setText(original) }
            }
            rule.onNodeWithTag("code-editor").performTouchInput { click() }
            rule.waitUntil(5_000) {
                var ready=false
                rule.runOnUiThread { findEditor(rule.activity.window.decorView)?.let { ready=it.isEditable && it.isEnabled && it.hasFocus() && it.hasWindowFocus() } }
                ready
            }
            rule.runOnUiThread {
                val editor = findEditor(rule.activity.window.decorView)!!
                editor.requestFocus()
                editor.setSelection(0,0)
                val input = editor.onCreateInputConnection(EditorInfo())!!
                assertTrue(input.setComposingText("# 中",1))
                assertTrue(input.setComposingText("# 中文😀",1))
                assertTrue(input.finishComposingText())
                assertTrue(input.commitText("\n",1))
            }
            // Sora dispatches Enter as a key event; observe the resulting document.
            try {
                rule.waitUntil(5_000) { model.state.value.document?.text?.startsWith("# 中文😀\n")==true }
            } catch(error: Throwable) {
                rule.runOnUiThread {
                    val editor=findEditor(rule.activity.window.decorView)!!
                    android.util.Log.e("MobileCodeImeTest","window=${editor.hasWindowFocus()} focus=${editor.hasFocus()} editor=${editor.text.toString().take(90)} model=${model.state.value.document?.text?.take(90)}")
                }
                throw error
            }
            rule.runOnUiThread {
                val editor = findEditor(rule.activity.window.decorView)!!
                val text = editor.text.toString()
                assertTrue(text, text.startsWith("# 中文😀\n"))
                editor.undo(); editor.redo()
                assertEquals(text, editor.text.toString())
                editor.setFoldRanges(intArrayOf(2),intArrayOf(3))
                editor.toggleFold(2)
                assertTrue(editor.isLineHidden(3))
                assertEquals(text, editor.text.toString())
                editor.unfoldAll()
                assertFalse(editor.isLineHidden(3))
            }
            rule.waitUntil(10_000) { model.state.value.document?.let { it.text.startsWith("# 中文😀\n") && !it.dirty }==true }
            val project = model.state.value.project!!
            val path = model.state.value.activePath
            assertTrue(runBlocking { app.workspace.open(project,path).text }.startsWith("# 中文😀\n"))
            rule.waitUntil(240_000) { model.toolchains.value.toolchain!=null }
            if(android.os.Build.VERSION.SDK_INT>=33) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation.grantRuntimePermission(app.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
            val previous = model.session.value.id
            rule.onNodeWithTag("运行").performClick()
            rule.waitUntil(30_000) { model.session.value.id!=null && model.session.value.id!=previous && !model.session.value.active }
            assertEquals(model.session.value.output, SessionPhase.FINISHED, model.session.value.phase)
            assertTrue(model.session.value.output, model.session.value.standardOutput.contains("Hello, Mobile Code!"))
        } finally {
            runBlocking { app.sessions.stop(); app.settings.update(old) }
        }
    }

    @Test fun fourIncrementalGrammarsAcceptUnicodeAndIncompleteSource() {
        val samples = listOf(
            "// 中文😀\nint main(){ return 8; }\n",
            "// 中文😀\nclass Person { public: int count=8; };\n",
            "# 中文😀\ndef add(a,b):\n return a+b\n",
            "// 中文😀\nclass Main { static int value=8; }\n"
        )
        samples.forEachIndexed { language, source ->
            val handle = NativeSyntax.create(language)
            assertNotEquals(0L,handle)
            try {
                val spans = NativeSyntax.parse(handle,source)
                assertTrue("language=$language",spans.isNotEmpty())
                assertEquals(0,spans.size%3)
                assertTrue(NativeSyntax.parse(handle,source+"\n\"unfinished 中😀").isNotEmpty())
                assertTrue(NativeSyntax.parse(handle,source.replace("8","18")).isNotEmpty())
            } finally { NativeSyntax.destroy(handle) }
        }
    }

    @Test fun visibleOutputFloodKeepsEditorResponsiveAndMemoryBounded() {
        lateinit var homing: Job
        rule.runOnUiThread {
            rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model=ViewModelProvider(rule.activity)[IdeViewModel::class.java]; homing=model.home()
        }
        rule.waitUntil(30_000) { homing.isCompleted && model.state.value.project==null }
        val project=runBlocking {
            app.workspace.create("可见控制台压力验收",CodeLanguage.PYTHON,false).also {
                app.workspace.save(it,Document("src/main.py","import sys\nsys.stdout.buffer.write(b'x'*(12*1024*1024))\nsys.stdout.flush()\n"))
            }
        }
        lateinit var opening: Job
        rule.runOnUiThread { opening=model.openProject(project) }
        rule.waitUntil(30_000) { opening.isCompleted && model.state.value.project?.config?.id==project.config.id }
        rule.waitForIdle()
        rule.waitUntil(240_000) { model.toolchains.value.toolchain!=null }
        if(android.os.Build.VERSION.SDK_INT>=33) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.grantRuntimePermission(app.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        val previousId=model.session.value.id
        rule.onNodeWithTag("运行").performClick()
        rule.waitUntil(30_000) { model.session.value.id!=null && model.session.value.id!=previousId && !model.session.value.active }
        assertEquals(model.session.value.output.takeLast(1000),SessionPhase.FINISHED,model.session.value.phase)
        assertTrue(model.session.value.standardOutput.length>=128*1024)
        val rendered=CountDownLatch(1); var start=0L; var end=0L
        rule.runOnUiThread {
            val editor=findEditor(rule.activity.window.decorView)!!
            editor.setSelection(0,0); start=SystemClock.elapsedRealtimeNanos()
            editor.insertText("# still responsive\n",19)
            Choreographer.getInstance().postFrameCallback { end=SystemClock.elapsedRealtimeNanos(); rendered.countDown() }
        }
        assertTrue("Output flood stalled the editor",rendered.await(2,TimeUnit.SECONDS))
        rule.waitForIdle(); System.gc(); runBlocking { delay(500) }
        val memory=Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val result=mapOf("appPssKiB" to memory.totalPss,"memoryStats" to memory.memoryStats,
            "inputToFrameMs" to (end-start)/1_000_000.0,"outputBytes" to 12*1024*1024)
        File(app.getExternalFilesDir(null),"console-performance.json").writeText(gson.toJson(result))
        assertTrue("Visible console retained excessive memory: ${memory.totalPss}KiB",memory.totalPss<512*1024)
    }

    private fun findEditor(view: View): MobileCodeEditor? {
        if(view is MobileCodeEditor) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) findEditor(view.getChildAt(index))?.let { return it }
        return null
    }
}
