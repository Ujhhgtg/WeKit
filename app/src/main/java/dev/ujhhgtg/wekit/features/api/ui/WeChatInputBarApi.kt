package dev.ujhhgtg.wekit.features.api.ui

import android.text.Editable
import android.text.TextWatcher
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 聊天输入栏监听服务。
 *
 * 监听聊天界面输入栏的文本变化事件，其他功能可通过 [addListener] 注册实现
 * [IInputBarListener] 接口的监听器来接收文本变化通知。
 */
@Feature(
    name = "聊天输入栏监听服务",
    categories = ["API"],
    description = "提供聊天输入栏文本变化的监听能力"
)
object WeChatInputBarApi : ApiFeature() {

    /**
     * 输入栏文本变化监听器。
     *
     * 每当用户输入文本发生变化时，所有已注册的监听器的 [onTextChanged] 方法会被调用。
     *
     * @param chatFooter 当前聊天页面的 ChatFooter 实例，可用于访问 [lastText], [context] 等属性。
     * @param text 输入框当前的文本内容。
     */
    fun interface IInputBarListener {
        fun onTextChanged(chatFooter: ChatFooter, text: String)
    }

    private val TAG = This.Class.simpleName

    private val listeners = CopyOnWriteArrayList<IInputBarListener>()
    private val hookedFooters = Collections.newSetFromMap(WeakHashMap<ChatFooter, Boolean>())

    /**
     * 注册一个输入栏监听器。
     *
     * 监听器的 [IInputBarListener.onTextChanged] 会在每次输入框文本变化时被调用。
     * 重复注册同一个监听器实例不会生效。
     */
    fun addListener(listener: IInputBarListener) {
        listeners.addIfAbsent(listener)
    }

    /**
     * 移除一个已注册的输入栏监听器。
     */
    fun removeListener(listener: IInputBarListener) {
        listeners.remove(listener)
    }

    override fun onEnable() {
        // Hook onAttachedToWindow — fires every time a chat is opened.
        // f207261m (type fl5.i) is the real chat input, assigned in the constructor
        // via findViewById(R.id.bkk) and is non-null by onAttachedToWindow.
        //
        // The previous approach used firstField { type = MMEditText::class } which
        // found f207201b4 — the voice-to-text popup's EditText. That field is null
        // until the user taps the mic button, so the hook silently did nothing.
        //
        // fl5.i is obfuscated and can't be referenced at compile time, so we locate
        // the field at runtime: whichever non-null field value exposes addTextChangedListener.
        ChatFooter::class.java.reflekt()
            .firstMethod { name = "onAttachedToWindow"; parameterCount = 0 }
            .hookAfter {
                val chatFooter = thisObject as ChatFooter

                if (!hookedFooters.add(chatFooter)) return@hookAfter

                val field = chatFooter.reflekt().firstField {
                    type { clazz ->
                        clazz.isInterface && clazz.declaredMethods.any { it.name == "addTextChangedListener" }
                    }
                }.get()!!
                field.reflekt().firstMethod {
                    name = "addTextChangedListener"
                    superclass()
                }.invoke(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val text = s?.toString() ?: ""
                        for (listener in listeners) {
                            try {
                                listener.onTextChanged(chatFooter, text)
                            } catch (e: Throwable) {
                                WeLogger.e(TAG, "input bar listener callback failed", e)
                            }
                        }
                    }
                })

                WeLogger.d(TAG, "attached text watcher to ${field.javaClass.name}")
            }
    }

    override fun onDisable() {
        listeners.clear()
    }
}
