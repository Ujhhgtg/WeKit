package moe.ouom.wekit.hooks.base

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.view.Menu
import android.view.MenuItem
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.config.RuntimeConfig
import moe.ouom.wekit.constants.Constants.Companion.CLAZZ_ICON_PREFERENCE
import moe.ouom.wekit.constants.Constants.Companion.CLAZZ_MAIN_SETTINGS_UI
import moe.ouom.wekit.constants.Constants.Companion.CLAZZ_MMActivity
import moe.ouom.wekit.constants.Constants.Companion.CLAZZ_SETTINGS_UI
import moe.ouom.wekit.dexkit.TargetManager
import moe.ouom.wekit.hooks._base.ApiHookItem
import moe.ouom.wekit.hooks._core.annotation.HookItem
import moe.ouom.wekit.ui.CommonContextWrapper
import moe.ouom.wekit.ui.creator.dialog.MainSettingsDialog
import moe.ouom.wekit.util.log.Logger

@SuppressLint("DiscouragedApi")
@HookItem(path = "设置模块入口")
class WeSettingInjector : ApiHookItem() {

    companion object {
        private const val KEY_WEKIT_ENTRY = "wekit_settings_entry"
        private const val TITLE_WEKIT_ENTRY = "WeKit 设置"
        private const val MENU_ID_WEKIT = 11451419
    }

    override fun entry(classLoader: ClassLoader) {
        // 尝试 Hook 旧版 UI
        tryHookLegacySettings(classLoader)

        // 尝试 Hook 新版 UI (8.0.67+)
        tryHookNewSettings(classLoader)
    }

