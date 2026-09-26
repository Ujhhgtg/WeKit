package dev.ujhhgtg.wekit.features.items.chat.autoreply

import android.os.SystemClock
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.automation.AutomationSettingsActivity
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.automation.AutomationMessageKind
import dev.ujhhgtg.wekit.features.automation.AutomationReplyContext
import dev.ujhhgtg.wekit.features.automation.AutomationReplyExecutor
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.IncomingMessageEvents
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Rule-based automatic replies (ported from Hchat), with steps run by [AutomationReplyExecutor]. */
object AutoReply : ClickableFeature() {
    override val technicalId = "自动回复"
    override val nameRes = R.string.feature_auto_reply_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_reply_description

    private const val TAG = "AutoReply"

    private val executor = Executors.newSingleThreadExecutor { Thread(it, TAG) }

    /** rule × talker × sender → replies sent; rule × talker → last reply time. In memory, like Hchat. */
    private val replyCounts = ConcurrentHashMap<String, Long>()
    private val lastReplyAt = ConcurrentHashMap<String, Long>()

    private val onMessage: (MessageInfo) -> Unit = { message ->
        if (!message.isSelfSender && !AutomationTalkerCategory.isSystemTalker(message.talker)) {
            val kind = AutomationMessageKind.classify(message.typeCode, message.actualContent)
            val isPat = message.typeCode == MessageType.PAT.code
            // System notices and recalls are never answered; pats are.
            if (kind != AutomationMessageKind.SYSTEM && message.typeCode != MessageType.RECALL.code) {
                val facts = runCatching { facts(message, isPat) }
                    .onFailure { WeLogger.e(TAG, "failed to read message ${message.id}", it) }.getOrNull()
                if (facts != null) executor.execute { handle(facts, message.id) }
            }
        }
    }

    override fun onEnable() {
        AutoReplyAutomation.store.snapshot()
        IncomingMessageEvents.subscribe(onMessage)
    }

    override fun onDisable() {
        IncomingMessageEvents.unsubscribe(onMessage)
    }

    override fun onClick(context: ComponentActivity) {
        AutomationSettingsActivity.start(context, AutoReplyAutomation)
    }

    private fun facts(message: MessageInfo, isPat: Boolean): AutoReplyMessage {
        val quote = message.typeCode == MessageType.QUOTE.code
        @Suppress("DEPRECATION")
        val text = message.typeCode == MessageType.TEXT.code
        return AutoReplyMessage(
            talker = message.talker,
            sender = message.sender,
            content = when {
                quote -> message.quoteMsgActualContent.orEmpty()
                isPat -> ""
                else -> message.actualContent
            }.trim(),
            isText = text || quote,
            isGroup = message.isInGroupChat,
            atMe = message.isAtMe,
            atAll = message.isNotifyAll,
            isPat = isPat,
            patMe = isPat && message.toPatMessage()!!.pattedUser == WeApi.selfWxId,
        )
    }

    private fun handle(message: AutoReplyMessage, msgId: Long) {
        if (message.isText && message.content.isBlank()) return
        val snapshot = AutoReplyAutomation.store.snapshot()
        if (!snapshot.resolve(null)[AutoReplyAutomation.chats].matches(message.talker, message.sender)) return
        snapshot.ofType(AutomationScopeType.ITEM).forEach { item ->
            val ruleId = item.scope.scopeId
            val rules = snapshot.effectiveRules(item.scope)
            if (!AutoReplyMatcher.matches(rules, message)) return@forEach

            val cooldownMs = rules[AutoReplyAutomation.cooldown].let { if (it.enabled) it.value * 1000L else 0L }
            val cooldownKey = "$ruleId|${message.talker}"
            val now = SystemClock.elapsedRealtime()
            if (cooldownMs > 0 && lastReplyAt[cooldownKey]?.let { now - it < cooldownMs } == true) return@forEach

            val maxCount = rules[AutoReplyAutomation.maxCount]
            if (maxCount.enabled) {
                val countKey = "$ruleId|${message.talker}|${message.sender}"
                val count = replyCounts[countKey] ?: 0L
                if (count >= maxCount.value) return@forEach
                replyCounts[countKey] = count + 1
            }

            WeLogger.i(TAG, "rule '${item.name}' matched in ${message.talker}")
            val reply = rules[AutoReplyAutomation.reply]
            val sent = AutomationReplyExecutor.run(
                reply.steps,
                AutomationReplyContext(message.talker, message.sender, message.content, msgId),
                reply.asQuote,
            )
            if (sent && cooldownMs > 0) lastReplyAt[cooldownKey] = SystemClock.elapsedRealtime()
        }
    }
}
