package dev.ujhhgtg.wekit.bootstrap.auth

import dev.ujhhgtg.wekit.bootstrap.BootstrapConfig
import dev.ujhhgtg.wekit.bootstrap.payload.LoaderNatives
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI

class RemoteApiException(val code: String, message: String, val retryable: Boolean, val requestId: String?) :
    IOException("$code: $message")

/** Synchronous API for startup and background account/download work; responses are scoped to execute. */
class BootstrapApi(private val config: BootstrapConfig, private val store: InstallationStore) {
    /** Challenges pre-issued by the server (challenge pipelining); see ChallengePool. */
    private val pool = ChallengePool(store)
    /**
     * Control-plane HTTP is dispatched by the native loader.  Keeping the
     * transport, redirect policy, and certificate pin check beside the native
     * integrity guard makes the bootstrap DEX a poor source of truth for the
     * authorization protocol.  The streaming payload path below stays on
     * Ktor because it must feed a resumable file without buffering the APK.
     */
    fun publicPost(path: String, body: JsonObject): JsonObject =
        nativeJsonRequest("POST", path, WireJson.bytes(body))

    fun signedPost(path: String, body: JsonObject, state: InstallationState, nonce: String, registration: Boolean = false): JsonObject {
        if (!registration) {
            require(body.string("installationId") == state.installationId && body.string("processNonce") == nonce)
        }
        val requestId = body.string("requestId")
        val bytes = WireJson.bytes(body)
        return retryNetwork { signedNative("POST", path, bytes, state, nonce, requestId, registration) }
    }

    private fun signedNative(
        method: String,
        path: String,
        body: ByteArray,
        state: InstallationState,
        nonce: String,
        requestId: String,
        registration: Boolean,
    ): JsonObject {
        val response = try {
            nativeJsonRequest(method, path, body,
                signedHeadersNative(method, path, body, state, nonce, requestId, registration))
        } catch (error: RemoteApiException) {
            if (error.code != "CHALLENGE_INVALID") throw error
            // A pipelined challenge can expire, or lose a cross-process race and be
            // consumed elsewhere; fetch a fresh one once before surfacing the failure.
            nativeJsonRequest(method, path, body,
                signedHeadersNative(method, path, body, state, nonce, requestId, registration,
                    forceFresh = true))
        }
        pool.offer(response.challengeTickets(), nonce)
        return response
    }

    private fun signedHeadersNative(
        method: String,
        path: String,
        body: ByteArray,
        state: InstallationState,
        nonce: String,
        requestId: String,
        registration: Boolean,
        range: String = "",
        grantId: String = "",
        forceFresh: Boolean = false,
    ): Map<String, String> {
        val scope = WireJson.body(ChallengeRequest(method, path, nonce,
            registrationId = if (registration) state.registrationId!! else null,
            installationId = if (registration) null else state.installationId!!,
            grantId = grantId.ifEmpty { null }))
        val pooled = if (forceFresh || registration) null else pool.take(method, path, nonce)
        val challengeId: String
        val serverNonce: String
        if (pooled != null) {
            challengeId = pooled.challengeId
            serverNonce = pooled.serverNonce
        } else {
            val challenge = nativeJsonRequest(
                "POST", "/v1/challenges", WireJson.bytes(scope),
                mapOf(HttpHeaders.Authorization to "Bearer ${state.token}"),
            )
            challengeId = challenge.string("challengeId")
            serverNonce = challenge.string("serverNonce")
        }
        WireProtocol.decode(serverNonce, 32)
        val signature = store.sign(state, WireProtocol.requestBytes(method, path, body, state.token,
            challengeId, serverNonce, requestId, nonce, range, grantId))
        val headers = linkedMapOf(
            HttpHeaders.Authorization to "Bearer ${state.token}",
            "X-WeKit-Challenge-Id" to challengeId,
            "X-WeKit-Request-Id" to requestId,
            "X-WeKit-Process-Nonce" to nonce,
            "X-WeKit-Signature" to signature,
            (if (registration) "X-WeKit-Registration-Id" else "X-WeKit-Installation-Id") to
                (if (registration) state.registrationId!! else state.installationId!!),
        )
        if (range.isNotEmpty()) headers[HttpHeaders.Range] = range
        if (grantId.isNotEmpty()) headers["X-WeKit-Grant-Id"] = grantId
        else headers["X-WeKit-Challenge-Pool"] = pool.floatingCount().toString()
        return headers
    }

    private fun nativeJsonRequest(
        method: String,
        path: String,
        body: ByteArray,
        requestHeaders: Map<String, String> = emptyMap(),
    ): JsonObject {
        require(path.startsWith("/v1/") && URI(path).rawPath == path && '?' !in path && '#' !in path)
        val headers = buildMap {
            put(HttpHeaders.Accept, "application/json")
            put(HttpHeaders.AcceptEncoding, "identity")
            put(HttpHeaders.CacheControl, "no-store")
            putAll(requestHeaders)
            put(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        }
        val response = runBlocking {
            withContext(Dispatchers.IO) {
                LoaderNatives.request(
                    path,
                    method,
                    headers.entries.map { (name, value) -> "$name: $value" }.toTypedArray(),
                    body,
                )
            }
        }
        if (response.size < 4) error("native HTTP response is truncated")
        val status = java.nio.ByteBuffer.wrap(response, 0, 4).int
        val bytes = response.copyOfRange(4, response.size)
        if (status !in 200..299) throw nativeError(status, bytes)
        return StrictJson.objectFrom(bytes)
    }

    private fun nativeError(status: Int, body: ByteArray): IOException {
        if (body.isNotEmpty()) {
            runCatching {
                val json = StrictJson.objectFrom(body, 32768).obj("error")
                return RemoteApiException(json.string("code"), json.string("message"),
                    json.boolean("retryable"), json.nullableString("requestId"))
            }
        }
        return IOException("Remote API returned HTTP $status")
    }

    fun download(path: String, state: InstallationState, processNonce: String, grantId: String,
        offset: Long, expectedRemaining: Long, outputFd: Int, outputOffset: Long,
    ): Long {
        require(offset >= 0)
        val range = if (offset == 0L) "" else "bytes=$offset-"
        val headers = signedHeadersNative("GET", path, ByteArray(0), state, processNonce,
            WireProtocol.requestId(), registration = false, range = range, grantId = grantId)
        val response = runBlocking {
            withContext(Dispatchers.IO) {
                LoaderNatives.download(path, headers.map { (name, value) -> "$name: $value" }.toTypedArray(), outputFd, outputOffset, expectedRemaining)
            }
        }
        require(response.size >= 4) { "native download response is truncated" }
        val status = java.nio.ByteBuffer.wrap(response, 0, 4).int
        val body = response.copyOfRange(4, response.size)
        val expected = if (range.isEmpty()) 200 else 206
        if (status != expected) throw nativeError(status, body)
        return expectedRemaining
    }

    private fun <T> retryNetwork(action: () -> T): T {
        try { return action() }
        catch (error: IOException) { if (error is RemoteApiException) throw error; return action() }
    }

    companion object {
        fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maximum) { "Local state exceeds input limit" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

}
