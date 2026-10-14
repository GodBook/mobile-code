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

data class Navigation(val line: Int=0,val column: Int=0,val serial: Long=0,val restoreScroll: Boolean=false)
data class Preview(val title: String,val document: Document,val file: File?=null)
data class FileSaveState(val phase: String="saved",val message: String="",val savedAt: Long=0)
data class ExportContent(val name: String,val text: String?=null,val file: File?=null,val directory: File?=null)
data class EditorDiagnosticState(val projectId: String="",val document: Document?=null,val items: List<Diagnostic> = emptyList())
data class IdeState(val projects: List<Project> = emptyList(),val project: Project?=null,val files: List<String> = emptyList(),
    val documents: Map<String,Document> = emptyMap(),val activePath: String="",val breakpoints: List<Breakpoint> = emptyList(),
    val preview: Preview?=null,val navigation: Navigation=Navigation(),val error: String?=null,val busy: Boolean=false,
    val signature: String="",val watchExpressions: List<String> = emptyList(),val watchValues: List<DebugVariable> = emptyList(),
    val saveStates: Map<String,FileSaveState> = emptyMap(),val memories: Map<String,ProjectMemory> = emptyMap(),
    val recoveries: List<RecoveryDraft> = emptyList(),val importChoice: ImportLanguageChoice?=null,
    val searchResults: List<TextMatch> = emptyList(),val searching: Boolean=false,val searchQuery: String="",
    val locations: List<CodeLocation> = emptyList(),val symbols: List<CodeSymbol> = emptyList(),val navigationBusy: Boolean=false,
    val storage: List<StorageItem> = emptyList(),val history: List<StoredSession> = emptyList(),
    val recycled: List<TrashRecord> = emptyList(),val export: ExportContent?=null,val command: String="",val commandSerial: Long=0) {
    val document get() = preview?.document ?: documents[activePath]
}

