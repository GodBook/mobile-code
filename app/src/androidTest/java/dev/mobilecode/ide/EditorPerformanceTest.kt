package dev.mobilecode.ide

import android.os.Debug
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.editor.MobileLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.MobileCodeEditor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Same instrumentation runs against the old and new signed production APKs. */
class EditorPerformanceTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as MobileCodeApp
    private lateinit var model: IdeViewModel
    @Test fun lexicalFallbackScansOneMiBWithoutNativeMemoryGrowth() {
        val suffix="\ncompletion_target_needle = 1\ncompletion_target_n"
        val line="value_name = 12345\n"
        val size=1024*1024-suffix.length
        val source=line.repeat(size/line.length).padEnd(size,' ')+suffix
        val document=Document("src/fallback.py",source)
        // Explicitly exercise the fallback used before the real server is ready.
        val language=MobileLanguage(CodeLanguage.PYTHON,{ document },{ _,_->emptyList() },{})
        val published=CountDownLatch(1)
        val publisher=CompletionPublisher(Handler(Looper.getMainLooper()),{ published.countDown() },Language.INTERRUPTION_LEVEL_STRONG)
        try {
            val content=Content(source)
            val started=SystemClock.elapsedRealtime()
            language.requireAutoComplete(ContentReference(content),content.indexer.getCharPosition(source.length),publisher,Bundle())
            assertTrue("Fallback was not published",published.await(5,TimeUnit.SECONDS))
            val elapsed=SystemClock.elapsedRealtime()-started
            assertTrue(publisher.items.toString(),publisher.items.any { it.label.toString()=="completion_target_needle" })
            val memory=Debug.MemoryInfo().also(Debug::getMemoryInfo)
            File(app.getExternalFilesDir(null),"lexical-performance.json").writeText(gson.toJson(mapOf(
                "sourceBytes" to source.length,"elapsedMs" to elapsed,"appPssKiB" to memory.totalPss,"memoryStats" to memory.memoryStats)))
            assertTrue("Fallback scan stalled: ${elapsed}ms",elapsed<2000)
            assertTrue("Fallback scan exceeded 512MiB: ${memory.totalPss}KiB",memory.totalPss<512*1024)
        } finally { language.destroy() }
    }
    @Test fun measureLargeFileTypingTabRetentionAndIdleAllocations() {
        val previous=runBlocking { app.settings.flow.first() }
        try {
            val sourceBytes=InstrumentationRegistry.getArguments().getString("mcPerformanceBytes")?.toInt() ?: 1024*1024
            require(sourceBytes in 16*1024..1024*1024)
            lateinit var homing: Job
            rule.runOnUiThread {
                rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                model=ViewModelProvider(rule.activity)[IdeViewModel::class.java]; homing=model.home()
            }
            rule.waitUntil(30_000) { homing.isCompleted && model.state.value.project==null }
            val line="# memory and typing sample: abcdefghijklmnopqrstuvwxyz 0123456789 ..............................................\n"
            val source=line.repeat(sourceBytes/line.length+1).take(sourceBytes)
            val project=runBlocking {
                app.workspace.create("1MiB编辑性能验收",CodeLanguage.PYTHON,false).also { p ->
                    app.workspace.save(p,Document("src/main.py",source))
                    repeat(4) { app.workspace.createFile(p,"src/large$it.py"); app.workspace.save(p,Document("src/large$it.py",source)) }
                }
            }
            val opening=SystemClock.elapsedRealtimeNanos()
            rule.runOnUiThread { model.openProject(project) }
            rule.waitUntil(30_000) { model.state.value.project?.config?.id==project.config.id && model.state.value.document!=null }
            rule.waitForIdle()
            var editorWidth=0; var editorHeight=0
            rule.runOnUiThread { editorWidth=editor().width; editorHeight=editor().height }
            assertTrue("Editor has no visible viewport: ${editorWidth}x$editorHeight",editorWidth>0 && editorHeight>0)
            val openMs=(SystemClock.elapsedRealtimeNanos()-opening)/1_000_000.0
            android.util.Log.i("MobileCodePerformance","stage=open bytes=$sourceBytes ms=$openMs")
            val samples=mutableListOf<Double>()
            rule.runOnUiThread { editor().setSelection(0,2) }
            repeat(80) {
                val frame=CountDownLatch(1)
                var start=0L; var end=0L
                rule.runOnUiThread {
                    start=SystemClock.elapsedRealtimeNanos()
                    editor().insertText("x",1)
                    Choreographer.getInstance().postFrameCallback { end=SystemClock.elapsedRealtimeNanos(); frame.countDown() }
                }
                assertTrue("Editor frame timeout",frame.await(5,TimeUnit.SECONDS))
                samples+=(end-start)/1_000_000.0
            }
            android.util.Log.i("MobileCodePerformance","stage=typing samples=${samples.size} p95=${samples.sorted()[75]}")
            val closedEditors=mutableListOf<MobileCodeEditor>()
            repeat(4) { index ->
                val path="src/large$index.py"
                rule.runOnUiThread { model.openFile(path) }
                rule.waitUntil(30_000) { model.state.value.activePath==path }; rule.waitForIdle()
                rule.runOnUiThread { closedEditors+=editor(); model.closeFile(path) }
                rule.waitUntil(15_000) { model.state.value.activePath!=path }; rule.waitForIdle()
            }
            // Drop probe references before measuring memory.
            val released=closedEditors.count { it.isReleased }; closedEditors.clear()
            rule.waitUntil(15_000) { model.state.value.documents.values.none { it.dirty } }
            runBlocking { app.languages.close(); delay(300) }
            System.gc(); runBlocking { delay(500) }
            val memory=Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val before=Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            runBlocking { delay(1500) }
            val idleAllocated=Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()-before
            val version=app.packageManager.getPackageInfo(app.packageName,0)
            val sorted=samples.sorted()
            val result=mapOf("versionName" to version.versionName,"versionCode" to version.longVersionCode,
                "editorViewportPx" to listOf(editorWidth,editorHeight),
                "sourceBytes" to source.toByteArray().size,"openToIdleMs" to openMs,"inputToFrameCallbackMs" to samples,
                "inputToFrameP50Ms" to sorted[sorted.size/2],"inputToFrameP95Ms" to sorted[(sorted.size*0.95).toInt()-1],
                "closedEditors" to 4,"releasedEditors" to released,"appTotalPssKiB" to memory.totalPss,
                "javaHeapAllocatedBytes" to Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),
                "nativeHeapAllocatedBytes" to Debug.getNativeHeapAllocatedSize(),"memoryStats" to memory.memoryStats,
                "idleAllocatedBytesOver1500ms" to idleAllocated,
                "scope" to "Instrumented insert-to-next-Choreographer callback; includes instrumentation, excludes physical IME latency. PSS is app-only after GC, language worker stopped.")
            val output=File(app.getExternalFilesDir(null),"editor-performance-${version.versionName}-$sourceBytes.json")
            output.writeText(gson.toJson(result))
            android.util.Log.i("MobileCodePerformance",gson.toJson(result))
            assertEquals(sourceBytes,source.length)
            assertTrue("App PSS after closing tabs exceeded 512MiB: ${memory.totalPss}KiB",memory.totalPss<512*1024)
        } finally { runBlocking { app.settings.update(previous) } }
    }
    private fun editor(): MobileCodeEditor = findEditor(rule.activity.window.decorView) ?: error("Editor missing")
    private fun findEditor(view: View): MobileCodeEditor? {
        if(view is MobileCodeEditor) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) findEditor(view.getChildAt(i))?.let { return it }
        return null
    }
}
