package dev.mobilecode.ide

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobilecode.ide.update.UpdatePhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit network acceptance; run separately from the offline IDE regression. */
@RunWith(AndroidJUnit4::class)
class PublishedUpdateTest {
    @Test fun checksAndDownloadsTheActualGithubRelease() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MobileCodeApp>()
        withTimeout(75_000) { app.updates.check().join() }
        val checked = app.updates.state.value
        check(checked.phase != UpdatePhase.ERROR) { checked.message }
        val release = checked.release ?: error("GitHub did not provide release metadata")
        assertTrue(release.versionCode >= BuildConfig.VERSION_CODE)
        Log.i("MobileCodeUpdateTest", "installed=${BuildConfig.VERSION_CODE} offered=${release.versionCode} sha256=${release.sha256}")
        if (!release.isNewerThan(BuildConfig.VERSION_CODE.toLong())) {
            assertEquals(UpdatePhase.CURRENT, checked.phase)
            return@runBlocking
        }
        app.updates.download().join()
        if (InstrumentationRegistry.getArguments().getString("mcUpdateProbe") == "start") {
            assertEquals(UpdatePhase.DOWNLOADING, app.updates.state.value.phase)
            Log.i("MobileCodeUpdateTest", "persistent system download enqueued; host may restart the application")
            return@runBlocking
        }
        val complete = withTimeout(20 * 60 * 1000L) { app.updates.state.first { it.phase == UpdatePhase.READY || it.phase == UpdatePhase.ERROR } }
        check(complete.phase == UpdatePhase.READY) { complete.message }
        val apk = app.updates.verifiedApk()
        assertEquals(release.size, apk.length())
        Log.i("MobileCodeUpdateTest", "verified real GitHub APK: version=${release.versionName} bytes=${apk.length()} path=${apk.absolutePath}")
    }
}
