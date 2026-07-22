package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.enumValueOfClass

@Feature(name = "引用消息直达", categories = ["聊天"], description = "点击被引用消息时直接跳转至对应消息")
object QuotedMessageDirectJump : SwitchFeature(), IResolveDex {

    private val methodClickEvent by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher {
            usingEqStrings(
                "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                "handleItemClickEvent,quotedMsg is null!"
            )
        }
    }
    private val methodClickToPositionEvent by dexMethod {
        matcher {
            declaredClass(methodClickEvent.method.declaringClass)
            usingEqStrings(
                "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                "handleItemClickToPositionEvent,quotedMsg is null!"
            )
        }
    }
    private val methodGetQuoteMessageInfo by dexMethod {
        matcher {
            declaredClass(methodClickEvent.method.declaringClass)
            usingStrings(
                "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                "%s msgId:%s msgSvrId:%s"
            )
        }
    }
    private val classEnumQuoteJumpToPositionSource by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("QuoteLongClickFromQuoteView", "QuoteClickFromTextPreviewLocateView")
        }
    }
    private val classChattingContext by dexClass {
        matcher {
            usingEqStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]")
        }
    }
    private val methodChattingContextGetTalker by dexMethod {
        matcher {
            declaredClass(classChattingContext.clazz)
            usingEqStrings("getTalker returns null.")
        }
    }

    override fun onEnable() {
        methodClickEvent.hookBefore {
            val chattingContext = args[0]
            val view = args[2]
            val longValue = args[3]
            val stringValue = args[4]
            val msgQuoteItem = args[5]
            val chattingItemHolder = args[7]!!
            val chattingItem = chattingItemHolder.reflekt()
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
}
