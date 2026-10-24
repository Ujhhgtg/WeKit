package dev.ujhhgtg.wekit.activity.settings

import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BackupManagedDataReplacementTest {
    @TempDir lateinit var temp: Path

    private val root get() = temp.resolve("live").toFile()
    private val staging get() = temp.resolve("staging").toFile()
    private val oldRoot get() = temp.resolve("old").toFile()
    private val database get() = File(root, "wekit.db")
    private val marker get() = File(root, BackupManagedDataReplacement.MARKER_NAME)

    private fun write(file: File, text: String): File = file.apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun prepare() {
        write(database, "old database")
        write(File(root, "wekit.db-wal"), "old wal")
        write(File(root, "wekit.db-shm"), "old shm")
        write(marker, "old marker")
        write(File(staging, "wekit.sqlite"), "new database")
        write(File(root, "first/item"), "old first")
        write(File(root, "second/item"), "old second")
        write(File(root, "untouched/item"), "untouched")
        write(File(staging, "first/item"), "new first")
    }

    @Test
    fun `successful replacement renews provenance marker and retains originals until caller cleans up`() {
        prepare()
        write(File(staging, BackupManagedDataReplacement.MARKER_NAME), "archive marker must not be used")
        BackupManagedDataReplacement.replace(root, staging, oldRoot, database,
            managedDirectories = listOf("first", "second"), managedFiles = emptyList())
        assertEquals("new database", database.readText())
        assertEquals("old database", File(oldRoot, "wekit.db").readText())
        assertEquals("old wal", File(oldRoot, "wekit.db-wal").readText())
        assertFalse(File(root, "wekit.db-wal").exists())
        assertEquals("new first", File(root, "first/item").readText())
        assertTrue(File(root, "second").isDirectory)
        assertTrue(File(root, "second").list()!!.isEmpty())
        assertEquals("untouched", File(root, "untouched/item").readText())
        assertNotEquals("old marker", marker.readText())
        assertNotEquals("archive marker must not be used", marker.readText())
        assertEquals("old marker", File(oldRoot, BackupManagedDataReplacement.MARKER_NAME).readText())
    }

    @Test
    fun `first database move failure does not delete paths which were never processed`() {
        prepare()
        val refusingDatabase = object : File(database.path) {
            override fun renameTo(destination: File): Boolean = false
        }
        assertThrows(IllegalStateException::class.java) {
            BackupManagedDataReplacement.replace(root, staging, oldRoot, refusingDatabase,
                listOf("first", "second", "untouched"), emptyList())
        }
        assertOriginalData()
    }

    @Test
    fun `midway directory failure restores completed moves database sidecars and old marker`() {
        prepare()
        write(File(staging, "second"), "wrong type for directory")
        assertThrows(IllegalStateException::class.java) {
            BackupManagedDataReplacement.replace(root, staging, oldRoot, database,
                listOf("first", "second", "untouched"), emptyList())
        }
        assertOriginalData()
    }

    @Test
    fun `late file failure restores moved files and removes only directories created by this import`() {
        prepare()
        write(File(root, "first.json"), "old first file")
        write(File(staging, "first.json"), "new first file")
        write(File(root, "later.json"), "old later file")
        File(staging, "later.json").mkdirs()
        write(File(root, "never.json"), "untouched file")
        assertThrows(IllegalStateException::class.java) {
            BackupManagedDataReplacement.replace(root, staging, oldRoot, database,
                listOf("first", "new-empty"), listOf("first.json", "later.json", "never.json"))
        }
        assertOriginalData()
        assertEquals("old first file", File(root, "first.json").readText())
        assertEquals("old later file", File(root, "later.json").readText())
        assertEquals("untouched file", File(root, "never.json").readText())
        assertFalse(File(root, "new-empty").exists())
    }

    @Test
    fun `failed rollback reports recovery location preserves old database and continues other undo entries`() {
        prepare()
        write(File(staging, "second"), "wrong type for directory")
        val undeletableDatabase = object : File(database.path) {
            override fun delete(): Boolean = false
        }
        val failure = assertThrows(BackupReplacementRollbackException::class.java) {
            BackupManagedDataReplacement.replace(root, staging, oldRoot, undeletableDatabase,
                listOf("first", "second", "untouched"), emptyList())
        }
        assertEquals(oldRoot, failure.backupRoot)
        assertTrue(failure.suppressed.isNotEmpty())
        assertTrue(failure.originalFailure is IllegalStateException)
        assertEquals("old database", File(oldRoot, "wekit.db").readText())
        assertEquals("new database", database.readText())
        assertEquals("old marker", marker.readText())
        assertEquals("old first", File(root, "first/item").readText())
        assertEquals("old second", File(root, "second/item").readText())
        assertEquals("untouched", File(root, "untouched/item").readText())
    }

    private fun assertOriginalData() {
        assertEquals("old database", database.readText())
        assertEquals("old wal", File(root, "wekit.db-wal").readText())
        assertEquals("old shm", File(root, "wekit.db-shm").readText())
        assertEquals("old marker", marker.readText())
        assertEquals("old first", File(root, "first/item").readText())
        assertEquals("old second", File(root, "second/item").readText())
        assertEquals("untouched", File(root, "untouched/item").readText())
    }
}
