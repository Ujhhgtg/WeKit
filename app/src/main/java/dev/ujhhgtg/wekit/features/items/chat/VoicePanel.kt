package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.chat.panel.CloneExample
import dev.ujhhgtg.wekit.features.items.chat.panel.CloneVoice
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelPaths
import dev.ujhhgtg.wekit.features.items.chat.panel.PickedPanelFile
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceItem
import dev.ujhhgtg.wekit.features.items.chat.panel.VoicePreview
import dev.ujhhgtg.wekit.features.items.chat.panel.listPanelTreeFiles
import dev.ujhhgtg.wekit.features.items.chat.panel.pickPanelDirectory
import dev.ujhhgtg.wekit.features.items.chat.panel.pickPanelFile
import dev.ujhhgtg.wekit.features.items.chat.panel.pickPanelFiles
import dev.ujhhgtg.wekit.features.items.chat.panel.service.FunBoxCloneVoiceRepository
import dev.ujhhgtg.wekit.features.items.chat.panel.service.FunBoxServiceClient
import dev.ujhhgtg.wekit.features.items.chat.panel.service.FunBoxVoiceRepository
import dev.ujhhgtg.wekit.features.items.chat.panel.voice.CloneVoiceRepository
import dev.ujhhgtg.wekit.features.items.chat.panel.voice.VoicePanelRepository
import dev.ujhhgtg.wekit.features.items.chat.panel.voice.VoiceProviderRegistry
import dev.ujhhgtg.wekit.ui.panel.VoiceImportMode
import dev.ujhhgtg.wekit.ui.panel.VoicePanelActions
import dev.ujhhgtg.wekit.ui.panel.showVoicePanelSheet
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.EdgeTtsClient
import dev.ujhhgtg.wekit.utils.MediaFileTypeDetector
import dev.ujhhgtg.wekit.utils.coerceToInt
import dev.ujhhgtg.wekit.utils.fs.asPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeBytes

internal data class EdgeTtsVoice(val id: String, @StringRes val titleRes: Int)

internal val EDGE_TTS_VOICES = listOf(
    EdgeTtsVoice("zh-CN-XiaoxiaoNeural", R.string.voice_edge_xiaoxiao),
    EdgeTtsVoice("zh-CN-XiaoyiNeural", R.string.voice_edge_xiaoyi),
    EdgeTtsVoice("zh-CN-YunxiNeural", R.string.voice_edge_yunxi),
    EdgeTtsVoice("zh-CN-YunyangNeural", R.string.voice_edge_yunyang),
    EdgeTtsVoice("zh-CN-YunjianNeural", R.string.voice_edge_yunjian),
    EdgeTtsVoice("zh-CN-YunxiaNeural", R.string.voice_edge_yunxia),
    EdgeTtsVoice("zh-CN-liaoning-XiaobeiNeural", R.string.voice_edge_xiaobei),
    EdgeTtsVoice("zh-CN-shaanxi-XiaoniNeural", R.string.voice_edge_xiaoni),
    EdgeTtsVoice("zh-HK-HiuMaanNeural", R.string.voice_edge_hiumaan),
    EdgeTtsVoice("zh-HK-WanLungNeural", R.string.voice_edge_wanlung),
    EdgeTtsVoice("zh-TW-HsiaoChenNeural", R.string.voice_edge_hsiaochen),
    EdgeTtsVoice("zh-TW-YunJheNeural", R.string.voice_edge_yunjhe),
    EdgeTtsVoice("en-US-AriaNeural", R.string.voice_edge_aria),
    EdgeTtsVoice("en-US-GuyNeural", R.string.voice_edge_guy),
    EdgeTtsVoice("ja-JP-NanamiNeural", R.string.voice_edge_nanami),
)

// Entry implementation in ChatFooterHooks.
object VoicePanel : SwitchFeature() {

    override val technicalId = "语音面板"
    override val nameRes = R.string.feature_voice_panel_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_voice_panel_description
    fun openPanel(anchor: View) {
        val context = anchor.context
        showVoicePanelSheet(
            context = context,
            actions = buildActions(context),
        )

        CoroutineScope(Dispatchers.IO).launch {
            PanelPaths.cleanupStalePanelCache()
        }
    }

