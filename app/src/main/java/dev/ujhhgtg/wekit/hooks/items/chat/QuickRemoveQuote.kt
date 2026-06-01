package dev.ujhhgtg.wekit.hooks.items.chat

import android.view.KeyEvent
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field

@HookItem(path = "聊天/快捷清除引用", description = "在输入退格时若输入框无文字自动清除引用")
object QuickRemoveQuote : SwitchHookItem(), IResolvesDex {

    private val methodSupportAutoCompleteOnKey by dexMethod()
    private val methodShowMsgQuoteContainer by dexMethod()

    private lateinit var chatFooterHelperField: Field
    private lateinit var chatFooterField: Field

    override fun onEnable() {
        methodSupportAutoCompleteOnKey.hookBefore {
            val keyEvent = args[2] as KeyEvent
            if (keyEvent.keyCode != 67 || keyEvent.action != 0) return@hookBefore

            if (!::chatFooterHelperField.isInitialized) {
                chatFooterHelperField = thisObject.asResolver()
                    .firstField {
                        type { clazz -> clazz.name.startsWith("com.tencent.mm.pluginsdk.ui.chat.") }
                    }.self
            }
            val chatFooterHelper = chatFooterHelperField.get(thisObject)

            if (!::chatFooterField.isInitialized) {
                chatFooterField = chatFooterHelper.asResolver()
                    .firstField {
                        type = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
                    }.self
            }
            val chatFooter = chatFooterField.get(chatFooterHelper) as ChatFooter

            val text = chatFooter.lastText
            val quoteMsgId = chatFooter.lastQuoteMsgId

            if (text.isEmpty() && quoteMsgId != 0L) {
                methodShowMsgQuoteContainer.method.invoke(chatFooter, false, true)
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodSupportAutoCompleteOnKey.find(dexKit) {
            searchPackages("com.tencent.mm.pluginsdk.ui.chat")
            matcher {
                name = "onKey"
                usingEqStrings("ChatFooterKtHelper", "supportAutoComplete err")
            }
        }

        methodShowMsgQuoteContainer.find(dexKit) {
            matcher {
                declaredClass = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
                paramTypes("boolean", "boolean")
                returnType = "void"
                usingEqStrings("")
            }
        }
    }
}
