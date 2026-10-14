package dev.mobilecode.ide

import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.*
import io.github.rosemoe.sora.widget.MobileCodeEditor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Real on-device flows. Fixtures never remove pre-existing projects or session directories. */
class UxUpgradeTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as MobileCodeApp
    private lateinit var model: IdeViewModel
    private lateinit var oldSettings: EditorSettings

    @Before fun prepare() {
        oldSettings=runBlocking { app.settings.flow.first() }
        rule.runOnUiThread {
            rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model=ViewModelProvider(rule.activity)[IdeViewModel::class.java]
        }
        runBlocking { app.sessions.stop() }
        perform { it.home() }
        rule.waitUntil(240_000) { model.toolchains.value.toolchain!=null }
        if(android.os.Build.VERSION.SDK_INT>=33) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun finish() {
        runBlocking { app.sessions.stop() }
        perform(checkError=false) { it.home() }
        runBlocking { app.settings.update(oldSettings) }
    }
    private fun perform(checkError: Boolean=true,action: (IdeViewModel)->Job) {
        lateinit var job: Job
        rule.runOnUiThread { model.clearError(); job=action(model) }
        runBlocking { withTimeout(90_000) { job.join() } }
        rule.waitForIdle()
        if(checkError) assertNull(model.state.value.error,model.state.value.error)
    }
    private fun project(language: CodeLanguage=CodeLanguage.PYTHON,multiple: Boolean=false): Project {
        perform { it.createProject("体验验收 ${language.title} ${UUID.randomUUID().toString().take(6)}",language,multiple) }
        return model.state.value.project!!
    }
    private fun change(text: String) {
        rule.runOnUiThread { val doc=model.state.value.document!!; model.edit(doc.path,text,0,text.count { it=='\n' }-doc.text.count { it=='\n' },doc.text.count { it=='\n' }) }
    }
    private fun editor(view: View=rule.activity.window.decorView): MobileCodeEditor? {
        if(view is MobileCodeEditor) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) editor(view.getChildAt(index))?.let { return it }
        return null
    }

    @Test fun failedSaveKeepsBufferAndExportThenRetryAndRecovery() {
        val project=project(); val path=model.state.value.activePath
        val file=resolveInside(project.directory,path); val original=file.readText()
        val backup=File(app.cacheDir,"save-original-${UUID.randomUUID()}")
        Files.move(file.toPath(),backup.toPath()); assertTrue(file.mkdir())
        val edited="# 未保存中文😀\nprint('preserved')\n"
        try {
            change(edited)
            perform(checkError=false) { it.flush() }
            rule.waitUntil(15_000) { model.state.value.saveStates[path]?.phase=="failed" }
            assertTrue(model.document(path)!!.dirty); assertEquals(original,backup.readText())
            assertEquals(edited,runBlocking { app.journal.drafts(project) }.single { it.path==path }.text)
            val oldSession=app.sessions.state.value.id
            perform(checkError=false) { it.run(false) }
            assertEquals(oldSession,app.sessions.state.value.id)
            val exported=File(app.cacheDir,"unsaved-${UUID.randomUUID()}.py")
            rule.runOnUiThread { model.prepareBufferExport(path) }
            perform { it.exportSource(Uri.fromFile(exported)) }
            assertEquals(edited,exported.readText())
            assertTrue(file.delete()); Files.move(backup.toPath(),file.toPath())
            perform { it.retrySave(path) }
            assertFalse(model.document(path)!!.dirty); assertEquals(edited,file.readText())
            val draftText="# 恢复候选😀\nprint('recover')\n"
            runBlocking { app.journal.draft(project,Document(path,draftText,version=17)) }
            perform { it.openProject(project) }
            val draft=model.state.value.recoveries.single()
            assertEquals(edited,file.readText())
            perform { it.recover(draft) }
            perform { it.flush() }
            assertEquals(draftText,file.readText())
            assertTrue(runBlocking { app.journal.drafts(project) }.isEmpty())
        } finally {
            if(backup.isFile) { if(file.isDirectory) file.delete(); Files.move(backup.toPath(),file.toPath()) }
        }
    }

    @Test fun unreadableProjectsAndMixedImportsRemainRecoverable() = runBlocking {
        val baseline=app.workspace.projects().map { it.config.id }.toSet()
        val id=UUID.randomUUID().toString(); val broken=File(app.workspace.projectsRoot,id).apply { mkdirs() }
        AtomicFiles.text(File(broken,"project.json"),"{")
        AtomicFiles.text(File(broken,"src/main.py"),"print('original')\n")
        assertFalse(app.workspace.projects().any { it.config.id==id })
        val problem=app.workspace.problems.value.single { it.directory.name==id }
        val exported=File(app.cacheDir,"broken-$id.zip")
        app.workspace.exportDirectory(problem.directory,Uri.fromFile(exported))
        ZipFile(exported).use { zip -> assertEquals("print('original')\n",zip.getInputStream(zip.getEntry("src/main.py")).bufferedReader().readText()) }
        AtomicFiles.text(File(broken,"project.json"),gson.toJson(ProjectConfig(id=id,name="已恢复异常项目",language=CodeLanguage.PYTHON)))
        assertTrue(app.workspace.projects().any { it.config.id==id })
        val mixed=File(app.cacheDir,"mixed-$id.zip")
        ZipOutputStream(mixed.outputStream()).use { zip ->
            mapOf("src/main.py" to "print('mixed')\n","src/Main.java" to "class Main {}\n").forEach { (path,text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        try { app.workspace.importProject(Uri.fromFile(mixed)); fail("Expected explicit language choice") } catch(choice: ImportLanguageChoice) { assertTrue(choice.candidates.containsAll(listOf(CodeLanguage.PYTHON,CodeLanguage.JAVA))) }
        val imported=app.workspace.importProject(Uri.fromFile(mixed),CodeLanguage.PYTHON)
        assertEquals(CodeLanguage.PYTHON,imported.config.language)
        assertEquals("print('mixed')\n",app.workspace.open(imported,"src/main.py").text)
        assertTrue(app.workspace.projects().map { it.config.id }.containsAll(baseline))
    }

    @Test fun cancelledImportLeavesExistingProjectsAndMarksTemporaryDirectory() = runBlocking {
        val baseline=app.workspace.projects().associate { it.config.id to sha256(File(it.directory,"project.json").readBytes()) }
        val archive=File(app.cacheDir,"cancel-${UUID.randomUUID()}.zip")
        ZipOutputStream(archive.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("src/main.py")); val block=ByteArray(256*1024) { 32 }; repeat(100) { zip.write(block) }; zip.closeEntry() }
        val job=launch(Dispatchers.IO) { app.workspace.importProject(Uri.fromFile(archive)) }
        withTimeout(15_000) { app.workspace.importProgress.first { it.running } }
        job.cancelAndJoin()
        assertFalse(app.workspace.importProgress.value.running)
        baseline.forEach { (id,hash) -> assertEquals(hash,sha256(File(app.workspace.projectsRoot,"$id/project.json").readBytes())) }
        assertTrue(app.workspace.projectsRoot.listFiles().orEmpty().any { it.name.startsWith(".import-") && File(it,".failed-import").isFile })
    }

    @Test fun fileMoveRecycleAndProjectCopyPreserveSourceAndBreakpoints() = runBlocking {
        val project=app.workspace.create("移动恢复验收",CodeLanguage.PYTHON,true)
        app.workspace.saveBreakpoints(project,listOf(Breakpoint("src/main.py",2)))
        val hash=sha256(File(project.directory,"src/main.py").readBytes())
        try { app.workspace.moveFile(project,"src/main.py","src/utils.py"); fail("Conflict must be rejected") } catch(_: IllegalArgumentException) { }
        assertEquals(hash,sha256(File(project.directory,"src/main.py").readBytes()))
        val moved=app.workspace.moveFile(project,"src/main.py","src/中文😀/entry.py")
        assertEquals("src/中文😀/entry.py",moved.config.entry)
        assertEquals("src/中文😀/entry.py",app.workspace.breakpoints(moved).single().path)
        val fileTrash=app.workspace.recycle(moved,moved.config.entry)
        assertFalse(File(moved.directory,moved.config.entry).exists())
        app.workspace.restore(fileTrash)
        assertEquals(hash,sha256(File(moved.directory,moved.config.entry).readBytes()))
        val copy=app.workspace.copyProject(moved,"副本")
        assertNotEquals(moved.config.id,copy.config.id)
        val renamed=app.workspace.renameProject(copy,"重命名副本")
        val projectTrash=app.workspace.recycle(renamed)
        assertFalse(app.workspace.projects().any { it.config.id==renamed.config.id })
        app.workspace.restore(projectTrash)
        val restored=app.workspace.projects().single { it.config.id==renamed.config.id }
        assertEquals("重命名副本",restored.config.name)
        assertEquals(hash,sha256(File(restored.directory,restored.config.entry).readBytes()))
    }

    @Test fun tabsLocationsWatchesAndPinnedProjectsRestoreAfterRecreation() {
        val project=project(multiple=true)
        perform { it.openFile("src/utils.py") }
        rule.runOnUiThread { editor()!!.setSelection(1,2); model.addWatch("result") }
        rule.waitUntil(15_000) { model.location("src/utils.py").let { it.line==1 && it.column==2 } }
        perform { it.flush() }
        perform { it.pin(project) }
        assertTrue(runBlocking { app.journal.project(project.config.id) }.pinned)
        val expectedTabs=model.state.value.documents.keys.toList()
        rule.activityRule.scenario.recreate()
        rule.runOnUiThread { model=ViewModelProvider(rule.activity)[IdeViewModel::class.java] }
        rule.waitUntil(30_000) { model.state.value.project?.config?.id==project.config.id && model.state.value.documents.size==expectedTabs.size }
        assertEquals(expectedTabs,model.state.value.documents.keys.toList())
        assertEquals("src/utils.py",model.state.value.activePath)
        assertTrue(model.state.value.watchExpressions.contains("result"))
        assertEquals(1,model.location("src/utils.py").line)
        rule.waitForIdle()
        rule.runOnUiThread { assertEquals(1,editor()!!.cursor.leftLine); assertEquals(2,editor()!!.cursor.leftColumn) }
        assertEquals(2,model.location("src/utils.py").column)
    }

    @Test fun quickOpenInlineSearchAndProjectSearchUseUnicodeBuffer() {
        val project=project(multiple=true)
        perform { it.createFile("src/目录😀/中文.py") }
        change("# 中文😀\nvalue='中文'\nprint(value)\n")
        val results=runBlocking { app.workspace.search(project,"中文",model.state.value.documents) }
        assertEquals(2,results.count { it.path=="src/目录😀/中文.py" })
        rule.runOnUiThread { rule.activity.onKeyShortcut(KeyEvent.KEYCODE_P,KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_P,0,KeyEvent.META_CTRL_ON)) }
        rule.onNodeWithTag("quick-open-query").performTextInput("utils.py")
        rule.onNodeWithText("src/utils.py").performClick()
        rule.waitUntil(10_000) { model.state.value.activePath=="src/utils.py" }
        rule.runOnUiThread { model.uiCommand("find") }
        rule.onNodeWithTag("inline-find").performTextReplacement("add")
        rule.waitUntil(10_000) { editor()?.searcher?.let { it.hasQuery() && it.matchedPositionCount==1 }==true }
        rule.onNodeWithContentDescription("关闭查找").performClick()
        rule.runOnUiThread { model.goToLine(2) }
        rule.waitUntil(10_000) { editor()?.cursor?.leftLine==1 }
    }

    @Test fun inputAcknowledgmentEofAndClosedPipeKeepDraft() {
        project()
        change("import sys\nprint('ready',flush=True)\nvalue=sys.stdin.readline()\nprint(value,flush=True)\n")
        perform { it.flush() }; perform { it.run(false) }
        rule.waitUntil(30_000) { model.session.value.canSendInput }
        if(rule.onAllNodesWithTag("output-collapsed").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("展开控制台").performClick()
        rule.onNodeWithTag("stdin").performTextReplacement("中文😀")
        var bounds: androidx.compose.ui.geometry.Rect?=null
        var settledAt=android.os.SystemClock.elapsedRealtime()
        // IME insets move the compact panel into a dialog; wait for its actual touch target.
        rule.waitUntil(15_000) {
            val next=rule.onNodeWithTag("send-stdin").fetchSemanticsNode().boundsInWindow
            if(next!=bounds) { bounds=next; settledAt=android.os.SystemClock.elapsedRealtime() }
            android.os.SystemClock.elapsedRealtime()-settledAt>=500
        }
        rule.onNodeWithTag("send-stdin").assertIsEnabled().assertIsDisplayed().performClick()
        try { rule.waitUntil(15_000) { model.session.value.standardOutput.contains("中文😀") && !model.session.value.active } }
        catch(error: ComposeTimeoutException) { throw AssertionError("Input result: ${model.session.value}; UI error: ${model.state.value.error}",error) }
        rule.onNodeWithTag("stdin").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        rule.onNodeWithTag("stdin").performTextReplacement("保留草稿")
        rule.onNodeWithTag("send-stdin").assertIsNotEnabled()
        var acknowledged=false
        perform(checkError=false) { it.input("should-fail") { acknowledged=true } }
        assertFalse(acknowledged)
        rule.onNodeWithTag("stdin").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("保留草稿")))
        change("import sys\nprint('ready',flush=True)\nprint(sys.stdin.read(),flush=True)\n")
        perform { it.flush() }; perform { it.run(false) }
        rule.waitUntil(30_000) { model.session.value.canSendInput }
        rule.runOnUiThread { model.eof(); model.eof() }
        rule.waitUntil(15_000) { !model.session.value.active }
        assertFalse(model.session.value.inputOpen)
        assertEquals(SessionPhase.FINISHED,model.session.value.phase)
    }

    @Test fun cDefinitionReferencesAndOutline()=navigation(CodeLanguage.C)
    @Test fun cppDefinitionReferencesAndOutline()=navigation(CodeLanguage.CPP)
    @Test fun pythonDefinitionReferencesAndOutline()=navigation(CodeLanguage.PYTHON)
    @Test fun javaDefinitionReferencesAndOutline()=navigation(CodeLanguage.JAVA)
    private fun navigation(language: CodeLanguage) {
        project(language,true)
        val document=model.state.value.document!!
        val index=document.text.lastIndexOf("add(")+1
        assertTrue(document.text,index>0)
        val position=Positions.position(document.text,index)
        runBlocking {
            withTimeout(90_000) { app.languages.status.first { it.contains("已就绪") } }
            var definitions=emptyList<CodeLocation>()
            withTimeout(30_000) { while(definitions.isEmpty()) { definitions=app.languages.locations(document,position,"textDocument/definition"); if(definitions.isEmpty()) delay(200) } }
            assertTrue(definitions.toString(),definitions.any { File(it.path).name.contains("utils",ignoreCase=true) })
            var references=emptyList<CodeLocation>()
            withTimeout(45_000) { while(references.isEmpty()) { references=app.languages.locations(document,position,"textDocument/references"); if(references.isEmpty()) delay(200) } }
            assertTrue(references.toString(),references.any { File(it.path).name==File(document.path).name })
            var symbols=emptyList<CodeSymbol>()
            withTimeout(30_000) { while(symbols.isEmpty()) { symbols=app.languages.symbols(document); if(symbols.isEmpty()) delay(200) } }
            assertTrue(symbols.toString(),symbols.any { it.name.contains("main",ignoreCase=true) })
        }
    }

    @Test fun hundredRealRunsHaveSafeStorageCleanupAndPreserveExistingSessions() = runBlocking {
        val project=app.workspace.create("100次真实运行存储验收",CodeLanguage.PYTHON,false)
        app.workspace.save(project,Document("src/main.py","print('storage-cycle')\n"))
        val hash=sha256(File(project.directory,"src/main.py").readBytes())
        val existing=app.workspace.sessionsRoot.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
        val chain=app.toolchains.status.value.toolchain!!
        val start=android.os.SystemClock.elapsedRealtime()
        repeat(100) {
            val id=app.sessions.start(project,chain,false,emptyList(),null)
            val finished=withTimeout(15_000) { app.sessions.state.first { it.id==id && !it.active } }
            assertEquals(finished.output,SessionPhase.FINISHED,finished.phase)
            assertTrue(finished.standardOutput.contains("storage-cycle"))
        }
        val protected=existing+app.sessions.snapshot!!.id
        val inventory=app.workspace.storage(protected,chain.root)
        assertTrue(inventory.first { it.id=="sessions" }.bytes>0)
        app.workspace.clean("sessions",protected,chain.root)
        assertEquals(0L,app.workspace.storage(protected,chain.root).first { it.id=="sessions" }.bytes)
        existing.forEach { assertTrue("Existing session removed: $it",File(app.workspace.sessionsRoot,it).isDirectory) }
        assertTrue(app.sessions.snapshot!!.source.isDirectory)
        assertEquals(hash,sha256(File(project.directory,"src/main.py").readBytes()))
        AtomicFiles.text(File(app.getExternalFilesDir(null),"ux-storage-100.json"),gson.toJson(mapOf("runs" to 100,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-start,"protectedExistingSessions" to existing.size,"sourceHash" to hash,"result" to "passed")))
    }
}
