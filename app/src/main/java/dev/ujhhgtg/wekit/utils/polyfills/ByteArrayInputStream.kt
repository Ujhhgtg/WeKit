package dev.ujhhgtg.wekit.utils.polyfills

import java.io.ByteArrayInputStream
import kotlin.math.min

private const val DEFAULT_BUFFER_SIZE = 8192
private const val MAX_BUFFER_SIZE = Int.MAX_VALUE - 8

fun ByteArrayInputStream.readBytes(len: Int): ByteArray {
    require(len >= 0) { "len < 0" }

    var bufs: MutableList<ByteArray>? = null
    var result: ByteArray? = null
    var total = 0
    var remaining = len
    var n: Int

    do {
        val buf = ByteArray(min(remaining, DEFAULT_BUFFER_SIZE))
        var nread = 0

        // read to EOF which may read more or less than buffer size
        while (true) {
            n = read(buf, nread, min(buf.size - nread, remaining))
            if (n <= 0) break
            nread += n
            remaining -= n
        }

        if (nread > 0) {
            if (MAX_BUFFER_SIZE - total < nread) {
                throw OutOfMemoryError("Required array size too large")
            }

            // Trim buffer if we read less than allocated chunk size
            val currentBuf =
                if (nread < buf.size) {
                    buf.copyOfRange(0, nread)
                } else {
                    buf
                }

            total += nread
            if (result == null) {
                result = currentBuf
            } else {
                if (bufs == null) {
                    bufs = ArrayList()
                    bufs.add(result)
                }
                bufs.add(currentBuf)
            }
        }
        // if the last call to read returned -1 or the number of bytes
        // requested have been read then break
    } while (n >= 0 && remaining > 0)

    if (bufs == null) {
        if (result == null) {
            return ByteArray(0)
        }
        return if (result.size == total) result else result.copyOf(total)
    }

    val finalResult = ByteArray(total)
    var offset = 0
    remaining = total
    for (b in bufs) {
        val count = min(b.size, remaining)
        System.arraycopy(b, 0, finalResult, offset, count)
        offset += count
        remaining -= count
    }

    return finalResult
}
