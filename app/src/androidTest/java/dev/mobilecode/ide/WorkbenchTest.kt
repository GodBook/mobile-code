package dev.mobilecode.ide

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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

class WorkbenchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as MobileCodeApp
    private lateinit var model: IdeViewModel

    @Test fun createEditComposeUndoFoldAutosaveAndRunFromWorkbench() {
        val old = runBlocking { app.settings.flow.first() }
        try {
            rule.runOnUiThread {
                rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                model = ViewModelProvider(rule.activity)[IdeViewModel::class.java]
                model.home()
            }
            rule.waitUntil(10_000) { model.state.value.project==null }
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
            rule.waitUntil(5_000) {
                var ready=false
                rule.runOnUiThread { findEditor(rule.activity.window.decorView)?.let { ready=it.isEditable && it.isEnabled } }
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
            rule.waitUntil(5_000) { model.state.value.document?.text?.startsWith("# 中文😀\n")==true }
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

    private fun findEditor(view: View): MobileCodeEditor? {
        if(view is MobileCodeEditor) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) findEditor(view.getChildAt(index))?.let { return it }
        return null
    }
}
