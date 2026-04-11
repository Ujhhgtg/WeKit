package dev.ujhhgtg.wekit.hooks.items.system

import android.content.ComponentName
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.hooks.api.ui.WeStartActivityApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger

@HookItem(path = "系统与隐私/使用旧版公众号列表", description = "!!! 仅适用于旧版本微信 !!!\n新版本已在代码中移除旧 UI, 无法继续使用本功能")
object UseLegacyOfficialAccountsView : SwitchHookItem(), WeStartActivityApi.IStartActivityListener {

    override fun onEnable() {
        WeStartActivityApi.addListener(this)
    }

    override fun onDisable() {
        WeStartActivityApi.removeListener(this)
    }

    override fun onStartActivity(param: XC_MethodHook.MethodHookParam, intent: Intent) {
        val className = intent.component?.className
        if (className == "${PackageNames.WECHAT}.plugin.brandservice.ui.flutter.BizFlutterTLFlutterViewActivity" ||
            className == "${PackageNames.WECHAT}.plugin.brandservice.ui.timeline.BizTimeLineUI") {
            WeLogger.d(This.Class.simpleName, "redirected $className")
            intent.component = ComponentName(
                HostInfo.packageName,
                "${PackageNames.WECHAT}.ui.conversation.NewBizConversationUI"
            )
        }
    }
}
