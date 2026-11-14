package dev.ujhhgtg.wekit.utils

import android.content.Context
import android.content.SharedPreferences
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.strings.md5Hex
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.isDirectory

object RuntimeConfig {
    lateinit var mmPrefs: SharedPreferences

    val loggedInWxId: String
        get() = mmPrefs.getString("login_weixin_username", "") ?: ""

    /**
     * The current account's WeChat data directory (`<dataDir>/MicroMsg/<md5("mm"+uin)>/`).
     *
     * Host versions differ in which uin width is hashed into the directory name: up to 8.0.78 the
     * truncated 32-bit uin is used, 8.0.79 uses the full 64-bit uin. Both candidate names are
     * probed on disk so no host-version check is needed.
     */
    val userDataDir: Path
        get() {
            val context = HostInfo.application
            val prefs = context.getSharedPreferences("system_config_prefs", Context.MODE_PRIVATE)
            val uin =
                if (prefs.contains("default_uin64")) {
                    prefs.getLong("default_uin64", 0L)
                } else {
                    prefs.getInt("default_uin", 0).toLong()
                }

            val microMsg = context.filesDir.asPath.parent / "MicroMsg"
            val longUinDir = microMsg / md5Hex("mm$uin")
            val intUinDir = microMsg / md5Hex("mm${uin.toInt()}")
            return when {
                longUinDir.isDirectory() -> longUinDir
                intUinDir.isDirectory() -> intUinDir
                else -> longUinDir
            }
        }
}
