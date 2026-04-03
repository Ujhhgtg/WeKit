package dev.ujhhgtg.wekit.hooks.api.core

import android.database.Cursor
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "API/对话服务", desc = "为其他功能提供对话管理能力")
object WeConversationApi : ApiHookItem(), IResolvesDex {

    private val TAG = nameOf(WeConversationApi)
    val classConversationStorage by dexClass()
    val methodUpdateUnreadByTalker by dexMethod()
    val methodHiddenConvParent by dexMethod()
    val methodGetConvByName by dexMethod()
    private val methodChatroomStorageGetMemberCount by dexMethod()
    private val classChatroomMember by dexClass()

    val conversationStorage by lazy {
        WeServiceApi.storageFeatureService.asResolver()
            .firstMethod {
                returnType = classConversationStorage.clazz
            }
            .invoke()!!
    }

    val chatroomStorage by lazy {
        WeServiceApi.chatroomService.asResolver()
            .firstMethod {
                returnType = methodChatroomStorageGetMemberCount.method.declaringClass
            }
            .invoke()!!
    }

    // this is NOT group 'member'
    // this is the group itself
    fun getGroup(groupId: String): Any {
        return chatroomStorage.asResolver()
            .firstMethod {
                parameters(String::class)
                returnType = classChatroomMember.clazz
            }
            .invoke(groupId)!!
    }

    fun markAllAsRead() {
        val cursor = WeDatabaseApi.rawQueryMethod.invoke(
            WeDatabaseApi.dbInstance,
            "SELECT username FROM rconversation WHERE unReadCount>0 OR unReadMuteCount>0",
            arrayOf<String>()
        ) as Cursor
        while (cursor.moveToNext()) {
            val talker = cursor.getString(0)
            try {
                methodUpdateUnreadByTalker.method.invoke(conversationStorage, talker)
                WeLogger.d(TAG, "marked $talker as read")
            } catch (ex: Exception) {
                WeLogger.w(TAG, "exception while updating unread count for $talker", ex)
            }
        }
        cursor.close()
    }

    fun markAsRead(talker: String) {
        try {
            methodUpdateUnreadByTalker.method.invoke(conversationStorage, talker)
            WeLogger.d(TAG, "marked $talker as read")
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while updating unread count for $talker", ex)
        }
    }

    fun setConversationsVisibility(visible: Boolean, talkers: List<String>) {
        val operation = if (visible) "" else "hidden_conv_parent"
        if (methodHiddenConvParent.method.parameterCount == 4) {
            methodHiddenConvParent.method.invoke(
                conversationStorage,
                talkers.toTypedArray(),
                operation,
                true,
                false
            )
        } else {
            methodHiddenConvParent.method.invoke(
                conversationStorage,
                talkers.toTypedArray(),
                operation
            )
        }
    }

    fun setAllConversationVisibility(visible: Boolean) {
        val cursor = WeDatabaseApi.rawQueryMethod.invoke(
            WeDatabaseApi.dbInstance,
            "SELECT username FROM rconversation",
            arrayOf<String>()
        ) as Cursor
        val talkers = mutableListOf<String>()
        while (cursor.moveToNext()) {
            talkers += cursor.getString(0)
        }
        cursor.close()
        setConversationsVisibility(visible, talkers)
    }

//    private fun debugCursor(cursor: Cursor?) {
//        if (cursor == null) {
//            WeLogger.d("CursorDebug", "Cursor is null")
//            return
//        }
//
//        WeLogger.d("CursorDebug", "Rows: ${cursor.count} | Columns: ${cursor.columnCount}")
//
//        // Save current position to restore it later
//        val initialPosition = cursor.position
//
//        if (cursor.moveToFirst()) {
//            do {
//                val rowString = StringBuilder()
//                for (i in 0 until cursor.columnCount) {
//                    val columnName = cursor.getColumnName(i)
//                    val value = try {
//                        cursor.getString(i) ?: "NULL"
//                    } catch (_: Exception) {
//                        "BLOB/Internal Error"
//                    }
//                    rowString.append("[$columnName: $value] ")
//                }
//                WeLogger.d("CursorDebug", "Row ${cursor.position}: $rowString")
//            } while (cursor.moveToNext())
//        } else {
//            WeLogger.d("CursorDebug", "Cursor is empty")
//        }
//
//        // Restore the cursor to its original position
//        cursor.moveToPosition(initialPosition)
//    }

    fun onlyShowFilteredConversations(queryFilter: String, selectedColumns: String = "username") {
        setAllConversationVisibility(false)
        val cursor = WeDatabaseApi.rawQueryMethod.invoke(
            WeDatabaseApi.dbInstance,
            "SELECT $selectedColumns FROM rconversation $queryFilter",
            arrayOf<String>()
        ) as Cursor
        val visibleTalkers = mutableListOf<String>()
        while (cursor.moveToNext()) {
            visibleTalkers += cursor.getString(0)
        }
        cursor.close()
        setConversationsVisibility(true, visibleTalkers)
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        classConversationStorage.find(dexKit) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("rconversation", "PRAGMA table_info( rconversation)")
            }
        }

        methodUpdateUnreadByTalker.find(dexKit) {
            matcher {
                declaredClass(classConversationStorage.clazz)
                usingEqStrings("MicroMsg.ConversationStorage", "updateUnreadByTalker %s")
            }
        }

        methodHiddenConvParent.find(dexKit) {
            matcher {
                declaredClass(classConversationStorage.clazz)
                usingEqStrings("Update rconversation set parentRef = '", "' where 1 != 1 ")
            }
        }

        methodGetConvByName.find(dexKit) {
            matcher {
                declaredClass(classConversationStorage.clazz)
                usingEqStrings("MicroMsg.ConversationStorage", "get null with username:")
            }
        }

        methodChatroomStorageGetMemberCount.find(dexKit) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.ChatroomStorage", "[getMemberCount] cost:%sms")
            }
        }

        classChatroomMember.find(dexKit) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.ChatRoomMember", "service is null")
            }
        }
    }
}
