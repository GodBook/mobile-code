package dev.mobilecode.ide.protocol

import com.google.gson.*
import dev.mobilecode.ide.core.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class JsonConnection(private val input: InputStream, private val output: OutputStream,
                     private val scope: CoroutineScope, private val dap: Boolean=false,
                     private val serverRequest: suspend (JsonObject)->JsonElement = { JsonNull.INSTANCE }) {
    private val next=AtomicInteger()
    private val pending=ConcurrentHashMap<Int,CompletableDeferred<JsonObject>>()
    private val mutableEvents=MutableSharedFlow<JsonObject>(extraBufferCapacity=128)
    val events=mutableEvents.asSharedFlow()
    val reader: Job
    init {
        reader=scope.launch(Dispatchers.IO) {
            try {
                val stream=BufferedInputStream(input)
                while(isActive) {
                    val message=read(stream) ?: break
                    if(dap && message["type"]?.asString=="response") pending.remove(message["request_seq"].asInt)?.complete(message)
                    else if(!dap && message.has("id") && !message.has("method")) pending.remove(message["id"].asInt)?.complete(message)
                    else if(!dap && message.has("id") && message.has("method")) {
                        launch { val result=runCatching { serverRequest(message) }.getOrDefault(JsonNull.INSTANCE); write(json("jsonrpc" to "2.0","id" to message["id"],"result" to result)) }
                    } else mutableEvents.emit(message)
                }
                throw EOFException("本地语言/调试服务已断开")
            } catch(error: Exception) {
                pending.values.forEach { it.completeExceptionally(error) }; pending.clear()
            }
        }
    }
    private fun read(input: BufferedInputStream): JsonObject? {
        var length=-1
        while(true) {
            val line=ByteArrayOutputStream()
            while(true) { val b=input.read(); if(b<0) return null; if(b==10) break; line.write(b); require(line.size()<=8192) }
            val text=line.toString("US-ASCII").trim()
            if(text.isEmpty()) break
            if(text.substringBefore(':').equals("Content-Length",true)) length=text.substringAfter(':').trim().toInt()
        }
        require(length in 0..16*1024*1024) { "协议消息大小异常" }
        val payload=ByteArray(length); var position=0
        while(position<length) { val n=input.read(payload,position,length-position); if(n<0) throw EOFException(); position+=n }
        return JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject
    }
    private fun write(message: JsonObject) {
        synchronized(output) {
            val bytes=message.toString().toByteArray()
            output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.write(bytes); output.flush()
        }
    }
    suspend fun request(method: String, parameters: JsonObject=json(), timeout: Long=2000): JsonObject {
        val id=next.incrementAndGet(); val deferred=CompletableDeferred<JsonObject>(); pending[id]=deferred
        try {
            withContext(Dispatchers.IO) { write(if(dap) json("seq" to id,"type" to "request","command" to method,"arguments" to parameters)
                else json("jsonrpc" to "2.0","id" to id,"method" to method,"params" to parameters)) }
            val response=withTimeout(timeout) { deferred.await() }
            if(dap && response["success"]?.asBoolean==false) error(response["message"]?.asString ?: "调试请求失败")
            if(response.has("error")) error(response.getAsJsonObject("error")["message"]?.asString ?: "语言服务请求失败")
            return response
        } catch(cancel: CancellationException) {
            if(!dap) runCatching { notify("$/cancelRequest",json("id" to id)) }
            throw cancel
        } finally { pending.remove(id) }
    }
    suspend fun notify(method: String, parameters: JsonObject=json()) = withContext(Dispatchers.IO) {
        write(json("jsonrpc" to "2.0","method" to method,"params" to parameters))
    }
    fun close() { runCatching { input.close() }; runCatching { output.close() }; reader.cancel(); pending.values.forEach { it.cancel() }; pending.clear() }
}
