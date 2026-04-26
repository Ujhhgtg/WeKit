package dev.ujhhgtg.wekit.hooks.api.core

import android.annotation.SuppressLint
import android.content.ContentValues
import com.highcapable.kavaref.extension.VariousClass
import com.tencent.wcdb.database.SQLiteDatabase
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.constants.PreferenceKeys
import dev.ujhhgtg.wekit.constants.WeChatVersion
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.resolve
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("DiscouragedApi")
@HookItem(path = "API/数据库监听服务", description = "提供数据库插入、更新与查询监听能力")
object WeDatabaseListenerApi : ApiHookItem() {

    fun interface IInsertListener {
        fun onInsert(table: String, values: ContentValues)
    }

    fun interface IUpdateListener {
        fun onUpdate(table: String, values: ContentValues): Boolean
    }

    fun interface IQueryListener {
        fun onQuery(sql: String): String?
    }

    private val TAG = nameOf(WeDatabaseApi)

    private val insertListeners = CopyOnWriteArrayList<IInsertListener>()
    private val updateListeners = CopyOnWriteArrayList<IUpdateListener>()
    private val queryListeners = CopyOnWriteArrayList<IQueryListener>()

    fun addListener(listener: Any) {
        if (listener is IInsertListener) {
            insertListeners.add(listener)
        }
        if (listener is IUpdateListener) {
            updateListeners.add(listener)
        }
        if (listener is IQueryListener) {
            queryListeners.add(listener)
        }
    }

    fun removeListener(listener: Any) {
        if (listener is IInsertListener) {
            insertListeners.remove(listener)
        }
        if (listener is IUpdateListener) {
            updateListeners.remove(listener)
        }
        if (listener is IQueryListener) {
            queryListeners.remove(listener)
        }
    }

    override fun onEnable() {
        hookDatabaseInsert()
        hookDatabaseUpdate()
        hookDatabaseQuery()
    }

    override fun onDisable() {
        insertListeners.clear()
        updateListeners.clear()
        queryListeners.clear()
    }

    // ==================== 私有辅助方法 ====================

    private fun formatArgs(args: Array<out Any?>): String {
        return args.mapIndexed { index, arg ->
            "arg[$index](${arg?.javaClass?.simpleName ?: "null"})=$arg"
        }.joinToString(", ")
    }

    private fun logWithStack(
        methodName: String,
        table: String,
        args: Array<out Any?>,
        result: Any? = null
    ) {
        if (!WePrefs.getBoolOrFalse(PreferenceKeys.VERBOSE_LOG)) return

        val argsInfo = formatArgs(args)
        val resultStr = if (result != null) ", result=$result" else ""
        val stackStr = ", stack=${WeLogger.getStackTraceString()}"

        WeLogger.logChunkedD(TAG, "[$methodName] table=$table$resultStr, args=[$argsInfo]$stackStr")
    }

    // ==================== Insert Hook ====================

    private fun hookDatabaseInsert() {
        SQLiteDatabase::class.resolve()
            .firstMethod {
                name = "insertWithOnConflict"
                parameters(String::class, String::class, ContentValues::class, Int::class)
            }.hookAfter {
                try {
                    if (insertListeners.isEmpty()) return@hookAfter

                    val table = args[0] as String
                    val values = args[2] as ContentValues

                    logWithStack("Insert", table, args, result)
                    insertListeners.forEach { it.onInsert(table, values) }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "Insert dispatch failed", e)
                }
            }
    }

    // ==================== Update Hook ====================

    private fun hookDatabaseUpdate() {
        val clsDb = VariousClass("com.tencent.wcdb.compat.SQLiteDatabase", "com.tencent.wcdb.database.SQLiteDatabase").load()

        clsDb.resolve()
            .firstMethod {
                name = "updateWithOnConflict"
                parameters(
                    String::class,
                    ContentValues::class,
                    String::class,
                    Array<String>::class,
                    Int::class
                )
            }
            .hookBefore {
                try {
                    if (updateListeners.isEmpty()) return@hookBefore

                    val table = args[0] as String
                    val values = args[1] as ContentValues

                    logWithStack("Update", table, args)

                    // 如果有任何一个监听器返回 true，则阻止更新
                    val shouldBlock = updateListeners.any { it.onUpdate(table, values) }

                    if (shouldBlock) {
                        result = 0 // 返回0表示没有行被更新
                        WeLogger.d(
                            TAG,
                            "[Update] 被监听器阻止, table=$table, stack=${WeLogger.getStackTraceString()}"
                        )
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "Update dispatch failed", e)
                }
            }
    }

    // ==================== Query Hook ====================

    private fun hookDatabaseQuery() {
        val isPlay = HostInfo.isHostGooglePlay
        val version = HostInfo.versionCode
        val isNewVersion = (!isPlay && version >= WeChatVersion.MM_8_0_43) ||
                (isPlay && version >= WeChatVersion.MM_8_0_48_PLAY)

        if (isNewVersion) {
            hookNewQueryMethod()
        } else {
            hookOldQueryMethod()
        }
    }

    private fun hookNewQueryMethod() {
        com.tencent.wcdb.compat.SQLiteDatabase::class.resolve()
            .firstMethod {
                name = "rawQuery"
                parameters(String::class, Array<Any>::class)
            }
            .hookBefore {
                try {
                    if (queryListeners.isEmpty()) return@hookBefore

                    val sql = args[0] as? String ?: return@hookBefore
                    var currentSql = sql

                    logWithStack("rawQuery", "N/A", args)

                    queryListeners.forEach { listener ->
                        listener.onQuery(currentSql)?.let { currentSql = it }
                    }

                    if (currentSql != sql) {
                        args[0] = currentSql
                        WeLogger.d(
                            TAG,
                            "[rawQuery] SQL被修改: $sql -> $currentSql, stack=${WeLogger.getStackTraceString()}"
                        )
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "New version query dispatch failed", e)
                }
            }
    }

    private fun hookOldQueryMethod() {
        SQLiteDatabase::class.resolve().firstMethod {
            name = "rawQueryWithFactory"
            parameterCount = 5
        }.hookBefore {
            try {
                if (queryListeners.isEmpty()) return@hookBefore

                val sql = args[1] as? String ?: return@hookBefore
                var currentSql = sql

                logWithStack(
                    "rawQueryWithFactory",
                    args[3] as? String ?: "N/A",
                    args
                )

                queryListeners.forEach { listener ->
                    listener.onQuery(currentSql)?.let { currentSql = it }
                }

                if (currentSql != sql) {
                    args[1] = currentSql
                    WeLogger.d(
                        TAG,
                        "[rawQueryWithFactory] SQL modified: $sql -> $currentSql, stack=${WeLogger.getStackTraceString()}"
                    )
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "Old version query dispatch failed", e)
            }
        }
    }
}
