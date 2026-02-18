package moe.ouom.wekit.utils.common

import moe.ouom.wekit.utils.log.WeLogger
import java.io.File
import java.security.MessageDigest

object DexEnvUtils {
    private const val TAG = "DexEnvUtils"

    fun collectDexPaths(classLoader: ClassLoader): List<String> {
        val paths = mutableListOf<String>()
        try {
            val pathList = readField(classLoader, "pathList") ?: return emptyList()
            val dexElements = readField(pathList, "dexElements") as? Array<*> ?: return emptyList()
            dexElements.forEach { element ->
                if (element == null) return@forEach
                val elementPath = extractElementPath(element)
                if (!elementPath.isNullOrBlank()) {
                    paths.add(elementPath)
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to collect dex paths", e)
        }
        return paths.distinct()
    }

    fun buildDexSetHash(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        return try {
            val sorted = paths.sorted()
            val joined = sorted.joinToString("|")
            val digest = MessageDigest.getInstance("SHA-1").digest(joined.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to build dex set hash", e)
            ""
        }
    }

    private fun extractElementPath(element: Any): String {
        val path = (readField(element, "path") as? File)?.path
        if (!path.isNullOrBlank()) return path
        val zip = (readField(element, "zip") as? File)?.path
        if (!zip.isNullOrBlank()) return zip
        val dexFile = readField(element, "dexFile")
        val dexName = dexFile?.let { getDexFileName(it) }
        if (!dexName.isNullOrBlank()) return dexName
        return element.toString()
    }

    private fun getDexFileName(dexFile: Any): String? {
        return try {
            dexFile.javaClass.getMethod("getName").invoke(dexFile) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun readField(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }
}
