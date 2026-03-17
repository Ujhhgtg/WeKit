package moe.ouom.wekit.utils.updates

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.wekit.constants.PackageNames
import moe.ouom.wekit.utils.ModulePaths
import okhttp3.Request
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.outputStream

object UpdateDownloader {

    suspend fun downloadAndInstall(context: Context, url: String, onProgress: (Int) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val cacheDir = ModulePaths.cache / "updates"
            cacheDir.createDirectories()
            val zipFile = cacheDir / "update.zip"
            val apkFile = cacheDir / "update.apk"

            val request = Request.Builder().url(url).header("User-Agent", "WeKit").build()
            HttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
                val body = response.body
                val total = body.contentLength()

                body.byteStream().use { input ->
                    zipFile.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) onProgress((downloaded * 100 / total).toInt())
                        }
                    }
                }
            }

            ZipFile(zipFile.toFile()).use { zip ->
                val entry = zip.entries().asSequence().first { it.name.endsWith(".apk") }
                zip.getInputStream(entry).use { input ->
                    apkFile.outputStream().use { input.copyTo(it) }
                }
            }

            withContext(Dispatchers.Main) { installApk(context, apkFile) }
        }

    private fun installApk(context: Context, apk: Path) {
        val uri = FileProvider.getUriForFile(context, "${PackageNames.WECHAT}.external.fileprovider", apk.toFile())
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
