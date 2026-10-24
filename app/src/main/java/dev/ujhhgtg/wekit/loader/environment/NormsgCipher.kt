package dev.ujhhgtg.wekit.loader.environment

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixed-work inverse of the audited normsg table network. No AES key is assumed. */
class NormsgCipher(tables: ByteArray) {
    private class Column(buffer: ByteBuffer) {
        val constant = buffer.int
        val decode = ByteArray(1024).also(buffer::get)
        val encode = ByteArray(1024).also(buffer::get)
        val forward = IntArray(1024) { buffer.int }
        val inverse = IntArray(1024) { buffer.int }
        val inputInverse = ByteArray(1024).also(buffer::get)
    }

    private val columns: Array<Column>
    private val final: ByteArray
    private val finalInverse: ByteArray

    init {
        require(tables.size == 413880 && String(tables, 0, 8, Charsets.US_ASCII) == "WKCD0001")
        val buffer = ByteBuffer.wrap(tables).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(40) // Magic + fingerprint of the source tables.
        columns = Array(36) { Column(buffer) }
        final = ByteArray(4096).also(buffer::get)
        finalInverse = ByteArray(4096).also(buffer::get)
    }

    private val iv =
        byteArrayOf(
            0x62,
            0x7d,
            0x1c,
            0x7e,
            0x3d,
            0xcd.toByte(),
            0x67,
            0x92.toByte(),
            0x59,
            0xcf.toByte(),
            0x2d,
            0x98.toByte(),
            0x48,
            0x58,
            0xf9.toByte(),
            0xfa.toByte(),
        )

    fun transform(input: ByteArray, decrypt: Boolean): ByteArray {
        require(input.size % 16 == 0)
        val output = ByteArray(input.size)
        val previous = iv.copyOf()
        var state = IntArray(16)
        var next = IntArray(16)
        for (offset in input.indices step 16) {
            for (row in 0..3) for (col in 0..3) {
                if (decrypt) {
                    val shifted = (col - row + 4) % 4
                    state[row * 4 + col] =
                        finalInverse[
                                (row * 4 + shifted) * 256 +
                                    (input[offset + shifted * 4 + row].toInt() and 255)]
                            .toInt() and 255
                } else {
                    val i = col * 4 + row
                    state[row * 4 + col] =
                        (input[offset + i].toInt() xor previous[i].toInt()) and 255
                }
            }
            for (iteration in 0..8) {
                val round = if (decrypt) 8 - iteration else iteration
                for (col in 0..3) {
                    val c = columns[round * 4 + col]
                    if (decrypt) {
                        var word = c.constant
                        for (row in 0..3) word =
                            word xor
                                ((c.decode[row * 256 + state[row * 4 + col]].toInt() and 255) shl
                                    (row * 8))
                        var coordinates = 0
                        for (row in 0..3) coordinates =
                            coordinates xor c.inverse[row * 256 + ((word ushr (row * 8)) and 255)]
                        for (row in 0..3) next[row * 4 + (col + row) % 4] =
                            c.inputInverse[row * 256 + ((coordinates ushr (row * 8)) and 255)]
                                .toInt() and 255
                    } else {
                        var word = 0
                        for (row in 0..3) word =
                            word xor c.forward[row * 256 + state[row * 4 + (col + row) % 4]]
                        for (row in 0..3) next[row * 4 + col] =
                            c.encode[row * 256 + ((word ushr (row * 8)) and 255)].toInt() and 255
                    }
                }
                val swap = state
                state = next
                next = swap
            }
            for (row in 0..3) for (col in 0..3) {
                val i = col * 4 + row
                output[offset + i] =
                    if (decrypt) {
                        (state[row * 4 + col] xor previous[i].toInt()).toByte()
                    } else {
                        final[(row * 4 + col) * 256 + state[row * 4 + (col + row) % 4]]
                    }
            }
            (if (decrypt) input else output).copyInto(previous, 0, offset, offset + 16)
        }
        return output
    }
}
