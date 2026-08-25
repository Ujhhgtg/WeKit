package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentResolver
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.PickRootTelegramStickerSetsContract
import dev.ujhhgtg.wekit.activity.RootTelegramStickerSetsResult
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelPaths
import dev.ujhhgtg.wekit.features.items.chat.panel.PickedPanelFile
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerItem
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerPack
import dev.ujhhgtg.wekit.features.items.chat.panel.listPanelTreeFiles
import dev.ujhhgtg.wekit.features.items.chat.panel.pickPanelDirectory
import dev.ujhhgtg.wekit.features.items.chat.panel.pickPanelFile
import dev.ujhhgtg.wekit.features.items.chat.panel.pickPanelFiles
import dev.ujhhgtg.wekit.features.items.chat.panel.service.FunBoxServiceClient
import dev.ujhhgtg.wekit.features.items.chat.panel.service.FunBoxStickerRepository
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.StickerOnlineSourceRecoveryProgress
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.StickerOnlineSourceRecoveryResult
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.StickerPanelRepository
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramInstalledStickerSet
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramStickerDatabase
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramStickerPackRepository
import dev.ujhhgtg.wekit.ui.panel.StickerImportMode
import dev.ujhhgtg.wekit.ui.panel.StickerPanelActions
import dev.ujhhgtg.wekit.ui.panel.TelegramDatabaseSource
import dev.ujhhgtg.wekit.ui.panel.WeChatStickerImportPhase
import dev.ujhhgtg.wekit.ui.panel.WeChatStickerImportProgress
import dev.ujhhgtg.wekit.ui.panel.showStickerPanelSheet
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.reflection.bool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.inputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.minutes

// Entry implementation in ChatFooterHooks.
object StickerPanel : SwitchFeature() {

