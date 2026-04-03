package dev.ujhhgtg.wekit.hooks.items.chat

import android.text.Spannable
import android.view.View
import android.widget.TextView
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.utils.WeLogger

@HookItem(path = "聊天/一键撤回并重新编辑", desc = "向消息长按菜单添加菜单项, 可快捷撤回消息并将文本内容加入输入框 (没写完)")
object QuickRevokeAndEdit : SwitchHookItem(), WeChatMessageViewApi.ICreateViewListener {

    private val TAG = nameOf(QuickRevokeAndEdit)

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View
    ) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.type != 285222674) return
        val neatTextView = view.findViewWhich<View> { view ->
            view.javaClass.simpleName == "MMNeat7extView"
        } ?: return
        val textView = neatTextView.asResolver()
            .firstMethod {
                name = "getWrappedTextView"
                superclass()
            }.invoke()!! as TextView
        val spannable = textView.text as? Spannable
        WeLogger.d(TAG, "${spannable != null}")
    }
}
