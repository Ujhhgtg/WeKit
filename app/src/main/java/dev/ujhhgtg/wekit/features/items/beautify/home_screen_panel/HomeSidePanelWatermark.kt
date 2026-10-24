package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import java.nio.charset.StandardCharsets

/**
 * The side panel carries an opaque, error-correcting watermark so an administrator can attribute a
 * screenshot without displaying an account identifier in the UI. New frames contain only the
 * unguessable initialization token; the server maps it to identity after the authenticated trace
 * request. The older RSA frame remains supported by the WebUI decoder for migration.
 *
 * The raster signal is spread over a keyed permutation of small local blocks. This is deliberately
 * different from a regular grid: regular adjacent-cell patterns are easy to spot in a flat panel
 * and their signal is dominated by the underlying UI when a screenshot is recompressed.
 */

// Version 3 deliberately uses a low-frequency raster signal. Each logical pair is made of two
// solid 4x4 patches in an eight-cell macroblock. The old version sampled one pixel from
// four-cell macroblocks; that signal was too easy for JPEG quantisation and screenshot resizing
// to erase.
private const val WATERMARK_BLOCK_SIZE = 8
// Keep each mark to two logical cells.  At a typical 1080–1440 px panel this is a
// 4–6 px patch after rasterisation, which is substantially less conspicuous than
// the previous 4×4-cell marks while still leaving enough samples for a pair mean.
private const val WATERMARK_MARK_WIDTH = 2
private const val WATERMARK_MARK_HEIGHT = 2
private const val WATERMARK_GRID_SIZE = 512
private const val WATERMARK_BLOCKS_PER_SIDE = WATERMARK_GRID_SIZE / WATERMARK_BLOCK_SIZE
private const val WATERMARK_REPETITIONS = 10
// The pair contrast remains measurable after screenshot resampling/JPEG encoding, but
// the lower blend keeps the signal below the visible speckle threshold on flat surfaces.
private const val WATERMARK_ALPHA = 0.0055f
private const val WATERMARK_MAGIC = "WKWM3V1\u0000"
private const val WATERMARK_TOKEN_BYTES = 32
private const val WATERMARK_CHECKSUM_BYTES = 4

// This only determines the public raster layout. Identity is resolved from the server-side
// initialization mapping after the Access-protected trace request.
private const val WATERMARK_LAYOUT_SEED = 0x6A09E667

/**
 * Creates the opaque value that is spread through the side-panel pixels. The value is safe to
 * expose to a screenshot decoder: only the authenticated server-side mapping can recover the
 * account identity.
 */
fun homeSidePanelWatermarkToken(token: String?): ByteArray? {
    val value =
        token?.takeIf {
            it.length == WATERMARK_TOKEN_BYTES &&
                it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' }
        } ?: return null
    return value.toByteArray(StandardCharsets.US_ASCII)
}

/**
 * Draws the watermark after the panel contents without adding a pointer-intercepting layer. `token`
 * is normally memoized by the caller for the lifetime of an account/session.
 */
fun Modifier.homeSidePanelWatermark(token: ByteArray?): Modifier {
    if (token == null) return this
    val bits = homeSidePanelWatermarkBits(token)
    val layout = homeSidePanelWatermarkLayout()
    return drawWithCache {
        val plus = Path()
        val minus = Path()
        val cellWidth = size.width / WATERMARK_GRID_SIZE
        val cellHeight = size.height / WATERMARK_GRID_SIZE
        val insetX = cellWidth * 0.08f
        val insetY = cellHeight * 0.08f
        bits.forEachIndexed { bitIndex, bit ->
            repeat(WATERMARK_REPETITIONS) { repetition ->
                val pair = layout[bitIndex * WATERMARK_REPETITIONS + repetition]
                // Pair orientation is randomized by the public layout permutation. The decoder
                // applies the same orientation before voting, so no regular checkerboard remains.
                val firstPlus = (bit == 1) xor pair.reversed
                addWatermarkBlock(
                    if (firstPlus) plus else minus,
                    pair.first,
                    cellWidth,
                    cellHeight,
                    insetX,
                    insetY,
                    pair.vertical,
                )
                addWatermarkBlock(
                    if (firstPlus) minus else plus,
                    pair.second,
                    cellWidth,
                    cellHeight,
                    insetX,
                    insetY,
                    pair.vertical,
                )
            }
        }
        onDrawWithContent {
            drawContent()
            drawPath(plus, Color.White.copy(alpha = WATERMARK_ALPHA))
            drawPath(minus, Color.Black.copy(alpha = WATERMARK_ALPHA))
        }
    }
}

private fun addWatermarkBlock(
    path: Path,
    index: Int,
    cellWidth: Float,
    cellHeight: Float,
    insetX: Float,
    insetY: Float,
    vertical: Boolean,
) {
    val row = index / WATERMARK_GRID_SIZE
    val column = index % WATERMARK_GRID_SIZE
    val width = if (vertical) WATERMARK_MARK_HEIGHT else WATERMARK_MARK_WIDTH
    val height = if (vertical) WATERMARK_MARK_WIDTH else WATERMARK_MARK_HEIGHT
    path.addRect(
        Rect(
            left = column * cellWidth + insetX,
            top = row * cellHeight + insetY,
            right = (column + width) * cellWidth - insetX,
            bottom = (row + height) * cellHeight - insetY,
        ),
    )
}