    override val technicalId = "表情面板"
    override val nameRes = R.string.feature_sticker_panel_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_sticker_panel_description
    fun openPanel(anchor: View) {
        showStickerPanelSheet(
            context = anchor.context,
            actions = StickerPanelActions(
                reloadLocal = ::loadLocalPacks,
                importSticker = { packId, mode, onStarted, onComplete ->
                    when (mode) {
                        StickerImportMode.WECHAT_CUSTOM -> Unit

                        StickerImportMode.MULTIPLE_FILES -> pickPanelFiles(
                            anchor.context,
                            STICKER_MIME_TYPES,
                        ) { files, activity ->
                            onStarted()
                            CoroutineScope(Dispatchers.IO).launch {
                                val result = importStickerBatch(
                                    packId,
                                    files,
                                    activity.contentResolver,
                                )
                                withContext(Dispatchers.Main) {
                                    onComplete(result)
                                    activity.finish()
                                }
                            }
                        }

                        StickerImportMode.DIRECTORY -> pickPanelDirectory(anchor.context) { treeUri, activity ->
                            onStarted()
                            CoroutineScope(Dispatchers.IO).launch {
                                val result = runCatching {
                                    listPanelTreeFiles(activity.contentResolver, treeUri)
                                }.mapCatching { files ->
                                    importStickerBatch(
                                        packId,
                                        files,
                                        activity.contentResolver,
                                    ).getOrThrow()
                                }
                                withContext(Dispatchers.Main) {
                                    onComplete(result)
                                    activity.finish()
                                }
                            }
                        }

                        StickerImportMode.TELEGRAM_SINGLE,
                        StickerImportMode.TELEGRAM_BATCH,
                            -> Unit
                    }
                },
                importWeChatCustomStickers = ::importWeChatCustomStickers,
                importTelegramStickerSet = TelegramStickerPackRepository::importStickerSet,
                pickTelegramStickerSets = { source, onComplete ->
                    pickTelegramStickerSets(anchor, source, onComplete)
                },
                loadImportedTelegramStickerSetNames =
                    TelegramStickerPackRepository::importedStickerSetNames,
                createPack = { name -> withContext(Dispatchers.IO) { StickerPanelRepository.createPack(name) } },
                renamePack = StickerPanelRepository::renamePack,
                deletePack = StickerPanelRepository::deletePack,
                loadOnlinePacks = FunBoxStickerRepository::loadCatalog,
                loadMyUploads = FunBoxStickerRepository::loadMyUploads,
                loadOnlineItems = FunBoxStickerRepository::loadPack,
                searchOnline = FunBoxStickerRepository::searchText,
                pickSimilarityImage = { onComplete ->
                    pickPanelFile(anchor.context, arrayOf("image/*")) { _, uri, activity ->
                        CoroutineScope(Dispatchers.IO).launch {
                            val result = runCatching {
                                activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: error(localizedChatString(R.string.chat_sticker_selected_image_read_failed))
                            }.mapCatching { bytes ->
                                require(bytes.isNotEmpty()) { localizedChatString(R.string.chat_sticker_selected_image_empty) }
                                bytes
                            }
                            withContext(Dispatchers.Main) {
                                onComplete(result)
                                activity.finish()
                            }
                        }
                    }
                },
                loadSimilarityImage = ::resolveStickerBytes,
                searchSimilar = FunBoxStickerRepository::searchSimilar,
                uploadPack = FunBoxStickerRepository::uploadPack,
                setCustomTitle = StickerPanelRepository::setCustomTitle,
                setPackCover = StickerPanelRepository::setPackCover,
                deleteSticker = StickerPanelRepository::deleteSticker,
                deleteStickers = StickerPanelRepository::deleteStickers,
                savePackOrder = StickerPanelRepository::savePackOrder,
                saveItemOrder = StickerPanelRepository::saveItemOrder,
                ensurePack = { name -> withContext(Dispatchers.IO) { StickerPanelRepository.ensurePack(name) } },
                setOnlinePackSource = { packId, onlinePackId ->
                    withContext(Dispatchers.IO) {
                        StickerPanelRepository.setOnlinePackSource(packId, onlinePackId)
                    }
                },
                recoverOnlinePackSources = ::recoverOnlinePackSources,
                saveOnlineSticker = { packId, item, overwrite ->
                    saveOnlineSticker(packId, item, overwrite)
                },
            ),
        ) { item ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val path = resolveStickerPath(item).getOrThrow()
                    val temporary = item.localPath == null
                    try {
                        check(WeMessageApi.sendSticker(WeCurrentConversationApi.value, path)) {
                            localizedChatString(R.string.chat_sticker_send_failed)
                        }
                        if (temporary) StickerPanelRepository.recordOnlineRecent(item)
                        else StickerPanelRepository.recordRecent(path)
                    } finally {
                        if (temporary) path.asPath.deleteIfExists()
                    }
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            PanelPaths.cleanupStalePanelCache()
        }
    }

    private fun loadLocalPacks() = buildList {
        val recents = StickerPanelRepository.getRecents()
        if (recents.items.isNotEmpty()) add(recents)
        addAll(StickerPanelRepository.loadPacks())
    }

    private suspend fun resolveStickerPath(item: StickerItem): Result<String> = withContext(Dispatchers.IO) {
        cancellableResult {
            item.localPath?.let { return@cancellableResult it }
            val bytes = resolveStickerBytes(item).getOrThrow()
            val extension = StickerPanelRepository.detectImageExtension(bytes)
                ?: error(localizedChatString(R.string.chat_sticker_server_unsupported_format))
            val path = PanelPaths.panelCacheDir / "sticker-${UUID.randomUUID()}.$extension"
            path.writeBytes(bytes)
            path.absolutePathString()
        }
    }

    private suspend fun resolveStickerBytes(item: StickerItem): Result<ByteArray> = withContext(Dispatchers.IO) {
        cancellableResult {
            val bytes = item.localPath?.asPath?.readBytes() ?: run {
                val objectId = item.remoteObjectId ?: error(localizedChatString(R.string.chat_sticker_object_unavailable))
                FunBoxServiceClient.downloadObject("image", objectId).getOrThrow()
            }
            require(bytes.isNotEmpty()) { localizedChatString(R.string.chat_sticker_image_empty) }
            bytes
        }
    }

