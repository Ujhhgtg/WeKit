package dev.ujhhgtg.wekit.utils.fs

import android.os.Environment
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.utils.HostInfo
import java.nio.file.Path
import kotlin.io.path.div

object KnownPaths {

    val internalStorage: Path by lazy {
        Environment.getExternalStorageDirectory().asPath
    }

    val moduleData by lazy {
        (internalStorage / "Android" / "data" /
                runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT) /
                BuildConfig.TAG).createDirsSafe()
    }

    val codeCacheDir: Path by lazy {
        HostInfo.application.codeCacheDir.asPath
    }

    val moduleCache by lazy {
        (internalStorage / "Android" / "data" /
                runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT)
                / "cache" / BuildConfig.TAG).createDirsSafe()
    }

    val moduleAssets by lazy {
        (moduleData / "assets").createDirsSafe()
    }

    val downloads by lazy {
        (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath() / "WeKit")
            .createDirsSafe()
    }
}
