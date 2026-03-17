package moe.ouom.wekit.utils

import android.os.Environment
import moe.ouom.wekit.BuildConfig
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

object ModulePaths {

    val sysStorage: Path by lazy {
        Path(Environment.getExternalStorageDirectory().absolutePath) }

    val data: Path?
        get() {
            try {
                val directory =
                    sysStorage / "Android" / "data" / HostInfo.packageName / "files" / BuildConfig.TAG
                directory.createDirectories()
                return directory
            } catch (_: Exception) {
                return null
            }
        }

    val cache: Path
        get() {
            val directory =
                sysStorage / "Android" / "data" / HostInfo.packageName / "cache" / BuildConfig.TAG
            directory.createDirectories()
            return directory
        }
}