    private suspend fun saveOnlineSticker(
        packId: String,
        item: StickerItem,
        overwrite: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            cancellableResult {
                if (!overwrite && StickerPanelRepository.hasOnlineSticker(packId, item)) return@cancellableResult
                val path = resolveStickerPath(item).getOrThrow()
                val temporary = item.localPath == null
                try {
                    Files.newInputStream(path.asPath).use { input ->
                        StickerPanelRepository.importOnlineSticker(item, packId, input, overwrite).getOrThrow()
                    }
                } finally {
                    if (temporary) path.asPath.deleteIfExists()
                }
            }.map { }
        }

    private suspend fun recoverOnlinePackSources(
        packIds: List<String>,
        onProgress: suspend (StickerOnlineSourceRecoveryProgress) -> Unit,
    ): Result<StickerOnlineSourceRecoveryResult> = withContext(Dispatchers.IO) {
        cancellableResult {
            val requestedIds = packIds.toSet()
            val localPacks = StickerPanelRepository.loadPacks().filter { it.id in requestedIds }
            val total = localPacks.size
            var recovered = 0
            var alreadyLinked = 0
            var unmatched = 0

            if (localPacks.all { it.onlineSourcePackId != null }) {
                alreadyLinked = total
                onProgress(StickerOnlineSourceRecoveryProgress(total, total, localizedChatString(R.string.chat_sticker_recovery_already_linked)))
                return@cancellableResult StickerOnlineSourceRecoveryResult(
                    selected = total,
                    recovered = 0,
                    alreadyLinked = alreadyLinked,
                    unmatched = 0,
                )
            }

            onProgress(StickerOnlineSourceRecoveryProgress(0, total, localizedChatString(R.string.chat_sticker_recovery_reading_catalog)))
            val catalog = FunBoxStickerRepository.loadCatalog().getOrThrow()
            val uploads = FunBoxStickerRepository.loadMyUploads().getOrDefault(emptyList())
            val onlinePacks = (catalog + uploads).distinctBy(StickerPack::id)

            localPacks.forEachIndexed { index, localPack ->
                onProgress(
                    StickerOnlineSourceRecoveryProgress(
                        completed = index,
                        total = total,
                        message = localizedChatString(R.string.chat_sticker_recovery_matching, localPack.title),
                    ),
                )
                when {
                    localPack.onlineSourcePackId != null -> alreadyLinked++
                    else -> {
                        val onlinePackId = onlinePacks.singleOrNull {
                            it.title.equals(localPack.title, ignoreCase = true)
                        }?.id
                        if (onlinePackId == null) {
                            unmatched++
                        } else {
                            StickerPanelRepository.setOnlinePackSource(localPack.id, onlinePackId).getOrThrow()
                            recovered++
                        }
                    }
                }
                onProgress(
                    StickerOnlineSourceRecoveryProgress(
                        completed = index + 1,
                        total = total,
                        message = localizedChatString(R.string.chat_sticker_recovery_processed, localPack.title),
                    ),
                )
            }

            StickerOnlineSourceRecoveryResult(
                selected = total,
                recovered = recovered,
                alreadyLinked = alreadyLinked,
                unmatched = unmatched,
            )
        }
    }

