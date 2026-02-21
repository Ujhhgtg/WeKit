package moe.ouom.wekit.hooks.sdk.base

import android.annotation.SuppressLint
import android.content.ContentValues
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.config.WeConfig
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.log.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("DiscouragedApi")
@HookItem(path = "API/数据库监听服务", desc = "为其他功能提供数据库写入监听能力")
class WeDatabaseListener : ApiHookItem() {

    // 定义监听器接口
    interface IDatabaseInsertListener {
        fun onInsert(table: String, values: ContentValues)
    }

    companion object {
        private const val TAG: String = "WeDatabaseApi"

        private val listeners = CopyOnWriteArrayList<IDatabaseInsertListener>()

        // 供其他模块注册监听
        fun addListener(listener: IDatabaseInsertListener) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
                WeLogger.i(TAG, "listener added, current listener count: ${listeners.size}")
            } else {
                WeLogger.w(TAG, "listener already exists, ignored")
            }
        }

        fun removeListener(listener: IDatabaseInsertListener) {
            val removed = listeners.remove(listener)
            WeLogger.i(TAG, "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}")
        }
    }

    override fun entry(classLoader: ClassLoader) {
        hookDatabaseInsert(classLoader)
    }

    private fun hookDatabaseInsert(classLoader: ClassLoader) {
        try {
            val sqliteCls = "com.tencent.wcdb.database.SQLiteDatabase".toClass(classLoader)

            sqliteCls.asResolver()
                .firstMethod {
                    name = "insertWithOnConflict"
                    parameters(String::class, String::class, ContentValues::class, Int::class)
                }
                .hookAfter { param ->
                    try {
                        val table = param.args[0] as String
                        val values = param.args[2] as ContentValues

                        val config = WeConfig.getDefaultConfig()
                        val verboseLog = config.getBooleanOrFalse(Constants.PrekVerboseLog)
                        val dbVerboseLog = config.getBooleanOrFalse(Constants.PrekDatabaseVerboseLog)

                        // 分发事件给所有监听者
                        if (listeners.isNotEmpty()) {
                            if (verboseLog) {
                                if (dbVerboseLog) {
                                    val argsInfo = param.args.mapIndexed { index, arg ->
                                        "arg[$index](${arg?.javaClass?.simpleName ?: "null"})=$arg"
                                    }.joinToString(", ")
                                    val result = param.result

                                    WeLogger.logChunkedD(
                                        TAG,
                                        "[Insert] table=$table, result=$result, args=[$argsInfo], stack=${WeLogger.getStackTraceString()}"
                                    )
                                }
                            }
                            listeners.forEach { it.onInsert(table, values) }
                        }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "dispatch failed", e)
                    }
                }
            WeLogger.i(TAG, "hook success")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "hook database failed", e)
        }
    }

    override fun unload(classLoader: ClassLoader) {
        listeners.clear()
    }
}