package dev.ujhhgtg.wekit.hooks.items.contacts

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.wekit.hooks.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import java.util.Collections
import java.util.WeakHashMap

@HookItem(name = "显示群成员实名", categories = ["联系人与群组"], description = "查询并缓存群聊成员实名, 可在消息详情中显示")
object DisplayGroupMemberRealName : SwitchHookItem() {

    private const val TAG = "DisplayGroupMemberRealName"
    private const val REQUEST_CLASS_NAME = "com.tencent.mm.plugin.remittance.model.i"
    private const val CALLBACK_METHOD_NAME = "I"
    private const val RESPONSE_FIELD_NAME = "r"
    private const val REAL_NAME_FIELD_NAME = "f"
    private const val REAL_NAME_PREFIX_KEY = "key_real_name_prefix"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRequests = Collections.synchronizedMap(
        WeakHashMap<Any, PendingRealNameRequest>()
    )

    private var realNamePrefix by prefOption(REAL_NAME_PREFIX_KEY, "*")

    override fun onEnable() {
        val requestClass = XposedHelpers.findClassIfExists(REQUEST_CLASS_NAME, ClassLoaders.HOST)
            ?: error("real name request class not found: $REQUEST_CLASS_NAME")

        XposedBridge.hookAllMethods(
            requestClass,
            CALLBACK_METHOD_NAME,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val request = param.thisObject ?: return
                    val pending = pendingRequests.remove(request) ?: return
                    val response = XposedHelpers.getObjectField(request, RESPONSE_FIELD_NAME)
                        ?: return
                    val fullName = XposedHelpers.getObjectField(
                        response,
                        REAL_NAME_FIELD_NAME
                    ) as? String ?: return
                    if (fullName.isEmpty()) return

                    val displayName = maskRealName(fullName)
                    cacheDisplayName(pending.wxId, displayName)
                    mainHandler.post { pending.onResult(displayName) }
                }
            }
        ).forEach(::registerUnhook)
    }

    fun getCachedDisplayName(wxId: String): String? {
        return WePrefs.getStringOrDef(cacheKey(wxId), "").takeIf { it.isNotEmpty() }
    }

    fun requestDisplayName(
        wxId: String,
        chatroomId: String,
        onResult: (String) -> Unit
    ): Boolean {
        if (!hasEnabled) return false
        if (wxId.isBlank()) return false

        getCachedDisplayName(wxId)?.let { cached ->
            mainHandler.post { onResult(cached) }
            return true
        }

        return runCatching {
            val requestClass = XposedHelpers.findClassIfExists(REQUEST_CLASS_NAME, ClassLoaders.HOST)
                ?: return@runCatching false
            val request = XposedHelpers.newInstance(requestClass, wxId, chatroomId)
                ?: return@runCatching false
            pendingRequests[request] = PendingRealNameRequest(wxId, onResult)
            WeNetSceneApi.addNetSceneToQueue(request)
            true
        }.onFailure {
            WeLogger.w(TAG, "failed to request real name for $wxId", it)
        }.getOrDefault(false)
    }

    fun parseGroupSender(content: String): String? {
        val idx = content.indexOf(":\n")
        if (idx <= 0) return null
        val wxId = content.substring(0, idx).trim()
        if (wxId.isEmpty() || wxId.contains("<")) return null
        return wxId
    }

    fun formatDisplayName(displayName: String): String {
        return "*$displayName"
    }

    private fun cacheDisplayName(wxId: String, displayName: String) {
        WePrefs.putString(cacheKey(wxId), displayName)
    }

    private fun cacheKey(wxId: String) = "real_name_$wxId"

    private fun maskRealName(fullName: String): String {
        return realNamePrefix + fullName.last()
    }

    private data class PendingRealNameRequest(
        val wxId: String,
        val onResult: (String) -> Unit
    )
}
