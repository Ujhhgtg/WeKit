package dev.ujhhgtg.wekit.features.api.core

import android.annotation.SuppressLint
import android.content.ContentValues
import com.tencent.wcdb.database.SQLiteDatabase
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("DiscouragedApi")
object WeDatabaseListenerApi : ApiFeature() {

    override val technicalId = "数据库监听服务"
    override val nameRes = R.string.feature_we_database_listener_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_database_listener_api_description

    fun interface IInsertListener {
        fun onInsert(table: String, values: ContentValues)
    }

    fun interface IUpdateListener {
        fun onUpdate(
            table: String,
            values: ContentValues,
            whereClause: String?,
            whereArgs: Array<String>?,
            conflictAlgorithm: Int,
        )
    }

    fun interface IQueryListener {
        fun onQuery(sql: String): String?
    }

    private const val TAG = "WeDatabaseListenerApi"

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

    // ==================== Insert Hook ====================

    private fun hookDatabaseInsert() {
        SQLiteDatabase::class.reflekt()
            .firstMethod {
                name = "insertWithOnConflict"
                parameters(String::class, String::class, ContentValues::class, Int::class)
            }
            .hookAfter {
                try {
                    if (insertListeners.isEmpty()) return@hookAfter

                    val table = args[0] as String
                    val values = args[2] as ContentValues

                    insertListeners.forEach { it.onInsert(table, values) }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "Insert dispatch failed", e)
                }
            }
    }

    // ==================== Update Hook ====================

    private fun hookDatabaseUpdate() {
        listOf(
                "com.tencent.wcdb.compat.SQLiteDatabase",
                "com.tencent.wcdb.database.SQLiteDatabase",
            )
            .forEach { className ->
                className
                    .toClass()
                    .reflekt()
                    .firstMethod {
                        name = "updateWithOnConflict"
                        parameters(
                            String::class,
                            ContentValues::class,
                            String::class,
                            Array<String>::class,
                            Int::class,
                        )
                    }
                    .hookBefore {
                        try {
                            if (updateListeners.isEmpty()) return@hookBefore

                            val table = args[0] as String
                            val values = args[1] as ContentValues
                            val whereClause = args[2] as String?

                            @Suppress("UNCHECKED_CAST") val whereArgs = args[3] as Array<String>?
                            val conflictAlgorithm = args[4] as Int

                            updateListeners.forEach {
                                it.onUpdate(
                                    table,
                                    values,
                                    whereClause,
                                    whereArgs,
                                    conflictAlgorithm,
                                )
                            }
                        } catch (e: Throwable) {
                            WeLogger.e(TAG, "update dispatch failed", e)
                        }
                    }
            }
    }

    // ==================== Query Hook ====================

    private fun hookDatabaseQuery() {
        com.tencent.wcdb.compat.SQLiteDatabase::class
            .reflekt()
            .firstMethod {
                name = "rawQuery"
                parameters(String::class, Array<Any>::class)
            }
            .hookBefore {
                try {
                    if (queryListeners.isEmpty()) return@hookBefore

                    val sql = args[0] as? String ?: return@hookBefore
                    var currentSql = sql

                    queryListeners.forEach { listener ->
                        listener.onQuery(currentSql)?.let { currentSql = it }
                    }

                    if (currentSql != sql) {
                        args[0] = currentSql
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "New version query dispatch failed", e)
                }
            }
    }
}
