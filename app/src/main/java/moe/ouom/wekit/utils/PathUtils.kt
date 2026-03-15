package moe.ouom.wekit.utils

import android.os.Environment
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

object PathUtils {

    val storageDirectory: Path by lazy { Path(Environment.getExternalStorageDirectory().absolutePath) }

    val moduleDataPath: Path?
        get() {
            try {
                val directory =
                    storageDirectory / "Android" / "data" / HostInfo.packageName / "files" / "WeKit"
                directory.createDirectories()
                return directory
            } catch (_: Exception) {
                return null
            }
        }

    val moduleCachePath: Path?
        get() {
            return moduleDataPath?.resolve("cache")?.apply { createDirectories() }
        }
}
