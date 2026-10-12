package dev.mobilecode.ide

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.editor.*
import io.github.rosemoe.sora.widget.MobileCodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File

class EditorAppearanceTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as MobileCodeApp
    private lateinit var model: IdeViewModel
    @Before fun prepare() {
        lateinit var homing: Job
        rule.runOnUiThread {
            rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model=ViewModelProvider(rule.activity)[IdeViewModel::class.java]
            homing=model.home()
        }
        rule.waitUntil(30_000) { homing.isCompleted && model.state.value.project==null }
    }
    @After fun cleanup() { runBlocking { app.sessions.stop() } }

    @Test fun syntaxRolesMatchReferenceAndRemainOrderedWithUnicode() {
        val samples=listOf(
            "// 中文😀\n#include <stdio.h>\nstatic int add(int n) { return n+8; }\nint main() { printf(\"hello\"); return add(1); }\n",
            SAMPLE,
            "# 中文😀\nclass Animal:\n    def speak(self):\n        return \"hello\"\nvalue = 8\nprint(Animal().speak())\n",
            "// 中文😀\nclass Animal { public static void speak() { System.out.println(\"hello\"); } int age=8; }\n"
        )
        samples.forEachIndexed { language,source ->
            val handle=NativeSyntax.create(language)
            try {
                fun check(text: String) {
                    val bytes=text.toByteArray()
                    val spans=NativeSyntax.parse(handle,text)
                    var end=0
                    val roles=mutableSetOf<Int>()
                    for(i in spans.indices step 3) {
                        assertTrue("Overlapping/out-of-order language=$language",spans[i]>=end)
                        assertTrue(spans[i+1]>spans[i] && spans[i+1]<=bytes.size)
                        end=spans[i+1]; roles+=spans[i+2]
                    }
                    assertTrue("Missing roles language=$language: $roles",roles.containsAll(listOf(1,2,3,4,6,9,12)))
                    if(language==1) assertTrue(roles.containsAll(listOf(5,7,8,13,14)))
                }
                check(source)
                check(source.replace("8","18")+"\n// incomplete 中文😀 {")
                check(source)
            } finally { NativeSyntax.destroy(handle) }
        }
    }

    @Test fun lightAndDarkSelectionPreservesTextUndoAndRestoresAfterRecreation() {
        val previous=runBlocking { app.settings.flow.first() }
        try {
            val project=runBlocking {
                app.workspace.create("代码配色与主题",CodeLanguage.CPP,false).also { app.workspace.save(it,Document("src/main.cpp",SAMPLE)) }
            }
            rule.runOnUiThread { model.openProject(project) }
            waitForFile("src/main.cpp")
            rule.onNodeWithTag("折叠控制台").performClick()
            lateinit var original: MobileCodeEditor
            rule.runOnUiThread { original=editor(); original.setSelection(0,0); original.insertText("// theme undo\n",14) }
            val edited=model.state.value.document!!.text
            chooseTheme(false)
            rule.runOnUiThread {
                assertSame(original,editor()); assertFalse(editor().colorScheme.isDark)
                assertEquals(0xFFFAFAF8.toInt(),editor().colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
                assertEquals(edited,editor().text.toString()); assertTrue(editor().canUndo())
                editor().undo(); assertEquals(SAMPLE,editor().text.toString()); editor().redo()
            }
            screenshot("appearance-light.png")
            rule.activityRule.scenario.recreate()
            rule.runOnUiThread { model=ViewModelProvider(rule.activity)[IdeViewModel::class.java] }
            waitForFile("src/main.cpp")
            rule.runOnUiThread { original=editor(); assertFalse(original.colorScheme.isDark); assertEquals(edited,original.text.toString()) }
            chooseTheme(true)
            rule.runOnUiThread {
                assertSame(original,editor()); assertTrue(editor().colorScheme.isDark)
                assertEquals(0xFF111213.toInt(),editor().colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
                assertEquals(0xFFFF574C.toInt(),editor().colorScheme.getColor(EditorColorScheme.KEYWORD))
                assertEquals(0xFF00DFCE.toInt(),editor().colorScheme.getColor(SyntaxStyle.TYPE))
            }
            rule.waitUntil(10_000) { var ready=false; rule.runOnUiThread { ready=editor().styles?.spans!=null }; ready }
            screenshot("appearance-dark.png")
            rule.activityRule.scenario.recreate()
            rule.runOnUiThread { model=ViewModelProvider(rule.activity)[IdeViewModel::class.java] }
            waitForFile("src/main.cpp")
            rule.runOnUiThread { assertTrue(editor().colorScheme.isDark); assertEquals(edited,editor().text.toString()) }
        } finally { runBlocking { app.settings.update(previous) } }
    }

    @Test fun closedTabsAndReplacedSnapshotsReleaseEditorResources() {
        val project=runBlocking {
            app.workspace.create("编辑器资源回收",CodeLanguage.PYTHON,false).also { p ->
                repeat(5) { app.workspace.createFile(p,"src/tab$it.py"); app.workspace.save(p,Document("src/tab$it.py","# 中文😀\nvalue=$it\n")) }
            }
        }
        rule.runOnUiThread { model.openProject(project) }; waitForFile("src/main.py")
        repeat(5) { index ->
            val path="src/tab$index.py"
            rule.runOnUiThread { model.openFile(path) }; waitForFile(path)
            lateinit var closed: MobileCodeEditor
            rule.runOnUiThread { closed=editor(); model.closeFile(path) }
            rule.waitUntil(10_000) { var released=false; rule.runOnUiThread { released=closed.isReleased }; released }
        }
        var previous: MobileCodeEditor?=null
        repeat(3) { index ->
            val source=File(app.cacheDir,"appearance-snapshot-$index.py").apply { writeText("# snapshot $index\nprint($index)\n") }
            rule.runOnUiThread { model.diagnostic(Diagnostic(source.absolutePath,SourceRange(Position(0,0),Position(0,1)),"preview")) }
            rule.waitUntil(10_000) { model.state.value.preview?.document?.path==source.absolutePath }
            rule.waitForIdle()
            rule.runOnUiThread { previous?.let { assertTrue(it.isReleased) }; previous=editor() }
        }
        rule.runOnUiThread { model.closePreview() }; rule.waitForIdle()
        rule.runOnUiThread { assertTrue(previous!!.isReleased) }
    }

    @Test fun semanticCompletionRestartsAfterMemoryReleaseAndRejectsOlderVersions() = runBlocking {
        val chain=withTimeout(240_000) { app.toolchains.prepare() }
        val project=app.workspace.create("低内存后恢复补全",CodeLanguage.PYTHON,false)
        val document=Document("src/main.py","import math\nmath.sq",version=2)
        app.workspace.save(project,document)
        app.languages.activate(project,chain); app.languages.change(document)
        app.languages.close()
        val completions=app.languages.complete(document,Position(1,7))
        assertTrue(completions.toString(),completions.any { it.label.startsWith("sqrt") })
        app.languages.change(document.copy(text="invalid_old_document",version=1))
        assertTrue(app.languages.complete(document,Position(1,7)).any { it.label.startsWith("sqrt") })
        app.languages.close()
    }

    private fun chooseTheme(dark: Boolean) {
        rule.onNodeWithTag("更多").performClick(); rule.onNodeWithText("设置").performClick()
        rule.onNodeWithTag(if(dark) "theme-dark" else "theme-light").performClick()
        rule.onNodeWithText("保存",useUnmergedTree=true).performClick()
        rule.waitUntil(10_000) { model.settings.value.dark==dark }
        rule.waitForIdle()
    }
    private fun waitForFile(path: String) {
        rule.waitUntil(15_000) { model.state.value.activePath==path }
        rule.waitForIdle()
    }
    private fun editor(): MobileCodeEditor = findEditor(rule.activity.window.decorView) ?: error("Editor missing")
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    private fun findEditor(view: View): MobileCodeEditor? {
        if(view is MobileCodeEditor) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) findEditor(view.getChildAt(i))?.let { return it }
        return null
    }
    companion object {
        val SAMPLE="""// 中文注释 · 参考图配色 😀
#include <iostream>
#include <string>
#define LOG(msg) std::cout << msg

enum class LogLevel { Info, Warning, Error };
class Animal {
public:
    virtual void speak() const = 0;
};
class Dog : public Animal {
private:
    std::string name;
    int age = 8;
public:
    explicit Dog(std::string name) : name(name) {}
    void speak() const override {
        std::cout << "Hello, " << name << (age+1);
    }
};
int main() {
    Dog dog("Mobile Code");
    dog.speak();
    return 0;
}
"""
    }
}
