package dev.ujhhgtg.wekit.features.items.chat

import android.view.KeyEvent
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(name = "快捷清除引用", categories = ["聊天"], description = "在输入退格时若输入框无文字自动清除引用")
object QuickRemoveQuote : SwitchFeature(), IResolveDex {

    private val methodSupportAutoCompleteOnKey by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            name = "onKey"
            usingEqStrings("ChatFooterKtHelper", "supportAutoComplete err")
        }
    }
    private val methodShowMsgQuoteContainer by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
            paramTypes("boolean", "boolean")
            returnType = "void"
            usingEqStrings("")
        }
    }

    override fun onEnable() {
        methodSupportAutoCompleteOnKey.hookBefore {
            val event = args[2] as KeyEvent
            if (event.action != KeyEvent.ACTION_DOWN || event.keyCode != KeyEvent.KEYCODE_DEL) return@hookBefore

            val chatFooterHelper = thisObject!!.reflekt()
                .firstField {
                    type { clazz -> clazz.name.startsWith("com.tencent.mm.pluginsdk.ui.chat.") }
                }.get()!!

            val chatFooter = chatFooterHelper.reflekt()
                .firstField {
                    type = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
                }.get()!! as ChatFooter

            val text = chatFooter.lastText
            val quoteMsgId = chatFooter.lastQuoteMsgId

            if (text.isEmpty() && quoteMsgId != 0L) {
                methodShowMsgQuoteContainer.method.invoke(chatFooter, false, true)
            }
        }
    }
}
