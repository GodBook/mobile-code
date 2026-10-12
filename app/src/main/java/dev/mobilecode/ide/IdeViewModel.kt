package dev.mobilecode.ide

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

data class Navigation(val line: Int=0,val column: Int=0,val serial: Long=0)
data class Preview(val title: String,val document: Document)
data class IdeState(val projects: List<Project> = emptyList(),val project: Project?=null,val files: List<String> = emptyList(),
    val documents: Map<String,Document> = emptyMap(),val activePath: String="",val breakpoints: List<Breakpoint> = emptyList(),
    val preview: Preview?=null,val navigation: Navigation=Navigation(),val error: String?=null,val busy: Boolean=false,
    val signature: String="",val watchExpressions: List<String> = emptyList(),val watchValues: List<DebugVariable> = emptyList()) {
    val document get() = preview?.document ?: documents[activePath]
}

class IdeViewModel(application: Application): AndroidViewModel(application) {
    val app=application as MobileCodeApp
    private val mutable=MutableStateFlow(IdeState())
    val state=mutable.asStateFlow()
    val settings=app.settings.flow.stateIn(viewModelScope,SharingStarted.Eagerly,EditorSettings())
    val toolchains=app.toolchains.status
    val session=app.sessions.state
    val diagnostics=combine(app.languages.diagnostics,session) { live,run -> live+run.diagnostics }.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val languageStatus=app.languages.status
    private val saves=mutableMapOf<String,Job>()
    private val saveLock=Mutex()
    private var activation: Job?=null
    private var signatureJob: Job?=null
    private var cursorSave: Job?=null
    private var navigationId=0L

