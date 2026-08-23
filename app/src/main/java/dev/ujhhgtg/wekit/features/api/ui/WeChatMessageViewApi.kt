package dev.ujhhgtg.wekit.features.api.ui

import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

object WeChatMessageViewApi : ApiFeature(), IResolveDex {

    override val technicalId = "消息 View 创建监听服务"
    override val nameRes = R.string.feature_we_chat_message_view_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_chat_message_view_api_description

    fun interface ICreateViewListener {
        fun onCreateView(
            param: HookParam, view: View
        )
    }

    interface IMessageViewLifecycleListener {
        fun onMessageViewAttached(view: View, message: MessageInfo) {}
        fun onMessageViewDetached(view: View, message: MessageInfo) {}
        fun onMessageViewRecycled(view: View, message: MessageInfo) {}
    }

    private val listeners = CopyOnWriteArrayList<ICreateViewListener>()
    private val lifecycleListeners = CopyOnWriteArrayList<IMessageViewLifecycleListener>()
    private val currentBindings =
        Collections.synchronizedMap(WeakHashMap<View, MessageInfo>())
    private val attachStateListeners =
        Collections.synchronizedMap(WeakHashMap<View, View.OnAttachStateChangeListener>())

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

    fun addLifecycleListener(listener: IMessageViewLifecycleListener) {
        if (!lifecycleListeners.contains(listener)) {
            lifecycleListeners.add(listener)
        }
    }

    fun removeLifecycleListener(listener: IMessageViewLifecycleListener) {
        val removed = lifecycleListeners.remove(listener)
        WeLogger.i(
            TAG,
            "lifecycle listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${lifecycleListeners.size}"
        )
    }

    private const val TAG = "WeChatMessageViewApi"

    private val methodChatItemOnBindView by dexMethod {
        matcher {
            usingStrings(
                "MicroMsg.MvvmChattingItem",
                "[onBindView]"
            )
        }
    }

    private val methodChatItemOnViewRecycled by dexMethod {
        matcher {
            usingStrings("rvnotify-test-onViewRecycled viewType=")
        }
    }

    override fun onEnable() {
        methodChatItemOnBindView.hookAfter {
            val holder = args[0]!!
            val view = holder.reflekt()
                .firstField {
                    type = View::class
                    superclass()
                }
                .get()!! as View
            val message = getMsgInfoFromParam(this)
            ensureAttachStateListener(view)

            val previous = synchronized(currentBindings) { currentBindings[view] }
            val bindingChanged = previous?.instance !== message.instance
            if (view.isAttachedToWindow && bindingChanged && previous != null) {
                dispatchLifecycle { it.onMessageViewDetached(view, previous) }
            }
            synchronized(currentBindings) {
                currentBindings[view] = message
            }
            if (view.isAttachedToWindow && bindingChanged) {
                dispatchLifecycle { it.onMessageViewAttached(view, message) }
            }

            for (listener in listeners) {
                try {
                    listener.onCreateView(this, view)
                } catch (ex: Exception) {
                    WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
                }
            }
        }

        methodChatItemOnViewRecycled.hookBefore {
            val holder = args[0]!!
            val view = holder.reflekt()
                .firstField {
                    type = View::class
                    superclass()
                }
                .get()!! as View
            val message = synchronized(currentBindings) { currentBindings[view] } ?: return@hookBefore
            dispatchLifecycle { it.onMessageViewRecycled(view, message) }
            synchronized(currentBindings) {
                currentBindings.remove(view)
            }
        }
    }

    private fun ensureAttachStateListener(view: View) {
        synchronized(attachStateListeners) {
            if (attachStateListeners.containsKey(view)) return
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    val message = synchronized(currentBindings) { currentBindings[view] } ?: return
                    dispatchLifecycle { it.onMessageViewAttached(view, message) }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    val message = synchronized(currentBindings) { currentBindings[view] } ?: return
                    dispatchLifecycle { it.onMessageViewDetached(view, message) }
                }
            }
            attachStateListeners[view] = listener
            view.addOnAttachStateChangeListener(listener)
        }
    }

    private fun dispatchLifecycle(callback: (IMessageViewLifecycleListener) -> Unit) {
        for (listener in lifecycleListeners) {
            try {
                callback(listener)
            } catch (ex: Exception) {
                WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
            }
        }
    }

    fun getChattingContextFromParam(param: HookParam): Any {
        return param.thisObject!!.reflekt()
            .firstField { type = WeMessageApi.classChattingContext.clazz }
            .get()!!
    }

    fun getMsgInfoFromParam(param: HookParam): MessageInfo {
        val chattingDataAdapter = param.thisObject!!.reflekt()
            .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
            .get()!!
        val msgId = param.args[2] as Int
        val msgInfo = chattingDataAdapter.reflekt()
            .firstMethod { name = "getItem" }
            .invoke(msgId)!!
        return MessageInfo(msgInfo)
    }
}
