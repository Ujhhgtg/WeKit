package dev.ujhhgtg.wekit.hooks.items.contacts

import android.widget.BaseAdapter
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.tencent.mm.plugin.profile.ui.ProfileSettingUI
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.cast

@HookItem(path = "联系人与群组/显示隐藏朋友设置项", desc = "阻止微信隐藏朋友设置; 部分设置项可能显示异常, 但不影响功能")
object DisplayHiddenContactSettings : SwitchHookItem() {

    override fun onEnable() {
        ProfileSettingUI::class.asResolver()
            .firstMethod {
                name = "initView"
            }.hookAfter { param ->
                val prefScreen = param.thisObject.asResolver()
                    .firstMethod {
                        name = "getPreferenceScreen"
                        superclass()
                    }.invoke()!!
                val hiddenSet = prefScreen.asResolver()
                    .firstField {
                        type = HashSet::class
                    }.get()!! as HashSet<*>
                hiddenSet.clear()
                prefScreen.cast<BaseAdapter>().notifyDataSetChanged()
            }
    }
}
