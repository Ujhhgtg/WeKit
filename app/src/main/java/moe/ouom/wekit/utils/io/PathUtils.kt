package moe.ouom.wekit.utils.io

import android.os.Environment
import moe.ouom.wekit.host.impl.hostInfo
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

object PathUtils {
    val storageDirectory: Path by lazy { Path(Environment.getExternalStorageDirectory().absolutePath) }

    val moduleDataPath: Path?
        get() {
            try {
                val directory = storageDirectory.resolve("Android").resolve("data").resolve(hostInfo.packageName).resolve("files").resolve("WeKit")
                return directory.apply {
                    createDirectories()
                }
            }
            catch (_: Exception) { return null }
    }

    val moduleCachePath: Path?
        get() { return moduleDataPath?.resolve("cache")?.apply { createDirectories() } }
}
