package dev.ujhhgtg.wekit.features.items.chat.panel.sticker

import dev.ujhhgtg.wekit.features.items.chat.panel.LocalSortMode
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelCustomOrders
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelPaths
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSettings
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSource
import dev.ujhhgtg.wekit.features.items.chat.panel.RECENT_PACK_ID
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerItem
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerPack
import dev.ujhhgtg.wekit.features.items.chat.panel.customOrderIndex
import dev.ujhhgtg.wekit.features.items.chat.panel.normalizedCustomOrder
import dev.ujhhgtg.wekit.features.items.chat.localizedChatString
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.utils.MediaFileTypeDetector
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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

object StickerPanelRepository {
    private const val LEGACY_IMPORT_PACK = "导入"
    private val recentsFile get() = PanelPaths.stickerPanelDir / "recents.json"
    private val onlineRecentsFile get() = PanelPaths.stickerPanelDir / ".online_recents.json"
    private val statsFile get() = PanelPaths.stickerPanelDir / ".stats.json"
    private val titlesFile get() = PanelPaths.stickerPanelDir / ".titles.json"
    private val coversFile get() = PanelPaths.stickerPanelDir / ".covers.json"
    private val ordersFile get() = PanelPaths.stickerPanelDir / ".orders.json"
    private val onlinePackSourcesFile get() = PanelPaths.stickerPanelDir / ".online_pack_sources.json"

    @Serializable
    private data class StickerStats(val sendCount: Long = 0, val lastSentAt: Long = 0)

    fun loadPacks(): List<StickerPack> {
        val stats = readStats()
        val titles = readTitles()
        val covers = readCovers()
        val orders = readOrders()
        val onlinePackSources = readOnlinePackSources()
        return runCatching {
            PanelPaths.stickerPanelDir.listDirectoryEntries()
                .filter { it.isDirectory() && !it.name.startsWith(".") }
                .map { packDir ->
                    val items = packDir.listDirectoryEntries()
                        .filter(::isStickerFile)
                        .sortedWith(stickerComparator(packDir.name, stats, orders))
                        .map { file -> file.toItem(packDir.name, stats, titles, PanelSource.LOCAL) }
                    StickerPack(
                        id = packDir.name,
                        title = packDir.name,
                        cover = items.firstOrNull { item ->
                            item.localPath?.asPath?.name == covers[packDir.name]
                        }?.localPath ?: items.firstOrNull()?.localPath,
                        source = PanelSource.LOCAL,
                        onlineSourcePackId = onlinePackSources[packDir.name],
                        itemCount = items.size,
                        items = items,
                    )
                }
                .sortedWith(packComparator(orders))
        }.getOrElse { emptyList() }
    }

    fun savePackOrder(packIds: List<String>): Result<Unit> = runCatching {
        val available = PanelPaths.stickerPanelDir.listDirectoryEntries()
            .filter { it.isDirectory() && !it.name.startsWith(".") }
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
        require(directory.isDirectory()) { localizedChatString(R.string.chat_sticker_pack_not_found) }
        val requested = filePaths.map { value ->
            requireLocalSticker(value).also { path ->
                require(path.parent == directory) { localizedChatString(R.string.chat_sticker_not_in_pack) }
            }.name
        }
        val available = directory.listDirectoryEntries().filter(::isStickerFile).map { it.name }
        val orders = readOrders()
        atomicWrite(
            ordersFile,
            DefaultJson.encodeToString(
                orders.copy(items = orders.items + (safePack to normalizedCustomOrder(requested, available))),
            ),
        )
    }

    fun getRecents(): StickerPack {
        val stats = readStats()
        val titles = readTitles()
        val limit = PanelSettings.stickerMaxHistory.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val localItems = readRecentPaths().map { path ->
            val file = path.asPath
            file.toItem(RECENT_PACK_ID, stats, titles, PanelSource.RECENT)
        }
        val items = (localItems + readOnlineRecents())
            .distinctBy(::recentKey)
            .sortedWith(compareByDescending<StickerItem> { it.lastSentAt }.thenBy { it.id })
            .take(limit)
        return StickerPack(
            id = RECENT_PACK_ID,
            title = localizedChatString(R.string.chat_panel_recent),
            source = PanelSource.RECENT,
            itemCount = items.size,
            items = items,
        )
    }

