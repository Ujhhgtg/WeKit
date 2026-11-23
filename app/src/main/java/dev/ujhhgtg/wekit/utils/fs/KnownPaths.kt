package dev.ujhhgtg.wekit.utils.fs

import android.os.Environment
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.utils.HostInfo
import java.nio.file.Path
import kotlin.io.path.div

object KnownPaths {

    /**
     * Root for all WeKit-owned files that need a real filesystem path.
     *
     * Keep the directory names below this root stable: script loaders and extension runtimes
     * use their relative layout as part of their contract.
     */
    val moduleRoot: Path by lazy {
        (HostInfo.application.filesDir.asPath / "wekit").createDirsSafe()
    }

    /**
     * A Dex cache is tied to both the WeChat build and its distribution channel.  Domestic and
     * Google Play builds can share a version name/code while exposing different DEX layouts, so
     * they must never address the same cache partition.
     */
    fun hostVersionKey(): String {
        val channel = if (HostInfo.isHostGooglePlay) "play" else "domestic"
        return sanitizeVersionKey("${HostInfo.versionName}-${HostInfo.versionCode}-$channel")
    }

    private fun sanitizeVersionKey(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    val internalStorage: Path by lazy {
        Environment.getExternalStorageDirectory().asPath
    }

    val codeCacheDir: Path by lazy {
        HostInfo.application.codeCacheDir.asPath
    }

    val moduleCache by lazy {
        (moduleRoot / "cache").createDirsSafe()
    }

    val moduleAssets by lazy {
        (moduleRoot / "assets").createDirsSafe()
    }

    val userAssets by lazy {
        (moduleAssets / "user").createDirsSafe()
    }

    val downloads by lazy {
        (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath() / BuildConfig.TAG)
            .createDirsSafe()
    }
}
