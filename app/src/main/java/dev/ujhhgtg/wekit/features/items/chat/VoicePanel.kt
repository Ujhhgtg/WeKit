package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.Feature
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

internal val EDGE_TTS_VOICES = listOf(
    "zh-CN-XiaoxiaoNeural" to "晓晓 (女, 温柔)",
    "zh-CN-XiaoyiNeural" to "晓伊 (女, 活泼)",
    "zh-CN-YunxiNeural" to "云希 (男, 阳光)",
    "zh-CN-YunyangNeural" to "云扬 (男, 播报)",
    "zh-CN-YunjianNeural" to "云健 (男, 浑厚)",
    "zh-CN-YunxiaNeural" to "云夏 (男, 少年)",
    "zh-CN-liaoning-XiaobeiNeural" to "晓北 (女, 东北话)",
    "zh-CN-shaanxi-XiaoniNeural" to "晓妮 (女, 陕西话)",
    "zh-HK-HiuMaanNeural" to "曉曼 (女, 粤语)",
    "zh-HK-WanLungNeural" to "雲龍 (男, 粤语)",
    "zh-TW-HsiaoChenNeural" to "曉臻 (女, 台湾)",
    "zh-TW-YunJheNeural" to "雲哲 (男, 台湾)",
    "en-US-AriaNeural" to "Aria (女, 英语)",
    "en-US-GuyNeural" to "Guy (男, 英语)",
    "ja-JP-NanamiNeural" to "七海 (女, 日语)",
)

@Feature(
    id = "语音面板",
    nameRes = "feature_voice_panel_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_voice_panel_description",
)
object VoicePanel : SwitchFeature() { // entry implementation in ChatFooterHooks

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
        ensureLocalPack = { name -> withContext(Dispatchers.IO) { VoicePanelRepository.ensurePack(name) } },
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
                        public.exceptionOrNull() ?: mine.exceptionOrNull() ?: IllegalStateException("共享语音包加载失败"),
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
                    val result = if (bytes == null) Result.failure(IllegalStateException("无法读取所选语音"))
                    else if (bytes.size > MAX_SHARED_VOICE_BYTES) Result.failure(IllegalArgumentException("单条语音不能超过 10 MiB"))
                    else {
                        val format = MediaFileTypeDetector.detectAudio(bytes)
                        if (format == null) Result.failure(IllegalArgumentException("不支持或无法识别的语音格式"))
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
                val provider = VoiceProviderRegistry.forItem(item) ?: error("没有可用语音提供商")
                val resolved = provider.resolveAudio(item).getOrThrow()
                require(!resolved.remoteUrl.isNullOrBlank()) { "没有可用语音地址" }
                FunBoxServiceClient.download(requireNotNull(resolved.remoteUrl)).getOrThrow()
            }
            require(bytes.isNotEmpty()) { "服务器未返回语音数据" }
            val prefix = bytes.copyOfRange(0, minOf(bytes.size, 256)).toString(Charsets.UTF_8).trimStart()
            if (prefix.startsWith("{")) {
                val message = Regex("\"msg\"\\s*:\\s*\"([^\"]+)\"")
                    .find(prefix)?.groupValues?.getOrNull(1)
                error(message ?: "服务器返回的不是音频数据")
            }
            val format = MediaFileTypeDetector.detectAudio(bytes)
                ?: error("服务器返回了不支持或无法识别的语音格式")
            val path = PanelPaths.panelCacheDir / "voice-${UUID.randomUUID()}.${format.extension}"
            path.writeBytes(bytes)
            VoicePreview(path.absolutePathString(), temporary = true)
        }
    }

    private suspend fun resolveExamplePath(example: CloneExample): Result<VoicePreview> = withContext(Dispatchers.IO) {
        FunBoxCloneVoiceRepository.exampleAudio(example).mapCatching { bytes ->
            val format = MediaFileTypeDetector.detectAudio(bytes)
                ?: error("音色示例不是可识别的语音格式")
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
                    ?: error("不支持或无法识别的语音格式")
                val directSource = sourceFormat == MediaFileTypeDetector.AudioFormat.SILK ||
                        sourceFormat == MediaFileTypeDetector.AudioFormat.AMR
                val silkPath = if (directSource) source else PanelPaths.panelCacheDir / "send-${UUID.randomUUID()}.silk"
                try {
                    if (!directSource) require(AudioUtils.anyToSilk(resolvedPath, silkPath.absolutePathString())) { "音频转 SILK 失败" }
                    check(WeMessageApi.sendVoice(talker, silkPath.absolutePathString(), durationMs.coerceToInt())) { "语音发送失败" }
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
        synthesizeAndSend(talker, "系统 TTS") { synthesizeSystemPreview(context, text) }

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
            require(path.isRegularFile() && Files.size(path) > 0L) { "语音转换结果为空" }
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
                require(Files.size(path) > 0L) { "语音转换结果为空" }
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
                finish(Result.failure(IllegalStateException("系统 TTS 初始化失败")))
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
                override fun onError(id: String?) = finish(Result.failure(IllegalStateException("系统 TTS 合成失败")))
                override fun onError(id: String?, errorCode: Int) =
                    finish(Result.failure(IllegalStateException("系统 TTS 合成失败，代码: $errorCode")))
            })
            val result = tts.synthesizeToFile(text, Bundle(), output, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                finish(Result.failure(IllegalStateException("系统 TTS 无法开始合成")))
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
            return@withContext Result.failure(IllegalArgumentException("所选内容中没有支持的语音文件"))
        }

        var imported = 0
        val failures = mutableListOf<Pair<String, Throwable>>()
        files.forEach { file ->
            runCatching {
                val input = resolver.openInputStream(file.uri) ?: error("无法读取文件")
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
                    "已导入 $imported 个，${failures.size} 个失败；${first.first}: " +
                            (first.second.message ?: "未知错误"),
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
                } ?: Result.failure(IllegalStateException("无法读取所选音色文件"))
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