    private suspend inline fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private fun pickTelegramStickerSets(
        anchor: View,
        source: TelegramDatabaseSource,
        onComplete: (Result<List<TelegramInstalledStickerSet>>?) -> Unit,
    ) {
        TransparentActivity.launch(anchor.context) {
            when (source) {
                TelegramDatabaseSource.ROOT -> {
                    val launcher = registerForActivityResult(PickRootTelegramStickerSetsContract()) { result ->
                        onComplete(
                            when (result) {
                                is RootTelegramStickerSetsResult.Success -> Result.success(result.stickerSets)
                                is RootTelegramStickerSetsResult.Failure ->
                                    Result.failure(IllegalStateException(result.message))

                                RootTelegramStickerSetsResult.Cancelled -> null
                            },
                        )
                        finish()
                    }
                    launcher.launch(Unit)
                }

                TelegramDatabaseSource.MANUAL -> {
                    val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri == null) {
                            onComplete(null)
                            finish()
                            return@registerForActivityResult
                        }
                        CoroutineScope(Dispatchers.IO).launch {
                            val temporary = PanelPaths.panelCacheDir / "telegram-cache4-${UUID.randomUUID()}.db"
                            val result = runCatching {
                                contentResolver.openInputStream(uri)?.use { input ->
                                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
                                } ?: error(localizedChatString(R.string.chat_telegram_database_read_failed))
                                TelegramStickerDatabase.readInstalledSets(temporary).getOrThrow()
                            }
                            temporary.deleteIfExists()
                            withContext(Dispatchers.Main) {
                                onComplete(result)
                                finish()
                            }
                        }
                    }
                    launcher.launch(arrayOf("application/x-sqlite3", "application/octet-stream", "*/*"))
                }
            }
        }
    }

    private suspend fun importStickerBatch(
        packId: String,
        files: List<PickedPanelFile>,
        resolver: ContentResolver,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (files.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException(localizedChatString(R.string.chat_sticker_no_supported_selected)),
            )
        }

        var imported = 0
        val failures = mutableListOf<Pair<String, Throwable>>()
        files.forEach { file ->
            runCatching {
                val input = resolver.openInputStream(file.uri)
                    ?: error(localizedChatString(R.string.chat_sticker_file_read_failed))
                input.use {
                    StickerPanelRepository.importSticker(packId, file.name, it).getOrThrow()
                }
            }.onSuccess {
                imported++
            }.onFailure {
                failures += file.name to it
            }
        }

        if (failures.isEmpty()) {
            Result.success(Unit)
        } else {
            val first = failures.first()
            Result.failure(
                IllegalStateException(
                    localizedChatQuantity(
                        R.plurals.chat_sticker_import_partial_failed,
                        imported,
                        imported,
                        failures.size,
                        first.first,
                        first.second.message ?: localizedChatString(R.string.chat_unknown_error),
                    ),
                    first.second,
                ),
            )
        }
    }

    private suspend fun importWeChatCustomStickers(
        packName: String,
        onProgress: suspend (WeChatStickerImportProgress) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        cancellableResult {
            val importContext = currentCoroutineContext()
            onProgress(WeChatStickerImportProgress(WeChatStickerImportPhase.SCANNING))
            check(WeDatabaseApi.isReady) { localizedChatString(R.string.chat_wechat_sticker_database_not_ready) }
            val md5s = WeDatabaseApi.rawQuery(
                "SELECT md5 FROM EmojiInfo WHERE catalog = ? AND temp = ? ORDER BY reserved3 ASC",
                arrayOf(WECHAT_CUSTOM_EMOJI_CATALOG, 0),
            ).use { cursor ->
                val md5Column = cursor.getColumnIndexOrThrow("md5")
                buildList {
                    while (cursor.moveToNext()) {
                        importContext.ensureActive()
                        cursor.getString(md5Column)
                            ?.takeIf {
                                it.matches(WECHAT_EMOJI_MD5_REGEX) &&
                                    it.lowercase() !in EmojiGameControl.GAME_EMOJI_MD5S
                            }
                            ?.let(::add)
                    }
                }.distinct()
            }

            importContext.ensureActive()
            StickerPanelRepository.ensurePack(packName).getOrThrow()
            var imported = 0
            var unchanged = 0
            val failures = mutableListOf<Pair<String, Throwable>>()
            onProgress(
                WeChatStickerImportProgress(
                    phase = WeChatStickerImportPhase.IMPORTING,
                    total = md5s.size,
                ),
            )

            md5s.forEachIndexed { index, md5 ->
                importContext.ensureActive()
                if (StickerPanelRepository.hasWeChatSticker(packName, md5)) {
                    unchanged++
                } else {
                    cancellableResult {
                        check(cacheWeChatSticker(md5)) {
                            localizedChatString(R.string.chat_wechat_sticker_download_failed)
                        }
                        val exportedPath = WeMessageApi.saveStickerByMd5(
                            md5,
                            ".wekit-wechat-$md5-${UUID.randomUUID()}.gif",
                        ) ?: error(localizedChatString(R.string.chat_wechat_sticker_export_failed))
                        try {
                            importContext.ensureActive()
                            exportedPath.asPath.inputStream().use { input ->
                                StickerPanelRepository.importWeChatSticker(
                                    packName,
                                    md5,
                                    input,
                                ).getOrThrow()
                            }
                        } finally {
                            exportedPath.asPath.deleteIfExists()
                        }
                    }.onSuccess {
                        imported++
                    }.onFailure { error ->
                        failures += md5 to error
                    }
                }
                onProgress(
                    WeChatStickerImportProgress(
                        phase = WeChatStickerImportPhase.IMPORTING,
                        processed = index + 1,
                        total = md5s.size,
                        failed = failures.size,
                    ),
                )
            }

            if (md5s.isNotEmpty() && imported == 0 && unchanged == 0) {
                val firstFailure = failures.firstOrNull()?.second
                throw IllegalStateException(
                    localizedChatString(
                        R.string.chat_wechat_sticker_import_failed,
                        firstFailure?.message ?: localizedChatString(R.string.chat_unknown_error),
                    ),
                    firstFailure,
                )
            }

            if (md5s.isEmpty()) {
                localizedChatString(R.string.chat_wechat_sticker_no_custom)
            } else {
                localizedChatQuantity(
                    R.plurals.chat_wechat_sticker_import_result,
                    imported,
                    packName,
                    imported,
                    unchanged,
                    failures.size,
                )
            }
        }
    }

    private suspend fun cacheWeChatSticker(md5: String): Boolean =
        withTimeoutOrNull(WECHAT_EMOJI_CACHE_TIMEOUT) {
            suspendCancellableCoroutine { continuation ->
                val loadMethod = WeMessageApi.methodLoadEmojiFile.method
                val callbackType = loadMethod.parameterTypes[2]
                val callback = Proxy.newProxyInstance(
                    callbackType.classLoader,
                    arrayOf(callbackType),
                ) { proxy, callbackMethod, args ->
                    when (callbackMethod.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        "toString" -> "WeKitEmojiLoadCallback"
                        else -> {
                            if (
                                callbackMethod.parameterCount == 1 &&
                                callbackMethod.parameterTypes[0] == bool
                            ) {
                                val success = args?.getOrNull(0) as? Boolean ?: false
                                if (continuation.isActive) continuation.resume(success)
                            }
                            null
                        }
                    }
                }

                try {
                    val receiver = if (Modifier.isStatic(loadMethod.modifiers)) {
                        null
                    } else {
                        loadMethod.declaringClass.declaredFields
                            .firstOrNull {
                                Modifier.isStatic(it.modifiers) &&
                                    it.type == loadMethod.declaringClass
                            }
                            ?.apply { isAccessible = true }
                            ?.get(null)
                            ?: error("无法获取微信表情加载器")
                    }
                    val emojiInfo = WeServiceApi.getEmojiInfoByMd5(md5)
                    loadMethod.invoke(receiver, emojiInfo, true, callback)
                } catch (error: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error.cause ?: error)
                    }
                }
            }
        } ?: false

    private val STICKER_MIME_TYPES = arrayOf(
        "*/*",
    )

    private const val WECHAT_CUSTOM_EMOJI_CATALOG = 81
    private val WECHAT_EMOJI_CACHE_TIMEOUT = 2.minutes
    private val WECHAT_EMOJI_MD5_REGEX = Regex("[A-Fa-f0-9]{32}")
}
