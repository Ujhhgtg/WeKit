package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.utils.serialization.XmlUtils
import kotlin.math.roundToInt

internal data class StickerPixelSize(val width: Int, val height: Int)

internal data class PreviewFileMetadata(
    val name: String,
    val lastModifiedMillis: Long,
)

internal fun resolveStickerMd5(
    imagePath: String?,
    messageXml: String,
): String? = imagePath?.trim()?.takeIf { it.isNotEmpty() }
    ?: XmlUtils.extractXmlAttr(messageXml, "md5").trim().takeIf { it.isNotEmpty() }
    ?: XmlUtils.extractXmlTag(messageXml, "md5").trim().takeIf { it.isNotEmpty() }

internal fun scaleStickerSnapshot(
    width: Int,
    height: Int,
    maxDimension: Int = 2048,
): StickerPixelSize? {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return null
    val scale = minOf(1.0, maxDimension.toDouble() / maxOf(width, height))
    return StickerPixelSize(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
    )
}

internal fun previewFilesToDelete(
    existing: List<PreviewFileMetadata>,
    oldFilesToKeep: Int = 10,
): List<PreviewFileMetadata> {
    require(oldFilesToKeep >= 0)
    return existing.sortedByDescending { it.lastModifiedMillis }.drop(oldFilesToKeep)
}
