package dev.mobilecode.ide.data

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files

internal fun readSessionHistory(root: File, rows: List<HistoryEntity>, linkedIds: Set<String> = rows.map { it.id }.toSet()): List<StoredSession> {
    fun files(directory: File): List<File> {
        if(Files.isSymbolicLink(directory.toPath())) return emptyList()
        val work=File(directory,"work")
        return (listOf(File(directory,"console.log"))+work.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { it.isFile }.toList())
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
    }
    val records=rows.map { row ->
        val directory=resolveInside(root,row.id)
        StoredSession(row.id,row.projectId,row.startedAt,row.status,row.durationMs,row.exitCode,files(directory))
    }
    // Old releases did not persist the relationship. Offer the actual directories
    // separately rather than assigning one user's log to a record by timestamp.
    val legacy=root.listFiles().orEmpty().filter { it.isDirectory && it.name !in linkedIds && !Files.isSymbolicLink(it.toPath()) }
        .sortedByDescending { File(it,"snapshot.json").lastModified() }.asSequence().mapNotNull { directory -> runCatching {
            val marker=File(directory,"snapshot.json")
            require(marker.isFile && marker.length()<=8L*1024*1024)
            val metadata=JsonParser.parseString(marker.readText()).asJsonObject
            require(metadata["id"].asString==directory.name)
            StoredSession(directory.name,metadata.getAsJsonObject("project")["id"].asString,marker.lastModified(),
                "未关联的旧快照",0,null,files(directory),unlinkedSnapshot=true)
        }.getOrNull() }.take(20).toList()
    return (records+legacy).sortedByDescending { it.startedAt }
}
