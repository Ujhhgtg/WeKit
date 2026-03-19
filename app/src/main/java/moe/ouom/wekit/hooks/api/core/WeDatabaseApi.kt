package moe.ouom.wekit.hooks.api.core

import android.annotation.SuppressLint
import android.database.Cursor
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.abc.IResolvesDex
import moe.ouom.wekit.hooks.api.core.model.SelfProfileField
import moe.ouom.wekit.hooks.api.core.model.WeContact
import moe.ouom.wekit.hooks.api.core.model.WeGroup
import moe.ouom.wekit.hooks.api.core.model.WeMessage
import moe.ouom.wekit.hooks.api.core.model.WeOfficial
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.utils.logging.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信数据库 API
 */
@SuppressLint("DiscouragedApi")
@HookItem(path = "API/数据库服务", desc = "提供数据库直接查询能力")
object WeDatabaseApi : ApiHookItem(), IResolvesDex {

    val classMmKernel by dexClass()
    private val methodGetStorage by dexMethod()
    private val classCoreStorage by dexClass()
    private val classConfigStorage by dexClass()
    private val classSqliteDbWrapper by dexClass()

    lateinit var dbInstance: Any
    lateinit var rawQueryMethod: Method
    lateinit var execStatementMethod: Method

    private val TAG = nameof(WeDatabaseApi)

    val coreStorage by lazy {
        classMmKernel.clazz.asResolver()
            .firstMethod {
                parameterCount = 0
                returnType = classCoreStorage.clazz
            }
            .invoke()!!
    }

    val configStorage by lazy {
        coreStorage.asResolver()
            .firstMethod {
                parameterCount = 0
                returnType = classConfigStorage.clazz
            }
            .invoke()!!
    }

    fun getSelfProfileField(field: SelfProfileField) =
        configStorage.asResolver()
            .firstMethod {
                parameters(Int::class, Any::class)
                returnType = Any::class
            }
            .invoke(field.code, null)!!

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

        /** 所有人类账号（排除群聊和公众号和系统账号） */
        val CONTACTS = """
            SELECT $CONTACT_FIELDS, r.type
            FROM rcontact r 
            $LEFT_JOIN_IMG_FLAG 
            WHERE 
                r.username != 'filehelper'
                AND r.verifyFlag = 0 
                AND (r.type & 1) != 0
                AND (r.type & 8) = 0
                AND (r.type & 32) = 0
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
            SELECT msgId, talker, content, type, createTime, isSend 
            FROM message 
            WHERE talker='$wxid' 
            ORDER BY createTime DESC 
            LIMIT $limit OFFSET $offset
        """.trimIndent()

        // =========================================
        // 头像查询
        // =========================================

        /** 获取头像 URL */
        fun avatar(wxid: String) = """
            SELECT i.reserved2 AS avatarUrl 
            FROM img_flag i 
            WHERE i.username = '$wxid'
        """.trimIndent()

