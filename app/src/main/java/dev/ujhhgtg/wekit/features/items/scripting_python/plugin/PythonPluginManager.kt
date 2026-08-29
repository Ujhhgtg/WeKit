package dev.ujhhgtg.wekit.features.items.scripting_python.plugin

import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.features.items.scripting_python.PythonScriptingFeature
import dev.ujhhgtg.wekit.features.items.scripting_python.runtime.PythonRuntimeLimits
import dev.ujhhgtg.wekit.features.items.scripting_python.runtime.PythonRuntimeLoader
import dev.ujhhgtg.wekit.features.items.scripting_python.runtime.PythonRuntimeMissingException
import dev.ujhhgtg.wekit.features.items.scripting_python.services.PythonPluginHostImpl
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.python.api.PythonPluginRequest
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.div

object PythonPluginManager {
    private const val TAG = "PythonPluginManager"
    private val idPattern = Regex("[a-z0-9]+([._-][a-z0-9]+)*")
    private val entryPattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val json = Json { ignoreUnknownKeys = false }
    private val discoveryLock = Any()
    private val lifecycleLocks = ConcurrentHashMap<String, Any>()
    private val scopes = ConcurrentHashMap<String, PythonPluginScope>()
    private val activatingIds = ConcurrentHashMap.newKeySet<String>()
    private val mutableRecords = MutableStateFlow<Map<String, PythonPluginRecord>>(emptyMap())
    val records: StateFlow<Map<String, PythonPluginRecord>> = mutableRecords

    private val scriptsDirectory by lazy { (KnownPaths.moduleData / "scripts_python").createDirsSafe().toFile() }
    private val dataDirectory by lazy { (KnownPaths.moduleData / "python" / "data").createDirsSafe().toFile() }
    private val cacheDirectory by lazy { (KnownPaths.moduleCache / "python").createDirsSafe().toFile() }

    fun discover(): List<PythonPluginRecord> = synchronized(discoveryLock) {
        val existing = mutableRecords.value
        val discovered = LinkedHashMap<String, PythonPluginRecord>()
        val crashSuspect = PythonCrashGuard.suspect()
        scriptsDirectory.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .sortedBy(File::getName)
            .forEach { root ->
                val result = runCatching { validate(root) }
                val manifest = result.getOrNull()
                val id = manifest?.id ?: root.name
                val duplicate = discovered.containsKey(id)
                val error = result.exceptionOrNull()?.message ?: if (duplicate) "Duplicate plugin ID: $id" else null
                val previous = existing[id]?.takeIf { it.root == root && it.manifest != null && manifest != null }
                discovered[id] = PythonPluginRecord(
                    id = id,
                    root = root,
                    manifest = manifest,
                    desiredEnabled = manifest != null && WePrefs.getBoolOrFalse(preferenceKey(id)),
                    status = when {
                        error != null -> PythonPluginStatus.FAILED
                        crashSuspect?.pluginId == id -> PythonPluginStatus.CRASH_SUSPECT
                        else -> previous?.status ?: PythonPluginStatus.DISABLED
                    },
                    lastError = error ?: previous?.lastError,
                    traceback = previous?.traceback,
                )
            }
        existing.values
            .filter { it.id !in discovered && (scopes.containsKey(it.id) || it.id in activatingIds) }
            .forEach { record ->
                discovered[record.id] = record.copy(
                    status = PythonPluginStatus.FAILED,
                    lastError = "Plugin directory was removed while active",
                )
            }
        mutableRecords.update { current ->
            discovered.mapValues { (id, scanned) ->
                val latest = current[id]
                if (latest != null && latest != existing[id] && scanned.manifest != null) {
                    scanned.copy(
                        desiredEnabled = latest.desiredEnabled,
                        status = latest.status,
                        lastError = latest.lastError,
                        traceback = latest.traceback,
                    )
                } else {
                    scanned
                }
            }
        }
        mutableRecords.value.values.toList()
    }

    fun activateDesired() {
        if (mutableRecords.value.isEmpty()) discover()
        mutableRecords.value.values
            .filter { it.desiredEnabled && it.status != PythonPluginStatus.CRASH_SUSPECT }
            .forEach { activate(it.id) }
    }

    fun setDesiredEnabled(pluginId: String, enabled: Boolean) {
        val record = mutableRecords.value.getValue(pluginId)
        WePrefs.putBool(preferenceKey(pluginId), enabled)
        if (!enabled && record.status == PythonPluginStatus.CRASH_SUSPECT) {
            PythonCrashGuard.clear(pluginId)
        }
        mutableRecords.update { records ->
            records + (pluginId to records.getValue(pluginId).copy(desiredEnabled = enabled))
        }
        if (enabled && PythonScriptingFeature.isActive) activate(pluginId) else deactivate(pluginId)
    }

