package dev.ujhhgtg.wekit.features.items.chat.panel.sticker

import dev.ujhhgtg.wekit.utils.fs.moveReplacing
import kotlin.io.path.fileSize
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.localizedChatString
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile

internal object TelegramStickerApiClient {
    private const val TAG = "TelegramStickerApi"
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofMinutes(2))
        .callTimeout(Duration.ofMinutes(3))
        .build()

    suspend fun getStickerSet(token: String, name: String): TelegramStickerSet =
        call(token, "getStickerSet", mapOf("name" to name))

    suspend fun getFile(token: String, fileId: String): TelegramFile =
        call(token, "getFile", mapOf("file_id" to fileId))

    suspend fun downloadFile(
        token: String,
        filePath: String,
        destination: Path,
        expectedSize: Long? = null,
    ) = withContext(Dispatchers.IO) {
        require(isSafeFilePath(filePath)) { localizedChatString(R.string.chat_telegram_invalid_file_path) }
        val partial = destination.resolveSibling("${destination.fileName}.part")
        var existing = partial.takeIf { it.isRegularFile() }?.fileSize() ?: 0L
        if (expectedSize != null && existing > expectedSize) {
            partial.deleteIfExists()
            existing = 0L
        } else if (expectedSize != null && existing == expectedSize && existing > 0L) {
            moveCompletedDownload(partial, destination)
            return@withContext
        }
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val builder = Request.Builder()
                .url("https://api.telegram.org/file/bot$token/$filePath")
                .get()
            if (existing > 0L) builder.header("Range", "bytes=$existing-")
            val response = try {
                client.newCall(builder.build()).awaitResponse()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw IOException(localizedChatString(R.string.chat_telegram_file_service_unreachable), error.withoutSensitiveMessage())
            }
            response.use {
                if (it.code == 416 && attempt == 0) {
                    partial.deleteIfExists()
                    existing = 0L
                    return@repeat
                }
                if (!it.isSuccessful) throw TelegramApiException(localizedChatString(R.string.chat_telegram_file_download_failed, it.code))
                val append = existing > 0L && it.code == 206
                val options = if (append) {
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                } else {
                    existing = 0L
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                }
                partial.outputStream(*options).use { output ->
                    it.body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            val downloadedSize = partial.fileSize()
            require(downloadedSize > 0L) { localizedChatString(R.string.chat_telegram_file_empty) }
            require(expectedSize == null || downloadedSize == expectedSize) {
                localizedChatString(R.string.chat_telegram_file_incomplete)
            }
            moveCompletedDownload(partial, destination)
            WeLogger.i(TAG, "download completed bytes=${destination.fileSize()}")
            return@withContext
        }
        error(localizedChatString(R.string.chat_telegram_file_resume_failed))
    }

    private suspend inline fun <reified T> call(
        token: String,
        method: String,
        parameters: Map<String, String>,
    ): T = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply {
            parameters.forEach(::add)
        }.build()
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/$method")
            .post(body)
            .build()
        val response = try {
            client.newCall(request).awaitResponse()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            WeLogger.w(TAG, "$method failed before response: ${error.javaClass.simpleName}")
            throw IOException(localizedChatString(R.string.chat_telegram_api_unreachable), error.withoutSensitiveMessage())
        }
        response.use {
            val payload = it.body.string()
            val decoded = runCatching {
                DefaultJson.decodeFromString<TelegramResponse<T>>(payload)
            }.getOrElse { error ->
                WeLogger.w(TAG, "$method returned invalid JSON: ${error.javaClass.simpleName}")
                throw TelegramApiException(localizedChatString(R.string.chat_telegram_api_invalid_data))
            }
            if (!decoded.ok) {
                throw TelegramApiException(
                    decoded.description?.take(200) ?: localizedChatString(R.string.chat_telegram_api_request_failed),
                )
            }
            WeLogger.i(TAG, "$method completed")
            decoded.result ?: throw TelegramApiException(localizedChatString(R.string.chat_telegram_api_missing_result))
        }
    }

    private fun isSafeFilePath(value: String): Boolean =
        value.isNotBlank() && !value.startsWith('/') && value.split('/').none { it == ".." }

    private fun moveCompletedDownload(partial: Path, destination: Path) {
        partial.moveReplacing(destination)
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

    private fun Throwable.withoutSensitiveMessage(): Throwable =
        IOException(javaClass.simpleName)
}

internal class TelegramApiException(message: String) : IOException(message)

@Serializable
private data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
)

@Serializable
internal data class TelegramStickerSet(
    val name: String,
    val title: String,
    @SerialName("sticker_type") val stickerType: String = "regular",
    val stickers: List<TelegramSticker>,
)

@Serializable
internal data class TelegramSticker(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String,
    @SerialName("is_animated") val isAnimated: Boolean = false,
    @SerialName("is_video") val isVideo: Boolean = false,
    val emoji: String? = null,
)

@Serializable
internal data class TelegramFile(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("file_path") val filePath: String,
)
