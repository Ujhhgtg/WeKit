package dev.ujhhgtg.wekit.bootstrap.payload

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PayloadCipherTest {
    private val key = ByteArray(32) { it.toByte() }
    private val prefix = "a0a1a2a3a4a5a6a7".unhex()
    private val smallCipher = "fd4dcb9e166a1b5f5ce377274396c4a8b5b15bbaece724145628f1a617bdd5408ce9d68757b070a680e90d32c297d2".unhex()
    private val smallManifest = descriptor(
        31,
        "64cb82bc3829555059e7e7d0cbc070bb370ca3cefc671e919a51086294f842ff",
        "0bba9036b7a5a4c4873d18434d8715cca5aaedede73af71cfb0e276f223c0736",
    )

    @Test
    fun `decrypts externally generated fixture through fragmented input`() {
        val stream = object : FilterInputStream(ByteArrayInputStream(smallCipher)) {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int = super.read(bytes, offset, minOf(count, 7))
        }
        val output = ByteArrayOutputStream()
        PayloadCipher.decrypt(stream, smallManifest, key, output::write)
        assertArrayEquals(pattern(31), output.toByteArray())
    }

    @Test
    fun `matches frozen Python Node workerd vectors at block boundaries`() {
        for ((size, apkHash, cipherHash) in listOf(
            Triple(1048576, "470952a05336a638e11755d028432cb890c3240d0b33668038a975e7e3b5b4ef", "9a56c68309da6d7c7c053914e87e612e9a440795d43eb9443b9284fceb052f8c"),
            Triple(1048595, "57e9aa0624f2276423f1e2757ad70901a37d639fdf906bb80453d6e89f7734cf", "43d399d7072e4ea38669e3f6fbc90597d31cc4f348d4c2dc6f500f4af6a0eca1"),
        )) {
            val manifest = descriptor(size, apkHash, cipherHash)
            val plaintext = pattern(size)
            val encrypted = encryptFixture(plaintext, manifest)
            assertEquals(cipherHash, encrypted.sha256())
            val output = ByteArrayOutputStream()
            PayloadCipher.decrypt(encrypted.inputStream(), manifest, key, output::write)
            assertArrayEquals(plaintext, output.toByteArray())
        }
    }

    @Test
    fun `bad tag releases no bytes from the unauthenticated block`() {
        val damaged = smallCipher.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        var published = 0
        assertThrows(AEADBadTagException::class.java) {
            PayloadCipher.decrypt(damaged.inputStream(), smallManifest, key) { published += it.size }
        }
        assertEquals(0, published)
    }

    @Test
    fun `rejects truncated tag and trailing ciphertext`() {
        assertThrows(EOFException::class.java) {
            PayloadCipher.decrypt(smallCipher.copyOf(smallCipher.size - 1).inputStream(), smallManifest, key) { }
        }
        assertThrows(IllegalStateException::class.java) {
            PayloadCipher.decrypt((smallCipher + 0.toByte()).inputStream(), smallManifest, key) { }
        }
    }

    @Test
    fun `rejects a different payload identity even with the same key`() {
        assertThrows(AEADBadTagException::class.java) {
            PayloadCipher.decrypt(smallCipher.inputStream(), smallManifest.copy(payloadId = "another-release"), key) { }
        }
    }

    @Test
    fun `checks ciphertext and plaintext release digests after block authentication`() {
        assertThrows(IllegalStateException::class.java) {
            PayloadCipher.decrypt(smallCipher.inputStream(), smallManifest.copy(cipherSha256 = "0".repeat(64)), key) { }
        }
        val wrongPlaintextManifest = smallManifest.copy(apkSha256 = "0".repeat(64))
        val cipher = encryptFixture(pattern(31), wrongPlaintextManifest)
        assertThrows(IllegalStateException::class.java) {
            PayloadCipher.decrypt(cipher.inputStream(), wrongPlaintextManifest.copy(cipherSha256 = cipher.sha256()), key) { }
        }
    }

    @Test
    fun `block indexes authenticate ordering`() {
        val plaintext = pattern(2 * PayloadDescriptor.CHUNK_SIZE)
        val manifest = descriptor(plaintext.size, plaintext.sha256(), "0".repeat(64))
        val encrypted = encryptFixture(plaintext, manifest)
        val firstBlockSize = PayloadDescriptor.CHUNK_SIZE + PayloadDescriptor.TAG_SIZE
        val reordered = encrypted.copyOfRange(firstBlockSize, encrypted.size) + encrypted.copyOfRange(0, firstBlockSize)
        assertThrows(AEADBadTagException::class.java) {
            PayloadCipher.decrypt(reordered.inputStream(), manifest.copy(cipherSha256 = reordered.sha256()), key) { }
        }
    }

    private fun descriptor(size: Int, apkHash: String, cipherHash: String): PayloadDescriptor {
        val chunks = (size + PayloadDescriptor.CHUNK_SIZE - 1) / PayloadDescriptor.CHUNK_SIZE
        return PayloadDescriptor("test-payload-v1", "test.PayloadEntry", apkHash, size.toLong(), cipherHash,
            size + chunks * 16L, prefix, chunks)
    }

    private fun encryptFixture(plaintext: ByteArray, manifest: PayloadDescriptor): ByteArray {
        val output = ByteArrayOutputStream()
        val aadPrefix = "WEKIT-APK-V1\n${manifest.payloadId}\n${manifest.apkSha256}\n".toByteArray()
        repeat(manifest.chunkCount) { index ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).array()
            val aad = ByteBuffer.allocate(aadPrefix.size + 16).put(aadPrefix)
                .putLong(manifest.apkSize).putInt(manifest.chunkCount).putInt(index).array()
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val start = index * manifest.chunkSize
            output.write(cipher.doFinal(plaintext, start, minOf(manifest.chunkSize, plaintext.size - start)))
        }
        return output.toByteArray()
    }

    private fun pattern(size: Int): ByteArray = ByteArray(size) { (it * 17 + 3).toByte() }
    private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
