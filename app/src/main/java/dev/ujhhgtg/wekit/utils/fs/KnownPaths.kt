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
     * Keep the directory names below this root stable: script loaders and extension runtimes use
     * their relative layout as part of their contract.
     */
    val moduleRoot: Path by lazy {
        (HostInfo.application.filesDir.asPath / "wekit").createDirsSafe()
    }

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

    val downloads by lazy {
        (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath() /
                BuildConfig.TAG)
            .createDirsSafe()
    }
}
