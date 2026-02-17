package moe.ouom.wekit.hooks.sdk.api

import android.annotation.SuppressLint
import android.content.ContentValues
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.config.WeConfig
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.util.Initiator.loadClass
import moe.ouom.wekit.util.log.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("DiscouragedApi")
@HookItem(path = "API/数据库监听服务", desc = "为其他功能提供数据库写入监听能力")
class WeDatabaseListener : ApiHookItem() {
    // 定义监听器接口
    interface DatabaseInsertListener {
        fun onInsert(table: String, values: ContentValues)
    }

    companion object {
        private val listeners = CopyOnWriteArrayList<DatabaseInsertListener>()

        // 供其他模块注册监听
        fun addListener(listener: DatabaseInsertListener) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
                WeLogger.i("WeDatabaseApi: 监听器已添加，当前监听器数量: ${listeners.size}")
            } else {
                WeLogger.w("WeDatabaseApi: 监听器已存在，跳过添加")
            }
        }

        fun removeListener(listener: DatabaseInsertListener) {
            val removed = listeners.remove(listener)
            WeLogger.i("WeDatabaseApi: 监听器移除${if (removed) "成功" else "失败"}，当前监听器数量: ${listeners.size}")
        }
    }

    override fun entry(classLoader: ClassLoader) {
        hookDatabaseInsert()
    }

    private fun hookDatabaseInsert() {
        try {
            val clsSQLite = loadClass(Constants.CLAZZ_SQLITE_DATABASE)

            val mInsertWithOnConflict = XposedHelpers.findMethodExact(
                clsSQLite,
                "insertWithOnConflict",
                String::class.java,
                String::class.java,
                ContentValues::class.java,
                Int::class.javaPrimitiveType
            )

            hookAfter(mInsertWithOnConflict) { param ->
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
                                    "WeDatabaseApi",
                                    "[Insert] table=$table, result=$result, args=[$argsInfo], stack=${WeLogger.getStackTraceString()}"
                                )
                            }
                        }
                        listeners.forEach { it.onInsert(table, values) }
                    }
                } catch (e: Throwable) {
                    WeLogger.e("WeDatabaseApi: Dispatch failed", e)
                }
            }
            WeLogger.i("WeDatabaseApi: Hook success")
        } catch (e: Throwable) {
            WeLogger.e("WeDatabaseApi: Hook database failed", e)
        }
    }

    override fun unload(classLoader: ClassLoader) {
        listeners.clear()
    }
}