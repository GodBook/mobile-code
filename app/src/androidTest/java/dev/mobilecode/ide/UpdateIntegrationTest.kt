package dev.mobilecode.ide

import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mobilecode.ide.update.ApkVerifier
import dev.mobilecode.ide.update.UpdateRelease
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<MobileCodeApp>()

    @Test fun updaterProviderCannotExposeWorkspaceFiles() {
        val info = app.packageManager.resolveContentProvider("${app.packageName}.updates", PackageManager.GET_META_DATA)!!
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        val outside = File(app.filesDir, "projects/do-not-expose.py")
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(app, "${app.packageName}.updates", outside) }
        val inside = File(app.filesDir, "updates/test.apk")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", inside)
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.updates", uri.authority)
    }

    @Test fun rejectsCorruptDownloadsAndDowngradesBeforeInstaller() = runBlocking {
        val file = File(app.cacheDir, "corrupt-update-test.apk")
        file.writeText("this is a corrupt APK")
        try {
            val release = UpdateRelease(1, app.packageName, BuildConfig.VERSION_CODE.toLong() + 1, "0.3.0", 29,
                BuildConfig.RUNTIME_ABI, "https://github.com/${BuildConfig.UPDATE_REPOSITORY}", file.length(), "0".repeat(64), "test")
            try { ApkVerifier(app).verify(file, release); fail("Corrupt APK accepted") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("校验失败")) }
            try { ApkVerifier(app).verify(file, release.copy(versionCode = BuildConfig.VERSION_CODE.toLong())); fail("Downgrade accepted") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("不能降级")) }
        } finally { file.delete() }
    }
}
