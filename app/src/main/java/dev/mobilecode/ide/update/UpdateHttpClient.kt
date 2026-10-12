package dev.mobilecode.ide.update

import dev.mobilecode.ide.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateHttpClient {
    suspend fun metadata(): String = withTimeout(60_000) {
        withContext(Dispatchers.IO) {
            var url = URL("https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/latest/download/update.json")
            repeat(6) {
                ensureActive()
                require(url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443) &&
                    url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "更新服务器重定向无效" }
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.useCaches = false
                    connection.setRequestProperty("User-Agent", "MobileCode/${BuildConfig.VERSION_NAME}")
                    connection.setRequestProperty("Accept", "application/json")
                    when (val code = connection.responseCode) {
                        301, 302, 303, 307, 308 -> {
                            val location = connection.getHeaderField("Location") ?: error("更新服务器没有提供下载地址")
                            url = URL(url, location)
                        }
                        200 -> {
                            require(connection.contentLengthLong <= UpdateRelease.MAX_METADATA_BYTES) { "更新信息过大" }
                            val output = ByteArrayOutputStream()
                            connection.inputStream.use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(output.size() + count <= UpdateRelease.MAX_METADATA_BYTES) { "更新信息过大" }
                                    output.write(buffer, 0, count)
                                }
                            }
                            return@withContext output.toString(Charsets.UTF_8.name())
                        }
                        404 -> error("GitHub 上尚未发布可用更新")
                        403, 429 -> error("更新服务器暂时限制访问，请稍后重试")
                        else -> error("更新服务器暂时不可用（HTTP $code）")
                    }
                } finally {
                    connection.disconnect()
                }
            }
            error("更新服务器重定向次数过多")
        }
    }
}
