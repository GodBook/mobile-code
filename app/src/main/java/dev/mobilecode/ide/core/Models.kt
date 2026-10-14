package dev.mobilecode.ide.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest

val gson = Gson()
fun json(vararg pairs: Pair<String, Any?>): JsonObject = gson.toJsonTree(mapOf(*pairs)).asJsonObject

enum class CodeLanguage(val title: String, val extension: String, val lspId: String, val entry: String) {
    C("C", "c", "c", "src/main.c"),
    CPP("C++", "cpp", "cpp", "src/main.cpp"),
    PYTHON("Python", "py", "python", "src/main.py"),
    JAVA("Java", "java", "java", "Main");

    fun isSource(path: String) = when (this) {
        C -> path.endsWith(".c")
        CPP -> listOf(".cpp", ".cc", ".cxx", ".c").any(path::endsWith)
        PYTHON -> path.endsWith(".py")
        JAVA -> path.endsWith(".java")
    }
}

data class ProjectConfig(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val language: CodeLanguage,
    val sourceRoots: List<String> = listOf("src"),
    val entry: String = language.entry,
    val arguments: List<String> = emptyList(),
    val runTimeoutSeconds: Int = 60,
    val preloadedInput: String? = null,
)
data class Project(val config: ProjectConfig, val directory: File, val updatedAt: Long)
data class Document(val path: String, val text: String, val version: Int = 1, val savedVersion: Int = 1) {
    val dirty get() = version != savedVersion
}
data class Position(val line: Int, val character: Int)
data class SourceRange(val start: Position, val end: Position)
data class Diagnostic(val path: String, val range: SourceRange, val message: String, val severity: Int = 1, val version: Int? = null, val source: String = "")
data class Snapshot(val id: String, val project: ProjectConfig, val directory: File, val source: File, val work: File, val build: File, val hash: String)
data class Completion(val label: String, val insertText: String, val detail: String = "", val range: SourceRange? = null,
    val additionalEdits: List<Pair<SourceRange, String>> = emptyList(), val snippet: Boolean = false, val version: Int = 0)
enum class SessionPhase { IDLE, PREPARING, BUILDING, STARTING, RUNNING, PAUSED, FINISHED, FAILED, CANCELLED }
data class StackFrame(val id: Int, val name: String, val path: String?, val line: Int, val column: Int)
data class DebugVariable(val name: String, val value: String, val type: String = "", val reference: Int = 0)
data class Breakpoint(val path: String, val line: Int, val verified: Boolean = false, val message: String = "")
data class SessionState(
    val id: String? = null, val phase: SessionPhase = SessionPhase.IDLE, val debugging: Boolean = false,
    val output: String = "", val diagnostics: List<Diagnostic> = emptyList(), val exitCode: Int? = null,
    val standardOutput: String = "", val standardError: String = "", val buildOutput: String = "",
    val reason: String = "", val elapsedMs: Long = 0, val frames: List<StackFrame> = emptyList(),
    val variables: List<DebugVariable> = emptyList(), val threadId: Int = 1, val frameId: Int? = null,
    val breakpointResults: Map<String, List<Breakpoint>> = emptyMap(),
    val stopSequence: Int = 0,
    val inputOpen: Boolean = false, val inputSending: Boolean = false,
    val outputCounts: Map<String,Long> = emptyMap(),
) {
    val active get() = phase in setOf(SessionPhase.PREPARING, SessionPhase.BUILDING, SessionPhase.STARTING, SessionPhase.RUNNING, SessionPhase.PAUSED)
    val sourceLocked get() = active && debugging
    val canSendInput get() = phase==SessionPhase.RUNNING && inputOpen && !inputSending
}

data class CodeLocation(val path: String, val position: Position, val label: String = "")
data class CodeSymbol(val name: String, val kind: Int, val location: CodeLocation, val depth: Int = 0)

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

object Positions {
    fun index(text: String, position: Position): Int {
        var start = 0
        repeat(position.line.coerceAtLeast(0)) { val next = text.indexOf('\n', start); if (next < 0) return text.length; start = next + 1 }
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        return (start + position.character.coerceAtLeast(0)).coerceAtMost(end)
    }
    fun position(text: String, index: Int): Position {
        val end = index.coerceIn(0, text.length)
        var line = 0; var start = 0
        for (i in 0 until end) if (text[i] == '\n') { line++; start = i + 1 }
        return Position(line, end - start)
    }
    fun utf8Column(line: String, byteColumn: Int): Int {
        var bytes = 0; var index = 0
        while (index < line.length && bytes < byteColumn) {
            val cp = Character.codePointAt(line, index)
            val width = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            if (bytes + width > byteColumn) break
            bytes += width; index += Character.charCount(cp)
        }
        return index
    }
}