private data class WatermarkPair(
    val first: Int,
    val second: Int,
    val reversed: Boolean,
    val vertical: Boolean,
)

private fun homeSidePanelWatermarkLayout(): Array<WatermarkPair> {
    val frameBits =
        WATERMARK_MAGIC.toByteArray(StandardCharsets.US_ASCII).size * Byte.SIZE_BITS +
            Short.SIZE_BITS +
            WATERMARK_TOKEN_BYTES * Byte.SIZE_BITS +
            WATERMARK_CHECKSUM_BYTES * Byte.SIZE_BITS
    val pairCount = frameBits * WATERMARK_REPETITIONS
    val anchors =
        IntArray(WATERMARK_BLOCKS_PER_SIDE * (WATERMARK_BLOCKS_PER_SIDE - 1)) {
            val row = it / (WATERMARK_BLOCKS_PER_SIDE - 1)
            val column = it % (WATERMARK_BLOCKS_PER_SIDE - 1)
            row * WATERMARK_BLOCKS_PER_SIDE + column
        }
    val random = WatermarkPrng(WATERMARK_LAYOUT_SEED)
    for (index in anchors.lastIndex downTo 1) {
        val swap = random.nextInt(index + 1)
        val value = anchors[index]
        anchors[index] = anchors[swap]
        anchors[swap] = value
    }
    return Array(pairCount) { index ->
        val block = anchors[index]
        val blockRow = block / WATERMARK_BLOCKS_PER_SIDE
        val blockColumn = block % WATERMARK_BLOCKS_PER_SIDE
        val vertical = random.nextInt(2) == 1
        val width = if (vertical) WATERMARK_MARK_HEIGHT else WATERMARK_MARK_WIDTH
        val height = if (vertical) WATERMARK_MARK_WIDTH else WATERMARK_MARK_HEIGHT
        // Keep both patches inside the macroblock: the pair occupies two widths or two heights.
        val rowSpan = if (vertical) height * 2 else height
        val columnSpan = if (vertical) width else width * 2
        val rowOffset = random.nextInt(WATERMARK_BLOCK_SIZE - rowSpan + 1)
        val columnOffset = random.nextInt(WATERMARK_BLOCK_SIZE - columnSpan + 1)
        val first =
            (blockRow * WATERMARK_BLOCK_SIZE + rowOffset) * WATERMARK_GRID_SIZE +
                blockColumn * WATERMARK_BLOCK_SIZE +
                columnOffset
        WatermarkPair(
            first = first,
            second =
                first +
                    if (vertical) WATERMARK_MARK_WIDTH * WATERMARK_GRID_SIZE
                    else WATERMARK_MARK_WIDTH,
            reversed = random.nextInt(2) == 1,
            vertical = vertical,
        )
    }
}

/** Xorshift32 with unsigned arithmetic, mirrored by the WebUI decoder. */
private class WatermarkPrng(seed: Int) {
    private var state = seed.toLong() and 0xFFFF_FFFFL

    private fun nextUnsigned(): Long {
        state = (state xor ((state shl 13) and 0xFFFF_FFFFL)) and 0xFFFF_FFFFL
        state = (state xor (state ushr 17)) and 0xFFFF_FFFFL
        state = (state xor ((state shl 5) and 0xFFFF_FFFFL)) and 0xFFFF_FFFFL
        return state
    }

    fun nextInt(bound: Int): Int = (nextUnsigned() % bound.toLong()).toInt()
}

private fun homeSidePanelWatermarkBits(token: ByteArray): IntArray {
    val sync = WATERMARK_MAGIC.toByteArray(StandardCharsets.US_ASCII)
    val bytes = ByteArray(sync.size + Short.SIZE_BYTES + token.size + WATERMARK_CHECKSUM_BYTES)
    sync.copyInto(bytes)
    bytes[sync.size] = (token.size ushr 8).toByte()
    bytes[sync.size + 1] = token.size.toByte()
    token.copyInto(bytes, sync.size + Short.SIZE_BYTES)
    val checksum = watermarkCrc32(bytes, 0, sync.size + Short.SIZE_BYTES + token.size)
    val checksumOffset = bytes.size - WATERMARK_CHECKSUM_BYTES
    bytes[checksumOffset] = (checksum ushr 24).toByte()
    bytes[checksumOffset + 1] = (checksum ushr 16).toByte()
    bytes[checksumOffset + 2] = (checksum ushr 8).toByte()
    bytes[checksumOffset + 3] = checksum.toByte()
    return IntArray(bytes.size * Byte.SIZE_BITS) { index ->
        val value = bytes[index / Byte.SIZE_BITS].toInt()
        (value ushr (Byte.SIZE_BITS - 1 - (index % Byte.SIZE_BITS))) and 1
    }
}

private fun watermarkCrc32(bytes: ByteArray, offset: Int, length: Int): Long {
    var crc = 0xFFFF_FFFFL
    for (index in offset until offset + length) {
        crc = crc xor (bytes[index].toInt() and 0xFF).toLong()
        repeat(Byte.SIZE_BITS) {
            crc =
                if ((crc and 1L) != 0L) {
                    (crc ushr 1) xor 0xEDB88320L
                } else {
                    crc ushr 1
                }
        }
    }
    return crc xor 0xFFFF_FFFFL
}
