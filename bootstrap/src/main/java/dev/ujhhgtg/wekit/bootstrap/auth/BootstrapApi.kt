package dev.ujhhgtg.wekit.bootstrap.auth

import dev.ujhhgtg.wekit.bootstrap.BootstrapConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.CertificatePinner
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI

class RemoteApiException(val code: String, message: String, val retryable: Boolean, val requestId: String?) :
    IOException("$code: $message")

/** Synchronous API for startup and background account/download work; responses are scoped to execute. */
class BootstrapApi(private val config: BootstrapConfig, private val store: InstallationStore) {
    private val client = pinnedClient(config)

    private fun pinnedClient(bootstrap: BootstrapConfig): HttpClient {
        require(bootstrap.tlsPins.isNotEmpty())
        // The pinner trusts only these CA keys for the API host, on top of normal chain validation.
        val pinner = CertificatePinner.Builder().apply {
            bootstrap.tlsPins.forEach { add(URI(bootstrap.apiOrigin).host, "sha256/$it") }
        }.build()
        return HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) { connectTimeoutMillis = 15000; socketTimeoutMillis = 30000 }
            engine { config { retryOnConnectionFailure(false); certificatePinner(pinner) } }
        }
    }

    fun publicPost(path: String, body: JsonObject): JsonObject = runBlocking {
        request("POST", path, WireJson.bytes(body)).execute { jsonResponse(it) }
    }

    fun signedPost(path: String, body: JsonObject, state: InstallationState, nonce: String, registration: Boolean = false): JsonObject {
        if (!registration) {
            require(body.string("installationId") == state.installationId && body.string("processNonce") == nonce)
        }
        val requestId = body.string("requestId")
        val bytes = WireJson.bytes(body)
        return retryNetwork {
            runBlocking { signed("POST", path, bytes, state, nonce, requestId, registration = registration).execute { jsonResponse(it) } }
        }
    }

    fun download(path: String, state: InstallationState, processNonce: String, grantId: String,
        offset: Long, expectedRemaining: Long, consume: (ByteArray, Int) -> Unit,
    ) = runBlocking {
        require(offset >= 0)
        val range = if (offset == 0L) "" else "bytes=$offset-"
        signed("GET", path, ByteArray(0), state, processNonce, WireProtocol.requestId(), range, grantId).execute { response ->
            val expected = if (range.isEmpty()) 200 else 206
            if (response.status.value != expected) throw error(response)
            require(response.headers[HttpHeaders.ContentEncoding].let { it == null || it == "identity" })
            require(response.headers[HttpHeaders.ContentLength]?.toLong() == expectedRemaining) { "Download length differs from signed size" }
            if (offset != 0L) require(response.headers[HttpHeaders.ContentRange]?.startsWith("bytes $offset-") == true)
            val input = response.bodyAsChannel()
            val buffer = ByteArray(65536)
            var received = 0L
            while (true) {
                val count = input.readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                if (count == 0) continue
                require(received + count <= expectedRemaining) { "Download exceeds signed size" }
                consume(buffer, count); received += count
            }
            if (received != expectedRemaining) throw java.io.EOFException("Payload download interrupted")
        }
    }

    private suspend fun signed(method: String, path: String, body: ByteArray, state: InstallationState, nonce: String,
        requestId: String, range: String = "", grantId: String = "", registration: Boolean = false,
    ): HttpStatement {
        val scope = WireJson.body(ChallengeRequest(method, path, nonce,
            registrationId = if (registration) state.registrationId!! else null,
            installationId = if (registration) null else state.installationId!!,
            grantId = grantId.ifEmpty { null }))
        val challenge = request("POST", "/v1/challenges", WireJson.bytes(scope),
            mapOf(HttpHeaders.Authorization to "Bearer ${state.token}")).execute { jsonResponse(it) }
        val challengeId = challenge.string("challengeId")
        val serverNonce = challenge.string("serverNonce")
        WireProtocol.decode(serverNonce, 32)
        val signature = store.sign(state, WireProtocol.requestBytes(method, path, body, state.token,
            challengeId, serverNonce, requestId, nonce, range, grantId))
        val headers = linkedMapOf(HttpHeaders.Authorization to "Bearer ${state.token}",
            "X-WeKit-Challenge-Id" to challengeId, "X-WeKit-Request-Id" to requestId,
            "X-WeKit-Process-Nonce" to nonce, "X-WeKit-Signature" to signature,
            (if (registration) "X-WeKit-Registration-Id" else "X-WeKit-Installation-Id") to
                (if (registration) state.registrationId!! else state.installationId!!))
        if (range.isNotEmpty()) headers[HttpHeaders.Range] = range
        if (grantId.isNotEmpty()) headers["X-WeKit-Grant-Id"] = grantId
        return request(method, path, body, headers)
    }

    private suspend fun request(methodName: String, path: String, bytes: ByteArray,
        requestHeaders: Map<String, String> = emptyMap(),
    ): HttpStatement {
        require(path.startsWith("/v1/") && URI(path).rawPath == path && '?' !in path && '#' !in path)
        return client.prepareRequest(config.apiOrigin + path) {
            method = HttpMethod.parse(methodName)
            headers {
                append(HttpHeaders.Accept, if (methodName == "GET") "application/octet-stream" else "application/json")
                append(HttpHeaders.AcceptEncoding, "identity"); append(HttpHeaders.CacheControl, "no-store")
                requestHeaders.forEach { (name, value) -> append(name, value) }
            }
            if (methodName != "GET") {
                contentType(ContentType.Application.Json)
                // Hashing and sending use this exact buffer, without a JSON serializer rewriting it.
                setBody(bytes)
            }
        }
    }

    private suspend fun jsonResponse(response: HttpResponse): JsonObject {
        if (response.status.value !in 200..299) throw error(response)
        return StrictJson.objectFrom(readBounded(response.bodyAsChannel(), 65536))
    }

    private suspend fun error(response: HttpResponse): IOException {
        val body = readBounded(response.bodyAsChannel(), 32768)
        if (body.isNotEmpty() && response.contentType()?.match(ContentType.Application.Json) == true) {
            val json = StrictJson.objectFrom(body, 32768).obj("error")
            return RemoteApiException(json.string("code"), json.string("message"),
                json.boolean("retryable"), json.nullableString("requestId"))
        }
        return IOException("Remote API returned HTTP ${response.status.value}")
    }

    private fun <T> retryNetwork(action: () -> T): T {
        try { return action() }
        catch (error: IOException) { if (error is RemoteApiException) throw error; return action() }
    }

    private suspend fun readBounded(input: ByteReadChannel, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) continue
            require(output.size() + count <= maximum) { "HTTP response exceeds input limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
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
