package moe.ouom.wekit.hooks.items.scripting_js

import android.content.ContentValues
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.hooks.api.core.WeDatabaseListenerApi
import moe.ouom.wekit.hooks.api.net.intf.IWePacketInterceptor
import moe.ouom.wekit.hooks.api.net.WeProtoData
import moe.ouom.wekit.utils.PathUtils
import moe.ouom.wekit.utils.logging.WeLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

@HookItem(path = "脚本/脚本引擎", desc = "点击管理脚本")
object JsScriptingHook : SwitchHookItem(),
    WeDatabaseListenerApi.IInsertListener, IWePacketInterceptor {

    private val TAG = nameof(JsScriptingHook)

    // type=0 post
    // type=1 plain text
    // type=3 image
    // type=34 voice
    // type=37 add friend request verification
    // type=40 friends you possible know
    // type=42 contact card
    // type=43 video
    // type=48 static location
    // type=49 app message
    // type=50 voip
    // type=51 app initialization
    // type=52 voip notification
    // type=53 voip invitation
    // type=419430449 cash transfer
    // type=436207665 red packet
    // type=1040187441 qq music
    // type=1090519089 file

    val rules = ConcurrentHashMap<String, String>()

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        WeLogger.i(TAG, "loading scripts...")
        for (path in (PathUtils.moduleDataPath!! / "scripts").listDirectoryEntries("*.js")) {
            val name = path.name
            val content = runCatching { path.readText() }.getOrElse { continue }
            WeLogger.i(TAG, "loaded script, name='${name}', length=${content.length}")
            rules[name] = content
        }
    }

    // --- onMessage ---
    override fun onInsert(table: String, values: ContentValues) {
        if (!isEnabled) return
        if (!OnMessage.isEnabled) {
            WeLogger.i(TAG, "OnMessage hook is disabled, ignoring")
            return
        }

        if (table != "message") return

        val isSend = values.getAsInteger("isSend") ?: return
        // if (isSend != 0) return // ignore outgoing

        val talker = values.getAsString("talker") ?: return
        val content = values.getAsString("content") ?: return
        val type = values.getAsInteger("type") ?: 0

        WeLogger.i(
            TAG,
            "message received: talker=$talker type=$type content.length=${content.length}"
        )

        JsEngine.executeAllOnMessage(rules, talker, content, type, isSend)
    }

    override fun onDisable() {
        WeLogger.i(TAG, "removing automation DB listener")
        WeDatabaseListenerApi.removeListener(this)
    }

    // --- onRequest ---
    override fun onRequest(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        if (!isEnabled) return null
        if (!OnRequest.isEnabled) {
            WeLogger.i(TAG, "OnRequest hook is disabled, ignoring")
            return null
        }

        try {
            val data = WeProtoData()
            data.fromBytes(reqBytes)
            val json = data.toJsonObject()
            val modifiedJson = JsEngine.executeAllOnRequest(uri, cgiId, json)
            data.applyViewJson(modifiedJson, true)
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e(TAG, e)
        }

        return null
    }

    // --- onResponse ---
    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (!isEnabled) return null
        if (!OnResponse.isEnabled) {
            WeLogger.i(TAG, "OnResponse hook is disabled, ignoring")
            return null
        }

        try {
            val data = WeProtoData()
            data.fromBytes(respBytes)
            val json = data.toJsonObject()
            val modifiedJson = JsEngine.executeAllOnResponse(uri, cgiId, json)
            data.applyViewJson(modifiedJson, true)
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e(TAG, e)
        }
        return null
    }
}
