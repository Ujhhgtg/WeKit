package dev.ujhhgtg.wekit.hooks.items.chat

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/阻止消息撤回 3", description = "有撤回提示")
object AntiMessageRecall3 : ClickableHookItem(), IResolvesDex {

    private val TAG = This.Class.simpleName

    private var RECALL_OUTGOING: Boolean
        get() = WePrefs.getBoolOrFalse("recall_outgoing")
        set(value) {
            WePrefs.putBool("recall_outgoing", value)
        }

    private val methodXmlParser by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodXmlParser.find(dexKit) {
            searchPackages("com.tencent.mm.sdk.platformtools")
            matcher {
                usingEqStrings("MicroMsg.SDK.XmlParser", "[ %s ]")
            }
        }
    }

    private val NAME_REGEX = Regex("([\"「])(.*?)([」\"])")

    override fun onEnable() {
        methodXmlParser.hookAfter {
            val args = args
            val xmlContent = args[0] as? String ?: ""
            val rootTag = args[1] as? String ?: ""

            if (rootTag != "sysmsg" || !xmlContent.contains("revokemsg")) {
                return@hookAfter
            }

            @Suppress("UNCHECKED_CAST")
            val resultMap = result as MutableMap<String, Any?>
            val typeKey = $$".sysmsg.$type"

            if (resultMap[typeKey] == "revokemsg") {
                val talker = resultMap[".sysmsg.revokemsg.session"] as? String?
                    ?: return@hookAfter
                val replaceMsg = resultMap[".sysmsg.revokemsg.replacemsg"] as? String?
                    ?: return@hookAfter
                val msgSvrId = resultMap[".sysmsg.revokemsg.newmsgid"] as? String?
                    ?: return@hookAfter

                if (!replaceMsg.contains("\"") && !replaceMsg.contains("「")) {
                    WeLogger.i(TAG, "outgoing message, skipping")
                    return@hookAfter
                }

                resultMap[typeKey] = null

                val cursor = WeDatabaseApi.rawQuery(
                    "SELECT createTime FROM message WHERE msgSvrId = ?",
                    arrayOf(msgSvrId)
                )

                cursor.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val createTime =
                            cursor.getLong(cursor.getColumnIndexOrThrow("createTime"))
                        val match = NAME_REGEX.find(replaceMsg)
                        val senderName = match?.groupValues?.get(2) ?: "未知"
                        val interceptNotice = "「$senderName」尝试撤回上一条消息 (已阻止)"
                        WeMessageApi.createSimpleMsgInfoAndInsert(
                            10000,
                            talker,
                            interceptNotice,
                            createTime + 1
                        )
                        WeLogger.d(TAG, "blocked message revoke")
                    }
                }
            }
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("阻止消息撤回 3") },
                text = {
                    var recallOutgoing by remember { mutableStateOf(RECALL_OUTGOING) }

                    ListItem(
                        headlineContent = { Text("防撤回自己的消息") },
                        supportingContent = { Text("是否对自己发出的消息也生效") },
                        trailingContent = {
                            Switch(checked = recallOutgoing, onCheckedChange = {
                                recallOutgoing = it
                                RECALL_OUTGOING = it
                            })
                        },
                        modifier = Modifier.clickable { recallOutgoing = !recallOutgoing }
                    )
                })
        }
    }
}