    init {
        viewModelScope.launch {
            guarded {
                val projects=app.workspace.projects(); mutable.update { it.copy(projects=projects) }
                val restored=app.settings.flow.first()
                projects.find { it.config.id==restored.lastProject }?.let { select(it,restored.lastFile,Position(restored.cursorLine,restored.cursorColumn)) }
            }
        }
        viewModelScope.launch { toolchains.collect { if(it.toolchain!=null) activate() } }
        viewModelScope.launch {
            session.map { Triple(it.phase,it.frameId,it.frames to it.stopSequence) }.distinctUntilChanged().collect { (phase,frameId,observation) ->
                val frames=observation.first
                if(phase==SessionPhase.PAUSED && state.value.project?.config?.id==app.sessions.snapshot?.project?.id) {
                    (frames.find { it.id==frameId } ?: frames.firstOrNull())?.let { frame -> if(frame.path!=null) navigate(frame.path,Position(frame.line,frame.column)) }
                    refreshWatches()
                }
            }
        }
    }
    private suspend fun guarded(block: suspend ()->Unit) {
        try { block() } catch(cancel: CancellationException) { throw cancel } catch(error:Throwable) { mutable.update { it.copy(error=error.message ?: error.toString(),busy=false) } }
    }
    fun clearError() { mutable.update { it.copy(error=null) } }
    fun report(error: String) { mutable.update { it.copy(error=error) } }
    fun document(path: String): Document? = mutable.value.documents[path]
    fun createProject(name: String, language: CodeLanguage, multiple: Boolean) = viewModelScope.launch {
        guarded {
            check(!session.value.active) { "请先停止当前任务" }; saveAll()
            val created=app.workspace.create(name,language,multiple)
            val project=app.workspace.updateConfig(created,created.config.copy(runTimeoutSeconds=settings.value.runTimeout))
            val projects=app.workspace.projects(); mutable.update { it.copy(projects=projects) }; select(project)
        }
    }
    fun openProject(project: Project) = viewModelScope.launch { guarded { check(!session.value.active) { "请先停止当前任务再切换项目" }; saveAll(); select(project) } }
    private suspend fun select(project: Project, file: String="", cursor: Position=Position(0,0)) {
        activation?.cancelAndJoin(); app.languages.close()
        val files=app.workspace.files(project)
        val chosen=file.takeIf { it in files } ?: files.firstOrNull { project.config.language.isSource(it) } ?: files.firstOrNull().orEmpty()
        val document=chosen.takeIf { it.isNotBlank() }?.let { app.workspace.open(project,it) }
        val points=app.workspace.breakpoints(project)
        mutable.update { it.copy(project=project,files=files,documents=if(document!=null) mapOf(chosen to document) else emptyMap(),activePath=chosen,
            preview=null,breakpoints=points,navigation=Navigation(cursor.line,cursor.character,++navigationId),signature="") }
        app.settings.update(settings.value.copy(lastProject=project.config.id,lastFile=chosen,cursorLine=cursor.line,cursorColumn=cursor.character))
        activate()
    }
    private fun activate() {
        val project=state.value.project ?: return; val chain=toolchains.value.toolchain ?: return
        activation?.cancel()
        activation=viewModelScope.launch { guarded { app.languages.activate(project,chain); state.value.documents.values.forEach { app.languages.change(it) } } }
    }
    fun retryTools()=viewModelScope.launch { guarded { app.toolchains.prepare(); activate() } }
    fun home()=viewModelScope.launch { guarded { check(!session.value.active) { "请先停止当前任务" }; saveAll(); activation?.cancelAndJoin(); app.languages.close(); mutable.update { it.copy(project=null,documents=emptyMap(),activePath="",preview=null) } } }
    fun openFile(path: String, position: Position?=null)=viewModelScope.launch { guarded { open(path,position) } }
    private suspend fun open(path: String, position: Position?=null) {
        val project=state.value.project ?: return
        val previous=state.value.documents[state.value.activePath]
        if(previous?.dirty==true) saveDocument(previous)
        val document=state.value.documents[path] ?: app.workspace.open(project,path)
        mutable.update { it.copy(documents=it.documents+(path to document),activePath=path,preview=null,signature="",
            navigation=position?.let { p -> Navigation(p.line,p.character,++navigationId) } ?: Navigation()) }
        app.languages.change(document)
        app.settings.update(settings.value.copy(lastProject=project.config.id,lastFile=path))
    }
    fun closeFile(path: String)=viewModelScope.launch { guarded {
        val document=state.value.documents[path] ?: return@guarded; if(document.dirty) saveDocument(document)
        app.languages.closeDocument(path)
        val remaining=state.value.documents-path
        mutable.update { it.copy(documents=remaining,activePath=if(it.activePath==path) remaining.keys.lastOrNull().orEmpty() else it.activePath) }
    } }
    fun createFile(path: String)=viewModelScope.launch { guarded {
        check(!session.value.sourceLocked) { "调试期间请先停止再添加文件" }
        val project=state.value.project ?: return@guarded
        app.workspace.createFile(project,path); val files=app.workspace.files(project); mutable.update { it.copy(files=files) }; open(path)
    } }
    fun edit(path: String, text: String, line: Int, delta: Int, removedUntil: Int) {
        if(session.value.sourceLocked || state.value.preview!=null) return
        val old=state.value.documents[path] ?: return
        if(old.text==text) return
        val document=old.copy(text=text,version=old.version+1)
        val moved=state.value.breakpoints.map { point -> if(point.path!=path || delta==0) point else point.copy(line=when {
            delta>0 && point.line>line -> point.line+delta
            delta<0 && point.line>removedUntil -> point.line+delta
            delta<0 && point.line>line -> line
            else -> point.line
        },verified=false) }.distinctBy { it.path to it.line }
        mutable.update { it.copy(documents=it.documents+(path to document),breakpoints=moved) }
        saves.remove(path)?.cancel()
        saves[path]=viewModelScope.launch { delay(500); guarded { saveDocument(document) } }
        viewModelScope.launch { guarded { app.languages.change(document) } }
    }
    private suspend fun saveDocument(document: Document)=saveLock.withLock {
        val project=state.value.project ?: return@withLock
        app.workspace.save(project,document)
        mutable.update { state -> val current=state.documents[document.path] ?: return@update state; state.copy(documents=state.documents+(document.path to current.copy(savedVersion=document.version))) }
    }
    suspend fun saveAll() {
        saves.values.forEach { it.cancel() }; saves.clear()
        state.value.documents.values.filter { it.dirty }.forEach { saveDocument(it) }
        state.value.project?.let { app.workspace.saveBreakpoints(it,state.value.breakpoints) }
    }
    fun flush()=app.scope.launch { guarded { saveAll() } }
    fun savePosition(line: Int,column: Int) {
        if(state.value.preview!=null) return
        val path=state.value.activePath
        cursorSave?.cancel()
        cursorSave=viewModelScope.launch { delay(300); app.settings.update(settings.value.copy(lastFile=path,cursorLine=line,cursorColumn=column)) }
        signatureJob?.cancel()
        signatureJob=viewModelScope.launch {
            delay(200)
            val document=state.value.document ?: return@launch
            val index=Positions.index(document.text,Position(line,column))
            val recent=document.text.take(index).takeLast(100)
            if(recent.contains('(')) {
                val signature=runCatching { app.languages.signature(document,Position(line,column)) }.getOrDefault("")
                if(state.value.document?.version==document.version) mutable.update { it.copy(signature=signature) }
            } else mutable.update { it.copy(signature="") }
        }
    }
    fun toggleBreakpoint(path: String,line: Int) {
        val old=state.value.breakpoints
        val boundIndex=if(session.value.sourceLocked) session.value.breakpointResults[path]?.indexOfFirst { it.line==line } ?: -1 else -1
        val requestedLine=if(boundIndex>=0) old.filter { it.path==path }.getOrNull(boundIndex)?.line ?: line else line
        val next=if(old.any { it.path==path && it.line==requestedLine }) old.filterNot { it.path==path && it.line==requestedLine } else old+Breakpoint(path,line)
        mutable.update { it.copy(breakpoints=next) }
        viewModelScope.launch { guarded { state.value.project?.let { app.workspace.saveBreakpoints(it,next) }; if(session.value.sourceLocked) app.sessions.setBreakpoints(path,next.filter { it.path==path }) } }
    }
    fun run(debug: Boolean)=viewModelScope.launch {
        guarded {
            check(!session.value.active && !state.value.busy) { "已有任务正在执行" }
            val project=state.value.project ?: return@guarded
            val chain=toolchains.value.toolchain ?: error("本地工具链尚未准备完成")
            mutable.update { it.copy(busy=true,preview=null) }; saveAll()
            app.sessions.start(project,chain,debug,state.value.breakpoints,project.config.preloadedInput,UUID.randomUUID().toString())
            mutable.update { it.copy(busy=false) }
        }
    }
    fun stop()=viewModelScope.launch { app.sessions.stop() }
    fun restart()=viewModelScope.launch { val debug=session.value.debugging; app.sessions.stop(); run(debug) }
    fun debugCommand(command: String)=viewModelScope.launch { guarded { app.sessions.command(command) } }
    fun input(text: String)=viewModelScope.launch { guarded { app.sessions.sendInput(text) } }
    fun eof()=app.sessions.eof()
    fun selectFrame(frame: StackFrame)=viewModelScope.launch { guarded { app.sessions.selectFrame(frame.id); frame.path?.let { navigate(it,Position(frame.line,frame.column)) }; refreshWatches() } }
    fun expandVariable(variable: DebugVariable,onResult: (List<DebugVariable>)->Unit)=viewModelScope.launch { guarded { onResult(app.sessions.variables(variable.reference)) } }
    fun addWatch(expression: String) {
        if(expression.isBlank()) return
        mutable.update { it.copy(watchExpressions=(it.watchExpressions+expression.trim()).distinct()) }
        viewModelScope.launch { refreshWatches() }
    }
    fun removeWatch(expression: String) { mutable.update { it.copy(watchExpressions=it.watchExpressions-expression,watchValues=it.watchValues.filterNot { v -> v.name==expression }) } }
    private suspend fun refreshWatches() {
        val values=state.value.watchExpressions.map { expression -> runCatching { app.sessions.watch(expression) }.getOrElse { DebugVariable(expression,it.message ?: "暂不可用") } }
        mutable.update { it.copy(watchValues=values) }
    }
    fun diagnostic(item: Diagnostic)=viewModelScope.launch { guarded { navigate(item.path,item.range.start) } }
    private suspend fun navigate(path: String, position: Position) {
        val project=state.value.project ?: return
        val file=File(path); val snapshot=app.sessions.snapshot
        val relative=when {
            file.toPath().startsWith(project.directory.toPath()) -> file.relativeTo(project.directory).invariantSeparatorsPath
            snapshot!=null && file.toPath().startsWith(snapshot.source.toPath()) -> file.relativeTo(snapshot.source).invariantSeparatorsPath
            else -> null
        }
        val current=relative?.let { state.value.documents[it]?.text ?: runCatching { File(project.directory,it).readText() }.getOrNull() }
        if(relative!=null && current==runCatching { file.readText() }.getOrNull()) open(relative,position)
        else {
            val text=withContext(Dispatchers.IO) { file.readText() }
            mutable.update { it.copy(preview=Preview("运行快照 · ${file.name}",Document(relative ?: path,text)),navigation=Navigation(position.line,position.character,++navigationId)) }
        }
    }
    fun closePreview() { mutable.update { it.copy(preview=null,navigation=Navigation()) } }
    fun updateSettings(value: EditorSettings)=viewModelScope.launch { guarded { app.settings.update(value) } }
    fun configureRun(entry: String,args: List<String>,timeout: Int,preload: String?)=viewModelScope.launch { guarded {
        val project=state.value.project ?: return@guarded
        val updated=app.workspace.updateConfig(project,project.config.copy(entry=entry,arguments=args,runTimeoutSeconds=timeout,preloadedInput=preload))
        mutable.update { it.copy(project=updated) }
    } }
    fun importProject(uri: Uri)=viewModelScope.launch { guarded {
        check(!session.value.active) { "请先停止当前任务" }; saveAll()
        val project=app.workspace.importProject(uri); val projects=app.workspace.projects(); mutable.update { it.copy(projects=projects) }; select(project)
    } }
    fun exportProject(uri: Uri)=viewModelScope.launch { guarded { saveAll(); state.value.project?.let { app.workspace.export(it,uri) } } }
    fun exportSource(uri: Uri)=viewModelScope.launch { guarded { saveAll(); state.value.project?.let { app.workspace.exportSource(it,state.value.activePath,uri) } } }
    fun installUpdate(onReady: (File)->Unit)=viewModelScope.launch { guarded {
        check(!session.value.active) { "请先停止运行或调试，再安装更新" }
        saveAll()
        val apk=app.updates.verifiedApk()
        check(!session.value.active) { "请先停止运行或调试，再安装更新" }
        onReady(apk)
    } }
}
