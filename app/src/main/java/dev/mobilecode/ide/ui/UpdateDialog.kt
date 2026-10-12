package dev.mobilecode.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobilecode.ide.BuildConfig
import dev.mobilecode.ide.update.UpdatePhase
import dev.mobilecode.ide.update.UpdateService
import java.util.Locale

@Composable
fun UpdateDialog(service: UpdateService, installationAllowed: Boolean, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val state by service.state.collectAsStateWithLifecycle()
    val browser = LocalUriHandler.current
    LaunchedEffect(Unit) { service.check() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("软件更新") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text(state.message, modifier = Modifier.testTag("更新状态"), color = if (state.phase == UpdatePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (state.hasUpdate) state.release?.let { release ->
                    Text("新版本 ${release.versionName} · ${mib(release.size)} MiB", style = MaterialTheme.typography.titleSmall)
                    if (release.releaseNotes.isNotBlank()) Text(release.releaseNotes, style = MaterialTheme.typography.bodySmall)
                    if (state.phase == UpdatePhase.DOWNLOADING) {
                        LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / release.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${mib(state.downloadedBytes)} / ${mib(release.size)} MiB", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (state.phase == UpdatePhase.CHECKING || state.phase == UpdatePhase.VERIFYING) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.phase == UpdatePhase.READY && !installationAllowed) Text("请先停止运行或调试，再安装更新。", color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启动时检查更新", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(state.autoCheck, onCheckedChange = service::setAutoCheck, modifier = Modifier.testTag("自动检查更新"))
                }
                Text("检查更新不会自动下载。下载可在后台继续；安装由安卓系统确认，项目文件会保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { browser.openUri("https://github.com/${BuildConfig.UPDATE_REPOSITORY}") }) { Text("GitHub 项目与历史版本") }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.READY -> TextButton(onClick = onInstall, enabled = installationAllowed, modifier = Modifier.testTag("安装更新")) { Text("安装更新") }
                UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING -> TextButton(onClick = { service.cancelDownload() }, modifier = Modifier.testTag("取消更新下载")) { Text("取消下载") }
                else -> {
                    if (state.hasUpdate && !state.busy) TextButton(onClick = { service.download() }, modifier = Modifier.testTag("下载更新")) { Text("下载更新") }
                    else TextButton(onClick = { service.check() }, enabled = !state.busy, modifier = Modifier.testTag("检查更新")) { Text("检查更新") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun mib(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0)