class IdeViewModel(application: Application): AndroidViewModel(application) {
    val app=application as MobileCodeApp
    private val mutable=MutableStateFlow(IdeState())
    val state=mutable.asStateFlow()
    val settings=app.settings.flow.stateIn(viewModelScope,SharingStarted.Eagerly,EditorSettings())
    val toolchains=app.toolchains.status
    val session=app.sessions.state
    val diagnostics=combine(app.languages.diagnostics,session,state) { live,run,ide ->
        (live+run.diagnostics).filter { it.projectId==ide.project?.config?.id && it.projectId!=null }.distinct()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val editorDiagnostics=combine(diagnostics,state) { items,ide ->
        val project=ide.project; val document=ide.document
        if(project==null || document==null) EditorDiagnosticState() else {
            val visible=if(ide.preview!=null && ide.preview.file==null) emptyList() else
                items.filter { DiagnosticProvenance.matchesEditor(it,project,document,ide.preview?.file) }
            EditorDiagnosticState(project.config.id,document,visible)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope,SharingStarted.Eagerly,EditorDiagnosticState())
    val languageStatus=app.languages.status
    private val saves=mutableMapOf<String,Job>()
    private val languageChanges=mutableMapOf<String,Job>()
    private val saveLock=Mutex()
    private val drafts=mutableMapOf<String,Job>()
    private var workspaceSave: Job?=null
    private var importJob: Job?=null
    private var searchJob: Job?=null
    private var fileLocations=emptyMap<String,FileLocation>()
    private val closedTabs=ArrayDeque<String>()
    private val backLocations=ArrayDeque<CodeLocation>()
    private val forwardLocations=ArrayDeque<CodeLocation>()
    private var activation: Job?=null
    private var signatureJob: Job?=null
    private var cursorSave: Job?=null
    private var navigationId=0L
    private val restoration: Job=viewModelScope.launch { restoreWorkspace() }

    init {
        viewModelScope.launch { toolchains.collect { if(it.toolchain!=null) activate() } }
        viewModelScope.launch { observeSessions() }
    }
    private suspend fun restoreWorkspace() {
        guarded {
            refreshProjects()
            val restored=app.settings.flow.first()
            state.value.projects.find { it.config.id==restored.lastProject }?.let { select(it,restored.lastFile,Position(restored.cursorLine,restored.cursorColumn)) }
        }
    }
    private suspend fun observeSessions() {
            session.map { Triple(it.phase,it.frameId,it.frames to it.stopSequence) }.distinctUntilChanged().collect { (phase,frameId,observation) ->
                val frames=observation.first
                if(phase==SessionPhase.PAUSED && state.value.project?.config?.id==app.sessions.snapshot?.project?.id) {
                    (frames.find { it.id==frameId } ?: frames.firstOrNull())?.let { frame -> if(frame.path!=null) navigate(frame.path,Position(frame.line,frame.column)) }
                    refreshWatches()
                }
            }
    }
    private suspend fun guarded(block: suspend ()->Unit) {
        try { block() } catch(cancel: CancellationException) { throw cancel } catch(error:Throwable) { mutable.update { it.copy(error=error.message ?: error.toString(),busy=false) } }
    }
    fun clearError() { mutable.update { it.copy(error=null) } }
    fun report(error: String) { mutable.update { it.copy(error=error) } }
    fun uiCommand(command: String) { mutable.update { it.copy(command=command,commandSerial=it.commandSerial+1) } }
    fun viewRefresh()=viewModelScope.launch { guarded { refreshProjects() } }
    fun document(path: String): Document? = mutable.value.documents[path]
    suspend fun refreshProjects() {
        val projects=app.workspace.projects(); val memories=app.journal.all()
        mutable.update { it.copy(projects=projects,memories=memories) }
    }
    fun createProject(name: String, language: CodeLanguage, multiple: Boolean) = viewModelScope.launch {
        restoration.join()
        guarded {
            check(!session.value.active) { "请先停止当前任务" }; saveAll()
            val created=app.workspace.create(name,language,multiple)
            val project=app.workspace.updateConfig(created,created.config.copy(runTimeoutSeconds=settings.value.runTimeout))
            val projects=app.workspace.projects(); mutable.update { it.copy(projects=projects) }; select(project)
        }
    }
    fun openProject(project: Project) = viewModelScope.launch { restoration.join(); guarded { check(!session.value.active) { "请先停止当前任务再切换项目" }; saveAll(); select(project) } }
    private suspend fun select(project: Project, file: String="", cursor: Position=Position(0,0)) {
        workspaceSave?.cancel(); searchJob?.cancel(); drafts.values.forEach { it.cancel() }; drafts.clear()
        languageChanges.values.forEach { it.cancel() }; languageChanges.clear(); cursorSave?.cancel(); signatureJob?.cancel()
        activation?.cancelAndJoin(); app.languages.close()
        val files=app.workspace.files(project)
        val memory=app.journal.project(project.config.id).workspace
        fileLocations=memory.locations; closedTabs.clear(); backLocations.clear(); forwardLocations.clear()
        val chosen=file.takeIf { it in files } ?: memory.active.takeIf { it in files } ?: files.firstOrNull { project.config.language.isSource(it) } ?: files.firstOrNull().orEmpty()
        val documents=linkedMapOf<String,Document>()
        var restoredBytes=0L
        (memory.tabs+chosen).distinct().filter { it in files }.take(12).forEach { path ->
            val size=File(project.directory,path).length()
            if(path==chosen || restoredBytes+size<=8L*1024*1024) runCatching { app.workspace.open(project,path) }.getOrNull()?.let { documents[path]=it; restoredBytes+=size }
        }
        if(chosen.isNotBlank() && chosen !in documents) documents[chosen]=app.workspace.open(project,chosen)
        val points=app.workspace.breakpoints(project)
        val position=fileLocations[chosen] ?: if(file.isNotBlank()) FileLocation(cursor.line,cursor.character) else FileLocation()
        val recoveries=app.journal.drafts(project)
        mutable.update { it.copy(project=project,files=files,documents=documents,activePath=chosen,
            preview=null,breakpoints=points,navigation=Navigation(position.line,position.column,++navigationId,restoreScroll=true),signature="",
            saveStates=emptyMap(),recoveries=recoveries,watchExpressions=memory.watches,watchValues=emptyList(),searchResults=emptyList(),locations=emptyList(),symbols=emptyList()) }
        app.journal.opened(project.config.id)
        app.settings.location(project.config.id,chosen,position.line,position.column)
        refreshProjects(); persistWorkspace()
        val skipped=memory.tabs.count { it !in documents }
        if(skipped>0) report("有 $skipped 个标签因文件缺失或容量预算未自动打开；原文件不会被覆盖，可在文件列表重新打开")
        activate()
    }
    private fun activate() {
        val project=state.value.project ?: return; val chain=toolchains.value.toolchain ?: return
        activation?.cancel()
        activation=viewModelScope.launch { guarded { app.languages.activate(project,chain); state.value.documents.values.forEach { app.languages.change(it) } } }
    }
    fun retryTools()=viewModelScope.launch { guarded {
        check(!session.value.active && !state.value.busy) { "请先停止任务再检查组件" }
        mutable.update { it.copy(busy=true) }
        try { activation?.cancelAndJoin(); app.languages.close(); app.toolchains.prepare(repair=true) }
        finally { mutable.update { it.copy(busy=false) } }
    } }
    fun retryLanguage()=viewModelScope.launch { guarded { activation?.cancelAndJoin(); app.languages.close(); activate() } }
    fun foreground() { if(restoration.isCompleted) activate() }
    fun home()=viewModelScope.launch { restoration.join(); guarded {
        check(!session.value.active) { "请先停止当前任务" }; saveAll()
        persistWorkspace(); searchJob?.cancel()
        languageChanges.values.forEach { it.cancel() }; languageChanges.clear(); cursorSave?.cancel(); signatureJob?.cancel()
        activation?.cancelAndJoin(); app.languages.close()
        mutable.update { it.copy(project=null,documents=emptyMap(),activePath="",preview=null) }
    } }
    fun openFile(path: String, position: Position?=null)=viewModelScope.launch { guarded { open(path,position) } }
    private suspend fun open(path: String, position: Position?=null) {
        val project=state.value.project ?: return
        val previous=state.value.documents[state.value.activePath]
        if(previous?.dirty==true) saveDocument(previous)
        val existing=state.value.documents[path]
        if(existing==null) {
            val resident=state.value.documents.values.sumOf { it.text.length*2L }
            val nextBytes=resolveInside(project.directory,path).length()*2L
            require(resident+nextBytes<=32L*1024*1024) { "打开的文本已达内存预算，请先关闭已保存的标签；未保存内容会保留" }
        }
        val document=existing ?: app.workspace.open(project,path)
        val target=position ?: fileLocations[path]?.let { Position(it.line,it.column) }
        mutable.update { it.copy(documents=it.documents+(path to document),activePath=path,preview=null,signature="",
            navigation=target?.let { p -> Navigation(p.line,p.character,++navigationId,restoreScroll=position==null) } ?: Navigation()) }
        app.languages.change(document)
        cursorSave?.cancel()
        app.settings.location(project.config.id,path,target?.line ?: 0,target?.character ?: 0)
        persistWorkspace()
    }
    fun closeFile(path: String)=viewModelScope.launch { guarded {
        val document=state.value.documents[path] ?: return@guarded; if(document.dirty) saveDocument(document)
        languageChanges.remove(path)?.cancel()
        app.languages.closeDocument(path)
        val remaining=state.value.documents-path
        closedTabs.addLast(path); if(closedTabs.size>20) closedTabs.removeFirst()
        mutable.update { it.copy(documents=remaining,saveStates=it.saveStates-path,activePath=if(it.activePath==path) remaining.keys.lastOrNull().orEmpty() else it.activePath) }
        persistWorkspace()
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
        mutable.update { it.copy(documents=it.documents+(path to document),breakpoints=moved,saveStates=it.saveStates+(path to FileSaveState("pending",savedAt=it.saveStates[path]?.savedAt ?: 0))) }
        val project=state.value.project ?: return
        drafts.remove(path)?.cancel()
        drafts[path]=viewModelScope.launch { delay(200); try { app.journal.draft(project,document) } catch(cancel: CancellationException) { throw cancel } catch(error: Throwable) { report("恢复草稿未能写入：${error.message}；请保留编辑器并导出未保存内容") } }
        saves.remove(path)?.cancel()
        saves[path]=viewModelScope.launch { delay(500); guarded { saveDocument(document) } }
        languageChanges.remove(path)?.cancel()
        languageChanges[path]=viewModelScope.launch { delay(120); guarded { app.languages.change(document) } }
    }
    private suspend fun saveDocument(document: Document)=saveLock.withLock {
        val project=state.value.project ?: return@withLock
        mutable.update { it.copy(saveStates=it.saveStates+(document.path to FileSaveState("saving",savedAt=it.saveStates[document.path]?.savedAt ?: 0))) }
        try {
            withContext(NonCancellable) {
                app.workspace.save(project,document)
                mutable.update { state ->
                    if(state.project?.config?.id!=project.config.id) return@update state
                    val current=state.documents[document.path] ?: return@update state
                    state.copy(documents=state.documents+(document.path to current.copy(savedVersion=document.version)),
                        saveStates=state.saveStates+(document.path to FileSaveState(if(current.version==document.version) "saved" else "pending",savedAt=System.currentTimeMillis())))
                }
                app.journal.saved(project.config.id,document.path,document.version,document.text)
            }
        } catch(error: Throwable) {
            // A background/build flush can cancel the delayed draft before a write fails.
            // Persist the newest buffer on that failure path as well.
            withContext(NonCancellable) {
                if(state.value.project?.config?.id==project.config.id) runCatching {
                    app.journal.draft(project,state.value.documents[document.path] ?: document)
                }.onFailure { report("恢复草稿未能写入：${it.message}；请保留编辑器并导出未保存内容") }
            }
            mutable.update { it.copy(saveStates=it.saveStates+(document.path to FileSaveState("failed",error.message ?: "保存失败",it.saveStates[document.path]?.savedAt ?: 0))) }
            throw error
        }
    }
    suspend fun saveAll() {
        saves.values.forEach { it.cancel() }; saves.clear()
        drafts.values.forEach { it.cancel() }; drafts.clear()
        state.value.documents.values.filter { it.dirty }.forEach { saveDocument(it) }
        state.value.project?.let { app.workspace.saveBreakpoints(it,state.value.breakpoints); persistWorkspace() }
    }
    fun flush()=app.scope.launch { guarded { saveAll() } }
    fun savePosition(line: Int,column: Int,scrollX: Int?=null,scrollY: Int?=null) {
        if(state.value.preview!=null) return
        val path=state.value.activePath
        val projectId=state.value.project?.config?.id ?: return
        val oldLocation=fileLocations[path] ?: FileLocation()
        fileLocations=fileLocations+(path to FileLocation(line,column,scrollX ?: oldLocation.scrollX,scrollY ?: oldLocation.scrollY))
        cursorSave?.cancel()
        cursorSave=viewModelScope.launch { delay(300); app.settings.location(projectId,path,line,column); persistWorkspace() }
        signatureJob?.cancel()
        signatureJob=viewModelScope.launch {
            delay(200)
            val document=state.value.document ?: return@launch
            val index=Positions.index(document.text,Position(line,column))
            val recent=document.text.substring((index-100).coerceAtLeast(0),index)
            if(recent.contains('(')) {
                val signature=runCatching { app.languages.signature(document,Position(line,column)) }.getOrDefault("")
                currentCoroutineContext().ensureActive()
                if(state.value.activePath==document.path && state.value.document?.version==document.version) mutable.update { it.copy(signature=signature) }
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
    fun input(text: String,onSuccess: ()->Unit={})=viewModelScope.launch { guarded {
        check(session.value.canSendInput) { if(session.value.inputOpen) "运行期间才可发送输入" else "标准输入尚未建立或已经关闭" }
        val id=session.value.id; app.sessions.sendInput(text,expectedSessionId=id); if(session.value.id==id) onSuccess()
    } }
    fun eof()=app.sessions.eof()
    fun selectFrame(frame: StackFrame)=viewModelScope.launch { guarded { app.sessions.selectFrame(frame.id); frame.path?.let { navigate(it,Position(frame.line,frame.column)) }; refreshWatches() } }
    fun expandVariable(variable: DebugVariable,onResult: (List<DebugVariable>)->Unit)=viewModelScope.launch { guarded {
        val identity=Triple(session.value.id,session.value.stopSequence,session.value.frameId)
        val result=app.sessions.variables(variable.reference)
        if(session.value.phase==SessionPhase.PAUSED && identity==Triple(session.value.id,session.value.stopSequence,session.value.frameId)) onResult(result)
    } }
    fun addWatch(expression: String) {
        if(expression.isBlank()) return
        mutable.update { it.copy(watchExpressions=(it.watchExpressions+expression.trim()).distinct()) }
        viewModelScope.launch { guarded { persistWorkspace(); refreshWatches() } }
    }
    fun removeWatch(expression: String) { mutable.update { it.copy(watchExpressions=it.watchExpressions-expression,watchValues=it.watchValues.filterNot { v -> v.name==expression }) }; viewModelScope.launch { guarded { persistWorkspace() } } }
    private suspend fun refreshWatches() {
        val identity=Triple(session.value.id,session.value.stopSequence,session.value.frameId)
        val values=state.value.watchExpressions.map { expression -> runCatching { app.sessions.watch(expression) }.getOrElse { DebugVariable(expression,it.message ?: "暂不可用") } }
        if(identity==Triple(session.value.id,session.value.stopSequence,session.value.frameId)) mutable.update { it.copy(watchValues=values) }
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
            mutable.update { it.copy(preview=Preview(if(snapshot!=null && file.toPath().startsWith(snapshot.source.toPath())) "运行快照 · ${file.name}" else "只读源码 · ${file.name}",Document(relative ?: path,text),file),navigation=Navigation(position.line,position.character,++navigationId)) }
        }
    }
    fun closePreview() { mutable.update { it.copy(preview=null,navigation=Navigation()) } }
    fun updateSettings(value: EditorSettings)=viewModelScope.launch { guarded { app.settings.appearance(value) } }
    fun configureRun(entry: String,args: List<String>,timeout: Int,preload: String?)=viewModelScope.launch { guarded {
        val project=state.value.project ?: return@guarded
        val updated=app.workspace.updateConfig(project,project.config.copy(entry=entry,arguments=args,runTimeoutSeconds=timeout,preloadedInput=preload))
        mutable.update { it.copy(project=updated) }
    } }
    fun importProject(uri: Uri,language: CodeLanguage?=null): Job {
        if(importJob?.isActive==true) return importJob!!
        return viewModelScope.launch {
            try { guarded {
                check(!session.value.active) { "请先停止当前任务" }; saveAll()
                mutable.update { it.copy(importChoice=null) }
                val project=try { app.workspace.importProject(uri,language) } catch(choice: ImportLanguageChoice) {
                    mutable.update { it.copy(importChoice=choice) }; return@guarded
                }
                refreshProjects(); select(project)
            } } finally { importJob=null }
        }.also { importJob=it }
    }
    fun cancelImport() { importJob?.cancel(); mutable.update { it.copy(importChoice=null) } }
    fun exportProject(uri: Uri)=viewModelScope.launch { guarded { saveAll(); state.value.project?.let { app.workspace.export(it,uri) } } }
    fun exportSource(uri: Uri)=viewModelScope.launch { guarded {
        val request=state.value.export
        if(request==null) { saveAll(); state.value.project?.let { app.workspace.exportSource(it,state.value.activePath,uri) } }
        else if(request.directory!=null) app.workspace.exportDirectory(request.directory,uri)
        else withContext(Dispatchers.IO) {
            val stream=app.contentResolver.openOutputStream(uri,"wt") ?: error("无法打开导出文件")
            stream.use { output -> if(request.text!=null) output.write(request.text.toByteArray(Charsets.UTF_8)) else request.file!!.inputStream().use { it.copyTo(output) } }
        }
        mutable.update { it.copy(export=null) }
    } }
    fun prepareBufferExport(path: String?=null) { (if(path==null) state.value.document else state.value.documents[path])?.let { doc -> mutable.update { it.copy(export=ExportContent(doc.path.substringAfterLast('/'),text=doc.text)) } } }
    fun prepareFileExport(file: File) {
        val base=app.workspace.sessionsRoot.canonicalFile.toPath(); val path=file.canonicalFile.toPath()
        require(path.startsWith(base) && file.isFile) { "仅能导出运行历史中的文件" }
        mutable.update { it.copy(export=ExportContent(file.name,file=file)) }
    }
    fun prepareRecoveryExport(project: UnreadableProject) { mutable.update { it.copy(export=ExportContent("recovered-project.zip",directory=project.directory)) } }
    fun cancelExport() { mutable.update { it.copy(export=null) } }
    suspend fun persistWorkspace() {
        val state=state.value; val project=state.project ?: return
        app.journal.workspace(project.config.id,WorkspaceMemory(state.documents.keys.toList(),state.activePath,fileLocations,state.watchExpressions))
    }
    fun location(path: String)=fileLocations[path] ?: FileLocation()
    fun retrySave(path: String)=viewModelScope.launch { guarded { state.value.documents[path]?.let { saveDocument(it) } } }
    fun recover(draft: RecoveryDraft)=viewModelScope.launch { guarded {
        check(!session.value.sourceLocked) { "请先停止调试" }
        val project=state.value.project ?: return@guarded
        require(project.config.id==draft.projectId)
        if(!resolveInside(project.directory,draft.path).exists()) { app.workspace.createFile(project,draft.path); val files=app.workspace.files(project); mutable.update { it.copy(files=files) } }
        open(draft.path)
        val old=state.value.documents.getValue(draft.path)
        edit(draft.path,draft.text,0,draft.text.count { it=='\n' }-old.text.count { it=='\n' },old.text.count { it=='\n' })
        app.journal.draft(project,state.value.documents.getValue(draft.path))
        mutable.update { it.copy(recoveries=it.recoveries-draft) }
    } }
    fun discardRecovery(draft: RecoveryDraft)=viewModelScope.launch { guarded { app.journal.discard(draft); mutable.update { it.copy(recoveries=it.recoveries-draft) } } }
    fun previewRecovery(draft: RecoveryDraft) {
        mutable.update { it.copy(preview=Preview("恢复草稿预览 · 尚未应用",Document(draft.path,draft.text)),navigation=Navigation(0,0,++navigationId)) }
    }
    fun pin(project: Project)=viewModelScope.launch { guarded { app.journal.pin(project.config.id); refreshProjects() } }
    fun renameProject(project: Project,name: String)=viewModelScope.launch { guarded {
        check(!session.value.active); saveAll(); val updated=app.workspace.renameProject(project,name)
        if(state.value.project?.config?.id==project.config.id) mutable.update { it.copy(project=updated) }
        refreshProjects()
    } }
    fun copyProject(project: Project,name: String)=viewModelScope.launch { guarded { check(!session.value.active); saveAll(); val created=app.workspace.copyProject(project,name); refreshProjects(); select(created) } }
    fun deleteProject(project: Project)=viewModelScope.launch { guarded {
        check(!session.value.active); saveAll(); app.workspace.recycle(project)
        if(state.value.project?.config?.id==project.config.id) { activation?.cancelAndJoin(); app.languages.close(); mutable.update { it.copy(project=null,documents=emptyMap(),activePath="") } }
        refreshProjects(); loadStorage()
    } }
    fun moveFile(from: String,to: String)=viewModelScope.launch { guarded {
        check(!session.value.active) { "请先停止任务再移动文件" }; saveAll()
        val project=state.value.project ?: return@guarded
        val updated=app.workspace.moveFile(project,from,to.trim())
        fileLocations=fileLocations.mapKeys { if(it.key==from) to.trim() else it.key }
        mutable.update { it.copy(documents=it.documents.map { (path,doc) -> (if(path==from) to.trim() else path) to (if(path==from) doc.copy(path=to.trim()) else doc) }.toMap(),
            activePath=if(it.activePath==from) to.trim() else it.activePath,project=updated,saveStates=emptyMap()) }
        app.languages.close(); mutable.update { it.copy(files=app.workspace.files(updated),breakpoints=app.workspace.breakpoints(updated)) }
        persistWorkspace(); activate()
    } }
    fun deleteFile(path: String)=viewModelScope.launch { guarded {
        check(!session.value.active) { "请先停止任务再移入回收站" }; saveAll()
        val project=state.value.project ?: return@guarded
        app.workspace.recycle(project,path)
        languageChanges.remove(path)?.cancel(); app.languages.closeDocument(path)
        val remaining=state.value.documents-path
        mutable.update { it.copy(documents=remaining,activePath=if(it.activePath==path) remaining.keys.lastOrNull().orEmpty() else it.activePath,files=app.workspace.files(project)) }
        persistWorkspace(); loadStorage()
    } }
    fun restore(record: TrashRecord)=viewModelScope.launch { guarded {
        check(!session.value.active) { "请先停止当前任务" }; app.workspace.restore(record); refreshProjects()
        state.value.project?.let { project -> mutable.update { it.copy(files=app.workspace.files(project)) }; app.languages.close(); activate() }
        loadStorage()
    } }
    fun reopenClosed()=viewModelScope.launch { guarded { closedTabs.removeLastOrNull()?.takeIf { it in state.value.files }?.let { open(it) } } }
    fun closeOthers()=viewModelScope.launch { guarded { state.value.documents.keys.filter { it!=state.value.activePath }.forEach { closeFile(it).join() } } }
    fun closeSaved()=viewModelScope.launch { guarded { state.value.documents.values.filter { !it.dirty }.forEach { closeFile(it.path).join() } } }
    fun search(query: String,matchCase: Boolean=false,sourceOnly: Boolean=false,suffix: String="") {
        searchJob?.cancel()
        val project=state.value.project ?: return
        val versions=state.value.documents.mapValues { it.value.version }
        mutable.update { it.copy(searching=query.isNotBlank(),searchQuery=query,searchResults=emptyList()) }
        searchJob=viewModelScope.launch { guarded {
            delay(200); val results=app.workspace.search(project,query,state.value.documents,matchCase,sourceOnly,suffix)
            ensureActive()
            if(state.value.project?.config?.id==project.config.id && state.value.searchQuery==query && versions==state.value.documents.mapValues { it.value.version }) mutable.update { it.copy(searchResults=results,searching=false) }
            else if(state.value.searchQuery==query) { mutable.update { it.copy(searching=false,searchResults=emptyList()) }; report("搜索期间文件已修改，请重新搜索") }
        } }
    }
    fun cancelSearch() { searchJob?.cancel(); mutable.update { it.copy(searching=false) } }
    fun goToLine(line: Int) { val doc=state.value.document ?: return; val target=(line-1).coerceIn(0,doc.text.count { it=='\n' }); mutable.update { it.copy(navigation=Navigation(target,0,++navigationId)) } }
    fun codeNavigation(method: String,position: Position)=viewModelScope.launch { guarded {
        val document=state.value.document ?: return@guarded; val project=state.value.project ?: return@guarded
        mutable.update { it.copy(navigationBusy=true,locations=emptyList(),symbols=emptyList()) }
        try {
            val locations=if(method=="textDocument/documentSymbol") emptyList() else app.languages.locations(document,position,method)
            val symbols=if(method=="textDocument/documentSymbol") app.languages.symbols(document) else emptyList()
            if(state.value.project?.config?.id==project.config.id && state.value.activePath==document.path && state.value.document?.version==document.version) {
                mutable.update { it.copy(locations=locations,symbols=symbols) }
                if(method=="textDocument/definition" && locations.size==1) openLocation(locations.single())
                if(locations.isEmpty() && symbols.isEmpty()) report("没有找到可用结果；语义服务可能仍在索引")
            }
        } finally { mutable.update { it.copy(navigationBusy=false) } }
    } }
    fun openLocation(location: CodeLocation)=viewModelScope.launch { guarded {
        val current=state.value.activePath
        state.value.project?.let { project -> if(current.isNotBlank()) backLocations.addLast(CodeLocation(File(project.directory,current).absolutePath,fileLocations[current]?.let { Position(it.line,it.column) } ?: Position(0,0))) }
        forwardLocations.clear(); navigate(location.path,location.position)
    } }
    fun navigateBack(forward: Boolean=false)=viewModelScope.launch { guarded {
        val from=if(forward) forwardLocations else backLocations; val to=if(forward) backLocations else forwardLocations
        val destination=from.removeLastOrNull() ?: return@guarded
        state.value.project?.let { project -> val path=state.value.activePath; if(path.isNotBlank()) to.addLast(CodeLocation(File(project.directory,path).absolutePath,fileLocations[path]?.let { Position(it.line,it.column) } ?: Position(0,0))) }
        navigate(destination.path,destination.position)
    } }
    private fun protectedSessions(): Set<String> {
        val preview=state.value.preview?.file?.canonicalFile?.toPath()
        val root=app.workspace.sessionsRoot.toPath()
        val previewId=if(preview!=null && preview.startsWith(root)) root.relativize(preview).getName(0).toString() else null
        val export=state.value.export?.file?.canonicalFile?.toPath()
        val exportId=if(export!=null && export.startsWith(root)) root.relativize(export).getName(0).toString() else null
        return setOfNotNull(app.sessions.snapshot?.id,previewId,exportId)
    }
    suspend fun loadStorage() {
        val storage=app.workspace.storage(protectedSessions(),toolchains.value.toolchain?.root)
        val history=app.workspace.history(); val recycled=app.workspace.recycled()
        mutable.update { it.copy(storage=storage,history=history,recycled=recycled) }
    }
    fun refreshStorage()=viewModelScope.launch { guarded { loadStorage() } }
    fun cleanStorage(category: String)=viewModelScope.launch { guarded {
        check(!session.value.active && !app.workspace.importProgress.value.running && !app.updates.state.value.busy) { "请等待运行、导入或更新结束" }
        activation?.cancelAndJoin(); app.languages.close()
        try { app.workspace.clean(category,protectedSessions(),toolchains.value.toolchain?.root); loadStorage() } finally { activate() }
    } }
    fun installUpdate(onReady: (File)->Unit)=viewModelScope.launch { guarded {
        check(!session.value.active) { "请先停止运行或调试，再安装更新" }
        saveAll()
        val apk=app.updates.verifiedApk()
        check(!session.value.active) { "请先停止运行或调试，再安装更新" }
        onReady(apk)
    } }
}
