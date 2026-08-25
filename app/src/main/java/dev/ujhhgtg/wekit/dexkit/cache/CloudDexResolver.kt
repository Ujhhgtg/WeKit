package dev.ujhhgtg.wekit.dexkit.cache

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request

internal sealed interface CloudDexNotice {
    data object ReportNotFound : CloudDexNotice
    data class NetworkFailure(val message: String) : CloudDexNotice
    data class InvalidReport(val message: String) : CloudDexNotice
    data class CacheWriteFailure(val message: String) : CloudDexNotice
    data object NoMatchingEntries : CloudDexNotice
    data class Partial(val importedCount: Int, val remainingCount: Int) : CloudDexNotice
}

internal data class CloudDexResolutionResult(
    val importedCount: Int,
    val remainingItems: List<IResolveDex>,
    val notice: CloudDexNotice?,
)

internal object CloudDexResolver {
    private const val TAG = "CloudDexResolver"
    private const val RELEASE_BASE_URL =
        "https://github.com/Ujhhgtg/WeKit/releases/download/Dex-Test"
    private const val MAX_REPORT_BYTES = 8 * 1024 * 1024

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun resolve(items: List<IResolveDex>): CloudDexResolutionResult = withContext(Dispatchers.IO) {
        val host = CloudDexHost(
            versionName = HostInfo.versionName,
            versionCode = HostInfo.versionCode,
            isGooglePlay = HostInfo.isHostGooglePlay,
        )
        val reportText = try {
            fetchReport(host)
        } catch (_: ReportNotFoundException) {
            return@withContext failure(items, CloudDexNotice.ReportNotFound)
        } catch (error: IOException) {
            WeLogger.e(TAG, "failed to download cloud Dex report", error)
            return@withContext failure(
                items,
                CloudDexNotice.NetworkFailure(error.message.orEmpty()),
            )
        }

        val selection = try {
            CloudDexReport.select(
                jsonText = reportText,
                host = host,
                items = items.map { item ->
                    val feature = item as BaseFeature
                    CurrentDexItem(
                        className = item.javaClass.name,
                        technicalId = feature.technicalId,
                        methodHash = DexCacheManager.methodHash(item),
                        delegateKeys = item.dexDelegates.mapTo(linkedSetOf()) { it.key },
                    )
                },
            )
        } catch (error: SerializationException) {
            WeLogger.e(TAG, "cloud Dex report is malformed", error)
            return@withContext failure(items, CloudDexNotice.InvalidReport(error.message.orEmpty()))
        } catch (error: IllegalArgumentException) {
            WeLogger.e(TAG, "cloud Dex report is incompatible", error)
            return@withContext failure(items, CloudDexNotice.InvalidReport(error.message.orEmpty()))
        }

        if (selection.entries.isEmpty()) {
            return@withContext failure(items, CloudDexNotice.NoMatchingEntries)
        }

        try {
            DexCacheManager.importCloudCaches(selection.entries)
        } catch (error: Exception) {
            WeLogger.e(TAG, "failed to import cloud Dex caches", error)
            return@withContext failure(items, CloudDexNotice.CacheWriteFailure(error.message.orEmpty()))
        }

        val remainingItems = DexCacheManager.getOutdatedItems(items)
        val importedCount = items.size - remainingItems.size
        CloudDexResolutionResult(
            importedCount = importedCount,
            remainingItems = remainingItems,
            notice = if (remainingItems.isEmpty()) {
                null
            } else {
                CloudDexNotice.Partial(importedCount, remainingItems.size)
            },
        )
    }

    private fun fetchReport(host: CloudDexHost): String {
        val request = Request.Builder()
            .url("$RELEASE_BASE_URL/${CloudDexReport.assetName(host)}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) throw ReportNotFoundException()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val contentLength = body.contentLength()
            if (contentLength > MAX_REPORT_BYTES) {
                throw IOException("cloud Dex report is larger than $MAX_REPORT_BYTES bytes")
            }
            return body.byteStream().use(::readBoundedUtf8)
        }
    }

    private fun readBoundedUtf8(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_REPORT_BYTES) {
                throw IOException("cloud Dex report is larger than $MAX_REPORT_BYTES bytes")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun failure(
        items: List<IResolveDex>,
        notice: CloudDexNotice,
    ) = CloudDexResolutionResult(
        importedCount = 0,
        remainingItems = items,
        notice = notice,
    )

    private class ReportNotFoundException : IOException()
}
