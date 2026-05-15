package dev.ujhhgtg.wekit.hooks.items.moments

import android.content.ContentValues
import com.highcapable.kavaref.extension.createInstance
import com.tencent.mm.plugin.sns.ui.SnsCommentFooter
import com.tencent.mm.protocal.protobuf.SnsObject
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.hooks.api.ui.WeMomentsContextMenuApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.ui.content.ContactsSelectionDialog
import dev.ujhhgtg.wekit.ui.utils.StarIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.resolve
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedList

@HookItem(
    path = "朋友圈/朋友圈伪集赞",
    description = "自定义朋友圈点赞用户列表"
)
object FakeMomentsLikes : SwitchHookItem(), WeMomentsContextMenuApi.IMenuItemsProvider,
    WeDatabaseListenerApi.IUpdateListener {

    private val TAG = nameOf(FakeMomentsLikes)

    // 存储每个朋友圈动态的伪点赞用户配置 (snsId -> Set<WxId>)
    private val fakeLikeWxIds = mutableMapOf<Long, Set<String>>()
    private lateinit var parseFromMethod: Method
    private lateinit var snsUserProtobufClass: Class<*>
    private lateinit var snsUserProtobufClassWxIdField: Field

    override fun onEnable() {
        snsUserProtobufClass = SnsCommentFooter::class.java.getMethod("getCommentInfo").returnType
        snsUserProtobufClassWxIdField = snsUserProtobufClass.resolve().firstField { type = String::class }.self
        parseFromMethod = SnsObject::class.resolve().firstMethod { name = "parseFrom"; superclass() }.self
        WeMomentsContextMenuApi.addProvider(this)
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> {
        return listOf(
            WeMomentsContextMenuApi.MenuItem(
                777006,
                "伪点赞",
                StarIcon,
                { _, _ -> true }
            ) { moments ->
                val allFriends = WeDatabaseApi.getContacts()
                val snsInfo = moments.snsInfo
                val snsId = snsInfo!!.javaClass.superclass!!
                    .getDeclaredField("field_snsId")
                    .apply { isAccessible = true }
                    .get(snsInfo) as Long

                val currentSelected = fakeLikeWxIds[snsId] ?: emptySet()

                showComposeDialog(moments.activity) {
                    ContactsSelectionDialog(
                        title = "选择伪点赞用户",
                        contacts = allFriends,
                        initialSelectedWxIds = currentSelected,
                        onDismiss = onDismiss,
                        onConfirm = { selectedWxids ->
                            if (selectedWxids.isEmpty()) {
                                fakeLikeWxIds.remove(snsId)
                                showToast("已清除伪点赞配置")
                            } else {
                                fakeLikeWxIds[snsId] = selectedWxids
                                showToast("已设置 ${selectedWxids.size} 个伪点赞")
                            }
                            onDismiss()
                        }
                    )
                }
            }
        )
    }

    override fun onUpdate(table: String, values: ContentValues): Boolean {
        try {
            injectFakeLikes(table, values)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "处理数据库更新异常", e)
        }

        return false
    }

    private fun injectFakeLikes(tableName: String, values: ContentValues) = runCatching {
        if (tableName != "SnsInfo") return@runCatching
        val snsId = values.get("snsId") as? Long ?: return@runCatching
        val fakeWxIds = fakeLikeWxIds[snsId] ?: emptySet()
        if (fakeWxIds.isEmpty()) return@runCatching

        val snsObj = SnsObject()
        parseFromMethod.invoke(snsObj, values.get("attrBuf") as? ByteArray ?: return@runCatching)

        val fakeList = LinkedList<Any>().apply {
            fakeWxIds.forEach { wxid ->
                snsUserProtobufClass.createInstance().apply {
                    snsUserProtobufClassWxIdField.set(this, wxid)
                    add(this)
                }
            }
        }

        snsObj.LikeUserList = fakeList
        snsObj.LikeUserListCount = fakeList.size
        snsObj.LikeCount = fakeList.size
        snsObj.LikeFlag = 1

        values.put("attrBuf", snsObj.toByteArray())
        WeLogger.i(TAG, "成功为朋友圈 $snsId 注入 ${fakeList.size} 个伪点赞")
    }.onFailure { WeLogger.e(TAG, "注入伪点赞失败", it) }
}
