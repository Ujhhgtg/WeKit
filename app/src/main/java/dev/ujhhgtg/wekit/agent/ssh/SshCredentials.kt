package dev.ujhhgtg.wekit.agent.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface SshCredentials {
    data class Password(val password: String) : SshCredentials
    data class PrivateKey(val privateKey: String, val passphrase: String? = null) : SshCredentials
}

data class EncryptedSshCredentials(val ciphertext: ByteArray, val iv: ByteArray)

class SshCredentialUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

object SshCredentialStore {
    private const val KEY_ALIAS = "weagent-ssh-credentials-v1"
    private const val KEYSTORE = "AndroidKeyStore"

    fun encrypt(credentials: SshCredentials): EncryptedSshCredentials = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        EncryptedSshCredentials(cipher.doFinal(SshCredentialCodec.encode(credentials)), cipher.iv)
    } catch (error: Exception) {
        throw SshCredentialUnavailableException("cannot encrypt SSH credentials", error)
    }

    fun decrypt(encrypted: EncryptedSshCredentials): SshCredentials = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.iv))
        SshCredentialCodec.decode(cipher.doFinal(encrypted.ciphertext))
    } catch (error: Exception) {
        throw SshCredentialUnavailableException("SSH credentials must be entered again", error)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

internal object SshCredentialCodec {
    private const val PASSWORD = 1
    private const val PRIVATE_KEY = 2

    fun encode(credentials: SshCredentials): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            when (credentials) {
                is SshCredentials.Password -> {
                    output.writeByte(PASSWORD)
                    output.writeString(credentials.password)
                }
                is SshCredentials.PrivateKey -> {
                    output.writeByte(PRIVATE_KEY)
                    output.writeString(credentials.privateKey)
                    output.writeBoolean(credentials.passphrase != null)
                    credentials.passphrase?.let { output.writeString(it) }
                }
            }
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): SshCredentials = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val credentials = when (input.readUnsignedByte()) {
            PASSWORD -> SshCredentials.Password(input.readString())
            PRIVATE_KEY -> SshCredentials.PrivateKey(
                input.readString(),
                if (input.readBoolean()) input.readString() else null,
            )
            else -> error("unsupported SSH credential format")
        }
        require(input.read() == -1) { "trailing SSH credential data" }
        credentials
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16 * 1024 * 1024) { "SSH credential value is too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..16 * 1024 * 1024) { "invalid SSH credential value length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}
