package moe.ouom.wekit.hooks.items.chat

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
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
        methodClickEvent.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val chattingContext = param.args[0]
                    val view = param.args[2]
                    val longValue = param.args[3]
                    val stringValue = param.args[4]
                    val msgQuoteItem = param.args[5]
                    val chattingItemHolder = param.args[7]
                    val chattingItem = chattingItemHolder.asResolver()
                        .firstField { type { it != String::class.java } }.get()!!
                    val msgInfo = methodGetQuoteMessageInfo.method.invoke(
                        null,
                        false /* isGroupChat: this arg is ignored */,
                        methodChattingContextGetTalker.method.invoke(chattingContext),
                        longValue,
                        stringValue,
                        msgQuoteItem,
                        "handleQuoteMsgClick" /* hardcoded in original code */
                    )
                    methodClickToPositionEvent.method.invoke(
                        null,
                        chattingContext,
                        chattingItem,
                        msgInfo,
                        view,
                        longValue,
                        stringValue,
                        msgQuoteItem,
                        classEnumQuoteJumpToPositionSource.clazz.asResolver() // java doesn't implicitly cast integer to the upper enum type, so we still have to locate the enum class
                            .firstMethod { name = "valueOf" }.invoke("QuoteLongClickFromQuoteView")
                    )
                    param.result = null
                }
            }
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
                usingEqStrings(
                    "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                    "getQuoteMsgInfo %s msgId:%s msgSvrId:%s msgTaker:%s MsgQuoteItem(type:%s svrid:%s content:%s)"
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

        classEnumQuoteJumpToPositionSource.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("QuoteLongClickFromQuoteView", "QuoteClickFromTextPreviewLocateView")
            }
        }

        return descriptors
    }
}