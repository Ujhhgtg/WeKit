package moe.ouom.wekit.constants

class Constants private constructor() {
    init {
        throw AssertionError("No instance for you!")
    }

    companion object {
        const val CLAZZ_WECHAT_LAUNCHER_UI: String = "com.tencent.mm.ui.LauncherUI"
        const val CLAZZ_BASE_APPLICATION: String = "com.tencent.mm.app.Application"
        const val CLAZZ_SETTINGS_UI = "com.tencent.mm.plugin.setting.ui.setting.SettingsUI"
        const val CLAZZ_MAIN_SETTINGS_UI = "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
        const val CLAZZ_ICON_PREFERENCE = "com.tencent.mm.ui.base.preference.IconPreference"
        const val CLAZZ_MMActivity = "com.tencent.mm.ui.MMActivity"
        const val CLAZZ_I_PREFERENCE_SCREEN = "com.tencent.mm.ui.base.preference.IPreferenceScreen"
        const val CLAZZ_PREFERENCE = "com.tencent.mm.ui.base.preference.Preference"
        

        const val PrekXXX: String = "setting_switch_value_"
        const val PrekCfgXXX: String = "setting_cfg_value_"
        const val PrekClickableXXX: String = "clickable_setting_switch_value_"
        const val PrekEnableLog: String = "setting_switch_value_prek_enable_log"
    }
}