    private fun buildActions(context: Context) = VoicePanelActions(
        reloadLocal = VoicePanelRepository::loadPacks,
        importVoice = { packId, mode, onStarted, onComplete ->
            when (mode) {
                VoiceImportMode.MULTIPLE_FILES -> pickPanelFiles(context, AUDIO_MIME_TYPES) { files, activity ->
                    onStarted()
                    CoroutineScope(Dispatchers.IO).launch {
                        val result = importVoiceBatch(packId, files, activity.contentResolver)
                        withContext(Dispatchers.Main) {
                            onComplete(result)
                            activity.finish()
                        }
                    }
                }

                VoiceImportMode.DIRECTORY -> pickPanelDirectory(context) { treeUri, activity ->
                    onStarted()
                    CoroutineScope(Dispatchers.IO).launch {
                        val result = runCatching {
                            listPanelTreeFiles(activity.contentResolver, treeUri)
                        }.mapCatching { files ->
                            importVoiceBatch(packId, files, activity.contentResolver).getOrThrow()
                        }
                        withContext(Dispatchers.Main) {
                            onComplete(result)
                            activity.finish()
                        }
                    }
                }
            }
        },
        createLocalPack = { name -> withContext(Dispatchers.IO) { VoicePanelRepository.createPack(name) } },
        renameLocalPack = { old, new -> withContext(Dispatchers.IO) { VoicePanelRepository.renamePack(old, new) } },
        deleteLocalPack = { withContext(Dispatchers.IO) { VoicePanelRepository.deletePack(it) } },
        deleteLocalVoices = { paths -> withContext(Dispatchers.IO) { VoicePanelRepository.deleteVoices(paths) } },
        savePackOrder = { withContext(Dispatchers.IO) { VoicePanelRepository.savePackOrder(it) } },
        saveItemOrder = { packId, paths ->
            withContext(Dispatchers.IO) { VoicePanelRepository.saveItemOrder(packId, paths) }
        },
        preview = ::resolveVoicePath,
        releasePreview = { preview ->
            if (preview.temporary) preview.path.asPath.deleteIfExists()
        },
        send = { sendVoice(WeCurrentConversationApi.value, it) },
        ensureLocalPack = { name, legacyName ->
            withContext(Dispatchers.IO) { VoicePanelRepository.ensurePack(name, legacyName) }
        },
        addToLocal = addToLocal@{ packId, item ->
            if (VoicePanelRepository.hasOnlineVoice(packId, item)) return@addToLocal Result.success(Unit)
            resolveVoicePath(item).mapCatching { path ->
                try {
                    Files.newInputStream(path.path.asPath).use { input ->
                        VoicePanelRepository.importOnlineVoice(packId, item, input).getOrThrow()
                    }
                } finally {
                    if (path.temporary) path.path.asPath.deleteIfExists()
                }
            }
        },
        synthesizeEdge = { text, voice -> synthesizeEdgeAndSend(WeCurrentConversationApi.value, text, voice) },
        synthesizeSystem = { text -> synthesizeSystemAndSend(context, WeCurrentConversationApi.value, text) },
        convertEdge = ::synthesizeEdgePreview,
        convertSystem = { text -> synthesizeSystemPreview(context, text) },
        loadClones = { withContext(Dispatchers.IO) { CloneVoiceRepository.load() } },
        selectedCloneId = { withContext(Dispatchers.IO) { CloneVoiceRepository.selectedId() } },
        selectClone = { withContext(Dispatchers.IO) { CloneVoiceRepository.select(it) } },
        deleteClone = { withContext(Dispatchers.IO) { CloneVoiceRepository.delete(it) } },
        importClone = { onStarted, onComplete -> importCloneFile(context, onStarted, onComplete) },
        importCloneFromVoice = { name, item ->
            resolveVoicePath(item).mapCatching { path ->
                val source = path.path.asPath
                try {
                    Files.newInputStream(source).use { input ->
                        CloneVoiceRepository.import(name, input, Files.size(source)).getOrThrow()
                    }
                } finally {
                    if (path.temporary) source.deleteIfExists()
                }
            }
        },
        synthesizeClone = { text, voice -> synthesizeCloneAndSend(WeCurrentConversationApi.value, text, voice) },
        convertClone = ::synthesizeClonePreview,
        sendConverted = { preview, title -> sendPreview(WeCurrentConversationApi.value, preview, title) },
        loadExampleGroups = FunBoxCloneVoiceRepository::exampleGroups,
        loadExamples = FunBoxCloneVoiceRepository::examples,
        previewExample = ::resolveExamplePath,
        addExample = { example ->
            withContext(Dispatchers.IO) {
                FunBoxCloneVoiceRepository.exampleAudio(example).mapCatching { bytes ->
                    CloneVoiceRepository.importBytes(example.title, bytes).getOrThrow()
                    Unit
                }
            }
        },
        loadCloneSharedPacks = {
            withContext(Dispatchers.IO) {
                val public = FunBoxVoiceRepository.listSharedPacks()
                val mine = FunBoxVoiceRepository.listMyPacks()
                when {
                    public.isFailure && mine.isFailure -> Result.failure(
                        public.exceptionOrNull() ?: mine.exceptionOrNull() ?: IllegalStateException(
                            localizedChatString(R.string.chat_voice_shared_packs_load_failed),
                        ),
                    )

                    else -> Result.success(
                        (public.getOrDefault(emptyList()) + mine.getOrDefault(emptyList())).distinctBy { it.id },
                    )
                }
            }
        },
        loadMySharedPacks = FunBoxVoiceRepository::listMyPacks,
        loadSharedPack = FunBoxVoiceRepository::loadSharedPack,
        createSharedPack = FunBoxVoiceRepository::createPack,
        renameSharedPack = FunBoxVoiceRepository::renamePack,
        deleteSharedPack = FunBoxVoiceRepository::deletePack,
        confirmSharedPack = FunBoxVoiceRepository::confirmPack,
        uploadSharedVoice = { packId, onStarted, onComplete ->
            pickPanelFile(context, AUDIO_MIME_TYPES) { name, uri, activity ->
                onStarted()
                CoroutineScope(Dispatchers.IO).launch {
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    val result = if (bytes == null) Result.failure(IllegalStateException(localizedChatString(R.string.chat_voice_selected_read_failed)))
                    else if (bytes.size > MAX_SHARED_VOICE_BYTES) Result.failure(IllegalArgumentException(localizedChatString(R.string.chat_voice_single_max_size)))
                    else {
                        val format = MediaFileTypeDetector.detectAudio(bytes)
                        if (format == null) Result.failure(IllegalArgumentException(localizedChatString(R.string.chat_voice_unsupported_format)))
                        else FunBoxVoiceRepository.uploadVoice(
                            packId,
                            VoiceItem(
                                id = name,
                                title = name.substringBeforeLast('.', name),
                                format = format.extension,
                            ),
                            bytes,
                        )
                    }
                    withContext(Dispatchers.Main) {
                        onComplete(result)
                        activity.finish()
                    }
                }
            }
        },
    )

