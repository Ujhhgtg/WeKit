package dev.ujhhgtg.wekit.utils.livephoto

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * ISO MotionPhotoMetadataDescriptor carried by an MP4 `mett` sample (not protobuf).
 * https://developer.android.com/media/platform/motion-photo-format#syntax
 *
 * The C3 primary frame timestamp is a fixed-width big-endian int64. Updating those eight bytes
 * leaves MP4 box lengths, sample sizes, chunk offsets, and all media intact.
 */
object MotionPhotoVideoMetadata {
    data class FrameTimestamp(val timestampUs: Long, val offset: Long)

    data class Info(val frames: List<FrameTimestamp>)

    /** Timestamp offsets in [Info] are relative to the beginning of this MP4. */
    fun inspect(channel: FileChannel, start: Long, length: Long): Info? =
        MotionPhotoNative.metadata(
            MotionPhotoNative.decode(
                MotionPhotoNative.inspectMetadata(channel, channel.size(), start, length),
            ),
        )

    /** Mutates only an already copied MP4; the source sample layout is unchanged. */
    fun synchronize(output: FileChannel, videoStart: Long, info: Info, timestampUs: Long) {
        if (timestampUs < -1) fail("Invalid motion metadata timestamp")
        for ((_, offset) in info.frames) {
            val position = checkedAdd(videoStart, offset)
            if (position < 0 || position > output.size() - 8)
                fail("Motion metadata timestamp is outside output")
            val buffer = ByteBuffer.allocate(8).putLong(timestampUs)
            buffer.flip()
            while (buffer.hasRemaining()) {
                if (output.write(buffer, position + buffer.position()) <= 0)
                    fail("Could not write motion metadata timestamp")
            }
        }
    }

    private fun checkedAdd(first: Long, second: Long): Long =
        try {
            Math.addExact(first, second)
        } catch (error: ArithmeticException) {
            throw IOException("File offset overflow", error)
        }

    private fun fail(message: String): Nothing = throw IOException(message)
}
