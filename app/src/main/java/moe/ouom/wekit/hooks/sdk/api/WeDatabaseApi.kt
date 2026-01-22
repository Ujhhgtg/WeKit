package moe.ouom.wekit.hooks.sdk.api

import android.annotation.SuppressLint
import android.content.ContentValues
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.util.log.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("DiscouragedApi")
@HookItem(path = "API/数据库监听服务", desc = "为其他功能提供数据库写入监听能力")
class WeDatabaseApi : ApiHookItem() {

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
            }
        }

        fun removeListener(listener: DatabaseInsertListener) {
            listeners.remove(listener)
        }
    }

    override fun entry(classLoader: ClassLoader) {
        hookDatabaseInsert(classLoader)
    }

    private fun hookDatabaseInsert(classLoader: ClassLoader) {
        try {
            val clsSQLite = XposedHelpers.findClass(Constants.CLAZZ_SQLITE_DATABASE, classLoader)

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

                    // 分发事件给所有监听者
                    listeners.forEach { it.onInsert(table, values) }
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