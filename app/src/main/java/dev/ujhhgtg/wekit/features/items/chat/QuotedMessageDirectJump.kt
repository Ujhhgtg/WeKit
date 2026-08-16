package dev.ujhhgtg.wekit.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.ListItem
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.enumValueOfClass

@Feature(
    id = "引用消息直达",
    nameRes = "feature_quoted_message_direct_jump_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_quoted_message_direct_jump_description",
)
object QuotedMessageDirectJump : ClickableFeature(), IResolveDex {

    private var messageListDirectJump by prefOption("chat_quoted_direct_jump_message_list", true)
    private var inputBoxDirectJump by prefOption("chat_quoted_direct_jump_input_box", true)

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
            declaredClass(methodClickEvent.data.declaredClassName)
            usingEqStrings(
                "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                "handleItemClickToPositionEvent,quotedMsg is null!"
            )
        }
    }
    private val methodGetQuoteMessageInfo by dexMethod {
        matcher {
            declaredClass(methodClickEvent.data.declaredClassName)
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
            declaredClass(classChattingContext.data.name)
            usingEqStrings("getTalker returns null.")
        }
    }

    override fun onEnable() {
        methodClickEvent.hookBefore {
            val isInputBox = args[1] == null
            val shouldDirectJump = if (isInputBox) inputBoxDirectJump else messageListDirectJump
            if (!shouldDirectJump) return@hookBefore

            val chattingContext = args[0]
            val view = args[2]
            val longValue = args[3]
            val stringValue = args[4]
            val msgQuoteItem = args[5]
            val chattingItemHolder = args[7]!!
            val chattingItem = chattingItemHolder.reflekt()
                .firstField { type { it != String::class.java } }.get()
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

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var messageList by remember { mutableStateOf(messageListDirectJump) }
            var inputBox by remember { mutableStateOf(inputBoxDirectJump) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_quoted_message_direct_jump_name)) },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
                                messageList = !messageList
                                messageListDirectJump = messageList
                            },
                            trailingContent = {
                                Switch(checked = messageList, onCheckedChange = null)
                            },
                            supportingContent = { Text(stringResource(R.string.chat_quote_jump_message_list_description)) },
                            content = { Text(stringResource(R.string.chat_quote_jump_message_list)) },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                inputBox = !inputBox
                                inputBoxDirectJump = inputBox
                            },
                            trailingContent = {
                                Switch(checked = inputBox, onCheckedChange = null)
                            },
                            supportingContent = { Text(stringResource(R.string.chat_quote_jump_input_box_description)) },
                            content = { Text(stringResource(R.string.chat_quote_jump_input_box)) },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
