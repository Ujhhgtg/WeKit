package dev.ujhhgtg.wekit.features.items.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getSystemService
import dev.ujhhgtg.wekit.utils.collections.LruCache
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import dev.ujhhgtg.wekit.utils.strings.replaceEmojis
import dev.ujhhgtg.wekit.utils.strings.replaceRichContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.isRegularFile
import kotlin.time.Duration.Companion.milliseconds

object NotificationsEvolved : ClickableFeature(), IResolveDex {

    override val technicalId = "通知进化"
    override val nameRes = R.string.feature_notifications_evolved_name
    override val categoryIds = listOf(FeatureCategoryIds.NOTIFICATIONS)
    override val descriptionRes = R.string.feature_notifications_evolved_description

    private const val TAG = "NotificationsEvolved"

    // com.tencent.mm.booter.notification.x.d(x, String talker, String content, int, int, boolean)
    // args[1] is the talker wxid. Anchored on a log string unique to that method.
    internal val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        }
    }

    // com.tencent.mm.storage.ConversationStorage#updateUnreadByTalker(talker) — same declaration
    // as ConversationAggregation.methodConversationStorageUpdateUnreadByTalker. WeChat calls it
    // whenever a conversation's unread state is cleared (chat opened / marked read elsewhere), so
    // the accumulated MessagingStyle history for that talker is stale and should be dropped.
    // WeChat's notification avatar loader. The declaring class is obfuscated and changes between
    // host versions, so it is resolved by the stable NotificationAvatar implementation markers.
    private val methodLoadNotificationAvatar by dexMethod {
        matcher {
            paramTypes(
                "android.content.Context",
                "java.lang.String",
                "java.lang.String",
            )
            returnType("android.graphics.Bitmap")
            usingEqStrings("MicroMsg.NotificationAvatar", "wcf://avatar/")
        }
    }

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_PUSH

    private enum class ImageNotificationMode {
        DISABLED,
        WAIT_THUMBNAIL,
        WAIT_LARGE,
    }

    private enum class StickerNotificationMode {
        LOADED_ONLY,
        WAIT_FOR_LOAD,
    }

    private var imageNotificationModeName by prefOption(
        "notifications_evolved_image_mode",
        ImageNotificationMode.WAIT_THUMBNAIL.name,
    )
    private var imageNotificationMode: ImageNotificationMode
        get() = runCatching { ImageNotificationMode.valueOf(imageNotificationModeName) }
            .getOrDefault(ImageNotificationMode.WAIT_THUMBNAIL)
        set(value) {
            imageNotificationModeName = value.name
        }

    private var stickerNotificationModeName by prefOption(
        "notifications_evolved_sticker_mode",
        StickerNotificationMode.WAIT_FOR_LOAD.name,
    )
    private var stickerNotificationMode: StickerNotificationMode
        get() = runCatching { StickerNotificationMode.valueOf(stickerNotificationModeName) }
            .getOrDefault(StickerNotificationMode.WAIT_FOR_LOAD)
        set(value) {
            stickerNotificationModeName = value.name
        }

    private var clearHistoryAfterQuickReply by prefOption(
        "notifications_evolved_clear_history_after_quick_reply",
        true,
    )

    private data class MediaAttachment(
        val mimeType: String,
        val uri: Uri,
        val cachePath: Path,
    )

    private data class PendingMediaTask(
        val deadlineElapsedRealtime: Long,
        val awaitCompletion: Boolean,
        val startOnAwait: Boolean,
        val deferred: Deferred<MediaAttachment?>,
    )

    private data class PendingMessage(
        val talker: String,
        val senderWxId: String,
        val rawContent: String,
        val text: String,
        val timestamp: Long,
        val messageId: Long,
        val capturedAt: Long,
        val mediaTask: PendingMediaTask?,
    )

    private data class HistoryEntry(
        val senderWxId: String?,
        val senderName: String?,
        val text: String,
        val timestamp: Long,
        val messageId: Long?,
        val media: MediaAttachment?,
    )

    private data class NotificationContext(
        val talker: String,
        val rawContent: String,
    )

    private data class CachedAvatar(val icon: Icon, val expiresAt: Long)

    private val stateLock = Any()
    private val pendingMessages = HashMap<String, ArrayDeque<PendingMessage>>()
    private val avatarLock = Any()
    private val avatarCache = LruCache<String, CachedAvatar>(maxLimit = 64)
    private val notificationContext = ThreadLocal<NotificationContext?>()
    private lateinit var mediaScope: CoroutineScope
    private val notificationMediaDir by lazy {
        (KnownPaths.moduleCache / "notification-media").createDirsSafe()
    }
    private val notificationAvatarLoader by lazy {
        methodLoadNotificationAvatar.method.declaringClass.reflekt()
            .firstConstructor { parameters(Context::class) }
            .newInstance(HostInfo.application)
    }

    // Per-conversation message history rebuilt into MessagingStyle on each notification update.
    // Cleared when WeChat clears the conversation's unread state (updateUnreadByTalker) or when
    // the user marks it read; quick replies are re-added so the exchange stays visible. Bounded
    // to avoid unbounded growth.
    private val messageHistory = LinkedHashMap<String, ArrayDeque<HistoryEntry>>()
    private const val MAX_HISTORY = 7

    private const val ACTION_REPLY = "${PackageNames.WECHAT}.ACTION_WEKIT_REPLY"
    private const val ACTION_MARK_READ = "${PackageNames.WECHAT}.ACTION_WEKIT_MARK_READ"
    private const val ACTION_NOTIFICATION_OPENED = "${PackageNames.WECHAT}.ACTION_WEKIT_NOTIFICATION_OPENED"
    private const val ACTION_NOTIFICATION_DISMISSED = "${PackageNames.WECHAT}.ACTION_WEKIT_NOTIFICATION_DISMISSED"
    private const val MAX_PENDING_MESSAGES = 16
    private const val PENDING_MESSAGE_TTL_MILLIS = 30_000L
    private const val AVATAR_CACHE_TTL_MILLIS = 5 * 60 * 1000L
    private const val SHORT_MEDIA_WAIT_MILLIS = 1_500L
    private const val STICKER_MEDIA_WAIT_MILLIS = 3_000L
    private const val LARGE_IMAGE_WAIT_MILLIS = 5_000L
    private const val MEDIA_CACHE_MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000L

    // WeChat's original contentIntent per convWxId, stored so we can fire it after clearing history.
    private val pendingContentIntents = HashMap<String, PendingIntent>()
    private var receiverRegistered = false

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val targetWxId = intent.getStringExtra("extra_target_wxid") ?: return
            val notificationManager =
                context.getSystemService<NotificationManager>()

            when (intent.action) {
                ACTION_REPLY -> {
                    val results = RemoteInput.getResultsFromIntent(intent) ?: return
                    val replyContent = results.getCharSequence("key_reply_content")?.toString()

                    if (replyContent.isNullOrEmpty())
                        return

                    val preservedHistory = if (clearHistoryAfterQuickReply) {
                        emptyList()
                    } else {
                        synchronized(stateLock) {
                            messageHistory[targetWxId]?.toList().orEmpty()
                        }
                    }
                    WeLogger.i(TAG, "quick replying '$replyContent' to $targetWxId")
                    WeMessageApi.sendText(targetWxId, replyContent)
                    WeConversationApi.markAsRead(targetWxId)
                    notificationManager.cancel(targetWxId.hashCode())

                    // markAsRead clears the accumulated history through the hook below. Restore it
                    // only when configured, then append the quick reply in both modes.
                    appendHistory(targetWxId, preservedHistory)
                    appendHistory(
                        targetWxId,
                        HistoryEntry(
                            senderWxId = WeApi.selfWxId,
                            senderName = null,
                            text = replyContent,
                            timestamp = System.currentTimeMillis(),
                            messageId = null,
                            media = null,
                        ),
                    )
                }

                ACTION_MARK_READ -> {
                    WeLogger.i(TAG, "marking chat as read for $targetWxId")
                    WeConversationApi.markAsRead(targetWxId)
                    clearConversationState(targetWxId)
                    removeContentIntent(targetWxId)
                    notificationManager.cancel(targetWxId.hashCode())
                }

                ACTION_NOTIFICATION_OPENED -> {
                    // Notification was tapped — clear history, then hand off to WeChat's own intent.
                    clearConversationState(targetWxId)
                    val contentIntent = removeContentIntent(targetWxId) ?: return
                    try {
                        contentIntent.send()
                    } catch (e: PendingIntent.CanceledException) {
                        WeLogger.w(TAG, "original content intent was canceled for $targetWxId", e)
                    }
                }

                ACTION_NOTIFICATION_DISMISSED -> {
                    // Notification was swiped away — just clear history.
                    clearConversationState(targetWxId)
                    removeContentIntent(targetWxId)
                }
            }
        }
    }

    private val MESSAGE_REGEX = Regex("""^(\[\d+条])?(.+?)?: (.*)$""", RegexOption.DOT_MATCHES_ALL)

    private fun normalizeText(text: String): String = text
        .replaceRichContent()
        .replaceEmojis()

    private fun normalizeTimestamp(timestamp: Long): Long {
        if (timestamp <= 0L) return System.currentTimeMillis()
        return if (timestamp < 100_000_000_000L) timestamp * 1000L else timestamp
    }

    private fun cleanupNotificationMediaCache() {
        val cutoff = System.currentTimeMillis() - MEDIA_CACHE_MAX_AGE_MILLIS
        runCatching {
            Files.list(notificationMediaDir).use { paths ->
                paths.filter { path ->
                    path.isRegularFile() && Files.getLastModifiedTime(path).toMillis() < cutoff
                }.forEach(Path::deleteIfExists)
            }
        }.onFailure { WeLogger.w(TAG, "failed to clean notification media cache", it) }
    }

    private fun toMediaAttachment(media: WeMessageApi.NotificationMediaFile): MediaAttachment {
        val uri = FileProvider.getUriForFile(
            HostInfo.application,
            "${PackageNames.WECHAT}.external.fileprovider",
            media.path.toFile(),
        )
        return MediaAttachment(media.mimeType, uri, media.path)
    }

    private fun launchMediaTask(
        waitMillis: Long,
        awaitCompletion: Boolean,
        startImmediately: Boolean = true,
        prepare: (Long) -> WeMessageApi.NotificationMediaFile?,
    ): PendingMediaTask {
        val deadline = SystemClock.elapsedRealtime() + waitMillis
        val deferred = mediaScope.async(
            start = if (startImmediately) CoroutineStart.DEFAULT else CoroutineStart.LAZY,
        ) {
            runCatching {
                val media = prepare(deadline) ?: return@runCatching null
                if (SystemClock.elapsedRealtime() >= deadline) null else toMediaAttachment(media)
            }
                .onFailure { WeLogger.w(TAG, "failed to prepare notification media", it) }
                .getOrNull()
        }
        return PendingMediaTask(deadline, awaitCompletion, !startImmediately, deferred)
    }

    private fun createMediaTask(message: MessageInfo): PendingMediaTask? {
        return when {
            message.typeCode == MessageType.IMAGE.code -> when (imageNotificationMode) {
                ImageNotificationMode.DISABLED -> null
                ImageNotificationMode.WAIT_THUMBNAIL -> launchMediaTask(
                    waitMillis = SHORT_MEDIA_WAIT_MILLIS,
                    awaitCompletion = true,
                ) { deadline ->
                    WeMessageApi.materializeNotificationThumbnail(
                        message,
                        notificationMediaDir / "image-thumb-v2-${message.serverId}.media",
                        deadline,
                    )
                }

                ImageNotificationMode.WAIT_LARGE -> launchMediaTask(
                    waitMillis = LARGE_IMAGE_WAIT_MILLIS,
                    awaitCompletion = true,
                    startImmediately = false,
                ) { deadline ->
                    WeMessageApi.materializeNotificationLargeImage(
                        message.serverId,
                        notificationMediaDir / "image-large-${message.serverId}.media",
                        deadline,
                    )
                }
            }

            message.type?.isSticker == true -> {
                val md5 = message.stickerMd5
                    ?.lowercase()
                    ?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
                    ?: return null
                val wait = stickerNotificationMode == StickerNotificationMode.WAIT_FOR_LOAD
                launchMediaTask(
                    waitMillis = STICKER_MEDIA_WAIT_MILLIS,
                    awaitCompletion = true,
                ) { deadline ->
                    WeMessageApi.materializeNotificationSticker(
                        md5,
                        notificationMediaDir / "sticker-$md5.media",
                        deadline,
                        wait,
                    )
                }
            }

            else -> null
        }
    }

    private fun awaitMedia(task: PendingMediaTask?): MediaAttachment? {
        if (task == null) return null
        val remaining = task.deadlineElapsedRealtime - SystemClock.elapsedRealtime()
        if (task.startOnAwait && remaining <= 0L) {
            task.deferred.cancel()
            return null
        }
        if (task.startOnAwait) task.deferred.start()
        if (!task.awaitCompletion && !task.deferred.isCompleted) return null

        val media = runCatching {
            runBlocking {
                if (task.deferred.isCompleted) {
                    task.deferred.await()
                } else {
                    if (remaining <= 0L) null
                    else withTimeoutOrNull(remaining.milliseconds) { task.deferred.await() }
                }
            }
        }.onFailure { WeLogger.w(TAG, "failed while awaiting notification media", it) }
            .getOrNull()
        if (!task.deferred.isCompleted) task.deferred.cancel()
        return media
    }

    private fun enqueueMessage(message: MessageInfo, insertedMessageId: Long) {
        if (message.isSelfSender) return

        val senderWxId = message.sender
        if (senderWxId.isEmpty()) return

        val now = System.currentTimeMillis()
        val pending = PendingMessage(
            talker = message.talker,
            senderWxId = senderWxId,
            rawContent = message.content,
            text = normalizeText(message.humanReadableRepr),
            timestamp = normalizeTimestamp(message.createTime),
            messageId = insertedMessageId.takeIf { it > 0L } ?: message.id,
            capturedAt = now,
            mediaTask = createMediaTask(message),
        )

        synchronized(stateLock) {
            val queue = pendingMessages.getOrPut(pending.talker) { ArrayDeque() }
            while (queue.firstOrNull()?.let { now - it.capturedAt > PENDING_MESSAGE_TTL_MILLIS } == true) {
                queue.removeFirst().mediaTask?.deferred?.cancel()
            }
            if (pending.messageId != 0L && queue.any { it.messageId == pending.messageId }) return
            queue.addLast(pending)
            while (queue.size > MAX_PENDING_MESSAGES) {
                queue.removeFirst().mediaTask?.deferred?.cancel()
            }
        }
    }

    private fun consumePendingMessage(context: NotificationContext): PendingMessage? {
        val now = System.currentTimeMillis()
        return synchronized(stateLock) {
            val queue = pendingMessages[context.talker] ?: return@synchronized null
            while (queue.firstOrNull()?.let { now - it.capturedAt > PENDING_MESSAGE_TTL_MILLIS } == true) {
                queue.removeFirst().mediaTask?.deferred?.cancel()
            }
            if (queue.isEmpty()) {
                pendingMessages.remove(context.talker)
                return@synchronized null
            }

            val entries = queue.toList()
            val matchIndex = entries.indexOfFirst { it.rawContent == context.rawContent }
            if (matchIndex < 0) return@synchronized null

            val matched = entries[matchIndex]
            queue.clear()
            queue.addAll(entries.filterIndexed { index, _ -> index != matchIndex })
            if (queue.isEmpty()) pendingMessages.remove(context.talker)

            matched
        }
    }

    private fun discardPendingMessage(context: NotificationContext) {
        val discarded = synchronized(stateLock) {
            val queue = pendingMessages[context.talker] ?: return
            val index = queue.indexOfFirst { it.rawContent == context.rawContent }
            var removed: PendingMessage? = null
            if (index >= 0) {
                val entries = queue.toList()
                removed = entries[index]
                queue.clear()
                queue.addAll(entries.filterIndexed { entryIndex, _ -> entryIndex != index })
            }
            if (queue.isEmpty()) pendingMessages.remove(context.talker)
            removed
        }
        discarded?.mediaTask?.deferred?.cancel()
    }

    private fun PendingMessage.toHistoryEntry(): HistoryEntry = HistoryEntry(
        senderWxId = senderWxId,
        senderName = null,
        text = text,
        timestamp = timestamp,
        messageId = messageId.takeIf { it != 0L },
        media = awaitMedia(mediaTask),
    )

    private fun appendHistory(talker: String, vararg entries: HistoryEntry) {
        synchronized(stateLock) {
            val history = messageHistory.getOrPut(talker) { ArrayDeque() }
            entries.forEach { entry ->
                if (entry.messageId != null && history.any { it.messageId == entry.messageId }) {
                    return@forEach
                }
                history.addLast(entry)
            }
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
    }

    private fun appendHistory(talker: String, entries: List<HistoryEntry>) {
        appendHistory(talker, *entries.toTypedArray())
    }

    private fun clearConversationState(talker: String) {
        val pending = synchronized(stateLock) {
            val removed = pendingMessages.remove(talker)
            messageHistory.remove(talker)
            removed
        }
        pending?.forEach { it.mediaTask?.deferred?.cancel() }
    }

    private fun setContentIntent(talker: String, intent: PendingIntent) {
        synchronized(stateLock) { pendingContentIntents[talker] = intent }
    }

    private fun removeContentIntent(talker: String): PendingIntent? {
        return synchronized(stateLock) { pendingContentIntents.remove(talker) }
    }

    private fun clearState() {
        synchronized(stateLock) {
            pendingMessages.clear()
            messageHistory.clear()
            pendingContentIntents.clear()
        }
        notificationContext.remove()
        synchronized(avatarLock) { avatarCache.clear() }
    }

    private fun loadAvatarIcon(wxId: String): Icon? {
        if (wxId.isEmpty() || wxId == "system") return null

        val now = System.currentTimeMillis()
        synchronized(avatarLock) {
            avatarCache[wxId]?.let { cached ->
                if (cached.expiresAt > now) return cached.icon
                avatarCache.remove(wxId)
            }
        }

        val icon = runCatching {
            val bitmap = methodLoadNotificationAvatar.method.invoke(
                notificationAvatarLoader,
                HostInfo.application,
                wxId,
                "",
            ) as? Bitmap? ?: return@runCatching null
            Icon.createWithBitmap(bitmap)
        }.onFailure {
            WeLogger.w(TAG, "failed to load notification avatar for $wxId", it)
        }.getOrNull() ?: return null

        synchronized(avatarLock) {
            avatarCache[wxId] = CachedAvatar(icon, now + AVATAR_CACHE_TTL_MILLIS)
        }
        return icon
    }

    private fun resolveDisplayName(
        talker: String,
        entry: HistoryEntry,
        conversationTitle: String,
        selfName: String,
        fallbackGroupSenderWxId: String?,
        fallbackGroupSenderName: String?,
    ): String {
        val senderWxId = entry.senderWxId ?: return entry.senderName ?: conversationTitle
        if (senderWxId == WeApi.selfWxId) return selfName
        if (!talker.isGroupChatWxId) return conversationTitle
        if (senderWxId == fallbackGroupSenderWxId && !fallbackGroupSenderName.isNullOrEmpty()) {
            return fallbackGroupSenderName
        }
        if (!entry.senderName.isNullOrEmpty()) return entry.senderName

        return runCatching {
            WeDatabaseApi.getGroupMemberDisplayName(talker, senderWxId)
        }.getOrDefault("").ifEmpty {
            runCatching { WeDatabaseApi.getDisplayName(senderWxId) }
                .getOrDefault(senderWxId)
        }.ifEmpty { senderWxId }
    }

    private fun buildPerson(name: String, wxId: String?): Person {
        return Person.Builder().setName(name).apply {
            if (!wxId.isNullOrEmpty()) {
                setKey(wxId)
                loadAvatarIcon(wxId)?.let(::setIcon)
            }
        }.build()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var imageMode by remember { mutableStateOf(imageNotificationMode) }
            var stickerMode by remember { mutableStateOf(stickerNotificationMode) }
            var clearHistory by remember { mutableStateOf(clearHistoryAfterQuickReply) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.notifications_settings_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = "image_mode") {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.notifications_image_behavior),
                                description = null,
                                value = imageMode,
                                options = listOf(
                                    DropdownOption(
                                        ImageNotificationMode.DISABLED,
                                        stringResource(R.string.notifications_image_disabled),
                                    ),
                                    DropdownOption(
                                        ImageNotificationMode.WAIT_THUMBNAIL,
                                        stringResource(R.string.notifications_image_wait_thumbnail),
                                    ),
                                    DropdownOption(
                                        ImageNotificationMode.WAIT_LARGE,
                                        stringResource(R.string.notifications_image_wait_large),
                                    ),
                                ),
                                onValueChange = {
                                    imageMode = it
                                    imageNotificationMode = it
                                },
                            )
                        }
                        item(key = "sticker_mode") {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.notifications_sticker_behavior),
                                description = null,
                                value = stickerMode,
                                options = listOf(
                                    DropdownOption(
                                        StickerNotificationMode.LOADED_ONLY,
                                        stringResource(R.string.notifications_sticker_loaded_only),
                                    ),
                                    DropdownOption(
                                        StickerNotificationMode.WAIT_FOR_LOAD,
                                        stringResource(R.string.notifications_sticker_wait_load),
                                    ),
                                ),
                                onValueChange = {
                                    stickerMode = it
                                    stickerNotificationMode = it
                                },
                            )
                        }
                        item(key = "clear_history_after_quick_reply") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(
                                    R.string.notifications_clear_history_after_quick_reply,
                                ),
                                checked = clearHistory,
                                onCheckedChange = {
                                    clearHistory = it
                                    clearHistoryAfterQuickReply = it
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

    override fun onEnable() {
        clearState()
        mediaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        mediaScope.launch { cleanupNotificationMediaCache() }

        WeMessageApi.methodMsgInfoStorageInsertMessage.hookAfter {
            val insertedMessageId = result as Long
            if (insertedMessageId < 0L) return@hookAfter
            enqueueMessage(MessageInfo(args[0]!!), insertedMessageId)
        }

        // x.d -> m0.a -> e0.b -> Notification.Builder.build() all run synchronously on this
        // thread, so the raw dealNotify content identifies the message consumed by build().
        methodDealNotify.hookBefore {
            notificationContext.set(
                NotificationContext(
                    talker = args[1] as String,
                    rawContent = args[2] as String,
                )
            )
        }
        methodDealNotify.hookAfter {
            notificationContext.get()?.let(::discardPendingMessage)
            notificationContext.remove()
        }

        // WeChat calls ConversationStorage.updateUnreadByTalker(talker) when a conversation's
        // unread state is cleared outside our receiver (chat opened, read elsewhere, ...). Drop
        // that talker's accumulated history so the next notification doesn't replay stale messages.
        WeConversationApi.methodUpdateUnreadByTalker.hookBefore {
            clearConversationState(args[0] as String)
        }

        Notification.Builder::class.reflekt()
            .firstMethod { name = "build" }
            .hookBefore {
                val context = HostInfo.application
                val notifyContext = notificationContext.get() ?: return@hookBefore

                val builder = thisObject as Notification.Builder
                val notif = builder.reflekt().firstField { type = Notification::class }
                    .get() as Notification
                val channelId = notif.channelId

                if (channelId != "message_channel_new_id") {
                    return@hookBefore
                }
                notificationContext.remove()

                val notifTitle = notif.extras.getString(Notification.EXTRA_TITLE)
                    ?: localizedNotificationString(R.string.notifications_unknown_conversation)
                val notifText =
                    notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        ?: localizedNotificationString(R.string.notifications_unknown_content)

                val match = MESSAGE_REGEX.find(notifText)
                val fallbackGroupSenderName = match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
                val fallbackSenderName = if (notifyContext.talker.isGroupChatWxId) {
                    fallbackGroupSenderName ?: notifTitle
                } else {
                    notifTitle
                }
                val fallbackText = if (match == null) {
                    WeLogger.w(
                        TAG,
                        "failed to match message regex, using raw sender name & text content"
                    )
                    notifText
                } else {
                    match.groupValues[3]
                }

                val convWxId = notifyContext.talker
                val capturedMessage = consumePendingMessage(notifyContext)
                val capturedEntries = capturedMessage?.let { listOf(it.toHistoryEntry()) }.orEmpty()
                val entries = capturedEntries.ifEmpty {
                    listOf(
                        HistoryEntry(
                            senderWxId = convWxId.takeUnless { it.isGroupChatWxId },
                            senderName = fallbackSenderName,
                            text = normalizeText(fallbackText),
                            timestamp = System.currentTimeMillis(),
                            messageId = null,
                            media = null,
                        )
                    )
                }
                appendHistory(convWxId, entries)

                WeLogger.i(TAG, "enhancing notification for $notifTitle ($convWxId)")

                val selfName = localizedNotificationString(R.string.notifications_self)
                val selfPerson = buildPerson(selfName, WeApi.selfWxId)
                val messagingStyle = Notification.MessagingStyle(selfPerson)

                if (convWxId.isGroupChatWxId) {
                    messagingStyle.isGroupConversation = true
                    messagingStyle.conversationTitle = notifTitle
                }

                val history = synchronized(stateLock) {
                    messageHistory[convWxId]?.toList().orEmpty()
                }
                val currentSenderWxId = capturedMessage?.senderWxId

                for (entry in history) {
                    val person = if (entry.senderWxId == WeApi.selfWxId) {
                        selfPerson
                    } else {
                        buildPerson(
                            resolveDisplayName(
                                talker = convWxId,
                                entry = entry,
                                conversationTitle = notifTitle,
                                selfName = selfName,
                                fallbackGroupSenderWxId = currentSenderWxId,
                                fallbackGroupSenderName = fallbackGroupSenderName,
                            ),
                            entry.senderWxId,
                        )
                    }
                    val message = Notification.MessagingStyle.Message(
                        entry.text,
                        entry.timestamp,
                        person,
                    )
                    entry.media?.let { media -> message.setData(media.mimeType, media.uri) }
                    messagingStyle.addMessage(message)
                }

                builder.style = messagingStyle
                val conversationIcon = loadAvatarIcon(convWxId)
                if (conversationIcon != null) {
                    builder.setLargeIcon(conversationIcon)
                } else if (!convWxId.isGroupChatWxId) {
                    builder.setLargeIcon(null as Icon?)
                }

                // 2.5. Wrap WeChat's contentIntent so tapping the notification clears
                //      history before handing off to WeChat's own chat-open flow.
                //      Also attach a deleteIntent to catch swipe-dismiss.
                val originalContentIntent = notif.contentIntent
                if (originalContentIntent != null) {
                    setContentIntent(convWxId, originalContentIntent)
                    val openIntent = Intent(ACTION_NOTIFICATION_OPENED).apply {
                        setPackage(PackageNames.WECHAT)
                        putExtra("extra_target_wxid", convWxId)
                    }
                    builder.setContentIntent(
                        PendingIntent.getBroadcast(
                            context, convWxId.hashCode(), openIntent,
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
                val dismissIntent = Intent(ACTION_NOTIFICATION_DISMISSED).apply {
                    setPackage(PackageNames.WECHAT)
                    putExtra("extra_target_wxid", convWxId)
                }
                builder.setDeleteIntent(
                    PendingIntent.getBroadcast(
                        context, convWxId.hashCode(), dismissIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )

                // 3. Quick Reply Action
                val remoteInput = RemoteInput.Builder("key_reply_content")
                    .setLabel(localizedNotificationString(R.string.notifications_reply_hint))
                    .build()

                val replyIntent = Intent(ACTION_REPLY).apply {
                    setPackage(PackageNames.WECHAT)
                    putExtra("extra_target_wxid", convWxId)
                }
                val replyPendingIntent = PendingIntent.getBroadcast(
                    context, convWxId.hashCode(), replyIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val replyAction = Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_send),
                    localizedNotificationString(R.string.notifications_action_reply), replyPendingIntent
                ).addRemoteInput(remoteInput).build()

                // 4. Mark as Read Action
                val readIntent = Intent(ACTION_MARK_READ).apply {
                    setPackage(PackageNames.WECHAT)
                    putExtra("extra_target_wxid", convWxId)
                }
                val readPendingIntent = PendingIntent.getBroadcast(
                    context, convWxId.hashCode(), readIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val readAction = Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_view),
                    localizedNotificationString(R.string.notifications_action_mark_read), readPendingIntent
                ).build()

                // Apply actions directly to the builder
                builder.addAction(replyAction)
                builder.addAction(readAction)
            }

        val filter = IntentFilter().apply {
            addAction(ACTION_REPLY)
            addAction(ACTION_MARK_READ)
            addAction(ACTION_NOTIFICATION_OPENED)
            addAction(ACTION_NOTIFICATION_DISMISSED)
        }
        ContextCompat.registerReceiver(
            HostInfo.application, notificationReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    override fun onDisable() {
        if (::mediaScope.isInitialized) mediaScope.cancel()
        clearState()
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { HostInfo.application.unregisterReceiver(notificationReceiver) }
            .onFailure { WeLogger.w(TAG, "failed to unregister notification receiver", it) }
    }
}
