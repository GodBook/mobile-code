package dev.mobilecode.ide.data

import android.content.Context
import dev.mobilecode.ide.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class FileLocation(val line: Int=0, val column: Int=0, val scrollX: Int=0, val scrollY: Int=0)
data class WorkspaceMemory(val tabs: List<String> = emptyList(), val active: String="",
    val locations: Map<String,FileLocation> = emptyMap(), val watches: List<String> = emptyList())
data class ProjectMemory(val pinned: Boolean=false, val openedAt: Long=0, val workspace: WorkspaceMemory=WorkspaceMemory())
data class RecoveryDraft(val projectId: String, val path: String, val text: String, val originalHash: String,
    val version: Int, val modifiedAt: Long=System.currentTimeMillis())

/** Small navigation metadata stays separate from source, database indexes and recovery text. */
class WorkspaceJournal(context: Context) {
    private val root=File(context.filesDir,"workspace-state").apply { mkdirs() }
    private val recovery=File(context.filesDir,"recovery").apply { mkdirs() }
    private val lock=Mutex()
    private fun marker(id: String)=resolveInside(root,"$id.json")
    private fun draftFile(id: String,path: String)=resolveInside(recovery,"$id/${sha256(path.toByteArray())}.json")
    private fun read(id: String): ProjectMemory = runCatching {
        gson.fromJson(marker(id).readText(),ProjectMemory::class.java)
    }.getOrNull() ?: ProjectMemory()
    suspend fun project(id: String)=lock.withLock { withContext(Dispatchers.IO) { read(id) } }
    suspend fun all(): Map<String,ProjectMemory> = lock.withLock { withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().filter { it.extension=="json" }.associate { it.nameWithoutExtension to read(it.nameWithoutExtension) }
    } }
    suspend fun pin(id: String)=lock.withLock { withContext(Dispatchers.IO) {
        val old=read(id); AtomicFiles.text(marker(id),gson.toJson(old.copy(pinned=!old.pinned)))
    } }
    suspend fun opened(id: String)=lock.withLock { withContext(Dispatchers.IO) {
        AtomicFiles.text(marker(id),gson.toJson(read(id).copy(openedAt=System.currentTimeMillis())))
    } }
    suspend fun workspace(id: String,memory: WorkspaceMemory)=lock.withLock { withContext(Dispatchers.IO) {
        AtomicFiles.text(marker(id),gson.toJson(read(id).copy(workspace=memory)))
    } }
    suspend fun draft(project: Project,document: Document)=lock.withLock { withContext(Dispatchers.IO) {
        val file=draftFile(project.config.id,document.path)
        val original=resolveInside(project.directory,document.path)
        val existing=runCatching { gson.fromJson(file.readText(),RecoveryDraft::class.java) }.getOrNull()
        val hash=existing?.originalHash ?: if(original.isFile) sha256(original.readBytes()) else ""
        AtomicFiles.text(file,gson.toJson(RecoveryDraft(project.config.id,document.path,document.text,hash,document.version)))
    } }
    suspend fun saved(id: String,path: String,version: Int,text: String)=lock.withLock { withContext(Dispatchers.IO) {
        val file=draftFile(id,path)
        val draft=runCatching { gson.fromJson(file.readText(),RecoveryDraft::class.java) }.getOrNull()
        if(draft!=null && (draft.version<=version || draft.text==text)) file.delete()
    } }
    suspend fun drafts(project: Project): List<RecoveryDraft> = lock.withLock { withContext(Dispatchers.IO) {
        val directory=resolveInside(recovery,project.config.id)
        directory.listFiles().orEmpty().mapNotNull { file -> runCatching {
            val draft=gson.fromJson(file.readText(),RecoveryDraft::class.java)
            require(draft.projectId==project.config.id)
            val original=resolveInside(project.directory,draft.path)
            draft.takeUnless { original.isFile && original.readText()==draft.text }
        }.getOrNull() }
    } }
    suspend fun discard(draft: RecoveryDraft)=lock.withLock { withContext(Dispatchers.IO) {
        draftFile(draft.projectId,draft.path).delete()
    } }
}
