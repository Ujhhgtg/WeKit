package dev.ujhhgtg.wekit.features.items.chat.spoof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.SpannableStringBuilder
import android.webkit.MimeTypeMap
import dev.ujhhgtg.wekit.data.AssetStore
import dev.ujhhgtg.wekit.data.WeKitDatabase
import dev.ujhhgtg.wekit.data.entity.SpoofedChatMessageEntity
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

enum class SpoofKind(val messageType: Int) {
    TEXT(1),
    IMAGE(3),
    VIDEO(43),
    TRANSFER(419430449);

    val isMedia
        get() = this == IMAGE || this == VIDEO

    companion object {
        fun of(msg: MessageInfo): SpoofKind? = entries.firstOrNull {
            it.messageType == msg.typeCode
        }
    }
}

data class SpoofKey(val talker: String, val msgId: Long)

val SpoofedChatMessageEntity.key
    get() = SpoofKey(talker, msgId)
val SpoofedChatMessageEntity.spoofKind
    get() = SpoofKind.valueOf(kind)

/** The message a spoof is attached to: everything a new row needs besides the spoofed value. */
data class SpoofTarget(
    val key: SpoofKey,
    val createTime: Long,
    val kind: SpoofKind,
    val original: String?,
    val transferId: String?,
    val videoFileName: String?,
) {
    companion object {
        fun of(msg: MessageInfo): SpoofTarget {
            val kind = SpoofKind.of(msg)!!
            val transfer = if (kind == SpoofKind.TRANSFER) msg.toTransferMessage()!! else null
            return SpoofTarget(
                key = SpoofKey(msg.talker, msg.id),
                createTime = msg.createTime,
                kind = kind,
                original =
                    when (kind) {
                        SpoofKind.TEXT -> msg.actualContent
                        SpoofKind.TRANSFER -> transfer!!.feedesc
                        else -> null
                    },
                transferId = transfer?.transferId,
                videoFileName = if (kind == SpoofKind.VIDEO) msg.imagePath!! else null,
            )
        }

        fun of(entity: SpoofedChatMessageEntity) =
            SpoofTarget(
                entity.key,
                entity.createTime,
                entity.spoofKind,
                entity.original,
                entity.transferId,
                entity.videoFileName,
            )
    }
}

/**
 * Spoofed messages, persisted in Room and mirrored in memory for the render hooks. Image and video
 * bytes are Room assets; the host decodes and plays files, so they are also materialized under the
 * module cache, named by asset id so a replaced or restored asset never reuses a stale file.
 */
object SpoofedMessageStore {

    private const val ASSET_OWNER = "spoofed-chat-message"
    private const val POSTER_SUFFIX = "#poster"
    private const val POSTER_MAX_EDGE = 1280

    private val entries = ConcurrentHashMap<SpoofKey, SpoofedChatMessageEntity>()

    @Volatile private var loaded = false

    private val _all = MutableStateFlow(emptyList<SpoofedChatMessageEntity>())

    /** All spoofs, most recently edited first. */
    val all: StateFlow<List<SpoofedChatMessageEntity>> by lazy {
        ensureLoaded()
        _all.asStateFlow()
    }

    val isEmpty
        get() = entries.isEmpty()

    /** Materialized files by asset slot, so render hooks don't query Room on every frame. */
    private val materialized = ConcurrentHashMap<String, File>()

