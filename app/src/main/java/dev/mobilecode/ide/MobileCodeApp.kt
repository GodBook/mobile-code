package dev.mobilecode.ide

import android.app.Application
import android.content.ComponentCallbacks2
import dev.mobilecode.ide.data.*
import dev.mobilecode.ide.language.LanguageService
import dev.mobilecode.ide.runtime.*
import dev.mobilecode.ide.update.UpdateService
import kotlinx.coroutines.*
import java.io.File

class MobileCodeApp: Application() {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    lateinit var workspace: WorkspaceService; private set
    lateinit var settings: SettingsRepository; private set
    lateinit var toolchains: ToolchainService; private set
    lateinit var processes: ProcessFactory; private set
    lateinit var languages: LanguageService; private set
    lateinit var sessions: SessionSupervisor; private set
    lateinit var updates: UpdateService; private set
    lateinit var journal: WorkspaceJournal; private set
    override fun onCreate() {
        super.onCreate()
        workspace=WorkspaceService(this); settings=SettingsRepository(this); toolchains=ToolchainService(this)
        journal=WorkspaceJournal(this)
        processes=ProcessFactory(scope); languages=LanguageService(scope,processes,File(filesDir,"language-state"))
        sessions=SessionSupervisor(this,scope,workspace,processes)
        updates=UpdateService(this,scope)
        scope.launch { runCatching { toolchains.prepare() } }
    }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) scope.launch { languages.close() } }
}
