package dev.mobilecode.ide.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.google.gson.JsonParser
import dev.mobilecode.ide.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object AtomicFiles {
    fun write(file: File, bytes: ByteArray) {
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile,".${file.name}.${UUID.randomUUID()}.saving")
        try {
            FileOutputStream(temporary).use { out -> out.write(bytes); out.fd.sync() }
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    fun text(file: File, text: String) = write(file,text.toByteArray(Charsets.UTF_8))
}

fun resolveInside(directory: File, path: String): File {
    require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':')) { "文件路径无效" }
    require(path.split('/').none { it == ".." || it == "." || it.isBlank() }) { "文件路径不能越出项目" }
    val result = File(directory,path).canonicalFile
    require(result.toPath().startsWith(directory.canonicalFile.toPath()) && result != directory.canonicalFile) { "文件路径不能越出项目" }
    return result
}

data class UnreadableProject(val directory: File,val reason: String)
data class ImportProgress(val running: Boolean=false,val bytes: Long=0,val files: Int=0,val message: String="")
class ImportLanguageChoice(val uri: Uri,val candidates: List<CodeLanguage>): IllegalArgumentException("请选择导入项目的语言")

class WorkspaceService(internal val context: Context) {
    val projectsRoot = File(context.filesDir,"projects").canonicalFile.apply { mkdirs() }
    val sessionsRoot = File(context.filesDir,"sessions").canonicalFile.apply { mkdirs() }
    internal val mutex = Mutex()
    private val mutableProblems=MutableStateFlow<List<UnreadableProject>>(emptyList())
    val problems=mutableProblems.asStateFlow()
    internal val mutableImport=MutableStateFlow(ImportProgress())
    val importProgress=mutableImport.asStateFlow()
    private var recovered = false
    val database = Room.databaseBuilder(context,MetaDatabase::class.java,"metadata.db").build()
    internal val dao get() = database.dao()

