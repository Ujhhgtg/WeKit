package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.automation.AutomationSettingsActivity
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.AddMsgProto
import dev.ujhhgtg.wekit.features.automation.AutomationCodecs
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.automation.AutomationMessageKind
import dev.ujhhgtg.wekit.features.automation.AutomationMessageTypeRule
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationRuleValues
import dev.ujhhgtg.wekit.features.automation.AutomationRules
import dev.ujhhgtg.wekit.features.automation.AutomationScope
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import dev.ujhhgtg.wekit.features.automation.localizedAutomationString
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.automation.keywordEditor
import dev.ujhhgtg.wekit.ui.automation.loadAutomationContacts
import dev.ujhhgtg.wekit.ui.automation.messageTypeEditor
import dev.ujhhgtg.wekit.ui.automation.toggleEditor
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Rules of [BlockMessages] on the scope hierarchy, with Hchat's precedence: `blockAll` ("quick
 * block all", set from conversation groups) wins outright; a configured chat or member whose
 * switch is off is left alone; a chat or member explicitly set to not block is an exclusion that
 * beats blocking elsewhere; otherwise the resolved rules decide.
 */
object BlockMessagesAutomation : AutomationSpec("MESSAGE_BLOCK") {
    val blockAll = AutomationRuleKey("blockAll", AutomationCodecs.toggle, AutomationToggleRule())
    val block = AutomationRuleKey("block", AutomationCodecs.toggle, AutomationToggleRule())
    val types = AutomationRuleKey("types", AutomationCodecs.messageTypes, AutomationMessageTypeRule())
    val textKeyword = AutomationRuleKey("textKeyword", AutomationCodecs.keyword, AutomationKeywordRule(ignoreCase = true))
    val active = AutomationRuleKey("active", AutomationCodecs.toggle, AutomationToggleRule(true))

    override val keys = listOf(blockAll, block, types, textKeyword, active)
    override val scopeSwitch = active
    override val scopeTypes = setOf(
        AutomationScopeType.GLOBAL, AutomationScopeType.CATEGORY, AutomationScopeType.CONTACT,
        AutomationScopeType.GROUP_MEMBER, AutomationScopeType.PRESET,
    )
    override val contactKinds = AutomationTalkerCategory.entries.toSet()
    override val titleRes = R.string.feature_block_messages_name
    override val globalSummaryRes = R.string.block_messages_global_summary

    override val editors = listOf(
        toggleEditor(blockAll, R.string.block_messages_block_all, R.string.block_messages_block_all_on, R.string.block_messages_block_all_off),
        toggleEditor(block, R.string.block_messages_block, R.string.block_messages_block_on, R.string.block_messages_block_off),
        messageTypeEditor(types, R.string.block_messages_types),
        keywordEditor(textKeyword, R.string.block_messages_text_keyword, R.string.block_messages_text_keyword_off),
    )

    override fun validate(rules: AutomationRules, keyIds: Set<String>?): String? {
        if (validates(keyIds, textKeyword)) {
            return rules[textKeyword].validationError(localizedAutomationString(R.string.block_messages_text_keyword))
        }
        return null
    }

    /**
     * [chain] holds the stored overrides of the chat and member scopes that exist, including
     * switched-off ones; [variants] are resolved without those. Blocks when any preset variant
     * blocks (Hchat: a binding blocks if any of its templates matches).
     */
    fun shouldBlock(
        variants: List<AutomationRules>,
        chain: List<AutomationRuleValues>,
        kind: AutomationMessageKind,
        text: String,
    ): Boolean {
        if (chain.any { it[blockAll]?.enabled == true } || variants.any { it[blockAll].enabled }) return true
        val switchedOn = chain.filter { it[active]?.enabled != false }
        if (chain.isNotEmpty() && switchedOn.isEmpty()) return false
        if (switchedOn.any { it[block]?.enabled == false }) return false
        return variants.any { rules -> rules[block].enabled && ruleMatches(rules[types], rules[textKeyword], kind, text) }
    }

    /**
     * "All kinds": everything except text that misses the keyword. Explicit kinds: text must also
     * match the keyword. A quote counts as text.
     */
    fun ruleMatches(types: AutomationMessageTypeRule, keyword: AutomationKeywordRule, kind: AutomationMessageKind, text: String): Boolean {
        if (kind.isTextLike && !keyword.matches(text)) return false
        if (types.all) return true
        return kind in types.kinds || kind == AutomationMessageKind.QUOTE && AutomationMessageKind.TEXT in types.kinds
    }

    /** Sets or clears "quick block all" on every chat in [talkers], keeping their other rules. */
    suspend fun setBlockAll(talkers: Collection<String>, blocked: Boolean) {
        val snapshot = store.snapshot()
        store.saveAll(talkers.map { talker ->
            val stored = snapshot[AutomationScope.contact(talker)]
            stored.copy(values = if (blocked) stored.values.with(blockAll, AutomationToggleRule(true)) else stored.values.without(blockAll))
        })
    }

    fun isBlockAll(talker: String): Boolean =
        store.snapshot()[AutomationScope.contact(talker)].values[blockAll]?.enabled == true
}

/** Drops matching incoming messages before WeChat stores them (ported from Hchat's 屏蔽消息). */
object BlockMessages : ClickableFeature(), IResolveDex {
    override val technicalId = "屏蔽消息"
    override val nameRes = R.string.feature_block_messages_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_block_messages_description

