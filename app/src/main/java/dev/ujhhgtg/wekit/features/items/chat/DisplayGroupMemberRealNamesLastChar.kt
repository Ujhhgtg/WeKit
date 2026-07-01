package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.core.view.isGone
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.chat.DisplayGroupMemberRealNamesLastChar.actualFetchRealName
import dev.ujhhgtg.wekit.features.items.chat.DisplayGroupMemberRealNamesLastChar.cacheFile
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Feature(
    name = "显示群成员实名尾字",
    categories = ["聊天"],
    description = "通过转账接口获取并显示群成员的实名尾字"
)
object DisplayGroupMemberRealNamesLastChar : SwitchFeature(), WeChatMessageViewApi.ICreateViewListener, IContactInfoProvider {

    private val TAG = This.Class.simpleName

    /**
     * Integer tag key stamped onto the username [TextView] so in-flight async fetches can
     * detect that the view has been recycled to a different message before posting their update.
     * Placed in the 0x7E… range to avoid collisions with Android-generated R.id values (0x7F…).
     */
    private const val VIEW_TAG_SENDER = 0x7E000001

    private const val PREF_KEY = "real_name_last_char"

    private val cacheFile by lazy { KnownPaths.moduleData / "real_names.json" }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * wxId → real nickname. Only confirmed real names are stored here; contacts that have
     * deleted/blocked us produce no entry. Persisted to [cacheFile] across sessions.
     */
    private val realNames = ConcurrentHashMap<String, String>()

