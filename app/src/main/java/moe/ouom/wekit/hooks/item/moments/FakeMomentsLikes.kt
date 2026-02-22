package moe.ouom.wekit.hooks.item.moments

import android.content.ContentValues
import android.view.ContextMenu
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItemsMultiChoice
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeDatabaseApi
import moe.ouom.wekit.hooks.sdk.base.WeDatabaseListener
import moe.ouom.wekit.hooks.sdk.ui.WeMomentsContextMenuApi
import moe.ouom.wekit.ui.utils.CommonContextWrapper
import moe.ouom.wekit.utils.Initiator.loadClass
import moe.ouom.wekit.utils.log.WeLogger
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedList

@HookItem(
    path = "朋友圈/朋友圈伪集赞",
    desc = "自定义朋友圈点赞用户列表"
)
object FakeMomentsLikes : BaseSwitchFunctionHookItem(), WeDatabaseListener.IUpdateListener  {

    private const val TAG = "FakeMomentsLikes"
    private const val MENU_ID_FAKE_LIKES = 20001
    private const val TBL_SNS_INFO = "SnsInfo"

    // 存储每个朋友圈动态的伪点赞用户配置 (snsId -> Set<微信id>)
    private val fakeLikeWxids = mutableMapOf<Long, Set<String>>()

    private var snsObjectClass: Class<*>? = null
    private var parseFromMethod: Method? = null
    private var toByteArrayMethod: Method? = null
    private var likeUserListField: Field? = null
    private var likeUserListCountField: Field? = null
    private var likeCountField: Field? = null
    private var likeFlagField: Field? = null
    private var snsUserProtobufClass: Class<*>? = null

    private val onCreateListener = WeMomentsContextMenuApi.OnCreateListener { menu ->
        menu.add(ContextMenu.NONE, MENU_ID_FAKE_LIKES, 0, "设置伪点赞")
            ?.setIcon(android.R.drawable.star_on)
    }

    private val onSelectListener = WeMomentsContextMenuApi.OnSelectListener { context, itemId ->
        if (itemId == MENU_ID_FAKE_LIKES) {
            showFakeLikesDialog(context)
            true
        } else {
            false
        }
    }

    override fun entry(classLoader: ClassLoader) {
        initReflection(classLoader)

        WeMomentsContextMenuApi.addOnCreateListener(onCreateListener)
        WeMomentsContextMenuApi.addOnSelectListener(onSelectListener)

        WeDatabaseListener.addListener(this)
        WeLogger.i(TAG, "伪点赞功能已启动")
    }

    override fun unload(classLoader: ClassLoader) {
        WeDatabaseListener.removeListener(this)

        WeMomentsContextMenuApi.removeOnCreateListener(onCreateListener)
        WeMomentsContextMenuApi.removeOnSelectListener(onSelectListener)
        WeLogger.i(TAG, "伪点赞功能已停止")
    }

    override fun onUpdate(table: String, values: ContentValues): Boolean {
        try {
            injectFakeLikes(table, values)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "处理数据库更新异常", e)
        }

