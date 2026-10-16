package dev.ujhhgtg.wekit.bootstrap.payload

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pure protocol code; the consumer receives only authenticated blocks, never an unverified tag. */
object PayloadCipher {
    fun decrypt(
        input: InputStream,
        manifest: PayloadDescriptor,
        key: ByteArray,
        consume: (ByteArray) -> Unit,
    ) {
        require(key.size == 32)
        val cipherDigest = MessageDigest.getInstance("SHA-256")
        val apkDigest = MessageDigest.getInstance("SHA-256")
        val aadPrefix = "WEKIT-APK-V1\n${manifest.payloadId}\n${manifest.apkSha256}\n".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        var plaintextSize = 0L
        repeat(manifest.chunkCount) { index ->
            val blockSize = minOf(manifest.chunkSize.toLong(), manifest.apkSize - plaintextSize).toInt()
            val encrypted = ByteArray(blockSize + PayloadDescriptor.TAG_SIZE)
            var offset = 0
            while (offset < encrypted.size) {
                val read = input.read(encrypted, offset, encrypted.size - offset)
                if (read == -1) throw EOFException("incomplete encrypted APK block $index")
                offset += read
            }
            cipherDigest.update(encrypted)
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                .put(manifest.noncePrefix).putInt(index).array()
            val aad = ByteBuffer.allocate(aadPrefix.size + 16).order(ByteOrder.BIG_ENDIAN)
                .put(aadPrefix).putLong(manifest.apkSize).putInt(manifest.chunkCount).putInt(index).array()
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val plaintext = try {
                cipher.doFinal(encrypted)
            } finally {
                encrypted.fill(0)
            }
            try {
                check(plaintext.size == blockSize) { "invalid GCM plaintext length" }
                apkDigest.update(plaintext)
                consume(plaintext)
            } finally {
                plaintext.fill(0)
            }
            plaintextSize += blockSize
        }
        check(input.read() == -1) { "trailing encrypted APK data" }
        check(plaintextSize == manifest.apkSize)
        check(cipherDigest.digest().hex() == manifest.cipherSha256) { "ciphertext SHA-256 mismatch" }
        check(apkDigest.digest().hex() == manifest.apkSha256) { "APK SHA-256 mismatch" }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
