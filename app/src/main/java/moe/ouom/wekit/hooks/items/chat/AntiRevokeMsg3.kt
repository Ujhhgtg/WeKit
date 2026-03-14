package moe.ouom.wekit.hooks.items.chat

import android.content.ContentValues
import android.database.Cursor
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeDatabaseApi
import moe.ouom.wekit.hooks.sdk.base.WeMessageApi
import moe.ouom.wekit.hooks.sdk.base.WeServiceApi
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.util.regex.Pattern
import kotlin.random.Random

@HookItem(path = "聊天/阻止消息撤回 3", desc = "有撤回提示")
object AntiRevokeMsg3 : SwitchHookItem(), IDexFind {

    private val TAG = nameof(AntiRevokeMsg3)

    private val methodXmlParser by dexMethod()

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodXmlParser.find(dexKit, descriptors = descriptors) {
            searchPackages("com.tencent.mm.sdk.platformtools")
            matcher {
                usingEqStrings("MicroMsg.SDK.XmlParser", "[ %s ]")
            }
        }

        return descriptors
    }

    private val nameRegex by lazy { Pattern.compile("([\"「])(.*?)([」\"])") }

    override fun onLoad() {
        methodXmlParser.toDexMethod {
            hook {
                afterIfEnabled { param ->
                    val args = param.args
                    val xmlContent = args[0] as? String ?: ""
                    val rootTag = args[1] as? String ?: ""

                    if (rootTag != "sysmsg" || !xmlContent.contains("revokemsg")) {
                        return@afterIfEnabled
                    }

                    @Suppress("UNCHECKED_CAST")
                    val resultMap = param.result as MutableMap<String, Any?>
                    val typeKey = $$".sysmsg.$type"

                    if (resultMap[typeKey] == "revokemsg") {
                        val session = resultMap[".sysmsg.revokemsg.session"] as? String?
                            ?: return@afterIfEnabled
                        val replaceMsg = resultMap[".sysmsg.revokemsg.replacemsg"] as? String?
                            ?: return@afterIfEnabled
                        val msgSvrId = resultMap[".sysmsg.revokemsg.newmsgid"] as? String?
                            ?: return@afterIfEnabled

                        if (!replaceMsg.contains("\"") && !replaceMsg.contains("「")) {
                            WeLogger.i(TAG, "outgoing message, skipping")
                            return@afterIfEnabled
                        }

                        resultMap[typeKey] = null

                        val db = WeDatabaseApi.dbInstance!!
                        val cursor = WeDatabaseApi.rawQueryMethod!!.invoke(
                            db,
                            "SELECT createTime FROM message WHERE msgSvrId = ?",
                            arrayOf(msgSvrId)
                        ) as Cursor

                        if (cursor.moveToFirst()) {
                            val originalCreateTime =
                                cursor.getLong(cursor.getColumnIndexOrThrow("createTime"))

                            val matcher = nameRegex.matcher(replaceMsg)

                            val senderName = if (matcher.find()) {
                                matcher.group(2) ?: "未知"
                            } else {
                                "未知"
                            }

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
    }
}
