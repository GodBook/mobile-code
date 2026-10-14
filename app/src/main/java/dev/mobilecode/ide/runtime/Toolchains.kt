package dev.mobilecode.ide.runtime

import android.content.Context
import android.system.Os
import dev.mobilecode.ide.BuildConfig
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.AtomicFiles
import dev.mobilecode.ide.data.resolveInside
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class NativeAlias(val path: String, val library: String)
data class NativeLibrary(val name: String, val sha256: String, val page16k: Boolean)
data class ToolchainManifest(val schemaVersion: Int, val abi: String, val resourceSha256: String, val resourceBytes: Long,
    val pythonHome: String, val pythonLibrary: String, val javaHome: String, val jvmLibrary: String,
    val jdtLauncher: String, val aliases: List<NativeAlias>, val libraries: List<NativeLibrary>)
data class Toolchain(val root: File, val nativeDir: File, val manifest: ToolchainManifest) {
    val runner get() = File(nativeDir,"libmc_runner.so")
    val pythonHome get() = File(root,manifest.pythonHome)
    val pythonLibrary get() = File(nativeDir,manifest.pythonLibrary)
    val pythonBootstrap get() = File(root,"runtime/mobilecode_python.py")
    val javaHome get() = File(root,manifest.javaHome)
    val jvmLibrary get() = File(nativeDir,manifest.jvmLibrary)
    val runtimeJar get() = File(root,"runtime/mobilecode-runtime.jar")
    val jdtLauncher get() = File(root,manifest.jdtLauncher)
    val clangResources get() = File(root,"usr/lib/clang").listFiles()!!.first { it.isDirectory }
    fun command(name: String): File = File(root,"usr/bin/$name")
}
data class ToolchainStatus(val progress: Float=0f, val message: String="准备本地工具链", val toolchain: Toolchain?=null, val error: String?=null)

class ToolchainService(private val context: Context) {
    private val mutable=MutableStateFlow(ToolchainStatus())
    val status=mutable.asStateFlow()
    private val lock=Mutex()
    suspend fun prepare(): Toolchain = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                val raw=context.assets.open("toolchains/manifest.json").use { it.readBytes() }
                val manifest=gson.fromJson(raw.toString(Charsets.UTF_8),ToolchainManifest::class.java)
                require(manifest.schemaVersion==1 && manifest.abi==BuildConfig.RUNTIME_ABI) { "工具链架构或版本不匹配" }
                val parent=File(context.filesDir,"toolchains").canonicalFile.apply { mkdirs() }
                val root=File(parent,manifest.resourceSha256.take(20))
                val marker=File(root,".ready")
                val nativeDir=File(context.applicationInfo.nativeLibraryDir)
                if(!marker.exists()) {
                    mutable.value=ToolchainStatus(0f,"校验离线资源")
                    val checksum=MessageDigest.getInstance("SHA-256")
                    context.assets.open("toolchains/root.zip").use { input -> val buffer=ByteArray(65536); while(true) { val n=input.read(buffer); if(n<0) break; checksum.update(buffer,0,n) } }
                    val actual=checksum.digest().joinToString("") { "%02x".format(it) }
                    require(actual==manifest.resourceSha256) { "离线资源校验失败" }
                    require(parent.usableSpace>manifest.resourceBytes+50L*1024*1024) { "可用存储 ${parent.usableSpace/1024/1024}MiB，首次准备至少需要 ${manifest.resourceBytes/1024/1024+50}MiB，请释放空间后重试" }
                    root.mkdirs(); var written=0L; var next=0L
                    ZipInputStream(context.assets.open("toolchains/root.zip")).use { zip ->
                        while(true) {
                            val entry=zip.nextEntry ?: break
                            val target=resolveInside(root,entry.name.trimEnd('/'))
                            if(entry.isDirectory) target.mkdirs() else {
                                target.parentFile!!.mkdirs()
                                if(target.exists()) target.setWritable(true)
                                target.outputStream().use { out -> val buffer=ByteArray(65536); while(true) { val n=zip.read(buffer); if(n<0) break; written+=n; require(written<=manifest.resourceBytes+1024*1024) { "离线资源大小异常" }; out.write(buffer,0,n) } }
                                target.setReadOnly()
                            }
                            if(written>=next) { mutable.value=ToolchainStatus((written.toFloat()/manifest.resourceBytes).coerceAtMost(.98f),"解压本地标准库 ${written/1024/1024} MB"); next=written+2*1024*1024 }
                            zip.closeEntry()
                        }
                    }
                    AtomicFiles.text(marker,sha256(raw))
                }
                val verified=File(root,".native-verified")
                val manifestHash=sha256(raw)
                if(!verified.exists() || verified.readText()!=manifestHash) {
                    mutable.value=ToolchainStatus(.99f,"校验原生编译与调试组件")
                    for(component in manifest.libraries) {
                        require(Regex("lib[^/\\\\]+\\.so").matches(component.name)) { "原生组件名称无效" }
                        val checksum=MessageDigest.getInstance("SHA-256")
                        File(nativeDir,component.name).inputStream().use { input ->
                            val buffer=ByteArray(65536)
                            while(true) { val n=input.read(buffer); if(n<0) break; checksum.update(buffer,0,n) }
                        }
                        val hash=checksum.digest().joinToString("") { "%02x".format(it) }
                        require(hash==component.sha256) { "原生组件校验失败：${component.name}" }
                    }
                    AtomicFiles.text(verified,manifestHash)
                }
                for(alias in manifest.aliases) {
                    require(!alias.path.startsWith('/') && alias.path.split('/').none { it==".." || it.isBlank() })
                    val file=File(root,alias.path); val library=File(nativeDir,alias.library)
                    require(file.parentFile!!.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                    require(library.isFile) { "缺少原生组件 ${alias.library}" }
                    file.parentFile!!.mkdirs()
                    if(Files.isSymbolicLink(file.toPath())) Files.delete(file.toPath())
                    require(!file.exists()) { "组件路径冲突：${alias.path}" }
                    Os.symlink(library.absolutePath,file.absolutePath)
                }
                val runtime=File(root,"runtime").apply { mkdirs() }
                for(name in listOf("mobilecode_python.py","mobilecode-runtime.jar")) {
                    val data=context.assets.open("runtime/$name").use { it.readBytes() }
                    AtomicFiles.write(File(runtime,name),data); File(runtime,name).setReadOnly()
                }
                val chain=Toolchain(root,nativeDir,manifest)
                require(chain.runner.isFile && chain.pythonBootstrap.isFile && chain.runtimeJar.isFile) { "运行组件不完整" }
                mutable.value=ToolchainStatus(1f,"四门语言已就绪",chain)
                chain
            } catch(error: Exception) {
                mutable.value=ToolchainStatus(error=error.message ?: "本地工具链初始化失败",message="工具链未就绪")
                throw error
            }
        }
    }
}
