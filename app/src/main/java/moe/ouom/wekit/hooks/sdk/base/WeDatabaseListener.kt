package moe.ouom.wekit.hooks.sdk.base

import android.annotation.SuppressLint
import android.content.ContentValues
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.config.WeConfig
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.constants.MMVersion
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.utils.Initiator.loadClass
import moe.ouom.wekit.utils.log.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("DiscouragedApi")
@HookItem(path = "API/数据库监听服务", desc = "为其他功能提供数据库插入、更新监听与查询能力")
object WeDatabaseListener : ApiHookItem() {

    // 定义独立的接口
    interface IInsertListener {
        fun onInsert(table: String, values: ContentValues)
    }

    interface IUpdateListener {
        fun onUpdate(table: String, values: ContentValues): Boolean
    }

    interface IQueryListener {
        fun onQuery(sql: String): String?
    }

    private const val TAG = "WeDatabaseApi"

    private val insertListeners = CopyOnWriteArrayList<IInsertListener>()
    private val updateListeners = CopyOnWriteArrayList<IUpdateListener>()
    private val queryListeners = CopyOnWriteArrayList<IQueryListener>()
    fun addListener(listener: Any) {
        val addedTypes = mutableListOf<String>()

        if (listener is IInsertListener) {
            insertListeners.add(listener)
            addedTypes.add("INSERT")
        }
        if (listener is IUpdateListener) {
            updateListeners.add(listener)
            addedTypes.add("UPDATE")
        }
        if (listener is IQueryListener) {
            queryListeners.add(listener)
            addedTypes.add("QUERY")
        }

        // 只有实现了至少一个接口才打印日志
        if (addedTypes.isNotEmpty()) {
            WeLogger.i(TAG, "监听器已添加: ${listener.javaClass.simpleName} [${addedTypes.joinToString()}]")
        }
    }

    fun removeListener(listener: Any) {
        var removed = false

        if (listener is IInsertListener) {
            removed = insertListeners.remove(listener) || removed
        }
        if (listener is IUpdateListener) {
            removed = updateListeners.remove(listener) || removed
        }
        if (listener is IQueryListener) {
            removed = queryListeners.remove(listener) || removed
        }

        if (removed) {
            WeLogger.i(TAG, "监听器已移除: ${listener.javaClass.simpleName}")
        }
    }

    override fun entry(classLoader: ClassLoader) {
        hookDatabaseInsert()
        hookDatabaseUpdate()
        hookDatabaseQuery()
    }

    override fun unload(classLoader: ClassLoader) {
        insertListeners.clear()
        updateListeners.clear()
        queryListeners.clear()
    }

    // ==================== 私有辅助方法 ====================

    private fun shouldLogDatabase(): Boolean {
        val config = WeConfig.getDefaultConfig()
        return config.getBooleanOrFalse(Constants.PrekVerboseLog) &&
                config.getBooleanOrFalse(Constants.PrekDatabaseVerboseLog)
    }

    private fun formatArgs(args: Array<out Any?>): String {
        return args.mapIndexed { index, arg ->
            "arg[$index](${arg?.javaClass?.simpleName ?: "null"})=$arg"
        }.joinToString(", ")
    }

    private fun logWithStack(methodName: String, table: String, args: Array<out Any?>, result: Any? = null) {
        if (!shouldLogDatabase()) return

        val argsInfo = formatArgs(args)
        val resultStr = if (result != null) ", result=$result" else ""
        val stackStr = ", stack=${WeLogger.getStackTraceString()}"

        WeLogger.logChunkedD(TAG, "[$methodName] table=$table$resultStr, args=[$argsInfo]$stackStr")
    }

    // ==================== Insert Hook ====================

