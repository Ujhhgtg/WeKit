@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.utils.fs

import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream

@Suppress("NOTHING_TO_INLINE")
inline fun Path.createDirsSafe(): Path {
    runCatching { createDirectories() }
    return this
}

inline val String.asPath get() = Path(this)

inline val File.asPath: Path get() = toPath()

inline val Path.asAndroidUri: Uri get() = Uri.fromFile(toFile())

/** Replaces [target] atomically when supported, falling back only when atomic moves are unavailable. */
fun Path.moveReplacing(target: Path): Path = try {
    moveTo(target, REPLACE_EXISTING, ATOMIC_MOVE)
} catch (_: AtomicMoveNotSupportedException) {
    moveTo(target, REPLACE_EXISTING)
}

/** Copies [input] into this path and closes the output stream opened for the path. */
fun Path.copyFrom(input: InputStream, vararg options: OpenOption): Long =
    outputStream(*options).use(input::copyTo)

/** Copies this path into [output] and closes the input stream opened for the path. */
fun Path.copyTo(output: OutputStream, vararg options: OpenOption): Long =
    inputStream(*options).use { it.copyTo(output) }
