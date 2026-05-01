package dev.ujhhgtg.wekit.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.constants.PackageNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
)

sealed interface UpdateResult {
    /** Remote versionCode ≤ installed versionCode. */
    data object UpToDate : UpdateResult

    /** A newer version is available. */
    data class UpdateAvailable(val info: UpdateInfo) : UpdateResult

    /** Something went wrong while checking or downloading. */
    data class Error(val cause: Throwable) : UpdateResult
}

// ─── ABI → APK mapping ───────────────────────────────────────────────────────

private const val BASE_URL =
    "https://github.com/Ujhhgtg/WeKit/releases/download/CI"

private val ABI_APK_MAP = mapOf(
    "arm64-v8a"   to "$BASE_URL/app-arm64-v8a-release.apk",
    "armeabi-v7a" to "$BASE_URL/app-armeabi-v7a-release.apk",
    "x86"         to "$BASE_URL/app-x86-release.apk",
    "x86_64"      to "$BASE_URL/app-x86_64-release.apk",
)
private const val UNIVERSAL_APK = "$BASE_URL/app-universal-release.apk"
private const val UPDATE_JSON_URL = "$BASE_URL/update.json"

/** Returns the best APK URL for this device. */
private fun apkUrlForDevice(): String {
    val supportedAbis = Build.SUPPORTED_ABIS  // ordered by preference
    for (abi in supportedAbis) {
        ABI_APK_MAP[abi]?.let { return it }
    }
    return UNIVERSAL_APK
}

// ─── AppUpdater ───────────────────────────────────────────────────────────────

/**
 * Self-contained in-app updater for WeKit.
 *
 * Usage:
 * ```
 * when (val result = AppUpdater.checkForUpdate(context)) {
 *     is UpdateResult.UpdateAvailable -> AppUpdater.downloadAndInstall(context, result.info)
 *     is UpdateResult.UpToDate        -> { /* nothing to do */ }
 *     is UpdateResult.Error           -> { /* show error */ }
 * }
 * ```
 */
object AppUpdater {

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches [UPDATE_JSON_URL] and compares [UpdateInfo.versionCode] with
     * the currently installed version.
     *
     * Must be called from a coroutine; network I/O runs on [Dispatchers.IO].
     */
    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val remoteInfo = fetchUpdateInfo()
            val installedCode = BuildConfig.VERSION_CODE
            if (remoteInfo.versionCode > installedCode) {
                UpdateResult.UpdateAvailable(remoteInfo)
            } else {
                UpdateResult.UpToDate
            }
        }.getOrElse { UpdateResult.Error(it) }
    }

    /**
     * Enqueues an APK download via [DownloadManager] and, on completion,
     * triggers the system installer.
     *
     * Requires the `REQUEST_INSTALL_PACKAGES` permission and a FileProvider
     * authority of `<packageName>.provider` in your manifest.
     *
     * Must be called from a coroutine; completion is awaited via a
     * [BroadcastReceiver] on [Dispatchers.Main].
     */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val apkUrl = apkUrlForDevice()
        val fileName = "wekit-${info.versionName}.apk"

        val downloadId = enqueueDownload(context, apkUrl, fileName)
        waitForDownload(context, downloadId)

        val apkFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName,
        )
        install(context, apkFile)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun fetchUpdateInfo(): UpdateInfo {
        val request = Request.Builder().url(UPDATE_JSON_URL).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} fetching update info")
            }
            val body = response.body.string()
            return json.decodeFromString(body)
        }
    }

    private fun enqueueDownload(context: Context, url: String, fileName: String): Long {
        val request = DownloadManager.Request(url.toUri()).apply {
            setTitle("WeKit 更新")
            setDescription("正在下载更新...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType("application/vnd.android.package-archive")
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    /** Suspends until [DownloadManager] broadcasts completion for [downloadId]. */
    private suspend fun waitForDownload(context: Context, downloadId: Long) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                        if (id != downloadId) return

                        context.unregisterReceiver(this)

                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = dm.query(query)

                        if (cursor != null && cursor.moveToFirst()) {
                            val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            when (cursor.getInt(statusCol)) {
                                DownloadManager.STATUS_SUCCESSFUL -> cont.resume(Unit)
                                else -> {
                                    val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                    cont.resumeWithException(
                                        RuntimeException("Download failed: reason=${cursor.getInt(reasonCol)}")
                                    )
                                }
                            }
                            cursor.close()
                        } else {
                            cont.resumeWithException(RuntimeException("Download query returned no results"))
                        }
                    }
                }

                val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }

                cont.invokeOnCancellation {
                    runCatching { context.unregisterReceiver(receiver) }
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.remove(downloadId)
                }
            }
        }

    private fun install(context: Context, apk: File) {
        /*
        <provider
            android:name="androidx.core.content.FileProvider"
            android:exported="false"
            android:process=":recovery"
            android:authorities="com.tencent.mm.external.recovery.logprovider"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/di"/>
        </provider>
         */

        val uri =
            FileProvider.getUriForFile(
                context,
                "${PackageNames.WECHAT}.external.recovery.logprovider",
                apk,
            )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
