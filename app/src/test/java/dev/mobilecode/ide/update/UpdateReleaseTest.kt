package dev.mobilecode.ide.update

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private fun metadata() = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("packageName", "dev.mobilecode.ide")
        addProperty("versionCode", 3)
        addProperty("versionName", "0.2.0")
        addProperty("minSdk", 29)
        addProperty("abi", "arm64-v8a")
        addProperty("apkUrl", "https://github.com/GodBook/mobile-code/releases/download/v0.2.0/MobileCode-0.2.0-arm64-v8a.apk")
        addProperty("size", 430_000_000L)
        addProperty("sha256", "a".repeat(64))
        addProperty("releaseNotes", "新增在线更新，保留项目。")
    }
    private fun parse(value: JsonObject) = UpdateRelease.parse(value.toString(), "GodBook/mobile-code", "dev.mobilecode.ide", "arm64-v8a", 35)

    @Test fun acceptsCompatibleReleaseAndComparesAndroidVersionCodes() {
        val release = parse(metadata())
        assertTrue(release.isNewerThan(2))
        assertFalse(release.isNewerThan(3))
        assertFalse(release.isNewerThan(4))
        assertEquals("新增在线更新，保留项目。", release.releaseNotes)
    }

    @Test fun rejectsOtherRepositoriesAndUnsafeDownloadUrls() {
        for (url in listOf(
            "http://github.com/GodBook/mobile-code/releases/download/v0.2.0/MobileCode-0.2.0-arm64-v8a.apk",
            "https://github.com/attacker/mobile-code/releases/download/v0.2.0/MobileCode-0.2.0-arm64-v8a.apk",
            "https://github.com.evil.test/GodBook/mobile-code/releases/download/v0.2.0/MobileCode-0.2.0-arm64-v8a.apk",
            "https://github.com@evil.test/GodBook/mobile-code/releases/download/v0.2.0/MobileCode-0.2.0-arm64-v8a.apk",
            "file:///storage/emulated/0/Download/update.apk",
            "https://github.com/GodBook/mobile-code/releases/download/v0.2.0/MobileCode-0.2.0-arm64-v8a.apk?redirect=evil",
        )) assertThrows(IllegalArgumentException::class.java) { parse(metadata().apply { addProperty("apkUrl", url) }) }
    }

    @Test fun rejectsIncompatiblePackageArchitectureAndSdk() {
        for ((key, value) in listOf("packageName" to "other.app", "abi" to "x86_64")) {
            assertThrows(IllegalArgumentException::class.java) { parse(metadata().apply { addProperty(key, value) }) }
        }
        assertThrows(IllegalArgumentException::class.java) { parse(metadata().apply { addProperty("minSdk", 36) }) }
        assertThrows(IllegalArgumentException::class.java) { parse(metadata().apply { addProperty("schemaVersion", 2) }) }
    }

    @Test fun rejectsTruncatedAndOversizedPayloads() {
        assertThrows(RuntimeException::class.java) { UpdateRelease.parse("{", "GodBook/mobile-code", "dev.mobilecode.ide", "arm64-v8a", 35) }
        assertThrows(IllegalArgumentException::class.java) { parse(metadata().apply { remove("sha256") }) }
        assertThrows(IllegalArgumentException::class.java) { parse(metadata().apply { addProperty("releaseNotes", "中".repeat(30_000)) }) }
    }

    @Test fun rejectsInvalidHashesAndSizes() {
        assertThrows(IllegalArgumentException::class.java) { parse(metadata().apply { addProperty("sha256", "abc") }) }
        for (bytes in listOf(0, -1, UpdateRelease.MAX_APK_BYTES + 1)) {
            assertThrows(RuntimeException::class.java) { parse(metadata().apply { addProperty("size", bytes) }) }
        }
    }

    @Test fun rejectsFractionalOverflowAndStringVersionCodes() {
        assertThrows(RuntimeException::class.java) { parse(metadata().apply { addProperty("versionCode", 2.5) }) }
        assertThrows(RuntimeException::class.java) { parse(metadata().apply { addProperty("versionCode", "3") }) }
        val huge = metadata().toString().replace("\"versionCode\":3", "\"versionCode\":18446744073709551619")
        assertThrows(RuntimeException::class.java) { UpdateRelease.parse(huge, "GodBook/mobile-code", "dev.mobilecode.ide", "arm64-v8a", 35) }
    }
}
