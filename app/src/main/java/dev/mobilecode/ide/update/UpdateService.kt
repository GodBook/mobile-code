package dev.mobilecode.ide.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import dev.mobilecode.ide.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: UpdateRelease? = null,
    val downloadedBytes: Long = 0,
    val message: String = "检查 GitHub 上的新版本",
    val autoCheck: Boolean = true,
) {
    val busy get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
    val hasUpdate get() = release?.isNewerThan(BuildConfig.VERSION_CODE.toLong()) == true
}

/** The system download survives activity recreation and application process death. */
class UpdateService(private val context: Context, private val scope: CoroutineScope) {
    private val preferences = context.getSharedPreferences("application-updates", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(UpdateState(autoCheck = preferences.getBoolean("auto", true)))
    val state = mutable.asStateFlow()
    private val commands = Mutex()
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val http = UpdateHttpClient()
    private val verifier = ApkVerifier(context)
    private var downloadJob: Job? = null
    private val restored = scope.async { commands.withLock { restore() } }

    private fun parse(text: String) = UpdateRelease.parse(text, BuildConfig.UPDATE_REPOSITORY,
        context.packageName, BuildConfig.RUNTIME_ABI, Build.VERSION.SDK_INT)
    private fun readyFile(release: UpdateRelease) = File(context.filesDir, "updates/${release.versionCode}-${release.sha256}.apk")
    private fun partialFile(release: UpdateRelease): File {
        val directory = context.getExternalFilesDir("updates") ?: error("下载存储暂时不可用")
        return File(directory, "${release.versionCode}-${release.sha256}.apk")
    }
    private fun clearPending() {
        preferences.edit().remove("pending").remove("downloadId").remove("ready").commit()
    }

    private suspend fun restore() {
        try {
            val pending = preferences.getString("pending", null)
            if (pending != null) {
                val release = parse(pending)
                if (!release.isNewerThan(BuildConfig.VERSION_CODE.toLong())) {
                    val id = preferences.getLong("downloadId", 0)
                    if (id > 0) manager.remove(id)
                    readyFile(release).delete()
                    clearPending()
                } else if (preferences.getBoolean("ready", false)) {
                    mutable.update { it.copy(phase = UpdatePhase.VERIFYING, release = release, message = "重新校验已下载的更新…") }
                    verifier.verify(readyFile(release), release)
                    mutable.update { it.copy(phase = UpdatePhase.READY, message = "校验通过，可以安装更新") }
                    return
                } else {
                    val id = preferences.getLong("downloadId", 0)
                    if (id > 0) {
                        mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, release = release, message = "恢复下载进度…") }
                        observe(id, release)
                        return
                    }
                }
            }
            preferences.getString("latest", null)?.let { value ->
                val release = parse(value)
                mutable.update { it.copy(phase = if (release.isNewerThan(BuildConfig.VERSION_CODE.toLong())) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT,
                    release = release, message = if (release.isNewerThan(BuildConfig.VERSION_CODE.toLong())) "发现新版本 ${release.versionName}" else "当前已是最新版本（上次检查结果）") }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            clearPending()
            mutable.update { it.copy(phase = UpdatePhase.ERROR, message = friendly(error)) }
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        preferences.edit().putBoolean("auto", enabled).apply()
        mutable.update { it.copy(autoCheck = enabled) }
    }

    fun check(manual: Boolean = true): Job = scope.launch {
        restored.await()
        commands.withLock {
            if (state.value.busy || state.value.phase == UpdatePhase.READY) return@withLock
            val elapsed = System.currentTimeMillis() - preferences.getLong("lastAttempt", 0)
            if (!manual && (!state.value.autoCheck || elapsed in 0 until 24 * 60 * 60 * 1000L)) return@withLock
            preferences.edit().putLong("lastAttempt", System.currentTimeMillis()).apply()
            mutable.update { it.copy(phase = UpdatePhase.CHECKING, message = "正在检查 GitHub 版本…") }
            try {
                val text = http.metadata()
                val release = parse(text)
                preferences.edit().putString("latest", text).apply()
                val newer = release.isNewerThan(BuildConfig.VERSION_CODE.toLong())
                mutable.update { it.copy(phase = if (newer) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT, release = release,
                    message = if (newer) "发现新版本 ${release.versionName}" else "当前已是最新版本") }
            } catch (timeout: TimeoutCancellationException) {
                mutable.update { it.copy(phase = UpdatePhase.ERROR, message = "检查更新超时，请稍后重试") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { it.copy(phase = UpdatePhase.ERROR, message = friendly(error)) } }
        }
    }

    fun download(): Job = scope.launch {
        restored.await()
        commands.withLock {
            if (state.value.busy || state.value.phase == UpdatePhase.READY) return@withLock
            val release = state.value.release ?: return@withLock
            try {
                require(release.isNewerThan(BuildConfig.VERSION_CODE.toLong())) { "当前已是最新版本" }
                val target = partialFile(release)
                target.parentFile!!.mkdirs()
                require(context.filesDir.usableSpace >= release.size * 2 + 32 * 1024 * 1024) { "空间不足，下载和校验需要至少 ${release.size * 2 / 1024 / 1024 + 32} MiB 可用空间" }
                // Only our version/hash-specific APK can be removed here.
                target.delete()
                val request = DownloadManager.Request(Uri.parse(release.apkUrl))
                    .setTitle("Mobile Code ${release.versionName}")
                    .setDescription("软件更新，下载完成后请返回应用安装")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverRoaming(false)
                    .setDestinationUri(Uri.fromFile(target))
                val id = manager.enqueue(request)
                val saved = preferences.edit().putString("pending", Gson().toJson(release)).putLong("downloadId", id).putBoolean("ready", false).commit()
                if (!saved) { manager.remove(id); error("无法保存下载状态，请重试") }
                mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, downloadedBytes = 0, message = "正在下载更新…") }
                observe(id, release)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { it.copy(phase = UpdatePhase.ERROR, message = friendly(error)) } }
        }
    }

