package dev.mobilecode.ide.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticProvenanceTest {
    @get:Rule val temporary=TemporaryFolder()
    private val document=Document("src/main.py","# 中文😀\nprint(\n",version=4)
    private fun project(id: String)=Project(ProjectConfig(id=id,name=id,language=CodeLanguage.PYTHON),temporary.newFolder(id),0)
    private fun diagnostic(path: String)=Diagnostic(path,SourceRange(Position(1,0),Position(1,5)),"syntax error",source="Python")
    private fun snapshot(project: Project): Snapshot {
        val directory=temporary.newFolder()
        val source=File(directory,"source").apply { mkdirs() }
        return Snapshot("session",project.config,directory,source,File(directory,"work"),File(directory,"build"),"",
            mapOf(document.path to document.contentHash))
    }

    @Test fun buildErrorsRequireTheSameProjectAndExactSourceContents() {
        val project=project("one"); val snapshot=snapshot(project)
        val error=DiagnosticProvenance.build(diagnostic(File(snapshot.source,document.path).path),snapshot)
        assertTrue(DiagnosticProvenance.matchesEditor(error,project,document))
        assertFalse(DiagnosticProvenance.matchesEditor(error,project("two"),document))
        assertFalse(DiagnosticProvenance.matchesEditor(error,project,document.copy(text="print('fixed')\n",version=5)))
        // Reopening resets the editor version; a number alone is not a source identity.
        assertFalse(DiagnosticProvenance.matchesEditor(error,project,Document(document.path,"different source",version=4)))
        assertTrue(DiagnosticProvenance.matchesEditor(error,project,Document(document.path,document.text,version=1)))
    }

    @Test fun aSnapshotPreviewRequiresItsOwnFileAndHash() {
        val project=project("one"); val snapshot=snapshot(project)
        val file=File(snapshot.source,document.path)
        val error=DiagnosticProvenance.build(diagnostic(file.path),snapshot)
        assertTrue(DiagnosticProvenance.matchesEditor(error,project,document,file))
        assertFalse(DiagnosticProvenance.matchesEditor(error,project,document,File(project.directory,document.path)))
        assertFalse(DiagnosticProvenance.matchesEditor(error,project,document.copy(text="changed"),file))
    }

    @Test fun liveErrorsRequireAnExactPathAndMatchingReportedVersion() {
        val project=project("one")
        val error=diagnostic(File(project.directory,document.path).path).copy(projectId="one",version=4)
        assertTrue(DiagnosticProvenance.matchesEditor(error,project,document))
        assertFalse(DiagnosticProvenance.matchesEditor(error.copy(version=3),project,document))
        assertFalse(DiagnosticProvenance.matchesEditor(error.copy(version=null),project,document))
        assertFalse(DiagnosticProvenance.matchesEditor(error.copy(path=File(temporary.newFolder(),document.path).path),project,document))
    }

    @Test fun structuredRuntimeErrorsReceiveProvenanceEvenWithoutJsonMetadata() {
        val project=project("one"); val snapshot=snapshot(project)
        val raw=gson.toJson(mapOf("path" to File(snapshot.source,document.path).path,"range" to SourceRange(Position(0,0),Position(0,1)),
            "message" to "actual compiler error","severity" to 1,"source" to "Python"))
        val error=DiagnosticProvenance.build(gson.fromJson(raw,Diagnostic::class.java),snapshot)
        assertEquals(DiagnosticOrigin.BUILD,error.origin)
        assertEquals(snapshot.id,error.snapshotId)
        assertEquals(document.contentHash,error.sourceHash)
        assertTrue(DiagnosticProvenance.matchesEditor(error,project,document))
    }

    @Test fun systemHeaderErrorsCannotMarkAProjectFileWithTheSameSuffix() {
        val project=project("one"); val snapshot=snapshot(project)
        val error=DiagnosticProvenance.build(diagnostic(File(temporary.newFolder(),document.path).path),snapshot)
        assertNull(error.relativePath)
        assertFalse(DiagnosticProvenance.matchesEditor(error,project,document))
    }
}
