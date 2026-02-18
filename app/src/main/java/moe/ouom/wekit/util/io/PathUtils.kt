package moe.ouom.wekit.util.io

import android.app.AndroidAppHelper
import java.nio.file.Path
import kotlin.io.path.createDirectories

object PathUtils {
    val moduleDataPath: Path by lazy {
        val directory = AndroidAppHelper.currentApplication().getExternalFilesDir("WeKit")!!.absolutePath
        Path.of(directory).apply {
            createDirectories()
        }
    }

    val moduleCachePath: Path = moduleDataPath.resolve("cache")
}