    private fun observe(id: Long, release: UpdateRelease) {
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val row = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                        if (!cursor.moveToFirst()) null else Triple(
                            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                    } ?: error("下载任务已被系统移除，请重新下载")
                    require(row.second <= release.size) { "下载文件大小与版本说明不匹配" }
                    when (row.first) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            mutable.update { it.copy(phase = UpdatePhase.VERIFYING, downloadedBytes = row.second, message = "正在校验文件和应用签名…") }
                            val source = partialFile(release)
                            val target = readyFile(release)
                            target.parentFile!!.mkdirs()
                            val temporary = File(target.parentFile, "${target.name}.part")
                            try {
                                source.inputStream().use { input -> temporary.outputStream().use { output ->
                                    val buffer = ByteArray(128 * 1024)
                                    var copied = 0L
                                    while (true) {
                                        ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        copied += count
                                        require(copied <= release.size) { "下载文件过大" }
                                        output.write(buffer, 0, count)
                                    }
                                    output.fd.sync()
                                } }
                                verifier.verify(temporary, release)
                                withContext(NonCancellable) {
                                    target.delete()
                                    check(temporary.renameTo(target) && target.setReadOnly()) { "无法保存已验证的更新" }
                                    check(preferences.edit().putBoolean("ready", true).remove("downloadId").commit()) { "无法保存更新状态" }
                                    manager.remove(id)
                                    mutable.update { it.copy(phase = UpdatePhase.READY, message = "校验通过，可以安装更新") }
                                }
                            } finally { temporary.delete() }
                            return@launch
                        }
                        DownloadManager.STATUS_FAILED -> error(when (row.third) {
                            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "下载空间不足，请清理后重试"
                            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载存储不可用，请重试"
                            else -> "更新下载失败，请检查网络后重试（${row.third}）"
                        })
                        else -> mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, downloadedBytes = row.second,
                            message = if (row.first == DownloadManager.STATUS_PAUSED) "等待网络恢复，系统将继续下载…" else "正在下载更新…") }
                    }
                    delay(1000)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                manager.remove(id)
                clearPending()
                mutable.update { it.copy(phase = UpdatePhase.ERROR, release = release, message = friendly(error)) }
            }
        }
    }

    fun cancelDownload(): Job = scope.launch {
        restored.await()
        commands.withLock {
            if (state.value.phase !in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) return@withLock
            downloadJob?.cancelAndJoin()
            if (state.value.phase == UpdatePhase.READY) return@withLock
            val id = preferences.getLong("downloadId", 0)
            if (id > 0) manager.remove(id)
            clearPending()
            mutable.update { it.copy(phase = UpdatePhase.AVAILABLE, downloadedBytes = 0, message = "下载已取消，可以重新下载") }
        }
    }

    suspend fun verifiedApk(): File {
        restored.await()
        return commands.withLock {
            check(state.value.phase == UpdatePhase.READY) { "请先完成更新下载" }
            val release = state.value.release ?: error("缺少更新版本信息")
            val file = readyFile(release)
            try { verifier.verify(file, release) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                clearPending()
                file.delete()
                mutable.update { it.copy(phase = UpdatePhase.ERROR, message = friendly(error)) }
                throw error
            }
            file
        }
    }

    private fun friendly(error: Exception): String = when (error) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "无法连接 GitHub，请检查网络后重试"
        is java.net.SocketTimeoutException, is TimeoutCancellationException -> "检查更新超时，请稍后重试"
        is javax.net.ssl.SSLException -> "无法验证更新服务器的 HTTPS 证书，请检查网络和系统时间"
        is IOException -> "网络或存储操作失败，请检查连接和可用空间后重试"
        is IllegalArgumentException, is IllegalStateException -> error.message ?: "更新信息无效，已阻止安装"
        else -> "无法完成更新，请稍后重试"
    }
}
