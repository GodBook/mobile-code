package dev.mobilecode.ide

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.mobilecode.ide.ui.IdeScreen

class MainActivity: ComponentActivity() {
    private val model: IdeViewModel by viewModels()
    private var notificationAsked=false
    private val importProject=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.importProject(it) } }
    private val exportProject=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { model.exportProject(it) } }
    private val exportSource=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { model.exportSource(it) } }
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val installPermission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(packageManager.canRequestPackageInstalls()) installDownloadedUpdate()
        else model.report("尚未允许安装更新，可稍后从软件更新中重试")
    }
    override fun onStart() {
        super.onStart()
        model.foreground()
        model.app.updates.check(manual=false)
    }
    private fun installDownloadedUpdate() {
        model.installUpdate { apk ->
            if(!packageManager.canRequestPackageInstalls()) {
                installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))
            } else {
                val uri=FileProvider.getUriForFile(this,"$packageName.updates",apk)
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri,"application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData=ClipData.newRawUri("Mobile Code update",uri)
                })
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val dark by remember(model) { model.settings.map { it.dark }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue=true)
            LaunchedEffect(dark) {
                val style=if(dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(style,style)
            }
            val palette=remember(dark) { if(dark) darkColorScheme(primary=Color(0xFF82D6FF),onPrimary=Color(0xFF003548),background=Color(0xFF111213),surface=Color(0xFF111213),onSurface=Color(0xFFD9E2EE),onSurfaceVariant=Color(0xFFA1AFBF),surfaceContainer=Color(0xFF202429),surfaceContainerLow=Color(0xFF191C20),surfaceContainerHigh=Color(0xFF292E35),outline=Color(0xFF8190A1),outlineVariant=Color(0xFF343B43),error=Color(0xFFFF857B))
                else lightColorScheme(primary=Color(0xFF00658A),onPrimary=Color.White,background=Color(0xFFFAFAF8),surface=Color(0xFFFAFAF8),onSurface=Color(0xFF263445),onSurfaceVariant=Color(0xFF566577),surfaceContainer=Color(0xFFEEF1F4),surfaceContainerLow=Color(0xFFF4F6F8),surfaceContainerHigh=Color(0xFFE5EAF0),outline=Color(0xFF718094),outlineVariant=Color(0xFFD2DAE3),error=Color(0xFFC02C27)) }
            MaterialTheme(colorScheme=palette) {
                val lifecycle=LocalLifecycleOwner.current
                DisposableEffect(lifecycle) {
                    val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) model.flush() }
                    lifecycle.lifecycle.addObserver(observer); onDispose { lifecycle.lifecycle.removeObserver(observer) }
                }
                IdeScreen(model,onImport={ importProject.launch(arrayOf("application/zip","application/octet-stream")) },
                    onExport={ exportProject.launch("${model.state.value.project?.config?.name ?: "project"}.zip") },
                    onExportSource={ exportSource.launch(model.state.value.activePath.substringAfterLast('/')) },
                    onRun={ debug ->
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(currentFocus?.windowToken,0)
                        if(Build.VERSION.SDK_INT>=33 && !notificationAsked && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                            notificationAsked=true; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        model.run(debug)
                    },onCopy={ text -> (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Mobile Code",text)) },
                    onInstallUpdate=::installDownloadedUpdate)
            }
        }
    }
}
