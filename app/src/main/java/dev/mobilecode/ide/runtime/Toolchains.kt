package dev.mobilecode.ide.runtime

import android.content.Context
import android.system.Os
import dev.mobilecode.ide.BuildConfig
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.AtomicFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

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
    suspend fun prepare(repair: Boolean=false): Toolchain = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                mutable.value=ToolchainStatus(message=if(repair) "正在完整检查并修复离线组件" else "准备本地工具链")
                val raw=context.assets.open("toolchains/manifest.json").use { it.readBytes() }
                val manifest=gson.fromJson(raw.toString(Charsets.UTF_8),ToolchainManifest::class.java)
                require(manifest.schemaVersion==1 && manifest.abi==BuildConfig.RUNTIME_ABI) { "工具链架构或版本不匹配" }
                require(manifest.resourceBytes>0 && Regex("[a-f0-9]{64}").matches(manifest.resourceSha256)) { "离线资源清单无效" }
                val parent=File(context.filesDir,"toolchains").canonicalFile.apply { mkdirs() }
                val installer=OfflineResourceInstaller(parent,manifest.resourceSha256.take(20))
                installer.recover()
                val root=installer.root
                val manifestHash=sha256(raw)
                val nativeDir=File(context.applicationInfo.nativeLibraryDir)
                val verified=File(root,".native-verified")
                if(repair || runCatching { verified.readText() }.getOrNull()!=manifestHash) verifyNative(nativeDir,manifest)
                val marked=runCatching { File(root,".ready").readText()==manifestHash }.getOrDefault(false)
                val ready=!repair && marked && runCatching {
                    // APK updates move the installed native directory; refresh aliases
                    // before deciding whether the extracted standard libraries need repair.
                    aliases(root,nativeDir,manifest)
                    resourcesPresent(Toolchain(root,nativeDir,manifest))
                }.getOrDefault(false)
                if(repair || !ready) {
                    mutable.value=ToolchainStatus(0f,"校验离线资源")
                    val open={ context.assets.open("toolchains/root.zip") }
                    verifyOfflineArchive(open,manifest.resourceSha256)
                    require(parent.usableSpace>manifest.resourceBytes+50L*1024*1024) { "可用存储 ${parent.usableSpace/1024/1024}MiB，完整准备或修复至少需要 ${manifest.resourceBytes/1024/1024+50}MiB，请释放空间后重试" }
                    installer.replace { staging ->
                        extractOfflineArchive(open,staging,manifest.resourceBytes) { written ->
                            mutable.value=ToolchainStatus((written.toFloat()/manifest.resourceBytes).coerceAtMost(.98f),"解压本地标准库 ${written/1024/1024} MB")
                        }
                        aliases(staging,nativeDir,manifest)
                        runtime(staging)
                        require(resourcesPresent(Toolchain(staging,nativeDir,manifest))) { "离线运行资源不完整" }
                        AtomicFiles.text(File(staging,".native-verified"),manifestHash)
                        AtomicFiles.text(File(staging,".ready"),manifestHash)
                    }
                } else {
                    aliases(root,nativeDir,manifest)
                    runtime(root)
                    AtomicFiles.text(verified,manifestHash)
                }
                val chain=Toolchain(root,nativeDir,manifest)
                require(resourcesPresent(chain)) { "运行组件不完整，请重新检查并修复组件" }
                mutable.value=ToolchainStatus(1f,"四门语言已就绪",chain)
                chain
            } catch(error: Exception) {
                mutable.value=ToolchainStatus(error=error.message ?: "本地工具链初始化失败",message="工具链未就绪")
                throw error
            }
        }
    }

    private suspend fun verifyNative(directory: File, manifest: ToolchainManifest) {
        mutable.value=ToolchainStatus(.01f,"校验原生编译与调试组件")
        for(component in manifest.libraries) {
            currentCoroutineContext().ensureActive()
            require(Regex("lib[^/\\\\]+\\.so").matches(component.name)) { "原生组件名称无效" }
            val checksum=MessageDigest.getInstance("SHA-256")
            File(directory,component.name).inputStream().use { input ->
                val buffer=ByteArray(65536)
                while(true) { currentCoroutineContext().ensureActive(); val count=input.read(buffer); if(count<0) break; checksum.update(buffer,0,count) }
            }
            require(checksum.digest().joinToString("") { "%02x".format(it) }==component.sha256) { "安装包原生组件损坏：${component.name}，请重新安装完整 APK" }
        }
    }

    private fun aliases(root: File, nativeDir: File, manifest: ToolchainManifest) {
        for(alias in manifest.aliases) {
            require(Regex("lib[^/\\\\]+\\.so").matches(alias.library)) { "原生组件名称无效" }
            val path=File(root,alias.path).toPath()
            require(!alias.path.startsWith('/') && alias.path.split('/').none { it==".." || it.isBlank() })
            require(path.parent.toFile().canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
            val library=File(nativeDir,alias.library)
            require(library.isFile) { "缺少原生组件 ${alias.library}" }
            path.parent.toFile().mkdirs()
            if(Files.isSymbolicLink(path)) {
                if(Files.readSymbolicLink(path).toString()==library.absolutePath) continue
                Files.delete(path)
            }
            require(!Files.exists(path)) { "组件路径冲突：${alias.path}" }
            Os.symlink(library.absolutePath,path.toString())
        }
    }

    private fun runtime(root: File) {
        val directory=File(root,"runtime").apply { mkdirs() }
        for(name in listOf("mobilecode_python.py","mobilecode-runtime.jar")) {
            val bytes=context.assets.open("runtime/$name").use { it.readBytes() }
            AtomicFiles.write(File(directory,name),bytes)
            check(File(directory,name).setReadOnly())
        }
    }

    private fun resourcesPresent(chain: Toolchain): Boolean = listOf(chain.runner,chain.pythonBootstrap,chain.runtimeJar,
        chain.pythonLibrary,chain.jvmLibrary,chain.jdtLauncher,File(chain.javaHome,"lib/modules"),
        File(chain.pythonHome,"lib/python3.13/os.py"),File(chain.root,"usr/include/stdio.h"),File(chain.root,"usr/include/c++/v1/string"),
        chain.command("clang"),chain.command("clangd"),chain.command("ld.lld"),chain.command("lldb-dap"))
        .all { it.isFile && it.length()>0 }
}
