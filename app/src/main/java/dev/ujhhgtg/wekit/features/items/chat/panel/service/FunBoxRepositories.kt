package dev.ujhhgtg.wekit.features.items.chat.panel.service

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.panel.CloneExample
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSettings
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSource
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerItem
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerPack
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceItem
import dev.ujhhgtg.wekit.features.items.chat.panel.VoicePack
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceProviderPage
import dev.ujhhgtg.wekit.loader.utils.ResourcesInjector
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.security.MessageDigest
import java.util.LinkedHashMap

private val funBoxClientWxId: String
    get() = PanelSettings.effectiveFunBoxApiClientWxId

object FunBoxStickerRepository {
    private val embeddedCatalog by lazy {
        runCatching {
            val resources = HostInfo.application.resources
            ResourcesInjector.injectModuleRes(resources)
            resources.openRawResource(R.raw.funbox_sticker_catalog_snapshot)
                .bufferedReader()
                .use { reader ->
                    DefaultJson.decodeFromString<List<FunBoxStickerPackSnapshot>>(reader.readText())
                }
                .map(FunBoxStickerPackSnapshot::toStickerPack)
        }
    }

    suspend fun loadCatalog(): Result<List<StickerPack>> {
        WeLogger.d(TAG, "catalog load start")
        val request = FunBoxBinaryWriter().apply { string(funBoxClientWxId) }.build()
        val result = FunBoxServiceClient.call(OP_STICKER_CATALOG, request) { response ->
            response.objects { pack ->
                val id = pack.string()
                val title = pack.string()
                pack.int()
                pack.long()
                pack.string()
                val downloadCount = pack.int()
                val clickCount = pack.int()
                pack.string()
                val thumbId = pack.string()
                val uploadTime = pack.long()
                StickerPack(
                    id = id,
                    title = title,
                    cover = thumbId,
                    source = PanelSource.ONLINE,
                    badge = "点击 $clickCount · 下载 $downloadCount",
                    uploadTime = uploadTime,
                    downloadCount = downloadCount,
                )
            }
        }.map { serverCatalog ->
            val snapshotCatalog = embeddedCatalog.getOrElse { error ->
                WeLogger.e(TAG, "embedded catalog load failed", error)
                emptyList()
            }
            mergeCatalog(snapshotCatalog, serverCatalog)
        }
        return resolvePackCovers(result).logNetworkResult(TAG, "catalog load") { "packs=${it.size}" }
    }

    suspend fun loadMyUploads(): Result<List<StickerPack>> {
        WeLogger.d(TAG, "my uploads load start")
        val request = FunBoxBinaryWriter().apply { string(funBoxClientWxId) }.build()
        val result = FunBoxServiceClient.call(OP_STICKER_MY_UPLOADS, request) { response ->
            response.objects { pack ->
                val id = pack.string()
                pack.string()
                val title = pack.string()
                pack.int()
                val reviewState = pack.int()
                pack.long()
                pack.string()
                val downloadCount = pack.int()
                val clickCount = pack.int()
                val thumbId = pack.string()
                val uploadTime = pack.long()
                StickerPack(
                    id = id,
                    title = title,
                    cover = thumbId,
                    source = PanelSource.ONLINE,
                    badge = "状态 $reviewState · 点击 $clickCount · 下载 $downloadCount",
                    uploadTime = uploadTime,
                    downloadCount = downloadCount,
                )
            }
        }
        return resolvePackCovers(result).logNetworkResult(TAG, "my uploads load") { "packs=${it.size}" }
    }

    suspend fun loadPack(pack: StickerPack): Result<List<StickerItem>> {
        WeLogger.d(TAG, "pack contents load start")
        val request = FunBoxBinaryWriter().apply {
            string(pack.id)
            string(funBoxClientWxId)
        }.build()
        val result = FunBoxServiceClient.call(OP_STICKER_CONTENTS, request) { response ->
            val status = response.int()
            val items = response.objects(::decodeSticker)
            val message = response.string()
            check(status == 0) { message.ifBlank { "表情包加载失败" } }
            items.map { it.copy(packId = pack.id) }
        }
        return resolveStickerThumbnails(result).logNetworkResult(TAG, "pack contents load") { "items=${it.size}" }
    }

