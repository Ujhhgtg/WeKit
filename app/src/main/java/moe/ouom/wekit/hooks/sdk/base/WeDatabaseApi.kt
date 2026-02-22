package moe.ouom.wekit.hooks.sdk.base

import android.annotation.SuppressLint
import android.database.Cursor
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.model.WeContact
import moe.ouom.wekit.hooks.sdk.base.model.WeGroup
import moe.ouom.wekit.hooks.sdk.base.model.WeMessage
import moe.ouom.wekit.hooks.sdk.base.model.WeOfficial
import moe.ouom.wekit.utils.common.SyncUtils
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信数据库 API
 */
@SuppressLint("DiscouragedApi")
@HookItem(path = "API/数据库服务", desc = "提供数据库直接查询能力")
object WeDatabaseApi : ApiHookItem(), IDexFind {
    // MMKernel 类
    private val dexClassKernel by dexClass()

    // Kernel.storage()
    private val dexMethodGetStorage by dexMethod()

    // -------------------------------------------------------------------------------------
    // 运行时缓存
    // -------------------------------------------------------------------------------------
    private var getStorageMethod: Method? = null

    // 运行时缓存
    @Volatile
    private var wcdbInstance: Any? = null
    private var rawQueryMethod: Method? = null

    private const val TAG = "WeDatabaseApi"
    private const val WCDB_CLASS_NAME = "com.tencent.wcdb.database.SQLiteDatabase"

    // =============================================================================
    // SQL 语句集中管理
    // =============================================================================
    private object SQL {
        // 基础字段 - 联系人查询常用字段
        const val CONTACT_FIELDS = """
            r.username, r.alias, r.conRemark, r.nickname, 
            r.pyInitial, r.quanPin, r.encryptUsername, i.reserved2 AS avatarUrl
        """

        // 基础字段 - 群聊查询常用字段
        const val CHATROOM_FIELDS = "r.username, r.nickname, r.pyInitial, r.quanPin, i.reserved2 AS avatarUrl"

        // 基础字段 - 公众号查询常用字段
        const val OFFICIAL_FIELDS = "r.username, r.alias, r.nickname, i.reserved2 AS avatarUrl"

        // 基础 JOIN 语句
        const val LEFT_JOIN_IMG_FLAG = "LEFT JOIN img_flag i ON r.username = i.username"

        // =========================================
        // 联系人查询
        // =========================================

