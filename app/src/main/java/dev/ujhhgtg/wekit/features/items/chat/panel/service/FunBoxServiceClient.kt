package dev.ujhhgtg.wekit.features.items.chat.panel.service

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.localizedChatString
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.Calendar
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object FunBoxServiceClient {
    private const val TAG = "FunBoxService"
    private const val OBJECT_SECURITY_HEADER = "Sec"
    private const val RESOLVER_NAME = "resolve.fpfast.top"
    private const val API_CACHE_KEY = "funbox_panel_api_host"
    private const val OBJECT_CACHE_KEY = "funbox_panel_object_host"
    private val random = SecureRandom()
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .callTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofSeconds(60))
        .build()

    suspend fun <T> call(
        operation: Int,
        requestPayload: ByteArray,
        decode: (FunBoxBinaryReader) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        WeLogger.d(
            TAG,
            "operation=$operation start requestBytes=${requestPayload.size} thread=${Thread.currentThread().name}",
        )
        cancellableResult {
            val host = apiHost()
            try {
                callOnHost(host, operation, requestPayload, decode)
            } catch (error: Throwable) {
                if (!isRetryableHostFailure(error)) throw error
                WeLogger.w(TAG, "operation=$operation failed on host=$host; resolving replacement", error)
                invalidateApiHost(host)
                val replacement = apiHost(excludedHost = host)
                WeLogger.i(TAG, "operation=$operation retry host=$replacement")
                callOnHost(replacement, operation, requestPayload, decode)
            }
        }.onSuccess {
            WeLogger.i(TAG, "operation=$operation completed")
        }.onFailure { error ->
            WeLogger.e(TAG, "operation=$operation failed", error)
        }
    }

    private suspend fun <T> callOnHost(
        host: String,
        operation: Int,
        requestPayload: ByteArray,
        decode: (FunBoxBinaryReader) -> T,
    ): T {
        val sessionKey = randomText(32)
        val encryptedPayload = FunBoxCrypto.teaEncrypt(sessionKey, requestPayload)
        val envelope = FunBoxBinaryWriter().apply {
            int(operation)
            bytes(encryptedPayload)
            bytes(FunBoxCrypto.sm2Encrypt(sessionKey.toByteArray()))
            long(System.currentTimeMillis())
            string(randomText(8))
            long(requestPayload.size.toLong())
        }.build().gzip()
        val request = Request.Builder()
            .url(host.trimEnd('/') + "/funbox/api/req2")
            .header("ph", requestProof())
            .post(envelope.toRequestBody())
            .build()
        client.newCall(request).awaitResponse().use { response ->
            WeLogger.d(TAG, "operation=$operation HTTP ${response.code} host=$host")
            if (!response.isSuccessful) throw HttpStatusException(response.code, response.message)
            val bytes = response.body.bytes()
            WeLogger.d(TAG, "operation=$operation responseBytes=${bytes.size}")
            require(bytes.isNotEmpty()) { localizedChatString(R.string.chat_funbox_server_empty) }
            val responseEnvelope = FunBoxBinaryReader(bytes)
            val encrypted = responseEnvelope.bytes()
            val status = responseEnvelope.int()
            WeLogger.d(TAG, "operation=$operation envelopeStatus=$status encryptedBytes=${encrypted.size}")
            check(status == 0) { localizedChatString(R.string.chat_funbox_server_error_code, status) }
            val decodedPayload = FunBoxCrypto.teaDecrypt(sessionKey, encrypted)
            return decode(FunBoxBinaryReader(decodedPayload))
        }
    }

    suspend fun objectUrl(type: String, objectId: String): String = withContext(Dispatchers.IO) {
        val host = objectHost()
        WeLogger.d(TAG, "resolved object URL type=$type host=$host")
        host.trimEnd('/') + "/vfile/$type/$objectId"
    }

    suspend fun downloadObject(type: String, objectId: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            WeLogger.d(TAG, "object download start type=$type")
            cancellableResult {
                val host = objectHost()
                try {
                    downloadBlocking(host.trimEnd('/') + "/vfile/$type/$objectId")
                } catch (error: Throwable) {
                    if (!isRetryableHostFailure(error)) throw error
                    WeLogger.w(TAG, "object download failed type=$type host=$host; resolving replacement", error)
                    invalidateObjectHost(host)
                    val replacement = objectHost(excludedHost = host)
                    WeLogger.i(TAG, "object download retry type=$type host=$replacement")
                    downloadBlocking(replacement.trimEnd('/') + "/vfile/$type/$objectId")
                }
            }.onSuccess { bytes ->
                WeLogger.i(TAG, "object download completed type=$type bytes=${bytes.size}")
            }.onFailure { error ->
                WeLogger.e(TAG, "object download failed type=$type", error)
            }
        }

    suspend fun download(url: String, headers: Map<String, String> = emptyMap()): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val endpoint = endpointLabel(url)
            WeLogger.d(TAG, "download start endpoint=$endpoint headers=${headers.size}")
            cancellableResult {
                downloadBlocking(url, headers)
            }.onSuccess { bytes ->
                WeLogger.i(TAG, "download completed endpoint=$endpoint bytes=${bytes.size}")
            }.onFailure { error ->
                WeLogger.e(TAG, "download failed endpoint=$endpoint", error)
            }
        }

    private suspend fun downloadBlocking(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val builder = Request.Builder().url(url).get()
        headers.forEach(builder::header)
        return client.newCall(builder.build()).awaitResponse().use { response ->
            WeLogger.d(TAG, "download HTTP ${response.code} endpoint=${endpointLabel(url)}")
            if (!response.isSuccessful) throw HttpStatusException(response.code, response.message)
            val bytes = response.body.bytes()
            val securityKey = response.header(OBJECT_SECURITY_HEADER)
            if (securityKey.isNullOrEmpty()) {
                bytes
            } else {
                FunBoxCrypto.decryptObject(bytes, securityKey).also { decoded ->
                    WeLogger.d(
                        TAG,
                        "decoded secured object endpoint=${endpointLabel(url)} " +
                                "encryptedBytes=${bytes.size} decodedBytes=${decoded.size}",
                    )
                }
            }
        }
    }

    @Synchronized
    private fun cachedApiHost() = WePrefs.getString(API_CACHE_KEY).orEmpty()

    @Synchronized
    private fun cachedObjectHost() = WePrefs.getString(OBJECT_CACHE_KEY).orEmpty()

    private fun apiHost(excludedHost: String? = null): String {
        cachedApiHost().takeIf { it.isNotBlank() && it != excludedHost }?.let {
            WeLogger.d(TAG, "using cached API host=$it")
            return it
        }
        WeLogger.i(TAG, "resolving API host excluded=${excludedHost != null}")
        val resolved = resolveCandidates()
        val host = resolved.first.firstOrNull { candidate -> candidate != excludedHost && probeApi(candidate) }
            ?: error(localizedChatString(R.string.chat_funbox_api_unreachable))
        WePrefs.putString(API_CACHE_KEY, host)
        WeLogger.i(TAG, "selected API host=$host")
        return host
    }

    private fun objectHost(excludedHost: String? = null): String {
        cachedObjectHost().takeIf { it.isNotBlank() && it != excludedHost }?.let {
            WeLogger.d(TAG, "using cached object host=$it")
            return it
        }
        WeLogger.i(TAG, "resolving object host excluded=${excludedHost != null}")
        val resolved = resolveCandidates()
        val host = resolved.second.firstOrNull { candidate -> candidate != excludedHost && probeObject(candidate) }
            ?: error(localizedChatString(R.string.chat_funbox_object_service_unreachable))
        WePrefs.putString(OBJECT_CACHE_KEY, host)
        WeLogger.i(TAG, "selected object host=$host")
        return host
    }

    private fun resolveCandidates(): Pair<List<String>, List<String>> {
        WeLogger.d(TAG, "resolver request name=$RESOLVER_NAME")
        val resolverUrl = "https://223.5.5.5/resolve?name=$RESOLVER_NAME&type=TXT"
        val body = client.newCall(Request.Builder().url(resolverUrl).get().build()).execute().use { response ->
            WeLogger.d(TAG, "resolver HTTP ${response.code}")
            check(response.isSuccessful) { localizedChatString(R.string.chat_funbox_resolver_failed, response.code) }
            response.body.string()
        }
        val answer = DefaultJson.parseToJsonElement(body).jsonObject["Answer"]?.jsonArray
            ?.asSequence()
            ?.mapNotNull { it.jsonObject["data"]?.jsonPrimitive?.content }
            ?.map(::joinTxtFragments)
            ?.filter { it.isNotBlank() }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
            ?: error(localizedChatString(R.string.chat_funbox_resolver_empty))
        val decoded = String(Base64.getDecoder().decode(answer))
        val json = DefaultJson.parseToJsonElement(decoded).jsonObject
        val api = json["vapi"]!!.jsonArray.map { it.jsonPrimitive.content }
        val objects = json["vraw"]!!.jsonArray.map { it.jsonPrimitive.content }
        WeLogger.i(TAG, "resolver returned apiCandidates=${api.size} objectCandidates=${objects.size}")
        return api to objects
    }

    /** Mirrors dnsjava TXT#getStrings(): concatenate the character strings in one record. */
    private fun joinTxtFragments(value: String): String {
        val fragments = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
            .findAll(value)
            .map { match ->
                match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
            .toList()
        return if (fragments.isNotEmpty()) fragments.joinToString("") else value.trim()
    }

    private fun probeApi(host: String): Boolean {
        val result = runCatching {
            val requestPayload = FunBoxBinaryWriter().apply { long(0L) }.build()
            callBlockingProbe(host, 100, requestPayload).isNotEmpty()
        }
        result.exceptionOrNull()?.let { WeLogger.w(TAG, "API probe failed host=$host", it) }
        return result.getOrDefault(false).also { WeLogger.d(TAG, "API probe host=$host accepted=$it") }
    }

    private fun probeObject(host: String): Boolean {
        val result = runCatching {
            val testPath = "/vfile/vfun/vtest"
            val url = host.trimEnd('/') + testPath
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    WeLogger.d(TAG, "object probe host=$host HTTP ${response.code}")
                    return@use false
                }
                val body = response.body.string()
                val accepted = body == testPath || body == "success"
                WeLogger.d(TAG, "object probe host=$host bodyClass=${if (accepted) "accepted" else "unexpected"}")
                accepted
            }
        }
        result.exceptionOrNull()?.let { WeLogger.w(TAG, "object probe failed host=$host", it) }
        return result.getOrDefault(false)
    }

    private fun callBlockingProbe(host: String, operation: Int, requestPayload: ByteArray): ByteArray {
        val sessionKey = randomText(32)
        val envelope = FunBoxBinaryWriter().apply {
            int(operation)
            bytes(FunBoxCrypto.teaEncrypt(sessionKey, requestPayload))
            bytes(FunBoxCrypto.sm2Encrypt(sessionKey.toByteArray()))
            long(System.currentTimeMillis())
            string(randomText(8))
            long(requestPayload.size.toLong())
        }.build().gzip()
        val request = Request.Builder()
            .url(host.trimEnd('/') + "/funbox/api/req2")
            .header("ph", requestProof())
            .post(envelope.toRequestBody())
            .build()
        return client.newCall(request).execute().use { response ->
            WeLogger.d(TAG, "probe operation=$operation host=$host HTTP ${response.code}")
            if (!response.isSuccessful) return@use ByteArray(0)
            val responseBytes = response.body.bytes()
            if (responseBytes.isEmpty()) return@use ByteArray(0)
            val responseEnvelope = FunBoxBinaryReader(responseBytes)
            val encrypted = responseEnvelope.bytes()
            val status = responseEnvelope.int()
            WeLogger.d(TAG, "probe operation=$operation status=$status encryptedBytes=${encrypted.size}")
            if (encrypted.isEmpty() || status != 0) return@use ByteArray(0)
            FunBoxCrypto.teaDecrypt(sessionKey, encrypted)
        }
    }

    @Synchronized
    private fun invalidateApiHost(host: String) {
        if (cachedApiHost() == host) {
            WePrefs.putString(API_CACHE_KEY, "")
            WeLogger.i(TAG, "invalidated API host=$host")
        }
    }

    @Synchronized
    private fun invalidateObjectHost(host: String) {
        if (cachedObjectHost() == host) {
            WePrefs.putString(OBJECT_CACHE_KEY, "")
            WeLogger.i(TAG, "invalidated object host=$host")
        }
    }

    private fun requestProof(): String {
        val calendar = Calendar.getInstance()
        return (System.currentTimeMillis() / 1000L * calendar.get(Calendar.HOUR_OF_DAY) +
                calendar.get(Calendar.DAY_OF_YEAR)).toString()
    }

    private fun isRetryableHostFailure(error: Throwable): Boolean = when (error) {
        is HttpStatusException -> error.code == 404 || error.code == 408 || error.code >= 500
        is IOException -> true
        else -> false
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private suspend inline fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private class HttpStatusException(val code: Int, message: String) :
        IOException("HTTP $code: $message")

    private fun endpointLabel(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return "invalid-url"
        val path = when (parsed.pathSegments.firstOrNull()) {
            "vfile" -> parsed.pathSegments.take(2)
            else -> parsed.pathSegments.take(3)
        }.joinToString("/", prefix = "/")
        return "${parsed.scheme}://${parsed.host}$path"
    }

    private fun randomText(length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun ByteArray.gzip(): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(this) }
        output.toByteArray()
    }
}
