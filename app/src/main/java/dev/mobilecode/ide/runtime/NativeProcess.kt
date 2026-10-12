package dev.mobilecode.ide.runtime

import android.os.ParcelFileDescriptor
import dev.mobilecode.ide.core.Snapshot
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

object NativeProcess {
    init { System.loadLibrary("mc_process") }
    external fun spawn(executable: String, arguments: Array<String>, environment: Array<String>, cwd: String, sources: IntArray, targets: IntArray): IntArray
    external fun waitFor(pid: Int): Int
    external fun signal(pid: Int, signal: Int, group: Boolean)
    external fun socketPair(): IntArray
    external fun pipe(): IntArray
    external fun fifoEndpoints(paths: Array<String>): IntArray
    external fun closeFd(fd: Int)
}

class ChildProcess(val pid: Int, inputFd: Int, outputFd: Int, errorFd: Int, private val scope: CoroutineScope) {
    val input: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(inputFd))
    val output: InputStream = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(outputFd))
    val error: InputStream = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(errorFd))
    private val inputClosed=AtomicBoolean()
    val exit=scope.async(Dispatchers.IO) { NativeProcess.waitFor(pid) }
    suspend fun send(text: String) = withContext(Dispatchers.IO) { synchronized(input) { check(!inputClosed.get()) { "标准输入已经关闭" }; input.write(text.toByteArray()); input.flush() } }
    fun eof() { if(inputClosed.compareAndSet(false,true)) runCatching { input.close() } }
    suspend fun stop() {
        if(!exit.isCompleted) {
            NativeProcess.signal(pid,15,true)
            if(withTimeoutOrNull(1200) { exit.await() }==null) NativeProcess.signal(pid,9,true)
            withTimeoutOrNull(800) { exit.await() }
        }
        eof()
    }
    fun closeStreams() { eof(); runCatching { output.close() }; runCatching { error.close() } }
}

data class DuplexStreams(val input: InputStream, val output: OutputStream) {
    fun close() { runCatching { input.close() }; runCatching { output.close() } }
    companion object {
        fun adopt(fd: Int): DuplexStreams {
            val owner=ParcelFileDescriptor.adoptFd(fd)
            val writer=ParcelFileDescriptor.dup(owner.fileDescriptor)
            return DuplexStreams(ParcelFileDescriptor.AutoCloseInputStream(owner),ParcelFileDescriptor.AutoCloseOutputStream(writer))
        }
    }
}

class ProcessFactory(private val scope: CoroutineScope) {
    fun start(toolchain: Toolchain, executable: File, arguments: List<String>, cwd: File,
              pass: Map<Int,Int> = emptyMap(), extraEnvironment: Map<String,String> = emptyMap()): ChildProcess {
        File(cwd,"tmp").mkdirs()
        val environment=mapOf("PATH" to "${toolchain.root}/usr/bin:/system/bin", "HOME" to cwd.absolutePath,
            "TMPDIR" to File(cwd,"tmp").absolutePath,"LANG" to "C.UTF-8","LC_ALL" to "C.UTF-8",
            "LD_LIBRARY_PATH" to toolchain.nativeDir.absolutePath,
            "PYTHONHOME" to toolchain.pythonHome.absolutePath,"PYTHONNOUSERSITE" to "1",
            "PYTHONDONTWRITEBYTECODE" to "1","PYTHONUNBUFFERED" to "1",
            "LLDB_DEBUGSERVER_PATH" to toolchain.command("lldb-server").absolutePath,
            "TERMINFO" to File(toolchain.root,"usr/share/terminfo").absolutePath,
            "JAVA_HOME" to toolchain.javaHome.absolutePath) + extraEnvironment
        val result=NativeProcess.spawn(toolchain.runner.absolutePath,(listOf("exec",executable.absolutePath)+arguments).toTypedArray(),
            environment.map { "${it.key}=${it.value}" }.toTypedArray(),cwd.absolutePath,pass.keys.toIntArray(),pass.values.toIntArray())
        return ChildProcess(result[0],result[1],result[2],result[3],scope)
    }
    fun python(toolchain: Toolchain, mode: String, config: File, cwd: File, pass: Map<Int,Int> = emptyMap()): ChildProcess =
        start(toolchain,toolchain.runner,listOf("python",toolchain.pythonLibrary.absolutePath,toolchain.pythonHome.absolutePath,toolchain.pythonBootstrap.absolutePath,mode,config.absolutePath),cwd,pass)
    fun java(toolchain: Toolchain, main: String, arguments: List<String>, cwd: File,
             classpath: String=toolchain.runtimeJar.absolutePath, vmOptions: List<String> = emptyList(), pass: Map<Int,Int> = emptyMap()): ChildProcess =
        start(toolchain,toolchain.runner,listOf("jvm",toolchain.jvmLibrary.absolutePath,toolchain.javaHome.absolutePath,
            toolchain.nativeDir.absolutePath,classpath,main)+vmOptions.map { "--vm=$it" }+arguments,cwd,pass)
}
