package dev.mobilecode.ide.core

import java.io.File

/** A build location always refers to the immutable source, even when compilation fails. */
object DiagnosticProvenance {
    fun build(item: Diagnostic, snapshot: Snapshot): Diagnostic {
        val file=File(item.path).canonicalFile
        val root=snapshot.source.canonicalFile
        val relative=if(file.toPath().startsWith(root.toPath()) && file!=root) file.relativeTo(root).invariantSeparatorsPath else null
        return item.copy(path=file.path,origin=DiagnosticOrigin.BUILD,projectId=snapshot.project.id,snapshotId=snapshot.id,
            relativePath=relative,sourceHash=relative?.let { snapshot.fileHashes[it] })
    }

    fun matchesEditor(item: Diagnostic, project: Project, document: Document, previewFile: File?=null): Boolean {
        if(item.projectId!=project.config.id) return false
        if(previewFile!=null) return item.origin==DiagnosticOrigin.BUILD &&
            File(item.path).canonicalFile==previewFile.canonicalFile && item.sourceHash==document.contentHash
        return when(item.origin) {
            DiagnosticOrigin.BUILD -> item.snapshotId!=null && item.relativePath==document.path && item.sourceHash==document.contentHash
            // Servers that omit a document version remain visible in Problems, but cannot
            // safely mark an editable buffer as if the response described that revision.
            DiagnosticOrigin.LANGUAGE -> item.version==document.version &&
                File(item.path).canonicalFile==File(project.directory,document.path).canonicalFile
        }
    }
}
