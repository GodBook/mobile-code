package dev.mobilecode.ide

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.runtime.Toolchain
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs the real offline tools in the installed, non-debuggable APK. */
@RunWith(AndroidJUnit4::class)
class OfflineIdeTest {
    private val app get() = ApplicationProvider.getApplicationContext<MobileCodeApp>()
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var chain: Toolchain
    private var observer: Job? = null

    @Before fun prepare() = runBlocking {
        activity = ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        chain = withTimeout(240_000) { app.toolchains.prepare() }
        app.sessions.stop()
        lateinit var model: IdeViewModel
        activity.onActivity { model=ViewModelProvider(it)[IdeViewModel::class.java] }
        withTimeout(30_000) { model.home().join() }
        assertEquals(null,model.state.value.project)
        observer = app.scope.launch {
            app.sessions.state.map { Triple(it.phase,it.stopSequence,it.reason) }.distinctUntilChanged().collect {
                Log.i("MobileCodeTest", "session=$it output=${app.sessions.state.value.output.takeLast(2500)}")
            }
        }
    }

    @After fun cleanup() = runBlocking {
        app.sessions.stop()
        app.languages.close()
        observer?.cancel()
        activity.close()
    }

    @Test fun installedReleaseHasUpdaterPermissionsAndNonDebuggable() {
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(permissions.contains("android.permission.INTERNET"))
        assertTrue(permissions.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertFalse(permissions.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
        assertTrue(chain.runner.canExecute())
        assertTrue(chain.manifest.libraries.isNotEmpty())
        assertTrue(chain.pythonHome.resolve("lib/python3.13").isDirectory)
    }

    @Test fun atomicWorkspaceSnapshotAndZipRoundTrip() = runBlocking {
        val project = app.workspace.create("项目 😀", CodeLanguage.PYTHON, false)
        val text = "# 中文 😀\r\nvalue = 7\r\nprint(value)\r\n"
        app.workspace.save(project, Document("src/main.py", text))
        val snapshot = app.workspace.snapshot(project)
        app.workspace.save(project, Document("src/main.py", "print(9)\n"))
        assertEquals(text, snapshot.source.resolve("src/main.py").readText())
        assertEquals("print(9)\n", app.workspace.open(project, "src/main.py").text)
        val archive = File(app.cacheDir, "roundtrip.zip")
        val single = File(app.cacheDir,"exported.py")
        app.workspace.exportSource(project,"src/main.py",Uri.fromFile(single))
        assertEquals("print(9)\n",single.readText())
        app.workspace.export(project, Uri.fromFile(archive))
        val imported = app.workspace.importProject(Uri.fromFile(archive))
        assertNotEquals(project.config.id, imported.config.id)
        assertEquals("print(9)\n", app.workspace.open(imported, "src/main.py").text)
        val malicious = File(app.cacheDir, "traversal.zip")
        ZipOutputStream(malicious.outputStream()).use {
            it.putNextEntry(ZipEntry("../outside.py")); it.write("print(1)".toByteArray()); it.closeEntry()
        }
        assertTrue(runCatching { app.workspace.importProject(Uri.fromFile(malicious)) }.isFailure)
        assertFalse(File(app.workspace.projectsRoot, "outside.py").exists())
        val line = "a中😀z"
        assertEquals(4, Positions.utf8Column(line, 8))
        assertEquals(Position(1, 4), Positions.position("first\r\n$line", 11))
    }

    @Test fun cRunsWithInputAndArguments() = runBlocking {
        execute(CodeLanguage.C, "#include <stdio.h>\nint main(int n,char**a){int x;scanf(\"%d\",&x);printf(\"answer %d %s\\n\",x+5,a[1]);return 0;}\n", "3\n", listOf("中文😀"))
    }

    @Test fun cppRunsStlAcrossFiles(): Unit = runBlocking {
        val project = app.workspace.create("CPP run", CodeLanguage.CPP, true)
        app.workspace.save(project, Document("src/main.cpp", "#include <iostream>\n#include <vector>\n#include \"utils.hpp\"\nint main(){std::vector<int> v{3,5};std::cout<<\"answer \"<<add(v[0],v[1])<<std::endl;return 0;}\n"))
        finishRun(project,"unused input\n")
    }

    @Test fun python313RunsWithInput() = runBlocking {
        execute(CodeLanguage.PYTHON, "import sys\nassert sys.version_info[:2] == (3, 13)\nprint('answer', int(input()) + 5, sys.argv[1])\n", "3\n", listOf("中文😀"))
    }

    @Test fun java17RunsWithInput() = runBlocking {
        execute(CodeLanguage.JAVA, "import java.util.Scanner;\npublic class Main {public static void main(String[] a){assert Runtime.version().feature()==21;System.out.println(\"answer \"+(new Scanner(System.in).nextInt()+5)+\" \"+a[0]);}}\n", "3\n", listOf("中文😀"))
    }

    @Test fun cHasRealSourceDebugger() = runBlocking { debug(CodeLanguage.C) }
    @Test fun cppHasRealSourceDebugger() = runBlocking { debug(CodeLanguage.CPP) }
    @Test fun pythonHasRealSourceDebugger() = runBlocking { debug(CodeLanguage.PYTHON) }
    @Test fun javaHasRealSourceDebugger() = runBlocking { debug(CodeLanguage.JAVA) }

    private suspend fun execute(language: CodeLanguage, text: String, input: String? = null, args: List<String> = emptyList()) {
        var project = app.workspace.create("${language.name} run", language, false)
        app.workspace.save(project, Document("src/${if(language==CodeLanguage.JAVA) "Main.java" else "main.${language.extension}"}", text))
        project = app.workspace.updateConfig(project, project.config.copy(arguments=args))
        val result = finishRun(project, input)
        if(args.isNotEmpty()) assertTrue(result.output, result.standardOutput.contains("中文😀"))
    }

    private suspend fun finishRun(project: Project, input: String? = null): SessionState {
        val id = app.sessions.start(project, chain, false, emptyList(), input)
        val result = withTimeout(150_000) { app.sessions.state.first { it.id==id && !it.active } }
        val detail="${result.output}\n${result.reason}; exit=${result.exitCode}; elapsed=${result.elapsedMs}ms"
        assertEquals(detail, SessionPhase.FINISHED, result.phase)
        assertEquals(detail, 0, result.exitCode)
        assertTrue(result.toString(), result.standardOutput.contains("answer 8"))
        return result
    }

    private suspend fun debug(language: CodeLanguage) {
        val source = when(language) {
            CodeLanguage.C -> "#include <stdio.h>\nint add(int a,int b){\n int value=a+b;\n return value;\n}\nint main(void){\n int first;\n scanf(\"%d\",&first);\n int result=add(first,5);\n printf(\"answer %d\\n\",result);\n return 0;\n}\n"
            CodeLanguage.CPP -> "#include <iostream>\nint add(int a,int b){\n int value=a+b;\n return value;\n}\nint main(){\n int first;\n std::cin>>first;\n int result=add(first,5);\n std::cout<<\"answer \"<<result<<std::endl;\n return 0;\n}\n"
            CodeLanguage.PYTHON -> "def add(a,b):\n value=a+b\n return value\nfirst=int(input())\nresult=add(first,5)\nprint('answer',result)\n"
            CodeLanguage.JAVA -> "public class Main {\n static int add(int a,int b){\n  int value=a+b;\n  return value;\n }\n public static void main(String[]args){\n  int first=new java.util.Scanner(System.in).nextInt();\n  int result=add(first,5);\n  System.out.println(\"answer \"+result);\n }\n}\n"
        }
        val project = app.workspace.create("${language.name} debug", language, false)
        val path = "src/${if(language==CodeLanguage.JAVA) "Main.java" else "main.${language.extension}"}"
        app.workspace.save(project, Document(path, source))
        val line = source.lines().indexOfFirst { it.contains("result=add(") }
        val configured = if(language==CodeLanguage.PYTHON) app.workspace.updateConfig(project,project.config.copy(runTimeoutSeconds=2)) else project
        val id = app.sessions.start(configured, chain, true, listOf(Breakpoint(path, line)), "3\n")
        var paused = pauseOrFinish(id)
        var skips = 0
        while(paused.frames.first().line!=line) {
            assertTrue(paused.toString(),++skips<=3)
            app.sessions.command("continue")
            paused = pauseOrFinish(id, paused.stopSequence)
        }
        app.sessions.command("stepIn")
        paused = pauseOrFinish(id,paused.stopSequence)
        assertTrue(paused.output, paused.frames.first().name.contains("add"))
        assertTrue(paused.variables.toString(), paused.variables.any { it.name=="a" && it.value=="3" })
        val assignment = if(language==CodeLanguage.PYTHON) 1 else 2
        while(paused.frames.first().line<assignment) {
            app.sessions.command("next"); paused=pauseOrFinish(id,paused.stopSequence)
        }
        app.sessions.command("next")
        paused = pauseOrFinish(id,paused.stopSequence)
        assertEquals(paused.output, "8", app.sessions.watch("value").value)
        if(language==CodeLanguage.PYTHON) delay(2500) // Paused time must not consume the 2s execution budget.
        app.sessions.command("stepOut")
        val caller = pauseOrFinish(id, paused.stopSequence)
        assertFalse(caller.frames.toString(), caller.frames.first().name.contains("add"))
        app.sessions.command("continue")
        val result = withTimeout(20_000) { app.sessions.state.first { it.id==id && !it.active } }
        assertEquals(result.output, SessionPhase.FINISHED, result.phase)
        assertTrue(result.output, result.standardOutput.contains("answer 8"))
    }

    private suspend fun pauseOrFinish(id: String, after: Int = 0): SessionState {
        val result = withTimeout(150_000) { app.sessions.state.first {
            it.id==id && ((!it.active) || (it.phase==SessionPhase.PAUSED && it.stopSequence>after && it.frames.isNotEmpty() && it.frameId!=null))
        } }
        assertEquals(result.output, SessionPhase.PAUSED, result.phase)
        return result
    }

    @Test fun cHasCrossFileSemanticCompletion() = runBlocking { completion(CodeLanguage.C) }
    @Test fun cppHasCrossFileSemanticCompletion() = runBlocking { completion(CodeLanguage.CPP) }
    @Test fun pythonHasCrossFileSemanticCompletion() = runBlocking { completion(CodeLanguage.PYTHON) }
    @Test fun javaHasCrossFileSemanticCompletion() = runBlocking { completion(CodeLanguage.JAVA) }

    private suspend fun completion(language: CodeLanguage) {
        val project = app.workspace.create("${language.name} completion", language, true)
        val path = "src/${if(language==CodeLanguage.JAVA) "Main.java" else "main.${language.extension}"}"
        val text = when(language) {
            CodeLanguage.C -> "#include \"utils.h\"\nint main(void){ ad }\n"
            CodeLanguage.CPP -> "#include \"utils.hpp\"\nint main(){ ad }\n"
            CodeLanguage.PYTHON -> "import utils\nutils.ad"
            CodeLanguage.JAVA -> "public class Main { public static void main(String[]a){ Utils.ad } }\n"
        }
        app.workspace.save(project, Document(path, text))
        withTimeout(60_000) { app.languages.activate(project, chain) }
        val position = Positions.position(text, text.lastIndexOf("ad")+2)
        val doc = Document(path, text)
        var items = emptyList<Completion>()
        fun found() = items.any { it.label.trimStart().startsWith("add") || it.insertText.startsWith("add") }
        withTimeout(45_000) {
            while(!found()) {
                items = app.languages.complete(doc, position)
                Log.i("MobileCodeTest", "completion ${language.name}: ${items.take(8)}; diagnostics=${app.languages.diagnostics.value.take(3)}")
                if(!found()) delay(500)
            }
        }
        assertTrue(items.toString(), found())
    }

    @Test fun timeoutAndCrashLeaveEditorAndNextRunUsable(): Unit = runBlocking {
        var project = app.workspace.create("faults", CodeLanguage.PYTHON, false)
        val normalTimeout=project.config.runTimeoutSeconds
        app.workspace.save(project, Document("src/main.py", "while True: pass\n"))
        project = app.workspace.updateConfig(project, project.config.copy(runTimeoutSeconds=1))
        val id = app.sessions.start(project, chain, false, emptyList(), null)
        val timeout = withTimeout(20_000) { app.sessions.state.first { it.id==id && !it.active } }
        assertTrue(timeout.toString(), timeout.reason.contains("TIMEOUT"))
        // Recovery checks use the normal budget; the 1s timeout above is a deliberate fault.
        project=app.workspace.updateConfig(project,project.config.copy(runTimeoutSeconds=normalTimeout))
        app.workspace.save(project, Document("src/main.py", "raise ValueError('intentional')\n"))
        val crashId = app.sessions.start(project, chain, false, emptyList(), null)
        val crash = withTimeout(20_000) { app.sessions.state.first { it.id==crashId && !it.active } }
        assertEquals(crash.output, SessionPhase.FAILED, crash.phase)
        assertTrue(crash.output, crash.standardError.contains("ValueError"))
        app.workspace.save(project, Document("src/main.py", "print('answer 8')\n"))
        finishRun(project)
    }

    @Test fun blockedPresetInputStillTimesOut(): Unit = runBlocking {
        val original = app.workspace.create("blocked stdin",CodeLanguage.PYTHON,false)
        val project = app.workspace.updateConfig(original,original.config.copy(runTimeoutSeconds=1))
        app.workspace.save(project,Document("src/main.py","while True: pass\n"))
        val id = app.sessions.start(project,chain,false,emptyList(),"x".repeat(2*1024*1024))
        val result = withTimeout(10_000) { app.sessions.state.first { it.id==id && !it.active } }
        assertTrue(result.toString(),result.reason.contains("TIMEOUT"))
    }

    @Test fun interactiveInputEofAndIdempotentStop(): Unit = runBlocking {
        val project = app.workspace.create("interactive stdin",CodeLanguage.PYTHON,false)
        app.workspace.save(project,Document("src/main.py","import sys\nprint(input())\nprint(len(sys.stdin.read()))\n"))
        val id = app.sessions.start(project,chain,false,emptyList(),null)
        withTimeout(10_000) { app.sessions.state.first { it.id==id && it.phase==SessionPhase.RUNNING } }
        assertEquals(id,app.sessions.start(project,chain,false,emptyList(),null,id))
        app.sessions.sendInput("你好😀"); app.sessions.sendInput("abc",false); app.sessions.eof()
        val result = withTimeout(10_000) { app.sessions.state.first { it.id==id && !it.active } }
        assertEquals(result.output,SessionPhase.FINISHED,result.phase)
        assertTrue(result.toString(),result.standardOutput.contains("你好😀\n3"))
        app.workspace.save(project,Document("src/main.py","while True: pass\n"))
        val running = app.sessions.start(project,chain,false,emptyList(),null)
        withTimeout(10_000) { app.sessions.state.first { it.id==running && it.phase==SessionPhase.RUNNING } }
        val start=android.os.SystemClock.elapsedRealtime()
        app.sessions.stop()
        assertTrue("Stop exceeded 2s",android.os.SystemClock.elapsedRealtime()-start<2000)
        assertFalse(app.sessions.state.value.active)
    }

    @Test fun nativeCrashAndOutputFloodStayContained(): Unit = runBlocking {
        val native = app.workspace.create("native crash",CodeLanguage.C,false)
        app.workspace.save(native,Document("src/main.c","int main(void){volatile int*p=0;*p=1;return 0;}\n"))
        val id = app.sessions.start(native,chain,false,emptyList(),null)
        val crash = withTimeout(20_000) { app.sessions.state.first { it.id==id && !it.active } }
        assertEquals(crash.toString(),SessionPhase.FAILED,crash.phase)
        assertEquals(crash.toString(),139,crash.exitCode)
        val python = app.workspace.create("output flood",CodeLanguage.PYTHON,false)
        app.workspace.save(python,Document("src/main.py","import sys\nsys.stdout.buffer.write(b'x'*(12*1024*1024))\nsys.stdout.flush()\n"))
        val floodId=app.sessions.start(python,chain,false,emptyList(),null)
        val flood=withTimeout(30_000) { app.sessions.state.first { it.id==floodId && !it.active } }
        assertEquals(flood.toString().take(1000),SessionPhase.FINISHED,flood.phase)
        assertTrue(flood.output.length.toString(),flood.output.length<=513*1024)
        assertEquals(10L*1024*1024,File(app.sessions.snapshot!!.directory,"console.log").length())
    }
}
