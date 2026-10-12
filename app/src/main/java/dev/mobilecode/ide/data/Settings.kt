package dev.mobilecode.ide.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")
data class EditorSettings(val dark: Boolean = true, val fontSize: Float = 16f, val wordWrap: Boolean = false,
    val runTimeout: Int = 60, val lastProject: String = "", val lastFile: String = "", val cursorLine: Int = 0, val cursorColumn: Int = 0)

class SettingsRepository(private val context: Context) {
    private val dark = booleanPreferencesKey("dark")
    private val font = floatPreferencesKey("font")
    private val wrap = booleanPreferencesKey("wrap")
    private val timeout = intPreferencesKey("timeout")
    private val project = stringPreferencesKey("lastProject")
    private val file = stringPreferencesKey("lastFile")
    private val line = intPreferencesKey("cursorLine")
    private val column = intPreferencesKey("cursorColumn")
    val flow = context.settingsStore.data.map { p -> EditorSettings(p[dark] ?: true,p[font] ?: 16f,p[wrap] ?: false,p[timeout] ?: 60,p[project] ?: "",p[file] ?: "",p[line] ?: 0,p[column] ?: 0) }
    suspend fun update(settings: EditorSettings) = context.settingsStore.edit {
        it[dark]=settings.dark; it[font]=settings.fontSize.coerceIn(10f,30f); it[wrap]=settings.wordWrap
        it[timeout]=settings.runTimeout.coerceIn(1,600); it[project]=settings.lastProject; it[file]=settings.lastFile
        it[line]=settings.cursorLine.coerceAtLeast(0); it[column]=settings.cursorColumn.coerceAtLeast(0)
    }
}
