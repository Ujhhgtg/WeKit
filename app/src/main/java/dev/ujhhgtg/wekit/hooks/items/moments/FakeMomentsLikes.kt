package dev.ujhhgtg.wekit.hooks.items.moments

import android.content.ContentValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.createInstance
import com.tencent.mm.plugin.sns.ui.SnsCommentFooter
import com.tencent.mm.protocal.protobuf.SnsObject
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.hooks.api.ui.WeMomentsContextMenuApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.ModuleRes
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.showToast
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedList

@HookItem(
    path = "朋友圈/朋友圈伪集赞",
    desc = "自定义朋友圈点赞用户列表"
)
object FakeMomentsLikes : SwitchHookItem(), WeMomentsContextMenuApi.IMenuItemsProvider,
    WeDatabaseListenerApi.IUpdateListener {

    private val TAG = nameOf(FakeMomentsLikes)
    private const val TBL_SNS_INFO = "SnsInfo"

    // 存储每个朋友圈动态的伪点赞用户配置 (snsId -> Set<WxId>)
    private val fakeLikeWxIds = mutableMapOf<Long, Set<String>>()
    private lateinit var parseFromMethod: Method
    private lateinit var snsUserProtobufClass: Class<*>
    private lateinit var snsUserProtobufClassWxIdField: Field

    override fun onEnable() {
        snsUserProtobufClass = SnsCommentFooter::class.java.getMethod("getCommentInfo").returnType
        snsUserProtobufClassWxIdField = snsUserProtobufClass.asResolver().firstField { type = String::class }.self
        parseFromMethod = SnsObject::class.asResolver().firstMethod { name = "parseFrom"; superclass() }.self
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
                { ModuleRes.getDrawable(R.drawable.star_24px)!! },
                { _, _ -> true }) { moments ->
                val allFriends = WeDatabaseApi.getContacts()

                val displayItems = allFriends.map { contact ->
                    buildString {
                        if (contact.remarkName.isNotBlank()) {
                            append(contact.remarkName)
                            if (contact.nickname.isNotBlank()) append(" (${contact.nickname})")
                        } else if (contact.nickname.isNotBlank()) {
                            append(contact.nickname)
                        } else {
                            append(contact.wxId)
                        }
                    }
                }

                val snsInfo = moments.snsInfo
                val snsId = moments.snsInfo!!.javaClass.superclass!!
                    .getDeclaredField("field_snsId")
                    .apply { isAccessible = true }
                    .get(snsInfo) as Long

                val currentSelected = fakeLikeWxIds[snsId] ?: emptySet()
                val initialSelection = allFriends.mapIndexedNotNull { index, contact ->
                    if (contact.wxId in currentSelected) index else null
                }.toSet()

                showComposeDialog(moments.activity) {
                    var selectedIndices by remember { mutableStateOf(initialSelection) }

                    AlertDialogContent(
                        title = { Text("选择伪点赞用户") },
                        text = {
                            LazyColumn {
                                itemsIndexed(displayItems) { index, label ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedIndices = if (index in selectedIndices)
                                                    selectedIndices - index
                                                else
                                                    selectedIndices + index
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = index in selectedIndices,
                                            onCheckedChange = { checked ->
                                                selectedIndices = if (checked)
                                                    selectedIndices + index
                                                else
                                                    selectedIndices - index
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = label)
                                    }
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { onDismiss() }) {
                                Text("取消")
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                val selectedWxids = selectedIndices.map { allFriends[it].wxId }.toSet()
                                if (selectedWxids.isEmpty()) {
                                    fakeLikeWxIds.remove(snsId)
                                    showToast("已清除朋友圈的伪点赞配置")
                                } else {
                                    fakeLikeWxIds[snsId] = selectedWxids
                                    showToast("已设置朋友圈的 ${selectedWxids.size} 个伪点赞")
                                }
                                onDismiss()
                            }) {
                                Text("确定")
                            }
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
        if (tableName != TBL_SNS_INFO) return@runCatching
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
