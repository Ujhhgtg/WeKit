package dev.ujhhgtg.wekit.python.runtime

import dev.ujhhgtg.wekit.python.api.PythonRuntimeConfig
import dev.ujhhgtg.wekit.python.api.PythonRuntimeStartupException
import org.json.JSONObject
import java.io.File

internal object RuntimeNativeLoader {
    private val loadedPaths = mutableSetOf<String>()

    @Synchronized
    fun load(config: PythonRuntimeConfig) {
        val manifest = config.application.assets.open("runtime-manifest.json").use { input ->
            JSONObject(input.readBytes().decodeToString())
        }
        val libraries = manifest.getJSONArray("nativeLibraries")
        for (index in 0 until libraries.length()) {
            val name = libraries.getString(index)
            try {
                val library = File(config.nativeDirectory, name).canonicalFile
                require(library.parentFile == config.nativeDirectory.canonicalFile && library.isFile) {
                    "Python runtime native library is missing: $library"
                }
                if (loadedPaths.add(library.absolutePath)) {
                    System.load(library.absolutePath)
                }
            } catch (error: Throwable) {
                throw PythonRuntimeStartupException(
                    phase = "NATIVE",
                    library = name,
                    message = "Failed to load Python runtime native library $name",
                    cause = error,
                )
            }
        }
    }
}
