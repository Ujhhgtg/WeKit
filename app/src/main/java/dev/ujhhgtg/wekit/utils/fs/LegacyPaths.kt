package dev.ujhhgtg.wekit.utils.fs

import android.os.Environment
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.utils.HostInfo
import java.nio.file.Path
import kotlin.io.path.div

/**
 * Locations used by releases before module data moved into the host's private files directory.
 *
 * New runtime code must never use this object as a fallback.  It exists only for one-shot
 * migration scanners, which can leave the source untouched when migration or validation fails.
 */
object LegacyPaths {
    /** A restored backup owns its inputs; do not supplement it with the previous installation. */
    val localSourcesAllowed: Boolean
        get() = !KnownPaths.moduleRoot.resolve(".restored-backup").toFile().exists()

    val externalModuleRoot: Path by lazy {
        (Environment.getExternalStorageDirectory().toPath() / "Android" / "data" /
                runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT) /
                BuildConfig.TAG)
    }

    val privateWeAgentDatabase: Path by lazy {
        HostInfo.application.filesDir.asPath / "wekit-agent" / "weagent.db"
    }

    /** Root used by extension packs before they shared the WeKit private root. */
    val privateExtensionRoot: Path by lazy {
        HostInfo.application.filesDir.asPath / "wekit-extensions"
    }

    /** Root used by Agent's managed runtime directories before the root consolidation. */
    val privateAgentRoot: Path by lazy {
        HostInfo.application.filesDir.asPath / "wekit-agent"
    }
}