    suspend fun searchSimilar(imageBytes: ByteArray): Result<List<StickerItem>> {
        require(imageBytes.isNotEmpty()) { "图片内容为空" }
        val request = FunBoxBinaryWriter().apply { bytes(imageBytes) }.build()
        WeLogger.d(TAG, "similarity search start imageBytes=${imageBytes.size}")
        val result = FunBoxServiceClient.call(OP_STICKER_SIMILARITY, request) { response ->
            response.objects { item ->
                val score = java.lang.Double.longBitsToDouble(item.long())
                val id = item.string()
                val thumbId = item.string()
                val packId = item.string()
                StickerItem(
                    id = id,
                    remoteObjectId = id,
                    thumbnailUrl = thumbId,
                    source = PanelSource.ONLINE,
                    packId = packId,
                    title = "相似度 ${(score * 100).toInt()}%",
                )
            }
        }
        return resolveStickerThumbnails(result).logNetworkResult(TAG, "similarity search") { "items=${it.size}" }
    }

    suspend fun searchText(query: String): Result<List<StickerItem>> {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        WeLogger.d(TAG, "text search start")
        val request = FunBoxBinaryWriter().apply {
            string(funBoxClientWxId)
            string(query)
        }.build()
        val result = FunBoxServiceClient.call(OP_STICKER_TEXT_SEARCH, request) { response ->
            response.objects(::decodeSticker)
        }
        return resolveStickerThumbnails(result).logNetworkResult(TAG, "text search") { "items=${it.size}" }
    }

    suspend fun uploadPack(pack: StickerPack, onProgress: (Float) -> Unit): Result<String> = runCatching {
        val localPaths = pack.items.mapNotNull { it.localPath }.map { it.asPath }
        require(localPaths.isNotEmpty()) { "表情包为空" }
        WeLogger.d(TAG, "pack upload start items=${localPaths.size}")
        val hashes = localPaths.map { md5(Files.readAllBytes(it)) }
        val preflight = FunBoxServiceClient.call(
            OP_STICKER_UPLOAD_PREFLIGHT,
            FunBoxBinaryWriter().apply {
                strings(hashes)
                string(funBoxClientWxId)
            }.build(),
        ) { response ->
            UploadSession(response.int(), response.string(), response.string(), response.string())
        }.getOrThrow()
        check(preflight.status != 2) { preflight.message.ifBlank { "表情包上传被拒绝" } }
        if (preflight.status == 3) {
            error(preflight.message.ifBlank { "服务器要求确认重复表情，请调整表情包内容后重试" })
        }
        val archive = buildUploadArchive(localPaths)
        val session = FunBoxServiceClient.call(
            OP_STICKER_UPLOAD_SESSION,
            FunBoxBinaryWriter().apply {
                string(pack.title)
                string("")
                string("")
                string(preflight.token)
                string(funBoxClientWxId)
                int(1)
                bool(true)
            }.build(),
        ) { response ->
            UploadSession(response.int(), response.string(), response.string(), response.string())
        }.getOrThrow()
        if (session.status == 2) return@runCatching "上传完成，请等待审核通过"
        check(session.status == 0) { session.message.ifBlank { "无法创建上传会话" } }
        val chunks = (0..archive.size / UPLOAD_CHUNK_BYTES).map { index ->
            val start = index * UPLOAD_CHUNK_BYTES
            archive.copyOfRange(start, minOf(start + UPLOAD_CHUNK_BYTES, archive.size))
        }
        WeLogger.d(TAG, "pack upload archiveBytes=${archive.size} chunks=${chunks.size}")
        chunks.forEachIndexed { index, chunk ->
            var lastFailure: Throwable? = null
            var uploaded = false
            repeat(4) { attempt ->
                if (uploaded) return@repeat
                val result = FunBoxServiceClient.call(
                    OP_STICKER_UPLOAD_CHUNK,
                    FunBoxBinaryWriter().apply {
                        string(session.extra)
                        int(index)
                        bytes(chunk)
                    }.build(),
                ) { response -> StatusResponse(response.int(), response.string(), response.bytes()) }
                val response = result.getOrNull()
                if (response?.status == 0) {
                    lastFailure = null
                    uploaded = true
                    return@repeat
                }
                lastFailure = result.exceptionOrNull() ?: IllegalStateException(response?.message ?: "分块上传失败")
                if (attempt == 3) throw lastFailure
            }
            check(uploaded) { lastFailure?.message ?: "分块上传失败" }
            onProgress((index + 1f) / chunks.size)
        }
        val confirmation = FunBoxServiceClient.call(
            OP_STICKER_UPLOAD_CONFIRM,
            FunBoxBinaryWriter().apply {
                string(funBoxClientWxId)
                string(session.extra)
                strings(localPaths.indices.map(Int::toString))
            }.build(),
        ) { response -> StatusResponse(response.int(), response.string(), response.bytes()) }.getOrThrow()
        check(confirmation.status == 0) { confirmation.message.ifBlank { "上传确认失败" } }
        "上传完成，请等待审核通过"
    }.logNetworkResult(TAG, "pack upload") { "completed" }