    fun search(query: String): List<StickerItem> {
        val term = query.trim()
        if (term.isEmpty()) return loadPacks().flatMap { it.items }
        return loadPacks().flatMap { pack ->
            pack.items.filter {
                it.title.contains(term, ignoreCase = true) ||
                        it.customTitle?.contains(term, ignoreCase = true) == true ||
                        pack.title.contains(term, ignoreCase = true)
            }
        }
    }

    fun createPack(name: String): Result<String> = runCatching {
        val safeName = sanitizeName(name)
        require(safeName.isNotBlank()) { localizedChatString(R.string.chat_sticker_pack_name_empty) }
        require(safeName !in reservedNames) { localizedChatString(R.string.chat_sticker_pack_name_unavailable) }
        val destination = packPath(safeName)
        require(Files.notExists(destination)) { localizedChatString(R.string.chat_sticker_pack_exists) }
        destination.createDirectories()
        safeName
    }

    /** Returns an existing pack or creates it, which makes interrupted online saves resumable. */
    fun ensurePack(name: String): Result<String> = runCatching {
        val safeName = requirePackName(name)
        packPath(safeName).createDirectories()
        safeName
    }

    fun setOnlinePackSource(packName: String, onlinePackId: String): Result<Unit> = runCatching {
        val safePack = requirePackName(packName)
        require(packPath(safePack).isDirectory()) { localizedChatString(R.string.chat_sticker_pack_not_found) }
        val sourceId = onlinePackId.trim()
        require(sourceId.isNotEmpty()) { localizedChatString(R.string.chat_sticker_online_source_empty) }
        atomicWrite(
            onlinePackSourcesFile,
            DefaultJson.encodeToString(readOnlinePackSources() + (safePack to sourceId)),
        )
    }

    fun renamePack(oldName: String, newName: String): Result<Unit> = runCatching {
        val safeOldName = requirePackName(oldName)
        val safeName = sanitizeName(newName)
        require(safeName.isNotBlank()) { localizedChatString(R.string.chat_sticker_pack_name_empty) }
        require(safeName !in reservedNames) { localizedChatString(R.string.chat_sticker_pack_name_unavailable) }
        val source = packPath(safeOldName)
        val destination = packPath(safeName)
        require(source.isDirectory()) { localizedChatString(R.string.chat_sticker_pack_not_found) }
        require(Files.notExists(destination)) { localizedChatString(R.string.chat_sticker_pack_exists) }
        Files.move(source, destination)
        migratePathPrefix(source, destination)
    }

    fun deletePack(name: String): Result<Unit> = runCatching {
        val dir = packPath(requirePackName(name))
        require(dir.isDirectory()) { localizedChatString(R.string.chat_sticker_pack_not_found) }
        require(dir.toFile().deleteRecursively()) { localizedChatString(R.string.chat_sticker_pack_delete_failed) }
        removePathPrefixFromMetadata(dir)
    }

    fun importSticker(packName: String, displayName: String, input: InputStream): Result<StickerItem> = runCatching {
        val safePack = requirePackName(sanitizeName(packName).ifBlank { LEGACY_IMPORT_PACK })
        val packDir = packPath(safePack).also { it.createDirectories() }
        val temporary = packDir / ".import-${UUID.randomUUID()}.part"
        try {
            input.use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
            require(Files.size(temporary) > 0L) { localizedChatString(R.string.chat_sticker_image_empty) }
            val format = MediaFileTypeDetector.detectImage(temporary)
                ?: throw IllegalArgumentException(localizedChatString(R.string.chat_sticker_unsupported_format))
            val destination = uniquePath(packDir, "${importedFileStem(displayName, "sticker")}.${format.extension}")
            moveImportedFile(temporary, destination)
            destination.toItem(safePack, readStats(), readTitles(), PanelSource.IMPORTED)
        } finally {
            temporary.deleteIfExists()
        }
    }

