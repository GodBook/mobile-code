package dev.mobilecode.ide.runtime

import dev.mobilecode.ide.data.AtomicFiles
import dev.mobilecode.ide.data.deleteOwnedTree
import dev.mobilecode.ide.data.resolveInside
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

internal suspend fun verifyOfflineArchive(open: ()->InputStream, expectedHash: String) {
    val checksum=MessageDigest.getInstance("SHA-256")
    open().use { input ->
        val buffer=ByteArray(65536)
        while(true) {
            currentCoroutineContext().ensureActive()
            val count=input.read(buffer); if(count<0) break
            checksum.update(buffer,0,count)
        }
    }
    require(checksum.digest().joinToString("") { "%02x".format(it) }==expectedHash) { "离线资源校验失败" }
}

internal suspend fun extractOfflineArchive(open: ()->InputStream, directory: File, expectedBytes: Long,
    progress: (Long)->Unit = {}) {
    var written=0L; var next=0L
    ZipInputStream(open()).use { zip ->
        while(true) {
            currentCoroutineContext().ensureActive()
            val entry=zip.nextEntry ?: break
            val path=entry.name.trimEnd('/')
            require(path !in setOf(".ready",".native-verified",OfflineResourceInstaller.COMMITTED)) { "离线资源包含保留路径" }
            val target=resolveInside(directory,path)
            if(entry.isDirectory) check(target.mkdirs() || target.isDirectory) else {
                check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                require(!target.exists()) { "离线资源路径重复：$path" }
                target.outputStream().use { output ->
                    val buffer=ByteArray(65536)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val count=zip.read(buffer); if(count<0) break
                        written+=count
                        require(written<=expectedBytes) { "离线资源大小异常" }
                        output.write(buffer,0,count)
                    }
                }
                check(target.setReadOnly()) { "无法保护离线资源：$path" }
            }
            if(written>=next) { progress(written); next=written+2*1024*1024 }
            zip.closeEntry()
        }
    }
    require(written==expectedBytes) { "离线资源不完整" }
}

/** Publish only a complete tree. A retained backup makes an interrupted swap recoverable. */
internal class OfflineResourceInstaller(private val parent: File, name: String,
    private val move: (File,File)->Unit = { from,to -> Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE); Unit }) {
    init { require(Regex("[A-Za-z0-9_-]+").matches(name)) }
    val root=File(parent,name)
    private val backup=File(parent,".backup-$name")
    companion object { const val COMMITTED=".install-committed" }

    fun recover() {
        if(backup.exists()) {
            if(root.isDirectory && File(root,COMMITTED).isFile) {
                deleteOwnedTree(backup,parent)
            } else {
                val abandoned=File(parent,".aborted-${root.name}-${UUID.randomUUID()}")
                if(root.exists()) move(root,abandoned)
                try { move(backup,root) }
                catch(error: Throwable) { if(abandoned.exists() && !root.exists()) move(abandoned,root); throw error }
                if(abandoned.exists()) deleteOwnedTree(abandoned,parent)
            }
        }
        // A process can die before it reaches the swap. Reclaim only this
        // installer's abandoned resource stages, including on a first launch.
        parent.listFiles().orEmpty().filter { it.name.startsWith(".prepare-${root.name}-") || it.name.startsWith(".aborted-${root.name}-") }
            .forEach { deleteOwnedTree(it,parent) }
    }

    suspend fun replace(build: suspend (File)->Unit): File {
        recover()
        val staging=Files.createTempDirectory(parent.toPath(),".prepare-${root.name}-").toFile()
        try {
            build(staging)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                if(root.exists()) move(root,backup)
                var published=false
                try {
                    move(staging,root); published=true
                    AtomicFiles.text(File(root,COMMITTED),"1")
                } catch(error: Throwable) {
                    if(published) deleteOwnedTree(root,parent)
                    if(backup.exists()) move(backup,root)
                    throw error
                }
                if(backup.exists()) deleteOwnedTree(backup,parent)
            }
            return root
        } finally { if(staging.exists()) deleteOwnedTree(staging,parent) }
    }
}
