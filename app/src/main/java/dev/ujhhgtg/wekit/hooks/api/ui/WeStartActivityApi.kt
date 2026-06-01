package dev.ujhhgtg.wekit.hooks.api.ui

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.resolve
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/活动启动监听服务", description = "提供 startActivity 监听能力")
object WeStartActivityApi : ApiHookItem() {

    interface IStartActivityListener {
        fun onStartActivity(param: XC_MethodHook.MethodHookParam, intent: Intent)
    }

    private val TAG = This.Class.simpleName

    private val listeners = CopyOnWriteArrayList<IStartActivityListener>()

    fun addListener(listener: IStartActivityListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: IStartActivityListener) {
        listeners.remove(listener)
    }

    override fun onEnable() {
        Activity::class.resolve()
            .method {
                name {
                    it == "startActivity" || it == "startActivityForResult"
                }
            }
            .forEach {
                it.hookBefore {
                    hookStartActivity(this)
                }
            }

        ContextWrapper::class.resolve()
            .method {
                name {
                    it == "startActivity" || it == "startActivityForResult"
                }
            }
            .forEach {
                it.hookBefore {
                    hookStartActivity(this)
                }
            }
    }

    private fun hookStartActivity(param: XC_MethodHook.MethodHookParam) {
        val intent = param.args[0] as? Intent ?: param.args[1] as? Intent
        if (intent == null) {
            WeLogger.w(TAG, "startActivity called but no Intent found in arguments")
            return
        }

        listeners.forEach { listener ->
            try {
                listener.onStartActivity(param, intent)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "listener threw an exception: ${e.message}")
            }
        }
    }
}
