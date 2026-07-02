package dev.ujhhgtg.wekit.features.api.core

import android.annotation.SuppressLint
import android.database.Cursor
import com.tencent.wcdb.DatabaseErrorHandler
import com.tencent.wcdb.database.SQLiteCipherSpec
import com.tencent.wcdb.database.SQLiteDatabase
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.models.SelfProfileField
import dev.ujhhgtg.wekit.features.api.core.models.WeContact
import dev.ujhhgtg.wekit.features.api.core.models.WeGroup
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.features.api.core.models.WeOfficialAccount
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.ChatRoomDataProto
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.lang.reflect.Modifier

/**
 * 微信数据库 API
 */
@OptIn(ExperimentalSerializationApi::class)
@SuppressLint("DiscouragedApi")
@Feature(name = "数据库服务", categories = ["API"], description = "提供数据库直接查询能力")
object WeDatabaseApi : ApiFeature(), IResolveDex {

    private val classMmKernel by dexClass {
        matcher {
            usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
        }
    }
    private val methodGetStorage by dexMethod {
        matcher {
            declaredClass(classMmKernel.clazz)
            modifiers = Modifier.PUBLIC or Modifier.STATIC
            paramCount = 0
            usingStrings("mCoreStorage not initialized!")
        }
    }
    private val classCoreStorage by dexClass {
        matcher {
            usingEqStrings(
                "MMKernel.CoreStorage",
                "CheckData path[%s] blocksize:%s blockcount:%s availcount:%s"
            )
        }
    }
    private val classConfigStorage by dexClass {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("MicroMsg.ConfigStorage", "shouldProcessEvent db is close :%s")
        }
    }
    private val classSqliteDbWrapper by dexClass {
        matcher {
            usingEqStrings("MicroMsg.SqliteDB", "sql is null ")
        }
    }

    lateinit var db: SQLiteDatabase

    val isReady: Boolean get() = ::db.isInitialized

    private val TAG = This.Class.simpleName

    val coreStorage by lazy {
        classMmKernel.reflekt()
            .firstMethod {
                parameterCount = 0
                returnType = classCoreStorage.clazz
            }
            .invokeStatic()!!
    }

    val configStorage by lazy {
        coreStorage.reflekt()
            .firstMethod {
                parameterCount = 0
                returnType = classConfigStorage.clazz
            }
            .invoke()!!
    }

    fun getSelfProfileField(field: SelfProfileField, defValue: Any? = null) =
        configStorage.reflekt()
            .firstMethod {
                parameters(Int::class, Any::class)
                returnType = Any::class
            }
            .invoke(field.code, defValue)!!

    private object SqlStatements {
        // 基础字段 - 联系人查询常用字段
        const val CONTACT_FIELDS = """
            r.username, r.alias, r.conRemark, r.nickname,
            r.pyInitial, r.quanPin, r.encryptUsername, i.reserved2 AS avatarUrl
        """

        // 基础字段 - 群聊查询常用字段
        const val GROUP_FIELDS =
            "r.username, r.nickname, r.pyInitial, r.quanPin, i.reserved2 AS avatarUrl"

        // 基础字段 - 公众号查询常用字段
        const val OFFICIAL_ACCOUNT_FIELDS =
            "r.username, r.alias, r.nickname, i.reserved2 AS avatarUrl"

        // 基础 JOIN 语句
        const val LEFT_JOIN_IMG_FLAG = "LEFT JOIN img_flag i ON r.username = i.username"

        // =========================================
        // 联系人查询
        // =========================================

        /** 所有账号 */
        val CONTACTS = """
            SELECT $CONTACT_FIELDS, r.type
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
        """.trimIndent()

        /** 好友列表（排除群聊和公众号和系统账号和自己和假好友） */
        val FRIENDS = """
            SELECT $CONTACT_FIELDS, r.type
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            WHERE
                (
                    r.encryptUsername != '' -- 是真好友
                    OR
                    r.username = (SELECT value FROM userinfo WHERE id = 2) -- 是我自己
                )
                AND r.verifyFlag = 0
                AND (r.type & 1) != 0
                AND (r.type & 8) = 0
                AND (r.type & 32) = 0
                AND r.username NOT LIKE '%chatroom'
        """.trimIndent()

        /** 查询单个好友（包含微信内部id或自定义微信号alias匹配） */
        fun friend(wxid: String) = """
            SELECT $CONTACT_FIELDS, r.type
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            WHERE
                (r.username = '$wxid' OR r.alias = '$wxid')
                AND (
                    r.encryptUsername != '' -- 是真好友
                    OR
                    r.username = (SELECT value FROM userinfo WHERE id = 2) -- 是我自己
                )
                AND r.verifyFlag = 0
                AND (r.type & 1) != 0
                AND (r.type & 8) = 0
                AND (r.type & 32) = 0
                AND r.username NOT LIKE '%chatroom'
        """.trimIndent()

        /** 按消息量降序获取好友及消息数 */
        fun friendsOrderedByMessageCount(limit: Int) = """
            SELECT COUNT(*) AS msg_count, $CONTACT_FIELDS, r.type
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            JOIN message m ON r.username = m.talker
            WHERE
                (
                    r.encryptUsername != '' -- 是真好友
                    OR
                    r.username = (SELECT value FROM userinfo WHERE id = 2) -- 是我自己
                )
                AND r.verifyFlag = 0
                AND (r.type & 1) != 0
                AND (r.type & 8) = 0
                AND (r.type & 32) = 0
                AND r.username NOT LIKE '%chatroom'
            GROUP BY r.username
            ORDER BY msg_count DESC
            LIMIT $limit
        """.trimIndent()

        // =========================================
        // 群聊查询
        // =========================================

        /** 所有群聊 */
        val GROUPS = """
            SELECT $GROUP_FIELDS
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            WHERE r.username LIKE '%@chatroom'
        """.trimIndent()

        /** 单个群聊 */
        fun group(wxId: String) = """
            SELECT $GROUP_FIELDS
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            WHERE r.username = '$wxId'
        """.trimIndent()

        /** 获取群成员列表 */
        fun groupMembers(idsStr: String) = """
            SELECT $CONTACT_FIELDS
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            WHERE r.username IN ($idsStr)
        """.trimIndent()

        // =========================================
        // 公众号查询
        // =========================================

        /** 所有公众号 */
        val OFFICIAL_LIST = """
            SELECT $OFFICIAL_ACCOUNT_FIELDS
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            WHERE r.username LIKE 'gh_%'
        """.trimIndent()

        // =========================================
        // 消息查询
        // =========================================

        /** 分页获取消息 */
        fun messages(wxid: String, limit: Int, offset: Int) = """
            SELECT msgId, msgSvrId, talker, content, type, createTime, isSend
            FROM message
            WHERE talker='$wxid'
            ORDER BY createTime DESC
            LIMIT $limit OFFSET $offset
        """.trimIndent()

        /**
         * 获取指定会话中特定发送者的消息
         * 支持群聊（通过 content 匹配对方，或通过 isSend 匹配自己）与单聊
         */
        fun messagesFromSender(convId: String, senderId: String, limit: Int, offset: Int) = """
            SELECT msgId, talker, content, type, createTime, isSend
            FROM message
            WHERE talker = '$convId'
              AND (
                  -- 情况 A: 群聊中其他人的消息，内容开头为 'senderId:'
                  (isSend = 0 AND (content LIKE '$senderId:%' OR content LIKE '$senderId:%'))
                  OR
                  -- 情况 B: 自己发送的消息，且请求的 senderId 就是我自己
                  (isSend = 1 AND '$senderId' = (SELECT value FROM userinfo WHERE id = 2))
                  OR
                  -- 情况 C: 单聊，且会话 ID 本身就是发送者 ID
                  ('$convId' = '$senderId')
              )
            ORDER BY createTime DESC
            LIMIT $limit OFFSET $offset
        """.trimIndent()

        /**
         * 获取指定会话中特定发送者的消息
         * 支持群聊（通过 content 匹配对方，或通过 isSend 匹配自己）与单聊
         */
        fun messagesFromSender(convId: String, senderId: String) = """
            SELECT msgId, msgSvrId, talker, content, type, createTime, isSend
            FROM message
            WHERE talker = '$convId'
              AND (
                  -- 情况 A: 群聊中其他人的消息，内容开头为 'senderId:'
                  (isSend = 0 AND (content LIKE '$senderId:%' OR content LIKE '$senderId:%'))
                  OR
                  -- 情况 B: 自己发送的消息，且请求的 senderId 就是我自己
                  (isSend = 1 AND '$senderId' = (SELECT value FROM userinfo WHERE id = 2))
                  OR
                  -- 情况 C: 单聊，且会话 ID 本身就是发送者 ID
                  ('$convId' = '$senderId')
              )
            ORDER BY createTime DESC
        """.trimIndent()

        // =========================================
        // 头像查询
        // =========================================

        /** 每个会话最近一条消息的时间 */
        val LAST_MESSAGE_TIMES = """
            SELECT talker, MAX(createTime) AS lastTime
            FROM message
            GROUP BY talker
        """.trimIndent()

        /** 获取头像 URL */
        fun avatar(wxid: String) = """
            SELECT i.reserved2 AS avatarUrl
            FROM img_flag i
            WHERE i.username = '$wxid'
        """.trimIndent()

        /** 获取群聊成员列表字符串 */
        const val GROUP_MEMBERS = "SELECT memberlist FROM chatroom WHERE chatroomname = '%s'"
    }

    override fun onEnable() {
        methodGetStorage.method.hookAfter {
            if (::db.isInitialized) return@hookAfter

            val storageObj = result ?: return@hookAfter
            initializeDatabase(storageObj)
        }

        if (Preferences.verboseLog) {
            SQLiteDatabase::class.reflekt().firstMethod {
                name = "openDatabase"
                parameters(BString, ByteArray::class, SQLiteCipherSpec::class, SQLiteDatabase.CursorFactory::class, int, DatabaseErrorHandler::class, int)
            }.hookBefore {
                val cipherSpec = args[2] as SQLiteCipherSpec?
                WeLogger.d(
                    TAG,
                    "openDatabase() called with: name=${args[0] as String?}, password=${String(args[1] as? ByteArray? ?: return@hookBefore)}, cipherSpec=${
                        cipherSpec.run {
                            "${this?.hmacAlgorithm},${this?.hmacEnabled},${this?.kdfAlgorithm},${this?.kdfIteration},${this?.pageSize}"
                        }
                    }"
                )
            }
        }
    }

    @Synchronized
    private fun initializeDatabase(storageObj: Any) {
        val wrapperObj = storageObj.reflekt()
            .firstField {
                type {
                    it == classSqliteDbWrapper.clazz ||
                        it == classSqliteDbWrapper.clazz.interfaces[0]
                }
            }.get() ?: return

        db = wrapperObj.reflekt()
            .firstMethod {
                parameterCount = 0
                returnType = "com.tencent.wcdb.database.SQLiteDatabase"
            }.invoke()!! as SQLiteDatabase
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun rawQuery(sql: String, args: Array<Any>? = null): Cursor = db.rawQuery(sql, args)

    @Suppress("NOTHING_TO_INLINE")
    inline fun delete(table: String, conditions: String, args: Array<String>? = null): Int = db.delete(table, conditions, args)

    fun executeQuery(statement: String, args: Array<Any>? = null): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()

        var cursor: Cursor? = null
        try {
            cursor = db.rawQuery(statement, args)
            if (cursor != null && cursor.moveToFirst()) {
                val columnNames = cursor.columnNames
                do {
                    val row = HashMap<String, Any?>()
                    for (i in columnNames.indices) {
                        val type = cursor.getType(i)
                        row[columnNames[i]] = when (type) {
                            Cursor.FIELD_TYPE_NULL -> ""
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                            else -> cursor.getString(i)
                        }
                    }
                    result.add(row)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "sql query failed", e)
        } finally {
            cursor?.close()
        }
        return result
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun execStatement(statement: String, args: Array<Any>? = null) = db.execSQL(statement, args)

    /**
     * 获取【全部联系人】
     * 返回所有账号
     */
    fun getContacts(): List<WeContact> {
        return mapToContacts(executeQuery(SqlStatements.CONTACTS))
    }

    /**
     * 获取【好友】
     */
    fun getFriends(): List<WeContact> {
        return mapToContacts(executeQuery(SqlStatements.FRIENDS))
    }

    /**
     * 获取单条【好友】
     * @param wxId 微信原始 ID (wxid_xxx) 或 用户自定义微信号 (alias)
     * @return 匹配到的 WeContact 对象，若未找到或非好友则返回 null
     */
    fun getFriend(wxId: String): WeContact? {
        if (wxId.isEmpty()) return null
        try {
            val escapedWxid = wxId.replace("'", "''")
            val result = executeQuery(SqlStatements.friend(escapedWxid))

            if (result.isEmpty()) return null

            return mapToContacts(result).firstOrNull()
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get friend details; wxid=$wxId", e)
            return null
        }
    }

    /**
     * 获取按聊天消息总数降序排列的好友列表
     * * @param limit 返回的最大结果数量限制
     * @return 包含 WeContact 到 消息总数 映射的 Map（保持降序排列）
     */
    fun getFriendsOrderedByMessageCount(limit: Int): Map<WeContact, Int> {
        if (limit <= 0) return emptyMap()
        try {
            val result = executeQuery(SqlStatements.friendsOrderedByMessageCount(limit))
            if (result.isEmpty()) return emptyMap()

            val contacts = mapToContacts(result)

            // 使用 mapIndexed 组合并转换为 Map，Kotlin 的 toMap() 会默认保留原本的降序遍历顺序
            return result.mapIndexed { index, row ->
                contacts[index] to row.int("msg_count")
            }.toMap()
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get friends ordered by message count; limit=$limit", e)
            return emptyMap()
        }
    }

    /**
     * 获取单个群聊
     * @param wxId 群聊 wxId（xxx@chatroom）
     * @return WeGroup 对象，若未找到则返回 null
     */
    fun getGroup(wxId: String): WeGroup? {
        if (wxId.isEmpty() || !wxId.isGroupChatWxId) return null
        try {
            val escapedWxid = wxId.replace("'", "''")
            val result = executeQuery(SqlStatements.group(escapedWxid))

            if (result.isEmpty()) return null

            val row = result[0]
            return WeGroup(
                wxId = row.str("username"),
                nickname = row.str("nickname"),
                nicknameShortPinyin = row.str("pyInitial"),
                nicknamePinyin = row.str("quanPin"),
                avatarUrl = row.str("avatarUrl")
            )
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get group; wxid=$wxId", e)
            return null
        }
    }

    fun getDisplayName(convId: String): String {
        if (convId.isEmpty()) error("convId is empty")
        try {
            val escapedWxid = convId.replace("'", "''")
            val isGroup = convId.isGroupChatWxId
            val sql = if (isGroup) {
                "SELECT r.nickname FROM rcontact r WHERE r.username = '$escapedWxid'"
            } else {
                "SELECT r.conRemark, r.nickname FROM rcontact r WHERE r.username = '$escapedWxid'"
            }
            val result = executeQuery(sql)
            if (result.isEmpty()) return convId

            val row = result[0]
            return if (isGroup) {
                val nickname = row.str("nickname")
                nickname.ifEmpty { convId }
            } else {
                val conRemark = row.str("conRemark")
                val nickname = row.str("nickname")
                conRemark.ifEmpty { nickname.ifEmpty { convId } }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get display name; wxid=$convId", e)
            return convId
        }
    }

    /**
     * 获取【群聊】
     */
    fun getGroups(): List<WeGroup> {
        return executeQuery(SqlStatements.GROUPS).map { row ->
            WeGroup(
                wxId = row.str("username"),
                nickname = row.str("nickname"),
                nicknameShortPinyin = row.str("pyInitial"),
                nicknamePinyin = row.str("quanPin"),
                avatarUrl = row.str("avatarUrl")
            )
        }
    }

    /**
     * 获取指定群聊的成员列表
     * @param groupId 群聊ID
     */
    fun getGroupMembers(groupId: String): List<WeContact> {
        if (!groupId.isGroupChatWxId) return emptyList()

        val roomSql = SqlStatements.GROUP_MEMBERS.format(groupId)
        val roomResult = executeQuery(roomSql)

        if (roomResult.isEmpty()) {
            WeLogger.w(TAG, "未找到群聊信息: $groupId")
            return emptyList()
        }

        val memberListStr = roomResult[0].str("memberlist")
        if (memberListStr.isEmpty()) return emptyList()

        val members = memberListStr.split(";").filter { it.isNotEmpty() }
        if (members.isEmpty()) return emptyList()

        val idsStr = members.joinToString(",") { "'$it'" }

        return mapToContacts(executeQuery(SqlStatements.groupMembers(idsStr)))
    }

    /**
     * 获取群成员在群中的群昵称（群备注）
     * 数据来自 chatroom.roomdata protobuf，没有设置群昵称时返回空字符串
     * @param groupId 群聊 wxId（xxx@chatroom）
     * @param memberId 成员 wxId
     * @return 群昵称，未设置时返回空字符串
     */
    fun getGroupMemberDisplayName(groupId: String, memberId: String): String {
        if (!groupId.isGroupChatWxId || memberId.isEmpty()) return ""
        try {
            val cursor = db.rawQuery(
                "SELECT roomdata FROM chatroom WHERE chatroomname = ?",
                arrayOf(groupId)
            )
            cursor.use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val blob = cursor.getBlob(0) ?: return ""
                    val data = ProtoBuf.decodeFromByteArray<ChatRoomDataProto>(blob)
                    return data.members.firstOrNull { it.wxId == memberId }?.displayName ?: ""
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get group member display name; groupId=$groupId, memberId=$memberId", e)
        }
        return ""
    }

    /**
     * 获取邀请指定群成员进群的邀请者 wxId
     * 数据来自 chatroom.roomdata protobuf，群主或无邀请者信息时返回空字符串
     * @param groupId 群聊 wxId（xxx@chatroom）
     * @param memberId 成员 wxId
     * @return 邀请者 wxId，未记录时返回空字符串
     */
    fun getGroupMemberInviter(groupId: String, memberId: String): String {
        if (!groupId.isGroupChatWxId || memberId.isEmpty()) return ""
        try {
            val cursor = db.rawQuery(
                "SELECT roomdata FROM chatroom WHERE chatroomname = ?",
                arrayOf(groupId)
            )
            cursor.use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val blob = cursor.getBlob(0) ?: return ""
                    val data = ProtoBuf.decodeFromByteArray<ChatRoomDataProto>(blob)
                    return data.members.firstOrNull { it.wxId == memberId }?.inviterWxId ?: ""
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get group member inviter; groupId=$groupId, memberId=$memberId", e)
        }
        return ""
    }

    /**
     * 获取【公众号】
     */
    fun getOfficialAccounts(): List<WeOfficialAccount> {
        return executeQuery(SqlStatements.OFFICIAL_LIST).map { row ->
            WeOfficialAccount(
                wxId = row.str("username"),
                nickname = row.str("nickname"),
                avatarUrl = row.str("avatarUrl")
            )
        }
    }

    /**
     * 获取【消息】
     */
    fun getMessages(convId: String, pageIndex: Int = 1, pageSize: Int = 20): List<WeMessage> {
        if (convId.isEmpty()) return emptyList()
        val offset = (pageIndex - 1) * pageSize
        return executeQuery(SqlStatements.messages(convId, pageSize, offset)).map { row ->
            WeMessage(
                msgId = row.long("msgId"),
                msgSvrId = row.long("msgSvrId"),
                talker = row.str("talker"),
                content = row.str("content"),
                typeCode = row.int("type"),
                createTime = row.long("createTime"),
                isSend = row.int("isSend")
            )
        }
    }

    /**
     * 获取指定会话中特定发送者的【消息】
     * @param convId 会话 ID（单聊为对方 wxid，群聊为 xxx@chatroom）
     * @param senderId 发送者 ID（wxid）
     */
    fun getMessagesFromSender(
        convId: String,
        senderId: String,
    ): List<WeMessage> {
        if (convId.isEmpty() || senderId.isEmpty()) return emptyList()

        try {
            // 防止 SQL 注入转义
            val escapedConvId = convId.replace("'", "''")
            val escapedSenderId = senderId.replace("'", "''")

            val sql = SqlStatements.messagesFromSender(escapedConvId, escapedSenderId)
            return executeQuery(sql).map { row ->
                WeMessage(
                    msgId = row.long("msgId"),
                    msgSvrId = row.long("msgSvrId"),
                    talker = row.str("talker"),
                    content = row.str("content"),
                    typeCode = row.int("type"),
                    createTime = row.long("createTime"),
                    isSend = row.int("isSend")
                )
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get messages from sender; convId=$convId, senderId=$senderId", e)
            return emptyList()
        }
    }

    /**
     * 获取每个会话最近一条消息的时间
     * @return 会话 wxId 到最近消息时间（毫秒时间戳）的映射
     */
    fun getLastMessageTimes(): Map<String, Long> {
        return try {
            executeQuery(SqlStatements.LAST_MESSAGE_TIMES).associate { row ->
                row.str("talker") to row.long("lastTime")
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get last message times", e)
            emptyMap()
        }
    }

    /**
     * 获取头像
     */
    fun getAvatarUrl(wxid: String): String {
        if (wxid.isEmpty()) return ""
        val result = executeQuery(SqlStatements.avatar(wxid))
        return if (result.isNotEmpty()) {
            result[0]["avatarUrl"] as? String ?: ""
        } else {
            ""
        }
    }

    private fun mapToContacts(data: List<Map<String, Any?>>): List<WeContact> {
        return data.map { row ->
            WeContact(
                wxId = row.str("username"),
                nickname = row.str("nickname"),
                customWxId = row.str("alias"),
                remarkName = row.str("conRemark"),
                initialNickname = row.str("pyInitial"),
                nicknamePinyin = row.str("quanPin"),
                avatarUrl = row.str("avatarUrl"),
                encryptedUsername = row.str("encryptUsername"),
                type = row.int("type")
            )
        }
    }

    private fun Map<String, Any?>.str(key: String): String = this[key]?.toString() ?: ""

    private fun Map<String, Any?>.long(key: String): Long {
        return when (val v = this[key]) {
            is Long -> v
            is Int -> v.toLong()
            else -> 0L
        }
    }

    private fun Map<String, Any?>.int(key: String): Int {
        return when (val v = this[key]) {
            is Int -> v
            is Long -> v.toInt()
            else -> 0
        }
    }
}
