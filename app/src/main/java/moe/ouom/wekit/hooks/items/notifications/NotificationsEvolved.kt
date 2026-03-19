package moe.ouom.wekit.hooks.items.notifications

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
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.nameof.nameof
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.wekit.constants.PackageNames
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.api.core.WeApi
import moe.ouom.wekit.hooks.api.core.WeConversationApi
import moe.ouom.wekit.hooks.api.core.WeDatabaseApi
import moe.ouom.wekit.hooks.api.core.WeMessageApi
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.utils.HostInfo
import moe.ouom.wekit.utils.LruCache
import moe.ouom.wekit.utils.ModulePaths
import moe.ouom.wekit.utils.logging.WeLogger
import moe.ouom.wekit.utils.replaceEmojis
import moe.ouom.wekit.utils.replaceRichContent
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.writeBytes

@HookItem(
    path = "通知/通知进化",
    desc = "让应用的新消息通知更易用\n1. '快速回复' 按钮\n2. '标记为已读' 按钮\n3. 使用原生对话样式 (MessagingStyle)"
)
object NotificationsEvolved : SwitchHookItem() {

    private val TAG = nameof(NotificationsEvolved)

    private val lastGroupChatSender = LruCache<String, String>()

    private const val ACTION_REPLY = "${PackageNames.WECHAT}.ACTION_WEKIT_REPLY"
    private const val ACTION_MARK_READ =
        "${PackageNames.WECHAT}.ACTION_WEKIT_MARK_READ"

    // cache friends to avoid repeating sql queries
    // TODO: build a sql statement to directly query target contact
    private val friends by lazy { WeDatabaseApi.getFriends() }
    private val groups by lazy { WeDatabaseApi.getGroups() }

    private lateinit var meAvatarIcon: Icon

    private val meAvatarPath = ModulePaths.data!! / "me_avatar"

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val targetWxId = intent.getStringExtra("extra_target_wxid") ?: return
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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

    private val MESSAGE_REGEX = Regex("""^(\[\d+条])?(.+?)?: (.*)$""")

    override fun onEnable() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val bitmap: Bitmap
                if (meAvatarPath.exists()) {
                    bitmap = BitmapFactory.decodeFile(meAvatarPath.pathString)
                } else {
                    while (runCatching { WeApi.selfWxId.isEmpty() }
                            .getOrDefault(true)) {
                        delay(2000)
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

        Notification.Builder::class.asResolver()
            .firstMethod { name = "build" }
            .hookBefore { param ->
                val context = HostInfo.application

                val builder = param.thisObject as Notification.Builder
                val notif = builder.asResolver().firstField { type = Notification::class }
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

                // 1. Resolve exact WXID immediately during notification creation
                val friend =
                    friends.firstOrNull { it.nickname == notifTitle || it.remarkName == notifTitle }
                var convWxId = friend?.wxId
                if (convWxId == null) {
                    val group =
                        groups.firstOrNull { it.nickname == notifTitle }
                    convWxId = group?.wxId
                }
                if (convWxId == null) {
                    WeLogger.w(TAG, "could not resolve wxid for $notifTitle, skipping enhancements")
                    return@hookBefore
                }

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

                if (isGroupChat(convWxId)) {
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

    private fun isGroupChat(wxid: String): Boolean {
        return wxid.endsWith("@chatroom")
    }
}