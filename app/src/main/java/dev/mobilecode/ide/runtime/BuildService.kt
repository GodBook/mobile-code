package dev.mobilecode.ide.runtime

import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.AtomicFiles
import dev.mobilecode.ide.data.resolveInside
import kotlinx.coroutines.*
import java.io.File

data class BuildArtifact(val snapshot: Snapshot, val module: File?, val entry: String, val config: File)

object CompilerCommands {
    fun sources(root: File, config: ProjectConfig): List<File> = config.sourceRoots.flatMap { relative ->
        File(root,relative).walkTopDown().onEnter { !it.name.startsWith('.') || it==root }
            .filter { it.isFile && config.language.isSource(it.name) }.toList()
    }.distinctBy { it.canonicalPath }.sortedBy { it.path }
    fun common(chain: Toolchain, snapshot: Snapshot, language: CodeLanguage): List<String> {
        val triple=if(chain.manifest.abi=="arm64-v8a") "aarch64-linux-android29" else "x86_64-linux-android29"
        return (if(language==CodeLanguage.CPP) listOf("-nostdinc++","-isystem","${chain.root}/usr/include/c++/v1") else emptyList())+
            listOf("--target=$triple","--sysroot=${chain.root}","-resource-dir=${chain.clangResources}",
            "-isystem","${chain.root}/usr/include","-fPIC","-Wall","-Wextra","-fno-color-diagnostics","-fmessage-length=0","-fdiagnostics-absolute-paths",
            "-g","-O0","-fno-omit-frame-pointer","-std=${if(language==CodeLanguage.C) "c17" else "c++17"}")+
            snapshot.project.sourceRoots.flatMap { listOf("-I",File(snapshot.source,it).absolutePath) }
    }
}

