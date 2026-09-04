package dev.ujhhgtg.wekit.features.items.chat.panel.sticker

import dev.ujhhgtg.wekit.utils.fs.moveReplacing
import kotlin.io.path.inputStream
import kotlin.io.path.moveTo
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.localizedChatString
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelPaths
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSettings
import dev.ujhhgtg.wekit.features.items.chat.panel.parallelForEachWithProgress
import dev.ujhhgtg.wekit.utils.TelegramStickerConverter
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

enum class TelegramStickerImportPhase {
    DOWNLOAD,
    CONVERSION,
}

data class TelegramStickerImportProgress(
    val phase: TelegramStickerImportPhase,
    val completed: Int,
    val total: Int,
    val currentItem: String? = null,
)

data class TelegramStickerImportResult(
    val packName: String,
    val total: Int,
    val imported: Int,
    val unchanged: Int,
    val failed: Int,
)

object TelegramStickerPackRepository {
    private const val TAG = "TelegramStickerImport"
    private val stickerSetLinkRegex = Regex(
        """^(?:https?://)?(?:www\.)?(?:t\.me|telegram\.me)/(?:addstickers|addemoji)/([A-Za-z0-9_]{1,64})(?:[/?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val stickerSetNameRegex = Regex("[A-Za-z0-9_]{1,64}")

    fun extractStickerSetName(value: String): String? {
        val input = value.trim()
        stickerSetLinkRegex.matchEntire(input)?.let { return it.groupValues[1] }
        return input.takeIf(stickerSetNameRegex::matches)
    }

    fun importedStickerSetNames(): Set<String> = runCatching {
        val localPackIds = StickerPanelRepository.loadPacks().mapTo(hashSetOf()) { it.id }
        PanelPaths.telegramStickerImportDir.listDirectoryEntries()
            .mapNotNull { directory ->
                val manifest = readManifest(directory / "manifest.json") ?: return@mapNotNull null
                manifest.setName.lowercase().takeIf {
                    manifest.localPackName.isNotBlank() &&
                            manifest.localPackName in localPackIds
                }
            }
            .toSet()
    }.getOrElse {
        WeLogger.w(TAG, "failed to read imported sticker sets", it)
        emptySet()
    }

    suspend fun importStickerSet(
        value: String,
        onProgress: suspend (TelegramStickerImportProgress) -> Unit,
    ): Result<TelegramStickerImportResult> = withContext(Dispatchers.IO) {
        try {
            val token = PanelSettings.telegramBotToken.trim()
            require(PanelSettings.isValidTelegramBotToken(token)) { localizedChatString(R.string.chat_telegram_token_invalid) }
            val removeRoundedVideoMask = PanelSettings.stickerRemoveRoundedVideoMask
            val tgsGifFrameRate = PanelSettings.stickerTgsGifFrameRate.coerceIn(
                PanelSettings.MIN_TGS_GIF_FRAME_RATE,
                PanelSettings.MAX_TGS_GIF_FRAME_RATE,
            )
            val downloadConcurrency = PanelSettings.effectivePanelDownloadConcurrency
            val conversionConcurrency = PanelSettings.effectivePanelConversionConcurrency
            val requestedName = extractStickerSetName(value)
                ?: throw IllegalArgumentException(localizedChatString(R.string.chat_telegram_pack_name_invalid))
            val stickerSet = TelegramStickerApiClient.getStickerSet(token, requestedName)
            val stickers = stickerSet.stickers.distinctBy(TelegramSticker::fileUniqueId)
            require(stickers.isNotEmpty()) { localizedChatString(R.string.chat_telegram_pack_empty) }

            val stagingDir = PanelPaths.telegramStickerImportDir / safePathSegment(stickerSet.name)
            val rawDir = (stagingDir / "raw").also { it.createDirectories() }
            val convertedDir = (stagingDir / "converted").also { it.createDirectories() }
            val manifestFile = stagingDir / "manifest.json"
            val oldManifest = readManifest(manifestFile)
            val packName = oldManifest
                ?.takeIf { it.setName == stickerSet.name }
                ?.localPackName
                ?.takeIf(String::isNotBlank)
                ?: createTelegramPack(stickerSet.title)
            StickerPanelRepository.ensurePack(packName).getOrThrow()

            val entries = ConcurrentHashMap(oldManifest?.entries.orEmpty())
            writeManifest(
                manifestFile,
                TelegramStickerManifest(
                    setName = stickerSet.name,
                    title = stickerSet.title,
                    localPackName = packName,
                    entries = entries.toMap(),
                ),
            )
            val failures = ConcurrentHashMap<String, String>()
            val downloaded = AtomicInteger()
            val imported = AtomicInteger()
            val unchanged = AtomicInteger()

            fun persistManifest() {
                writeManifest(
                    manifestFile,
                    TelegramStickerManifest(
                        setName = stickerSet.name,
                        title = stickerSet.title,
                        localPackName = packName,
                        entries = entries.toMap(),
                    ),
                )
            }

            WeLogger.i(
                TAG,
                        "starting set=${stickerSet.name} total=${stickers.size} " +
                        "downloadConcurrency=$downloadConcurrency " +
                        "conversionConcurrency=$conversionConcurrency",
            )
            onProgress(TelegramStickerImportProgress(TelegramStickerImportPhase.DOWNLOAD, 0, stickers.size))

            stickers.withIndex().parallelForEachWithProgress(
                maxConcurrency = downloadConcurrency,
                transform = { (index, sticker) ->
                    currentCoroutineContext().ensureActive()
                    val sourceFormat = sticker.sourceFormat()
                    val identity = safePathSegment(sticker.fileUniqueId)
                    val rawPath = rawDir / "$identity.${sourceFormat.extension}"
                    val existingLocal = StickerPanelRepository.hasTelegramSticker(
                        packName,
                        sticker.fileUniqueId,
                    )
                    if (existingLocal) {
                        unchanged.incrementAndGet()
                        entries[sticker.fileUniqueId] = TelegramStickerManifestEntry(
                            fileId = sticker.fileId,
                            sourceFormat = sourceFormat.name,
                            imported = true,
                        )
                    } else if (!rawPath.isRegularFile() || rawPath.fileSize() == 0L) {
                        try {
                            val remoteFile = TelegramStickerApiClient.getFile(token, sticker.fileId)
                            require(remoteFile.fileUniqueId == sticker.fileUniqueId) {
                                localizedChatString(R.string.chat_telegram_file_identity_mismatch)
                            }
                            TelegramStickerApiClient.downloadFile(
                                token,
                                remoteFile.filePath,
                                rawPath,
                                remoteFile.fileSize,
                            )
                            downloaded.incrementAndGet()
                            entries[sticker.fileUniqueId] = TelegramStickerManifestEntry(
                                fileId = sticker.fileId,
                                sourceFormat = sourceFormat.name,
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            failures[sticker.fileUniqueId] = error.userMessage()
                            WeLogger.w(
                                TAG,
                                "download failed item=$index type=${sourceFormat.name}: " +
                                        error.javaClass.simpleName,
                            )
                        }
                    }
                },
                onItemComplete = { completed, total, indexedSticker, _ ->
                    persistManifest()
                    onProgress(
                        TelegramStickerImportProgress(
                            TelegramStickerImportPhase.DOWNLOAD,
                            completed,
                            total,
                            indexedSticker.value.emoji,
                        ),
                    )
                },
            )

            onProgress(TelegramStickerImportProgress(TelegramStickerImportPhase.CONVERSION, 0, stickers.size))
            stickers.withIndex().parallelForEachWithProgress(
                maxConcurrency = conversionConcurrency,
                transform = { (index, sticker) ->
                    currentCoroutineContext().ensureActive()
                    if (!StickerPanelRepository.hasTelegramSticker(packName, sticker.fileUniqueId)) {
                        val sourceFormat = sticker.sourceFormat()
                        val identity = safePathSegment(sticker.fileUniqueId)
                        val rawPath = rawDir / "$identity.${sourceFormat.extension}"
                        if (!failures.containsKey(sticker.fileUniqueId)) {
                            try {
                                require(rawPath.isRegularFile() && rawPath.fileSize() > 0L) {
                                    localizedChatString(R.string.chat_telegram_sticker_unreadable)
                                }
                                val importPath = when (sourceFormat) {
                                    TelegramStickerSourceFormat.WEBP -> rawPath
                                    TelegramStickerSourceFormat.TGS,
                                    TelegramStickerSourceFormat.WEBM,
                                        -> convertSticker(
                                            sourceFormat,
                                            rawPath,
                                            convertedDir / when (sourceFormat) {
                                                TelegramStickerSourceFormat.TGS ->
                                                    "$identity.uwasm-fps$tgsGifFrameRate.gif"
                                                TelegramStickerSourceFormat.WEBM ->
                                                    "$identity.${if (removeRoundedVideoMask) "maskless" else "original"}.gif"
                                                TelegramStickerSourceFormat.WEBP -> error("静态 WebP 不需要转换")
                                            },
                                            removeRoundedVideoMask,
                                            tgsGifFrameRate,
                                        )
                                }
                                importPath.inputStream().use { input ->
                                    StickerPanelRepository.importTelegramSticker(
                                        packName,
                                        sticker.fileUniqueId,
                                        input,
                                    ).getOrThrow()
                                }
                                imported.incrementAndGet()
                                entries[sticker.fileUniqueId] = TelegramStickerManifestEntry(
                                    fileId = sticker.fileId,
                                    sourceFormat = sourceFormat.name,
                                    imported = true,
                                )
                                rawPath.deleteIfExists()
                                if (importPath != rawPath) importPath.deleteIfExists()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                failures[sticker.fileUniqueId] = error.userMessage()
                                WeLogger.w(
                                    TAG,
                                    "conversion failed item=$index type=${sourceFormat.name}",
                                    error,
                                )
                            }
                        }
                    }
                },
                onItemComplete = { completed, total, indexedSticker, _ ->
                    persistManifest()
                    onProgress(
                        TelegramStickerImportProgress(
                            TelegramStickerImportPhase.CONVERSION,
                            completed,
                            total,
                            indexedSticker.value.emoji,
                        ),
                    )
                },
            )

            val currentIds = stickers.mapTo(hashSetOf(), TelegramSticker::fileUniqueId)
            if (failures.isEmpty()) {
                entries.keys.filterNot { it in currentIds }.forEach { staleId ->
                    StickerPanelRepository.deleteTelegramSticker(packName, staleId).getOrThrow()
                }
            }
            val reconciledEntries = if (failures.isEmpty()) {
                entries.filterKeys { it in currentIds }
            } else {
                entries.toMap()
            }
            writeManifest(
                manifestFile,
                TelegramStickerManifest(
                    setName = stickerSet.name,
                    title = stickerSet.title,
                    localPackName = packName,
                    entries = reconciledEntries,
                ),
            )
            if (failures.isEmpty()) cleanupStaleStaging(rawDir, convertedDir, stickers)
            val downloadedCount = downloaded.get()
            val importedCount = imported.get()
            val unchangedCount = unchanged.get()
            WeLogger.i(
                TAG,
                "completed set=${stickerSet.name} total=${stickers.size} downloaded=$downloadedCount " +
                        "imported=$importedCount unchanged=$unchangedCount failed=${failures.size}",
            )
            if (importedCount + unchangedCount == 0) {
                val first = failures.values.firstOrNull() ?: localizedChatString(R.string.chat_telegram_no_importable_stickers)
                throw IllegalStateException(localizedChatString(R.string.chat_telegram_pack_import_failed, first))
            }
            Result.success(
                TelegramStickerImportResult(
                    packName = packName,
                    total = stickers.size,
                    imported = importedCount,
                    unchanged = unchangedCount,
                    failed = failures.size,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            WeLogger.w(TAG, "import failed: ${error.javaClass.simpleName}")
            Result.failure(error)
        }
    }

    private suspend fun convertSticker(
        format: TelegramStickerSourceFormat,
        source: Path,
        destination: Path,
        removeRoundedVideoMask: Boolean,
        tgsGifFrameRate: Int,
    ): Path {
        if (destination.isRegularFile() && withContext(Dispatchers.IO) {
                destination.fileSize()
            } > 0L) return destination
        val partial = destination.resolveSibling("${destination.fileName}.part")
        partial.deleteIfExists()
        val result = when (format) {
            TelegramStickerSourceFormat.TGS -> TelegramStickerConverter.tgsToGif(
                source,
                partial,
                tgsGifFrameRate,
            )
            TelegramStickerSourceFormat.WEBM -> TelegramStickerConverter.webmToGif(
                source,
                partial,
                removeRoundedVideoMask,
            )
            TelegramStickerSourceFormat.WEBP -> error("静态 WebP 不需要转换")
        }
        result.getOrThrow()
        currentCoroutineContext().ensureActive()
        partial.moveReplacing(destination)
        return destination
    }

    private fun cleanupStaleStaging(
        rawDir: Path,
        convertedDir: Path,
        stickers: List<TelegramSticker>,
    ) {
        val activePrefixes = stickers.mapTo(hashSetOf()) { safePathSegment(it.fileUniqueId) }
        listOf(rawDir, convertedDir).forEach { directory ->
            directory.listDirectoryEntries().forEach { path ->
                if (activePrefixes.none { prefix -> path.name.startsWith("$prefix.") }) {
                    path.deleteIfExists()
                }
            }
        }
    }

    private fun readManifest(path: Path): TelegramStickerManifest? {
        if (path.notExists()) return null
        return runCatching { DefaultJson.decodeFromString<TelegramStickerManifest>(path.readText()) }
            .onFailure { WeLogger.w(TAG, "ignored invalid manifest: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private fun createTelegramPack(title: String): String {
        val candidates = sequence {
            yield(title)
            yield("$title (Telegram)")
            var suffix = 2
            while (true) yield("$title (Telegram ${suffix++})")
        }
        candidates.take(100).forEach { candidate ->
            StickerPanelRepository.createPack(candidate).getOrNull()?.let { return it }
        }
        error(localizedChatString(R.string.chat_telegram_local_pack_create_failed))
    }

    @Synchronized
    private fun writeManifest(path: Path, manifest: TelegramStickerManifest) {
        path.parent?.createDirectories()
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        try {
            temporary.writeText(DefaultJson.encodeToString(manifest))
            temporary.moveReplacing(path)
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun TelegramSticker.sourceFormat(): TelegramStickerSourceFormat = when {
        isVideo -> TelegramStickerSourceFormat.WEBM
        isAnimated -> TelegramStickerSourceFormat.TGS
        else -> TelegramStickerSourceFormat.WEBP
    }

    private fun safePathSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "sticker_set" }.take(96)

    private fun Throwable.userMessage(): String = message
        ?.replace(Regex("https://api\\.telegram\\.org/\\S+"), "Telegram API")
        ?.take(240)
        ?: javaClass.simpleName
}

private enum class TelegramStickerSourceFormat(val extension: String) {
    WEBP("webp"),
    TGS("tgs"),
    WEBM("webm"),
}

@Serializable
private data class TelegramStickerManifest(
    val setName: String,
    val title: String,
    val localPackName: String,
    val entries: Map<String, TelegramStickerManifestEntry> = emptyMap(),
)

@Serializable
private data class TelegramStickerManifestEntry(
    val fileId: String,
    val sourceFormat: String,
    val imported: Boolean = false,
)
