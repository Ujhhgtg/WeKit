package dev.ujhhgtg.wekit.dextest

import java.util.Properties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DexTestWorkerConfigTest {
    @Test
    fun parsesAllWorkerProperties() {
        val config = DexTestWorkerConfig.fromSystemProperties(properties())
        assertEquals(3040L, config.versionCode)
        assertEquals("8.0.69", config.versionName)
        assertFalse(config.isGooglePlay)
    }

    @Test
    fun rejectsInvalidBooleanAndNumber() {
        val booleanProperties = properties().apply { setProperty("wekit.dexTest.isGooglePlay", "maybe") }
        assertThrows(IllegalStateException::class.java) {
            DexTestWorkerConfig.fromSystemProperties(booleanProperties)
        }
        val numberProperties = properties().apply { setProperty("wekit.dexTest.versionCode", "not-a-number") }
        assertThrows(IllegalStateException::class.java) {
            DexTestWorkerConfig.fromSystemProperties(numberProperties)
        }
    }

    private fun properties() = Properties().apply {
        setProperty("wekit.dexTest.apk", "/tmp/wechat.apk")
        setProperty("wekit.dexTest.nativeLibrary", "/tmp/libdexkit.so")
        setProperty("wekit.dexTest.report", "/tmp/report.json")
        setProperty("wekit.dexTest.dexKitVersion", "2.2.0")
        setProperty("wekit.dexTest.dexKitRevision", "revision")
        setProperty("wekit.dexTest.versionCode", "3040")
        setProperty("wekit.dexTest.versionName", "8.0.69")
        setProperty("wekit.dexTest.buildTag", "Android_Wechat_RELEASE")
        setProperty("wekit.dexTest.isGooglePlay", "false")
    }
}
