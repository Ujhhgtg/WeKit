package dev.ujhhgtg.wekit.extensions

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MonetDexStagerTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `staged DEX is verified and read-only`() {
        val source = temp.resolve("source.dex").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val target = temp.resolve("code-cache/classes.dex")

        stageReadOnlyMonetDex(source, target, PackFs.sha256(source))

        assertArrayEquals(source.readBytes(), target.readBytes())
        assertFalse(target.canWrite())
    }
}