    private fun hookDatabaseInsert() {
        try {
            val clsSQLite = loadClass(Constants.CLAZZ_SQLITE_DATABASE)
            val method = XposedHelpers.findMethodExact(
                clsSQLite,
                "insertWithOnConflict",
                String::class.java,
                String::class.java,
                ContentValues::class.java,
                Int::class.javaPrimitiveType
            )

            hookAfter(method) { param ->
                try {
                    if (insertListeners.isEmpty()) return@hookAfter

                    val table = param.args[0] as String
                    val values = param.args[2] as ContentValues
                    val result = param.result

                    logWithStack("Insert", table, param.args, result)
                    insertListeners.forEach { it.onInsert(table, values) }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "Insert dispatch failed", e)
                }
            }
            WeLogger.i(TAG, "Insert hook success")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Hook insert failed", e)
        }
    }

    // ==================== Update Hook ====================

    private fun hookDatabaseUpdate() {
        try {
            val isPlay = HostInfo.isGooglePlayVersion
            val version = HostInfo.getVersionCode()
            val isNewVersion = (!isPlay && version >= MMVersion.MM_8_0_43) ||
                    (isPlay && version >= MMVersion.MM_8_0_48_Play)

            val clsName = if (isNewVersion) Constants.CLAZZ_COMPAT_SQLITE_DATABASE else Constants.CLAZZ_SQLITE_DATABASE
            val clsSQLite = loadClass(clsName)

            val method = XposedHelpers.findMethodExact(
                clsSQLite,
                "updateWithOnConflict",
                String::class.java,
                ContentValues::class.java,
                String::class.java,
                Array<String>::class.java,
                Int::class.javaPrimitiveType
            )

            hookBefore(method) { param ->
                try {
                    if (updateListeners.isEmpty()) return@hookBefore

                    val table = param.args[0] as String
                    val values = param.args[1] as ContentValues
                    val whereClause = param.args[2] as? String
                    @Suppress("UNCHECKED_CAST") val whereArgs = param.args[3] as? Array<String>

                    logWithStack("Update", table, param.args)

                    // 如果有任何一个监听器返回 true，则阻止更新
                    val shouldBlock = updateListeners.any { it.onUpdate(table, values) }

                    if (shouldBlock) {
                        param.result = 0 // 返回0表示没有行被更新
                        WeLogger.d(TAG, "[Update] 被监听器阻止, table=$table, stack=${WeLogger.getStackTraceString()}")
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "Update dispatch failed", e)
                }
            }
            WeLogger.i(TAG, "Update hook success")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Hook update failed", e)
        }
    }

    // ==================== Query Hook ====================

    private fun hookDatabaseQuery() {
        try {
            val isPlay = HostInfo.isGooglePlayVersion
            val version = HostInfo.getVersionCode()
            val isNewVersion = (!isPlay && version >= MMVersion.MM_8_0_43) ||
                    (isPlay && version >= MMVersion.MM_8_0_48_Play)

            if (isNewVersion) {
                hookNewVersionQuery()
            } else {
                hookOldVersionQuery()
            }
            WeLogger.i(TAG, "Query hook success")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Hook query failed", e)
        }
    }

    private fun hookNewVersionQuery() {
        val clsSQLite = loadClass(Constants.CLAZZ_COMPAT_SQLITE_DATABASE)
        val method = XposedHelpers.findMethodExact(
            clsSQLite,
            "rawQuery",
            String::class.java,
            Array<Any>::class.java,
        )

        hookBefore(method) { param ->
            try {
                if (queryListeners.isEmpty()) return@hookBefore

                val sql = param.args[0] as? String ?: return@hookBefore
                var currentSql = sql

                logWithStack("rawQuery", "N/A", param.args)

                queryListeners.forEach { listener ->
                    listener.onQuery(currentSql)?.let { currentSql = it }
                }

                if (currentSql != sql) {
                    param.args[0] = currentSql
                    WeLogger.d(TAG, "[rawQuery] SQL被修改: $sql -> $currentSql, stack=${WeLogger.getStackTraceString()}")
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "New version query dispatch failed", e)
            }
        }
    }

    private fun hookOldVersionQuery() {
        val clsSQLite = loadClass(Constants.CLAZZ_SQLITE_DATABASE)
        val cursorFactoryClass = loadClass("com.tencent.wcdb.database.SQLiteDatabase\$CursorFactory")
        val cancellationSignalClass = loadClass("com.tencent.wcdb.support.CancellationSignal")

        val method = XposedHelpers.findMethodExact(
            clsSQLite,
            "rawQueryWithFactory",
            cursorFactoryClass,
            String::class.java,
            Array<String>::class.java,
            String::class.java,
            cancellationSignalClass
        )

        hookBefore(method) { param ->
            try {
                if (queryListeners.isEmpty()) return@hookBefore

                val sql = param.args[1] as? String ?: return@hookBefore
                var currentSql = sql

                logWithStack("rawQueryWithFactory", param.args[3] as? String ?: "N/A", param.args)

                queryListeners.forEach { listener ->
                    listener.onQuery(currentSql)?.let { currentSql = it }
                }

                if (currentSql != sql) {
                    param.args[1] = currentSql
                    WeLogger.d(TAG, "[rawQueryWithFactory] SQL被修改: $sql -> $currentSql, stack=${WeLogger.getStackTraceString()}")
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "Old version query dispatch failed", e)
            }
        }
    }
}