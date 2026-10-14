package dev.mobilecode.ide

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

/** Invoke methods separately with host `am force-stop` in between. No existing project is changed. */
class RecoveryProcessTest {
    private val app get()=ApplicationProvider.getApplicationContext<MobileCodeApp>()
    private val marker get()=File(app.filesDir,"ux-process-recovery.json")
    private data class Marker(val projectId: String,val path: String,val originalHash: String,val draftHash: String,val backup: String,val settings: EditorSettings)
    @Test fun recordFailedSaveDraftBeforeProcessDeath(): Unit = runBlocking {
        val old=app.settings.flow.first()
        val project=app.workspace.create("进程终止恢复专用验收",CodeLanguage.PYTHON,false)
        val path="src/main.py"; val original=resolveInside(project.directory,path)
        val hash=sha256(original.readBytes())
        app.settings.location(project.config.id,path)
        val activity=ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: IdeViewModel
        activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); model=ViewModelProvider(it)[IdeViewModel::class.java] }
        withTimeout(30_000) { model.state.first { it.project?.config?.id==project.config.id && it.document!=null } }
        val backup=File(app.cacheDir,"ux-process-original-${project.config.id}.py")
        Files.move(original.toPath(),backup.toPath()); check(original.mkdir())
        val text="# 真正跨进程恢复中文😀\nprint('after-process-death')\n"
        activity.onActivity { model.edit(path,text,0,0,100) }
        withTimeout(15_000) { model.state.first { it.saveStates[path]?.phase=="failed" } }
        assertEquals(text,app.journal.drafts(project).single { it.path==path }.text)
        AtomicFiles.text(marker,gson.toJson(Marker(project.config.id,path,hash,sha256(text.toByteArray()),backup.name,old)))
        activity.close() // onStop retries still fail against the deliberate test directory.
        assertTrue(marker.isFile)
    }
    @Test fun verifyRecoveryAfterFreshProcess(): Unit = runBlocking {
        assertTrue("Run record method, then host force-stop, before this method",marker.isFile)
        val saved=gson.fromJson(marker.readText(),Marker::class.java)
        val project=app.workspace.projects().single { it.config.id==saved.projectId }
        assertEquals("进程终止恢复专用验收",project.config.name)
        val original=resolveInside(project.directory,saved.path)
        val backup=resolveInside(app.cacheDir,saved.backup)
        check(original.isDirectory && original.delete()); Files.move(backup.toPath(),original.toPath())
        assertEquals(saved.originalHash,sha256(original.readBytes()))
        val draft=app.journal.drafts(project).single { it.path==saved.path }
        assertEquals(saved.draftHash,sha256(draft.text.toByteArray()))
        val activity=ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var model: IdeViewModel
            activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); model=ViewModelProvider(it)[IdeViewModel::class.java] }
            withTimeout(30_000) { model.state.first { it.project?.config?.id==saved.projectId && it.recoveries.isNotEmpty() } }
            assertEquals(saved.originalHash,sha256(original.readBytes()))
            lateinit var job: Job
            activity.onActivity { job=model.recover(draft) }; withTimeout(15_000) { job.join() }
            activity.onActivity { job=model.flush() }; withTimeout(15_000) { job.join() }
            assertEquals(saved.draftHash,sha256(original.readBytes()))
            assertTrue(app.journal.drafts(project).isEmpty())
            activity.onActivity { job=model.home() }; withTimeout(15_000) { job.join() }
            app.settings.update(saved.settings)
            marker.delete()
        } finally { activity.close() }
    }
}
