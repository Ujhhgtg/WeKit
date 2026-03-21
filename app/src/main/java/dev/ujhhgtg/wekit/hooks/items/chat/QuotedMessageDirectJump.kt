package dev.ujhhgtg.wekit.hooks.items.chat

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.nameof.nameof
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.enumValueOfClass
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/引用消息直达", desc = "点击被引用消息时直接跳转至对应消息")
object QuotedMessageDirectJump : SwitchHookItem(), IResolvesDex {

    private val methodClickEvent by dexMethod()
    private val methodClickToPositionEvent by dexMethod()
    private val methodGetQuoteMessageInfo by dexMethod()
    private val methodChattingContextGetTalker by dexMethod()
    private val classEnumQuoteJumpToPositionSource by dexClass()
    private val classChattingContext by dexClass()

    override fun onEnable() {
        methodClickEvent.hookBefore { param ->
            val chattingContext = param.args[0]
            val view = param.args[2]
            val longValue = param.args[3]
            val stringValue = param.args[4]
            val msgQuoteItem = param.args[5]
            val chattingItemHolder = param.args[7]
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
            }
            else {
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
            }
            else {
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
            param.result = null
        }
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodClickEvent.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher {
                usingEqStrings(
                    "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                    "handleItemClickEvent,quotedMsg is null!"
                )
            }
        }

        methodClickToPositionEvent.find(dexKit, descriptors) {
            matcher {
                declaredClass(methodClickEvent.method.declaringClass)
                usingEqStrings(
                    "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                    "handleItemClickToPositionEvent,quotedMsg is null!"
                )
            }
        }

        methodGetQuoteMessageInfo.find(dexKit, descriptors) {
            matcher {
                declaredClass(methodClickEvent.method.declaringClass)
                usingStrings(
                    "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                    "%s msgId:%s msgSvrId:%s"
                )
            }
        }

        classChattingContext.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]")
            }
        }

        methodChattingContextGetTalker.find(dexKit, descriptors) {
            matcher {
                declaredClass(classChattingContext.clazz)
                usingEqStrings("getTalker returns null.")
            }
        }

        if (!classEnumQuoteJumpToPositionSource.find(dexKit, descriptors, throwOnFailure = false) {
            matcher {
                usingEqStrings("QuoteLongClickFromQuoteView", "QuoteClickFromTextPreviewLocateView")
            }
        }) {
            descriptors += "${nameof(QuotedMessageDirectJump)}:${nameof(classEnumQuoteJumpToPositionSource)}" to
                    "com.tencent.mm.ui.LauncherUI"
        }

        return descriptors
    }
}
