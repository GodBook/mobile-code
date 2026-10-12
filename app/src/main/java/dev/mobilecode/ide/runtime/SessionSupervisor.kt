package dev.mobilecode.ide.runtime

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.*
import dev.mobilecode.ide.protocol.JsonConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

class SessionSupervisor(private val context: Context, private val scope: CoroutineScope,
    private val workspace: WorkspaceService, private val factory: ProcessFactory) {
    private val mutable=MutableStateFlow(SessionState())
    val state=mutable.asStateFlow()
    private val lock=Mutex()
    private var job: Job?=null
    private val children=ConcurrentHashMap<Int,ChildProcess>()
    private var connection: JsonConnection?=null
    private var stdin: OutputStream?=null
    private var currentArtifact: BuildArtifact?=null
    private var debugTarget: Int?=null
    private var forcedReason: String?=null
    private val outputLock=Any()
    private val output=StringBuilder()
    private val channels=mapOf("stdout" to StringBuilder(),"stderr" to StringBuilder(),"build" to StringBuilder())
    private var diskLog: OutputStream?=null
    private var diskBytes=0L
    private val pointIds=ConcurrentHashMap<Int,Pair<String,Int>>()
    private val publisher=scope.launch { while(isActive) { delay(50); val texts=synchronized(outputLock) { listOf(output.toString(),channels.getValue("stdout").toString(),channels.getValue("stderr").toString(),channels.getValue("build").toString()) }; if(mutable.value.output!=texts[0]) mutable.update { it.copy(output=texts[0],standardOutput=texts[1],standardError=texts[2],buildOutput=texts[3]) } } }

    val snapshot get() = currentArtifact?.snapshot
    private fun track(child: ChildProcess) { children[child.pid]=child; scope.launch { child.exit.await(); children.remove(child.pid) } }
    private fun append(text: String,channel: String="system") {
        synchronized(outputLock) {
            channels[channel]?.let { value -> value.append(text); if(value.length>128*1024) value.delete(0,value.length-128*1024) }
            output.append(text)
            if(output.length>512*1024) output.delete(0,output.length-512*1024)
            if(diskBytes<10L*1024*1024) {
                val bytes=text.toByteArray(); val size=minOf(bytes.size.toLong(),10L*1024*1024-diskBytes).toInt()
                runCatching { diskLog?.write(bytes,0,size) }; diskBytes+=size
                if(diskBytes>=10L*1024*1024) output.append("\n[日志已达 10MB，继续显示最近输出]\n")
            }
        }
    }
    suspend fun start(project: Project, chain: Toolchain, debugging: Boolean, points: List<Breakpoint>, preloadedInput: String?, requestId: String=UUID.randomUUID().toString()): String = lock.withLock {
        if(mutable.value.active) { check(mutable.value.id==requestId) { "已有任务正在运行" }; return@withLock requestId }
        forcedReason=null; debugTarget=null; currentArtifact=null; pointIds.clear()
        synchronized(outputLock) { output.clear(); channels.values.forEach { it.clear() }; diskBytes=0; diskLog=null }
        mutable.value=SessionState(id=requestId,phase=SessionPhase.PREPARING,debugging=debugging)
        try { ContextCompat.startForegroundService(context,Intent(context,SessionForegroundService::class.java)) }
        catch(error:Exception) { mutable.update { it.copy(phase=SessionPhase.FAILED,reason=error.message ?: "无法启动本地任务") }; throw error }
        job=scope.launch {
            val start=SystemClock.elapsedRealtime(); var finalPhase=SessionPhase.FAILED; var exit:Int?=null; var reason=""
            val history=HistoryEntity().also { it.id=requestId; it.projectId=project.config.id; it.status="running"; it.startedAt=System.currentTimeMillis() }
            var watchdog:Job?=null
            try {
                withContext(Dispatchers.IO) { workspace.database.dao().putHistory(history) }
                val source=workspace.snapshot(project)
                watchdog=launch { monitor(source,project.config.runTimeoutSeconds) }
                diskLog=withContext(Dispatchers.IO) { File(source.directory,"console.log").outputStream() }
                append("${project.config.language.title} · ${if(debugging) "调试" else "运行"}\n")
                mutable.update { it.copy(phase=SessionPhase.BUILDING) }
                val builder=BuildService(factory,::track,{ append(it,"build") }) { diagnostic -> mutable.update { it.copy(diagnostics=it.diagnostics+diagnostic) } }
                val artifact=builder.build(chain,source); currentArtifact=artifact
                mutable.update { it.copy(phase=SessionPhase.STARTING) }
                exit=if(debugging) debug(chain,artifact,points,preloadedInput) else run(chain,artifact,preloadedInput)
                finalPhase=if(exit==null || exit==0) SessionPhase.FINISHED else SessionPhase.FAILED
                reason=when { exit==null -> "调试会话已结束"; exit==0 -> "执行完成"; exit>=128 -> "程序因信号 ${exit-128} 结束"; else -> "程序退出码 $exit" }
            } catch(cancel:CancellationException) { finalPhase=if(forcedReason==null) SessionPhase.CANCELLED else SessionPhase.FAILED; reason=forcedReason ?: "已停止" }
            catch(error:Throwable) { reason=forcedReason ?: error.message ?: error.toString(); append("\n$reason\n") }
            finally {
                withContext(NonCancellable+Dispatchers.IO) {
                    watchdog?.cancel(); connection?.close(); connection=null
                    runCatching { stdin?.close() }; stdin=null
                    debugTarget?.let { NativeProcess.signal(it,9,false) }; debugTarget=null
                    children.values.toList().forEach { it.stop(); it.closeStreams() }; children.clear()
                    synchronized(outputLock) { runCatching { diskLog?.close() }; diskLog=null }
                    val elapsed=SystemClock.elapsedRealtime()-start
                    val texts=synchronized(outputLock) { listOf(output.toString(),channels.getValue("stdout").toString(),channels.getValue("stderr").toString(),channels.getValue("build").toString()) }
                    mutable.update { it.copy(phase=finalPhase,exitCode=exit,reason=reason,elapsedMs=elapsed,output=texts[0],standardOutput=texts[1],standardError=texts[2],buildOutput=texts[3]) }
                    history.status=finalPhase.name.lowercase(); history.durationMs=elapsed; history.exitCode=exit
                    runCatching { workspace.database.dao().putHistory(history) }
                }
            }
        }
        requestId
    }
    private suspend fun run(chain: Toolchain, artifact: BuildArtifact, preload: String?): Int = coroutineScope {
        val source=artifact.snapshot
        val process=when(source.project.language) {
            CodeLanguage.C,CodeLanguage.CPP -> factory.start(chain,chain.runner,listOf("native",artifact.module!!.absolutePath)+source.project.arguments,source.work)
            CodeLanguage.PYTHON -> factory.python(chain,"run",artifact.config,source.work)
            CodeLanguage.JAVA -> factory.java(chain,"dev.mobilecode.runtime.Main",listOf("run",artifact.config.absolutePath),source.work,"${chain.runtimeJar}:${source.build}")
        }
        track(process); stdin=process.input
        val out=launch(Dispatchers.IO) { drain(process.output) }; val err=launch(Dispatchers.IO) { drain(process.error,"stderr") }
        try {
            mutable.update { it.copy(phase=SessionPhase.RUNNING) }
            if(preload!=null) { preloadInput(preload) }
            val result=process.exit.await(); out.join(); err.join(); result
        } finally {
            // Killing the worker releases blocking pipe readers before coroutineScope joins them.
            withContext(NonCancellable) { process.stop(); process.closeStreams() }
        }
    }
    private suspend fun drain(input: InputStream,channel: String="stdout") = withContext(Dispatchers.IO) {
        try { input.reader(Charsets.UTF_8).use { reader -> val buffer=CharArray(4096); while(true) { val size=reader.read(buffer); if(size<0) break; append(String(buffer,0,size),channel) } } }
        catch(error:IOException) { if(currentCoroutineContext().isActive) append("\n输出通道已关闭：${error.message}\n") }
    }
    private suspend fun debug(chain: Toolchain, artifact: BuildArtifact, points: List<Breakpoint>, preload: String?): Int? = coroutineScope {
        val source=artifact.snapshot
        val initialized=CompletableDeferred<Unit>(); val terminated=CompletableDeferred<Unit>()
        var exitCode:Int?=null; var user:ChildProcess?=null; val streams=mutableListOf<DuplexStreams>()
        val consoleHandles=mutableListOf<Closeable>()
        val fifoKeepers=mutableListOf<Int>()
        fun closeKeepers() = synchronized(fifoKeepers) { fifoKeepers.forEach(NativeProcess::closeFd); fifoKeepers.clear() }
        val fifoDirectory=File(source.directory,"console-pipes")
        val fifoPaths=listOf("stdin","stdout","stderr").map { File(fifoDirectory,it).absolutePath }
        val consoleReady=File(fifoDirectory,"ready")
        val readers=mutableListOf<Job>()
        val adapter:ChildProcess
        val rpc:JsonConnection
        when(source.project.language) {
            CodeLanguage.PYTHON -> {
                val pair=NativeProcess.socketPair()
                try { user=factory.python(chain,"debug",artifact.config,source.work,mapOf(pair[1] to 3)); track(user!!) }
                finally { NativeProcess.closeFd(pair[1]) }
                val io=DuplexStreams.adopt(pair[0]); streams+=io
                adapter=user!!; stdin=user!!.input; rpc=JsonConnection(io.input,io.output,scope,dap=true)
                readers+=launch(Dispatchers.IO) { drain(user!!.output) }; readers+=launch(Dispatchers.IO) { drain(user!!.error,"stderr") }
            }
            CodeLanguage.JAVA -> {
                val pair=NativeProcess.socketPair()
                try {
                    user=factory.java(chain,"dev.mobilecode.runtime.Main",listOf("run",artifact.config.absolutePath),source.work,"${chain.runtimeJar}:${source.build}",
                        listOf("-agentlib:jdwp=transport=dt_mobile,server=n,suspend=y,address=3"),mapOf(pair[1] to 3)); track(user!!)
                    val config=File(source.directory,"java-debug.json")
                    AtomicFiles.text(config,json("source" to source.source.absolutePath,"jdwpFd" to 3,"transportLibrary" to File(chain.nativeDir,"libdt_mobile.so").absolutePath).toString())
                    adapter=factory.java(chain,"dev.mobilecode.runtime.Main",listOf("debug-adapter",config.absolutePath),source.work,pass=mapOf(pair[0] to 3)); track(adapter)
                } finally { NativeProcess.closeFd(pair[0]); NativeProcess.closeFd(pair[1]) }
                stdin=user!!.input; rpc=JsonConnection(adapter.output,adapter.input,scope,dap=true)
                readers+=launch(Dispatchers.IO) { drain(user!!.output) }; readers+=launch(Dispatchers.IO) { drain(user!!.error,"stderr") }; readers+=launch(Dispatchers.IO) { drain(adapter.error,"system") }
            }
            CodeLanguage.C,CodeLanguage.CPP -> {
                fifoDirectory.mkdirs()
                val descriptors=NativeProcess.fifoEndpoints(fifoPaths.toTypedArray())
                fifoKeepers+=descriptors.drop(3)
                stdin=ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(descriptors[0]))
                val stdout=ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(descriptors[1]))
                val stderr=ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(descriptors[2]))
                consoleHandles+=listOf(stdin!!,stdout,stderr)
                try {
                    adapter=factory.start(chain,chain.command("lldb-dap"),emptyList(),source.work,extraEnvironment=
                        mapOf("PYTHONHOME" to File(chain.root,"usr").absolutePath,"PYTHONPATH" to "${chain.root}/usr/lib/python3.14:${chain.root}/usr/lib/python3.14/site-packages")); track(adapter)
                } catch(error:Throwable) { closeKeepers(); consoleHandles.forEach { runCatching { it.close() } }; throw error }
                readers+=launch(Dispatchers.IO) { drain(stdout) }; readers+=launch(Dispatchers.IO) { drain(stderr,"stderr") }; readers+=launch(Dispatchers.IO) { drain(adapter.error,"system") }
                rpc=JsonConnection(adapter.output,adapter.input,scope,dap=true)
            }
        }
        connection=rpc
        val events=launch(start=CoroutineStart.UNDISPATCHED) {
            rpc.events.collect { event ->
                val body=event["body"]?.takeIf { it.isJsonObject }?.asJsonObject ?: json()
                when(event["event"]?.asString) {
                    "initialized" -> initialized.complete(Unit)
                    "stopped" -> {
                        val thread=body["threadId"]?.asInt ?: 1
                        mutable.update { it.copy(phase=SessionPhase.PAUSED,threadId=thread,stopSequence=it.stopSequence+1) }
                        launch { runCatching { refreshStack(thread) }.onFailure { error -> mutable.update { it.copy(reason=error.message ?: "调用栈读取失败") } } }
                    }
                    "continued" -> mutable.update { it.copy(phase=SessionPhase.RUNNING,frames=emptyList(),variables=emptyList(),frameId=null) }
                    "output" -> append(body["output"]?.asString ?: "",if(body["category"]?.asString=="stderr") "stderr" else "stdout")
                    "process" -> debugTarget=body["systemProcessId"]?.asInt
                    "exited" -> { exitCode=body["exitCode"]?.asInt; debugTarget=null }
                    "terminated" -> terminated.complete(Unit)
                    "breakpoint" -> {
                        val point=body.getAsJsonObject("breakpoint")
                        val where=point["id"]?.asInt?.let { pointIds[it] }
                        if(where!=null) mutable.update { state ->
                            val values=state.breakpointResults[where.first].orEmpty().toMutableList()
                            if(where.second<values.size) values[where.second]=values[where.second].copy(verified=point["verified"]?.asBoolean ?: false,line=(point["line"]?.asInt?.minus(1)) ?: values[where.second].line,message=point["message"]?.asString ?: "")
                            state.copy(breakpointResults=state.breakpointResults+(where.first to values))
                        }
                    }
                }
            }
        }
        try {
            rpc.request("initialize",json("clientID" to "mobilecode","adapterID" to source.project.language.lspId,"linesStartAt1" to true,"columnsStartAt1" to true,"pathFormat" to "path","supportsRunInTerminalRequest" to false),15_000)
            val launchArgs=if(source.project.language in listOf(CodeLanguage.C,CodeLanguage.CPP)) {
                json("program" to chain.runner.absolutePath,"args" to (listOf("native-debug",artifact.module!!.absolutePath)+fifoPaths+consoleReady.absolutePath+source.project.arguments),"cwd" to source.work.absolutePath,
                    "stopOnEntry" to false,"disableASLR" to false,
                    "env" to listOf("LD_LIBRARY_PATH=${chain.nativeDir}","MC_DEBUG=1","HOME=${source.work}","TMPDIR=${source.work}/tmp"))
            } else json("stopOnEntry" to true)
            val launch=async { rpc.request("launch",launchArgs,30_000) }
            withTimeout(20_000) { initialized.await() }
            for((path,group) in points.groupBy { it.path }) setBreakpoints(path,group)
            rpc.request("configurationDone",timeout=10_000); launch.await()
            if(source.project.language in listOf(CodeLanguage.C,CodeLanguage.CPP)) {
                withTimeout(5000) {
                    while(!consoleReady.isFile) {
                        check(!adapter.exit.isCompleted) { "调试控制台未能连接" }
                        delay(10)
                    }
                }
                closeKeepers()
            }
            if(mutable.value.phase!=SessionPhase.PAUSED) mutable.update { it.copy(phase=SessionPhase.RUNNING) }
            if(preload!=null) { preloadInput(preload) }
            select<Unit> {
                terminated.onAwait { }
                adapter.exit.onAwait { if(!terminated.isCompleted && withTimeoutOrNull(500) { terminated.await(); true }!=true) error("调试器提前退出，请查看输出") }
            }
            if(user!=null) exitCode=withTimeoutOrNull(3000) { user!!.exit.await() } ?: exitCode
            exitCode
        } finally {
            withContext(NonCancellable) {
                events.cancel(); runCatching { rpc.request("disconnect",json("terminateDebuggee" to true),500) }; rpc.close(); connection=null
                adapter.stop(); user?.stop(); closeKeepers(); consoleHandles.forEach { runCatching { it.close() } }; streams.forEach { it.close() }
                debugTarget?.let { NativeProcess.signal(it,9,false) }; debugTarget=null
                readers.forEach { it.cancel() }
            }
        }
    }
    suspend fun setBreakpoints(path: String, points: List<Breakpoint>) {
        val rpc=connection ?: return; val source=snapshot ?: return
        val result=rpc.request("setBreakpoints",json("source" to json("path" to File(source.source,path).absolutePath),"breakpoints" to points.map { json("line" to it.line+1) }))
            .getAsJsonObject("body").getAsJsonArray("breakpoints")
        val values=result.mapIndexed { index, element ->
            val item=element.asJsonObject; item["id"]?.asInt?.let { pointIds[it]=path to index }
            Breakpoint(path,(item["line"]?.asInt ?: (points.getOrNull(index)?.line?.plus(1) ?: 1))-1,item["verified"]?.asBoolean ?: false,item["message"]?.asString ?: "")
        }
        mutable.update { it.copy(breakpointResults=it.breakpointResults+(path to values)) }
    }
    private suspend fun refreshStack(threadId: Int) {
        val rpc=connection ?: return
        val body=rpc.request("stackTrace",json("threadId" to threadId,"startFrame" to 0,"levels" to 100)).getAsJsonObject("body")
        val frames=body.getAsJsonArray("stackFrames").map { element -> val value=element.asJsonObject
            StackFrame(value["id"].asInt,value["name"].asString,value["source"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("path")?.asString,
                (value["line"]?.asInt ?: 1)-1,(value["column"]?.asInt ?: 1)-1)
        }.filter { frame -> frame.path!=null && snapshot?.source?.canonicalFile?.toPath()?.let { File(frame.path).canonicalFile.toPath().startsWith(it) }==true }
        if(state.value.phase!=SessionPhase.PAUSED) return
        mutable.update { it.copy(frames=frames) }; frames.firstOrNull()?.let { selectFrame(it.id) }
    }
    suspend fun selectFrame(id: Int) {
        val rpc=connection ?: return
        val scopes=rpc.request("scopes",json("frameId" to id)).getAsJsonObject("body").getAsJsonArray("scopes")
        val values=mutableListOf<DebugVariable>()
        for(scope in scopes.take(3)) values+=variables(scope.asJsonObject["variablesReference"].asInt)
        if(state.value.phase==SessionPhase.PAUSED) mutable.update { it.copy(frameId=id,variables=values.distinctBy { v -> v.name }) }
    }
    suspend fun variables(reference: Int): List<DebugVariable> {
        if(reference==0) return emptyList()
        val rpc=connection ?: return emptyList()
        return rpc.request("variables",json("variablesReference" to reference,"start" to 0,"count" to 100)).getAsJsonObject("body").getAsJsonArray("variables").map { item -> val v=item.asJsonObject
            DebugVariable(v["name"].asString,v["value"].asString,v["type"]?.asString ?: "",v["variablesReference"]?.asInt ?: 0)
        }
    }
    suspend fun watch(expression: String): DebugVariable {
        require(Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*(?:(?:\\.|->)[\\p{L}_$][\\p{L}\\p{N}_$]*|\\[\\d+])*").matches(expression)) { "观察项支持变量、字段和固定数组下标" }
        check(state.value.phase==SessionPhase.PAUSED) { "程序尚未暂停" }
        val rpc=connection ?: error("调试器未连接")
        if(snapshot?.project?.language in listOf(CodeLanguage.C,CodeLanguage.CPP)) {
            val tokens=Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*|\\[\\d+]").findAll(expression).map { it.value }.toList()
            var value=state.value.variables.firstOrNull { it.name==tokens.first() } ?: error("没有该变量")
            for(token in tokens.drop(1)) value=variables(value.reference).firstOrNull { it.name==token } ?: error("无法读取该字段或下标")
            return value.copy(name=expression)
        }
        val body=rpc.request("evaluate",json("expression" to expression,"frameId" to state.value.frameId,"context" to "watch")).getAsJsonObject("body")
        return DebugVariable(expression,body["result"].asString,body["type"]?.asString ?: "",body["variablesReference"]?.asInt ?: 0)
    }
    suspend fun command(command: String) { connection?.request(command,json("threadId" to state.value.threadId),3000) ?: error("调试器未连接") }
    suspend fun sendInput(text: String, newline: Boolean=true) = withContext(Dispatchers.IO) {
        val stream=stdin ?: error("标准输入已经关闭")
        synchronized(stream) { stream.write((text+if(newline) "\n" else "").toByteArray()); stream.flush() }
    }
    private suspend fun preloadInput(text: String) {
        try { sendInput(text,false) }
        catch(error:IOException) { append("\n[标准输入已关闭，剩余预置输入未发送]\n") }
        finally { eof() }
    }
    fun eof() { runCatching { stdin?.close() }; stdin=null }
    suspend fun stop(reason: String?=null) {
        forcedReason=reason
        job?.cancel()
        // Wake blocking protocol/console readers before cancelling their owners.
        children.values.forEach { NativeProcess.signal(it.pid,9,true) }
        debugTarget?.let { NativeProcess.signal(it,9,false) }
        withTimeoutOrNull(2500) { job?.join() }
        children.values.forEach { NativeProcess.signal(it.pid,9,true) }
    }
    private fun abort(reason: String) {
        forcedReason=reason
        job?.cancel()
        children.values.forEach { NativeProcess.signal(it.pid,9,true) }
        debugTarget?.let { NativeProcess.signal(it,9,false) }
    }
    private suspend fun monitor(source: Snapshot, timeoutSeconds: Int) {
        var elapsed=0L; var last=SystemClock.elapsedRealtime(); var filesAt=0L
        val baseline=source.work.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        while(currentCoroutineContext().isActive) {
            delay(250); val now=SystemClock.elapsedRealtime()
            if(state.value.phase==SessionPhase.RUNNING) elapsed+=now-last
            last=now
            if(elapsed>timeoutSeconds*1000L) { abort("TIMEOUT：超过 ${timeoutSeconds} 秒运行限制"); return }
            val max=if(state.value.phase==SessionPhase.BUILDING) 1024L*1024 else 512L*1024
            val pids=children.keys.toList()+listOfNotNull(debugTarget)
            for(pid in pids) {
                val rss=runCatching { File("/proc/$pid/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0 } }.getOrDefault(0)
                if(rss>max) { abort("MEMORY_LIMIT：程序超过内存限制"); return }
            }
            if(now-filesAt>1000) {
                filesAt=now; var size=0L; var count=0
                for(file in source.work.walkTopDown()) if(file.isFile) { size+=file.length(); if(++count>5000 || size-baseline>100L*1024*1024) { abort("OUTPUT_LIMIT：运行产物超过限制"); return } }
            }
        }
    }
}
