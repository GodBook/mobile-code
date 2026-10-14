package dev.mobilecode.ide

import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.*
import dev.mobilecode.ide.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real installed tools and files; all mutations belong to newly created fixtures. */
@RunWith(AndroidJUnit4::class)
class ReliabilityFixTest {
    private val app get()=ApplicationProvider.getApplicationContext<MobileCodeApp>()
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var model: IdeViewModel
    private lateinit var chain: Toolchain
    private lateinit var oldSettings: EditorSettings

    @Before fun prepare() = runBlocking {
        oldSettings=app.settings.flow.first()
        activity=ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model=ViewModelProvider(it)[IdeViewModel::class.java]
        }
        app.sessions.stop()
        perform { it.home() }
        chain=withTimeout(240_000) { app.toolchains.status.first { it.toolchain!=null }.toolchain!! }
    }

    @After fun finish() = runBlocking {
        app.sessions.stop()
        perform { it.home() }
        app.languages.close()
        app.settings.update(oldSettings)
        activity.close()
    }

    private suspend fun perform(action: (IdeViewModel)->Job) {
        lateinit var job: Job
        activity.onActivity { model.clearError(); job=action(model) }
        withTimeout(240_000) { job.join() }
        assertNull(model.state.value.error,model.state.value.error)
    }
    private suspend fun finished(id: String)=withTimeout(45_000) { app.sessions.state.first { it.id==id && !it.active } }
    private suspend fun project(language: CodeLanguage=CodeLanguage.PYTHON)=app.workspace.create("可靠性验收 ${UUID.randomUUID().toString().take(6)}",language,false)

    @Test fun runAndFailedBuildBothKeepTheirOwnHistoryAndSnapshot() = runBlocking {
        val project=project()
        app.workspace.save(project,Document("src/main.py","from pathlib import Path\nPath('result.txt').write_text('产物😀')\nprint('history-ok')\n"))
        val id=app.sessions.start(project,chain,false,emptyList(),null)
        assertEquals(SessionPhase.FINISHED,finished(id).phase)
        assertEquals(id,app.sessions.snapshot!!.id)
        val row=app.workspace.history().single { it.id==id }
        assertEquals("产物😀",row.files.single { it.name=="result.txt" }.readText())
        assertTrue(row.files.single { it.name=="console.log" }.readText().contains("history-ok"))
        val reloaded=WorkspaceService(app)
        try { assertEquals(row.files.map { it.canonicalPath }.toSet(),reloaded.history().single { it.id==id }.files.map { it.canonicalPath }.toSet()) }
        finally { reloaded.database.close() }

        val broken="def broken(:\n"
        app.workspace.save(project,Document("src/main.py",broken))
        val failedId=app.sessions.start(project,chain,false,emptyList(),null)
        val failed=finished(failedId)
        assertEquals(SessionPhase.FAILED,failed.phase)
        assertEquals(failedId,app.sessions.snapshot!!.id)
        assertEquals(broken,File(app.sessions.snapshot!!.source,"src/main.py").readText())
        assertTrue(failed.diagnostics.any { it.origin==DiagnosticOrigin.BUILD && it.snapshotId==failedId && it.projectId==project.config.id })
        assertTrue(app.workspace.history().single { it.id==failedId }.files.single { it.name=="console.log" }.readText().contains("BUILD_FAILED"))
    }

    @Test fun buildErrorsFollowTheSnapshotInsteadOfEditedOrOtherProjectBuffers() = runBlocking {
        perform { it.createProject("诊断来源 A",CodeLanguage.PYTHON,false) }
        val bad="def broken(:\n"
        activity.onActivity { val document=model.state.value.document!!; model.edit(document.path,bad,0,0,0) }
        perform { it.run(false) }
        val run=finished(app.sessions.state.value.id!!)
        assertEquals(SessionPhase.FAILED,run.phase)
        val error=run.diagnostics.first { it.origin==DiagnosticOrigin.BUILD }
        withTimeout(10_000) { model.editorDiagnostics.first { it.document?.text==bad && it.items.any { item -> item.snapshotId==run.id } } }

        val fixed="print('fixed 中文😀')\n"
        activity.onActivity { val document=model.state.value.document!!; model.edit(document.path,fixed,0,0,0) }
        withTimeout(10_000) { model.editorDiagnostics.first { it.document?.text==fixed && it.items.none { item -> item.origin==DiagnosticOrigin.BUILD } } }
        assertTrue(model.diagnostics.value.any { it.snapshotId==run.id })
        perform { it.diagnostic(error) }
        assertTrue(model.state.value.preview!!.title.startsWith("运行快照"))
        assertEquals(bad,model.state.value.preview!!.document.text)
        activity.onActivity { model.closePreview() }

        perform { it.createProject("诊断来源 B",CodeLanguage.PYTHON,false) }
        val second=model.state.value.project!!.config.id
        withTimeout(10_000) { model.editorDiagnostics.first { it.projectId==second && it.document!=null } }
        assertTrue(model.diagnostics.value.none { it.projectId==error.projectId })
        assertTrue(model.editorDiagnostics.value.items.none { it.snapshotId==run.id })
    }

    @Test fun deadlineKillsARealDescendantHoldingOutputAfterItsParentExited() = runBlocking {
        val project=project(CodeLanguage.C)
        app.workspace.save(project,Document("src/main.c","""
            #include <stdio.h>
            #include <unistd.h>
            int main(void) {
                pid_t child=fork();
                if(child<0) return 1;
                if(child==0) { printf("holding-child:%d\n",getpid()); for(;;) pause(); }
                puts("parent-exited");
                return 0;
            }
        """.trimIndent()))
        val snapshot=app.workspace.snapshot(project)
        val log=StringBuilder()
        val builder=BuildService(app.processes,{}, { synchronized(log) { log.append(it) } },{})
        val artifact=builder.build(chain,snapshot)
        val process=app.processes.start(chain,chain.runner,listOf("native",artifact.module!!.absolutePath),snapshot.work)
        val started=android.os.SystemClock.elapsedRealtime()
        try {
            val failure=runCatching { withBuildDeadline(1500) { builder.runProcess(process,snapshot) } }.exceptionOrNull()
            assertTrue(failure.toString(),failure is BuildTimeoutException)
            assertTrue("Cleanup exceeded its bounded budget",android.os.SystemClock.elapsedRealtime()-started<6500)
            assertEquals(0,withTimeout(1000) { process.exit.await() })
            val output=synchronized(log) { log.toString() }
            assertTrue(output,output.contains("parent-exited"))
            val child=Regex("holding-child:(\\d+)").find(output)!!.groupValues[1]
            withTimeout(3000) {
                while(File("/proc/$child/status").let { file -> file.isFile && runCatching { file.readText().contains("State:\tZ") }.getOrDefault(false).not() }) delay(25)
            }
        } finally {
            NativeProcess.signal(process.pid,9,true); process.closeStreams(); process.stop()
        }
    }

    @Test fun manualRepairIgnoresReadyMarkersAndRestoresOfflineResourcesThenRunsAllLanguages() = runBlocking {
        val header=File(chain.root,"usr/include/stdio.h")
        val standardLibrary=File(chain.pythonHome,"lib/python3.13/os.py")
        val headerBytes=header.readBytes(); val pythonBytes=standardLibrary.readBytes()
        val sourceProject=project(); val source=File(sourceProject.directory,"src/main.py")
        val sourceHash=sha256(source.readBytes())
        try {
            app.languages.close()
            AtomicFiles.text(header,"deliberately corrupt resource")
            assertTrue(standardLibrary.delete())
            assertTrue(File(chain.root,".ready").isFile)
            assertTrue(File(chain.root,".native-verified").isFile)
            perform { it.retryTools() }
            chain=app.toolchains.status.value.toolchain!!
            assertArrayEquals(headerBytes,header.readBytes())
            assertArrayEquals(pythonBytes,standardLibrary.readBytes())
            assertEquals(sourceHash,sha256(source.readBytes()))
            for(language in CodeLanguage.entries) {
                val project=project(language)
                val id=app.sessions.start(project,chain,false,emptyList(),null)
                val result=finished(id)
                assertEquals("$language: ${result.output}",SessionPhase.FINISHED,result.phase)
            }
        } finally {
            // Restore these exact component fixtures even if repair/acceptance fails.
            if(chain.root.isDirectory) {
                AtomicFiles.write(header,headerBytes); header.setReadOnly()
                AtomicFiles.write(standardLibrary,pythonBytes); standardLibrary.setReadOnly()
            }
        }
    }
}