    private val cacheDir by lazy {
        (KnownPaths.moduleCache / "spoofed-chat-messages").createDirsSafe().toFile()
    }

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val saved =
                runBlocking(Dispatchers.IO) {
                    WeKitDatabase.instance.simpleStructuredDao().getSpoofedMessages()
                }
            entries.putAll(saved.associateBy { it.key })
            loaded = true
            publish()
        }
    }

    operator fun get(key: SpoofKey): SpoofedChatMessageEntity? = entries[key]

    /**
     * The spoof of [msg], unless its local id has since been reused or the message changed type.
     */
    fun find(msg: MessageInfo): SpoofedChatMessageEntity? =
        entries[SpoofKey(msg.talker, msg.id)]?.takeIf {
            it.createTime == msg.createTime && it.spoofKind.messageType == msg.typeCode
        }

    fun findByTransferId(transferId: String): SpoofedChatMessageEntity? =
        entries.values.firstOrNull { it.transferId == transferId }

    /**
     * The video spoof whose host file (`<imgPath>.mp4`, `_hd` or `origin` variants) is [fileName].
     */
    fun findByVideoFileName(fileName: String): SpoofedChatMessageEntity? =
        entries.values.firstOrNull { entity ->
            val base = entity.videoFileName ?: return@firstOrNull false
            fileName.startsWith(base) &&
                fileName.substring(base.length).let {
                    it.startsWith(".") || it.startsWith("_") || it.startsWith("origin")
                }
        }

    suspend fun saveText(target: SpoofTarget, text: String) = save(target) { it.copy(text = text) }

    suspend fun saveAmount(target: SpoofTarget, amount: String) =
        save(target) { it.copy(amount = amount) }

    /**
     * Imports the image or video at [uri]; throws [IllegalArgumentException] for unreadable media.
     */
    suspend fun saveMedia(context: Context, target: SpoofTarget, uri: Uri) =
        withContext(Dispatchers.IO) {
            val slot = slot(target.key)
            val mimeType =
                context.contentResolver.getType(uri)
                    ?: if (target.kind == SpoofKind.VIDEO) "video/mp4" else "image/jpeg"
            val source = File(cacheDir, "${fileStem(target.key)}.import")
            try {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    source.outputStream().use { input.copyTo(it) }
                }
                var duration: Int? = null
                if (target.kind == SpoofKind.VIDEO) {
                    val retriever = MediaMetadataRetriever()
                    val poster =
                        try {
                            retriever.setDataSource(source.path)
                            duration =
                                retriever
                                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                                    .toLong()
                                    .let { ((it + 500) / 1000).toInt() }
                            retriever.getScaledFrameAtTime(
                                0,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                POSTER_MAX_EDGE,
                                POSTER_MAX_EDGE,
                            )
                        } finally {
                            retriever.release()
                        }
                    requireNotNull(poster) { "cannot read a frame of the selected video" }
                    val jpeg =
                        ByteArrayOutputStream().also {
                            poster.compress(Bitmap.CompressFormat.JPEG, 90, it)
                        }
                    check(
                        AssetStore.importBinding(
                            ASSET_OWNER,
                            slot + POSTER_SUFFIX,
                            jpeg.toByteArray(),
                            "image/jpeg",
                        ),
                    )
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(source.path, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                        "cannot decode the selected image"
                    }
                }
                check(AssetStore.importBinding(ASSET_OWNER, slot, source, mimeType))
                deleteCachedFiles(target.key)
                source.renameTo(
                    cachedFile(
                        target.key,
                        AssetStore.bindingAssetId(ASSET_OWNER, slot)!!,
                        extension(mimeType),
                    ),
                )
                save(target) { it.copy(videoDurationSeconds = duration) }
            } finally {
                source.delete()
            }
        }

    suspend fun remove(key: SpoofKey) =
        withContext(Dispatchers.IO) {
            WeKitDatabase.instance.simpleStructuredDao().removeSpoofedMessage(key.talker, key.msgId)
            AssetStore.removeBinding(ASSET_OWNER, slot(key))
            AssetStore.removeBinding(ASSET_OWNER, slot(key) + POSTER_SUFFIX)
            deleteCachedFiles(key)
            entries.remove(key)
            publish()
        }

    /** The spoofed image or video file of [entity], materialized from Room when not cached yet. */
    fun mediaFile(entity: SpoofedChatMessageEntity): File =
        materialize(
            entity.key,
            slot(entity.key),
            if (entity.spoofKind == SpoofKind.VIDEO) "mp4" else "jpg",
        )

    /** The still image shown for [entity]: the image itself, or the video's first frame. */
    fun previewFile(entity: SpoofedChatMessageEntity): File =
        if (entity.spoofKind == SpoofKind.VIDEO)
            materialize(entity.key, slot(entity.key) + POSTER_SUFFIX, "jpg")
        else mediaFile(entity)

    private suspend fun save(
        target: SpoofTarget,
        update: (SpoofedChatMessageEntity) -> SpoofedChatMessageEntity,
    ) =
        withContext(Dispatchers.IO) {
            val base =
                entries[target.key]?.takeIf { it.createTime == target.createTime }
                    ?: SpoofedChatMessageEntity(
                        talker = target.key.talker,
                        msgId = target.key.msgId,
                        createTime = target.createTime,
                        kind = target.kind.name,
                        original = target.original,
                        text = null,
                        amount = null,
                        transferId = target.transferId,
                        videoFileName = target.videoFileName,
                        videoDurationSeconds = null,
                        updatedAt = 0,
                    )
            val entity = update(base).copy(updatedAt = System.currentTimeMillis())
            WeKitDatabase.instance.simpleStructuredDao().putSpoofedMessage(entity)
            entries[entity.key] = entity
            publish()
        }

    private fun materialize(key: SpoofKey, slot: String, extension: String): File {
        materialized[slot]?.takeIf(File::exists)?.let {
            return it
        }
        val assetId = AssetStore.bindingAssetId(ASSET_OWNER, slot)!!
        // An interrupted copy leaves `<name>.partial` behind; it is overwritten, never reused.
        val file =
            cacheDir.listFiles()!!.firstOrNull {
                it.name.startsWith("${fileStem(key)}-$assetId.") && !it.name.endsWith(".partial")
            }
                ?: cachedFile(key, assetId, extension).also {
                    check(AssetStore.copyBinding(ASSET_OWNER, slot, it)) {
                        "cannot materialize spoofed media $slot"
                    }
                }
        materialized[slot] = file
        return file
    }

    private fun deleteCachedFiles(key: SpoofKey) {
        materialized.remove(slot(key))
        materialized.remove(slot(key) + POSTER_SUFFIX)
        cacheDir
            .listFiles()!!
            .filter { it.name.startsWith("${fileStem(key)}-") }
            .forEach(File::delete)
    }

    private fun cachedFile(key: SpoofKey, assetId: String, extension: String) =
        File(cacheDir, "${fileStem(key)}-$assetId.$extension")

    private fun slot(key: SpoofKey) = "${key.talker}/${key.msgId}"

    private fun fileStem(key: SpoofKey) = "${key.talker}_${key.msgId}"

    private fun extension(mimeType: String) =
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"

    private fun publish() {
        _all.value = entries.values.sortedByDescending { it.updatedAt }
        SpoofChatMessages.onSpoofsChanged()
    }
}