    /**
     * Tracks wxIds for which a fetch has already been dispatched this session.
     * Prevents duplicate in-flight requests. On network failure the id is removed so the
     * next view-bind can retry; on "no real name" it stays in to suppress further requests.
     */
    private val pendingOrQueried = ConcurrentHashMap.newKeySet<String>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        loadCache()
        WeChatMessageViewApi.addListener(this)
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        WeContactPrefsScreenApi.removeProvider(this)
    }

    // ── Cache I/O ─────────────────────────────────────────────────────────────

    private fun loadCache() {
        runCatching {
            val file = cacheFile
            if (!file.exists()) return
            val map = Json.decodeFromString<Map<String, String>>(file.readText())
            realNames.putAll(map)
            WeLogger.d(TAG, "loaded ${map.size} cached real names")
        }.onFailure { WeLogger.w(TAG, "failed to load $cacheFile", it) }
    }

    private fun saveCache() {
        runCatching {
            cacheFile.writeText(Json.encodeToString(realNames.toMap()))
        }.onFailure { WeLogger.w(TAG, "failed to save $cacheFile", it) }
    }

    // ── ICreateViewListener ───────────────────────────────────────────────────

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (!msgInfo.isInGroupChat) return
        if (msgInfo.isSend != 0) return
        val sender = runCatching { msgInfo.sender }.getOrNull() ?: return

        val textView = view.tag.reflekt()
            .firstField { name = "userTV"; superclass() }
            .get() as? TextView? ?: return

        if (textView.isGone) return

        // Stamp sender so the async callback can verify the view hasn't been recycled
        textView.setTag(VIEW_TAG_SENDER, sender)

        val cached = realNames[sender]
        if (cached != null) {
            applyRealName(textView, cached)
            return
        }

        // add() returns true only when the element was absent → fetch dispatched exactly once
        if (pendingOrQueried.add(sender)) {
            fetchRealName(sender, msgInfo.talker, textView)
        }
    }

    // ── Network fetch ─────────────────────────────────────────────────────────

    /** Outcome of a [actualFetchRealName] call, reported on the CGI callback thread. */
    private sealed interface FetchResult {
        data class Found(val realName: String) : FetchResult
        /** Server responded but field "4" was absent → contact deleted/blocked us, or abnormal account. */
        data object NoRealName : FetchResult
        data class Failure(val errType: Int, val errCode: Int, val errMsg: String?) : FetchResult
    }

    /**
     * Reuses the same `/cgi-bin/mmpay-bin/beforetransfer` CGI as [dev.ujhhgtg.wekit.features.items.contacts.DetectDeletedFriends].
     * Field `"4"` in the response carries the real nickname; its absence means the contact
     * deleted/blocked us or the account is abnormal — no disk entry is written in that case.
     *
     * On [FetchResult.Found] the name is cached and persisted before [onResult] runs. [onResult]
     * is invoked on the CGI callback thread; callers that touch UI must hop to the main thread.
     */
    private fun actualFetchRealName(senderId: String, groupId: String?, onResult: (FetchResult) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val payload = if (groupId != null) """{"2":"$senderId", "4":"$groupId"}""" else """{"2":"$senderId"}"""
            WePacketHelper.sendCgi(
                "/cgi-bin/mmpay-bin/beforetransfer", 2783, 0, 0,
                payload
            ) {
                onSuccess { json, _ ->
                    val realName = runCatching {
                        Json.parseToJsonElement(json).jsonObject["4"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()

                    if (realName != null) {
                        realNames[senderId] = realName
                        saveCache()
                        onResult(FetchResult.Found(realName))
                    } else {
                        onResult(FetchResult.NoRealName)
                    }
                }

                onFailure { errType, errCode, errMsg ->
                    WeLogger.w(TAG, "fetch failed for $senderId: errType=$errType errCode=$errCode errMsg=$errMsg")
                    onResult(FetchResult.Failure(errType, errCode, errMsg))
                }
            }
        }
    }

    /** View-bind path: fetch then patch the username [TextView] if it still belongs to [senderId]. */
    private fun fetchRealName(senderId: String, groupId: String, textView: TextView) {
        actualFetchRealName(senderId, groupId) { result ->
            when (result) {
                is FetchResult.Found -> mainHandler.post {
                    // Only apply if the view still belongs to this sender
                    if (textView.getTag(VIEW_TAG_SENDER) == senderId) {
                        applyRealName(textView, result.realName)
                    }
                }
                // wxId remains in pendingOrQueried to suppress retries for the rest of this session.
                FetchResult.NoRealName -> {}
                // Evict so the next view-bind for this sender can retry
                is FetchResult.Failure -> pendingOrQueried.remove(senderId)
            }
        }
    }

    // ── View update ───────────────────────────────────────────────────────────

    private fun applyRealName(textView: TextView, realName: String) {
        val existing = textView.text ?: return
        val base = existing.toString() // plain text only, for length/endsWith checks
        val annotation = " ($realName)"

        // Guard against double-application if onCreateView fires twice for the same binding
        if (base.endsWith(annotation)) return

        // Construct from the live CharSequence so that any spans already applied by other hooks
        // (e.g. the RoundedBackgroundSpan role badge from DisplayGroupMemberRoles) are preserved.
        val sb = SpannableStringBuilder(existing)
        sb.append(annotation)

        val annotStart = base.length
        val annotEnd = sb.length

        sb.setSpan(
            ForegroundColorSpan(0xFF9E9E9E.toInt()),
            annotStart, annotEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        textView.text = sb
    }

    // ── IContactInfoProvider ──────────────────────────────────────────────────

    /**
     * Exposes a contact-detail entry only for group members: requires the opened contact to be an individual (not the group itself).
     * Shows the cached real name as the summary when available.
     */
    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        val memberId = activity.currentWxId ?: return emptyList()
        if (memberId.isGroupChatWxId) return emptyList()

        return listOf(
            PreferenceItem(
                key = PREF_KEY,
                title = "获取实名尾字",
                summary = realNames[memberId]?.let { "实名: $it" } ?: "点击获取",
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false

        activity.run {
            val memberId = intent.getStringExtra("Contact_User")!!
            val groupId = intent.getStringExtra("room_name")
                ?: intent.getStringExtra("Contact_ChatRoomId")

            WeLogger.i(TAG, "fetching last char for $memberId $groupId")

            val cached = realNames[memberId]
            if (cached != null) {
                showToast(activity, "实名: $cached")
                return true
            }

            showToast(activity, "正在获取...")
            actualFetchRealName(memberId, groupId) { result ->
                mainHandler.post {
                    when (result) {
                        is FetchResult.Found -> showToast(activity, "实名: ${result.realName}")
                        FetchResult.NoRealName -> showToast(activity, "获取失败: 可能被删除/被拉黑/对方账号异常!")
                        is FetchResult.Failure -> showToast(activity, "获取失败: ${result.errMsg ?: result.errCode}!")
                    }
                }
            }
            return true
        }
    }
}