    private suspend fun resolveVoicePath(item: VoiceItem): Result<VoicePreview> = withContext(Dispatchers.IO) {
        cancellableResult {
            item.localPath?.let { return@cancellableResult VoicePreview(it, temporary = false) }
            val bytes = if (item.remoteObjectId != null) {
                FunBoxServiceClient.downloadObject("voice", item.remoteObjectId).getOrThrow()
            } else {
                val provider = VoiceProviderRegistry.forItem(item) ?: error(localizedChatString(R.string.chat_voice_provider_unavailable))
                val resolved = provider.resolveAudio(item).getOrThrow()
                require(!resolved.remoteUrl.isNullOrBlank()) { localizedChatString(R.string.chat_voice_url_unavailable) }
                FunBoxServiceClient.download(requireNotNull(resolved.remoteUrl)).getOrThrow()
            }
            require(bytes.isNotEmpty()) { localizedChatString(R.string.chat_voice_server_empty) }
            val prefix = bytes.copyOfRange(0, minOf(bytes.size, 256)).toString(Charsets.UTF_8).trimStart()
            if (prefix.startsWith("{")) {
                val message = Regex("\"msg\"\\s*:\\s*\"([^\"]+)\"")
                    .find(prefix)?.groupValues?.getOrNull(1)
                error(message ?: localizedChatString(R.string.chat_voice_server_not_audio))
            }
            val format = MediaFileTypeDetector.detectAudio(bytes)
                ?: error(localizedChatString(R.string.chat_voice_server_unsupported_format))
            val path = PanelPaths.panelCacheDir / "voice-${UUID.randomUUID()}.${format.extension}"
            path.writeBytes(bytes)
            VoicePreview(path.absolutePathString(), temporary = true)
        }
    }

