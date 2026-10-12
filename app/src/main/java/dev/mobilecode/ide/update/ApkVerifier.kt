package dev.mobilecode.ide.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.mobilecode.ide.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class ApkVerifier(private val context: Context) {
    suspend fun verify(file: File, release: UpdateRelease) = withContext(Dispatchers.IO) {
        require(release.isNewerThan(BuildConfig.VERSION_CODE.toLong())) { "此版本已安装，不能降级更新" }
        require(file.isFile && file.length() == release.size) { "更新文件不完整，请重新下载" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "更新文件校验失败，请重新下载" }
        ZipFile(file).use { archive ->
            val architectures = archive.entries().asSequence().map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet()
            require(architectures == setOf(release.abi)) { "APK 处理器架构与当前版本不同" }
        }
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("下载文件不是有效的 APK")
        require(archive.packageName == context.packageName && archive.packageName == release.packageName) { "APK 包名不匹配" }
        require(archive.longVersionCode == release.versionCode && archive.versionName == release.versionName) { "APK 版本与更新说明不匹配" }
        require(archive.applicationInfo?.minSdkVersion == release.minSdk && release.minSdk <= Build.VERSION.SDK_INT) { "APK 不支持当前安卓系统" }
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun certificates(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners
            ?.map { it.toCharsString() }?.toSet().orEmpty()
        val expected = certificates(installed)
        require(expected.isNotEmpty() && certificates(archive) == expected) { "APK 签名与已安装版本不同，已阻止安装" }
    }
}