    private const val TAG = "BlockMessages"

    /** `MessageSyncExtension.processAddMsg(AddMsg, …)`: parses, stores and notifies one synced message. */
    private val methodProcessAddMsg by dexMethod {
        matcher {
            usingEqStrings("processAddMsg insert db error")
        }
    }

    override fun onEnable() {
        BlockMessagesAutomation.store.snapshot()
        methodProcessAddMsg.hookBefore(priority = 10_000) {
            val addMsg = AddMsgProto.decode(args[0]!!.reflekt().invokeMethod("toByteArray", args[0], superclass = true) as ByteArray)
            if (shouldBlock(addMsg)) result = null
        }
    }

    override fun onClick(context: ComponentActivity) {
        AutomationSettingsActivity.start(context, BlockMessagesAutomation)
    }

    private fun shouldBlock(addMsg: AddMsgProto): Boolean {
        val self = WeApi.selfWxId
        val from = addMsg.fromUserName.userName
        val to = addMsg.toUserName.userName
        val talker = when {
            AutomationTalkerCategory.of(to) == AutomationTalkerCategory.GROUP -> to
            AutomationTalkerCategory.of(from) == AutomationTalkerCategory.GROUP -> from
            from == self -> to
            else -> from
        }
        if (talker.isEmpty() || from == self) return false
        var content = addMsg.content.userName
        var sender = from
        if (AutomationTalkerCategory.of(talker) == AutomationTalkerCategory.GROUP) {
            // Member messages carry a "wxid:\n" prefix; system messages don't and count as the group's own.
            val prefixEnd = content.indexOf(":\n")
            if (prefixEnd > 0 && content.indexOf('<') !in 0..<prefixEnd) {
                sender = content.substring(0, prefixEnd)
                content = content.substring(prefixEnd + 2)
            } else {
                sender = talker
            }
        }
        if (sender == self) return false
        val kind = AutomationMessageKind.classify(addMsg.msgType, content)
        val snapshot = BlockMessagesAutomation.store.snapshot()
        val chain = listOfNotNull(
            snapshot.scopes[AutomationScope.contact(talker)]?.values,
            if (sender != talker && AutomationTalkerCategory.of(talker) == AutomationTalkerCategory.GROUP) {
                snapshot.scopes[AutomationScope.member(talker, sender)]?.values
            } else null,
        )
        val text = AutomationMessageKind.textOf(kind, content)
        if (!BlockMessagesAutomation.shouldBlock(snapshot.resolveVariants(talker, sender), chain, kind, text)) return false
        WeLogger.i(TAG, "blocked $kind message ${addMsg.newMsgId} from $sender in $talker")
        return true
    }
}

/**
 * Long-press action of a conversation group: block (or unblock) every conversation in it. Blocking
 * turns [BlockMessages] on; unblocking only removes the block-all overrides and keeps other rules.
 */
fun showConversationGroupBlockDialog(context: Context, groupName: String, conversationIds: List<String>) {
    if (conversationIds.isEmpty()) {
        showToast(context, localizedAutomationString(R.string.block_messages_group_empty))
        return
    }
    val blocked = conversationIds.filter(BlockMessagesAutomation::isBlockAll)
    showComposeDialog(context) {
        AlertDialogContent(
            title = { Text(stringResource(R.string.block_messages_group_title, groupName)) },
            text = { Text(stringResource(R.string.block_messages_group_summary, conversationIds.size, blocked.size)) },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            confirmButton = {
                Row {
                    TextButton(enabled = blocked.isNotEmpty(), onClick = {
                        onDismiss()
                        pickAndApply(context, blocked, block = false)
                    }) { Text(stringResource(R.string.block_messages_group_unblock)) }
                    TextButton(onClick = {
                        onDismiss()
                        pickAndApply(context, conversationIds, block = true)
                    }) { Text(stringResource(R.string.block_messages_group_block)) }
                }
            },
        )
    }
}

private fun pickAndApply(context: Context, ids: List<String>, block: Boolean) {
    val known = loadAutomationContacts(AutomationTalkerCategory.entries.toSet()).associateBy { it.wxId }
    val contacts = ids.map { id ->
        known[id] ?: object : IWeContact {
            override val wxId = id
            override val nickname = WeDatabaseApi.getDisplayName(id)
            override val displayName = nickname
            override val avatarUrl = ""
        }
    }
    val title = localizedAutomationString(
        if (block) R.string.block_messages_group_pick_block else R.string.block_messages_group_pick_unblock,
    )
    showComposeDialog(context) {
        val scope = rememberCoroutineScope()
        ContactsSelector(title = title, contacts = contacts, initialSelectedWxIds = ids.toSet(), onDismiss = onDismiss) { selected ->
            scope.launch {
                try {
                    BlockMessagesAutomation.setBlockAll(selected, block)
                    if (block && !BlockMessages.isEnabled) BlockMessages.applyToggle(true)
                    showToast(context, localizedAutomationString(R.string.settings_saved))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    WeLogger.e("BlockMessages", "failed to update group block", e)
                    showToast(context, localizedAutomationString(R.string.structured_storage_save_failed))
                }
                onDismiss()
            }
        }
    }
}
