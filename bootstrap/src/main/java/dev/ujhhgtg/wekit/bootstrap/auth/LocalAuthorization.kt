package dev.ujhhgtg.wekit.bootstrap.auth

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.ujhhgtg.wekit.bootstrap.BootstrapLog
import dev.ujhhgtg.wekit.bootstrap.utils.fs.ensurePrivateDirectory
import dev.ujhhgtg.wekit.bootstrap.utils.fs.makeOwnerPrivate
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Short-lived local authorization for an offline payload start: the content key plus the identity
 * needed to report and watermark it, sealed with an AndroidKeyStore AES-GCM key.
 *
 * Every successful online authorization renews it, and it expires after [GRACE_MS]. An expired,
 * mismatched or corrupt record is discarded and startup falls back to the online path, so this
 * record can only ever shorten a start and never block one.
 */
@Serializable
data class LocalAuthorization(
    val installationId: String,
    val initializationId: String,
    val payloadId: String,
    val apkSha256: String,
    val cipherSha256: String,
    val manifestSha256: String,
    val issuedAt: Long,
    val expiresAt: Long,
    /** Base64url content key; only ever stored inside the sealed record. */
    val contentKey: String,
) {
    fun contentKeyBytes(): ByteArray = WireProtocol.decode(contentKey, 32)

    fun covers(installationId: String, release: VerifiedRelease): Boolean {
        val descriptor = release.descriptor
        return this.installationId == installationId &&
            initializationId.matches(INITIALIZATION_ID) &&
            payloadId == descriptor.payloadId &&
            apkSha256 == descriptor.apkSha256 &&
            cipherSha256 == descriptor.cipherSha256 &&
            manifestSha256 == release.manifestSha256
    }

    /** Never authorize a start with a record that could expire while the process is booting. */
    fun usable(now: Long): Boolean = expiresAt - EXPIRY_MARGIN_MS > now

    companion object {
        /** Offline grace period; every successful online authorization renews it. */
        const val GRACE_MS = 36L * 60 * 60 * 1000
        private const val EXPIRY_MARGIN_MS = 5L * 60 * 1000
        /** The payload only renders a watermark for an opaque 32-character initialization id. */
        private val INITIALIZATION_ID = Regex("[A-Za-z0-9_-]{32}")
    }
}

/**
 * Sealed on-disk store for [LocalAuthorization].
 *
 * The directory, file and Keystore alias names are derived hashes rather than readable WeKit names,
 * and the record itself is an opaque AES-GCM seal. This is only obfuscation (the bootstrap DEX
 * contains the derivation), but it keeps the key material out of the obvious places next to
 * `installation.json` where a casual inspection starts.
 */
class LocalAuthorizationStore(application: Application) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val root = File(application.noBackupFilesDir, "." + digest(ROOT_PREFIX).take(12))

    fun read(installationId: String): LocalAuthorization? {
        val file = recordFile(installationId)
        if (!file.isFile) return null
        return try {
            val sealed = file.inputStream().use { BootstrapApi.readBounded(it, MAX_SEALED_BYTES) }
            val plaintext = unseal(keyAlias(installationId), sealed)
            WireJson.format.decodeFromString(LocalAuthorization.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (error: Throwable) {
            BootstrapLog.e(TAG, "cannot read the local authorization; discarding it", error)
            clear(installationId)
            null
        }
    }

    fun write(record: LocalAuthorization) {
        val plaintext = WireJson.format.encodeToString(LocalAuthorization.serializer(), record)
            .toByteArray(Charsets.UTF_8)
        val sealed = seal(keyAlias(record.installationId), plaintext)
        val file = recordFile(record.installationId)
        val directory = checkNotNull(file.parentFile) { "local authorization has no directory" }
        root.ensurePrivateDirectory()
        directory.ensurePrivateDirectory()
        val temporary = File(directory, file.name + "." + WireProtocol.requestId() + ".part")
        try {
            temporary.outputStream().use { output ->
                output.write(sealed)
                output.fd.sync()
            }
            temporary.makeOwnerPrivate()
            check(temporary.renameTo(file)) { "cannot publish the local authorization" }
        } finally { temporary.delete() }
    }

    /** Removes one record and its sealing key; returns true when something was removed. */
    fun clear(installationId: String): Boolean {
        var removed = false
        val file = recordFile(installationId)
        if (file.isFile && file.delete()) removed = true
        val directory = file.parentFile
        if (directory != null && directory.isDirectory && directory.list()?.isEmpty() == true && directory.delete())
            removed = true
        val alias = keyAlias(installationId)
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
            removed = true
        }
        return removed
    }

    /** Removes every record and sealing key of this installation store; returns true when something was removed. */
    fun clearAll(): Boolean {
        var removed = false
        if (root.exists() && root.deleteRecursively()) removed = true
        for (alias in java.util.Collections.list(keyStore.aliases())) {
            if (alias.startsWith(ALIAS_PREFIX)) {
                keyStore.deleteEntry(alias)
                removed = true
            }
        }
        return removed
    }

    private fun recordFile(installationId: String): File {
        val directory = File(root, digest("d|$ROOT_PREFIX|$installationId").take(24))
        return File(directory, digest("f|$ROOT_PREFIX|$installationId").take(24) + ".dat")
    }

    private fun keyAlias(installationId: String): String =
        ALIAS_PREFIX + digest("k|$ROOT_PREFIX|$installationId").take(20)

    private fun secretKey(alias: String, create: Boolean): SecretKey? {
        if (keyStore.containsAlias(alias)) return keyStore.getKey(alias, null) as SecretKey?
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build())
        return generator.generateKey()
    }

    private fun seal(alias: String, plaintext: ByteArray): ByteArray {
        val key = checkNotNull(secretKey(alias, create = true)) { "cannot create the local authorization key" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return MAGIC + cipher.iv + cipher.doFinal(plaintext)
    }

    private fun unseal(alias: String, sealed: ByteArray): ByteArray {
        require(sealed.size > MAGIC.size + IV_SIZE) { "local authorization is truncated" }
        require(sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "invalid local authorization" }
        val key = checkNotNull(secretKey(alias, create = false)) { "local authorization key is missing" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, MAGIC.size, IV_SIZE))
        return cipher.doFinal(sealed, MAGIC.size + IV_SIZE, sealed.size - MAGIC.size - IV_SIZE)
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        private const val TAG = "Bootstrap"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val MAX_SEALED_BYTES = 8192
        /** Path/alias digest inputs; the stored names are the hashes, not these strings. */
        private const val ROOT_PREFIX = "wekit-host-store-v1"
        private const val ALIAS_PREFIX = "wk.b."
        private val MAGIC: ByteArray = MessageDigest.getInstance("SHA-256")
            .digest("wekit-local-authorization-v1".toByteArray(Charsets.UTF_8)).copyOf(8)
    }
}
