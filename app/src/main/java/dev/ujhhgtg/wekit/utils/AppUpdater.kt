package dev.ujhhgtg.wekit.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.loader.entry.zygisk.ZygiskLoaderService
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import dev.ujhhgtg.wekit.utils.android.getSystemService
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

// APKs are published per entry-point flavor: app-<flavor>-<abi>-release.apk.
// Stay on the same flavor the installed build was compiled for.
private val FLAVOR = BuildConfig.FLAVOR_SLUG

private val ABI_APK_MAP = mapOf(
    "arm64-v8a" to "$BASE_URL/app-$FLAVOR-arm64-v8a-release.apk",
    "armeabi-v7a" to "$BASE_URL/app-$FLAVOR-armeabi-v7a-release.apk",
)
private const val UPDATE_JSON_URL = "$BASE_URL/update.json"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val ZIP_MIME_TYPE = "application/zip"

/** Returns the best APK URL for this device. */
private fun apkUrlForDevice(): String {
    val supportedAbis = Build.SUPPORTED_ABIS  // ordered by preference
    for (abi in supportedAbis) {
        ABI_APK_MAP[abi]?.let { return it }
    }
    error("Unsupported Android ABI: ${supportedAbis.joinToString()}")
}

/** Matches the release name emitted by the Zygisk packager. */
private fun zygiskModuleFileName(info: UpdateInfo): String =
    "WeKit-${info.versionCode}-${info.versionName}-release.zip"

// ─── AppUpdater ───────────────────────────────────────────────────────────────

/**
 * Self-contained in-app updater for WeKit.
 *
 * Usage:
 * ```
 * when (val result = AppUpdater.checkForUpdate()) {
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
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
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
        }.getOrElse {
            UpdateResult.Error(it)
        }
    }

    /**
     * Downloads the update matching the active loader. Zygisk mode downloads
     * the module ZIP and opens it with a compatible root manager; other modes
     * download the APK and open the system package installer.
     *
     * Requires the `REQUEST_INSTALL_PACKAGES` permission and a FileProvider
     * authority of `<packageName>.provider` in your manifest.
     *
     * Must be called from a coroutine; completion is awaited via a
     * [BroadcastReceiver] on [Dispatchers.Main].
     */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val isZygisk = StartupInfo.loaderService is ZygiskLoaderService
        val fileName: String
        val downloadUrl: String
        val mimeType: String
        if (isZygisk) {
            fileName = zygiskModuleFileName(info)
            downloadUrl = "$BASE_URL/$fileName"
            mimeType = ZIP_MIME_TYPE
        } else {
            fileName = "wekit-${info.versionName}.apk"
            downloadUrl = apkUrlForDevice()
            mimeType = APK_MIME_TYPE
        }

        val downloadId = enqueueDownload(context, downloadUrl, fileName, mimeType)
        val downloadedFile = waitForDownload(context, downloadId)
        val contentUri = getDownloadedFileUri(context, downloadedFile)

        if (isZygisk) {
            launchKsuWithModule(context, contentUri)
        } else {
            installApk(context, contentUri)
        }
    }

    fun launchKsuWithModule(context: Context, zipUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(zipUri, ZIP_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
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

    private fun enqueueDownload(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String,
    ): Long {
        val localizedContext = LocalizedContextFactory.create(
            context,
            WeKitLocaleController.resolvedLocale,
            LocaleResourceMode.InjectedHost,
        )
        val request = DownloadManager.Request(url.toUri()).apply {
            setTitle(localizedContext.getString(R.string.noncompose_update_download_title))
            setDescription(localizedContext.getString(R.string.noncompose_update_downloading))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType(mimeType)
        }
        val dm = context.getSystemService<DownloadManager>()
        return dm.enqueue(request)
    }

    /** Suspends until [DownloadManager] broadcasts completion for [downloadId]. */
    private suspend fun waitForDownload(context: Context, downloadId: Long): File =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                        if (id != downloadId) return

                        context.unregisterReceiver(this)

                        val dm = context.getSystemService<DownloadManager>()
                        val query = DownloadManager.Query().setFilterById(downloadId)

                        dm.query(query)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                if (cursor.getInt(statusCol) == DownloadManager.STATUS_SUCCESSFUL) {

                                    // 核心：动态获取 DownloadManager 实际保存的本地真实路径
                                    val localUriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                    val localUriStr = cursor.getString(localUriCol)

                                    runCatching {
                                        val realFile = File(android.net.Uri.parse(localUriStr).path!!)
                                        cont.resume(realFile)
                                    }.getOrElse {
                                        cont.resumeWithException(RuntimeException("Failed to resolve download path", it))
                                    }
                                } else {
                                    val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                    cont.resumeWithException(RuntimeException("Download failed: reason=${cursor.getInt(reasonCol)}"))
                                }
                            } else {
                                cont.resumeWithException(RuntimeException("Download query returned no results"))
                            }
                        } ?: cont.resumeWithException(RuntimeException("Download query returned null cursor"))
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
                    val dm = context.getSystemService<DownloadManager>()
                    dm.remove(downloadId)
                }
            }
        }

    private fun getDownloadedFileUri(context: Context, file: File): Uri {
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

        return FileProvider.getUriForFile(
            context,
            "${PackageNames.WECHAT}.external.recovery.logprovider",
            file,
        )
    }

    private fun installApk(context: Context, apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