    private fun decodeSticker(item: FunBoxBinaryReader): StickerItem {
        val md5 = item.string().uppercase()
        val packId = item.string()
        val imageId = item.string()
        val ocr = item.string()
        val thumbId = item.string()
        return StickerItem(
            id = md5,
            title = ocr,
            remoteObjectId = imageId,
            thumbnailUrl = thumbId,
            source = PanelSource.ONLINE,
            packId = packId,
        )
    }

    private fun mergeCatalog(
        snapshotCatalog: List<StickerPack>,
        serverCatalog: List<StickerPack>,
    ): List<StickerPack> {
        val mergedById = LinkedHashMap<String, StickerPack>(snapshotCatalog.size + serverCatalog.size)
        snapshotCatalog.forEach { mergedById[it.id] = it }
        serverCatalog.forEach { mergedById[it.id] = it }
        return mergedById.values.toList()
    }

    private suspend fun resolvePackCovers(result: Result<List<StickerPack>>): Result<List<StickerPack>> {
        if (result.isFailure) return result
        return runCatching {
            val base = FunBoxServiceClient.objectUrl("thumb", "").trimEnd('/')
            result.getOrThrow().map { pack ->
                pack.copy(cover = pack.cover.toObjectUrl(base))
            }
        }
    }

    private suspend fun resolveStickerThumbnails(result: Result<List<StickerItem>>): Result<List<StickerItem>> {
        if (result.isFailure) return result
        return runCatching {
            val thumbnailBase = FunBoxServiceClient.objectUrl("thumb", "").trimEnd('/')
            val imageBase = FunBoxServiceClient.objectUrl("image", "").trimEnd('/')
            result.getOrThrow().map { item ->
                item.copy(
                    thumbnailUrl = item.thumbnailUrl.toObjectUrl(thumbnailBase),
                    imageUrl = item.remoteObjectId.toObjectUrl(imageBase),
                )
            }
        }
    }

    private fun String?.toObjectUrl(base: String): String? = when {
        isNullOrBlank() -> null
        startsWith("http://") || startsWith("https://") -> this
        else -> "$base/${trimStart('/')}"
    }

