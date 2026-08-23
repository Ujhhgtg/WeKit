package dev.ujhhgtg.wekit.features.items.chat

import android.app.Person
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

object ExternalSharingEvolved : ClickableFeature() {

    override val technicalId = "分享进化"
    override val nameRes = R.string.feature_external_sharing_evolved_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_external_sharing_evolved_description

    private const val TAG = "ExternalSharingEvolved"

    override fun onEnable() {
        CoroutineScope(Dispatchers.Main).launch {
            delay(1.seconds) // wait for db to initialize
            updateSharingShortcuts()
        }
    }

    override fun onClick(context: ComponentActivity) {
        val ctx = HostInfo.application
        val sm = ctx.getSystemService<ShortcutManager>()

        sm.removeAllDynamicShortcuts()
    }

    private fun updateSharingShortcuts() {
        val ctx = HostInfo.application
        val sm = ctx.getSystemService<ShortcutManager>()

        // 获取消息互动量排名前 3 的好友
        val topFriends = WeDatabaseApi.getFriendsOrderedByMessageCount(3)
        if (topFriends.isEmpty()) {
            WeLogger.w(TAG, "No top friends found to generate shortcuts.")
            return
        }

        val shortcuts = topFriends.map { (friend, _) ->
            val displayName = friend.remarkName.ifEmpty { friend.nickname }

            val contact = Person.Builder()
                .setName(displayName)
                .setKey(friend.wxId)
                .setImportant(true)
                .build()

            ShortcutInfo.Builder(ctx, "sharing_target_${friend.wxId}")
                .setShortLabel(displayName)
                .setPerson(contact)
                // 绑定到微信内置的系统分享匹配规则（通常对应其 shortcuts.xml 内置定义的 category）
                .setCategories(setOf("android.intent.category.DEFAULT"))
                .setIntent(
                    Intent(Intent.ACTION_SEND).apply {
                        component = ComponentName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI")
                        type = "*/*"
                        // 核心：携带微信内部定向路由联系人的特定 Extra 字段，让 ShareImgUI 能够直接绕过选择人界面
                        putExtra("Select_Conv_User", friend.wxId)
                        putExtra("I_am_from_share_msg", true)
                        putExtra("Intent_Direct_Share", true)
                    }
                )
                .setLongLived(true)
                // 提示：如果需要展示真实头像，可以使用 Icon.createWithAdaptiveBitmap() 加载本地域名下的头像文件缓存
                .build()
        }

        sm.dynamicShortcuts = shortcuts
        WeLogger.d(TAG, "Successfully published ${shortcuts.size} modern direct-share targets.")
    }
}