        /** 所有人类账号（排除群聊和公众号和系统账号） */
        val ALL_CONNECTS = """
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
        val CONTACT_LIST = """
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
        val CHATROOM_LIST = """
            SELECT $CHATROOM_FIELDS
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
            SELECT $OFFICIAL_FIELDS
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

        /** 获取头像URL */
        fun avatar(wxid: String) = """
            SELECT i.reserved2 AS avatarUrl 
            FROM img_flag i 
            WHERE i.username = '$wxid'
        """.trimIndent()

        /** 获取群聊成员列表字符串 */
        val CHATROOM_MEMBERS = "SELECT memberlist FROM chatroom WHERE chatroomname = '%s'"
    }

    @SuppressLint("NonUniqueDexKitData")
    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        try {
            WeLogger.i(TAG, ">>>> 校验数据库 API 缓存 (Process: ${SyncUtils.getProcessName()}) <<<<")

            // 定位 MMKernel
            dexClassKernel.find(dexKit, descriptors) {
                matcher {
                    usingStrings("MicroMsg.MMKernel", "Initialize skeleton")
                }
            }

            val kernelDesc = descriptors[dexClassKernel.key]
            if (kernelDesc != null) {
                // 定位 storage() 方法
                dexMethodGetStorage.find(dexKit, descriptors, true) {
                    matcher {
                        declaredClass = kernelDesc
                        modifiers = Modifier.PUBLIC or Modifier.STATIC
                        paramCount = 0
                        usingStrings("mCoreStorage not initialized!")
                    }
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "DexKit 查找流程异常", e)
        }
        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        try {
            getStorageMethod = dexMethodGetStorage.method

            if (getStorageMethod != null) {
                hookAfter(getStorageMethod!!) { param ->
                    val storageObj = param.result ?: return@hookAfter

                    if (wcdbInstance == null) {
                        initializeDatabase(storageObj)
                    }
                }
            }

        } catch (e: Exception) {
            WeLogger.e(TAG, "Entry 初始化异常", e)
        }
    }

    /**
     * 核心初始化逻辑
     */
    @Synchronized
    private fun initializeDatabase(storageObj: Any): Boolean {
        if (wcdbInstance != null && rawQueryMethod != null) return true

        try {
            // 在 Storage 中寻找 Wrapper
            val wrapperObj = findDbWrapper(storageObj)
            if (wrapperObj == null) {
                WeLogger.w(TAG, "初始化: 未找到 Wrapper")
                return false
            }

            // 获取 WCDB 实例
            val dbInstance = getWcdbFromWrapper(wrapperObj)
            if (dbInstance == null) {
                WeLogger.w(TAG, "初始化: 未找到 WCDB 实例")
                return false
            }

            // 获取 rawQuery 方法并缓存
            val rawQuery = findRawQueryMethod(dbInstance.javaClass)
            if (rawQuery != null) {
                wcdbInstance = dbInstance
                rawQueryMethod = rawQuery
                return true
            }

        } catch (e: Exception) {
            WeLogger.e(TAG, "数据库初始化失败", e)
        }
        return false
    }

    /**
     * 快速查找 Wrapper
     */
    private fun findDbWrapper(storageObj: Any): Any? {
        val fields = storageObj.javaClass.declaredFields
        for (field in fields) {
            try {
                field.isAccessible = true
                val obj = field.get(storageObj) ?: continue

                val typeName = obj.javaClass.name
                if (typeName.startsWith("java.") || typeName.startsWith("android.")) continue

                if (checkMethodFeature(obj) || checkStringFeature(obj)) {
                    return obj
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * 检查是否有 "MicroMsg.SqliteDB" 字符串
     */
    private fun checkStringFeature(obj: Any): Boolean {
        return try {
            obj.javaClass.declaredFields.any {
                it.isAccessible = true
                it.type == String::class.java && it.get(obj) == "MicroMsg.SqliteDB"
            }
        } catch (_: Exception) { false }
    }

    /**
     * 检查是否有无参方法返回 SQLiteDatabase
     */
    private fun checkMethodFeature(obj: Any): Boolean {
        return try {
            obj.javaClass.declaredMethods.any {
                it.parameterCount == 0 && it.returnType.name == WCDB_CLASS_NAME
            }
        } catch (_: Exception) { false }
    }

    private fun getWcdbFromWrapper(wrapperObj: Any): Any? {
        val methods = wrapperObj.javaClass.declaredMethods
        for (method in methods) {
            if (method.parameterCount == 0 &&
                method.returnType.name == WCDB_CLASS_NAME) {
                try {
                    method.isAccessible = true
                    val db = method.invoke(wrapperObj)
                    if (db != null) return db
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun findRawQueryMethod(clazz: Class<*>): Method? {
        try {
            return clazz.getMethod("rawQuery", String::class.java, Array<Any>::class.java)
        } catch (_: Exception) {}
        try {
            return clazz.getMethod("rawQuery", String::class.java, Array<String>::class.java)
        } catch (_: Exception) {}
        return null
    }

    // -------------------------------------------------------------------------------------
    // 业务接口
    // -------------------------------------------------------------------------------------

    /**
     * 通用查询执行器
     */
    fun executeQuery(sql: String): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()

        var cursor: Cursor? = null
        try {
            cursor = rawQueryMethod?.invoke(wcdbInstance, sql, null) as? Cursor
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
            WeLogger.e(TAG, "SQL执行异常: ${e.message}")
        } finally {
            cursor?.close()
        }
        return result
    }

    /**
     * 获取【全部联系人】
     * 返回所有人类账号（包含好友、陌生人、自己），但排除群和公众号
     */
    fun getAllConnects(): List<WeContact> {
        return mapToContact(executeQuery(SQL.ALL_CONNECTS))
    }

    /**
     * 获取【好友】
     */
    fun getContactList(): List<WeContact> {
        return mapToContact(executeQuery(SQL.CONTACT_LIST))
    }

    /**
     * 获取【群聊】
     */
    fun getChatroomList(): List<WeGroup> {
        return executeQuery(SQL.CHATROOM_LIST).map { row ->
            WeGroup(
                username = row.str("username"),
                nickname = row.str("nickname"),
                pyInitial = row.str("pyInitial"),
                quanPin = row.str("quanPin"),
                avatarUrl = row.str("avatarUrl")
            )
        }
    }

    /**
     * 获取指定群聊的成员列表
     * @param chatroomId 群聊ID
     */
    fun getGroupMembers(chatroomId: String): List<WeContact> {
        if (!chatroomId.endsWith("@chatroom")) return emptyList()

        val roomSql = SQL.CHATROOM_MEMBERS.format(chatroomId)
        val roomResult = executeQuery(roomSql)

        if (roomResult.isEmpty()) {
            WeLogger.w(TAG, "未找到群聊信息: $chatroomId")
            return emptyList()
        }

        val memberListStr = roomResult[0].str("memberlist")
        if (memberListStr.isEmpty()) return emptyList()

        val members = memberListStr.split(";").filter { it.isNotEmpty() }
        if (members.isEmpty()) return emptyList()

        val idsStr = members.joinToString(",") { "'$it'" }

        return mapToContact(executeQuery(SQL.groupMembers(idsStr)))
    }

    /**
     * 获取【公众号】
     */
    fun getOfficialAccountList(): List<WeOfficial> {
        return executeQuery(SQL.OFFICIAL_LIST).map { row ->
            WeOfficial(
                username = row.str("username"),
                nickname = row.str("nickname"),
                alias = row.str("alias"),
                signature = "暂无签名",
                avatarUrl = row.str("avatarUrl")
            )
        }
    }

    /**
     * 获取【消息】
     */
    fun getMessages(wxid: String, page: Int = 1, pageSize: Int = 20): List<WeMessage> {
        if (wxid.isEmpty()) return emptyList()
        val offset = (page - 1) * pageSize
        return executeQuery(SQL.messages(wxid, pageSize, offset)).map { row ->
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
        val result = executeQuery(SQL.avatar(wxid))
        return if (result.isNotEmpty()) {
            result[0]["avatarUrl"] as? String ?: ""
        } else {
            ""
        }
    }

    private fun mapToContact(data: List<Map<String, Any?>>): List<WeContact> {
        return data.map { row ->
            WeContact(
                username = row.str("username"),
                nickname = row.str("nickname"),
                alias = row.str("alias"),
                conRemark = row.str("conRemark"),
                pyInitial = row.str("pyInitial"),
                quanPin = row.str("quanPin"),
                avatarUrl = row.str("avatarUrl"),
                encryptUserName = row.str("encryptUsername")
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