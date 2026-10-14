package dev.mobilecode.ide.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionHistoryTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun snapshot(root: File, id: String): File = File(root,id).apply {
        mkdirs()
        File(this,"snapshot.json").writeText("""{"id":"$id","project":{"id":"project"}}""")
        File(this,"console.log").writeText("actual log for $id")
        File(this,"work/result.txt").apply { parentFile!!.mkdirs(); writeText("generated result") }
    }
    private fun row(id: String)=HistoryEntity().also { it.id=id; it.projectId="project"; it.status="finished" }

    @Test fun persistedRecordFindsItsLogAndGeneratedFilesAfterReload() {
        val root=temporary.newFolder(); snapshot(root,"session")
        val history=readSessionHistory(root,listOf(row("session")))
        assertEquals(1,history.size)
        assertFalse(history.single().unlinkedSnapshot)
        assertEquals(setOf("console.log","result.txt"),history.single().files.map { it.name }.toSet())
        assertEquals("actual log for session",history.single().files.single { it.name=="console.log" }.readText())
    }

    @Test fun oldUnassociatedSnapshotIsExportableWithoutGuessingItsRecord() {
        val root=temporary.newFolder(); val old=snapshot(root,"old-snapshot")
        val history=readSessionHistory(root,listOf(row("old-operation")))
        assertTrue(history.single { it.id=="old-operation" }.files.isEmpty())
        val retained=history.single { it.id=="old-snapshot" }
        assertTrue(retained.unlinkedSnapshot)
        assertEquals("actual log for old-snapshot",retained.files.single { it.name=="console.log" }.readText())
        assertTrue(old.isDirectory)
    }

    @Test fun recordsOutsideTheVisiblePageAreNotMislabelledAsUnlinked() {
        val root=temporary.newFolder(); snapshot(root,"older-linked")
        assertTrue(readSessionHistory(root,emptyList(),setOf("older-linked")).isEmpty())
        val invalid=snapshot(root,"invalid")
        File(invalid,"snapshot.json").writeText("""{"id":"different-directory","project":{"id":"project"}}""")
        assertTrue(readSessionHistory(root,emptyList(),setOf("older-linked")).isEmpty())
    }
}
