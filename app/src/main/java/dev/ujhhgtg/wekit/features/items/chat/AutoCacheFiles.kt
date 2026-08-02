package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@Feature(name = "自动缓存文件", categories = ["聊天"], description = "监听接收到的文件消息, 在对方上传完成后自动触发微信内部下载将其缓存到本地")
object AutoCacheFiles : ClickableFeature(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    private const val TAG = "AutoCacheFiles"

    // 已经发起过缓存的 msgSvrId, 避免同一文件被反复触发 (插入 + 多次更新)。
    private val handledSvrIds = ConcurrentHashMap.newKeySet<Long>()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var useWhitelist by WePrefs.prefOption("autocache_files_use_whitelist", false)
    private var whitelist by WePrefs.prefOption("autocache_files_whitelist", emptySet())
    private var blacklist by WePrefs.prefOption("autocache_files_blacklist", emptySet())

    override fun onEnable() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        handledSvrIds.clear()
    }

    // 文件消息刚到达时可能是"对方上传中"占位; 对方传完后微信会就地更新该行的 content,
    // 因此插入与更新都要监听。
    override fun onInsert(table: String, values: ContentValues) = maybeCacheFile(table, values)

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) = maybeCacheFile(table, values)

    private fun maybeCacheFile(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: return
        if (type != MessageType.FILE.code) return

        // 自己发出的文件本身就在本地, 无需缓存
        if (values.getAsInteger("isSend") == 1) return

        val talker = values.getAsString("talker") ?: return

        if (useWhitelist) {
            if (talker !in whitelist) return
        } else {
            if (talker in blacklist) return
        }

        val msgSvrId = values.getAsLong("msgSvrId") ?: return
        if (msgSvrId == 0L) return

        val content = values.getAsString("content") ?: return

        // 通过 appmsg 内层 <type> 判断文件状态:
        //   74 / 131 → 对方仍在上传, 此刻下载必然失败, 跳过 (等对方传完后的更新事件再处理)
        //   6  / 130 → 文件已就绪, 可以下载
        val file = try {
            MessageInfo.FileMessage(content)
        } catch (e: Exception) {
            WeLogger.e(TAG, "file msg $msgSvrId: failed to parse appmsg content, skip", e)
            return
        }

        when {
            file.isSenderUploading -> {
                WeLogger.i(TAG, "file msg $msgSvrId: sender still uploading (appmsgType=${file.appMsgType}), deferring")
                return
            }

            !file.isDownloadable -> {
                // 未知的 appmsg 类型, 保守跳过
                WeLogger.d(TAG, "file msg $msgSvrId: not downloadable yet (appmsgType=${file.appMsgType}), skip")
                return
            }
        }

        // 文件已就绪, 且尚未处理过 → 发起缓存
        if (!handledSvrIds.add(msgSvrId)) return

        WeLogger.i(TAG, "file msg $msgSvrId ready (appmsgType=${file.appMsgType}), auto caching")
        // 直接用这条消息的 ContentValues 重建实例, 避免再按 msgSvrId 查库 (可能查不到 / 字段缺失)。
        val msgInfoInstance = try {
            WeMessageApi.convertMsgInfoInstanceFromContentValues(values)
        } catch (e: Exception) {
            WeLogger.e(TAG, "file msg $msgSvrId: failed to build msgInfo from values", e)
            handledSvrIds.remove(msgSvrId)
            return
        }

        scope.launch {
            val path = WeMessageApi.cacheFile(msgInfoInstance)
            if (path != null) {
                WeLogger.i(TAG, "cached file to $path")
            } else {
                WeLogger.e(TAG, "failed to auto-cache file msgSvrId=$msgSvrId")
                // 缓存失败, 允许后续事件重试
                handledSvrIds.remove(msgSvrId)
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var useWhitelistState by remember { mutableStateOf(useWhitelist) }

            AlertDialogContent(
                title = { Text("自动缓存文件") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { useWhitelistState = !useWhitelistState },
                            trailingContent = { Switch(checked = useWhitelistState, onCheckedChange = null) },
                            supportingContent = { Text(if (useWhitelistState) "仅对选中联系人缓存文件" else "对选中联系人跳过缓存文件") },
                            content = { Text(if (useWhitelistState) "黑名单 [> 白名单 <]" else "[> 黑名单 <] 白名单") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
                                val currentList = if (useWhitelistState) whitelist else blacklist

                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = if (useWhitelistState) "选择白名单" else "选择黑名单",
                                        contacts = contacts,
                                        initialSelectedWxIds = currentList,
                                        onDismiss = onDismiss
                                    ) { selectedIds ->
                                        if (useWhitelistState) {
                                            whitelist = selectedIds
                                        } else {
                                            blacklist = selectedIds
                                        }
                                        showToast("已保存 ${selectedIds.size} 个联系人, 重启微信以使更改生效")
                                        onDismiss()
                                    }
                                }
                            },
                            supportingContent = { Text("点击选择联系人") },
                            content = { Text(if (useWhitelistState) "配置白名单" else "配置黑名单") },
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        useWhitelist = useWhitelistState
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
