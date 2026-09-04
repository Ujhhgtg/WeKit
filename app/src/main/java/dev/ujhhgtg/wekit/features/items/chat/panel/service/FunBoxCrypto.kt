package dev.ujhhgtg.wekit.features.items.chat.panel.service

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

object FunBoxCrypto {
    private val curveP = BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFF", 16)
    private val curveA = BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFC", 16)
    private val curveB = BigInteger("28E9FA9E9D9F5E344D5A9E4BCF6509A7F39789F515AB8F92DDBCBD414D940E93", 16)
    private val curveN = BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFF7203DF6B21C6052B53BBF40939D54123", 16)
    private val generator = Point(
        BigInteger("32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7", 16),
        BigInteger("BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0", 16),
    )
    private val serverPublic = Point(
        BigInteger("D185A433AA9DA51E898E8C32F4CB8EB4EB9F8A063E41588670F1789C79CD0142", 16),
        BigInteger("1228586967283B151A12D72A039B92C31D527FE0BC4CAA897C114A6AB0B47209", 16),
    )
    private val secureRandom = SecureRandom()

    fun teaEncrypt(keyText: String, input: ByteArray): ByteArray = tea(keyText, input, encrypt = true)
    fun teaDecrypt(keyText: String, input: ByteArray): ByteArray = tea(keyText, input, encrypt = false)

