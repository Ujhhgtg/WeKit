package dev.ujhhgtg.wekit.features.items.chat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** WeChat-process encrypted store for the retained read-receipts tunnel credential. */
class ReadReceiptsTunnelCredentialStore(baseDir: File) {
    private val file = AtomicFile(File(baseDir, FILE_PATH))

    fun exists(): Boolean = file.baseFile.isFile

    fun write(credential: TunnelCredentialPayload): Result<Unit> = runCatching {
        var plaintext: ByteArray? = null
        var iv: ByteArray? = null
        var encrypted: ByteArray? = null
        var filePayload: ByteArray? = null
        try {
            plaintext = TunnelCredentialPayloadCodec.encode(credential)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            iv = cipher.iv
            encrypted = cipher.doFinal(plaintext)
            filePayload = listOf(
                VERSION,
                Base64.encodeToString(iv, Base64.NO_WRAP),
                Base64.encodeToString(encrypted, Base64.NO_WRAP),
            ).joinToString("\n").toByteArray(Charsets.US_ASCII)
            require(filePayload.size <= MAX_FILE_BYTES)
            file.baseFile.parentFile!!.mkdirs()
            val output = file.startWrite()
            try {
                output.write(filePayload)
                output.fd.sync()
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        } finally {
            plaintext?.fill(0)
            iv?.fill(0)
            encrypted?.fill(0)
            filePayload?.fill(0)
        }
    }

    fun read(): Result<TunnelCredentialPayload> = runCatching {
        var filePayload: ByteArray? = null
        var iv: ByteArray? = null
        var encrypted: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            filePayload = readFileBytes()
            val envelope = filePayload.toString(Charsets.US_ASCII).split('\n')
            require(
                envelope.size == 3 &&
                    envelope[0] == VERSION,
            )
            iv = Base64.decode(envelope[1], Base64.NO_WRAP)
            encrypted = Base64.decode(envelope[2], Base64.NO_WRAP)
            require(iv.size == IV_BYTES)
            require(encrypted.size in GCM_TAG_BYTES..TunnelCredentialPayloadCodec.MAX_BYTES + GCM_TAG_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            plaintext = cipher.doFinal(encrypted)
            val decoded = TunnelCredentialPayloadCodec.decode(plaintext)
            require(decoded is TunnelCredentialDecode.Decoded)
            decoded.payload
        } finally {
            filePayload?.fill(0)
            iv?.fill(0)
            encrypted?.fill(0)
            plaintext?.fill(0)
        }
    }.onFailure { clear() }

    fun readMetadata(): Result<CommittedTunnelCredentialMetadata> =
        read().map(TunnelCredentialPayload::committedMetadata)

    fun clear() {
        file.delete()
    }

    private fun readFileBytes(): ByteArray {
        val scratch = ByteArray(MAX_FILE_BYTES + 1)
        try {
            var size = 0
            file.openRead().use { input ->
                while (size < scratch.size) {
                    val count = input.read(scratch, size, scratch.size - size)
                    if (count < 0) break
                    size += count
                }
                require(size <= MAX_FILE_BYTES && input.read() < 0)
            }
            return scratch.copyOf(size)
        } finally {
            scratch.fill(0)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val FILE_PATH = "wekit-read-receipts/tunnel_credential.v1"
        private const val VERSION = "2"
        private const val MAX_FILE_BYTES = 64 * 1024
        private const val IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val KEY_ALIAS = "wekit_read_receipts_tunnel_v2"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
