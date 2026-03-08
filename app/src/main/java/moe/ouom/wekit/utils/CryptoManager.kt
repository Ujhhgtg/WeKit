package moe.ouom.wekit.utils

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CryptoManager {

    companion object {
        private const val KEY_ALIAS = "wekit_tee_key"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }

    // Generate (or retrieve) a TEE-backed AES key requiring biometric auth
    @RequiresApi(Build.VERSION_CODES.R)
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)          // Requires biometric/PIN
                .setUserAuthenticationParameters(
                    0,                                         // 0 = auth required every use
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                )
                .setInvalidatedByBiometricEnrollment(true)    // Invalidate if new biometric added
                .build()
        )
        return keyGenerator.generateKey()
    }

    // Call this BEFORE showing BiometricPrompt for encryption
    @RequiresApi(Build.VERSION_CODES.R)
    fun getEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return cipher  // cipher.iv must be stored alongside ciphertext
    }

    // Call this BEFORE showing BiometricPrompt for decryption
    @RequiresApi(Build.VERSION_CODES.R)
    fun getDecryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
        return cipher
    }

    // Called after biometric success with the authorized cipher
    fun encrypt(plaintext: String, cipher: Cipher): EncryptedData {
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedData(
            ciphertext = Base64.encodeToString(ciphertext, Base64.DEFAULT),
            iv = Base64.encodeToString(cipher.iv, Base64.DEFAULT)
        )
    }

    fun decrypt(encryptedData: EncryptedData, cipher: Cipher): String {
        val ciphertext = Base64.decode(encryptedData.ciphertext, Base64.DEFAULT)
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }
}

data class EncryptedData(val ciphertext: String, val iv: String)
