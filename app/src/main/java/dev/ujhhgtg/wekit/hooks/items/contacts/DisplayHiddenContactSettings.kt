package dev.ujhhgtg.wekit.hooks.items.contacts

import android.widget.BaseAdapter
import com.tencent.mm.plugin.profile.ui.ProfileSettingUI
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.resolve

@HookItem(path = "联系人与群组/显示隐藏朋友设置项", description = "阻止微信隐藏朋友设置; 部分设置项可能显示异常, 但不影响功能")
object DisplayHiddenContactSettings : SwitchHookItem() {

    override fun onEnable() {
        ProfileSettingUI::class.resolve()
            .firstMethod {
                name = "initView"
            }.hookAfter {
                val prefScreen = thisObject.asResolver()
                    .firstMethod {
                        name = "getPreferenceScreen"
                        superclass()
                    }.invoke()!!
                val hiddenSet = prefScreen.asResolver()
                    .firstField {
                        type = HashSet::class
                    }.get()!! as HashSet<*>
                hiddenSet.clear()
                (prefScreen as BaseAdapter).notifyDataSetChanged()
            }
    }
}
