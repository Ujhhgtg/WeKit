package moe.ouom.wekit.hooks.sdk.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.core.factory.HookItemFactory.getItem
import moe.ouom.wekit.hooks.item.chat.risk.WeSendXml
import moe.ouom.wekit.hooks.sdk.api.WeMessageApi
import moe.ouom.wekit.utils.Initiator.loadClass
import moe.ouom.wekit.utils.common.Toasts
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(path = "API/聊天界面扩展")
class WeChatFooterApi : ApiHookItem() {
    companion object {
        private const val TAG = "WeChatFooterApi"
        private const val CLASS_CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
        private const val FIELD_TO_USER = "wekit_cache_toUser"
    }

    override fun entry(classLoader: ClassLoader) {
        try {
            val chatFooterClass = loadClass(CLASS_CHAT_FOOTER)

            hookAfter(
                chatFooterClass,
                { param ->
                    val chatFooterInstance = param.thisObject
                    findAndBindSendButton(chatFooterInstance)
                },
                Context::class.java,
                AttributeSet::class.java,
                Int::class.java
            )

            try {
                hookAfter(chatFooterClass, "setUserName") { param ->
                    val toUser = param.args[0] as? String
                    if (!toUser.isNullOrEmpty()) {
                        XposedHelpers.setAdditionalInstanceField(
                            param.thisObject,
                            FIELD_TO_USER,
                            toUser
                        )
                        WeLogger.d(TAG, "捕获并缓存 toUser: $toUser")
                    }
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "Hook setUserName 失败，可能方法名被混淆", e)
            }

            WeLogger.i(TAG, "ChatFooter Hook 初始化成功")

        } catch (e: Throwable) {
            WeLogger.e(TAG, "ChatFooter Hook 初始化失败", e)
        }
    }

    private fun findAndBindSendButton(chatFooter: Any) {
        try {
            val fields = chatFooter.javaClass.declaredFields
            for (field in fields) {
                field.isAccessible = true
                if (!Button::class.java.isAssignableFrom(field.type)) continue

                val button = field.get(chatFooter) as? Button ?: continue
                val text = button.text?.toString()?.trim() ?: ""

                if (text == "发送" || text.equals("Send", ignoreCase = true)) {
                    WeLogger.i(TAG, "定位到发送按钮 -> 字段名: ${field.name}")
                    button.setOnLongClickListener { view ->
                        try {
                            handleLongClickLogic(chatFooter, view)
                        } catch (e: Throwable) {
                            WeLogger.e(TAG, "业务逻辑执行出错", e)
                        }
                        true
                    }
                    break
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "查找按钮过程出错", e)
        }
    }

    private fun handleLongClickLogic(chatFooter: Any, buttonView: View) {
        val content = try {
            XposedHelpers.callMethod(chatFooter, "getLastText") as? String
        } catch (_: Throwable) {
            WeLogger.w(TAG, "getLastText 调用失败")
            null
        }

        val toUser = XposedHelpers.getAdditionalInstanceField(chatFooter, FIELD_TO_USER) as? String

        WeLogger.d(TAG, "content: $content, toUser: $toUser")

        if (toUser != null) {
            if (!content.isNullOrEmpty() && toUser.isNotEmpty()) {
                if (getItem(WeSendXml::class.java).isEnabled) {
                    val isSuccess = WeMessageApi.INSTANCE?.sendXmlAppMsg(toUser, content)
                    if (isSuccess == false) {
                        WeLogger.e(TAG, "发送 XML 消息失败")
                        Toasts.showToast("发送 XML 消息失败，请检查格式")
                    } else {
                        findInputEditText(chatFooter as? View, content)?.setText("")
                    }
                }
            } else {
                WeLogger.e(TAG, "信息不完整，无法发送！User: $toUser, Content: $content")
                Toasts.showToast("未获取到当前聊天对象或内容为空")
            }
        }
    }

    private fun findInputEditText(view: View?, content: String): android.widget.EditText? {
        if (view is android.widget.EditText) {
            return view
        }

        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val result = findInputEditText(child, content)
                if (content == (result?.text?.toString() ?: "")) {
                    return result
                }
            }
        }

        return null
    }
}