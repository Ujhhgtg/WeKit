package dev.ujhhgtg.wekit.bootstrap.auth

import android.os.ParcelFileDescriptor
import dev.ujhhgtg.wekit.bootstrap.BootstrapConfig
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class OpenPayloadCache(
    val fd: ParcelFileDescriptor,
    val offset: Long,
    val release: VerifiedRelease,
) : AutoCloseable {
    override fun close() = fd.close()
}

class PayloadCache(private val store: InstallationStore, private val config: BootstrapConfig) {
    private val destination = File(store.directory, "payload.cache")
    private val partial = File(store.directory, "payload.cache.part")
    fun clear() {
        if (destination.exists()) check(destination.delete()) { "Cannot remove damaged payload cache" }
    }

    /** Removes the published cache and any unfinished download; returns true when something was removed. */
    fun clearAll(): Boolean {
        var removed = false
        for (file in listOf(destination, partial)) {
            if (file.exists() && file.delete()) removed = true
        }
        return removed
    }

    fun open(): OpenPayloadCache? {
        if (!destination.isFile) return null
        val fd = ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            // The pathname is used only to obtain an independent read position on this same open inode.
            RandomAccessFile("/proc/self/fd/${fd.fd}", "r").use { input ->
                val magic = ByteArray(8).also(input::readFully)
                require(magic.contentEquals(MAGIC)) { "Invalid payload cache magic" }
                val length = input.readInt()
                require(length in 1..65536) { "Invalid manifest envelope length" }
                val envelope = ByteArray(length).also(input::readFully)
                val release = ReleaseManifest.verify(envelope, config)
                val offset = 12L + length
                require(fd.statSize == offset + release.descriptor.cipherSize) { "Incomplete payload cache" }
                return OpenPayloadCache(fd, offset, release)
            }
        } catch (error: Throwable) { fd.close(); throw error }
    }

    fun download(api: BootstrapApi, state: InstallationState, processNonce: String, authorization: kotlinx.serialization.json.JsonObject, release: VerifiedRelease) {
        val descriptor = release.descriptor
        require(authorization.string("payloadId") == descriptor.payloadId)
        val path = authorization.string("downloadPath")
        require(path == "/v1/payloads/${descriptor.payloadId}/content")
        var grantId = authorization.string("grantId")
        var expiresAt = authorization.integer("grantExpiresAt")
        val initializationId = authorization.string("initializationId")
        val headerSize = 12L + release.envelopeBytes.size
        partial.delete()
        try {
            RandomAccessFile(partial, "rw").use { output ->
                check(partial.setReadable(false, false) && partial.setReadable(true, true) &&
                    partial.setWritable(false, false) && partial.setWritable(true, true))
                output.write(MAGIC); output.writeInt(release.envelopeBytes.size); output.write(release.envelopeBytes)
                var received = 0L
                var networkRetries = 0
                var deniedRenewed = false
                fun renewGrant() {
                    val body = WireJson.body(RenewGrantRequest(WireProtocol.requestId(), state.installationId!!,
                        processNonce, initializationId, grantId, descriptor.payloadId, descriptor.apkSha256))
                    val renewed = api.signedPost("/v1/load-grants/renew", body, state, processNonce)
                    require(renewed.string("payloadId") == descriptor.payloadId)
                    grantId = renewed.string("grantId"); expiresAt = renewed.integer("grantExpiresAt")
                }
                ParcelFileDescriptor.dup(output.fd).use { nativeOutput ->
                    while (received < descriptor.cipherSize) {
                        if (expiresAt - System.currentTimeMillis() < 10000) renewGrant()
                        try {
                            val count = api.download(path, state, processNonce, grantId, received,
                                descriptor.cipherSize - received, nativeOutput.fd, headerSize + received)
                            received += count
                        } catch (error: java.io.IOException) {
                            if (error is RemoteApiException) {
                                // The server clock is authoritative. Renewal rechecks revocation before retrying.
                                if (error.code != "AUTHORIZATION_DENIED" || deniedRenewed) throw error
                                deniedRenewed = true; renewGrant()
                            } else if (networkRetries++ >= 2) throw error
                        }
                    }
                }
                check(sha256Range(partial, headerSize, descriptor.cipherSize) == descriptor.cipherSha256) {
                    "Payload ciphertext checksum failed"
                }
                check(output.length() == headerSize + descriptor.cipherSize)
                output.fd.sync()
            }
            check(partial.renameTo(destination)) { "Cannot publish payload cache" }
        } finally { partial.delete() }
    }

    private fun sha256Range(file: File, offset: Long, length: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "payload cache ended before ciphertext checksum" }
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        return WireProtocol.hex(digest.digest())
    }

    companion object { private val MAGIC = "WKPC0001".toByteArray(Charsets.US_ASCII) }
}