    /**
     * 适配旧版 SettingsUI (基于 PreferenceScreen)
     */
    private fun tryHookLegacySettings(classLoader: ClassLoader) {
        try {
            // 检查类是否存在
            val clsSettingsUI = try {
                XposedHelpers.findClass(CLAZZ_SETTINGS_UI, classLoader)
            } catch (_: Throwable) {
                return // 类不存在，跳过
            }

            // 检查微信版本
            if (RuntimeConfig.getWechatVersionCode() >= 3000) {
                return // 是新版，跳过
            }

            val methodSetKey = TargetManager.requireMethod(TargetManager.KEY_METHOD_SET_KEY)
            val methodSetTitle = TargetManager.requireMethod(TargetManager.KEY_METHOD_SET_TITLE)
            val methodGetKey = TargetManager.requireMethod(TargetManager.KEY_METHOD_GET_KEY)
            val methodAddPref = TargetManager.requireMethod(TargetManager.KEY_METHOD_ADD_PREF)

            if (methodSetKey == null || methodSetTitle == null || methodGetKey == null || methodAddPref == null) {
                Logger.e("WeSettingInjector: 关键方法未找到，跳过 Hook。请先运行 DexKit 分析 （TargetManager）")
                return
            }

            val mInitView = XposedHelpers.findMethodExact(
                clsSettingsUI,
                "initView",
                *arrayOf<Class<*>>()
            )

            hookAfter(mInitView) { param: MethodHookParam ->
                val activity = param.thisObject as Activity
                val context = activity as Context

                try {
                    val clsIconPref = XposedHelpers.findClass(CLAZZ_ICON_PREFERENCE, classLoader)
                    val prefInstance = XposedHelpers.newInstance(clsIconPref, context)

                    methodSetKey.invoke(prefInstance, KEY_WEKIT_ENTRY)
                    methodSetTitle.invoke(prefInstance, TITLE_WEKIT_ENTRY)

                    val prefScreen = XposedHelpers.callMethod(activity, "getPreferenceScreen")

                    methodAddPref.invoke(prefScreen, prefInstance, 0)

                } catch (e: Throwable) {
                    Logger.e("WeSettingInjector: 插入选项失败", e)
                }
            }

            Logger.i("WeSettingInjector: Created WeKit setting")

            XposedBridge.hookAllMethods(
                clsSettingsUI,
                "onPreferenceTreeClick",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.args.size < 2) return
                            val preference = param.args[1] ?: return

                            val key = methodGetKey.invoke(preference) as? String
                            Logger.d("WeKit Debug: Click key = $key")

                            if (KEY_WEKIT_ENTRY == key) {
                                val activity = param.thisObject as Activity

                                val fixContext = CommonContextWrapper.createAppCompatContext(activity)
                                val dialog = MainSettingsDialog(fixContext)
                                dialog.show()
                                param.result = true
                            }
                        } catch (t: Throwable) {
                            Logger.e(t)
                            Logger.e("WeSettingInjector: Click handle error", t)
                        }
                    }
                }
            )

            Logger.i("WeSettingInjector: Hooked onPreferenceTreeClick")

        } catch (t: Throwable) {
            Logger.e("Legacy Settings: Hook 流程异常", t)
        }
    }

    /**
     * 适配新版 MainSettingsUI (基于 Menu 注入)
     * 因为新版 UI 继承结构复杂且混淆严重，通过 onCreateOptionsMenu 注入最稳定
     */
    private fun tryHookNewSettings(classLoader: ClassLoader) {
        try {
            // 检查新版 UI 是否存在，不存在则直接退出，不进行 Hook
            try {
                XposedHelpers.findClass(CLAZZ_MAIN_SETTINGS_UI, classLoader)
            } catch (_: Throwable) {
                return
            }

            // 获取基类 MMActivity
            val clsMMActivity = XposedHelpers.findClass(CLAZZ_MMActivity, classLoader)

            // ---------------------------------------------------------------------
            // Hook 基类 MMActivity 的 onCreateOptionsMenu
            // ---------------------------------------------------------------------
            val mOnCreateOptionsMenu = XposedHelpers.findMethodExact(
                clsMMActivity,
                "onCreateOptionsMenu",
                Menu::class.java
            )

            hookAfter(mOnCreateOptionsMenu) { param ->
                val activity = param.thisObject
                // 检查当前 Activity 实例的类名是否为 MainSettingsUI
                if (activity.javaClass.name == CLAZZ_MAIN_SETTINGS_UI) {
                    val menu = param.args[0] as? Menu ?: return@hookAfter
                    // 防止重复添加
                    if (menu.findItem(MENU_ID_WEKIT) == null) {
                        menu.add(0, MENU_ID_WEKIT, 0, TITLE_WEKIT_ENTRY)
                            .setIcon(android.R.drawable.ic_menu_preferences)
                        Logger.i("New Settings: Injected Menu entry into ${activity.javaClass.simpleName}")
                    }
                }
            }

            // ---------------------------------------------------------------------
            // Hook 基类 MMActivity 的 onOptionsItemSelected 以处理点击事件
            // ---------------------------------------------------------------------
            val mOnOptionsItemSelected = XposedHelpers.findMethodExact(
                clsMMActivity,
                "onOptionsItemSelected",
                MenuItem::class.java
            )

            hookBefore(mOnOptionsItemSelected) { param ->
                val activity = param.thisObject as Activity
                // 检查实例类型
                if (activity.javaClass.name == CLAZZ_MAIN_SETTINGS_UI) {
                    val item = param.args[0] as? MenuItem ?: return@hookBefore
                    if (item.itemId == MENU_ID_WEKIT) {
                        openSettingsDialog(activity)
                        param.result = true // 消费事件，阻止传递给微信处理
                    }
                }
            }

            Logger.i("New Settings: Hook setup complete (Targeting MMActivity)")

        } catch (t: Throwable) {
            Logger.e("New Settings: Hook 流程异常", t)
        }
    }

    private fun openSettingsDialog(activity: Activity) {
        try {
            val fixContext = CommonContextWrapper.createAppCompatContext(activity)
            val dialog = MainSettingsDialog(fixContext)
            dialog.show()
        } catch (e: Throwable) {
            Logger.e("Failed to open settings dialog", e)
        }
    }

    override fun unload(classLoader: ClassLoader) {}
}