class BuildService(private val factory: ProcessFactory, private val track: (ChildProcess)->Unit,
                   private val log: (String)->Unit, private val diagnostic: (Diagnostic)->Unit,
                   private val buildTimeoutMs: Long=120_000) {
    suspend fun build(chain: Toolchain, snapshot: Snapshot): BuildArtifact = withContext(Dispatchers.IO) {
        withBuildDeadline(buildTimeoutMs) { compile(chain,snapshot) }
    }
    private suspend fun compile(chain: Toolchain, snapshot: Snapshot): BuildArtifact {
        val config=File(snapshot.directory,"runtime.json")
        val settings=json("source" to snapshot.source.absolutePath,"work" to snapshot.work.absolutePath,"build" to snapshot.build.absolutePath,
            "entry" to snapshot.project.entry,"sourceRoots" to snapshot.project.sourceRoots,"arguments" to snapshot.project.arguments,"debugFd" to 3)
        var entry=snapshot.project.entry
        val module=when(snapshot.project.language) {
            CodeLanguage.C,CodeLanguage.CPP -> {
                val files=CompilerCommands.sources(snapshot.source,snapshot.project)
                require(files.isNotEmpty()) { "没有可编译的源文件" }
                val objects=files.mapIndexed { index,file ->
                    val lang=if(file.extension=="c") CodeLanguage.C else CodeLanguage.CPP
                    val objectFile=File(snapshot.build,"source-$index.o")
                    val args=CompilerCommands.common(chain,snapshot,lang)+listOf("-c",file.absolutePath,"-o",objectFile.absolutePath)
                    check(run(chain,chain.command("clang"),args,snapshot)==0) { "BUILD_FAILED：编译失败，请查看问题面板" }
                    objectFile
                }
                val library=File(snapshot.build,"libuser_program.so")
                val cpp=snapshot.project.language==CodeLanguage.CPP
                val args=CompilerCommands.common(chain,snapshot,snapshot.project.language)+listOf("-shared","--ld-path=${chain.command("ld.lld")}",
                    "-L${chain.root}/usr/lib","-Wl,--no-undefined","-Wl,-z,max-page-size=16384","-Wl,-z,common-page-size=16384",
                    "-Wl,-rpath,${chain.nativeDir}")+objects.map { it.absolutePath }+
                    (if(cpp) listOf("-nostdlib++","-lc++_shared") else emptyList())+listOf("-lm","-ldl","-pthread","-o",library.absolutePath)
                check(run(chain,chain.command("clang"),args,snapshot)==0) { "BUILD_FAILED：链接失败，请查看输出" }
                val symbols=StringBuilder()
                check(run(chain,chain.command("llvm-nm"),listOf("--dynamic","--defined-only",library.absolutePath),snapshot,symbols)==0 && Regex("(?m)\\b[TW]\\s+main\\s*$").containsMatchIn(symbols)) { "ENTRY_NOT_FOUND：需要一个标准 main 入口" }
                check(library.setReadOnly()) { "无法将编译产物设为只读" }
                library
            }
            CodeLanguage.PYTHON -> {
                resolveInside(snapshot.source,entry).also { require(it.isFile) { "ENTRY_NOT_FOUND：入口脚本不存在" } }
                AtomicFiles.text(config,settings.toString())
                check(runProcess(factory.python(chain,"compile",config,snapshot.work),snapshot)==0) { "BUILD_FAILED：Python 语法检查失败" }
                null
            }
            CodeLanguage.JAVA -> {
                AtomicFiles.text(config,settings.toString())
                val output=StringBuilder()
                check(runProcess(factory.java(chain,"dev.mobilecode.runtime.Main",listOf("compile",config.absolutePath),snapshot.work),snapshot,output)==0) { "BUILD_FAILED：Java 编译失败" }
                val raw=output.lines().firstOrNull { it.startsWith("MC_ENTRIES:") }?.removePrefix("MC_ENTRIES:") ?: "[]"
                val entries=gson.fromJson(raw,Array<String>::class.java).toList()
                entry=when { entry in entries -> entry; entries.size==1 -> entries.single(); else -> error("ENTRY_NOT_FOUND：请在运行配置中选择入口类：${entries.joinToString()}") }
                settings.addProperty("entry",entry)
                null
            }
        }
        AtomicFiles.text(config,settings.toString())
        return BuildArtifact(snapshot,module,entry,config)
    }

    private suspend fun run(chain: Toolchain, executable: File, arguments: List<String>, snapshot: Snapshot, capture: StringBuilder?=null): Int =
        runProcess(factory.start(chain,executable,arguments,snapshot.work),snapshot,capture)

    internal suspend fun runProcess(process: ChildProcess, snapshot: Snapshot, capture: StringBuilder?=null): Int = coroutineScope {
        track(process); process.eof()
        fun publish(item: Diagnostic)=diagnostic(DiagnosticProvenance.build(item,snapshot))
        val consume: suspend (java.io.InputStream)->Unit = { input ->
            try {
                input.bufferedReader().useLines { lines -> lines.forEach { line ->
                    currentCoroutineContext().ensureActive()
                    if(capture!=null) synchronized(capture) { if(capture.length<1024*1024) capture.appendLine(line) }
                    if(line.startsWith("MC_DIAGNOSTIC:")) runCatching { publish(gson.fromJson(line.removePrefix("MC_DIAGNOSTIC:"),Diagnostic::class.java)) }
                    else if(!line.startsWith("MC_ENTRIES:")) { log(line+"\n"); parseClang(line,snapshot)?.let(::publish) }
                } }
            } catch(error: java.io.IOException) {
                // Android interrupts a blocking read when timeout/Stop closes the
                // descriptor. Preserve that cancellation instead of replacing its cause.
                currentCoroutineContext().ensureActive()
                throw error
            }
        }
        val stdout=launch(Dispatchers.IO) { consume(process.output) }
        val stderr=launch(Dispatchers.IO) { consume(process.error) }
        try { val result=process.exit.await(); stdout.join(); stderr.join(); result }
        finally { withContext(NonCancellable+Dispatchers.IO) {
            // A compiler descendant may keep a pipe open after its leader exits.
            NativeProcess.signal(process.pid,9,true)
            process.closeStreams(); process.stop()
            stdout.cancel(); stderr.cancel()
        } }
    }
    private fun parseClang(line: String, snapshot: Snapshot): Diagnostic? {
        val match=Regex("^(.+):(\\d+):(\\d+): (fatal error|error|warning|note): (.*)$").matchEntire(line) ?: return null
        val path=match.groupValues[1]; val row=(match.groupValues[2].toInt()-1).coerceAtLeast(0)
        val content=runCatching { File(path).readLines().getOrElse(row) { "" } }.getOrDefault("")
        val column=Positions.utf8Column(content,(match.groupValues[3].toInt()-1).coerceAtLeast(0))
        return Diagnostic(path,SourceRange(Position(row,column),Position(row,column+1)),match.groupValues[5],if(match.groupValues[4]=="warning") 2 else 1,source="Clang")
    }
}
