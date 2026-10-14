package dev.mobilecode.ide.runtime

import dev.mobilecode.ide.core.sha256
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfflineResourcesTest {
    @get:Rule val temporary=TemporaryFolder()
    @After fun makeFixturesDeletable() { temporary.root.walkBottomUp().forEach { it.setWritable(true) } }
    private fun archive(vararg entries: Pair<String,String>): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { zip -> entries.forEach { (path,text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }.toByteArray()
    private fun existing(parent: File)=File(parent,"resources").apply { mkdirs(); File(this,"original").writeText("retained") }

    @Test fun repairReplacesCorruptAndMissingResourcesFromTheVerifiedArchive() = runTest {
        val parent=temporary.newFolder(); val root=existing(parent)
        File(root,"header.h").writeText("corrupt")
        val bytes=archive("header.h" to "correct","stdlib/os.py" to "offline")
        val open={ ByteArrayInputStream(bytes) }
        verifyOfflineArchive(open,sha256(bytes))
        OfflineResourceInstaller(parent,"resources").replace { extractOfflineArchive(open,it,14) }
        assertEquals("correct",File(root,"header.h").readText())
        assertEquals("offline",File(root,"stdlib/os.py").readText())
        assertTrue(File(root,OfflineResourceInstaller.COMMITTED).isFile)
        assertFalse(File(parent,".backup-resources").exists())
    }

    @Test fun corruptArchiveCannotReplaceExistingResources() = runTest {
        val parent=temporary.newFolder(); val root=existing(parent)
        val bytes=archive("header.h" to "new")
        val failure=runCatching { verifyOfflineArchive({ ByteArrayInputStream(bytes) },"0".repeat(64)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("retained",File(root,"original").readText())
    }

    @Test fun traversalAndOversizedEntriesLeaveTheOriginalTreeIntact() = runTest {
        for((path,size) in listOf("../outside" to 7L,"header.h" to 2L)) {
            val parent=temporary.newFolder(); val root=existing(parent)
            val bytes=archive(path to "payload")
            val failure=runCatching { OfflineResourceInstaller(parent,"resources").replace {
                extractOfflineArchive({ ByteArrayInputStream(bytes) },it,size)
            } }.exceptionOrNull()
            assertTrue(failure.toString(),failure is IllegalArgumentException)
            assertEquals("retained",File(root,"original").readText())
            assertFalse(File(parent,"outside").exists())
        }
    }

    @Test fun incompleteExtractionCannotPublishAReadyTree() = runTest {
        val parent=temporary.newFolder(); val root=existing(parent)
        val bytes=archive("header.h" to "short")
        val failure=runCatching { OfflineResourceInstaller(parent,"resources").replace {
            extractOfflineArchive({ ByteArrayInputStream(bytes) },it,100)
        } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("retained",File(root,"original").readText())
    }

    @Test fun cancellationBeforePublicationPreservesOriginalResources() = runTest {
        val parent=temporary.newFolder(); val root=existing(parent)
        val staged=CompletableDeferred<Unit>()
        val job=launch { OfflineResourceInstaller(parent,"resources").replace {
            File(it,"partial").writeText("partial"); staged.complete(Unit); awaitCancellation()
        } }
        staged.await(); job.cancelAndJoin()
        assertEquals("retained",File(root,"original").readText())
        assertEquals(listOf("resources"),parent.listFiles()!!.map { it.name })
    }

    @Test fun publicationFailureRollsBackThePreviousDirectory() = runTest {
        val parent=temporary.newFolder(); val root=existing(parent)
        val installer=OfflineResourceInstaller(parent,"resources") { from,to ->
            if(from.name.startsWith(".prepare-")) throw IOException("injected rename failure")
            Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE); Unit
        }
        val failure=runCatching { installer.replace { File(it,"new").writeText("new") } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("retained",File(root,"original").readText())
        assertFalse(File(parent,".backup-resources").exists())
    }

    @Test fun freshProcessRollsBackASwapInterruptedBeforeCommit() {
        val parent=temporary.newFolder(); val old=existing(parent)
        Files.move(old.toPath(),File(parent,".backup-resources").toPath())
        File(parent,"resources").apply { mkdirs(); File(this,"uncommitted").writeText("new") }
        OfflineResourceInstaller(parent,"resources").recover()
        assertEquals("retained",File(parent,"resources/original").readText())
        assertFalse(File(parent,"resources/uncommitted").exists())
    }

    @Test fun freshProcessKeepsACommittedRepairAndRemovesOnlyItsBackup() {
        val parent=temporary.newFolder(); val old=existing(parent)
        Files.move(old.toPath(),File(parent,".backup-resources").toPath())
        File(parent,"resources").apply {
            mkdirs(); File(this,"repaired").writeText("correct"); File(this,OfflineResourceInstaller.COMMITTED).writeText("1")
        }
        val source=File(parent,"user-project").apply { mkdirs(); File(this,"main.py").writeText("source") }
        OfflineResourceInstaller(parent,"resources").recover()
        assertEquals("correct",File(parent,"resources/repaired").readText())
        assertFalse(File(parent,".backup-resources").exists())
        assertEquals("source",File(source,"main.py").readText())
    }

    @Test fun freshProcessReclaimsOnlyItsAbandonedStagesBeforeCheckingFreeSpace() {
        val parent=temporary.newFolder(); val root=existing(parent)
        File(parent,".prepare-resources-interrupted").apply { mkdirs(); File(this,"partial").writeText("partial") }
        val other=File(parent,".prepare-other-active").apply { mkdirs(); File(this,"retained").writeText("other") }
        OfflineResourceInstaller(parent,"resources").recover()
        assertFalse(File(parent,".prepare-resources-interrupted").exists())
        assertEquals("retained",File(root,"original").readText())
        assertEquals("other",File(other,"retained").readText())
    }
}
