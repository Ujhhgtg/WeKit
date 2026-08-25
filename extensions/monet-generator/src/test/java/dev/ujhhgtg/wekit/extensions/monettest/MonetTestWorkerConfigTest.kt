package dev.ujhhgtg.wekit.extensions.monettest

import java.util.Properties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MonetTestWorkerConfigTest {

    @Test
    fun `worker config parses every forwarded property`() {
        val config = MonetTestWorkerConfig.fromSystemProperties(properties())

        assertEquals(MonetTestInputKind.APKS, config.inputKind)
        assertEquals("/tmp/wechat.apks", config.inputPath.toString())
        assertEquals("/tmp/libdexkit.so", config.nativeLibrary.toString())
        assertEquals("/tmp/wechat.json", config.report.toString())
        assertEquals("2.2.0", config.dexKitVersion)
        assertEquals("ffa6c51", config.dexKitRevision)
        assertEquals(3084, config.versionCode)
        assertEquals("8.0.72", config.versionName)
        assertEquals(true, config.isGooglePlay)
    }

    @Test
    fun `worker config rejects unknown kind and malformed metadata`() {
        val unknownKind = properties().apply {
            setProperty("wekit.monetTest.inputKind", "bundle")
        }
        val invalidVersion = properties().apply {
            setProperty("wekit.monetTest.versionCode", "not-a-number")
        }
        val invalidChannel = properties().apply {
            setProperty("wekit.monetTest.isGooglePlay", "maybe")
        }

        assertThrows(IllegalStateException::class.java) {
            MonetTestWorkerConfig.fromSystemProperties(unknownKind)
        }
        assertThrows(IllegalStateException::class.java) {
            MonetTestWorkerConfig.fromSystemProperties(invalidVersion)
        }
        assertThrows(IllegalStateException::class.java) {
            MonetTestWorkerConfig.fromSystemProperties(invalidChannel)
        }
    }

    private fun properties() = Properties().apply {
        setProperty("wekit.monetTest.inputKind", "APKS")
        setProperty("wekit.monetTest.inputPath", "/tmp/wechat.apks")
        setProperty("wekit.monetTest.nativeLibrary", "/tmp/libdexkit.so")
        setProperty("wekit.monetTest.report", "/tmp/wechat.json")
        setProperty("wekit.monetTest.dexKitVersion", "2.2.0")
        setProperty("wekit.monetTest.dexKitRevision", "ffa6c51")
        setProperty("wekit.monetTest.versionCode", "3084")
        setProperty("wekit.monetTest.versionName", "8.0.72")
        setProperty("wekit.monetTest.isGooglePlay", "true")
    }
}
