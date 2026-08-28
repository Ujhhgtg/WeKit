package dev.ujhhgtg.wekit.agent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class WeAgentDatabaseRelocatorTest {
    @TempDir
    lateinit var root: Path

    private fun sourceDatabase(payload: String): File =
        root.resolve("external/weagent.db").toFile().apply {
            parentFile!!.mkdirs()
            writeText(payload)
        }

    @Test
    fun `existing private database always wins`() {
        val source = root.resolve("external/weagent.db").toFile().apply {
            parentFile!!.mkdirs(); writeText("old")
        }
        val destination = root.resolve("private/weagent.db").toFile().apply {
            parentFile!!.mkdirs(); writeText("new")
        }
        val prepared = WeAgentDatabaseRelocator(source, destination) {}.prepare()
        assertEquals(destination, prepared.file)
        assertFalse(prepared.migratedNow)
        assertEquals("new", destination.readText())
    }

    @Test
    fun `commit keeps private copy and deletes external sidecars`() {
        val source = sourceDatabase("payload")
        File(source.path + "-journal").writeText("")
        val destination = root.resolve("private/weagent.db").toFile()
        val relocator = WeAgentDatabaseRelocator(source, destination) {}
        val prepared = relocator.prepare()
        assertTrue(prepared.migratedNow)
        assertEquals("payload", destination.readText())
        relocator.commit(prepared)
        assertFalse(source.exists())
        assertFalse(File(source.path + "-journal").exists())
    }

    @Test
    fun `rollback removes new copy and preserves source`() {
        val source = sourceDatabase("payload")
        val destination = root.resolve("private/weagent.db").toFile()
        val relocator = WeAgentDatabaseRelocator(source, destination) {}
        val prepared = relocator.prepare()
        relocator.rollback(prepared)
        assertTrue(source.exists())
        assertFalse(destination.exists())
    }

    @Test
    fun `fresh install uses private destination without recovery`() {
        val source = root.resolve("external/weagent.db").toFile()
        val destination = root.resolve("private/nested/deep/weagent.db").toFile()
        var recoveryInvoked = false
        val prepared = WeAgentDatabaseRelocator(source, destination) { recoveryInvoked = true }.prepare()
        assertEquals(destination, prepared.file)
        assertFalse(prepared.externalFallback)
        assertFalse(prepared.migratedNow)
        assertFalse(recoveryInvoked)
        assertTrue(prepared.file.parentFile!!.isDirectory)
    }

    @Test
    fun `recovery failure returns external fallback without data loss`() {
        val source = sourceDatabase("payload")
        val destination = root.resolve("private/weagent.db").toFile()
        val prepared = WeAgentDatabaseRelocator(source, destination) { error("recover failed") }.prepare()
        assertTrue(prepared.externalFallback)
        assertEquals(source, prepared.file)
        assertTrue(source.exists())
        assertFalse(destination.exists())
    }
}
