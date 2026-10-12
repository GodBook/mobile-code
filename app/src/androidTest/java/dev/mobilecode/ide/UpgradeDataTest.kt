package dev.mobilecode.ide

import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import dev.mobilecode.ide.core.*
import dev.mobilecode.ide.data.EditorSettings
import dev.mobilecode.ide.data.AtomicFiles
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Run the two methods separately, before and after the actual system APK update. */
class UpgradeDataTest {
    private val app get() = ApplicationProvider.getApplicationContext<MobileCodeApp>()
    private val record get() = File(app.filesDir, "update-acceptance.json")
    private data class Marker(val projectId: String, val sourceHash: String, val projectIds: List<String>, val settings: EditorSettings)
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    @Test fun recordBeforeSystemUpdate() = runBlocking {
        val project = app.workspace.create("升级保留验收", CodeLanguage.PYTHON, false)
        val source = "# 升级数据验收 😀\nvalue = 17\nprint(value)\n"
        app.workspace.save(project, Document("src/main.py", source))
        app.workspace.saveBreakpoints(project, listOf(Breakpoint("src/main.py", 1)))
        val marker = Marker(project.config.id, sha(source), app.workspace.projects().map { it.config.id }, app.settings.flow.first())
        AtomicFiles.text(record, Gson().toJson(marker))
        assertTrue(record.isFile)
    }

    @Test fun verifyAfterSystemUpdate() = runBlocking {
        assertTrue("Create the acceptance marker before installing the update", record.isFile)
        val marker = Gson().fromJson(record.readText(), Marker::class.java)
        val projects = app.workspace.projects()
        val currentIds = projects.map { it.config.id }.toSet()
        assertTrue("Projects disappeared during the APK update", currentIds.containsAll(marker.projectIds))
        val project = projects.first { it.config.id == marker.projectId }
        assertEquals(marker.sourceHash, sha(app.workspace.open(project, "src/main.py").text))
        assertTrue(app.workspace.breakpoints(project).any { it.path == "src/main.py" && it.line == 1 })
        val current = app.settings.flow.first()
        assertEquals(marker.settings.dark, current.dark)
        assertEquals(marker.settings.fontSize, current.fontSize, 0f)
        assertEquals(marker.settings.wordWrap, current.wordWrap)
        assertEquals(marker.settings.runTimeout, current.runTimeout)
    }
}
