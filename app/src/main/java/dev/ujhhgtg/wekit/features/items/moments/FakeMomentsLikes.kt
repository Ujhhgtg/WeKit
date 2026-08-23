package dev.ujhhgtg.wekit.features.items.moments

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import android.content.ContentValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.tencent.mm.plugin.sns.ui.SnsCommentFooter
import com.tencent.mm.protocal.protobuf.SnsObject
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.StarIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

object FakeMomentsLikes : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider,
    WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "伪集赞"
    override val nameRes = R.string.feature_fake_moments_likes_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_fake_moments_likes_description

    private const val TAG = "FakeMomentsLikes"

    // 存储每个朋友圈动态的伪点赞用户配置 (snsId -> Set<WxId>)
    // 由 UI 线程写入 (菜单里选择伪点赞联系人), 由数据库监听线程读取, 因此必须是并发安全的.
    private val fakeLikeWxIds = ConcurrentHashMap<Long, Set<String>>()
    private lateinit var parseFromMethod: Method
    private lateinit var snsUserProtobufClass: Class<*>
    private lateinit var snsUserProtobufClassWxIdField: Field

    override fun onEnable() {
        snsUserProtobufClass = SnsCommentFooter::class.java.getMethod("getCommentInfo").returnType
        snsUserProtobufClassWxIdField = snsUserProtobufClass.reflekt().firstField { type = String::class }.self
        parseFromMethod = SnsObject::class.reflekt().firstMethod { name = "parseFrom"; superclass() }.self
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
                localizedMomentsString(R.string.moments_fake_likes_menu),
                StarIcon,
                { _, _ -> true }
            ) { moment ->
                val contacts = WeDatabaseApi.getContacts()
                val snsInfo = moment.snsInfo!!
                val snsId = snsInfo.reflekt().getField("field_snsId", true) as Long

                val currentSelected = fakeLikeWxIds[snsId] ?: emptySet()

                showComposeDialog(moment.activity) {
                    var countInput by remember { mutableStateOf("") }
                    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)

                    AlertDialogContent(
                        title = { Text(stringResource(R.string.moments_fake_likes_method_title)) },
                        text = {
                            DefaultColumn {
                                Button(
                                    onClick = {
                                        onDismiss()
                                        showComposeDialog(moment.activity) {
                                            ContactsSelector(
                                                title = localizedContext.getString(R.string.moments_fake_likes_select_users),
                                                contacts = contacts,
                                                initialSelectedWxIds = currentSelected,
                                                onDismiss = onDismiss,
                                                onConfirm = { selectedWxids ->
                                                    if (selectedWxids.isEmpty()) {
                                                        fakeLikeWxIds.remove(snsId)
                                                        showToast(localizedContext.getString(R.string.moments_fake_likes_cleared))
                                                    } else {
                                                        fakeLikeWxIds[snsId] = selectedWxids
                                                        showToast(localizedMomentsQuantity(R.plurals.moments_fake_likes_set_count, selectedWxids.size, selectedWxids.size))
                                                    }
                                                    onDismiss()
                                                }
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(stringResource(R.string.moments_fake_likes_select_contacts)) }

                                HorizontalDivider()

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        value = countInput,
                                        onValueChange = { countInput = it.filter(Char::isDigit) },
                                        label = { Text(stringResource(R.string.moments_fake_likes_random_count)) },
                                        enabled = contacts.isNotEmpty(),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        enabled = contacts.isNotEmpty(),
                                        onClick = {
                                            val count = countInput.toIntOrNull() ?: 0
                                            if (count < 0) {
                                                showToast(localizedContext.getString(R.string.moments_fake_likes_invalid_count))
                                                return@Button
                                            }
                                            if (count == 0) {
                                                fakeLikeWxIds.remove(snsId)
                                                showToast(localizedContext.getString(R.string.moments_fake_likes_cleared))
                                            } else {
                                                val selected = contacts.shuffled().take(count).map { it.wxId }.toSet()
                                                fakeLikeWxIds[snsId] = selected
                                                showToast(localizedMomentsQuantity(R.plurals.moments_fake_likes_random_set_count, selected.size, selected.size))
                                            }
                                            onDismiss()
                                        }
                                    ) { Text(stringResource(R.string.dialog_confirm)) }
                                }
                            }
                        },
                        dismissButton = {
                            TextButton({
                                onDismiss()
                                fakeLikeWxIds.remove(snsId)
                                showToast(localizedContext.getString(R.string.moments_fake_likes_cleared))
                            }) { Text(stringResource(R.string.action_clear)) }
                        },
                        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
                    )
                }
            }
        )
    }

    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        try {
            injectFakeLikes(table, values)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to handle database update", e)
        }
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
