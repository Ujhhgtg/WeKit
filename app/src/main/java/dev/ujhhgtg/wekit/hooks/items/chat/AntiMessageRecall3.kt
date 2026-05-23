package dev.ujhhgtg.wekit.hooks.items.chat

import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/阻止消息撤回 3", description = "有撤回提示")
object AntiMessageRecall3 : SwitchHookItem(), IResolvesDex {

    private val TAG = nameOf(AntiMessageRecall3)

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
                val session = resultMap[".sysmsg.revokemsg.session"] as? String?
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
                            session,
                            interceptNotice,
                            createTime + 1
                        )
                        WeLogger.d(TAG, "blocked message revoke")
                    }
                }

            }
        }
    }
}
