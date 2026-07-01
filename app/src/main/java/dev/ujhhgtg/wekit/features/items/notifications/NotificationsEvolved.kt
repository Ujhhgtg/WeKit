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
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getSystemService
import dev.ujhhgtg.wekit.utils.collections.LruCache
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import dev.ujhhgtg.wekit.utils.strings.replaceEmojis
import dev.ujhhgtg.wekit.utils.strings.replaceRichContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.milliseconds

@Feature(name = "通知进化", categories = ["通知"], description = "让微信的新消息通知更易用\n1. 「快速回复」按钮\n2. 「标记为已读」按钮\n3. 使用原生对话样式 (MessagingStyle)")
object NotificationsEvolved : SwitchFeature(), IResolveDex {

    private val TAG = nameOf(NotificationsEvolved)

    // com.tencent.mm.booter.notification.x.d(x, String talker, String content, int, int, boolean)
    // args[1] is the talker wxid. Anchored on a log string unique to that method.
    private val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        }
    }

    // talker wxid captured from x.d, read back in the synchronous Notification.Builder.build() hook
    private val currentTalker = ThreadLocal<String?>()

    override fun startup() {
        if (!TargetProcesses.isInMain && TargetProcesses.currentType != TargetProcesses.PROC_PUSH) return
        _isEnabled = WePrefs.getBoolOrFalse(name)
        if (_isEnabled) enable()
    }

    private val lastGroupChatSender = LruCache<String, String>()

    private const val ACTION_REPLY = "${PackageNames.WECHAT}.ACTION_WEKIT_REPLY"
    private const val ACTION_MARK_READ =
        "${PackageNames.WECHAT}.ACTION_WEKIT_MARK_READ"

    private lateinit var meAvatarIcon: Icon

    private val meAvatarPath by lazy { KnownPaths.moduleData / "me_avatar" }

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

                    WeLogger.i(TAG, "quick replying '$replyContent' to $targetWxId")
                    WeMessageApi.sendText(targetWxId, replyContent)
                    WeConversationApi.markAsRead(targetWxId)
                    notificationManager.cancel(targetWxId.hashCode())
                }

                ACTION_MARK_READ -> {
                    WeLogger.i(TAG, "marking chat as read for $targetWxId")
                    WeConversationApi.markAsRead(targetWxId)
                    notificationManager.cancel(targetWxId.hashCode())
                }
            }
        }
    }

    private val MESSAGE_REGEX = Regex("""^(\[\d+条])?(.+?)?: (.*)$""", RegexOption.DOT_MATCHES_ALL)

    override fun onEnable() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val bitmap: Bitmap
                if (meAvatarPath.exists()) {
                    bitmap = BitmapFactory.decodeFile(meAvatarPath.pathString)
                } else {
                    while (runCatching { WeApi.selfWxId.isEmpty() }
                            .getOrDefault(true)) {
                        delay(2000.milliseconds)
                    }

                    val urlString = WeDatabaseApi.getAvatarUrl(WeApi.selfWxId)
                    val connection = URL(urlString).openConnection()
                            as HttpURLConnection
                    connection.doInput = true

                    connection.inputStream.use { input ->
                        val bytes = input.readBytes()
                        meAvatarPath.writeBytes(bytes)
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                return@runCatching Icon.createWithBitmap(bitmap)
            }.onFailure { e ->
                WeLogger.e(TAG, "failed to fetch me avatar", e)
            }.onSuccess { meAvatarIcon = it }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_REPLY)
            addAction(ACTION_MARK_READ)
        }
        ContextCompat.registerReceiver(
            HostInfo.application, notificationReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Capture the exact talker wxid before WeChat builds the notification.
        // x.d → m0.a → e0.b → Notification.Builder.build() all run synchronously on
        // this thread, so the build() hook below reads it back via the ThreadLocal.
        methodDealNotify.hookBefore {
            currentTalker.set(args[1] as? String)
        }

        Notification.Builder::class.reflekt()
            .firstMethod { name = "build" }
            .hookBefore {
                val context = HostInfo.application

                val builder = thisObject as Notification.Builder
                val notif = builder.reflekt().firstField { type = Notification::class }
                    .get() as Notification
                val channelId = notif.channelId

                if (channelId != "message_channel_new_id") {
                    return@hookBefore
                }

                val notifTitle = notif.extras.getString(Notification.EXTRA_TITLE)
                    ?: "未知对话 (请向模块开发者报告错误)"
                val notifText =
                    notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        ?: "未知内容 (请向模块开发者报告错误)"

                // 1. Resolve exact WXID from the talker captured in the x.d hook
                val convWxId = currentTalker.get()
                if (convWxId == null) {
                    WeLogger.w(TAG, "no talker captured for $notifTitle, skipping enhancements")
                    return@hookBefore
                }
                currentTalker.remove()

                val match = MESSAGE_REGEX.find(notifText)

                var senderName: String
                var text: String
                if (match == null) {
                    WeLogger.w(
                        TAG,
                        "failed to match message regex, using raw sender name & text content"
                    )
                    senderName = notifTitle
                    text = notifText
                } else {
                    senderName = match.groupValues[2].takeIf { it.isNotEmpty() }
                        ?.also { lastGroupChatSender[convWxId] = it }
                        ?: lastGroupChatSender[convWxId] ?: run {
                            WeLogger.w(
                                TAG,
                                "couldn't find sender name in either notification or cache"
                            )
                            notifTitle
                        }
                    text = match.groupValues[3]
                }

                text = text
                    .replaceRichContent()
                    .replaceEmojis()

                WeLogger.i(TAG, "enhancing notification for $notifTitle ($convWxId)")

                // 2. Build the MessagingStyle
                // TODO: add cropping
                val mePerson = Person.Builder().setName("我")
                    .apply {
                        if (::meAvatarIcon.isInitialized)
                            setIcon(meAvatarIcon)
                    }
                    .build()
                val messagingStyle = Notification.MessagingStyle(mePerson)

                if (convWxId.isGroupChatWxId) {
                    messagingStyle.isGroupConversation = true
                    messagingStyle.conversationTitle = notifTitle
                } else {
                    senderName = notifTitle
                }

                val senderPerson = Person.Builder().setName(senderName).build()
                messagingStyle.addMessage(text, System.currentTimeMillis(), senderPerson)

                builder.style = messagingStyle

                // 3. Quick Reply Action
                val remoteInput = RemoteInput.Builder("key_reply_content")
                    .setLabel("输入回复内容...")
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
                    "回复", replyPendingIntent
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
                    "标为已读", readPendingIntent
                ).build()

                // Apply actions directly to the builder
                builder.addAction(replyAction)
                builder.addAction(readAction)
            }
    }
}