    private fun buildUploadArchive(paths: List<java.nio.file.Path>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            paths.forEachIndexed { index, path ->
                require(Files.isRegularFile(path)) { "表情文件不存在: $path" }
                zip.putNextEntry(java.util.zip.ZipEntry(index.toString()))
                Files.copy(path, zip)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun md5(value: ByteArray) = MessageDigest.getInstance("MD5").digest(value)
        .joinToString("") { "%02X".format(it) }

    private data class UploadSession(val status: Int, val message: String, val token: String, val extra: String)
    private data class StatusResponse(val status: Int, val message: String, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as StatusResponse

            if (status != other.status) return false
            if (message != other.message) return false
            if (!bytes.contentEquals(other.bytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = status
            result = 31 * result + message.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    private const val OP_STICKER_CATALOG = 10
    private const val OP_STICKER_MY_UPLOADS = 18
    private const val OP_STICKER_CONTENTS = 2
    private const val OP_STICKER_SIMILARITY = 260
    private const val OP_STICKER_TEXT_SEARCH = 13
    private const val OP_STICKER_UPLOAD_PREFLIGHT = 15
    private const val OP_STICKER_UPLOAD_SESSION = 12
    private const val OP_STICKER_UPLOAD_CHUNK = 16
    private const val OP_STICKER_UPLOAD_CONFIRM = 11
    private const val UPLOAD_CHUNK_BYTES = 4 * 1024 * 1024
    private const val TAG = "FunBoxStickerRepository"
}

@Serializable
private data class FunBoxStickerPackSnapshot(
    val id: String,
    val title: String,
    @SerialName("thumb_id")
    val thumbId: String,
    @SerialName("click_count")
    val clickCount: Int,
    @SerialName("download_count")
    val downloadCount: Int,
    @SerialName("updated_at")
    val updatedAt: Long,
) {
    fun toStickerPack() = StickerPack(
        id = id,
        title = title,
        cover = thumbId,
        source = PanelSource.ONLINE,
        badge = "点击 $clickCount · 下载 $downloadCount",
        uploadTime = updatedAt,
        downloadCount = downloadCount,
    )
}

object FunBoxVoiceRepository {
    suspend fun browseSharedVoices(parent: VoiceItem?, page: Int): Result<VoiceProviderPage> {
        WeLogger.d(TAG, "shared browse start page=$page root=${parent == null}")
        val result = if (parent == null) {
            listSharedPacks().map { packs ->
                VoiceProviderPage(
                    items = packs.map { pack ->
                        VoiceItem(
                            id = "funbox:pack:${pack.id}",
                            title = pack.title,
                            source = PanelSource.SHARED,
                            isContainer = true,
                            metadata = mapOf("packId" to pack.id),
                        )
                    },
                    page = 0,
                    hasMore = false,
                )
            }
        } else {
            val packId = parent.metadata["packId"] ?: return Result.failure(IllegalArgumentException("无效语音包"))
            loadSharedPack(packId).map { VoiceProviderPage(it, page, false) }
        }
        return result.logNetworkResult(TAG, "shared browse") { "page=${it.page} items=${it.items.size}" }
    }

    suspend fun searchSharedVoices(query: String, page: Int): Result<VoiceProviderPage> {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        WeLogger.d(TAG, "shared search start page=$page")
        val request = FunBoxBinaryWriter().apply {
            string(funBoxClientWxId)
            string(query)
        }.build()
        return FunBoxServiceClient.call(OP_SHARED_SEARCH, request) { response ->
            val items = response.objects(::decodeSharedVoice)
            VoiceProviderPage(items, page, false)
        }.logNetworkResult(TAG, "shared search") { "page=${it.page} items=${it.items.size}" }
    }

    suspend fun listSharedPacks(): Result<List<VoicePack>> {
        WeLogger.d(TAG, "public shared-pack list start")
        val request = FunBoxBinaryWriter().apply { string(funBoxClientWxId) }.build()
        return FunBoxServiceClient.call(OP_SHARED_PACKS, request) { response ->
            response.objects(::decodeSharedPack)
        }.logNetworkResult(TAG, "public shared-pack list") { "packs=${it.size}" }
    }

    suspend fun listMyPacks(): Result<List<VoicePack>> {
        WeLogger.d(TAG, "owned shared-pack list start")
        val request = FunBoxBinaryWriter().apply { string(funBoxClientWxId) }.build()
        return FunBoxServiceClient.call(OP_MY_PACKS, request) { response -> response.objects(::decodeSharedPack) }
            .logNetworkResult(TAG, "owned shared-pack list") { "packs=${it.size}" }
    }

    suspend fun loadSharedPack(packId: String): Result<List<VoiceItem>> {
        WeLogger.d(TAG, "shared-pack contents load start")
        val request = FunBoxBinaryWriter().apply {
            string(funBoxClientWxId)
            string(packId)
        }.build()
        return FunBoxServiceClient.call(OP_SHARED_PACK_ITEMS, request) { response ->
            val status = response.int()
            val message = response.string()
            val items = response.objects(::decodeSharedVoice)
            check(status == 0) { message.ifBlank { "语音包加载失败" } }
            items
        }.logNetworkResult(TAG, "shared-pack contents load") { "items=${it.size}" }
    }

    suspend fun createPack(name: String): Result<String> = statusRequest(
        OP_SHARED_CREATE_PACK,
        FunBoxBinaryWriter().apply {
            string(name)
            string(funBoxClientWxId)
        }.build(),
    )

    suspend fun renamePack(packId: String, name: String): Result<String> = statusRequest(
        OP_SHARED_RENAME_PACK,
        FunBoxBinaryWriter().apply {
            string(funBoxClientWxId)
            string(packId)
            int(1)
            string(name)
        }.build(),
    )

    suspend fun deletePack(packId: String): Result<String> = statusRequest(
        OP_SHARED_DELETE_PACK,
        FunBoxBinaryWriter().apply {
            string(funBoxClientWxId)
            string(packId)
            int(2)
            string("")
        }.build(),
    )

    suspend fun confirmPack(packId: String): Result<String> = shortStatusRequest(
        OP_SHARED_CONFIRM_PACK,
        FunBoxBinaryWriter().apply {
            string(packId)
            string(funBoxClientWxId)
        }.build(),
    )

    suspend fun uploadVoice(packId: String, item: VoiceItem, bytes: ByteArray): Result<String> = statusRequest(
        OP_SHARED_UPLOAD_VOICE,
        FunBoxBinaryWriter().apply {
            string(funBoxClientWxId)
            string(packId)
            bytes(bytes)
            string(item.title)
        }.build(),
    )

    private suspend fun statusRequest(operation: Int, payload: ByteArray): Result<String> =
        FunBoxServiceClient.call(operation, payload) { response ->
            val status = response.int()
            val message = response.string()
            response.bytes()
            check(status == 0) { message.ifBlank { "操作失败" } }
            message
        }.logNetworkResult(TAG, "shared mutation operation=$operation") { "completed" }

    private suspend fun shortStatusRequest(operation: Int, payload: ByteArray): Result<String> =
        FunBoxServiceClient.call(operation, payload) { response ->
            val status = response.int()
            val message = response.string()
            check(status == 0) { message.ifBlank { "操作失败" } }
            message
        }.logNetworkResult(TAG, "shared mutation operation=$operation") { "completed" }

    private fun decodeSharedPack(pack: FunBoxBinaryReader): VoicePack {
        val id = pack.string()
        val title = pack.string()
        pack.long()
        pack.long()
        val state = pack.int()
        val count = pack.int()
        return VoicePack(
            id = id,
            title = title,
            source = PanelSource.SHARED,
            itemCount = count,
            badge = when (state) {
                0 -> "草稿"
                1 -> "审核中"
                2 -> "已发布"
                3 -> "已拒绝"
                else -> state.toString()
            },
        )
    }

    private fun decodeSharedVoice(item: FunBoxBinaryReader): VoiceItem {
        val id = item.string()
        val objectId = item.string()
        val title = item.string()
        val packId = item.string()
        return VoiceItem(
            id = "funbox:voice:$id",
            title = title,
            remoteObjectId = objectId,
            source = PanelSource.SHARED,
            packId = packId,
            format = "mp3",
        )
    }

    private const val OP_SHARED_PACKS = 20
    private const val OP_MY_PACKS = 23
    private const val OP_SHARED_PACK_ITEMS = 21
    private const val OP_SHARED_SEARCH = 29
    private const val OP_SHARED_CREATE_PACK = 24
    private const val OP_SHARED_RENAME_PACK = 27
    private const val OP_SHARED_DELETE_PACK = 27
    private const val OP_SHARED_CONFIRM_PACK = 25
    private const val OP_SHARED_UPLOAD_VOICE = 32
    private const val TAG = "FunBoxVoiceRepository"
}

object FunBoxCloneVoiceRepository {
    suspend fun synthesize(text: String, voiceBytes: ByteArray, fileName: String): Result<ByteArray> {
        require(text.isNotBlank()) { "转换文字不能为空" }
        require(text.codePointCount(0, text.length) <= 256) { "转换文字不能超过 256 个字符" }
        require(voiceBytes.isNotEmpty()) { "音色文件不可读" }
        WeLogger.d(TAG, "clone synthesis start voiceBytes=${voiceBytes.size}")
        val request = FunBoxBinaryWriter().apply {
            string(funBoxClientWxId)
            string(text)
            bytes(voiceBytes)
            string(fileName)
        }.build()
        return FunBoxServiceClient.call(OP_CLONE_SYNTHESIS, request) { response ->
            val status = response.int()
            val message = response.string()
            val audio = response.bytes()
            check(status == 0 && audio.isNotEmpty()) { message.ifBlank { "克隆语音合成失败" } }
            audio
        }.logNetworkResult(TAG, "clone synthesis") { "audioBytes=${it.size}" }
    }

    suspend fun exampleGroups(): Result<List<String>> = loadExampleNames("")

    suspend fun examples(group: String): Result<List<CloneExample>> =
        loadExampleNames(group).map { names -> names.map { CloneExample(group, it) } }

    suspend fun exampleAudio(example: CloneExample): Result<ByteArray> {
        WeLogger.d(TAG, "clone example audio load start")
        val request = FunBoxBinaryWriter().apply {
            string(example.group)
            string(example.fileName)
        }.build()
        return FunBoxServiceClient.call(OP_CLONE_EXAMPLE_AUDIO, request) { response ->
            val status = response.int()
            val message = response.string()
            val audio = response.bytes()
            check(status == 0 && audio.isNotEmpty()) { message.ifBlank { "读取语音示例失败" } }
            audio
        }.logNetworkResult(TAG, "clone example audio load") { "audioBytes=${it.size}" }
    }

    private suspend fun loadExampleNames(path: String): Result<List<String>> {
        WeLogger.d(TAG, "clone example list start root=${path.isEmpty()}")
        val request = FunBoxBinaryWriter().apply { string(path) }.build()
        return FunBoxServiceClient.call(OP_CLONE_EXAMPLES, request) { response ->
            val status = response.int()
            val message = response.string()
            val names = response.strings()
            check(status == 0) { message.ifBlank { "读取语音示例失败" } }
            names
        }.logNetworkResult(TAG, "clone example list") { "items=${it.size}" }
    }

    private const val OP_CLONE_SYNTHESIS = 0xB5
    private const val OP_CLONE_EXAMPLES = 0xB7
    private const val OP_CLONE_EXAMPLE_AUDIO = 0xB8
    private const val TAG = "FunBoxCloneVoiceRepository"
}

private inline fun <T> Result<T>.logNetworkResult(
    tag: String,
    action: String,
    success: (T) -> String,
): Result<T> = onSuccess { value ->
    WeLogger.i(tag, "$action succeeded ${success(value)}")
}.onFailure { error ->
    WeLogger.e(tag, "$action failed", error)
}