    fun decryptObject(input: ByteArray, securityKey: String): ByteArray {
        val key = securityKey.toByteArray(Charsets.UTF_8)
        require(key.size == 16) { "FunBox object security key must be 16 bytes" }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
            doFinal(input)
        }
    }

    fun sm2Encrypt(input: ByteArray): ByteArray {
        require(input.isNotEmpty()) { "SM2 输入不能为空" }
        while (true) {
            val k = randomScalar()
            val c1 = multiply(generator, k)
            val shared = multiply(serverPublic, k)
            val sharedPoint = encodePoint(shared)
            val mask = sm2Kdf(sharedPoint, input.size)
            if (mask.all { it == 0.toByte() }) continue
            val c2 = ByteArray(input.size) { index -> input[index] xor mask[index] }
            val c3 = sm3(shared.x.toByteArray() + input + shared.y.toByteArray())
            return encodePoint(c1) + c2 + c3
        }
    }

    fun sm3(input: ByteArray): ByteArray {
        val padded = padSm3(input)
        var state = intArrayOf(
            0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
            0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt(),
        )
        padded.asList().chunked(64).forEach { chunkList ->
            val chunk = chunkList.toByteArray()
            val w = IntArray(68)
            val w1 = IntArray(64)
            for (j in 0 until 16) w[j] = chunk.readIntBe(j * 4)
            for (j in 16 until 68) {
                val x = w[j - 16] xor w[j - 9] xor Integer.rotateLeft(w[j - 3], 15)
                w[j] = p1(x) xor Integer.rotateLeft(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) w1[j] = w[j] xor w[j + 4]
            var a = state[0]
            var b = state[1]
            var c = state[2]
            var d = state[3]
            var e = state[4]
            var f = state[5]
            var g = state[6]
            var h = state[7]
            for (j in 0 until 64) {
                val tj = if (j < 16) 0x79cc4519 else 0x7a879d8a
                val ss1 = Integer.rotateLeft(Integer.rotateLeft(a, 12) + e + Integer.rotateLeft(tj, j), 7)
                val ss2 = ss1 xor Integer.rotateLeft(a, 12)
                val tt1 = ff(a, b, c, j) + d + ss2 + w1[j]
                val tt2 = gg(e, f, g, j) + h + ss1 + w[j]
                d = c
                c = Integer.rotateLeft(b, 9)
                b = a
                a = tt1
                h = g
                g = Integer.rotateLeft(f, 19)
                f = e
                e = p0(tt2)
            }
            state = intArrayOf(
                state[0] xor a, state[1] xor b, state[2] xor c, state[3] xor d,
                state[4] xor e, state[5] xor f, state[6] xor g, state[7] xor h,
            )
        }
        return ByteArrayOutputStream(32).apply { state.forEach { write(it.toBytesBe()) } }.toByteArray()
    }

    private fun tea(keyText: String, input: ByteArray, encrypt: Boolean): ByteArray {
        val key = keyText.toByteArray().copyOf(16)
        val data = if (input.size % 8 == 0) input.copyOf() else input.copyOf(input.size + 8 - input.size % 8)
        val k = IntArray(4) { key.readIntBe(it * 4) }
        for (offset in data.indices step 8) {
            var v0 = data.readIntBe(offset)
            var v1 = data.readIntBe(offset + 4)
            var sum = if (encrypt) 0 else 0xc6ef3720.toInt()
            repeat(32) {
                if (encrypt) {
                    sum -= 0x61c88647
                    v0 += (v1 shl 4) + k[0] xor v1 + sum xor (v1 shr 5) + k[1]
                    v1 += (v0 shl 4) + k[2] xor v0 + sum xor (v0 shr 5) + k[3]
                } else {
                    v1 -= (v0 shl 4) + k[2] xor v0 + sum xor (v0 shr 5) + k[3]
                    v0 -= (v1 shl 4) + k[0] xor v1 + sum xor (v1 shr 5) + k[1]
                    sum += 0x61c88647
                }
            }
            v0.writeBe(data, offset)
            v1.writeBe(data, offset + 4)
        }
        return data
    }

    private fun sm2Kdf(seed: ByteArray, length: Int): ByteArray {
        val output = ByteArrayOutputStream(length)
        var counter = 1
        while (output.size() < length) {
            output.write(sm3(seed + counter.toBytesBe()))
            counter++
        }
        return output.toByteArray().copyOf(length)
    }

    private fun randomScalar(): BigInteger {
        var value: BigInteger
        do value = BigInteger(curveN.bitLength(), secureRandom) while (value == BigInteger.ZERO || value >= curveN)
        return value
    }

    private fun multiply(point: Point, scalar: BigInteger): Point {
        var result: Point? = null
        var addend = point
        var k = scalar
        while (k.signum() > 0) {
            if (k.testBit(0)) result = if (result == null) addend else add(result, addend)
            addend = double(addend)
            k = k.shiftRight(1)
        }
        return requireNotNull(result)
    }

    private fun add(first: Point, second: Point): Point {
        if (first.x == second.x) return if (first.y == second.y) double(first) else error("SM2 point at infinity")
        val lambda = mod(second.y - first.y) * mod(second.x - first.x).modInverse(curveP) % curveP
        val x = mod(lambda * lambda - first.x - second.x)
        return Point(x, mod(lambda * (first.x - x) - first.y))
    }

    private fun double(point: Point): Point {
        val lambda = mod(BigInteger.valueOf(3) * point.x * point.x + curveA) *
                mod(BigInteger.valueOf(2) * point.y).modInverse(curveP) % curveP
        val x = mod(lambda * lambda - BigInteger.valueOf(2) * point.x)
        return Point(x, mod(lambda * (point.x - x) - point.y))
    }

    private fun mod(value: BigInteger): BigInteger = value.mod(curveP)
    private fun encodePoint(point: Point) = byteArrayOf(4) + point.x.toFixedUnsigned(32) + point.y.toFixedUnsigned(32)
    private fun p0(x: Int) = x xor Integer.rotateLeft(x, 9) xor Integer.rotateLeft(x, 17)
    private fun p1(x: Int) = x xor Integer.rotateLeft(x, 15) xor Integer.rotateLeft(x, 23)
    private fun ff(x: Int, y: Int, z: Int, j: Int) = if (j < 16) x xor y xor z else x and y or (x and z) or (y and z)
    private fun gg(x: Int, y: Int, z: Int, j: Int) = if (j < 16) x xor y xor z else x and y or (x.inv() and z)

    private fun padSm3(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        val padding = (56 - (input.size + 1) % 64 + 64) % 64
        return input + byteArrayOf(0x80.toByte()) + ByteArray(padding) + ByteArray(8) { index ->
            (bitLength ushr (7 - index) * 8).toByte()
        }
    }

    private data class Point(val x: BigInteger, val y: BigInteger)
}

private fun BigInteger.toFixedUnsigned(size: Int): ByteArray {
    val raw = toByteArray()
    val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    require(unsigned.size <= size)
    return ByteArray(size - unsigned.size) + unsigned
}

private fun ByteArray.readIntBe(offset: Int) =
    this[offset].toInt() and 0xff shl 24 or (this[offset + 1].toInt() and 0xff shl 16) or
            (this[offset + 2].toInt() and 0xff shl 8) or (this[offset + 3].toInt() and 0xff)

private fun Int.writeBe(target: ByteArray, offset: Int) {
    repeat(4) { index -> target[offset + index] = (this ushr (3 - index) * 8).toByte() }
}

private fun Int.toBytesBe() = ByteArray(4) { index -> (this ushr (3 - index) * 8).toByte() }
