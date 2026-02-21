package moe.ouom.wekit.hooks.sdk.base

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.log.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/活动启动监听服务", desc = "为其他功能提供 startActivity 监听能力")
object WeStartActivityListener : ApiHookItem() {

    interface IStartActivityListener {
        fun onStartActivity(hookParam: XC_MethodHook.MethodHookParam, intent: Intent)
    }

    private const val TAG: String = "WeStartActivityListener"

    private val listeners = CopyOnWriteArrayList<IStartActivityListener>()

    // 供其他模块注册监听
    fun addListener(listener: IStartActivityListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            WeLogger.i(TAG, "listener added, current listener count: ${listeners.size}")
        } else {
            WeLogger.w(TAG, "listener already exists, ignored")
        }
    }

    fun removeListener(listener: IStartActivityListener) {
        val removed = listeners.remove(listener)
        WeLogger.i(TAG, "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}")
    }

    override fun entry(classLoader: ClassLoader) {
        Activity::class.asResolver()
            .method {
                name {
                    it == "startActivity" || it == "startActivityForResult"
                }
            }
            .forEach {
                hookBefore(it.self) { param ->
                    hookStartActivity(param)
                }
            }

        ContextWrapper::class.asResolver()
            .method {
                name {
                    it == "startActivity" || it == "startActivityForResult"
                }
            }
            .forEach {
                hookBefore(it.self) { param ->
                    hookStartActivity(param)
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