        return false // 返回 false 表示继续原有流程
    }

    private fun initReflection(classLoader: ClassLoader) {
        try {
            snsObjectClass = loadClass("com.tencent.mm.protocal.protobuf.SnsObject")

            snsObjectClass?.let { clazz ->
                parseFromMethod = clazz.getMethod("parseFrom", ByteArray::class.java)
                toByteArrayMethod = clazz.getMethod("toByteArray")

                listOf("LikeUserList", "LikeUserListCount", "LikeCount", "LikeFlag").forEach { name ->
                    clazz.getDeclaredField(name).also { field ->
                        field.isAccessible = true
                        when (name) {
                            "LikeUserList" -> likeUserListField = field
                            "LikeUserListCount" -> likeUserListCountField = field
                            "LikeCount" -> likeCountField = field
                            "LikeFlag" -> likeFlagField = field
                        }
                    }
                }
            }

            snsUserProtobufClass = loadClass("com.tencent.mm.plugin.sns.ui.SnsCommentFooter")
                .getMethod("getCommentInfo").returnType

            WeLogger.d(TAG, "反射初始化成功")

        } catch (e: Exception) {
            WeLogger.e(TAG, "反射初始化失败", e)
        }
    }

    private fun injectFakeLikes(tableName: String, values: ContentValues) = runCatching {
        if (tableName != TBL_SNS_INFO) return@runCatching
        val snsId = values.get("snsId") as? Long ?: return@runCatching
        val fakeWxids = fakeLikeWxids[snsId] ?: emptySet()
        if (fakeWxids.isEmpty() || snsObjectClass == null || snsUserProtobufClass == null) return@runCatching

        val snsObj = snsObjectClass!!.getDeclaredConstructor().newInstance()
        parseFromMethod?.invoke(snsObj, values.get("attrBuf") as? ByteArray ?: return@runCatching)

        val fakeList = LinkedList<Any>().apply {
            fakeWxids.forEach { wxid ->
                snsUserProtobufClass!!.getDeclaredConstructor().newInstance().apply {
                    javaClass.getDeclaredField("d").apply { isAccessible = true }.set(this, wxid)
                    add(this)
                }
            }
        }

        likeUserListField?.set(snsObj, fakeList)
        likeUserListCountField?.set(snsObj, fakeList.size)
        likeCountField?.set(snsObj, fakeList.size)
        likeFlagField?.set(snsObj, 1)

        values.put("attrBuf", toByteArrayMethod?.invoke(snsObj) as? ByteArray ?: return@runCatching)
        WeLogger.i(TAG, "成功为朋友圈 $snsId 注入 ${fakeList.size} 个伪点赞")
    }.onFailure { WeLogger.e(TAG, "注入伪点赞失败", it) }

    /**
     * 显示伪点赞用户选择对话框
     */
    private fun showFakeLikesDialog(context: WeMomentsContextMenuApi.SnsContext) {
        try {
            // 获取所有好友列表
            val allFriends = WeDatabaseApi.getAllConnects()

            val displayItems = allFriends.map { contact ->
                buildString {
                    // 如果有备注，显示"备注(昵称)"
                    if (contact.conRemark.isNotBlank()) {
                        append(contact.conRemark)
                        if (contact.nickname.isNotBlank()) {
                            append(" (${contact.nickname})")
                        }
                    }
                    // 否则直接显示昵称
                    else if (contact.nickname.isNotBlank()) {
                        append(contact.nickname)
                    }
                    // 最后备选用wxid
                    else {
                        append(contact.username)
                    }
                }
            }

            val snsInfo = context.snsInfo
            val snsId = context.snsInfo!!.javaClass.superclass!!.getDeclaredField("field_snsId").apply { isAccessible = true }.get(snsInfo) as Long
            val currentSelected = fakeLikeWxids[snsId] ?: emptySet()

            val currentIndices = allFriends.mapIndexedNotNull { index, contact ->
                if (currentSelected.contains(contact.username)) index else null
            }.toIntArray()

            val wrappedContext = CommonContextWrapper.createAppCompatContext(context.activity)

            // 显示多选对话框
            MaterialDialog(wrappedContext).show {
                title(text = "选择伪点赞用户")
                listItemsMultiChoice(
                    items = displayItems,
                    initialSelection = currentIndices
                ) { dialog, indices, items ->
                    val selectedWxids = indices.map { allFriends[it].username }.toSet()

                    if (selectedWxids.isEmpty()) {
                        fakeLikeWxids.remove(snsId)
                        WeLogger.d(TAG, "已清除朋友圈 $snsId 的伪点赞配置")
                    } else {
                        fakeLikeWxids[snsId] = selectedWxids
                        WeLogger.d(TAG, "已设置朋友圈 $snsId 的伪点赞: $selectedWxids")
                    }
                }
                positiveButton(text = "确定")
                negativeButton(text = "取消")
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "显示选择对话框失败", e)
        }
    }
}