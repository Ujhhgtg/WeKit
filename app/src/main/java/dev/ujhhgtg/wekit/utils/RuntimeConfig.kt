package dev.ujhhgtg.wekit.utils

import android.content.Context
import android.content.SharedPreferences
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.div

object RuntimeConfig {

    lateinit var mmPrefs: SharedPreferences

    val loggedInWxId: String
        get() = mmPrefs.getString("login_weixin_username", "") ?: ""

    val userDataDir: Path
        get() {
            val context = HostInfo.application
            val prefs = context.getSharedPreferences("system_config_prefs", Context.MODE_PRIVATE)
            val defaultUin = prefs.getInt("default_uin", 0)

            val seedString = "mm$defaultUin"

            val md5FolderHash = calculateMd5(seedString).lowercase(Locale.ROOT)

            val targetDir = context.dataDir.toPath() / "MicroMsg" / md5FolderHash

            return targetDir
        }

    private fun calculateMd5(input: String): String {
        val digestBytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digestBytes.joinToString("") { byte ->
            "%02X".format(byte)
        }
    }
}
