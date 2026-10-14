package dev.mobilecode.ide.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")
data class EditorSettings(val dark: Boolean = true, val fontSize: Float = 16f, val wordWrap: Boolean = false,
    val runTimeout: Int = 60, val lastProject: String = "", val lastFile: String = "", val cursorLine: Int = 0, val cursorColumn: Int = 0,
    val followSystem: Boolean=false,val consoleFontSize: Float=13f)

class SettingsRepository(private val context: Context) {
    private val dark = booleanPreferencesKey("dark")
    private val font = floatPreferencesKey("font")
    private val wrap = booleanPreferencesKey("wrap")
    private val timeout = intPreferencesKey("timeout")
    private val project = stringPreferencesKey("lastProject")
    private val file = stringPreferencesKey("lastFile")
    private val line = intPreferencesKey("cursorLine")
    private val column = intPreferencesKey("cursorColumn")
    private val system=booleanPreferencesKey("followSystem")
    private val consoleFont=floatPreferencesKey("consoleFont")
    val flow = context.settingsStore.data.map { p -> EditorSettings(p[dark] ?: true,p[font] ?: 16f,p[wrap] ?: false,p[timeout] ?: 60,p[project] ?: "",p[file] ?: "",p[line] ?: 0,p[column] ?: 0,p[system] ?: false,p[consoleFont] ?: 13f) }
    suspend fun update(settings: EditorSettings) = context.settingsStore.edit {
        it[dark]=settings.dark; it[font]=settings.fontSize.coerceIn(10f,30f); it[wrap]=settings.wordWrap
        it[timeout]=settings.runTimeout.coerceIn(1,600); it[project]=settings.lastProject; it[file]=settings.lastFile
        it[line]=settings.cursorLine.coerceAtLeast(0); it[column]=settings.cursorColumn.coerceAtLeast(0)
        it[system]=settings.followSystem; it[consoleFont]=settings.consoleFontSize.coerceIn(12f,20f)
    }
    suspend fun appearance(settings: EditorSettings) = context.settingsStore.edit {
        it[dark]=settings.dark; it[font]=settings.fontSize.coerceIn(10f,30f)
        it[wrap]=settings.wordWrap; it[timeout]=settings.runTimeout.coerceIn(1,600)
        it[system]=settings.followSystem; it[consoleFont]=settings.consoleFontSize.coerceIn(12f,20f)
    }
    suspend fun location(projectId: String,path: String,cursorLine: Int=0,cursorColumn: Int=0) = context.settingsStore.edit {
        it[project]=projectId; it[file]=path
        it[line]=cursorLine.coerceAtLeast(0); it[column]=cursorColumn.coerceAtLeast(0)
    }
}
