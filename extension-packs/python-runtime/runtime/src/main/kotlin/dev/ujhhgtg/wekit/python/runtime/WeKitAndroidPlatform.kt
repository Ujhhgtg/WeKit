package dev.ujhhgtg.wekit.python.runtime

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.chaquo.python.internal.Common
import dev.ujhhgtg.wekit.python.api.PythonRuntimeConfig
import org.json.JSONObject
import java.io.File

/** AndroidPlatform variant for an externally mounted runtime APK and preloaded native directory. */
internal class WeKitAndroidPlatform(
    private val config: PythonRuntimeConfig,
) : Python.Platform() {
    private val application = config.application
    private val assets: AssetManager = application.assets
    private val preferences: SharedPreferences =
        application.getSharedPreferences("wekit-python-assets", Context.MODE_PRIVATE)
    private val buildJson = JSONObject(
        assets.open("${Common.ASSET_DIR}/${Common.ASSET_BUILD_JSON}").use { it.readBytes().decodeToString() },
    )
    private val abi: String = Build.SUPPORTED_ABIS.firstOrNull { candidate ->
        runCatching {
            assets.open("${Common.ASSET_DIR}/${Common.assetZip(Common.ASSET_STDLIB, candidate)}").close()
        }.isSuccess
    } ?: error("Python runtime does not support ${Build.SUPPORTED_ABIS.contentToString()}")

    init {
        require(abi == "arm64-v8a") { "Unsupported Python runtime ABI: $abi" }
        AndroidPlatform.ABI = abi
    }

    override fun getPath(): String {
        val bootstrapAssets = mutableListOf(
            Common.assetZip(Common.ASSET_STDLIB, Common.ABI_COMMON),
            Common.assetZip(Common.ASSET_BOOTSTRAP),
            "${Common.ASSET_BOOTSTRAP_NATIVE}/$abi",
        )
        extractAssets(bootstrapAssets + Common.ASSET_CACERT)
        val directory = File(application.filesDir, Common.ASSET_DIR)
        return (listOf(config.sdkRoot.absolutePath) +
            bootstrapAssets.map { File(directory, it).absolutePath })
            .joinToString(File.pathSeparator)
            .also { path ->
                Log.i(
                    TAG,
                    "PYTHONPATH=$path assetApk=${config.runtimeApk.absolutePath} " +
                        "nativeDir=${config.nativeDirectory.absolutePath}",
                )
            }
    }

    override fun onStart(python: Python) {
        val appPath = arrayOf(
            Common.ASSET_APP,
            Common.ASSET_REQUIREMENTS,
            "${Common.ASSET_STDLIB}-$abi",
        )
        python.getModule("java.android").callAttr("initialize", application, buildJson, appPath)
        val importer = python.getModule("java.android.importer")
        python.builtins.callAttr("setattr", importer, "nativeLibraryDir", config.nativeDirectory.absolutePath)
    }

    private fun extractAssets(required: Collection<String>) {
        val assetsJson = buildJson.getJSONObject("assets")
        val missing = required.toMutableSet()
        val directories = mutableSetOf<String>()
        val editor = preferences.edit()
        val keys = assetsJson.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            val requiredPath = required.firstOrNull { path == it || path.startsWith("$it/") } ?: continue
            extractAsset(path, assetsJson.getString(path), editor)
            missing.remove(requiredPath)
            if (path.startsWith("$requiredPath/")) directories += requiredPath
        }
        require(missing.isEmpty()) { "Python runtime assets are missing: $missing" }
        directories.forEach { cleanExtractedDirectory(it, assetsJson) }
        editor.apply()
    }

    private fun extractAsset(path: String, hash: String, editor: SharedPreferences.Editor) {
        val output = File(application.filesDir, "${Common.ASSET_DIR}/$path")
        val preferenceKey = "asset.$path"
        if (output.isFile && preferences.getString(preferenceKey, null) == hash) return
        output.parentFile!!.mkdirs()
        val temporary = File(output.parentFile, "${output.name}.tmp")
        assets.open("${Common.ASSET_DIR}/$path").use { input ->
            temporary.outputStream().use(input::copyTo)
        }
        if (output.exists()) output.delete()
        require(temporary.renameTo(output)) { "Cannot extract Python runtime asset $path" }
        editor.putString(preferenceKey, hash)
    }

    private fun cleanExtractedDirectory(path: String, assetsJson: JSONObject) {
        val directory = File(application.filesDir, "${Common.ASSET_DIR}/$path")
        directory.listFiles().orEmpty().forEach { child ->
            val childPath = "$path/${child.name}"
            if (child.isDirectory) cleanExtractedDirectory(childPath, assetsJson)
            else if (!assetsJson.has(childPath)) child.delete()
        }
    }

    private companion object {
        const val TAG = "WeKitPythonRuntime"
    }
}
