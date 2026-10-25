package dev.ujhhgtg.wekit.bootstrap.auth

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

@Serializable
data class InstallationState(
    val keyAlias: String,
    val token: String,
    val registrationNonce: String,
    val registrationId: String? = null,
    val attestationChallenge: String? = null,
    val registrationExpiresAt: Long = 0,
    val installationId: String? = null,
    val bindingSessionId: String? = null,
    val bindingCode: String? = null,
    val bindingExpiresAt: Long = 0,
    val active: Boolean = false,
    val telegramUserId: String? = null,
    /** NEW_KEY uses Google/TEE attestation; UNATTESTED_KEY is an explicit low-assurance path. */
    val registrationMode: String = "NEW_KEY",
) {
    override fun toString() = "InstallationState(installationId=$installationId, active=$active)"
}

class InstallationStore(application: Application) {
    val directory = File(application.noBackupFilesDir, "wekit-loader").also {
        check(it.isDirectory || it.mkdirs())
        it.setReadable(false, false); it.setReadable(true, true)
        it.setWritable(false, false); it.setWritable(true, true)
        it.setExecutable(false, false); it.setExecutable(true, true)
    }
    private val file = File(directory, "installation.json")
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun load(): InstallationState? {
        if (!file.exists()) return null
        val json = StrictJson.objectFrom(file.inputStream().use { BootstrapApi.readBounded(it, 32768) }, 32768)
        return WireJson.format.decodeFromJsonElement<InstallationState>(json)
            .also { WireProtocol.decode(it.token, 32); WireProtocol.decode(it.registrationNonce, 32) }
    }

    fun save(state: InstallationState) {
        val bytes = WireJson.format.encodeToString(state).toByteArray(Charsets.UTF_8)
        val temporary = File(directory, "installation.${WireProtocol.requestId()}.part")
        try {
            temporary.outputStream().use { output ->
                check(temporary.setReadable(false, false) && temporary.setReadable(true, true) &&
                    temporary.setWritable(false, false) && temporary.setWritable(true, true))
                output.write(bytes)
                output.fd.sync()
            }
            check(temporary.renameTo(file)) { "Cannot publish installation state" }
        } finally { temporary.delete() }
    }

    fun candidate(previous: InstallationState?, mode: String? = null): InstallationState {
        val reuse = previous != null && hasKey(previous.keyAlias) && previous.installationId != null
        return InstallationState(
            keyAlias = if (reuse) previous.keyAlias else "wekit.remote.${WireProtocol.requestId()}",
            token = WireProtocol.encode(WireProtocol.randomBytes()),
            registrationNonce = WireProtocol.encode(WireProtocol.randomBytes()),
            installationId = if (reuse) previous.installationId else null,
            registrationMode = mode ?: if (reuse) "REGISTERED_KEY" else "NEW_KEY",
        ).also(::save)
    }

    fun hasKey(alias: String): Boolean = keyStore.containsAlias(alias)

    fun createKey(state: InstallationState) {
        check(!hasKey(state.keyAlias))
        // Always generate in the default hardware-backed keystore (TEE); StrongBox is never
        // attempted. Unlocked devices can have a broken TEE attestation path; that case falls
        // back to UNATTESTED_KEY, which the server deliberately treats as low assurance and
        // never accepts as Android-attested evidence.
        val lowAssurance = state.registrationMode == "UNATTESTED_KEY"
        val challenge = if (lowAssurance) null else state.attestationChallenge?.let { WireProtocol.decode(it, 32) }
        try {
            val builder = KeyGenParameterSpec.Builder(state.keyAlias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false)
            if (challenge != null) builder.setAttestationChallenge(challenge)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply { initialize(builder.build()) }.generateKeyPair()
        } catch (error: Throwable) {
            if (hasKey(state.keyAlias)) keyStore.deleteEntry(state.keyAlias)
            throw error
        }
        val info = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .getKeySpec(privateKey(state), KeyInfo::class.java)
        if (!lowAssurance && !info.isInsideSecureHardware) {
            keyStore.deleteEntry(state.keyAlias)
            error("Hardware-backed Android Keystore is required")
        }
    }

    fun spki(state: InstallationState) = WireProtocol.encode(keyStore.getCertificate(state.keyAlias).publicKey.encoded)
    fun fingerprint(state: InstallationState) = WireProtocol.sha256(keyStore.getCertificate(state.keyAlias).publicKey.encoded)
    fun chain(state: InstallationState) = keyStore.getCertificateChain(state.keyAlias).map {
        WireProtocol.encode(it.encoded)
    }
    fun sign(state: InstallationState, input: ByteArray): String = WireProtocol.encode(
        WireProtocol.derToRaw(Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey(state)); update(input); sign()
        }))
    private fun privateKey(state: InstallationState) = keyStore.getKey(state.keyAlias, null) as PrivateKey
}