    private suspend fun resolveExamplePath(example: CloneExample): Result<VoicePreview> = withContext(Dispatchers.IO) {
        FunBoxCloneVoiceRepository.exampleAudio(example).mapCatching { bytes ->
            val format = MediaFileTypeDetector.detectAudio(bytes)
                ?: error(localizedChatString(R.string.chat_voice_example_unsupported_format))
            val path = PanelPaths.panelCacheDir / "example-${UUID.randomUUID()}.${format.extension}"
            path.writeBytes(bytes)
            VoicePreview(path.absolutePathString(), temporary = true)
        }
    }

    private suspend fun sendVoice(
        talker: String,
        item: VoiceItem,
        recordUsage: Boolean = true,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        cancellableResult {
            val preview = resolveVoicePath(item).getOrThrow()
            val resolvedPath = preview.path
            val source = resolvedPath.asPath
            val temporarySource = preview.temporary
            try {
                // Remote providers frequently expose a placeholder duration (RingDuoDuo, in
                // particular, reports the same value for every track). The downloaded file is
                // authoritative; only trust the cached metadata for local files.
                val durationMs = if (item.localPath == null) {
                    AudioUtils.getDurationMs(resolvedPath).coerceAtLeast(0L)
                } else {
                    item.durationMs.takeIf { it > 0 }
                        ?: AudioUtils.getDurationMs(resolvedPath).coerceAtLeast(0L)
                }
                val sourceFormat = MediaFileTypeDetector.detectAudio(source)
                    ?: error(localizedChatString(R.string.chat_voice_unsupported_format))
                val directSource = sourceFormat == MediaFileTypeDetector.AudioFormat.SILK ||
                        sourceFormat == MediaFileTypeDetector.AudioFormat.AMR
                val silkPath = if (directSource) source else PanelPaths.panelCacheDir / "send-${UUID.randomUUID()}.silk"
                try {
                    if (!directSource) require(AudioUtils.anyToSilk(resolvedPath, silkPath.absolutePathString())) { localizedChatString(R.string.chat_voice_convert_silk_failed) }
                    check(WeMessageApi.sendVoice(talker, silkPath.absolutePathString(), durationMs.coerceToInt())) { localizedChatString(R.string.chat_voice_send_failed) }
                    if (recordUsage) VoicePanelRepository.recordSent(item)
                    Unit
                } finally {
                    if (!directSource) silkPath.deleteIfExists()
                }
            } finally {
                if (temporarySource) source.deleteIfExists()
            }
        }
    }

    private suspend fun synthesizeEdgeAndSend(talker: String, text: String, voice: String): Result<Unit> =
        synthesizeAndSend(talker, "Edge TTS") { synthesizeEdgePreview(text, voice) }

    private suspend fun synthesizeSystemAndSend(context: Context, talker: String, text: String): Result<Unit> =
        synthesizeAndSend(talker, localizedChatString(R.string.chat_voice_system_tts)) { synthesizeSystemPreview(context, text) }

    private suspend fun synthesizeEdgePreview(text: String, voice: String): Result<VoicePreview> =
        createGeneratedPreview("edge", "mp3") { path ->
            EdgeTtsClient.synthesizeToMp3(text, path, voice).getOrThrow()
        }

    private suspend fun synthesizeSystemPreview(context: Context, text: String): Result<VoicePreview> =
        createGeneratedPreview("system-tts", "wav") { path ->
            synthesizeSystemTts(context, text, path.toFile()).getOrThrow()
        }

    private suspend fun synthesizeAndSend(
        talker: String,
        title: String,
        generate: suspend () -> Result<VoicePreview>,
    ): Result<Unit> {
        val generated = generate().getOrElse { return Result.failure(it) }
        return try {
            sendPreview(talker, generated, title)
        } finally {
            generated.path.asPath.deleteIfExists()
        }
    }

    private suspend fun createGeneratedPreview(
        prefix: String,
        extension: String,
        generate: suspend (java.nio.file.Path) -> Unit,
    ): Result<VoicePreview> = withContext(Dispatchers.IO) {
        val path = PanelPaths.panelCacheDir / "$prefix-${UUID.randomUUID()}.$extension"
        try {
            generate(path)
            require(path.isRegularFile() && Files.size(path) > 0L) { localizedChatString(R.string.chat_voice_conversion_empty) }
            Result.success(VoicePreview(path.absolutePathString(), temporary = true))
        } catch (error: CancellationException) {
            path.deleteIfExists()
            throw error
        } catch (error: Throwable) {
            path.deleteIfExists()
            Result.failure(error)
        }
    }

