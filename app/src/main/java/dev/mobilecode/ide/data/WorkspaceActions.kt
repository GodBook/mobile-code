package dev.mobilecode.ide.data

import android.net.Uri
import dev.mobilecode.ide.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class TrashRecord(val id: String,val projectId: String,val path: String,val project: Boolean,
    val name: String,val removedAt: Long=System.currentTimeMillis())
data class StorageItem(val id: String,val label: String,val bytes: Long,val cleanable: Boolean,val detail: String)
data class StoredSession(val id: String,val projectId: String,val startedAt: Long,val status: String,
    val durationMs: Long,val exitCode: Int?,val files: List<File>,val unlinkedSnapshot: Boolean=false)
data class TextMatch(val path: String,val line: Int,val column: Int,val text: String)

internal fun treeBytes(file: File): Long = if(!file.exists()) 0 else file.walkTopDown()
    .onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.sumOf { it.length() }

/** Delete only an explicitly selected child of one known app-owned directory; never follow links. */
internal fun deleteOwnedTree(target: File,parent: File) {
    val base=parent.canonicalFile.toPath(); val path=target.absoluteFile.toPath().normalize()
    require(path.startsWith(base) && path!=base) { "清理路径越界" }
    if(!Files.exists(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { child ->
        if(!Files.isSymbolicLink(child)) child.toFile().setWritable(true)
        Files.delete(child)
    } }
}

suspend fun WorkspaceService.renameProject(project: Project,name: String): Project = mutex.withLock {
    require(name.isNotBlank() && name.trim().length<=64) { "项目名称应为1～64个字符" }
    updateConfig(project,project.config.copy(name=name.trim()))
}

suspend fun WorkspaceService.copyProject(project: Project,name: String): Project = mutex.withLock { withContext(Dispatchers.IO) {
    require(name.isNotBlank() && name.trim().length<=64) { "项目名称应为1～64个字符" }
    val id=UUID.randomUUID().toString(); val staging=File(projectsRoot,".copy-$id").apply { mkdirs() }
    for(path in files(project)) { currentCoroutineContext().ensureActive(); val dest=resolveInside(staging,path); dest.parentFile!!.mkdirs(); resolveInside(project.directory,path).copyTo(dest) }
    val config=project.config.copy(id=id,name=name.trim())
    AtomicFiles.text(File(staging,"project.json"),gson.toJson(config))
    val target=File(projectsRoot,id); Files.move(staging.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
    Project(config,target,System.currentTimeMillis()).also(::register)
} }

suspend fun WorkspaceService.moveFile(project: Project,from: String,to: String): Project = mutex.withLock { withContext(Dispatchers.IO) {
    val source=resolveInside(project.directory,from); val target=resolveInside(project.directory,to)
    require(source.isFile && from!="project.json") { "请选择源码或资源文件" }
    require(!target.exists()) { "同名文件已存在" }
    val points=breakpoints(project)
    val config=if(project.config.language==CodeLanguage.PYTHON && project.config.entry==from) project.config.copy(entry=to) else project.config
    target.parentFile!!.mkdirs(); Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
    try {
        AtomicFiles.text(File(project.directory,"project.json"),gson.toJson(config))
        saveBreakpoints(project,points.map { if(it.path==from) it.copy(path=to,verified=false) else it })
    } catch(error: Throwable) {
        Files.move(target.toPath(),source.toPath(),StandardCopyOption.ATOMIC_MOVE)
        AtomicFiles.text(File(project.directory,"project.json"),gson.toJson(project.config))
        throw error
    }
    project.copy(config=config,updatedAt=System.currentTimeMillis()).also(::register)
} }

suspend fun WorkspaceService.recycle(project: Project,path: String?=null): TrashRecord = mutex.withLock { withContext(Dispatchers.IO) {
    val source=path?.let { resolveInside(project.directory,it) } ?: project.directory
    require(source.exists() && path!="project.json") { "文件不存在或不可删除" }
    val record=TrashRecord(UUID.randomUUID().toString(),project.config.id,path.orEmpty(),path==null,
        if(path==null) project.config.name else source.name)
    val folder=File(context.filesDir,"recycle/${record.id}").apply { mkdirs() }
    AtomicFiles.text(File(folder,"record.json"),gson.toJson(record))
    Files.move(source.toPath(),File(folder,"payload").toPath(),StandardCopyOption.ATOMIC_MOVE)
    record
} }

suspend fun WorkspaceService.recycled(): List<TrashRecord> = withContext(Dispatchers.IO) {
    File(context.filesDir,"recycle").listFiles().orEmpty().mapNotNull { folder -> runCatching {
        gson.fromJson(File(folder,"record.json").readText(),TrashRecord::class.java).takeIf { File(folder,"payload").exists() }
    }.getOrNull() }.sortedByDescending { it.removedAt }
}

suspend fun WorkspaceService.restore(record: TrashRecord) = mutex.withLock { withContext(Dispatchers.IO) {
    val folder=resolveInside(File(context.filesDir,"recycle"),record.id)
    val projectDir=resolveInside(projectsRoot,record.projectId)
    val target=if(record.project) projectDir else resolveInside(projectDir,record.path)
    require(record.project || File(projectDir,"project.json").isFile) { "请先恢复所属项目" }
    require(!target.exists()) { "原路径已被占用，请先重命名同名文件" }
    target.parentFile!!.mkdirs(); Files.move(File(folder,"payload").toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
    if(record.project) { val config=readConfig(File(target,"project.json")); register(Project(config,target,System.currentTimeMillis())) }
    File(folder,"record.json").delete(); folder.delete()
} }

suspend fun WorkspaceService.exportDirectory(directory: File,uri: Uri) = withContext(Dispatchers.IO) {
    val allowed=directory.canonicalFile.toPath()
    require(allowed.startsWith(projectsRoot.toPath()) && allowed!=projectsRoot.toPath())
    val stream=context.contentResolver.openOutputStream(uri,"wt") ?: error("无法打开导出文件")
    ZipOutputStream(stream).use { zip ->
        for(file in directory.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }) {
            currentCoroutineContext().ensureActive()
            zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        }
    }
}

suspend fun WorkspaceService.search(project: Project,query: String,documents: Map<String,Document>,matchCase: Boolean=false,sourceOnly: Boolean=false,suffix: String=""): List<TextMatch> = withContext(Dispatchers.Default) {
    if(query.isBlank()) return@withContext emptyList()
    val results=mutableListOf<TextMatch>()
    for(path in files(project)) {
        currentCoroutineContext().ensureActive()
        if(sourceOnly && project.config.sourceRoots.none { it=="." || path.startsWith("${it.trimEnd('/')}/") }) continue
        if(suffix.isNotBlank() && !path.endsWith(if(suffix.startsWith('.')) suffix else ".$suffix",ignoreCase=true)) continue
        val file=resolveInside(project.directory,path)
        if(file.length()>4L*1024*1024) continue
        val text=documents[path]?.text ?: withContext(Dispatchers.IO) { file.readText() }
        if(text.indexOf('\u0000')>=0) continue
        text.lineSequence().forEachIndexed { line,value ->
            if(results.size>=1000) return@withContext results
            val column=value.indexOf(query,ignoreCase=!matchCase)
            if(column>=0) results.add(TextMatch(path,line,column,value.take(400)))
        }
    }
    results
}

suspend fun WorkspaceService.storage(protectedSessions: Set<String>,currentToolchain: File?): List<StorageItem> = withContext(Dispatchers.IO) {
    val files=context.filesDir
    val disposableSessions=sessionsRoot.listFiles().orEmpty().filter { it.isDirectory && it.name !in protectedSessions }
    val imports=projectsRoot.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".import-") && File(it,".failed-import").isFile }
    val oldTools=File(files,"toolchains").listFiles().orEmpty().filter { it.isDirectory && it.canonicalFile!=currentToolchain?.canonicalFile }
    listOf(StorageItem("projects","项目源码",projectsRoot.listFiles().orEmpty().filter { !it.name.startsWith('.') }.sumOf(::treeBytes),false,"包含异常项目，永不作为缓存清理"),
        StorageItem("sessions","历史快照与运行产物",disposableSessions.sumOf(::treeBytes),true,"先在运行历史导出所需文件；当前运行/预览快照已排除"),
        StorageItem("indexes","语言索引",treeBytes(File(files,"language-state")),true,"可重建，清理后重新建立语义索引"),
        StorageItem("imports","失败导入暂存",imports.sumOf(::treeBytes),true,"仅含已结束且带失败标记的导入目录"),
        StorageItem("toolchains","旧组件资源",oldTools.sumOf(::treeBytes),currentToolchain!=null,"保留当前完整离线组件"),
        StorageItem("updates","更新包",treeBytes(File(files,"updates"))+treeBytes(context.getExternalFilesDir("updates") ?: File(files,"unused-updates")),false,"由更新器管理，下载/待安装文件不会被清理"),
        StorageItem("recycle","回收站",treeBytes(File(files,"recycle")),false,"不会自动清空，请先恢复需要的文件"),
        StorageItem("recovery","恢复草稿",treeBytes(File(files,"recovery")),false,"未保存内容，不能作为缓存清理"))
}

suspend fun WorkspaceService.clean(category: String,protectedSessions: Set<String>,currentToolchain: File?) = mutex.withLock { withContext(Dispatchers.IO) {
    val files=context.filesDir
    val parent: File
    val targets: List<File>
    when(category) {
        "sessions" -> { parent=sessionsRoot; targets=parent.listFiles().orEmpty().filter { it.isDirectory && it.name !in protectedSessions } }
        "imports" -> { parent=projectsRoot; targets=parent.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".import-") && File(it,".failed-import").isFile } }
        "indexes" -> { parent=File(files,"language-state"); targets=parent.listFiles().orEmpty().toList() }
        "toolchains" -> { require(currentToolchain!=null); parent=File(files,"toolchains"); targets=parent.listFiles().orEmpty().filter { it.isDirectory && it.canonicalFile!=currentToolchain.canonicalFile } }
        else -> error("此分类不可清理")
    }
    targets.forEach { currentCoroutineContext().ensureActive(); deleteOwnedTree(it,parent) }
} }

suspend fun WorkspaceService.history(): List<StoredSession> = withContext(Dispatchers.IO) {
    readSessionHistory(sessionsRoot,dao.history(),dao.historyIds().toSet())
}