        /** 获取群聊成员列表字符串 */
        const val GROUP_MEMBERS = "SELECT memberlist FROM chatroom WHERE chatroomname = '%s'"
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        classMmKernel.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
            }
        }

        classCoreStorage.find(dexKit, descriptors) {
            matcher {
                usingEqStrings(
                    "MMKernel.CoreStorage",
                    "CheckData path[%s] blocksize:%s blockcount:%s availcount:%s"
                )
            }
        }

        methodGetStorage.find(dexKit, descriptors, true) {
            matcher {
                declaredClass(classMmKernel.clazz)
                modifiers = Modifier.PUBLIC or Modifier.STATIC
                paramCount = 0
                usingStrings("mCoreStorage not initialized!")
            }
        }

        classConfigStorage.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.ConfigStorage", "shouldProcessEvent db is close :%s")
            }
        }

        classSqliteDbWrapper.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("MicroMsg.SqliteDB", "sql is null ")
            }
        }

        return descriptors
    }

    override fun onEnable() {
        methodGetStorage.method.hookAfter { param ->
            if (::dbInstance.isInitialized) return@hookAfter
            val storageObj = param.result ?: return@hookAfter

            initializeDatabase(storageObj)
        }

//        "com.tencent.wcdb.database.SQLiteDatabase".toClass().asResolver()
//            .firstMethod { name = "open" }
//            .hookBefore { param ->
//                val keyBytes = param.args[0] as? ByteArray
//                val cipherSpec = param.args[1] ?: return@hookBefore
//
//                val dbPath = param.thisObject.asResolver().firstMethod { name = "getPath" }.invoke()!! as String
//
//                if (keyBytes != null) {
//                    val hexOfBytes = keyBytes.joinToString("") { "%02x".format(it) }
//                    val asAscii = String(keyBytes, Charsets.UTF_8)
//                    WeLogger.d(TAG, "WCDB path=$dbPath")
//                    WeLogger.d(TAG, "WCDB key_hex=$hexOfBytes")
//                    WeLogger.d(TAG, "WCDB key_ascii=$asAscii")
//                }
//
//                cipherSpec.asResolver().apply {
//                    val pageSize = firstField { name = "pageSize" }.get()!! as Int
//                    val kdfIter = firstField { name = "kdfIteration" }.get()!! as Int
//                    val hmacAlgo = firstField { name = "hmacAlgorithm" }.get()!! as Int
//                    val kdfAlgo = firstField { name = "kdfAlgorithm" }.get()!! as Int
//                    val hmacEnabled = firstField { name = "hmacEnabled" }.get()!! as Boolean
//                    WeLogger.d(TAG, "WCDB pageSize=$pageSize kdfIter=$kdfIter hmacAlgo=$hmacAlgo kdfAlgo=$kdfAlgo hmacEnabled=$hmacEnabled")
//                }
//            }
    }

    @Synchronized
    private fun initializeDatabase(storageObj: Any): Boolean {
        return runCatching {
            // 在 Storage 中寻找 Wrapper
            val wrapperObj = storageObj.asResolver()
                .firstField {
                    type = classSqliteDbWrapper.clazz
                }.get()!!

            // 获取 DB 实例
            this.dbInstance = wrapperObj.asResolver()
                .firstMethod {
                    parameterCount = 0
                    returnType = "com.tencent.wcdb.database.SQLiteDatabase"
                }.invoke()!!

            rawQueryMethod = dbInstance.asResolver()
                .firstMethod {
                    name = "rawQuery"
                    parameterCount = 2
                }.self

            execStatementMethod = dbInstance.asResolver()
                .firstMethod {
                    name = "execSQL"
                    parameters(String::class)
                }.self

            return true
        }
            .onSuccess { WeLogger.i(TAG, "db init success") }
            .onFailure { e -> WeLogger.e("exception during db init", e) }
            .getOrDefault(false)
    }

    fun executeQuery(sql: String, args: Array<Any>? = null): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()

        var cursor: Cursor? = null
        try {
            cursor = rawQueryMethod.invoke(dbInstance, sql, args) as? Cursor
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
            WeLogger.e(TAG, "SQL Query 执行异常", e)
        } finally {
            cursor?.close()
        }
        return result
    }

    fun execStatement(sql: String): Boolean {
        try {
            execStatementMethod.invoke(dbInstance, sql)
            return true
        } catch (e: Exception) {
            WeLogger.e(TAG, "SQL Update 执行异常", e)
        }
        return false
    }

    /**
     * 获取【全部联系人】
     * 返回所有人类账号（包含好友、陌生人、自己），但排除群和公众号
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

    fun getDisplayName(convId: String): String {
        if (convId.isEmpty()) error("convId is empty")
        try {
            val escapedWxid = convId.replace("'", "''")
            val isGroup = convId.endsWith("@chatroom")
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
        if (!groupId.endsWith("@chatroom")) return emptyList()

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
     * 获取【公众号】
     */
    fun getOfficialAccounts(): List<WeOfficial> {
        return executeQuery(SqlStatements.OFFICIAL_LIST).map { row ->
            WeOfficial(
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
                talker = row.str("talker"),
                content = row.str("content"),
                type = row.int("type"),
                createTime = row.long("createTime"),
                isSend = row.int("isSend")
            )
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
                customWxid = row.str("alias"),
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