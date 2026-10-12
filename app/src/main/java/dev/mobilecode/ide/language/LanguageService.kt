package dev.mobilecode.ide.language

import com.google.gson.*
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.AtomicFiles
import dev.mobilecode.ide.protocol.JsonConnection
import dev.mobilecode.ide.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LanguageService(private val scope: CoroutineScope, private val factory: ProcessFactory, private val stateRoot: File) {
    private var process: ChildProcess?=null
    private var client: JsonConnection?=null
    private var eventJob: Job?=null
    private var project: Project?=null
    private var chain: Toolchain?=null
    @Volatile private var ready=false
    private val opened=ConcurrentHashMap<String,Int>()
    private val lock=Mutex()
    private val crashes=ArrayDeque<Long>()
    private val mutableDiagnostics=MutableStateFlow<List<Diagnostic>>(emptyList())
    private val mutableStatus=MutableStateFlow("基础补全")
    val diagnostics=mutableDiagnostics.asStateFlow()
    val status=mutableStatus.asStateFlow()

    suspend fun activate(next: Project, toolchain: Toolchain): Unit = lock.withLock {
        if(project?.config?.id==next.config.id && client!=null) return@withLock
        closeUnlocked(); project=next; chain=toolchain
        if(crashes.size>=3 && System.currentTimeMillis()-crashes.last()<60_000) { mutableStatus.value="语言服务暂歇，保留基础补全"; return@withLock }
        mutableStatus.value="正在准备 ${next.config.language.title} 语义分析"
        val state=File(stateRoot,next.config.id).apply { mkdirs() }
        val p=when(next.config.language) {
            CodeLanguage.C,CodeLanguage.CPP -> {
                val fake=Snapshot("language",next.config,state,next.directory,state,state,"")
                val commands=CompilerCommands.sources(next.directory,next.config).map { file ->
                    json("directory" to next.directory.absolutePath,"file" to file.absolutePath,"arguments" to (listOf(toolchain.command("clang").absolutePath)+
                        CompilerCommands.common(toolchain,fake,if(file.extension=="c") CodeLanguage.C else CodeLanguage.CPP)+listOf("-c",file.absolutePath)))
                }.toList()
                AtomicFiles.text(File(state,"compile_commands.json"),gson.toJson(commands))
                factory.start(toolchain,toolchain.command("clangd"),listOf("--background-index","--clang-tidy=false","--header-insertion=never","--compile-commands-dir=$state","--resource-dir=${toolchain.clangResources}","-j=2"),state)
            }
            CodeLanguage.PYTHON -> {
                val config=File(state,"python-lsp.json")
                AtomicFiles.text(config,json("source" to next.directory.absolutePath,"sourceRoots" to next.config.sourceRoots).toString())
                factory.python(toolchain,"lsp",config,state)
            }
            CodeLanguage.JAVA -> {
                fun xml(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;")
                AtomicFiles.text(File(next.directory,".project"),"<?xml version=\"1.0\"?><projectDescription><name>${xml(next.config.name)}</name><buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name><arguments/></buildCommand></buildSpec><natures><nature>org.eclipse.jdt.core.javanature</nature></natures></projectDescription>")
                AtomicFiles.text(File(next.directory,".classpath"),"<?xml version=\"1.0\"?><classpath>"+next.config.sourceRoots.joinToString("") { "<classpathentry kind=\"src\" path=\"${xml(if(it==".") "" else it)}\"/>" }+"<classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER\"/><classpathentry kind=\"output\" path=\".mobilecode/classes\"/></classpath>")
                AtomicFiles.text(File(next.directory,".settings/org.eclipse.jdt.core.prefs"),"eclipse.preferences.version=1\norg.eclipse.jdt.core.compiler.compliance=17\norg.eclipse.jdt.core.compiler.source=17\norg.eclipse.jdt.core.compiler.codegen.targetPlatform=17\norg.eclipse.jdt.core.compiler.release=enabled\n")
                val config=File(state,"configuration").apply { mkdirs() }
                val common=File(toolchain.root,"usr/share/jdtls")
                File(common,"config_linux").copyRecursively(config,overwrite=true)
                factory.java(toolchain,"org.eclipse.equinox.launcher.Main",listOf("-configuration",config.absolutePath,"-data",File(state,"workspace").absolutePath),state,
                    classpath=toolchain.jdtLauncher.absolutePath,vmOptions=listOf("-Xmx384m","-Declipse.application=org.eclipse.jdt.ls.core.id1",
                        "-Declipse.product=org.eclipse.jdt.ls.core.product","-Dosgi.bundles.defaultStartLevel=4","-Dosgi.install.area=${common.toURI()}","-Dlog.level=WARNING"))
            }
        }
        process=p
        scope.launch(Dispatchers.IO) {
            runCatching {
                File(state,"language.log").outputStream().use { log -> var size=0
                    p.error.bufferedReader().useLines { lines -> lines.forEach { line -> if(size<1024*1024) { val bytes=(line+"\n").toByteArray(); log.write(bytes); size+=bytes.size } } }
                }
            }
        }
        val rpc=JsonConnection(p.output,p.input,scope,serverRequest={ request ->
            when(request["method"]?.asString) {
                "workspace/configuration" -> JsonArray().apply { repeat(request.getAsJsonObject("params").getAsJsonArray("items").size()) { add(JsonObject()) } }
                "workspace/workspaceFolders" -> gson.toJsonTree(listOf(json("uri" to next.directory.toURI().toString(),"name" to next.config.name)))
                else -> JsonNull.INSTANCE
            }
        })
        client=rpc
        eventJob=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            rpc.events.collect { event ->
                if(project?.config?.id!=next.config.id || client!==rpc) return@collect
                if(event["method"]?.asString=="textDocument/publishDiagnostics") {
                    val params=event.getAsJsonObject("params"); val uri=params["uri"].asString
                    val path=File(java.net.URI(uri)).absolutePath
                    val version=params["version"]?.takeUnless { it.isJsonNull }?.asInt
                    val relative=runCatching { File(path).relativeTo(next.directory).invariantSeparatorsPath }.getOrDefault(path)
                    if(version==null || opened[relative]==version) {
                        val items=params.getAsJsonArray("diagnostics").map { item ->
                            val value=item.asJsonObject
                            Diagnostic(path,gson.fromJson(value["range"],SourceRange::class.java),value["message"].asString,value["severity"]?.asInt ?: 1,version,value["source"]?.asString ?: next.config.language.title)
                        }
                        mutableDiagnostics.value=mutableDiagnostics.value.filterNot { it.path==path }+items
                    }
                }
            }
        }
        try {
            val capability=json("workspace" to json("configuration" to true,"workspaceFolders" to true),
                "general" to json("positionEncodings" to listOf("utf-16")),
                "textDocument" to json("completion" to json("completionItem" to json("snippetSupport" to true,"insertReplaceSupport" to true,"documentationFormat" to listOf("plaintext"))),
                    "signatureHelp" to json(),"publishDiagnostics" to json("versionSupport" to true)))
            rpc.request("initialize",json("processId" to android.os.Process.myPid(),"rootUri" to next.directory.toPath().toUri().toString(),"capabilities" to capability,
                "workspaceFolders" to listOf(json("uri" to next.directory.toPath().toUri().toString(),"name" to next.config.name)),
                "initializationOptions" to json("settings" to json("java" to json("import" to json("maven" to json("enabled" to false),"gradle" to json("enabled" to false)),"configuration" to json("runtimes" to listOf(json("name" to "JavaSE-21","path" to toolchain.javaHome.absolutePath,"default" to true))))))),30_000)
            rpc.notify("initialized"); ready=true; mutableStatus.value="${next.config.language.title} 补全已就绪"
            scope.launch { p.exit.await(); if(process===p) { crashes.addLast(System.currentTimeMillis()); while(crashes.isNotEmpty() && System.currentTimeMillis()-crashes.first()>60_000) crashes.removeFirst(); mutableStatus.value="语言服务已断开，基础补全可用"; close(); delay(1500); if(crashes.size<3) runCatching { activate(next,toolchain) } } }
        } catch(error: Exception) { closeUnlocked(); mutableStatus.value="语义服务启动失败：${error.message}"; throw error }
        Unit
    }
    suspend fun change(document: Document) {
        val p=project ?: return; val rpc=client ?: return; if(!ready) return
        val uri=File(p.directory,document.path).toPath().toUri().toString()
        val old=opened.put(document.path,document.version)
        if(old==null) rpc.notify("textDocument/didOpen",json("textDocument" to json("uri" to uri,"languageId" to p.config.language.lspId,"version" to document.version,"text" to document.text)))
        else if(old!=document.version) rpc.notify("textDocument/didChange",json("textDocument" to json("uri" to uri,"version" to document.version),"contentChanges" to listOf(json("text" to document.text))))
    }
    suspend fun complete(document: Document, position: Position): List<Completion> {
        val p=project ?: return emptyList(); val rpc=client ?: return emptyList(); if(!ready) return emptyList()
        change(document)
        val response=try {
            rpc.request("textDocument/completion",json("textDocument" to json("uri" to File(p.directory,document.path).toPath().toUri().toString()),"position" to position))
        } catch(timeout: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); return emptyList() }
        if(opened[document.path]!=document.version) return emptyList()
        val result=response["result"] ?: return emptyList()
        if(result.isJsonNull) return emptyList()
        val items=if(result.isJsonArray) result.asJsonArray else result.asJsonObject.getAsJsonArray("items") ?: return emptyList()
        return items.take(100).map { element ->
            val item=element.asJsonObject; val edit=item["textEdit"]?.takeIf { it.isJsonObject }?.asJsonObject
            val range=edit?.get("range") ?: edit?.get("insert") ?: edit?.get("replace")
            val text=edit?.get("newText")?.asString ?: item["insertText"]?.asString ?: item["label"].asString
            val extra=item["additionalTextEdits"]?.takeIf { it.isJsonArray }?.asJsonArray?.map { e -> gson.fromJson(e.asJsonObject["range"],SourceRange::class.java) to e.asJsonObject["newText"].asString } ?: emptyList()
            Completion(item["label"].asString,text,item["detail"]?.takeUnless { it.isJsonNull }?.asString ?: "",range?.let { gson.fromJson(it,SourceRange::class.java) },extra,item["insertTextFormat"]?.asInt==2,document.version)
        }
    }
    suspend fun signature(document: Document, position: Position): String {
        val p=project ?: return ""; val rpc=client ?: return ""; if(!ready) return ""; change(document)
        val result=rpc.request("textDocument/signatureHelp",json("textDocument" to json("uri" to File(p.directory,document.path).toPath().toUri().toString()),"position" to position))["result"] ?: return ""
        if(result.isJsonNull || opened[document.path]!=document.version) return ""
        return result.asJsonObject.getAsJsonArray("signatures")?.firstOrNull()?.asJsonObject?.get("label")?.asString ?: ""
    }
    suspend fun closeDocument(path: String) {
        val p=project ?: return; opened.remove(path)
        client?.notify("textDocument/didClose",json("textDocument" to json("uri" to File(p.directory,path).toPath().toUri().toString())))
    }
    suspend fun close() = lock.withLock { closeUnlocked() }
    private suspend fun closeUnlocked() { ready=false; eventJob?.cancel(); client?.close(); client=null; val p=process; process=null; p?.stop(); p?.closeStreams(); opened.clear(); mutableDiagnostics.value=emptyList() }
}
