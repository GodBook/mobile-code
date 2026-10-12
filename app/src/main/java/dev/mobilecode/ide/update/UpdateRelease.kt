package dev.mobilecode.ide.update

import com.google.gson.JsonParser
import java.net.URI

data class UpdateRelease(
    val schemaVersion: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val abi: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
    val releaseNotes: String,
) {
    fun isNewerThan(installedVersion: Long) = versionCode > installedVersion

    companion object {
        const val MAX_METADATA_BYTES = 64 * 1024
        const val MAX_APK_BYTES = 900L * 1024 * 1024

        fun parse(text: String, repository: String, expectedPackage: String, expectedAbi: String, sdk: Int): UpdateRelease {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES) { "更新信息过大" }
            val root = JsonParser.parseString(text).asJsonObject
            fun string(key: String): String {
                val value = root.get(key)
                require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "更新信息缺少 $key" }
                return value.asString
            }
            fun number(key: String): Long {
                val value = root.get(key)
                require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.toString().matches(Regex("[0-9]+"))) { "更新信息中的 $key 无效" }
                return value.toString().toLongOrNull() ?: error("更新信息中的 $key 超出范围")
            }
            require(number("schemaVersion") == 1L) { "不支持此更新格式" }
            val name = string("versionName")
            val code = number("versionCode")
            val minimum = number("minSdk")
            val bytes = number("size")
            val digest = string("sha256").lowercase()
            val url = string("apkUrl")
            val notes = string("releaseNotes")
            require(name.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")) && name.length <= 64) { "更新版本名称无效" }
            require(code in 1..2_100_000_000L) { "更新版本号无效" }
            require(minimum in 29..sdk.toLong()) { "新版本不支持当前安卓系统" }
            require(string("packageName") == expectedPackage) { "更新包名不匹配" }
            require(string("abi") == expectedAbi) { "更新不支持当前处理器架构" }
            require(bytes in 1..MAX_APK_BYTES) { "更新文件大小无效" }
            require(digest.matches(Regex("[a-f0-9]{64}"))) { "更新校验值无效" }
            val expected = "https://github.com/$repository/releases/download/v$name/MobileCode-$name-$expectedAbi.apk"
            require(url == expected && URI(url).userInfo == null) { "更新文件必须来自本项目的 GitHub Release" }
            require(notes.length <= 16_384) { "版本说明过长" }
            return UpdateRelease(1, expectedPackage, code, name, minimum.toInt(), expectedAbi, url, bytes, digest, notes)
        }
    }
}
