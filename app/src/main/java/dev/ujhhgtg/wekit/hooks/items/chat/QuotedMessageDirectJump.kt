package dev.ujhhgtg.wekit.hooks.items.chat

import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.enumValueOfClass
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/引用消息直达", description = "点击被引用消息时直接跳转至对应消息")
object QuotedMessageDirectJump : SwitchHookItem(), IResolvesDex {

    private val methodClickEvent by dexMethod()
    private val methodClickToPositionEvent by dexMethod()
    private val methodGetQuoteMessageInfo by dexMethod()
    private val methodChattingContextGetTalker by dexMethod()
    private val classEnumQuoteJumpToPositionSource by dexClass()
    private val classChattingContext by dexClass()

    override fun onEnable() {
        methodClickEvent.hookBefore {
            val chattingContext = args[0]
            val view = args[2]
            val longValue = args[3]
            val stringValue = args[4]
            val msgQuoteItem = args[5]
            val chattingItemHolder = args[7]
            val chattingItem = chattingItemHolder.asResolver()
                .firstField { type { it != String::class.java } }.get()!!
            val mGetQuoteMessageInfo = methodGetQuoteMessageInfo.method
            var msgInfo: Any
            if (mGetQuoteMessageInfo.parameterCount == 6) {
                msgInfo = mGetQuoteMessageInfo.invoke(
                    null,
                    false /* isGroupChat: this arg is ignored */,
                    methodChattingContextGetTalker.method.invoke(chattingContext),
                    longValue,
                    stringValue,
                    msgQuoteItem,
                    "handleQuoteMsgClick" /* hardcoded in original code */
                )!!
            } else {
                msgInfo = mGetQuoteMessageInfo.invoke(
                    null,
                    false /* isGroupChat: this arg is ignored */,
                    methodChattingContextGetTalker.method.invoke(chattingContext),
                    longValue,
                    msgQuoteItem,
                    "handleQuoteMsgClick" /* hardcoded in original code */
                )!!
            }
            val mClickToPositionEvent = methodClickToPositionEvent.method
            if (mClickToPositionEvent.parameterCount == 8) {
                methodClickToPositionEvent.method.invoke(
                    null,
                    chattingContext,
                    chattingItem,
                    msgInfo,
                    view,
                    longValue,
                    stringValue,
                    msgQuoteItem,
                    enumValueOfClass(classEnumQuoteJumpToPositionSource.clazz, "QuoteLongClickFromQuoteView")
                )
            } else {
                methodClickToPositionEvent.method.invoke(
                    null,
                    chattingContext,
                    chattingItem,
                    msgInfo,
                    view,
                    longValue,
                    msgQuoteItem,
                    true
                )
            }
            result = null
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodClickEvent.find(dexKit) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher {
                usingEqStrings(
                    "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                    "handleItemClickEvent,quotedMsg is null!"
                )
            }
        }

        methodClickToPositionEvent.find(dexKit) {
            matcher {
                declaredClass(methodClickEvent.method.declaringClass)
                usingEqStrings(
                    "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                    "handleItemClickToPositionEvent,quotedMsg is null!"
                )
            }
        }

        methodGetQuoteMessageInfo.find(dexKit) {
            matcher {
                declaredClass(methodClickEvent.method.declaringClass)
                usingStrings(
                    "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                    "%s msgId:%s msgSvrId:%s"
                )
            }
        }

        classChattingContext.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]")
            }
        }

        methodChattingContextGetTalker.find(dexKit) {
            matcher {
                declaredClass(classChattingContext.clazz)
                usingEqStrings("getTalker returns null.")
            }
        }

        classEnumQuoteJumpToPositionSource.find(dexKit, allowFailure = true) {
            matcher {
                usingEqStrings("QuoteLongClickFromQuoteView", "QuoteClickFromTextPreviewLocateView")
            }
        }
    }
}