    /** Saves a remote sticker under a stable object-derived filename with its real image type. */
    fun importOnlineSticker(
        item: StickerItem,
        packName: String,
        input: InputStream,
        overwrite: Boolean = false,
    ): Result<StickerItem> = runCatching {
        val safePack = requirePackName(packName)
        val packDir = packPath(safePack).also { it.createDirectories() }
        val existing = existingOnlinePath(packDir, item)
        if (existing != null && !overwrite) {
            return@runCatching existing.toItem(safePack, readStats(), readTitles(), PanelSource.IMPORTED)
        }
        val identity = onlineIdentity(item)
        val temporary = packDir / "$identity.part"
        try {
            input.use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
            require(Files.size(temporary) > 0L) { "服务器未返回表情数据" }
            val extension = MediaFileTypeDetector.detectImage(temporary)?.extension
                ?: throw IllegalArgumentException("服务器返回了不支持的图片格式")
            val destination = packDir / "$identity.$extension"
            runCatching {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING) }
            if (existing != null && existing != destination) existing.deleteIfExists()
            destination.toItem(safePack, readStats(), readTitles(), PanelSource.IMPORTED)
        } finally {
            temporary.deleteIfExists()
        }
    }

    fun hasOnlineSticker(packName: String, item: StickerItem): Boolean = runCatching {
        existingOnlinePath(packPath(requirePackName(packName)), item) != null
    }.getOrDefault(false)

    fun hasTelegramSticker(packName: String, fileUniqueId: String): Boolean = runCatching {
        existingStablePath(
            packPath(requirePackName(packName)),
            telegramIdentity(fileUniqueId),
        ) != null
    }.getOrDefault(false)

    fun hasWeChatSticker(packName: String, md5: String): Boolean = runCatching {
        existingStablePath(
            packPath(requirePackName(packName)),
            weChatIdentity(md5),
        ) != null
    }.getOrDefault(false)

    fun importTelegramSticker(
        packName: String,
        fileUniqueId: String,
        input: InputStream,
    ): Result<StickerItem> = importStableSticker(
        packName = packName,
        identity = telegramIdentity(fileUniqueId),
        input = input,
        emptyDataMessage = "Telegram 未返回表情数据",
        unsupportedFormatMessage = "Telegram 表情转换结果格式不受支持",
    )

    fun importWeChatSticker(
        packName: String,
        md5: String,
        input: InputStream,
    ): Result<StickerItem> = importStableSticker(
        packName = packName,
        identity = weChatIdentity(md5),
        input = input,
        emptyDataMessage = "微信未返回表情数据",
        unsupportedFormatMessage = "微信表情导出结果格式不受支持",
    )

    private fun importStableSticker(
        packName: String,
        identity: String,
        input: InputStream,
        emptyDataMessage: String,
        unsupportedFormatMessage: String,
    ): Result<StickerItem> = runCatching {
        val safePack = requirePackName(packName)
        val packDir = packPath(safePack).also { it.createDirectories() }
        existingStablePath(packDir, identity)?.let { existing ->
            return@runCatching existing.toItem(
                safePack,
                readStats(),
                readTitles(),
                PanelSource.IMPORTED,
            )
        }
        val temporary = packDir / "$identity.part"
        try {
            input.use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
            require(Files.size(temporary) > 0L) { emptyDataMessage }
            val extension = MediaFileTypeDetector.detectImage(temporary)?.extension
                ?: throw IllegalArgumentException(unsupportedFormatMessage)
            val destination = packDir / "$identity.$extension"
            runCatching {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING) }
            destination.toItem(safePack, readStats(), readTitles(), PanelSource.IMPORTED)
        } finally {
            temporary.deleteIfExists()
        }
    }

    fun deleteTelegramSticker(packName: String, fileUniqueId: String): Result<Unit> = runCatching {
        val packDir = packPath(requirePackName(packName))
        val path = existingStablePath(packDir, telegramIdentity(fileUniqueId))
            ?: return@runCatching
        deleteSticker(path.absolutePathString()).getOrThrow()
    }

    fun detectImageExtension(data: ByteArray): String? =
        MediaFileTypeDetector.detectImage(data)?.extension

    fun setCustomTitle(filePath: String, title: String): Result<Unit> = runCatching {
        val path = requireLocalSticker(filePath)
        val titles = readTitles().toMutableMap()
        val normalized = title.trim()
        if (normalized.isBlank()) titles.remove(path.absolutePathString())
        else titles[path.absolutePathString()] = normalized
        atomicWrite(titlesFile, DefaultJson.encodeToString(titles))
    }

    fun setPackCover(filePath: String): Result<Unit> = runCatching {
        val path = requireLocalSticker(filePath)
        val covers = readCovers().toMutableMap()
        covers[path.parent.name] = path.name
        atomicWrite(coversFile, DefaultJson.encodeToString(covers))
    }

    fun deleteSticker(filePath: String): Result<Unit> =
        deleteStickers(listOf(filePath)).map { }

    fun deleteStickers(filePaths: List<String>): Result<Int> = runCatching {
        val paths = filePaths.map(::requireLocalSticker).distinct()
        require(paths.isNotEmpty()) { "没有选择表情" }
        paths.forEach { path -> require(path.deleteIfExists()) { "表情不存在" } }

        val deletedPaths = paths.mapTo(hashSetOf()) { it.absolutePathString() }
        atomicWrite(
            recentsFile,
            DefaultJson.encodeToString(readRecentPaths().filterNot { it in deletedPaths }),
        )
        atomicWrite(
            statsFile,
            DefaultJson.encodeToString(readStats().filterKeys { it !in deletedPaths }),
        )
        atomicWrite(
            titlesFile,
            DefaultJson.encodeToString(readTitles().filterKeys { it !in deletedPaths }),
        )

        val deletedCovers = paths.mapTo(hashSetOf()) { it.parent.name to it.name }
        atomicWrite(
            coversFile,
            DefaultJson.encodeToString(
                readCovers().filterNot { (packName, fileName) ->
                    (packName to fileName) in deletedCovers
                },
            ),
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

    fun recordRecent(filePath: String) {
        val current = readRecentPaths().toMutableList().apply {
            remove(filePath)
            add(0, filePath)
            val limit = PanelSettings.stickerMaxHistory.coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (size > limit) subList(limit, size).clear()
        }
        atomicWrite(recentsFile, DefaultJson.encodeToString(current))
        val stats = readStats().toMutableMap()
        val previous = stats[filePath] ?: StickerStats()
        stats[filePath] = previous.copy(
            sendCount = previous.sendCount + 1,
            lastSentAt = System.currentTimeMillis(),
        )
        atomicWrite(statsFile, DefaultJson.encodeToString(stats))
    }

    fun recordOnlineRecent(item: StickerItem) {
        val remoteKey = recentKey(item).takeIf { item.remoteObjectId != null } ?: return
        val previous = readOnlineRecents().firstOrNull { recentKey(it) == remoteKey }
        val recorded = item.copy(
            source = PanelSource.RECENT,
            packId = RECENT_PACK_ID,
            sendCount = (previous?.sendCount ?: item.sendCount) + 1,
            lastSentAt = System.currentTimeMillis(),
        )
        val current = buildList {
            add(recorded)
            addAll(readOnlineRecents().filterNot { recentKey(it) == remoteKey })
        }.take(PanelSettings.stickerMaxHistory.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        atomicWrite(onlineRecentsFile, DefaultJson.encodeToString(current))
    }

    private fun readRecentPaths(): List<String> {
        if (recentsFile.notExists()) return emptyList()
        return runCatching {
            DefaultJson.decodeFromString<List<String>>(recentsFile.readText())
                .filter { it.asPath.isRegularFile() }
        }.getOrDefault(emptyList())
    }

    private fun readOnlineRecents(): List<StickerItem> {
        if (onlineRecentsFile.notExists()) return emptyList()
        return runCatching {
            DefaultJson.decodeFromString<List<StickerItem>>(onlineRecentsFile.readText())
                .filter { it.localPath == null && it.remoteObjectId != null }
                // Older online-recents records only stored the thumbnail URL. Preserve those
                // records while upgrading their preview target to FunBox's original object URL.
                .map { item ->
                    if (!item.imageUrl.isNullOrBlank()) item
                    else item.copy(imageUrl = item.thumbnailUrl?.replace("/vfile/thumb/", "/vfile/image/"))
                }
        }.getOrDefault(emptyList())
    }

    private fun recentKey(item: StickerItem): String = item.remoteObjectId ?: item.id

    private fun readStats(): Map<String, StickerStats> {
        if (statsFile.notExists()) return emptyMap()
        return runCatching {
            DefaultJson.decodeFromString<Map<String, StickerStats>>(statsFile.readText())
        }.getOrDefault(emptyMap())
    }

    private fun readTitles(): Map<String, String> {
        if (titlesFile.notExists()) return emptyMap()
        return runCatching {
            DefaultJson.decodeFromString<Map<String, String>>(titlesFile.readText())
        }.getOrDefault(emptyMap())
    }

    private fun readCovers(): Map<String, String> {
        if (coversFile.notExists()) return emptyMap()
        return runCatching {
            DefaultJson.decodeFromString<Map<String, String>>(coversFile.readText())
        }.getOrDefault(emptyMap())
    }

    private fun readOrders(): PanelCustomOrders {
        if (ordersFile.notExists()) return PanelCustomOrders()
        return runCatching {
            DefaultJson.decodeFromString<PanelCustomOrders>(ordersFile.readText())
        }.getOrDefault(PanelCustomOrders())
    }

    private fun readOnlinePackSources(): Map<String, String> {
        if (onlinePackSourcesFile.notExists()) return emptyMap()
        return runCatching {
            DefaultJson.decodeFromString<Map<String, String>>(onlinePackSourcesFile.readText())
                .filterValues(String::isNotBlank)
        }.getOrDefault(emptyMap())
    }

    private fun stickerComparator(
        packName: String,
        stats: Map<String, StickerStats>,
        orders: PanelCustomOrders,
    ): Comparator<Path> {
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER, Path::name)
        return when (PanelSettings.stickerItemSortMode) {
            LocalSortMode.NAME -> byName
            LocalSortMode.MODIFIED -> compareByDescending<Path>(::lastModified).then(byName)
            LocalSortMode.RECENT -> compareByDescending<Path> {
                stats[it.absolutePathString()]?.lastSentAt ?: 0L
            }.then(byName)

            LocalSortMode.FREQUENT -> compareByDescending<Path> {
                stats[it.absolutePathString()]?.sendCount ?: 0L
            }.then(byName)

            LocalSortMode.CUSTOM -> compareBy<Path> {
                customOrderIndex(orders.items[packName], it.name)
            }.then(byName)
        }
    }

    private fun packComparator(orders: PanelCustomOrders): Comparator<StickerPack> {
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER, StickerPack::title)
        return when (PanelSettings.stickerPackSortMode) {
            LocalSortMode.NAME -> byName
            LocalSortMode.MODIFIED -> compareByDescending<StickerPack> { pack ->
                maxOf(
                    lastModified(packPath(pack.id)),
                    pack.items.maxOfOrNull { it.localPath?.asPath?.let(::lastModified) ?: 0L } ?: 0L,
                )
            }.then(byName)

            LocalSortMode.RECENT -> compareByDescending<StickerPack> { pack ->
                pack.items.maxOfOrNull(StickerItem::lastSentAt) ?: 0L
            }.then(byName)

            LocalSortMode.FREQUENT -> compareByDescending<StickerPack> { pack ->
                pack.items.sumOf(StickerItem::sendCount)
            }.then(byName)

            LocalSortMode.CUSTOM -> compareBy<StickerPack> {
                customOrderIndex(orders.packs, it.id)
            }.then(byName)
        }
    }

    private fun lastModified(path: Path): Long =
        runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)

    private fun Path.toItem(
        packId: String,
        stats: Map<String, StickerStats>,
        titles: Map<String, String>,
        source: PanelSource,
    ): StickerItem {
        val path = absolutePathString()
        val itemStats = stats[path] ?: StickerStats()
        return StickerItem(
            id = md5(path),
            title = nameWithoutExtension,
            customTitle = titles[path],
            localPath = path,
            source = source,
            packId = packId,
            sendCount = itemStats.sendCount,
            lastSentAt = itemStats.lastSentAt,
        )
    }

    private fun isStickerFile(path: Path) =
        path.isRegularFile() && !path.name.endsWith(".part") && MediaFileTypeDetector.detectImage(path) != null

    private fun sanitizeName(value: String) = value.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun requirePackName(value: String): String {
        val name = sanitizeName(value)
        require(name.isNotBlank() && name !in reservedNames) { "表情包名称不可用" }
        return name
    }

    private fun packPath(name: String): Path {
        val root = PanelPaths.stickerPanelDir.toAbsolutePath().normalize()
        return root.resolve(name).normalize().also { path ->
            require(path.parent == root) { "表情包路径无效" }
        }
    }

    private fun requireLocalSticker(value: String): Path {
        val root = PanelPaths.stickerPanelDir.toAbsolutePath().normalize()
        val path = value.asPath.toAbsolutePath().normalize()
        require(path.startsWith(root) && path.parent != root && path.isRegularFile()) { "表情路径无效" }
        return path
    }

    private fun importedFileStem(value: String, fallback: String): String {
        val safeName = sanitizeName(value).ifBlank { fallback }
        return safeName.substringBeforeLast('.', safeName).ifBlank { fallback }
    }

    private fun onlineIdentity(item: StickerItem): String =
        sanitizeName(item.remoteObjectId ?: item.id).ifBlank { "sticker" }.take(96)

    private fun telegramIdentity(fileUniqueId: String): String =
        "telegram_" + fileUniqueId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            .ifBlank { "sticker" }
            .take(80)

    private fun weChatIdentity(md5: String): String {
        require(md5.matches(Regex("[A-Fa-f0-9]{32}"))) { "微信表情 MD5 无效" }
        return "wechat_${md5.lowercase()}"
    }

    private fun existingOnlinePath(packDir: Path, item: StickerItem): Path? {
        if (!packDir.isDirectory()) return null
        val identity = onlineIdentity(item)
        return existingStablePath(packDir, identity)
    }

    private fun existingStablePath(packDir: Path, identity: String): Path? {
        if (!packDir.isDirectory()) return null
        return packDir.listDirectoryEntries().firstOrNull { path ->
            path.isRegularFile() &&
                    (path.name == identity || path.name.startsWith("$identity.")) &&
                    isStickerFile(path) && Files.size(path) > 0L
        }
    }

    private fun moveImportedFile(source: Path, destination: Path) {
        runCatching {
            Files.move(
                source,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun uniquePath(dir: Path, fileName: String): Path {
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

    @Synchronized
    private fun atomicWrite(path: Path, value: String) {
        val temporary = path.resolveSibling("${path.name}.tmp")
        try {
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
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun migratePathPrefix(source: Path, destination: Path) {
        val sourcePrefix = source.toAbsolutePath().normalize()
        val destinationPrefix = destination.toAbsolutePath().normalize()
        val recentPaths = readRecentPaths().map { value ->
            migratePath(value, sourcePrefix, destinationPrefix)
        }
        atomicWrite(recentsFile, DefaultJson.encodeToString(recentPaths))

        val migratedStats = readStats().mapKeys { (value, _) ->
            migratePath(value, sourcePrefix, destinationPrefix)
        }
        atomicWrite(statsFile, DefaultJson.encodeToString(migratedStats))
        val migratedTitles = readTitles().mapKeys { (value, _) ->
            migratePath(value, sourcePrefix, destinationPrefix)
        }
        atomicWrite(titlesFile, DefaultJson.encodeToString(migratedTitles))

        val covers = readCovers().toMutableMap()
        covers.remove(source.name)?.let { cover -> covers[destination.name] = cover }
        atomicWrite(coversFile, DefaultJson.encodeToString(covers))

        val onlinePackSources = readOnlinePackSources().toMutableMap()
        onlinePackSources.remove(source.name)?.let { onlinePackId ->
            onlinePackSources[destination.name] = onlinePackId
        }
        atomicWrite(onlinePackSourcesFile, DefaultJson.encodeToString(onlinePackSources))

        val orders = readOrders()
        atomicWrite(
            ordersFile,
            DefaultJson.encodeToString(
                orders.copy(
                    packs = orders.packs.map { if (it == source.name) destination.name else it },
                    items = orders.items.toMutableMap().apply {
                        remove(source.name)?.let { put(destination.name, it) }
                    },
                ),
            ),
        )
    }

    private fun removePathPrefixFromMetadata(directory: Path) {
        val prefix = directory.toAbsolutePath().normalize()
        val recentPaths = readRecentPaths().filterNot { value -> pathIsInside(value, prefix) }
        atomicWrite(recentsFile, DefaultJson.encodeToString(recentPaths))
        val retainedStats = readStats().filterKeys { value -> !pathIsInside(value, prefix) }
        atomicWrite(statsFile, DefaultJson.encodeToString(retainedStats))
        val retainedTitles = readTitles().filterKeys { value -> !pathIsInside(value, prefix) }
        atomicWrite(titlesFile, DefaultJson.encodeToString(retainedTitles))
        atomicWrite(
            coversFile,
            DefaultJson.encodeToString(readCovers().filterKeys { it != directory.name }),
        )
        atomicWrite(
            onlinePackSourcesFile,
            DefaultJson.encodeToString(readOnlinePackSources() - directory.name),
        )
        val orders = readOrders()
        atomicWrite(
            ordersFile,
            DefaultJson.encodeToString(
                orders.copy(
                    packs = orders.packs.filterNot { it == directory.name },
                    items = orders.items - directory.name,
                ),
            ),
        )
    }

    private fun pathIsInside(value: String, directory: Path): Boolean = runCatching {
        value.asPath.toAbsolutePath().normalize().startsWith(directory)
    }.getOrDefault(false)

    private fun migratePath(
        value: String,
        sourcePrefix: Path,
        destinationPrefix: Path,
    ): String = runCatching {
        val path = value.asPath.toAbsolutePath().normalize()
        if (path.startsWith(sourcePrefix)) destinationPrefix.resolve(sourcePrefix.relativize(path)).toString() else value
    }.getOrDefault(value)

    private fun md5(value: String) = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private val reservedNames = setOf(".", "..")
}
