package dev.ujhhgtg.wekit.bootstrap.identity

import android.app.Application
import android.content.Context

data class CurrentIdentity(val wxId: String?, val alias: String?, val readState: String, val ready: Boolean)

/**
 * Reads the live WeChat identity during bootstrap startup without DEX scanning.
 *
 * The account wxId and WeChat alias are persisted by WeChat's stable last-login preference layer in
 * the host's default "<package>_preferences" SharedPreferences file:
 * - "login_weixin_username" = current account wxId
 * - "last_login_alias" = current account's WeChat alias (when set)
 *
 * "login_user_name" is login history/identifier data and is not the current account wxId.
 *
 * These keys are stable across the supported 8.0.65-8.0.79 range (verified in the decompiled host
 * sources: the account preference wrapper reads/writes exactly these keys and commits them to the
 * SharedPreferences file before caching them in MMKV). The bootstrap process runs inside WeChat, so
 * the host application context can read the same file directly.
 */
class CurrentIdentityReader(private val application: Application) {
    fun read(): CurrentIdentity {
        val preferences = application.getSharedPreferences(
            application.packageName + "_preferences",
            Context.MODE_PRIVATE,
        )
        val wxId = preferences.getString("login_weixin_username", "")?.takeUnless(String::isEmpty)
            ?: return CurrentIdentity(null, null, "NOT_READY", false)
        val alias = preferences.getString("last_login_alias", "")?.takeUnless(String::isEmpty)
        return CurrentIdentity(
            wxId = wxId,
            alias = alias,
            readState = if (alias == null) "ALIAS_UNAVAILABLE" else "CURRENT",
            ready = true,
        )
    }
}