/** A transfer amount typed by the user as a two-decimal plain string, or null when invalid. */
fun normalizeSpoofAmount(input: String): String? {
    val value = input.trim().toBigDecimalOrNull() ?: return null
    if (value.signum() < 0 || value > BigDecimal("99999999.99")) return null
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString()
}

private val AMOUNT_NUMBER = Regex("""\d[\d,]*(?:\.\d+)?""")

/** [original] (e.g. `¥0.01`) with its number replaced by [amount], keeping the currency marks. */
fun formatSpoofAmount(original: String, amount: String): String =
    original.withSpoofAmount(amount).toString()

/**
 * This amount text with its first number replaced by [amount], keeping spans and currency marks.
 */
fun CharSequence.withSpoofAmount(amount: String): CharSequence {
    val number = AMOUNT_NUMBER.find(this) ?: return this
    return SpannableStringBuilder(this).replace(number.range.first, number.range.last + 1, amount)
}

/** The currency marks of a host amount such as `¥0.01`. */
fun amountCurrency(original: String): String = AMOUNT_NUMBER.replaceFirst(original, "").trim()

/** The plain number of a host amount such as `¥0.01`. */
fun amountNumber(original: String): String = AMOUNT_NUMBER.find(original)!!.value.replace(",", "")

fun formatSpoofDuration(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
