package dev.ujhhgtg.wekit.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class MonetInstallPathsTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `content version must be exactly twelve lowercase hex characters`() {
        val invalid = listOf(
            "0123456789a",
            "0123456789abc",
            "0123456789AB",
            "0123456789ag",
            "20260822-0123456789ab",
            "../0123456789ab",
        )

        invalid.forEach { version ->
            assertThrows(
                IllegalArgumentException::class.java,
                { MonetInstallPaths.resolve(temp.resolve("monet"), version) },
                version,
            )
        }
    }

    @Test
    fun `version is validated before base path canonicalization`() {
        val guardedBase = object : File(temp, "must-not-be-resolved") {
            override fun getCanonicalFile(): File = error("base path was resolved first")
        }

        assertThrows(IllegalArgumentException::class.java) {
            MonetInstallPaths.resolve(guardedBase, "INVALID")
        }
    }

    @Test
    fun `valid install paths are canonical direct children of the base`() {
        val base = temp.resolve("monet")

        val paths = MonetInstallPaths.resolve(base, "0123456789ab")

        assertEquals(base.canonicalFile, paths.baseDir)
        assertEquals(base.resolve("0123456789ab").canonicalFile, paths.destination)
        assertEquals(base.resolve(".0123456789ab-installing").canonicalFile, paths.staging)
        assertEquals(base.resolve(".0123456789ab-previous").canonicalFile, paths.previous)
    }

    @Test
    fun `destination staging and previous paths cannot escape through symlinks`() {
        val base = temp.resolve("monet").also(File::mkdirs)
        val outside = temp.resolve("outside").also(File::mkdirs)
        val version = "0123456789ab"
        val names = listOf(
            version,
            ".$version-installing",
            ".$version-previous",
        )

        names.forEach { name ->
            val link = base.resolve(name)
            Files.createSymbolicLink(link.toPath(), outside.toPath())

            assertThrows(
                IllegalArgumentException::class.java,
                { MonetInstallPaths.resolve(base, version) },
                name,
            )

            Files.delete(link.toPath())
        }
    }
}
