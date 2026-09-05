package dev.ujhhgtg.wekit.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.TargetProcess
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId

object BlockAtAllNotifications : ClickableFeature() {

    override val technicalId = "屏蔽群聊@所有人"
    override val nameRes = R.string.feature_block_at_all_notifications_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_block_at_all_notifications_description

    private var useWhitelist by WePrefs.prefOption(KEY_USE_WHITELIST, false)
    private var whitelist by WePrefs.prefOption(KEY_WHITELIST, emptySet())
    private var blacklist by WePrefs.prefOption(KEY_BLACKLIST, emptySet())

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var useWhitelistState by remember { mutableStateOf(useWhitelist) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_block_at_all_notifications_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(
                                    if (useWhitelistState) R.string.filter_list_whitelist_selected
                                    else R.string.filter_list_blacklist_selected
                                ),
                                description = stringResource(
                                    if (useWhitelistState) R.string.chat_block_at_all_whitelist_description
                                    else R.string.chat_block_at_all_blacklist_description
                                ),
                                checked = useWhitelistState,
                                onCheckedChange = {
                                    useWhitelistState = it
                                    useWhitelist = it
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(
                                    if (useWhitelistState) R.string.filter_list_configure_whitelist
                                    else R.string.filter_list_configure_blacklist
                                ),
                                description = stringResource(R.string.chat_block_at_all_select_groups_hint),
                                onClick = {
                                    val groups = WeDatabaseApi.getGroups()
                                    val currentList = if (useWhitelistState) whitelist else blacklist
                                    showComposeDialog(context) {
                                        ContactsSelector(
                                            title = stringResource(
                                                if (useWhitelistState) R.string.filter_list_select_whitelist
                                                else R.string.filter_list_select_blacklist
                                            ),
                                            contacts = groups,
                                            initialSelectedWxIds = currentList,
                                            onDismiss = onDismiss,
                                        ) { selectedIds ->
                                            if (useWhitelistState) {
                                                whitelist = selectedIds
                                            } else {
                                                blacklist = selectedIds
                                            }
                                            showToast(
                                                localizedChatQuantity(
                                                    R.plurals.chat_block_at_all_groups_saved,
                                                    selectedIds.size,
                                                    selectedIds.size,
                                                )
                                            )
                                            onDismiss()
                                        }
                                    }
                                },
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    fun shouldSuppress(groupId: String): Boolean {
        if (!groupId.isGroupChatWxId || !WePrefs.getBoolOrDef(technicalId, false)) return false
        return if (WePrefs.getBoolOrDef(KEY_USE_WHITELIST, false)) {
            groupId !in WePrefs.getStringSetOrDef(KEY_WHITELIST, emptySet())
        } else {
            groupId in WePrefs.getStringSetOrDef(KEY_BLACKLIST, emptySet())
        }
    }

    private const val KEY_USE_WHITELIST = "block_at_all_notifications_use_whitelist"
    private const val KEY_WHITELIST = "block_at_all_notifications_whitelist"
    private const val KEY_BLACKLIST = "block_at_all_notifications_blacklist"
}

/** Installs the notification hooks in both the main and push processes. */
object BlockAtAllNotificationsRuntime : ApiFeature(), IResolveDex {

    override val technicalId = "群聊@所有人通知拦截服务"
    override val nameRes = R.string.feature_block_at_all_notifications_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_block_at_all_notifications_description

    private const val TAG = "BlockAtAllNotifications"
    private const val PENDING_TTL_MILLIS = 30_000L
    private const val MAX_PENDING_PER_GROUP = 16

    private data class PendingAtAll(
        val rawContent: String,
        val capturedAt: Long,
    )

    private val pendingLock = Any()
    private val pendingByGroup = HashMap<String, ArrayDeque<PendingAtAll>>()

    private val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            returnType = "void"
            usingEqStrings(
                "jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s"
            )
        }
    }

    private val methodNotifyForLightPush by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(7)
            returnType = "void"
            usingEqStrings(
                "LightPush [NO NOTIFICATION] Util.isNullOrNil(userName) || Util.isNullOrNil(nickName)"
            )
        }
    }

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    override fun onEnable() {
        WeMessageApi.methodMsgInfoStorageInsertMessage.hookAfter {
            val message = MessageInfo(args[0]!!)
            if (message.isSelfSender || !message.isInGroupChat || !message.isNotifyAll) return@hookAfter
            recordPending(message.talker, message.content)
        }

        methodDealNotify.hookBefore(100) {
            val talker = args[1] as String
            val rawContent = args[2] as String
            if (!consumePending(talker, rawContent)) return@hookBefore
            if (!BlockAtAllNotifications.shouldSuppress(talker)) return@hookBefore
            WeLogger.i(TAG, "suppressing @all notification from $talker")
            result = null
        }

        methodNotifyForLightPush.hookBefore(100) {
            val talker = args[1] as String
            if (!BlockAtAllNotifications.shouldSuppress(talker)) return@hookBefore
            val msgSource = args[5] as Map<*, *>?
            if (!msgSource.containsAtAllMention()) return@hookBefore
            WeLogger.i(TAG, "suppressing LightPush @all notification from $talker")
            result = null
        }
    }

    override fun onDisable() {
        synchronized(pendingLock) { pendingByGroup.clear() }
    }

    private fun recordPending(groupId: String, rawContent: String) {
        val now = System.currentTimeMillis()
        synchronized(pendingLock) {
            val queue = pendingByGroup.getOrPut(groupId) { ArrayDeque() }
            discardExpired(queue, now)
            queue.addLast(PendingAtAll(rawContent, now))
            while (queue.size > MAX_PENDING_PER_GROUP) queue.removeFirst()
        }
    }

    private fun consumePending(groupId: String, rawContent: String): Boolean {
        val now = System.currentTimeMillis()
        return synchronized(pendingLock) {
            val queue = pendingByGroup[groupId] ?: return@synchronized false
            discardExpired(queue, now)
            val entries = queue.toList()
            val matchIndex = entries.indexOfFirst { it.rawContent == rawContent }
            if (matchIndex < 0) return@synchronized false
            queue.clear()
            queue.addAll(entries.filterIndexed { index, _ -> index != matchIndex })
            if (queue.isEmpty()) pendingByGroup.remove(groupId)
            true
        }
    }

    private fun discardExpired(queue: ArrayDeque<PendingAtAll>, now: Long) {
        while (queue.firstOrNull()?.let { now - it.capturedAt > PENDING_TTL_MILLIS } == true) {
            queue.removeFirst()
        }
    }

    private fun Map<*, *>?.containsAtAllMention(): Boolean {
        val atUsers = this?.get(".msgsource.atuserlist") as? String ?: return false
        return atUsers.split(',').any { user ->
            val normalized = user.trim()
            normalized == "notify@all" || normalized == "announcement@all"
        }
    }
}
