package moe.ouom.wekit.hooks.items.chat

import android.app.Notification
import android.app.PendingIntent
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
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(path = "聊天与消息/通知进化", desc = "让应用的新消息通知更易用")
object NotificationEvolved : BaseSwitchFunctionHookItem() {
    private const val TAG = "NotificationEvolved"

    private const val INTENT_FILTER_ACTION = "${PackageConstants.PACKAGE_NAME_WECHAT}.ACTION_NOTIFICATION_QUICK_REPLY"

    val replyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val results = RemoteInput.getResultsFromIntent(intent) ?: return
            val replyTargetName = intent.getStringExtra("extra_talker_name") ?: return
            val replyContent = results.getCharSequence("key_reply_content")?.toString()

            if (!replyContent.isNullOrEmpty()) {
                WeLogger.d(TAG, "received broadcast, replyTargetName=$replyTargetName, replyContent=$replyContent")
                // WeMessageApi.sendText(replyTargetName, replyContent)

                // val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                // nm.cancel(ID)
            }
            else {
                WeLogger.w(TAG, "received empty reply for $replyContent, ignoring")
            }
        }
    }

    override fun entry(classLoader: ClassLoader) {
        val context = HostInfo.getApplication()
        ContextCompat.registerReceiver(
            context,
            replyReceiver,
            IntentFilter(INTENT_FILTER_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Notification.Builder::class.asResolver()
            .firstMethod {
                name = "build"
            }
            .hookAfter { param ->
                val notification = param.result as Notification
                val context = notification.asResolver().firstField { name = "mContext" }.get()!! as Context
                WeLogger.d(TAG, "building notification: notification=$notification")

                if (notification.channelId == "message_channel_new_id") {
                    WeLogger.i(TAG, "found a new message notification, modifying it")

                    val remoteInput = RemoteInput.Builder("key_reply_content")
                        .setLabel("快速回复...")
                        .build()

                    val intent = Intent(INTENT_FILTER_ACTION).apply {
                        `package` = PackageConstants.PACKAGE_NAME_WECHAT
                        putExtra("extra_talker_name", notification.extras.getString("android.title"))
                    }

                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        notification.hashCode(),
                        intent,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )

                    val replyAction = Notification.Action.Builder(
                        Icon.createWithResource(context, android.R.drawable.ic_menu_send),
                        "快速回复",
                        pendingIntent
                    ).addRemoteInput(remoteInput).build()

                    val oldActions = notification.actions
                    val newActions = if (oldActions == null) {
                        arrayOf(replyAction)
                    } else {
                        oldActions + replyAction
                    }

                    notification.actions = newActions
                }
            }
    }
}