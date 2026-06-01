package dev.ujhhgtg.wekit.hooks.api.ui

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/消息 View 创建监听服务", description = "提供消息 View 创建监听能力")
object WeChatMessageViewApi : ApiHookItem(), IResolvesDex {

    interface ICreateViewListener {
        fun onCreateView(
            param: XC_MethodHook.MethodHookParam, view: View
        )
    }

    private val listeners = CopyOnWriteArrayList<ICreateViewListener>()

    fun addListener(listener: ICreateViewListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: ICreateViewListener) {
        val removed = listeners.remove(listener)
        WeLogger.i(
            TAG,
            "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}"
        )
    }

    private val TAG = nameOf(WeChatMessageViewApi)

    private val methodChatItemOnBindView by dexMethod()

    override fun onEnable() {
        methodChatItemOnBindView.hookAfter {
            val holder = args[0]
            val view = holder.asResolver()
                .firstField {
                    type = View::class
                    superclass()
                }
                .get()!! as View

            for (listener in listeners) {
                try {
                    listener.onCreateView(this, view)
                } catch (ex: Exception) {
                    WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
                }
            }
        }
    }

    fun getChattingContextFromParam(param: XC_MethodHook.MethodHookParam): Any {
        return param.thisObject.asResolver()
            .firstField { type = WeMessageApi.classChattingContext.clazz }
            .get()!!
    }

    fun getMsgInfoFromParam(param: XC_MethodHook.MethodHookParam): MessageInfo {
        val chattingDataAdapter = param.thisObject.asResolver()
            .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
            .get()!!
        val msgId = param.args[2] as Int
        val msgInfo = chattingDataAdapter.asResolver()
            .firstMethod { name = "getItem" }
            .invoke(msgId)!!
        return MessageInfo(msgInfo)
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodChatItemOnBindView.find(dexKit) {
            matcher {
                usingStrings(
                    "MicroMsg.MvvmChattingItem",
                    "[onBindView]"
                )
            }
        }
    }
}
