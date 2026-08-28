package dev.ujhhgtg.wekit.features.items.scripting_python.plugin

import android.os.SystemClock
import dev.ujhhgtg.wekit.extensions.PythonRuntimePack
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcesses
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class PythonCrashMarker(
    val pluginId: String,
    val phase: String,
    val processName: String,
    val runtimeVersion: String?,
    val token: Long,
)

object PythonCrashGuard {
    private val lock = Any()
    private val json = Json
    private val activeMarkers = LinkedHashMap<Long, PythonCrashMarker>()
    private val tokens = AtomicLong()
    private val directory: File get() = File(HostInfo.application.filesDir, "wekit-python")
    private val markerFile: File get() = File(directory, "execution-marker.json")

    fun begin(pluginId: String, phase: String): Long = synchronized(lock) {
        val now = SystemClock.elapsedRealtimeNanos()
        val token = tokens.updateAndGet { previous -> maxOf(now, previous + 1) }
        val marker = PythonCrashMarker(
            pluginId,
            phase,
            TargetProcesses.currentName,
            PythonRuntimePack.mounted()?.manifest?.version,
            token,
        )
        activeMarkers[token] = marker
        persist(marker)
        token
    }

    fun finish(token: Long) = synchronized(lock) {
        if (activeMarkers.remove(token) == null) return@synchronized
        val remaining = activeMarkers.values.lastOrNull()
        if (remaining == null) markerFile.delete() else persist(remaining)
    }

    fun suspect(): PythonCrashMarker? = synchronized(lock) {
        if (!markerFile.isFile) return@synchronized null
        runCatching { json.decodeFromString<PythonCrashMarker>(markerFile.readText()) }
            .getOrNull()
            ?.takeUnless { it.token in activeMarkers }
    }

    fun clear() = synchronized(lock) {
        activeMarkers.clear()
        markerFile.delete()
    }

    fun clear(pluginId: String) = synchronized(lock) {
        activeMarkers.entries.removeAll { it.value.pluginId == pluginId }
        if (suspect()?.pluginId == pluginId) {
            activeMarkers.values.lastOrNull()?.let(::persist) ?: markerFile.delete()
        }
    }

    private fun persist(marker: PythonCrashMarker) {
        directory.mkdirs()
        val temporary = File(directory, "execution-marker.tmp")
        temporary.outputStream().use { it.write(json.encodeToString(marker).encodeToByteArray()) }
        Files.move(
            temporary.toPath(),
            markerFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
