package moe.ouom.wekit.hooks.api.ui

import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.hooks.api.core.WeMessageApi
import moe.ouom.wekit.hooks.api.core.model.MessageInfo
import moe.ouom.wekit.utils.logging.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/消息 View 创建监听服务", desc = "为其他功能提供消息 View 创建监听能力")
object WeChatMessageViewApi : ApiHookItem(), IResolvesDex {

    interface ICreateViewListener {
        fun onCreateView(
            param: XC_MethodHook.MethodHookParam, view: View,
            chattingContext: Any, msgInfo: MessageInfo
        )
    }

    private val listeners = CopyOnWriteArrayList<ICreateViewListener>()

    fun addListener(listener: ICreateViewListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            WeLogger.i(TAG, "listener added, current listener count: ${listeners.size}")
        } else {
            WeLogger.w(TAG, "listener already exists, ignored")
        }
    }

    fun removeListener(listener: ICreateViewListener) {
        val removed = listeners.remove(listener)
        WeLogger.i(
            TAG,
            "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}"
        )
    }

    private val TAG = nameof(WeChatMessageViewApi)

    private val methodChatItemOnBindView by dexMethod()

    override fun onEnable() {
        methodChatItemOnBindView.toDexMethod {
            hook {
                afterIfEnabled { param ->
                    val holder = param.args[0]
                    val view = holder.asResolver()
                        .firstField {
                            type = View::class
                            superclass()
                        }
                        .get()!! as View
                    val msgId = param.args[2] as Int
                    val chattingContext = param.thisObject.asResolver()
                        .firstField { type = WeMessageApi.classChattingContext.clazz }
                        .get()!!
                    val chattingDataAdapter = param.thisObject.asResolver()
                        .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
                        .get()!!
                    val msgInfo = chattingDataAdapter.asResolver()
                        .firstMethod { name = "getItem" }
                        .invoke(msgId)!!

                    for (listener in listeners) {
                        try {
                            listener.onCreateView(
                                param,
                                view,
                                chattingContext,
                                MessageInfo(msgInfo)
                            )
                        } catch (ex: Exception) {
                            WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
                        }
                    }
                }
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodChatItemOnBindView.find(dexKit, descriptors) {
            matcher {
                usingEqStrings(
                    "MicroMsg.MvvmChattingItem",
                    "dealItemView",
                    "[onBindView] finish position:"
                )
            }
        }

        return descriptors
    }
}