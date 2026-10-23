package dev.ujhhgtg.wekit.features.items.notifications

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.automation.AutomationSettingsActivity
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.sql.sqlMethod
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.api.ui.WeNotificationAvatarApi
import dev.ujhhgtg.wekit.features.automation.AutomationMessageKind
import dev.ujhhgtg.wekit.features.automation.AutomationRules
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.IncomingMessageEvents
import dev.ujhhgtg.wekit.features.automation.localizedAutomationString
import dev.ujhhgtg.wekit.features.items.chat.BlockAtAllNotifications
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.notifications.CustomNotifications.DEDUP_TTL_MS
import dev.ujhhgtg.wekit.ui.automation.MESSAGE_KIND_LABELS
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcess
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-chat notification rules (ported from Hchat's 自定义通知): do-not-disturb, mute windows,
 * @all/@me and sender filters, and WeKit-published notifications with their own sound, vibration,
 * mark-read / quick-reply actions and per-chat merging. Mutually exclusive with [NotificationsEvolved].
 */
object CustomNotifications : ClickableFeature(), IResolveDex {
    override val technicalId = "自定义通知"
    override val nameRes = R.string.feature_custom_notifications_name
    override val categoryIds = listOf(FeatureCategoryIds.NOTIFICATIONS)
    override val descriptionRes = R.string.feature_custom_notifications_description

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    private const val TAG = "CustomNotifications"
    private val spec = CustomNotificationsAutomation

    private const val MARK = "wekit_custom_notification"
    private const val EXTRA_TALKER = "wekit_custom_notification_talker"
    private const val EXTRA_UNREAD = "wekit_custom_notification_unread"
    private const val EXTRA_LINES = "wekit_custom_notification_lines"
    private const val EXTRA_TOKEN = "wekit_custom_notification_token"
    private const val EXTRA_NOTIFY_ID = "wekit_custom_notification_id"
    private const val EXTRA_MSG_ID = "wekit_custom_notification_msg_id"
    private const val EXTRA_SVR_ID = "wekit_custom_notification_svr_id"
    private const val EXTRA_QUOTE = "wekit_custom_notification_quote"
    private const val EXTRA_DONE_TOKEN = "wekit_custom_notification_done_token"
    private const val ACTION_REPLY = "${HostPackage.NAME}.ACTION_WEKIT_CUSTOM_NOTIFY_REPLY"
    private const val ACTION_MARK_READ = "${HostPackage.NAME}.ACTION_WEKIT_CUSTOM_NOTIFY_MARK_READ"
    private const val REMOTE_INPUT_KEY = "wekit_custom_notification_reply"
    private const val CHANNEL_PREFIX = "wekit_custom_notification_"
    private const val MAX_CHANNELS = 100
    private const val MAX_MERGED_LINES = 7

    /** How long the observed-message fallback waits for WeChat's own notification. */
    private const val NATIVE_WAIT_MS = 900L
    private const val NATIVE_FALLBACK_BEFORE_MS = 2_000L
    private const val NATIVE_FALLBACK_AFTER_MS = 5_000L
    private const val OWNERSHIP_TTL_MS = 60_000L
    private const val FACTS_TTL_MS = 30_000L
    private const val DEDUP_TTL_MS = 60_000L
    private const val QUICK_REPLY_CANCEL_DELAY_MS = 1_200L

    private object HostPackage {
        const val NAME = "com.tencent.mm"
    }

    // com.tencent.mm.booter.notification.NotificationItem#notify(Context): posts one built notification.
    private val methodNotificationItemNotify by dexMethod(
        sqlMethod {
            declaredClass = "com.tencent.mm.booter.notification.NotificationItem"
            usingEqStrings("error, notify but mNotification == null")
        },
    )

    // dealNotify(x, talker, content, msgType, tipsFlag, isRevoke): entry of both the system
    // notification and the in-app (foreground) sound for one message.
    private val methodDealNotify by dexMethod(
        sqlMethod {
            searchPackages("com.tencent.mm.booter.notification")
            paramCount = 6
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        },
    )

    private val methodNotifyForLightPush by dexMethod(
        sqlMethod {
            searchPackages("com.tencent.mm.booter.notification")
            returnType = "void"
            paramCount = 7
            usingEqStrings("LightPush [NO NOTIFICATION] Util.isNullOrNil(userName) || Util.isNullOrNil(nickName)")
        },
    )

    // Notification sound players (base and "fixed" subclass), play(soundUri, ?, ?).
    private val methodPlaySound by dexMethod(
        sqlMethod {
            usingEqStrings("MicroMsg.Notification.Tool.Sound", "playSound playHandler == null")
        },
    )
    private val methodPlaySoundFixed by dexMethod(
        sqlMethod {
            usingEqStrings("MicroMsg.Notification.Tool.SoundFixed", "playSound playHandler == null")
        },
    )

    /** What WeKit knows about a stored message, keyed for the notification hooks that follow. */
    private data class MessageFacts(
        val talker: String,
        val sender: String,
        val rawContent: String,
        val text: String,
        val kind: AutomationMessageKind,
        val msgId: Long,
        val svrId: Long,
        val atMe: Boolean,
        val atAll: Boolean,
        val createTime: Long,
        val recordedAt: Long = SystemClock.elapsedRealtime(),
    )

    private data class Payload(
        val talker: String,
        val title: String,
        val text: String,
        val unreadCount: Int,
        val whenMillis: Long,
        val msgId: Long = 0L,
        val svrId: Long = 0L,
        val contentIntent: PendingIntent? = null,
    )

    private val factsBySvrId = ConcurrentHashMap<Long, MessageFacts>()
    private val nativeHandled = ConcurrentHashMap<String, Long>()
    private val nativeNotifiedAt = ConcurrentHashMap<String, Long>()
    private val dedup = ConcurrentHashMap<String, Long>()
    private val soundBlocked = ThreadLocal<Boolean>()
    private val swallowNativePost = ThreadLocal<Boolean>()
    private val notifySeq = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, TAG) }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastManualSoundAt = 0L
    private var receiverRegistered = false

    override val declaredErrors
        get() = if (NotificationsEvolved.isEnabled) listOf(R.string.custom_notify_conflict) else emptyList()

    override fun onClick(context: ComponentActivity) {
        AutomationSettingsActivity.start(context, spec)
    }

    override fun onEnable() {
        spec.store.snapshot()

        WeMessageApi.methodMsgInfoStorageInsertMessage.hookAfter {
            recordFacts(MessageInfo(args[0]!!))
        }

        methodDealNotify.hookBefore(100) {
            val talker = args[1] as String
            val rules = rulesFor(talker) ?: return@hookBefore
            val facts = factsByContent(talker, args[2] as String)
            if (suppressed(rules, facts)) {
                WeLogger.i(TAG, "suppressing notification from $talker")
                result = null
                return@hookBefore
            }
            if (!rules[spec.sound].enabled) soundBlocked.set(true)
        }
        methodDealNotify.hookAfter { soundBlocked.remove() }
        listOf(methodPlaySound, methodPlaySoundFixed).forEach { method ->
            method.hookBefore { if (soundBlocked.get() == true) result = null }
        }

        methodNotifyForLightPush.hookBefore(100) {
            val talker = args[1] as String
            val rules = rulesFor(talker) ?: return@hookBefore
            val atUsers = (args[5] as Map<*, *>?)?.get(".msgsource.atuserlist") as String?
            val mentioned = atUsers.orEmpty().split(',').map(String::trim)
            val facts = MessageFacts(
                talker = talker, sender = "", rawContent = "", text = "", kind = AutomationMessageKind.UNKNOWN,
                msgId = 0L, svrId = 0L, atMe = WeApi.selfWxId in mentioned,
                atAll = "notify@all" in mentioned || "announcement@all" in mentioned, createTime = 0L,
            )
            if (suppressed(rules, facts)) {
                WeLogger.i(TAG, "suppressing LightPush notification from $talker")
                result = null
            }
        }

        // Decide in NotificationItem.notify but only swallow its NotificationManager.notify call, so
        // WeChat's bookkeeping after posting (the "already notified" msgId list) still runs.
        methodNotificationItemNotify.hookBefore { handleNativeNotification(this) }
        methodNotificationItemNotify.hookAfter { swallowNativePost.remove() }
        NotificationManager::class.reflekt().firstMethod {
            name = "notify"
            parameters(String::class.java, Int::class.javaPrimitiveType!!, Notification::class.java)
        }.hookBefore {
            if (swallowNativePost.get() == true) result = null
        }

        if (TargetProcesses.isInMain) IncomingMessageEvents.subscribe(onMessage)

        ContextCompat.registerReceiver(
            HostInfo.application, actionReceiver,
            IntentFilter().apply {
                addAction(ACTION_REPLY)
                addAction(ACTION_MARK_READ)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    override fun onDisable() {
        IncomingMessageEvents.unsubscribe(onMessage)
        factsBySvrId.clear()
        nativeHandled.clear()
        nativeNotifiedAt.clear()
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { HostInfo.application.unregisterReceiver(actionReceiver) }
            .onFailure { WeLogger.w(TAG, "failed to unregister action receiver", it) }
    }

    // ---- decisions ------------------------------------------------------------------------------

    /** Enabled rules of [talker]; null for system chats and where rules are off. */
    private fun rulesFor(talker: String): AutomationRules? {
        if (talker.isEmpty() || AutomationTalkerCategory.isSystemTalker(talker)) return null
        return spec.store.resolve(talker).takeIf { it[spec.enabled].enabled }
    }

    /** DND mode, the mute window, and (with message facts) the mention and sender filters. */
    private fun suppressed(rules: AutomationRules, facts: MessageFacts?): Boolean {
        if (rules[spec.mode] == NotifyMode.DND || rules[spec.muteWindow].isActive()) return true
        if (facts == null || AutomationTalkerCategory.of(facts.talker) != AutomationTalkerCategory.GROUP) return false
        if (facts.atAll && BlockAtAllNotifications.shouldSuppress(facts.talker)) return true
        if (rules[spec.mentions].blocks(facts.atAll, facts.atMe)) return true
        if (facts.sender.isEmpty()) return false
        val senderName = WeDatabaseApi.getGroupMemberDisplayName(facts.talker, facts.sender)
        return !rules[spec.senders].allows(facts.sender, senderName, facts.text)
    }

    private fun recordFacts(message: MessageInfo) {
        if (message.isSelfSender || message.serverId <= 0L) return
        val content = message.actualContent
        val kind = AutomationMessageKind.classify(message.typeCode, content)
        factsBySvrId[message.serverId] = MessageFacts(
            talker = message.talker,
            sender = message.sender,
            rawContent = message.content,
            text = content,
            kind = kind,
            msgId = message.id,
            svrId = message.serverId,
            atMe = message.isInGroupChat && message.isAtMe,
            atAll = message.isInGroupChat && message.isNotifyAll,
            createTime = message.createTime,
        )
        val now = SystemClock.elapsedRealtime()
        if (factsBySvrId.size > 128) factsBySvrId.values.removeIf { now - it.recordedAt > FACTS_TTL_MS }
    }

    private fun factsByContent(talker: String, rawContent: String): MessageFacts? =
        factsBySvrId.values.filter { it.talker == talker && it.rawContent == rawContent }.maxByOrNull { it.recordedAt }

    // ---- native notifications -------------------------------------------------------------------

    private val ITEM_REGEX = Regex("""msgId: (-?\d+),userName: (.*),unreadCount: (-?\d+)$""")

    private fun handleNativeNotification(param: dev.ujhhgtg.wekit.utils.HookParam) {
        val item = param.thisObject!!
        // NotificationItem.toString(): "id: %d,msgId: %d,userName: %s,unreadCount: %d"
        val match = ITEM_REGEX.find(item.toString()) ?: return
        val svrId = match.groupValues[1].toLong()
        val talker = match.groupValues[2]
        val unread = match.groupValues[3].toInt()
        if (talker.isEmpty() || talker == "null") return
        markNativeHandled(talker, svrId)
        // Without a message id this notification can't be matched to a message; leave it alone.
        if (svrId <= 0L) return

        val rules = rulesFor(talker) ?: return
        val facts = factsBySvrId[svrId]
        if (suppressed(rules, facts)) {
            WeLogger.i(TAG, "dropping native notification $svrId from $talker")
            swallowNativePost.set(true)
            return
        }
        if (!rules[spec.takeOver].enabled) return
        // Muted chats WeChat still notifies (e.g. @me) stay silent unless the rule ignores WeChat's mute.
        if (!rules[spec.ignoreWechatDnd].enabled && WeConversationApi.isDnd(talker)) {
            swallowNativePost.set(true)
            return
        }

        val notification = item.reflekt().firstField { type = Notification::class }.get() as Notification
        swallowNativePost.set(true)
        val extras = notification.extras
        val nativeText = listOf(Notification.EXTRA_BIG_TEXT, Notification.EXTRA_TEXT)
            .firstNotNullOfOrNull { extras.getCharSequence(it)?.toString()?.takeIf(String::isNotBlank) }
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()?.toString()
            ?: notification.tickerText?.toString().orEmpty()
        val payload = Payload(
            talker = talker,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf(String::isNotBlank)
                ?: WeDatabaseApi.getDisplayName(talker),
            text = if (rules[spec.showDetail].enabled) sanitize(nativeText.replace(UNREAD_PREFIX, "")).ifEmpty { newMessageText() }
            else newMessageText(),
            unreadCount = unread.takeIf { it > 0 } ?: activeCount(talker) + 1,
            whenMillis = notification.`when`,
            msgId = facts?.msgId ?: 0L,
            svrId = svrId,
            contentIntent = notification.contentIntent,
        )
        executor.execute { publishOnce(rules, payload) }
    }

    private fun markNativeHandled(talker: String, svrId: Long) {
        val now = SystemClock.elapsedRealtime()
        if (svrId > 0L) nativeHandled["$talker|$svrId"] = now else nativeNotifiedAt[talker] = now
        if (nativeHandled.size + nativeNotifiedAt.size > 128) {
            nativeHandled.values.removeIf { now - it > OWNERSHIP_TTL_MS }
            nativeNotifiedAt.values.removeIf { now - it > OWNERSHIP_TTL_MS }
        }
    }

    private fun isHandledByNative(talker: String, svrId: Long, observedAt: Long): Boolean {
        if (svrId > 0L && nativeHandled.containsKey("$talker|$svrId")) return true
        val at = nativeNotifiedAt[talker] ?: return false
        return at - observedAt in -NATIVE_FALLBACK_BEFORE_MS..NATIVE_FALLBACK_AFTER_MS
    }

    // ---- observed-message fallback --------------------------------------------------------------

    /**
     * Notifies messages WeChat itself stays silent about (muted chats with "ignore WeChat DND",
     * or anything WeChat skipped), once it's clear no native notification followed.
     */
    private val onMessage: (MessageInfo) -> Unit = handler@{ message ->
        if (message.isSelfSender) return@handler
        val talker = message.talker
        val code = message.typeCode
        if (code == MessageType.FRIEND_VERIFY.code || code == MessageType.SYSTEM.code || code == MessageType.SYSTEM_NOTICE.code ||
            code == MessageType.PAT.code || code == MessageType.RECALL.code
        ) return@handler
        val rules = rulesFor(talker) ?: return@handler
        if (!rules[spec.takeOver].enabled) return@handler
        val observedAt = SystemClock.elapsedRealtime()
        val svrId = message.serverId
        val content = message.actualContent
        val kind = AutomationMessageKind.classify(code, content)
        val sender = message.sender
        val msgId = message.id
        val createTime = message.createTime
        mainHandler.postDelayed({
            if (isHandledByNative(talker, svrId, observedAt) || isChatVisible(talker)) return@postDelayed
            executor.execute {
                if (isHandledByNative(talker, svrId, observedAt)) return@execute
                if (suppressed(rules, factsBySvrId[svrId])) return@execute
                if (!rules[spec.ignoreWechatDnd].enabled && WeConversationApi.isDnd(talker)) return@execute
                val group = AutomationTalkerCategory.of(talker) == AutomationTalkerCategory.GROUP
                val detail = if (!rules[spec.showDetail].enabled) newMessageText() else {
                    val text = detailText(kind, content)
                    val senderName = if (group) WeDatabaseApi.getGroupMemberDisplayName(talker, sender) else ""
                    if (senderName.isNotBlank()) "$senderName: $text" else text
                }
                publishOnce(
                    rules,
                    Payload(
                        talker = talker,
                        title = WeDatabaseApi.getDisplayName(talker),
                        text = detail,
                        unreadCount = activeCount(talker) + 1,
                        whenMillis = createTime,
                        msgId = msgId,
                        svrId = svrId,
                    ),
                )
            }
        }, NATIVE_WAIT_MS)
    }

    /** The open chat in a visible WeChat doesn't need a notification. Main thread. */
    private fun isChatVisible(talker: String): Boolean {
        if (WeCurrentConversationApi.value != talker || WeCurrentConversationApi.chatFooter?.isShown != true) return false
        val manager = HostInfo.application.getSystemService<ActivityManager>()!!
        val pid = android.os.Process.myPid()
        val importance = manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.importance ?: return true
        return importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun detailText(kind: AutomationMessageKind, content: String): String {
        fun label() = "[" + localizedAutomationString(MESSAGE_KIND_LABELS.first { it.first == kind }.second) + "]"
        return when (kind) {
            AutomationMessageKind.TEXT -> sanitize(content)
            AutomationMessageKind.QUOTE -> label() + " " + sanitize(AutomationMessageKind.textOf(kind, content))
            AutomationMessageKind.LINK, AutomationMessageKind.MUSIC, AutomationMessageKind.MINI_PROGRAM,
            AutomationMessageKind.CHANNELS_VIDEO, AutomationMessageKind.TRANSFER ->
                TITLE_REGEX.find(content)?.groupValues?.get(1)?.let { label() + " " + sanitize(it) } ?: label()

            else -> label()
        }.ifBlank { newMessageText() }
    }

    // ---- publishing -----------------------------------------------------------------------------

    private val UNREAD_PREFIX = Regex("^\\[\\d+\\S*?]\\s*")
    private val TITLE_REGEX = Regex("<title>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</title>", RegexOption.DOT_MATCHES_ALL)

    private fun newMessageText() = localizedAutomationString(R.string.custom_notify_new_message)

    private fun sanitize(value: String) = value.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()

    /** Publishes [payload] unless the same message was already published within [DEDUP_TTL_MS]. */
    private fun publishOnce(rules: AutomationRules, payload: Payload) {
        val key = when {
            payload.svrId > 0L -> "${payload.talker}|s${payload.svrId}"
            payload.msgId > 0L -> "${payload.talker}|m${payload.msgId}"
            else -> null
        }
        val now = SystemClock.elapsedRealtime()
        if (key != null) {
            val previous = dedup.put(key, now)
            if (previous != null && now - previous < DEDUP_TTL_MS) return
            if (dedup.size > 256) dedup.values.removeIf { now - it >= DEDUP_TTL_MS }
        }
        runCatching { publish(rules, payload) }.onFailure {
            if (key != null) dedup.remove(key, now)
            WeLogger.e(TAG, "failed to publish notification for ${payload.talker}", it)
        }
    }

    private fun publish(rules: AutomationRules, payload: Payload) {
        val context = HostInfo.application
        val manager = context.getSystemService<NotificationManager>()!!
        val sound = rules[spec.sound]
        val vibrate = rules[spec.vibrate].enabled
        val merge = rules[spec.merge].enabled
        val quickReply = rules[spec.quickReply]

        val channelId = CHANNEL_PREFIX + "s${if (sound.enabled) 1 else 0}_v${if (vibrate) 1 else 0}"
        trimOldChannels(manager)
        manager.createNotificationChannel(
            NotificationChannel(channelId, localizedAutomationString(R.string.feature_custom_notifications_name), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(vibrate)
                vibrationPattern = if (vibrate) longArrayOf(0, 250, 250, 250) else longArrayOf(0)
                // Played manually so each chat can use its own ringtone.
                setSound(null, null)
            }
        )

        val notificationId = if (merge) 0x4B000000 or (payload.talker.hashCode() and 0x00FFFFFF) else nextNotificationId(payload.talker)
        val token = System.nanoTime()
        val ours = ourNotifications(manager, payload.talker)
        val unread = if (merge) maxOf(payload.unreadCount, maxOf(ours.size, ours.maxOfOrNull { it.notification.extras.getInt(EXTRA_UNREAD, 1) } ?: 0) + 1)
        else payload.unreadCount
        val lines = if (merge) {
            (ours.sortedBy { it.postTime }.flatMap { historyLines(it.notification) } + payload.text)
                .map(String::trim).filter(String::isNotEmpty).takeLast(MAX_MERGED_LINES)
        } else emptyList()
        val displayText = if (unread > 1) localizedAutomationString(R.string.custom_notify_unread_prefix, unread) + payload.text else payload.text
        val whenMillis = when {
            payload.whenMillis <= 0L -> System.currentTimeMillis()
            payload.whenMillis < 100_000_000_000L -> payload.whenMillis * 1000L
            else -> payload.whenMillis
        }

        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_chat)
            .setContentTitle(payload.title)
            .setContentText(displayText)
            .setTicker(displayText)
            .setNumber(unread)
            .setWhen(whenMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(payload.contentIntent ?: chatIntent(context, payload.talker, notificationId))
            .setExtras(Bundle().apply {
                putBoolean(MARK, true)
                putString(EXTRA_TALKER, payload.talker)
                putInt(EXTRA_UNREAD, unread)
                putLong(EXTRA_TOKEN, token)
                if (merge) putCharSequenceArray(EXTRA_LINES, lines.toTypedArray())
            })
        if (merge && lines.size > 1) {
            builder.setStyle(
                Notification.InboxStyle()
                    .setBigContentTitle(payload.title)
                    .setSummaryText(localizedAutomationString(R.string.custom_notify_merged_summary, unread))
                    .also { style -> lines.forEach(style::addLine) }
            )
        }
        WeNotificationAvatarApi.loadBitmap(payload.talker)?.let { builder.setLargeIcon(it) }
        if (rules[spec.markRead].enabled) builder.addAction(markReadAction(context, payload.talker, notificationId))
        if (quickReply.enabled) {
            builder.addAction(replyAction(context, payload, notificationId, quickReply.asQuote, token))
        }
        manager.notify(notificationId, builder.build())
        if (merge) {
            ours.filter { it.id != notificationId || it.tag != null }.forEach { manager.cancel(it.tag, it.id) }
        }
        if (sound.enabled) playSound(context, sound.ringtone)
    }

    private fun ourNotifications(manager: NotificationManager, talker: String) =
        manager.activeNotifications.filter { status ->
            status.notification.extras.getBoolean(MARK, false) && status.notification.extras.getString(EXTRA_TALKER) == talker
        }

    private fun activeCount(talker: String): Int =
        ourNotifications(HostInfo.application.getSystemService<NotificationManager>()!!, talker).size

    private fun historyLines(notification: Notification): List<String> {
        val extras = notification.extras
        extras.getCharSequenceArray(EXTRA_LINES)?.map { it.toString() }?.takeIf { it.isNotEmpty() }?.let { return it }
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return listOf(text.replace(UNREAD_PREFIX, ""))
    }

    private fun nextNotificationId(talker: String): Int {
        val seq = notifySeq.updateAndGet { if (it >= 999_999) 1 else it + 1 }
        val raw = 0x4A000000L or (talker.hashCode().toLong() and 0x3FFL shl 20) or (seq.toLong() and 0xFFFFFL)
        return (raw and 0x7FFFFFFFL).toInt()
    }

    private fun trimOldChannels(manager: NotificationManager) {
        val ours = manager.notificationChannels.map { it.id }.filter { it.startsWith(CHANNEL_PREFIX) }
        if (ours.size > MAX_CHANNELS) ours.take(ours.size - MAX_CHANNELS).forEach(manager::deleteNotificationChannel)
    }

    private fun playSound(context: Context, ringtone: String) {
        val now = System.currentTimeMillis()
        if (now - lastManualSoundAt < 1200L) return
        lastManualSoundAt = now
        val uri = if (ringtone.isNotEmpty() && File(ringtone).isFile) Uri.fromFile(File(ringtone))
        else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        mainHandler.post {
            val player: Ringtone = RingtoneManager.getRingtone(context, uri) ?: return@post
            player.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            player.play()
            mainHandler.postDelayed({ if (player.isPlaying) player.stop() }, 3500L)
        }
    }

    /** LauncherUI, then the chat, so back returns to the conversation list. */
    private fun chatIntent(context: Context, talker: String, requestCode: Int): PendingIntent {
        val home = Intent().apply {
            component = ComponentName(context.packageName, "com.tencent.mm.ui.LauncherUI")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val chat = Intent().apply {
            component = ComponentName(context.packageName, "com.tencent.mm.ui.chatting.ChattingUI")
            putExtra("Chat_User", talker)
            putExtra("Chat_Mode", 1)
            putExtra("finish_direct", true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivities(
            context, requestCode, arrayOf(home, chat), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---- actions --------------------------------------------------------------------------------

    private fun markReadAction(context: Context, talker: String, notificationId: Int): Notification.Action {
        val intent = Intent(ACTION_MARK_READ).setPackage(context.packageName)
            .putExtra(EXTRA_TALKER, talker)
            .putExtra(EXTRA_NOTIFY_ID, notificationId)
        val pending = PendingIntent.getBroadcast(
            context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_view),
            localizedAutomationString(R.string.custom_notify_action_mark_read), pending,
        ).setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build()
    }

    private fun replyAction(context: Context, payload: Payload, notificationId: Int, asQuote: Boolean, token: Long): Notification.Action {
        val intent = Intent(ACTION_REPLY).setPackage(context.packageName)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(EXTRA_TALKER, payload.talker)
            .putExtra(EXTRA_NOTIFY_ID, notificationId)
            .putExtra(EXTRA_MSG_ID, payload.msgId)
            .putExtra(EXTRA_SVR_ID, payload.svrId)
            .putExtra(EXTRA_QUOTE, asQuote)
            .putExtra(EXTRA_TOKEN, token)
        val pending = PendingIntent.getBroadcast(
            context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(localizedAutomationString(R.string.custom_notify_reply_hint))
            .build()
        return Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_send),
            localizedAutomationString(R.string.custom_notify_action_reply), pending,
        ).addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val talker = intent.getStringExtra(EXTRA_TALKER) ?: return
            val notificationId = intent.getIntExtra(EXTRA_NOTIFY_ID, 0)
            when (intent.action) {
                ACTION_MARK_READ -> executor.execute {
                    markRead(talker)
                    cancelAll(talker, notificationId)
                }

                ACTION_REPLY -> {
                    val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_KEY)?.toString()?.trim()
                    if (reply.isNullOrEmpty()) return
                    val pending = goAsync()
                    executor.execute {
                        try {
                            val sent = runCatching {
                                sendReply(
                                    talker, reply, intent.getLongExtra(EXTRA_MSG_ID, 0L), intent.getLongExtra(EXTRA_SVR_ID, 0L),
                                    intent.getBooleanExtra(EXTRA_QUOTE, false),
                                )
                            }.onFailure { WeLogger.e(TAG, "quick reply to $talker failed", it) }.getOrDefault(false)
                            finishReply(notificationId, intent.getLongExtra(EXTRA_TOKEN, 0L), reply, sent)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }
    }

    /**
     * Hchat's full mark-read: clears the unread state, the red packet / group collection alerts of
     * the conversation, and marks its latest unread incoming voice message as played.
     */
    private fun markRead(talker: String) {
        WeConversationApi.markAsRead(talker)
        WeDatabaseApi.execStatement(
            "UPDATE rconversation SET hbMarkRed = 0, attrflag = attrflag & ~$GROUP_COLLECTION_ALERT_FLAG WHERE username = ?",
            arrayOf(talker),
        )
        WeDatabaseApi.rawQuery(
            "SELECT msgId, content FROM message WHERE talker = ? AND type = 34 AND isSend = 0 ORDER BY createTime DESC LIMIT 1",
            arrayOf(talker),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            val played = playedVoiceContent(cursor.getString(1).orEmpty()) ?: return
            WeDatabaseApi.execStatement("UPDATE message SET content = ? WHERE msgId = ?", arrayOf(played, cursor.getLong(0)))
        }
    }

    private const val GROUP_COLLECTION_ALERT_FLAG = 0x01000000

    private fun sendReply(talker: String, reply: String, msgId: Long, svrId: Long, asQuote: Boolean): Boolean {
        if (asQuote) {
            if (msgId > 0L && WeMessageApi.sendQuoteTextByMsgId(talker, msgId, reply)) return true
            if (svrId > 0L && WeMessageApi.sendQuoteText(talker, svrId, reply)) return true
        }
        return WeMessageApi.sendText(talker, reply)
    }

    /** Shows "replied: …" (or a failure) on the notification, then removes it after a successful send. */
    private fun finishReply(notificationId: Int, token: Long, reply: String, sent: Boolean) {
        val context = HostInfo.application
        val manager = context.getSystemService<NotificationManager>()!!
        val current = manager.activeNotifications.firstOrNull { it.tag == null && it.id == notificationId }?.notification
        if (current == null || current.extras.getLong(EXTRA_TOKEN, 0L) != token) {
            if (sent) manager.cancel(notificationId)
            return
        }
        val doneToken = System.nanoTime()
        val builder = Notification.Builder.recoverBuilder(context, current)
            .setOnlyAlertOnce(true)
            .setContentText(
                if (sent) localizedAutomationString(R.string.custom_notify_replied, reply)
                else localizedAutomationString(R.string.custom_notify_reply_failed)
            )
            .setExtras(Bundle(current.extras).apply { putLong(EXTRA_DONE_TOKEN, doneToken) })
        if (sent) builder.setRemoteInputHistory(arrayOf<CharSequence>(reply))
        manager.notify(notificationId, builder.build())
        if (!sent) return
        mainHandler.postDelayed({
            val unchanged = manager.activeNotifications
                .firstOrNull { it.tag == null && it.id == notificationId }
                ?.notification?.extras?.getLong(EXTRA_DONE_TOKEN) == doneToken
            if (unchanged) manager.cancel(notificationId)
        }, QUICK_REPLY_CANCEL_DELAY_MS)
    }

    private fun cancelAll(talker: String, fallbackId: Int) {
        val manager = HostInfo.application.getSystemService<NotificationManager>()!!
        manager.cancel(fallbackId)
        ourNotifications(manager, talker).forEach { manager.cancel(it.tag, it.id) }
    }
}
