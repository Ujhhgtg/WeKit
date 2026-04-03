package dev.ujhhgtg.wekit.hooks.items.chat

import android.content.ContentValues
import android.database.Cursor
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.api.core.WeServiceApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import kotlin.random.Random

@HookItem(path = "聊天/阻止消息撤回 3", desc = "有撤回提示")
object AntiRevokeMsg3 : SwitchHookItem(), IResolvesDex {

    private val TAG = nameOf(AntiRevokeMsg3)

    private val methodXmlParser by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodXmlParser.find(dexKit) {
            searchPackages("com.tencent.mm.sdk.platformtools")
            matcher {
                usingEqStrings("MicroMsg.SDK.XmlParser", "[ %s ]")
            }
        }
    }

    private val nameRegex = Regex("([\"「])(.*?)([」\"])")

    override fun onEnable() {
        methodXmlParser.hookAfter { param ->
            val args = param.args
            val xmlContent = args[0] as? String ?: ""
            val rootTag = args[1] as? String ?: ""

            if (rootTag != "sysmsg" || !xmlContent.contains("revokemsg")) {
                return@hookAfter
            }

            @Suppress("UNCHECKED_CAST")
            val resultMap = param.result as MutableMap<String, Any?>
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

                val db = WeDatabaseApi.dbInstance
                val cursor = WeDatabaseApi.rawQueryMethod.invoke(
                    db,
                    "SELECT createTime FROM message WHERE msgSvrId = ?",
                    arrayOf(msgSvrId)
                ) as Cursor

                if (cursor.moveToFirst()) {
                    val originalCreateTime =
                        cursor.getLong(cursor.getColumnIndexOrThrow("createTime"))

                    val match = nameRegex.find(replaceMsg)

                    val senderName = match?.groupValues?.get(2) ?: "未知"

                    val interceptNotice = "'$senderName' 尝试撤回上一条消息 (已阻止)"

                    val contentValues = ContentValues()
                    contentValues.put("msgid", 0)
                    contentValues.put(
                        "msgSvrId",
                        originalCreateTime + Random.nextInt()
                    )
                    contentValues.put("type", 10000)
                    contentValues.put("status", 3)
                    contentValues.put("createTime", originalCreateTime + 1)
                    contentValues.put("talker", session)
                    contentValues.put("content", interceptNotice)

                    val msgInfo =
                        WeMessageApi.createMsgInfoFromContentValues(contentValues, true)
                    val msgInfoStorage = WeServiceApi.storageFeatureService.asResolver()
                        .firstMethod {
                            parameterCount = 0
                            returnType = WeMessageApi.classMsgInfoStorage.clazz
                        }
                        .invoke()
                    WeMessageApi.methodMsgInfoStorageInsertMessage.method.invoke(
                        msgInfoStorage,
                        msgInfo
                    )
                    WeLogger.d(TAG, "blocked message revoke")
                }
            }
        }
    }
}
