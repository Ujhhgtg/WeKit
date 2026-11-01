package dev.ujhhgtg.wekit.bootstrap.utils.fs

import android.system.Os
import android.system.OsConstants
import java.io.File

private val PERMISSION_MASK =
    OsConstants.S_IRWXU or OsConstants.S_IRWXG or OsConstants.S_IRWXO

/** Creates a private directory without temporarily clearing permissions on an existing one. */
fun File.ensurePrivateDirectory() {
    check(isDirectory || mkdirs()) { "cannot prepare private directory: $this" }
    Os.chmod(absolutePath, OsConstants.S_IRWXU)
    check(Os.stat(absolutePath).st_mode and PERMISSION_MASK == OsConstants.S_IRWXU) {
        "cannot restrict private directory: $this"
    }
}

fun File.makeOwnerReadOnly() {
    Os.chmod(absolutePath, OsConstants.S_IRUSR)
    check(Os.stat(absolutePath).st_mode and PERMISSION_MASK == OsConstants.S_IRUSR) {
        "cannot protect private file: $this"
    }
}

/** Owner-only read/write, for a private file that is atomically replaced by its publisher. */
fun File.makeOwnerPrivate() {
    val mode = OsConstants.S_IRUSR or OsConstants.S_IWUSR
    Os.chmod(absolutePath, mode)
    check(Os.stat(absolutePath).st_mode and PERMISSION_MASK == mode) {
        "cannot protect private file: $this"
    }
}
