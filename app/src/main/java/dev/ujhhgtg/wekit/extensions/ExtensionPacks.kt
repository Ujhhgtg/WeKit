package dev.ujhhgtg.wekit.extensions

import dev.ujhhgtg.wekit.extensions.ExtensionPackState.Downloading
import dev.ujhhgtg.wekit.extensions.ExtensionPackState.Failed
import dev.ujhhgtg.wekit.extensions.ExtensionPackState.NotInstalled
import dev.ujhhgtg.wekit.extensions.ExtensionPackState.Verifying
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Generic extension-pack plumbing: remote index, download, verify, install, state, delete. */
object ExtensionPacks {

    private const val TAG = "ExtensionPacks"

    /** The persistent "Extensions" prerelease carrying the pack assets and the index. */
    const val BASE_URL = "https://github.com/Ujhhgtg/WeKit/releases/download/Extensions"
    private const val INDEX_ASSET = "manifest.json"

    val packs: List<ExtensionPack> = listOf(ScriptDepsPack, CloudflaredPack, ArchLinuxPack)

    fun byId(id: String): ExtensionPack? = packs.firstOrNull { it.id == id }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flows = packs.associate { it.id to MutableStateFlow<ExtensionPackState>(NotInstalled) }
    private val downloadJobs = mutableMapOf<String, Job>()
    private val activeCalls = mutableMapOf<String, Call>()
    private val remote = mutableMapOf<String, PackIndexEntry>()
    private val lock = Any()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun stateFlow(pack: ExtensionPack): StateFlow<ExtensionPackState> = flows.getValue(pack.id)

    /** Re-scans disk into the state flow (keeps in-flight download states). */
    fun refresh(pack: ExtensionPack) {
        val flow = flows.getValue(pack.id)
        val current = flow.value
        if (current is Downloading || current is Verifying) return
        flow.value = classifyPackState(pack.installedManifest(), remoteEntryOf(pack))
    }

    /**
     * Fetches the remote index and reclassifies every pack against it, turning
     * up-to-date installs into [ExtensionPackState.UpdateAvailable]. The screen
     * calls this on open; fetch failures are logged and leave states untouched.
     */
    fun checkUpdates() {
        scope.launch {
            val index = try {
                fetchIndex()
            } catch (e: Exception) {
                WeLogger.w(TAG, "index fetch failed: ${e.message}")
                return@launch
            }
            synchronized(lock) {
                remote.clear()
                index.packs.associateByTo(remote) { it.id }
            }
            packs.forEach(::refresh)
        }
    }

    fun download(pack: ExtensionPack) {
        val flow = flows.getValue(pack.id)
        synchronized(lock) {
            if (flow.value is Downloading || flow.value is Verifying) return
            downloadJobs.remove(pack.id)?.cancel()
            downloadJobs[pack.id] = scope.launch { downloadInternal(pack, flow) }
        }
    }

    fun cancelDownload(pack: ExtensionPack) {
        synchronized(lock) {
            downloadJobs.remove(pack.id)?.cancel()
            activeCalls.remove(pack.id)?.cancel()
        }
        val flow = flows.getValue(pack.id)
        if (flow.value is Downloading || flow.value is Verifying) {
            flow.value = Failed("canceled")
            refresh(pack)
        }
    }

    /** @return false when the pack is in use and must not be deleted. */
    fun delete(pack: ExtensionPack): Boolean {
        val flow = flows.getValue(pack.id)
        if (pack.isInUse() || flow.value is Downloading || flow.value is Verifying) return false
        pack.installDir().deleteRecursively()
        pack.stagingDir().deleteRecursively()
        refresh(pack)
        return true
    }

    private fun remoteEntryOf(pack: ExtensionPack): PackIndexEntry? = synchronized(lock) { remote[pack.id] }

    private fun fetchIndex(): PackIndex {
        val request = Request.Builder().url("$BASE_URL/$INDEX_ASSET").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("manifest: HTTP ${response.code}")
            return PackFs.decodeIndex(response.body.byteStream().readBytes().decodeToString())
        }
    }

    /** The cached index entry, fetching the index first when it is not cached yet. */
    private suspend fun remoteEntry(pack: ExtensionPack): PackIndexEntry {
        remoteEntryOf(pack)?.let { return it }
        val index = fetchIndex()
        val entry = index.packs.firstOrNull { it.id == pack.id }
            ?: error("manifest: no entry for ${pack.id}")
        synchronized(lock) { remote[pack.id] = entry }
        return entry
    }

    private suspend fun downloadInternal(pack: ExtensionPack, flow: MutableStateFlow<ExtensionPackState>) {
        val staging = pack.stagingDir().also { it.mkdirs() }
        val tmp = File(staging, "download.tmp")
        tmp.delete()
        flow.value = Downloading(0f, 0, 0)
        try {
            val entry = remoteEntry(pack)
            val request = Request.Builder()
                .url("${BASE_URL}/${entry.asset}")
                .build()
            val call = httpClient.newCall(request)
            synchronized(lock) { activeCalls[pack.id] = call }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body
                    val total = body.contentLength()
                    var downloaded = 0L
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                downloaded += n
                                flow.value = Downloading(
                                    progress = if (total > 0) downloaded.toFloat() / total else 0f,
                                    bytesDownloaded = downloaded,
                                    bytesTotal = total,
                                )
                            }
                        }
                    }
                }
            } finally {
                synchronized(lock) { activeCalls.remove(pack.id) }
            }

            flow.value = Verifying
            if (!PackFs.verify(tmp, entry.sha256)) {
                tmp.delete()
                error("SHA-256 mismatch (expected ${entry.sha256}); refusing to install")
            }
            pack.install(tmp, entry.version, entry.sha256)
            WeLogger.i(TAG, "installed ${pack.id} ${entry.version}")
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            WeLogger.e(TAG, "download/install failed for ${pack.id}", e)
            flow.value = Failed(e.message ?: e.javaClass.simpleName)
            return
        }
        flow.value = classifyPackState(pack.installedManifest(), remoteEntryOf(pack))
    }
}