    fun activate(pluginId: String) = synchronized(lifecycleLocks.computeIfAbsent(pluginId) { Any() }) {
        val record = mutableRecords.value.getValue(pluginId)
        val manifest = record.manifest ?: return@synchronized
        if (record.status == PythonPluginStatus.ACTIVE || record.status == PythonPluginStatus.LOADING) return@synchronized
        activatingIds += pluginId
        updateStatus(pluginId, PythonPluginStatus.LOADING)
        val scope = PythonPluginScope(pluginId)
        val host = PythonPluginHostImpl(pluginId, scope)
        try {
            val backend = PythonRuntimeLoader.ensureStarted(host)
            val request = PythonPluginRequest(
                id = pluginId,
                root = record.root,
                entry = manifest.entry,
                dataDirectory = File(dataDirectory, pluginId).apply { mkdirs() },
                cacheDirectory = File(cacheDirectory, pluginId).apply { mkdirs() },
            )
            backend.activatePlugin(request, host)
            scope.defer { backend.deactivatePlugin(pluginId) }
            scopes[pluginId] = scope
            updateStatus(pluginId, PythonPluginStatus.ACTIVE)
        } catch (_: PythonRuntimeMissingException) {
            scope.close()
            updateStatus(pluginId, PythonPluginStatus.RUNTIME_MISSING, "Python Runtime required")
        } catch (error: Throwable) {
            scope.close()
            WeLogger.e(TAG, "failed to activate $pluginId", error)
            updateStatus(pluginId, PythonPluginStatus.FAILED, error.message, error.stackTraceToString())
        } finally {
            activatingIds -= pluginId
        }
    }

    fun deactivate(pluginId: String) = synchronized(lifecycleLocks.computeIfAbsent(pluginId) { Any() }) {
        if (mutableRecords.value[pluginId] == null) return@synchronized
        updateStatus(pluginId, PythonPluginStatus.UNLOADING)
        val errors = scopes.remove(pluginId)?.close().orEmpty()
        if (errors.isEmpty()) {
            updateStatus(pluginId, PythonPluginStatus.DISABLED)
        } else {
            val error = PythonPluginCleanupException(errors)
            updateStatus(pluginId, PythonPluginStatus.FAILED, error.message, error.stackTraceToString())
        }
    }

    fun reload(pluginId: String) = synchronized(lifecycleLocks.computeIfAbsent(pluginId) { Any() }) {
        deactivate(pluginId)
        if (mutableRecords.value[pluginId]?.status == PythonPluginStatus.DISABLED) {
            activate(pluginId)
        }
    }

    fun deactivateAll() = mutableRecords.value.keys.forEach(::deactivate)

    fun isValidPluginId(id: String): Boolean = idPattern.matches(id)

    fun createPlugin(id: String, name: String, version: String, author: String, description: String): String =
        synchronized(discoveryLock) {
            require(isValidPluginId(id)) { "Invalid plugin ID: $id" }
            require(name.isNotBlank() && version.isNotBlank()) { "Plugin name and version are required" }
            val root = File(scriptsDirectory, id)
            require(!root.exists()) { "Plugin already exists: $id" }
            root.mkdirs()
            try {
                val manifest = PythonPluginManifest(
                    schema = 1,
                    id = id,
                    name = name.trim(),
                    version = version.trim(),
                    author = author.trim(),
                    description = description.trim(),
                    entry = "main",
                )
                File(root, "plugin.json").writeText(json.encodeToString(manifest))
                File(root, "main.py").writeText(ENTRY_SKELETON)
            } catch (error: Throwable) {
                root.deleteRecursively()
                throw error
            }
            discover()
            id
        }

    fun importPlugin(archive: File): String = synchronized(discoveryLock) {
        ZipFile(archive).use { zip ->
            val manifestEntry = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .firstOrNull {
                    val name = it.name
                    name == "plugin.json" ||
                        (name.endsWith("/plugin.json") && !name.dropLast("/plugin.json".length).contains('/'))
                } ?: throw IllegalArgumentException("No plugin.json found in the archive")
            val prefix = manifestEntry.name.takeIf { it.contains('/') }?.substringBefore('/')?.plus("/") ?: ""
            val manifest =
                json.decodeFromString<PythonPluginManifest>(zip.getInputStream(manifestEntry).readBytes().decodeToString())
            require(manifest.schema == 1) { "Unsupported plugin schema: ${manifest.schema}" }
            require(isValidPluginId(manifest.id)) { "Invalid plugin ID: ${manifest.id}" }
            val destination = File(scriptsDirectory, manifest.id)
            require(!destination.exists()) { "Plugin already exists: ${manifest.id}" }

            val staging = File(scriptsDirectory, ".importing-${System.nanoTime()}")
            try {
                zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filter { it.name == manifestEntry.name || it.name.startsWith(prefix) }
                    .filterNot { it.name.contains("__MACOSX") || it.name.endsWith(".DS_Store") }
                    .forEach { entry ->
                        if (entry.size >= 0) {
                            require(entry.size <= PythonRuntimeLimits.MAX_PLUGIN_FILE_BYTES) {
                                "Plugin file is too large: ${entry.name}"
                            }
                        }
                        val target = File(staging, entry.name.removePrefix(prefix))
                        require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            "Archive entry escapes its directory: ${entry.name}"
                        }
                        target.parentFile!!.mkdirs()
                        zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
                    }
                if (!staging.renameTo(destination)) {
                    staging.copyRecursively(destination)
                    staging.deleteRecursively()
                }
                try {
                    validate(destination)
                } catch (error: Throwable) {
                    destination.deleteRecursively()
                    throw error
                }
                discover()
                manifest.id
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }
    }

