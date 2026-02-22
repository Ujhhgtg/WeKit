package moe.ouom.wekit.hooks.items.chat

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.constants.PackageConstants
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeDatabaseApi
import moe.ouom.wekit.hooks.sdk.base.WeMessageApi
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(path = "聊天与消息/通知进化", desc = "让应用的新消息通知更易用\n1. '快速回复' 按钮\n2. '标记为已读' 按钮\n3. 使用原生对话样式 (MessagingStyle)")
object NotificationEvolved : BaseSwitchFunctionHookItem() {

    private const val TAG = "NotificationEvolved"
    private const val ACTION_REPLY = "${PackageConstants.PACKAGE_NAME_WECHAT}.ACTION_WEKIT_REPLY"
    private const val ACTION_MARK_READ = "${PackageConstants.PACKAGE_NAME_WECHAT}.ACTION_WEKIT_MARK_READ"

    // cache friends to avoid repeating sql queries
    // TODO: build a sql statement to directly query target contact
    private val friends by lazy { WeDatabaseApi.getFriends() }

    val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val targetWxid = intent.getStringExtra("extra_target_wxid") ?: return
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            when (intent.action) {
                ACTION_REPLY -> {
                    val results = RemoteInput.getResultsFromIntent(intent) ?: return
                    val replyContent = results.getCharSequence("key_reply_content")?.toString()

                    if (!replyContent.isNullOrEmpty()) {
                        WeLogger.i(TAG, "Quick replying '$replyContent' to $targetWxid")
                        WeMessageApi.sendText(targetWxid, replyContent)
                        notificationManager.cancel(targetWxid.hashCode())
                    }
                }
                ACTION_MARK_READ -> {
                    WeLogger.i(TAG, "Marking chat as read for $targetWxid")
                    // TODO: Implement your WeDatabaseApi.markAsRead(targetWxid) logic here
                    notificationManager.cancel(targetWxid.hashCode())
                }
            }
        }
    }

    val multiMessageRegex = Regex("""^\[\d+条].+?: (.*)$""")

    override fun entry(classLoader: ClassLoader) {
        val context = HostInfo.getApplication()

        val filter = IntentFilter().apply {
            addAction(ACTION_REPLY)
            addAction(ACTION_MARK_READ)
        }
        ContextCompat.registerReceiver(
            context, notificationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Notification.Builder::class.asResolver()
            .firstMethod { name = "build" }
            .hookBefore { param ->
                val builder = param.thisObject as Notification.Builder
                val notification = builder.asResolver().firstField { type = Notification::class }.get() as Notification
                val channelId = notification.channelId

                if (channelId == "message_channel_new_id") {

                    val title = notification.extras.getString(Notification.EXTRA_TITLE) ?: "Unknown"
                    val rawText = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

                    val matchResult = multiMessageRegex.find(rawText)
                    var cleanText = if (matchResult != null) {
                        matchResult.groupValues[1]
                    } else {
                        rawText
                    }

                    cleanText = cleanText.replace("[图片]", "\uD83D\uDDBC\uFE0F")
                        .replace("[视频]", "\uD83C\uDFA5")
//                        .replace("[动画表情]", "\uD83D\uDE00")
                        .replace("[文件]", "\uD83D\uDCC1")
                        .replace("[语音]", "\uD83D\uDDE3\uFE0F")
                        .replace("[位置]", "\uD83D\uDDFA\uFE0F")
                        .replace("[红包]", "\uD83E\uDDE7")
                        .replace("[转账]", "\uD83D\uDCB5")

                    // 1. Resolve exact WXID immediately during notification creation
                    val friend = friends.firstOrNull { it.nickname == title || it.remarkName == title }
                    val targetWxid = friend?.wxid

                    if (targetWxid == null) {
                        WeLogger.w(TAG, "could not resolve wxid for $title, skipping enhancements")
                        return@hookBefore
                    }

                    WeLogger.i(TAG, "Enhancing notification for $title ($targetWxid)")

                    // 2. Build the MessagingStyle
                    val mePerson = Person.Builder().setName("朕").build()
                    val senderPerson = Person.Builder()
                        .setName(title)
                        // TODO: fetch the avatar bitmap here and set it via .setIcon()
                        .build()

                    val messagingStyle = Notification.MessagingStyle(mePerson)
                    messagingStyle.addMessage(cleanText, System.currentTimeMillis(), senderPerson)

                    if (isGroupChat(targetWxid)) {
                        messagingStyle.isGroupConversation = true
                        messagingStyle.conversationTitle = title
                    }

                    builder.style = messagingStyle

                    // 3. Quick Reply Action
                    val remoteInput = RemoteInput.Builder("key_reply_content")
                        .setLabel("准卿所请, 即刻拟旨...")
                        .build()

                    val replyIntent = Intent(ACTION_REPLY).apply {
                        setPackage(PackageConstants.PACKAGE_NAME_WECHAT)
                        putExtra("extra_target_wxid", targetWxid)
                    }
                    val replyPendingIntent = PendingIntent.getBroadcast(
                        context, targetWxid.hashCode(), replyIntent,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )

                    val replyAction = Notification.Action.Builder(
                        Icon.createWithResource(context, android.R.drawable.ic_menu_send),
                        "宣旨", replyPendingIntent
                    ).addRemoteInput(remoteInput).build()

                    // 4. Mark as Read Action
                    val readIntent = Intent(ACTION_MARK_READ).apply {
                        setPackage(PackageConstants.PACKAGE_NAME_WECHAT)
                        putExtra("extra_target_wxid", targetWxid)
                    }
                    val readPendingIntent = PendingIntent.getBroadcast(
                        context, targetWxid.hashCode(), readIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    val readAction = Notification.Action.Builder(
                        Icon.createWithResource(context, android.R.drawable.ic_menu_view),
                        "朕已阅", readPendingIntent
                    ).build()

                    // Apply actions directly to the builder
                    builder.addAction(replyAction)
                    builder.addAction(readAction)
                }
            }
    }

    private fun isGroupChat(wxid: String): Boolean {
        return wxid.endsWith("@chatroom")
    }
}