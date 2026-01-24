package moe.ouom.wekit.constants

class Constants private constructor() {
    init {
        throw AssertionError("No instance for you!")
    }

    companion object {
        const val CLAZZ_WECHAT_LAUNCHER_UI: String = "com.tencent.mm.ui.LauncherUI"
        const val CLAZZ_BASE_APPLICATION: String = "com.tencent.mm.app.Application"
        const val CLAZZ_MM_APPLICATION_LIKE: String = "com.tencent.mm.app.MMApplicationLike"
        const val CLAZZ_SETTINGS_UI = "com.tencent.mm.plugin.setting.ui.setting.SettingsUI"
        const val CLAZZ_MAIN_SETTINGS_UI = "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
        const val CLAZZ_ICON_PREFERENCE = "com.tencent.mm.ui.base.preference.IconPreference"
        const val CLAZZ_MMActivity = "com.tencent.mm.ui.MMActivity"
        const val CLAZZ_I_PREFERENCE_SCREEN = "com.tencent.mm.ui.base.preference.IPreferenceScreen"
        const val CLAZZ_PREFERENCE = "com.tencent.mm.ui.base.preference.Preference"

        // 数据库类
        const val CLAZZ_SQLITE_DATABASE = "com.tencent.wcdb.database.SQLiteDatabase"

        // 红包消息类型
        const val TYPE_LUCKY_MONEY = 436207665  // 红包
        const val TYPE_LUCKY_MONEY_EXCLUSIVE = 469762097  // 专属

        const val PrekXXX: String = "setting_switch_value_"
        const val PrekCfgXXX: String = "setting_cfg_value_"
        const val PrekClickableXXX: String = "clickable_setting_switch_value_"
        const val PrekEnableLog: String = "setting_switch_value_prek_enable_log"
        const val PrekVerboseLog: String = "setting_switch_value_prek_verbose_log"
        const val PrekDatabaseVerboseLog: String = "setting_switch_value_prek_database_verbose_log"
    }
}
