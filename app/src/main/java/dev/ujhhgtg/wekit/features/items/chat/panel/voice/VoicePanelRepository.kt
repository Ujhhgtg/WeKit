package dev.ujhhgtg.wekit.features.items.chat.panel.voice

import dev.ujhhgtg.wekit.features.items.chat.panel.LocalSortMode
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelCustomOrders
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelPaths
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSettings
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSource
import dev.ujhhgtg.wekit.features.items.chat.panel.RECENT_PACK_ID
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceItem
import dev.ujhhgtg.wekit.features.items.chat.panel.VoicePack
import dev.ujhhgtg.wekit.features.items.chat.panel.customOrderIndex
import dev.ujhhgtg.wekit.features.items.chat.panel.normalizedCustomOrder
import dev.ujhhgtg.wekit.features.items.chat.localizedChatString
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.MediaFileTypeDetector
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object VoicePanelRepository {
    private val statsFile get() = PanelPaths.voicePanelDir / ".stats.json"
    private val onlineRecentsFile get() = PanelPaths.voicePanelDir / ".online_recents.json"
    private val ordersFile get() = PanelPaths.voicePanelDir / ".orders.json"

    @Serializable
    private data class VoiceStats(val sendCount: Long = 0, val lastSentAt: Long = 0)

    fun loadPacks(): List<VoicePack> {
        migrateLegacyRootVoices()
        val root = PanelPaths.voicePanelDir
        val stats = readStats()
        val orders = readOrders()
        val packs = mutableListOf<VoicePack>()
        root.listDirectoryEntries()
            .filter { it.isDirectory() && it != PanelPaths.cloneVoiceDir && !it.name.startsWith(".") }
            .forEach { packDir ->
                val items = packDir.listDirectoryEntries().filter(::isVoiceFile)
                    .sortedWith(voiceComparator(packDir.name, stats, orders))
                    .map { it.toItem(packDir.name, PanelSource.LOCAL, stats) }
                packs += VoicePack(
                    id = packDir.name,
                    title = packDir.name,
                    source = PanelSource.LOCAL,
                    itemCount = items.size,
                    items = items,
                )
            }
        packs.sortWith(packComparator(orders))
        val recentItems = (packs.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.lastSentAt > 0 } + readOnlineRecents().asSequence())
            .distinctBy(::recentKey)
            .sortedByDescending(VoiceItem::lastSentAt)
            .take(historyLimit())
            .map { it.copy(source = PanelSource.RECENT, packId = RECENT_PACK_ID) }
            .toList()
        return buildList {
            if (recentItems.isNotEmpty()) {
                add(
                    VoicePack(
                        id = RECENT_PACK_ID,
                        title = localizedChatString(R.string.chat_panel_recent),
                        source = PanelSource.RECENT,
                        itemCount = recentItems.size,
                        items = recentItems,
                    ),
                )
            }
            addAll(packs)
        }
    }

    fun savePackOrder(packIds: List<String>): Result<Unit> = runCatching {
        val available = PanelPaths.voicePanelDir.listDirectoryEntries()
            .filter { it.isDirectory() && it != PanelPaths.cloneVoiceDir && !it.name.startsWith(".") }
            .map { it.name }
        val orders = readOrders()
        atomicWrite(
            ordersFile,
            DefaultJson.encodeToString(orders.copy(packs = normalizedCustomOrder(packIds, available))),
        )
    }

    fun saveItemOrder(packName: String, filePaths: List<String>): Result<Unit> = runCatching {
        val safePack = requirePackName(packName)
        val directory = packPath(safePack)
        require(directory.isDirectory()) { localizedChatString(R.string.chat_voice_pack_not_found) }
        val requested = filePaths.map { value ->
            value.asPath.toAbsolutePath().normalize().also { path ->
                require(path.parent == directory && path.isRegularFile() && isVoiceFile(path)) {
                    localizedChatString(R.string.chat_voice_not_in_pack)
                }
            }.name
        }
        val available = directory.listDirectoryEntries().filter(::isVoiceFile).map { it.name }
        val orders = readOrders()
        atomicWrite(
            ordersFile,
            DefaultJson.encodeToString(
                orders.copy(items = orders.items + (safePack to normalizedCustomOrder(requested, available))),
            ),
        )
    }

    fun loadVoices(): List<VoiceItem> = loadPacks()
        .filter { it.id != RECENT_PACK_ID }
        .flatMap { it.items }

    fun search(query: String): List<VoiceItem> {
        val term = query.trim()
        return loadPacks().filter { it.id != RECENT_PACK_ID }.flatMap { pack ->
            pack.items.filter {
                term.isEmpty() || it.title.contains(term, true) || pack.title.contains(term, true)
            }
        }
    }

    fun createPack(name: String): Result<String> = runCatching {
        val safeName = sanitizeName(name)
        require(safeName.isNotBlank()) { localizedChatString(R.string.chat_voice_pack_name_empty) }
        require(safeName !in reservedNames) { localizedChatString(R.string.chat_voice_pack_name_unavailable) }
        val destination = packPath(safeName)
        require(Files.notExists(destination)) { localizedChatString(R.string.chat_voice_pack_exists) }
        destination.createDirectories()
        safeName
    }

    /** Returns an existing pack or creates it, allowing interrupted online downloads to resume. */
    fun ensurePack(name: String): Result<String> = runCatching {
        val safeName = requirePackName(name)
        packPath(safeName).createDirectories()
        safeName
    }

    fun renamePack(oldName: String, newName: String): Result<Unit> = runCatching {
        val safeOldName = requirePackName(oldName)
        val safeName = sanitizeName(newName)
        require(safeName.isNotBlank()) { localizedChatString(R.string.chat_voice_pack_name_empty) }
        require(safeName !in reservedNames) { localizedChatString(R.string.chat_voice_pack_name_unavailable) }
        val source = packPath(safeOldName)
        val destination = packPath(safeName)
        require(source.isDirectory()) { localizedChatString(R.string.chat_voice_pack_not_found) }
        require(Files.notExists(destination)) { localizedChatString(R.string.chat_voice_pack_exists) }
        Files.move(source, destination)
        migrateStatsPrefix(source, destination)
        migrateOrders(source.name, destination.name)
    }

    fun deletePack(name: String): Result<Unit> = runCatching {
        val directory = packPath(requirePackName(name))
        require(directory.isDirectory()) { localizedChatString(R.string.chat_voice_pack_not_found) }
        require(directory.toFile().deleteRecursively()) { localizedChatString(R.string.chat_voice_pack_delete_failed) }
        removeStatsPrefix(directory)
        removePackOrder(directory.name)
    }

    fun deleteVoices(filePaths: List<String>): Result<Int> = runCatching {
        val root = PanelPaths.voicePanelDir.toAbsolutePath().normalize()
        val paths = filePaths.map { value ->
            value.asPath.toAbsolutePath().normalize().also { path ->
                require(
                    path.startsWith(root) && path.parent != root &&
                            path.isRegularFile() && isVoiceFile(path),
                ) { "语音路径无效" }
            }
        }.distinct()
        require(paths.isNotEmpty()) { localizedChatString(R.string.chat_voice_none_selected) }
        paths.forEach { path -> require(path.deleteIfExists()) { localizedChatString(R.string.chat_voice_not_found) } }

        val deletedPaths = paths.mapTo(hashSetOf()) { it.absolutePathString() }
        atomicWrite(
            statsFile,
            DefaultJson.encodeToString(readStats().filterKeys { it !in deletedPaths }),
        )
        val orders = readOrders()
        val deletedNamesByPack = paths.groupBy({ it.parent.name }) { it.name }
        atomicWrite(
            ordersFile,
            DefaultJson.encodeToString(
                orders.copy(
                    items = orders.items.mapValues { (packName, names) ->
                        names.filterNot { it in deletedNamesByPack[packName].orEmpty() }
                    },
                ),
            ),
        )
        paths.size
    }

    fun importVoice(packId: String, displayName: String, input: InputStream): Result<VoiceItem> = runCatching {
        val directory = packPath(requirePackName(packId)).also { it.createDirectories() }
        val temporary = directory / ".import-${UUID.randomUUID()}.part"
        try {
            input.use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
            require(Files.size(temporary) > 0L) { "语音文件为空" }
            val format = MediaFileTypeDetector.detectAudio(temporary)
                ?: throw IllegalArgumentException("不支持或无法识别的语音格式")
            val destination = uniquePath(
                directory,
                "${importedFileStem(displayName, "voice")}.${format.extension}",
            )
            moveImportedFile(temporary, destination)
            destination.toItem(packId, PanelSource.IMPORTED, readStats())
        } finally {
            temporary.deleteIfExists()
        }
    }

    /**
     * Saves an online voice using a stable provider/object identity. A non-empty existing file is
     * considered complete, so rerunning an interrupted pack save does not create suffixed copies.
     */
    fun importOnlineVoice(packName: String, item: VoiceItem, input: InputStream): Result<VoiceItem> = runCatching {
        val safePack = requirePackName(packName)
        val directory = packPath(safePack).also { it.createDirectories() }
        val identity = sanitizeName(item.remoteObjectId ?: item.id).ifBlank { sanitizeName(item.title) }
        existingOnlinePath(directory, identity)?.let { existing ->
            return@runCatching existing.toItem(safePack, PanelSource.IMPORTED, readStats())
        }
        val temporary = directory / "${identity.take(96)}.part"
        try {
            input.use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
            require(Files.size(temporary) > 0L) { "服务器未返回语音数据" }
            val extension = MediaFileTypeDetector.detectAudio(temporary)?.extension
                ?: throw IllegalArgumentException("服务器返回了不支持的语音格式")
            val destination = directory / "${identity.take(96)}.$extension"
            moveImportedFile(temporary, destination)
            destination.toItem(safePack, PanelSource.IMPORTED, readStats())
        } finally {
            temporary.deleteIfExists()
        }
    }

    fun hasOnlineVoice(packName: String, item: VoiceItem): Boolean = runCatching {
        val safePack = requirePackName(packName)
        val identity = sanitizeName(item.remoteObjectId ?: item.id).ifBlank { sanitizeName(item.title) }
        existingOnlinePath(packPath(safePack), identity) != null
    }.getOrDefault(false)

    fun recordSent(filePath: String) {
        val stats = readStats().toMutableMap()
        val current = stats[filePath] ?: VoiceStats()
        stats[filePath] = current.copy(
            sendCount = current.sendCount + 1,
            lastSentAt = System.currentTimeMillis(),
        )
        atomicWrite(statsFile, DefaultJson.encodeToString(stats))
    }

    fun recordSent(item: VoiceItem) {
        if (item.localPath != null) recordSent(item.localPath)
        else recordOnlineRecent(item)
    }

    private fun recordOnlineRecent(item: VoiceItem) {
        val key = recentKey(item).takeIf { item.remoteObjectId != null || ':' in item.id } ?: return
        val previous = readOnlineRecents().firstOrNull { recentKey(it) == key }
        val recorded = item.copy(
            source = PanelSource.RECENT,
            packId = RECENT_PACK_ID,
            sendCount = (previous?.sendCount ?: item.sendCount) + 1,
            lastSentAt = System.currentTimeMillis(),
        )
        val current = buildList {
            add(recorded)
            addAll(readOnlineRecents().filterNot { recentKey(it) == key })
        }.take(historyLimit())
        atomicWrite(onlineRecentsFile, DefaultJson.encodeToString(current))
    }

    private fun java.nio.file.Path.toItem(
        packId: String,
        source: PanelSource,
        stats: Map<String, VoiceStats>,
    ): VoiceItem {
        val path = absolutePathString()
        val itemStats = stats[path] ?: VoiceStats()
        return VoiceItem(
            id = path,
            title = nameWithoutExtension,
            localPath = path,
            source = source,
            packId = packId,
            durationMs = AudioUtils.getDurationMs(path).coerceAtLeast(0L),
            format = MediaFileTypeDetector.detectAudio(this)?.extension.orEmpty(),
            sendCount = itemStats.sendCount,
            lastSentAt = itemStats.lastSentAt,
        )
    }

    private fun readStats(): Map<String, VoiceStats> {
        if (statsFile.notExists()) return emptyMap()
        return runCatching { DefaultJson.decodeFromString<Map<String, VoiceStats>>(statsFile.readText()) }
            .getOrDefault(emptyMap())
    }

    private fun readOrders(): PanelCustomOrders {
        if (ordersFile.notExists()) return PanelCustomOrders()
        return runCatching {
            DefaultJson.decodeFromString<PanelCustomOrders>(ordersFile.readText())
        }.getOrDefault(PanelCustomOrders())
    }

    private fun voiceComparator(
        packName: String,
        stats: Map<String, VoiceStats>,
        orders: PanelCustomOrders,
    ): Comparator<java.nio.file.Path> {
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { path: java.nio.file.Path -> path.name }
        return when (PanelSettings.voiceItemSortMode) {
            LocalSortMode.NAME -> byName
            LocalSortMode.MODIFIED -> compareByDescending<java.nio.file.Path>(::lastModified).then(byName)
            LocalSortMode.RECENT -> compareByDescending<java.nio.file.Path> {
                stats[it.absolutePathString()]?.lastSentAt ?: 0L
            }.then(byName)

            LocalSortMode.FREQUENT -> compareByDescending<java.nio.file.Path> {
                stats[it.absolutePathString()]?.sendCount ?: 0L
            }.then(byName)

            LocalSortMode.CUSTOM -> compareBy<java.nio.file.Path> {
                customOrderIndex(orders.items[packName], it.name)
            }.then(byName)
        }
    }

    private fun packComparator(orders: PanelCustomOrders): Comparator<VoicePack> {
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { pack: VoicePack -> pack.title }
        return when (PanelSettings.voicePackSortMode) {
            LocalSortMode.NAME -> byName
            LocalSortMode.MODIFIED -> compareByDescending<VoicePack> { pack ->
                maxOf(
                    lastModified(packPath(pack.id)),
                    pack.items.maxOfOrNull { it.localPath?.asPath?.let(::lastModified) ?: 0L } ?: 0L,
                )
            }.then(byName)

            LocalSortMode.RECENT -> compareByDescending<VoicePack> { pack ->
                pack.items.maxOfOrNull(VoiceItem::lastSentAt) ?: 0L
            }.then(byName)

            LocalSortMode.FREQUENT -> compareByDescending<VoicePack> { pack ->
                pack.items.sumOf(VoiceItem::sendCount)
            }.then(byName)

            LocalSortMode.CUSTOM -> compareBy<VoicePack> {
                customOrderIndex(orders.packs, it.id)
            }.then(byName)
        }
    }

    private fun lastModified(path: java.nio.file.Path): Long =
        runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)

    private fun readOnlineRecents(): List<VoiceItem> {
        if (onlineRecentsFile.notExists()) return emptyList()
        return runCatching {
            DefaultJson.decodeFromString<List<VoiceItem>>(onlineRecentsFile.readText())
                .filter { it.localPath == null && (it.remoteObjectId != null || ':' in it.id) }
        }.getOrDefault(emptyList())
    }

    private fun recentKey(item: VoiceItem): String = item.remoteObjectId ?: item.id

    private fun historyLimit(): Int = PanelSettings.voiceMaxHistory
        .coerceIn(1L, Int.MAX_VALUE.toLong())
        .toInt()

    private fun migrateLegacyRootVoices() {
        val legacyFiles = PanelPaths.voicePanelDir.listDirectoryEntries().filter(::isVoiceFile)
        if (legacyFiles.isEmpty()) return
        val destinationDir = packPath(LEGACY_IMPORT_PACK).also { it.createDirectories() }
        val migratedPaths = buildMap {
            legacyFiles.forEach { source ->
                val destination = uniquePath(destinationDir, source.name)
                runCatching { Files.move(source, destination) }.onSuccess {
                    put(source.absolutePathString(), destination.absolutePathString())
                }
            }
        }
        if (migratedPaths.isEmpty()) return
        val migratedStats = readStats().mapKeys { (path, _) -> migratedPaths[path] ?: path }
        atomicWrite(statsFile, DefaultJson.encodeToString(migratedStats))
    }

    @Synchronized
    private fun atomicWrite(path: java.nio.file.Path, value: String) {
        val temporary = path.resolveSibling("${path.name}.tmp")
        temporary.writeText(value)
        runCatching {
            Files.move(
                temporary,
                path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
        temporary.deleteIfExists()
    }

    private fun migrateStatsPrefix(source: java.nio.file.Path, destination: java.nio.file.Path) {
        val sourcePrefix = source.toAbsolutePath().normalize()
        val destinationPrefix = destination.toAbsolutePath().normalize()
        val migrated = readStats().mapKeys { (value, _) ->
            runCatching {
                val path = value.asPath.toAbsolutePath().normalize()
                if (path.startsWith(sourcePrefix)) destinationPrefix.resolve(sourcePrefix.relativize(path)).toString() else value
            }.getOrDefault(value)
        }
        atomicWrite(statsFile, DefaultJson.encodeToString(migrated))
    }

    private fun removeStatsPrefix(directory: java.nio.file.Path) {
        val prefix = directory.toAbsolutePath().normalize()
        val retained = readStats().filterKeys { value ->
            runCatching {
                !value.asPath.toAbsolutePath().normalize().startsWith(prefix)
            }.getOrDefault(true)
        }
        atomicWrite(statsFile, DefaultJson.encodeToString(retained))
    }

    private fun migrateOrders(oldName: String, newName: String) {
        val orders = readOrders()
        atomicWrite(
            ordersFile,
            DefaultJson.encodeToString(
                orders.copy(
                    packs = orders.packs.map { if (it == oldName) newName else it },
                    items = orders.items.toMutableMap().apply {
                        remove(oldName)?.let { put(newName, it) }
                    },
                ),
            ),
        )
    }

    private fun removePackOrder(packName: String) {
        val orders = readOrders()
        atomicWrite(
            ordersFile,
            DefaultJson.encodeToString(
                orders.copy(
                    packs = orders.packs.filterNot { it == packName },
                    items = orders.items - packName,
                ),
            ),
        )
    }

    private fun isVoiceFile(path: java.nio.file.Path) =
        path.isRegularFile() && !path.name.endsWith(".part") && MediaFileTypeDetector.detectAudio(path) != null

    private fun existingOnlinePath(directory: java.nio.file.Path, identity: String): java.nio.file.Path? =
        if (!directory.isDirectory()) null
        else {
            val stableIdentity = identity.take(96)
            directory.listDirectoryEntries()
            .firstOrNull { path ->
                path.isRegularFile() &&
                        (path.name == stableIdentity || path.name.startsWith("$stableIdentity.")) &&
                        isVoiceFile(path)
            }
        }

    private fun sanitizeName(value: String) = value.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun requirePackName(value: String): String {
        val name = sanitizeName(value)
        require(name.isNotBlank() && name !in reservedNames) { "语音包名称不可用" }
        return name
    }

    private fun packPath(name: String): java.nio.file.Path {
        val root = PanelPaths.voicePanelDir.toAbsolutePath().normalize()
        return root.resolve(name).normalize().also { path ->
            require(path.parent == root) { "语音包路径无效" }
        }
    }

    private fun uniquePath(dir: java.nio.file.Path, fileName: String): java.nio.file.Path {
        var candidate = dir / fileName
        var suffix = 1
        while (Files.exists(candidate)) {
            val stem = fileName.substringBeforeLast('.')
            val ext = fileName.substringAfterLast('.', "")
            candidate = dir / "$stem-$suffix${if (ext.isEmpty()) "" else ".$ext"}"
            suffix++
        }
        return candidate
    }

    private fun importedFileStem(value: String, fallback: String): String {
        val safeName = sanitizeName(value).ifBlank { fallback }
        return safeName.substringBeforeLast('.', safeName).ifBlank { fallback }
    }

    private fun moveImportedFile(source: java.nio.file.Path, destination: java.nio.file.Path) {
        runCatching {
            Files.move(
                source,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING) }
    }

    private val reservedNames = setOf(".", "..", "clone_voices", RECENT_PACK_ID)

    private const val LEGACY_IMPORT_PACK = "已导入"
}
