package moe.ouom.wekit.hooks.items.chat

import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeMessageApi
import moe.ouom.wekit.hooks.sdk.base.WeServiceApi
import moe.ouom.wekit.hooks.sdk.base.model.MessageInfo
import moe.ouom.wekit.hooks.sdk.base.model.MessageType
import moe.ouom.wekit.hooks.sdk.ui.WeChatMessageViewApi
import moe.ouom.wekit.utils.LruCache
import java.lang.reflect.InvocationTargetException

@HookItem(path = "聊天/自动语音转文字", desc = "自动将语音消息转为文字")
object AutoSpeechToText : SwitchHookItem(),
    WeChatMessageViewApi.ICreateViewListener {

    private val processedMessages = LruCache<Long, Boolean>()

    override fun onLoad() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onUnload() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View,
        chattingContext: Any,
        msgInfo: MessageInfo
    ) {
        if (!msgInfo.isType(MessageType.VOICE)) return

        val id = msgInfo.id
        if (processedMessages[id] == true) {
            return
        }

        val apiManager = chattingContext.asResolver()
            .firstField {
                type = WeServiceApi.methodApiManagerGetApi.method.declaringClass
            }
            .get()!!
        val api = WeServiceApi.methodApiManagerGetApi.method.invoke(
            apiManager,
            WeMessageApi.classTransformChattingComponent.clazz.interfaces[0]
        )
        val chatViewItem = api.asResolver()
            .firstMethod {
                parameters(Long::class)
                returnType { clazz ->
                    clazz.name.startsWith("com.tencent.mm.ui.chatting.viewitems")
                }
            }
            .invoke(id)

        if (chatViewItem.toString() == "NoTransform") {
            processedMessages[id] = true
            try {
                api.asResolver()
                    .firstMethod {
                        parameters(
                            WeMessageApi.classMsgInfo.clazz,
                            Boolean::class.java,
                            Int::class.java,
                            Int::class.java
                        )
                        returnType = Void::class.javaPrimitiveType
                    }
                    .invoke(msgInfo.instance, false, -1, 0)
            } catch (_: InvocationTargetException) {
                // WeChat throws `java.lang.NullPointerException: getImgPath(...) must not be null`,
                // but that's not what we should care about and doesn't affect functionality
            }
        }
    }
}