    private suspend fun synthesizeClonePreview(text: String, voice: CloneVoice): Result<VoicePreview> =
        withContext(Dispatchers.IO) {
            val path = PanelPaths.panelCacheDir / "clone-${UUID.randomUUID()}.mp3"
            try {
                val (voiceBytes, fileName) = CloneVoiceRepository.synthesisInput(voice).getOrThrow()
                val audio = FunBoxCloneVoiceRepository.synthesize(text, voiceBytes, fileName).getOrThrow()
                path.writeBytes(audio)
                require(Files.size(path) > 0L) { localizedChatString(R.string.chat_voice_conversion_empty) }
                Result.success(VoicePreview(path.absolutePathString(), temporary = true))
            } catch (error: CancellationException) {
                path.deleteIfExists()
                throw error
            } catch (error: Throwable) {
                path.deleteIfExists()
                Result.failure(error)
            }
        }

    private suspend fun sendPreview(talker: String, preview: VoicePreview, title: String): Result<Unit> =
        sendVoice(
            talker,
            VoiceItem(
                id = preview.path,
                title = title,
                localPath = preview.path,
                durationMs = AudioUtils.getDurationMs(preview.path).coerceAtLeast(0L),
                format = MediaFileTypeDetector.detectAudio(preview.path.asPath)?.extension.orEmpty(),
            ),
            recordUsage = false,
        )

    private suspend fun synthesizeSystemTts(
        context: Context,
        text: String,
        output: java.io.File,
    ): Result<Unit> = suspendCancellableCoroutine { continuation ->
        var engine: TextToSpeech? = null
        var completed = false

        fun finish(result: Result<Unit>) {
            if (completed) {
                engine?.shutdown()
                return
            }
            completed = true
            engine?.stop()
            engine?.shutdown()
            if (continuation.isActive) continuation.resume(result)
        }

        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                finish(Result.failure(IllegalStateException(localizedChatString(R.string.chat_voice_system_tts_init_failed))))
                return@TextToSpeech
            }
            if (tts.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = Locale.SIMPLIFIED_CHINESE
            }
            val utteranceId = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) = finish(Result.success(Unit))

                @Deprecated("Deprecated in Android")
                override fun onError(id: String?) = finish(Result.failure(IllegalStateException(localizedChatString(R.string.chat_voice_system_tts_failed))))
                override fun onError(id: String?, errorCode: Int) =
                    finish(Result.failure(IllegalStateException(localizedChatString(R.string.chat_voice_system_tts_failed_with_code, errorCode))))
            })
            val result = tts.synthesizeToFile(text, Bundle(), output, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                finish(Result.failure(IllegalStateException(localizedChatString(R.string.chat_voice_system_tts_start_failed))))
            }
        }
        continuation.invokeOnCancellation {
            completed = true
            engine.stop()
            engine.shutdown()
            output.delete()
        }
    }

    private suspend fun synthesizeCloneAndSend(talker: String, text: String, voice: CloneVoice): Result<Unit> =
        synthesizeAndSend(talker, voice.name) { synthesizeClonePreview(text, voice) }

    private suspend inline fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private suspend fun importVoiceBatch(
        packId: String,
        files: List<PickedPanelFile>,
        resolver: ContentResolver,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (files.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException(localizedChatString(R.string.chat_voice_no_supported_selected)))
        }

        var imported = 0
        val failures = mutableListOf<Pair<String, Throwable>>()
        files.forEach { file ->
            runCatching {
                val input = resolver.openInputStream(file.uri) ?: error(localizedChatString(R.string.chat_voice_file_read_failed))
                input.use {
                    VoicePanelRepository.importVoice(packId, file.name, it).getOrThrow()
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
                        R.plurals.chat_voice_import_partial_failed,
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

    private fun importCloneFile(
        context: Context,
        onStarted: () -> Unit,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        pickPanelFile(context, AUDIO_MIME_TYPES) { name, uri, activity ->
            onStarted()
            CoroutineScope(Dispatchers.IO).launch {
                val size = activity.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
                val result = activity.contentResolver.openInputStream(uri)?.let { input ->
                    CloneVoiceRepository.import(name.substringBeforeLast('.'), input, size).map { }
                } ?: Result.failure(IllegalStateException(localizedChatString(R.string.chat_voice_selected_tone_read_failed)))
                withContext(Dispatchers.Main) {
                    onComplete(result)
                    activity.finish()
                }
            }
        }
    }

    private val AUDIO_MIME_TYPES = arrayOf(
        "*/*",
    )
    private const val MAX_SHARED_VOICE_BYTES = 10 * 1024 * 1024
}
