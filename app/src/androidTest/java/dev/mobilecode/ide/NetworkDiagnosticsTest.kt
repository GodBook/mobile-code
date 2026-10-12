package dev.mobilecode.ide

import android.util.Log
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Explicit network diagnostics, separate from offline acceptance. No credentials or project data. */
@RunWith(AndroidJUnit4::class)
class NetworkDiagnosticsTest {
    @Test fun inspectAndroidHttpsRoutes() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val failures = mutableListOf<String>()
            for (initial in listOf(
                "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/latest/download/update.json",
                "https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest",
            )) {
                var url = URL(initial)
                try {
                    for (hop in 0..5) {
                        val addresses = InetAddress.getAllByName(url.host).joinToString { it.hostAddress.orEmpty() }
                        Log.i("MobileCodeNet", "host=${url.host} addresses=$addresses")
                        val connection = url.openConnection() as HttpURLConnection
                        try {
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = 4000
                            connection.readTimeout = 6000
                            connection.setRequestProperty("User-Agent", "MobileCode/${BuildConfig.VERSION_NAME}")
                            connection.setRequestProperty("Accept", "application/json")
                            val code = connection.responseCode
                            Log.i("MobileCodeNet", "host=${url.host} response=$code proxy=${connection.usingProxy()}")
                            if (code in listOf(301, 302, 303, 307, 308)) url = URL(url, connection.getHeaderField("Location"))
                            else {
                                check(code == 200) { "HTTP $code" }
                                connection.inputStream.use { check(it.read() >= 0) }
                                break
                            }
                        } finally { connection.disconnect() }
                    }
                } catch (error: Exception) {
                    val failure = "${url.host}: ${error.javaClass.simpleName}: ${error.message}"
                    Log.w("MobileCodeNet", failure)
                    failures += failure
                }
            }
            check(failures.isEmpty()) { failures.joinToString("; ") }
        }
    }
}