    fun exportPlugin(pluginId: String, output: OutputStream) = synchronized(discoveryLock) {
        val root = File(scriptsDirectory, pluginId)
        require(root.isDirectory) { "Plugin not found: $pluginId" }
        ZipOutputStream(output.buffered()).use { zip ->
            root.walkTopDown().filter(File::isFile).forEach { file ->
                zip.putNextEntry(ZipEntry(file.toRelativeString(root).replace(File.separatorChar, '/')))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun updatePluginInfo(pluginId: String, manifest: PythonPluginManifest) {
        synchronized(discoveryLock) {
            require(manifest.id == pluginId && manifest.schema == 1) { "Plugin manifest identity cannot change" }
            require(manifest.name.isNotBlank() && manifest.version.isNotBlank()) {
                "Plugin name and version are required"
            }
            File(scriptsDirectory, pluginId).resolve("plugin.json").writeText(json.encodeToString(manifest))
            discover()
        }
        // Info edits are only allowed while the plugin is not ACTIVE, but a desired-enabled record
        // may still hold a previously activated scope or a FAILED/RUNTIME_MISSING attempt; reload
        // so the saved manifest takes effect immediately instead of lingering as stale state.
        if (mutableRecords.value[pluginId]?.desiredEnabled == true) reload(pluginId)
    }

    fun deletePlugin(pluginId: String) {
        deactivate(pluginId)
        synchronized(discoveryLock) {
            val root = mutableRecords.value[pluginId]?.root ?: File(scriptsDirectory, pluginId)
            root.deleteRecursively()
            File(dataDirectory, pluginId).deleteRecursively()
            File(cacheDirectory, pluginId).deleteRecursively()
            WePrefs.remove(preferenceKey(pluginId))
            PythonCrashGuard.clear(pluginId)
            lifecycleLocks.remove(pluginId)
            mutableRecords.update { it - pluginId }
        }
    }

    private fun validate(root: File): PythonPluginManifest {
        require(!Files.isSymbolicLink(root.toPath())) { "Plugin root cannot be a symlink" }
        root.walkTopDown().forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "Plugin tree contains a symlink: ${file.name}" }
            require(file.canonicalPath.startsWith(root.canonicalPath + File.separator) || file == root) {
                "Plugin file escapes its root"
            }
            if (file.isFile) require(file.length() <= PythonRuntimeLimits.MAX_PLUGIN_FILE_BYTES) {
                "Plugin file is too large: ${file.name}"
            }
        }
        val manifestFile = File(root, "plugin.json")
        require(
            manifestFile.isFile && manifestFile.length() <= PythonRuntimeLimits.MAX_MANIFEST_BYTES,
        ) { "Missing or oversized plugin.json" }
        val manifest = json.decodeFromString<PythonPluginManifest>(manifestFile.readText())
        require(manifest.schema == 1) { "Unsupported plugin schema: ${manifest.schema}" }
        require(idPattern.matches(manifest.id) && manifest.id == root.name) { "Invalid plugin ID: ${manifest.id}" }
        require(manifest.name.isNotBlank() && manifest.version.isNotBlank()) { "Plugin name and version are required" }
        require(entryPattern.matches(manifest.entry)) { "Invalid plugin entry: ${manifest.entry}" }
        require(manifest.processes == listOf("main")) { "Only the main process is supported" }
        require(manifest.minWeKitVersionCode >= 0) { "Invalid minWeKitVersionCode" }
        require(manifest.minWeKitVersionCode <= BuildConfig.VERSION_CODE) { "Plugin requires a newer WeKit" }
        val entry = File(root, manifest.entry.replace('.', File.separatorChar))
        require(File(entry.path + ".py").isFile || File(entry, "__init__.py").isFile) {
            "Plugin entry module does not exist: ${manifest.entry}"
        }
        return manifest
    }

    private fun updateStatus(
        pluginId: String,
        status: PythonPluginStatus,
        lastError: String? = null,
        traceback: String? = null,
    ) {
        mutableRecords.update { records ->
            records + (pluginId to records.getValue(pluginId).copy(
                status = status,
                lastError = lastError,
                traceback = traceback,
            ))
        }
    }

    fun isTrustWarningAccepted(): Boolean = WePrefs.getBoolOrFalse(TRUST_WARNING_KEY)

    fun acceptTrustWarning() = WePrefs.putBool(TRUST_WARNING_KEY, true)

    private fun preferenceKey(pluginId: String) = "python.plugin.$pluginId.enabled"

    private const val TRUST_WARNING_KEY = "python.trust_warning.accepted"

    private const val ENTRY_SKELETON = """from __future__ import annotations

from wekit.runtime import PluginContext


def setup(ctx: PluginContext) -> None:
    # TODO: write your plugin logic here; see the demo plugin for the full API surface.
    ctx.log.info("plugin loading")
"""
}
