package moe.ouom.wekit.hooks.item.chat.msg

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge
import java.util.regex.Pattern

@HookItem(path = "聊天与消息/阻止消息撤回 3", desc = "有撤回提示 (没写完)")
class AntiRevokeMsg3 : BaseSwitchFunctionHookItem(), IDexFind {
    companion object {
        private const val TAG = "AntiRevokeMsg3"
    }

    private val methodXmlParser by dexMethod()
    private val classSqliteDb by dexClass()
    private val classMmKernel by dexClass()
    private val classCoreStorage by dexClass()

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodXmlParser.find(dexKit, descriptors = descriptors) {
            searchPackages("com.tencent.mm.sdk.platformtools")
            matcher {
                usingEqStrings("MicroMsg.SDK.XmlParser", "[ %s ]")
            }
        }

        classSqliteDb.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.DBInit", "initSysDB checkini:%b exist:%b db:%s ")
                    }
                }
            }
        }

        classMmKernel.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
                    }
                }
            }
        }

        classCoreStorage.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings("MMKernel.CoreStorage",
                            "CheckData path[%s] blocksize:%s blockcount:%s availcount:%s")
                    }
                }
            }
        }

        return descriptors
    }

    private fun getCoreStorage(): Any {
        val mmKernel = classMmKernel.clazz
        return mmKernel.asResolver()
            .firstMethod {
                returnType = classCoreStorage.clazz
                parameterCount = 0
            }
            .invoke()!!
    }

    private fun getSqliteDatabase(): Any {
        val db = getCoreStorage().asResolver()
            .firstField {
                type(classSqliteDb.clazz)
            }
            .get()!!
        return db.asResolver()
            .firstMethod {
                returnType = "com.tencent.wcdb.database.SQLiteDatabase"
                parameterCount = 0
            }
            .invoke()!!
    }

    private val nameRegex by lazy { Pattern.compile("([\"「])(.*?)([」\"])") }

    override fun entry(classLoader: ClassLoader) {
        methodXmlParser.toDexMethod {
            hook {
                afterIfEnabled { param ->
//                    val args = param.args
//                    val xmlContent = args[0] as? String ?: ""
//                    val rootTag = args[1] as? String ?: ""
//
//                    if (rootTag != "sysmsg" || !xmlContent.contains("revokemsg")) {
//                        return@afterIfEnabled
//                    }
//
//                    @Suppress("UNCHECKED_CAST")
//                    val resultMap = param.result as MutableMap<String, Any?>
//                    val typeKey = ".sysmsg.\$type"
//
//                    if (resultMap[typeKey] == "revokemsg") {
//                        WeLogger.d(TAG, "processing revoked message")
//
//                        val session = resultMap[".sysmsg.revokemsg.session"] as? String
//                        if (session == null) {
//                            WeLogger.d(TAG, "session is null, skipping")
//                            return@afterIfEnabled
//                        }
//
//                        val replaceMsg = resultMap[".sysmsg.revokemsg.replacemsg"] as? String ?: ""
//                        val msgSvrId = resultMap[".sysmsg.revokemsg.newmsgid"] as? String ?: ""
//
//                        WeLogger.d(TAG, replaceMsg)
//
//                        // if (!replaceMsg.contains("\"") && !replaceMsg.contains("「")) {
//                        //     WeLogger.i(TAG, "outgoing message, skipping")
//                        //     return@afterIfEnabled
//                        // }
//
//                        resultMap[typeKey] = null
//                        param.result = resultMap
//
//                        var originalCreateTime: Long = System.currentTimeMillis()
//
//                        val db = getSqliteDatabase()
//                        val cursor = db.asResolver()
//                            .firstMethod { name = "rawQuery" }
//                            .invoke("SELECT createTime FROM message WHERE msgSvrId = ?", arrayOf(msgSvrId)
//                            ) as Cursor
//
//                        cursor.use {
//                            if (it.moveToFirst()) {
//                                originalCreateTime = it.getLong(it.getColumnIndexOrThrow("createTime"))
//                            }
//                        }
//
//                        val matcher = nameRegex.matcher(replaceMsg)
//
//                        val senderName = if (matcher.find()) {
//                            matcher.group(2) ?: "未知"
//                        } else {
//                            "未知"
//                        }
//
//                        val interceptNotice = "'$senderName' 尝试撤回上一条消息 (已阻止)"
//
//                        val contentValues = ContentValues()
//                        contentValues.put("msgid", 0 as Int?)
//                        contentValues.put(
//                            "msgSvrId",
//                            java.lang.Long.valueOf((cyj.f.b() as Long) + j)
//                        )
//                        contentValues.put("type", Integer.valueOf(i))
//                        contentValues.put("status", 3 as Int?)
//                        contentValues.put("createTime", java.lang.Long.valueOf(j))
//                        contentValues.put("talker", str)
//                        contentValues.put("content", str2)
//                        db.asResolver()
//                            .firstMethod {
//                                name = "insertWithOnConflict"
//                                parameters(String::class, String::class, ContentValues::class, Int::class)
//                            }
//                            .invoke(session, interceptNotice, contentValues, originalCreateTime + 1)
//                        WeLogger.d(TAG, "inserted intercept notice into database for session: $session, sender: $senderName")
//                    }
                }
            }
        }
    }
}