    suspend fun projects(): List<Project> = mutex.withLock { withContext(Dispatchers.IO) {
        if(!recovered) { dao.interruptPreviousSessions(); recovered = true }
        val metadata=dao.projects().associateBy { it.id }
        val problems=mutableListOf<UnreadableProject>()
        val projects=projectsRoot.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.mapNotNull { dir ->
            runCatching {
                val config = readConfig(File(dir,"project.json"))
                require(config.schemaVersion == 1 && config.id == dir.name)
                val old = metadata[config.id]
                val project = Project(config,dir,old?.updatedAt ?: dir.lastModified())
                register(project); project
            }.onFailure { problems.add(UnreadableProject(dir,it.message ?: "无法读取项目配置")) }.getOrNull()
        }.sortedByDescending { it.updatedAt }
        mutableProblems.value=problems
        projects
    } }
    internal fun readConfig(file: File, replacementId: String? = null): ProjectConfig {
        val raw = JsonParser.parseString(file.readText()).asJsonObject
        require((raw["schemaVersion"]?.asInt ?: 1)==1) { "不支持的项目格式" }
        val language = CodeLanguage.valueOf(raw["language"].asString)
        val id = replacementId ?: raw["id"].asString
        val name = raw["name"]?.asString ?: "导入的项目"
        val roots = raw["sourceRoots"]?.asJsonArray?.map { it.asString } ?: listOf("src")
        val args = raw["arguments"]?.asJsonArray?.map { it.asString } ?: emptyList()
        val entry = raw["entry"]?.asString ?: language.entry
        val timeout = raw["runTimeoutSeconds"]?.asInt ?: 60
        require(name.isNotBlank() && name.length<=64 && roots.isNotEmpty() && roots.size<=100 && timeout in 1..600) { "项目配置无效" }
        roots.filter { it!="." }.forEach { resolveInside(file.parentFile!!,it) }
        require(args.size<=100 && args.all { !it.contains('\u0000') && it.length<=8192 }) { "程序参数无效" }
        if(language==CodeLanguage.PYTHON) resolveInside(file.parentFile!!,entry)
        if(language==CodeLanguage.JAVA) require(Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*").matches(entry)) { "Java 入口类名无效" }
        return ProjectConfig(id=id,name=name,language=language,sourceRoots=roots,entry=entry,arguments=args,runTimeoutSeconds=timeout,
            preloadedInput=raw["preloadedInput"]?.takeUnless { it.isJsonNull }?.asString)
    }
    internal fun register(project: Project) {
        dao.putProject(ProjectEntity().also { it.id=project.config.id; it.name=project.config.name; it.language=project.config.language.name; it.updatedAt=project.updatedAt })
    }
    suspend fun create(name: String, language: CodeLanguage, multiple: Boolean): Project = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.length <= 64) { "项目名称应为 1～64 个字符" }
        val config = ProjectConfig(id=UUID.randomUUID().toString(),name=name.trim(),language=language)
        val dir = File(projectsRoot,config.id).apply { mkdirs() }
        Templates.files(language,multiple).forEach { (path,text) -> AtomicFiles.text(resolveInside(dir,path),text) }
        AtomicFiles.text(File(dir,"project.json"),gson.toJson(config))
        Project(config,dir,System.currentTimeMillis()).also(::register)
    }
    suspend fun files(project: Project): List<String> = withContext(Dispatchers.IO) {
        project.directory.walkTopDown().onEnter { it == project.directory || !it.name.startsWith('.') }
            .filter { it.isFile && it.name !in setOf("project.json",".project",".classpath") && !it.name.endsWith(".saving") }
            .map { it.relativeTo(project.directory).invariantSeparatorsPath }.sorted().toList()
    }
    suspend fun open(project: Project, path: String): Document = withContext(Dispatchers.IO) {
        val file=resolveInside(project.directory,path)
        require(file.length() <= 4*1024*1024) { "首版编辑文件上限为 4MB" }
        Document(path,file.readText(Charsets.UTF_8))
    }
    suspend fun save(project: Project, document: Document) = mutex.withLock {
        withContext(Dispatchers.IO) { AtomicFiles.text(resolveInside(project.directory,document.path),document.text); register(project.copy(updatedAt=System.currentTimeMillis())) }
    }
    suspend fun createFile(project: Project, path: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val target=resolveInside(project.directory,path)
            require(!target.exists()) { "同名文件已存在" }
            AtomicFiles.text(target,"")
        }
    }
    suspend fun updateConfig(project: Project, config: ProjectConfig): Project = withContext(Dispatchers.IO) {
        require(config.id == project.config.id && config.runTimeoutSeconds in 1..600)
        require(config.name.isNotBlank() && config.name.length<=64 && config.sourceRoots.isNotEmpty() && config.sourceRoots.size<=100) { "项目配置无效" }
        config.sourceRoots.filter { it!="." }.forEach { resolveInside(project.directory,it) }
        require(config.arguments.size<=100 && config.arguments.all { !it.contains('\u0000') && it.length<=8192 }) { "程序参数无效" }
        if(config.language==CodeLanguage.PYTHON) resolveInside(project.directory,config.entry)
        if(config.language==CodeLanguage.JAVA) require(Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*").matches(config.entry)) { "Java 入口类名无效" }
        AtomicFiles.text(File(project.directory,"project.json"),gson.toJson(config))
        project.copy(config=config).also(::register)
    }
    suspend fun breakpoints(project: Project): List<Breakpoint> = withContext(Dispatchers.IO) {
        dao.breakpoints(project.config.id).map { Breakpoint(it.path,it.line) }
    }
    suspend fun saveBreakpoints(project: Project, points: List<Breakpoint>) = withContext(Dispatchers.IO) {
        dao.replaceBreakpoints(project.config.id,points.map { p -> BreakpointEntity().also { it.projectId=project.config.id; it.path=p.path; it.line=p.line } })
    }
    suspend fun snapshot(project: Project, id: String=UUID.randomUUID().toString()): Snapshot = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(sessionsRoot.usableSpace>64L*1024*1024) { "可用空间不足，至少需要64MiB；请在存储管理中导出并清理历史产物" }
            require(Regex("[A-Za-z0-9_-]{1,128}").matches(id)) { "运行任务标识无效" }
            val dir=resolveInside(sessionsRoot,id)
            require(!dir.exists() && dir.mkdir()) { "运行快照已存在或无法创建，原产物已保留" }
            val source=File(dir,"source").apply { mkdirs() }; val work=File(dir,"work").apply { mkdirs() }
            val build=File(dir,"build").apply { mkdirs() }; File(dir,"tmp").mkdirs()
            val hashes=linkedMapOf<String,String>(); var bytes=0L; var count=0
            for(path in files(project)) {
                require(++count <= 1000) { "轻量项目文件数量上限为 1000" }
                val original=resolveInside(project.directory,path); bytes+=original.length()
                require(bytes<=50L*1024*1024) { "轻量项目总大小上限为 50MB" }
                val target=resolveInside(source,path).apply { parentFile!!.mkdirs() }
                original.copyTo(target); hashes[path]=sha256(target.readBytes()); target.setReadOnly()
                if(!project.config.language.isSource(path) && !path.endsWith(".h") && !path.endsWith(".hpp")) {
                    val resource=resolveInside(work,path).apply { parentFile!!.mkdirs() }; original.copyTo(resource)
                }
            }
            val hash=sha256(gson.toJson(hashes).toByteArray())
            AtomicFiles.text(File(dir,"snapshot.json"),gson.toJson(mapOf("id" to id,"project" to project.config,"hash" to hash,"files" to hashes)))
            Snapshot(id,project.config,dir,source,work,build,hash,hashes)
        }
    }
    suspend fun export(project: Project, uri: Uri) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val stream=context.contentResolver.openOutputStream(uri,"wt") ?: error("无法打开导出文件")
            ZipOutputStream(stream).use { zip ->
                for(path in listOf("project.json")+files(project)) {
                    zip.putNextEntry(ZipEntry(path)); resolveInside(project.directory,path).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        }
    }
    suspend fun exportSource(project: Project,path: String,uri: Uri) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val stream=context.contentResolver.openOutputStream(uri,"wt") ?: error("无法打开导出文件")
            stream.use { out -> resolveInside(project.directory,path).inputStream().use { it.copyTo(out) } }
        }
    }
    suspend fun importProject(uri: Uri, selectedLanguage: CodeLanguage?=null): Project = mutex.withLock { withContext(Dispatchers.IO) {
        val id=UUID.randomUUID().toString(); val staging=File(projectsRoot,".import-$id").apply { mkdirs() }
        mutableImport.value=ImportProgress(true,message="正在读取 ZIP，取消不会影响已有项目")
        try {
        var bytes=0L; var count=0
        val stream=context.contentResolver.openInputStream(uri) ?: error("无法打开导入文件")
        ZipInputStream(stream).use { zip ->
            while(true) {
                currentCoroutineContext().ensureActive()
                val entry=zip.nextEntry ?: break
                val path=entry.name.trimEnd('/')
                if(path.isEmpty()) continue
                val dest=resolveInside(staging,path)
                require(++count<=1000) { "导入文件过多" }
                if(entry.isDirectory) dest.mkdirs() else {
                    dest.parentFile!!.mkdirs()
                    FileOutputStream(dest).use { out ->
                        val buffer=ByteArray(16384)
                        while(true) { currentCoroutineContext().ensureActive(); val n=zip.read(buffer); if(n<0) break; bytes+=n; require(bytes<=50L*1024*1024) { "导入项目超过 50MB" }; out.write(buffer,0,n); mutableImport.value=ImportProgress(true,bytes,count,"正在导入 $count 个文件") }
                    }
                }
                zip.closeEntry()
            }
        }
        val nested=staging.listFiles().orEmpty().singleOrNull()?.takeIf { it.isDirectory }
        val base=if(!File(staging,"project.json").exists() && nested!=null && nested.name!="src") nested else staging
        val marker=File(base,"project.json")
        val config=if(marker.exists()) {
            readConfig(marker,id)
        } else {
            val paths=base.walkTopDown().filter { it.isFile }.map { it.relativeTo(base).invariantSeparatorsPath }.toList()
            val candidates=CodeLanguage.entries.filter { l -> paths.any(l::isSource) }.filterNot { it==CodeLanguage.C && paths.any(CodeLanguage.CPP::isSource) && paths.any { it.endsWith(".cpp") } }
            if(selectedLanguage==null && candidates.size!=1) throw ImportLanguageChoice(uri,candidates.ifEmpty { CodeLanguage.entries })
            val language=selectedLanguage ?: candidates.single()
            require(paths.any(language::isSource)) { "ZIP 中没有 ${language.title} 源文件" }
            val entry=if(language==CodeLanguage.PYTHON) paths.firstOrNull { it.endsWith("main.py") } ?: paths.first(language::isSource) else language.entry
            ProjectConfig(id=id,name="导入的项目",language=language,sourceRoots=listOf("src").takeIf { File(base,"src").isDirectory } ?: listOf("."),entry=entry)
        }
        require(config.name.isNotBlank() && config.sourceRoots.all { it=="." || runCatching { resolveInside(base,it); true }.getOrDefault(false) }) { "项目配置无效" }
        AtomicFiles.text(File(base,"project.json"),gson.toJson(config))
        val finalDir=File(projectsRoot,id)
        Files.move(base.toPath(),finalDir.toPath(),StandardCopyOption.ATOMIC_MOVE)
        Project(config,finalDir,System.currentTimeMillis()).also(::register)
        } finally {
            mutableImport.value=mutableImport.value.copy(running=false)
            // Explicitly marked for the storage screen; never delete an imported or existing project.
            if(staging.isDirectory) runCatching { AtomicFiles.text(File(staging,".failed-import"),"可在存储管理中清理此导入暂存目录") }
        }
    } }
}
