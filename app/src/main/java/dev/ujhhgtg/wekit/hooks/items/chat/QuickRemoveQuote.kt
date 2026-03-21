package dev.ujhhgtg.wekit.hooks.items.chat

import android.view.KeyEvent
import android.widget.FrameLayout
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/快捷清除引用", desc = "在输入退格时若输入框无文字自动清除引用")
object QuickRemoveQuote : SwitchHookItem(), IResolvesDex {

    private val methodSupportAutoCompleteOnKey by dexMethod()
    private val methodShowMsgQuoteContainer by dexMethod()

    override fun onEnable() {
        methodSupportAutoCompleteOnKey.hookBefore { param ->
            val keyEvent = param.args[2] as KeyEvent
            if (keyEvent.keyCode != 67 || keyEvent.action != 0) return@hookBefore

            val chatFooterHelper = param.thisObject.asResolver()
                .firstField {
                    type { clazz -> clazz.name.startsWith("com.tencent.mm.pluginsdk.ui.chat.") }
                }
                .get()!!
            val chatFooter = chatFooterHelper.asResolver()
                .firstField {
                    type = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
                }
                .get()!! as FrameLayout
            val text = chatFooter.asResolver()
                .firstMethod { name = "getLastText" }
                .invoke()!! as String
            val quoteMsgId = chatFooter.asResolver()
                .firstMethod { name = "getLastQuoteMsgId" }
                .invoke()!! as Long
            if (text.isEmpty() && quoteMsgId != 0L) {
                methodShowMsgQuoteContainer.method.invoke(chatFooter, false, true)
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodSupportAutoCompleteOnKey.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.pluginsdk.ui.chat")
            matcher {
                name = "onKey"
                usingEqStrings("ChatFooterKtHelper", "supportAutoComplete err")
            }
        }

        methodShowMsgQuoteContainer.find(dexKit, descriptors) {
            matcher {
                declaredClass = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
                paramTypes("boolean", "boolean")
                returnType = "void"
                usingEqStrings("")
            }
        }

        return descriptors
    }
}
