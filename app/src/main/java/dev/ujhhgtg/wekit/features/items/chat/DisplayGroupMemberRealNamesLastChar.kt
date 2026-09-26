package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.data.WeKitDatabase
import dev.ujhhgtg.wekit.data.entity.ContactRealNamePartEntity
import dev.ujhhgtg.wekit.data.JsonDataMigration
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.BeforeTransferRespProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.BeforeTransferReqProto
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.WeColorField
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object DisplayGroupMemberRealNamesLastChar : ClickableFeature(), IContactInfoProvider {

    override val technicalId = "显示群成员实名尾字"
    override val nameRes = R.string.feature_display_group_member_real_names_last_char_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_display_group_member_real_names_last_char_description

    private const val TAG = "DisplayGroupMemberRealNamesLastChar"

    private const val DEFAULT_FG = "#FF9E9E9E"

    /**
     * Foreground color for the real-name annotation. Exposed so
     * [DisplayGroupMemberRealName] (the sole TextView annotator) can read the same preference.
     */
    var annotationFg by KvStore.prefOption("real_name_last_char_fg", DEFAULT_FG)

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var fg by remember { mutableStateOf(annotationFg) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_display_group_member_real_names_last_char_name)) },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        WeColorField(
                            label = stringResource(R.string.chat_color_foreground),
                            value = fg,
                            onValueChange = { fg = it })
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        annotationFg = fg
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * wxId → masked real name (last char). Only confirmed hits are stored here.
     * Loaded when the feature is enabled; updated only after the row is persisted.
     * Exposed so [DisplayGroupMemberRealName] can read it for combined display.
     */
    val realNames = ConcurrentHashMap<String, String>()

    /**
     * Tracks wxIds for which a fetch has already been dispatched this session.
     * Prevents duplicate in-flight requests. On network failure the id is removed so the
     * next view-bind can retry; on "no real name" it stays in to suppress further requests.
     */
    private val pendingOrQueried = ConcurrentHashMap.newKeySet<String>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        runBlocking(Dispatchers.IO) {
            JsonDataMigration.requireCompleted("chat", "real_names_last_char")
            val saved = WeKitDatabase.instance.simpleStructuredDao().getRealNameParts("MASKED")
            realNames.clear()
            realNames.putAll(saved.associate { it.wxId to it.value })
        }
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    // ── Network fetch ─────────────────────────────────────────────────────────

    /** Outcome of a [actualFetchRealName] call, reported on a background thread. */
    private sealed interface FetchResult {
        data class Found(val realName: String) : FetchResult

        /** Server responded but field "4" was absent → contact deleted/blocked us, or abnormal account. */
        data object NoRealName : FetchResult
        data object SaveFailure : FetchResult
        data class Failure(val errType: Int, val errCode: Int, val errMsg: String?) : FetchResult
    }

    /**
     * Reuses the same `/cgi-bin/mmpay-bin/beforetransfer` CGI as
     * [dev.ujhhgtg.wekit.features.items.contacts.DetectDeletedFriends].
     * Field `"4"` in the response carries the real nickname; its absence means the contact
     * deleted/blocked us or the account is abnormal — no disk entry is written in that case.
     *
     * On [FetchResult.Found] the name is cached and persisted before [onResult] runs. [onResult]
     * is invoked on a background thread; callers that touch UI must hop to the main thread.
     */
    private fun actualFetchRealName(senderId: String, groupId: String?, onResult: (FetchResult) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val reqBytes = BeforeTransferReqProto(userName = senderId, groupId = groupId).encode()
            WePacketHelper.sendCgi(
                "/cgi-bin/mmpay-bin/beforetransfer", 2783, 0, 0,
                reqBytes
            ) {
                onSuccess { bytes ->
                    val realName = bytes
                        ?.let { runCatching { BeforeTransferRespProto.decode(it) }.getOrNull() }
                        ?.maskedRealName

                    if (realName != null) {
                        CoroutineScope(Dispatchers.IO).launch persist@{
                            try {
                                WeKitDatabase.instance.simpleStructuredDao()
                                    .putRealNamePart(ContactRealNamePartEntity(senderId, "MASKED", realName))
                                realNames[senderId] = realName
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                WeLogger.w(TAG, "failed to save masked real name", e)
                                onResult(FetchResult.SaveFailure)
                                return@persist
                            }
                            onResult(FetchResult.Found(realName))
                        }
                    } else {
                        onResult(FetchResult.NoRealName)
                    }
                }

                onFailure { errType, errCode, errMsg ->
                    WeLogger.w(TAG, "fetch failed for $senderId (groupId=$groupId): errType=$errType errCode=$errCode errMsg=$errMsg")

                    if (groupId != null) {
                        actualFetchRealName(senderId, null, onResult)
                    } else {
                        onResult(FetchResult.Failure(errType, errCode, errMsg))
                    }
                }
            }
        }
    }

    /**
     * Initiates a background fetch for [senderId]'s masked real name if no fetch has been
     * dispatched yet this session. [onFound] is called on a background thread when the name is
     * successfully retrieved. Callers that need to touch UI must post to the main thread themselves.
     *
     * The [pendingOrQueried] gate ensures at most one in-flight request per wxId.
     */
    fun fetchRealName(senderId: String, groupId: String, onFound: (String) -> Unit) {
        // add() returns true only when the element was absent → fetch dispatched exactly once
        if (!pendingOrQueried.add(senderId)) return

        actualFetchRealName(senderId, groupId) { result ->
            when (result) {
                is FetchResult.Found -> onFound(result.realName)
                // wxId stays in pendingOrQueried to suppress retries for the rest of this session.
                FetchResult.NoRealName -> {}
                // Evict so the next view-bind for this sender can retry.
                is FetchResult.Failure, FetchResult.SaveFailure -> pendingOrQueried.remove(senderId)
            }
        }
    }

    // ── IContactInfoProvider ──────────────────────────────────────────────────

    /**
     * Exposes a contact-detail entry only for individual group members.
     * Shows the cached real name as the summary when available.
     */
    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        val memberId = activity.currentWxId ?: return emptyList()
        if (memberId.isGroupChatWxId) return emptyList()

        return listOf(
            PreferenceItem(
                title = activity.localizedChatString(R.string.chat_real_name_fetch_title),
                summary = realNames[memberId]?.let { activity.localizedChatString(R.string.chat_real_name_value, it) }
                    ?: activity.localizedChatString(R.string.chat_contact_tap_to_fetch),
                position = 1,
                onClick = onClick@{ activity ->
                    activity.run {
                        val clickedMemberId = activity.currentWxId ?: return@onClick
                        val groupId = WeCurrentConversationApi.value.takeIf { it.isGroupChatWxId }

                        WeLogger.i(TAG, "fetching last char for $clickedMemberId $groupId")

                        val cached = realNames[clickedMemberId]
                        if (cached != null) {
                            showToast(activity, activity.localizedChatString(R.string.chat_real_name_value, cached))
                            return@onClick
                        }

                        showToast(activity, activity.localizedChatString(R.string.chat_real_name_fetching))
                        actualFetchRealName(clickedMemberId, groupId) { result ->
                            mainHandler.post {
                                when (result) {
                                    is FetchResult.Found -> showToast(activity, activity.localizedChatString(R.string.chat_real_name_value, result.realName))
                                    FetchResult.NoRealName -> showToast(activity, activity.localizedChatString(R.string.chat_real_name_not_found))
                                    FetchResult.SaveFailure -> showToast(activity, activity.localizedChatString(R.string.structured_storage_save_failed))
                                    is FetchResult.Failure -> showToast(activity, activity.localizedChatString(R.string.chat_real_name_fetch_failed, result.errMsg ?: result.errCode))
                                }
                            }
                        }
                    }
                },
            )
        )
    }

}
