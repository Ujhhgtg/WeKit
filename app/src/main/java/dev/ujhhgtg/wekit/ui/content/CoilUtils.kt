package dev.ujhhgtg.wekit.ui.content

import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import dev.ujhhgtg.wekit.features.items.chat.panel.service.FunBoxCrypto
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

private const val TAG = "GlobalImageLoader"

private val imageHttpClient by lazy {
    OkHttpClient.Builder()
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val securityKey = response.header("Sec") ?: return@addNetworkInterceptor response
            val originalContentType = response.body.contentType()
            val encrypted = response.body.bytes()
            val decoded = try {
                FunBoxCrypto.decryptObject(encrypted, securityKey)
            } catch (error: Throwable) {
                WeLogger.w(TAG, "failed to decode secured image response", error)
                throw IOException("failed to decode secured image response", error)
            }
            response.newBuilder()
                .removeHeader("Sec")
                .removeHeader("Content-Length")
                .body(decoded.toResponseBody(decoded.imageMediaType() ?: originalContentType))
                .build()
        }
        .build()
}

val GlobalImageLoader by lazy {
    ImageLoader.Builder(HostInfo.application)
        .components {
            add(OkHttpNetworkFetcherFactory(imageHttpClient))
            add(AnimatedImageDecoder.Factory())
        }
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()
}

private fun ByteArray.imageMediaType(): MediaType? = when {
    size >= 3 && this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte() &&
            this[2] == 'F'.code.toByte() -> "image/gif".toMediaType()

    size >= 8 && copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
    ) -> "image/png".toMediaType()

    size >= 12 && decodeToString(0, 4) == "RIFF" && decodeToString(8, 12) == "WEBP" ->
        "image/webp".toMediaType()

    size >= 3 && this[0] == 0xff.toByte() && this[1] == 0xd8.toByte() &&
            this[2] == 0xff.toByte() -> "image/jpeg".toMediaType()

    else -> null
}
