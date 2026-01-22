package moe.ouom.wekit.hooks.sdk

import android.R
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.view.Menu
import android.view.MenuItem
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.config.RuntimeConfig
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.core.dsl.lazyDexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.CommonContextWrapper
import moe.ouom.wekit.ui.creator.dialog.MainSettingsDialog
import moe.ouom.wekit.util.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

@SuppressLint("DiscouragedApi")
@HookItem(path = "设置模块入口")
class WeSettingInjector : ApiHookItem(), IDexFind {

    // ========== DSL ==========
    private val methodSetKey by lazyDexMethod("MethodSetKey")
    private val methodSetTitle by lazyDexMethod("MethodSetTitle")
    private val methodGetKey by lazyDexMethod("MethodGetKey")
    private val methodAddPref by lazyDexMethod("MethodAddPref")

    companion object {
        private const val KEY_WEKIT_ENTRY = "wekit_settings_entry"
        private const val TITLE_WEKIT_ENTRY = "WeKit 设置"
        private const val MENU_ID_WEKIT = 11451419
        private const val CLS_PREFERENCE = "com.tencent.mm.ui.base.preference.Preference"
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // 查找 Preference 类
        val prefClass = dexKit.findClass {
            matcher { className = CLS_PREFERENCE }
        }.singleOrNull() ?: run {
            WeLogger.e("WeSettingInjector: Preference 类未找到")
            return descriptors
        }

        // 查找 setKey 方法
        methodSetKey.findDexClassMethodOptional(dexKit, allowMultiple = true) {
            searchPackages("com.tencent.mm.ui.base.preference")
            matcher {
                declaredClass = CLS_PREFERENCE
                returnType = "void"
                paramTypes("java.lang.String")
                usingStrings("Preference")
            }
        }
        methodSetKey.getDescriptorString()?.let { descriptors["methodSetKey"] = it }

        // 查找 setTitle 方法
        val setTitleCandidates = prefClass.findMethod {
            matcher {
                returnType = "void"
                paramTypes("java.lang.CharSequence")
            }
        }
        if (setTitleCandidates.isNotEmpty()) {
            val target = setTitleCandidates.last()
            methodSetTitle.setDescriptor(
                moe.ouom.wekit.dexkit.DexMethodDescriptor(
                    target.className,
                    target.methodName,
                    target.methodSign
                )
            )
        }
        methodSetTitle.getDescriptorString()?.let { descriptors["methodSetTitle"] = it }

        // 查找 getKey 方法
        val getKeyCandidates = prefClass.findMethod {
            matcher {
                paramCount = 0
                returnType = "java.lang.String"
            }
        }
        val targetGetKey = getKeyCandidates.singleOrNull { it.name != "toString" }
        if (targetGetKey != null) {
            methodGetKey.setDescriptor(
                moe.ouom.wekit.dexkit.DexMethodDescriptor(
                    targetGetKey.className,
                    targetGetKey.methodName,
                    targetGetKey.methodSign
                )
            )
        }
        methodGetKey.getDescriptorString()?.let { descriptors["methodGetKey"] = it }

        // 查找 Adapter 类和 addPreference 方法
        val adapterClass = dexKit.findClass {
            searchPackages("com.tencent.mm.ui.base.preference")
            matcher {
                superClass = "android.widget.BaseAdapter"
                methods {
                    add {
                        modifiers = Modifier.PUBLIC
                        name = "getView"
                        paramCount = 3
                    }
                    add {
                        name = "<init>"
                        paramCount = 3
                    }
                }
            }
        }.singleOrNull()

        if (adapterClass != null) {
            methodAddPref.findDexClassMethodOptional(dexKit, allowMultiple = true) {
                searchPackages("com.tencent.mm.ui.base.preference")
                matcher {
                    declaredClass = adapterClass.name
                    paramTypes(CLS_PREFERENCE, "int")
                    returnType = "void"
                }
            }
        }
        methodAddPref.getDescriptorString()?.let { descriptors["methodAddPref"] = it }

        return descriptors
    }

    override fun loadFromCache(cache: Map<String, Any>) {
        (cache["methodSetKey"] as? String)?.let { methodSetKey.setDescriptorFromCache(it) }
        (cache["methodSetTitle"] as? String)?.let { methodSetTitle.setDescriptorFromCache(it) }
        (cache["methodGetKey"] as? String)?.let { methodGetKey.setDescriptorFromCache(it) }
        (cache["methodAddPref"] as? String)?.let { methodAddPref.setDescriptorFromCache(it) }
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
                XposedHelpers.findClass(Constants.Companion.CLAZZ_SETTINGS_UI, classLoader)
            } catch (_: Throwable) {
                return // 类不存在，跳过
            }

            // 检查微信版本
            if (RuntimeConfig.getWechatVersionCode() >= 3000) {
                return // 是新版，跳过
            }

            // 从 DSL 获取方法
            val setKeyMethod = methodSetKey.getMethod(classLoader)
            val setTitleMethod = methodSetTitle.getMethod(classLoader)
            val getKeyMethod = methodGetKey.getMethod(classLoader)
            val addPrefMethod = methodAddPref.getMethod(classLoader)

            if (setKeyMethod == null || setTitleMethod == null || getKeyMethod == null || addPrefMethod == null) {
                WeLogger.e("WeSettingInjector: 关键方法未找到，跳过 Hook。请先运行 DexKit 分析")
                return
            }

            val mInitView = XposedHelpers.findMethodExact(
                clsSettingsUI,
                "initView",
                *arrayOf<Class<*>>()
            )

            hookAfter(mInitView) { param: XC_MethodHook.MethodHookParam ->
                val activity = param.thisObject as Activity
                val context = activity as Context

                try {
                    val clsIconPref = XposedHelpers.findClass(Constants.Companion.CLAZZ_ICON_PREFERENCE, classLoader)
                    val prefInstance = XposedHelpers.newInstance(clsIconPref, context)

                    setKeyMethod.invoke(prefInstance, KEY_WEKIT_ENTRY)
                    setTitleMethod.invoke(prefInstance, TITLE_WEKIT_ENTRY)

                    val prefScreen = XposedHelpers.callMethod(activity, "getPreferenceScreen")

                    addPrefMethod.invoke(prefScreen, prefInstance, 0)

                } catch (e: Throwable) {
                    WeLogger.e("WeSettingInjector: 插入选项失败", e)
                }
            }

            WeLogger.i("WeSettingInjector: Created WeKit setting")

            XposedBridge.hookAllMethods(
                clsSettingsUI,
                "onPreferenceTreeClick",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.args.size < 2) return
                            val preference = param.args[1] ?: return

                            val key = getKeyMethod.invoke(preference) as? String
                            WeLogger.d("WeKit Debug: Click key = $key")

                            if (KEY_WEKIT_ENTRY == key) {
                                val activity = param.thisObject as Activity

                                val fixContext = CommonContextWrapper.createAppCompatContext(activity)
                                val dialog = MainSettingsDialog(fixContext)
                                dialog.show()
                                param.result = true
                            }
                        } catch (t: Throwable) {
                            WeLogger.e(t)
                            WeLogger.e("WeSettingInjector: Click handle error", t)
                        }
                    }
                }
            )

            WeLogger.i("WeSettingInjector: Hooked onPreferenceTreeClick")

        } catch (t: Throwable) {
            WeLogger.e("Legacy Settings: Hook 流程异常", t)
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
                XposedHelpers.findClass(Constants.Companion.CLAZZ_MAIN_SETTINGS_UI, classLoader)
            } catch (_: Throwable) {
                return
            }

            // 获取基类 MMActivity
            val clsMMActivity = XposedHelpers.findClass(Constants.Companion.CLAZZ_MMActivity, classLoader)

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
                if (activity.javaClass.name == Constants.Companion.CLAZZ_MAIN_SETTINGS_UI) {
                    val menu = param.args[0] as? Menu ?: return@hookAfter
                    // 防止重复添加
                    if (menu.findItem(MENU_ID_WEKIT) == null) {
                        menu.add(0, MENU_ID_WEKIT, 0, TITLE_WEKIT_ENTRY)
                            .setIcon(R.drawable.ic_menu_preferences)
                        WeLogger.i("New Settings: Injected Menu entry into ${activity.javaClass.simpleName}")
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
                if (activity.javaClass.name == Constants.Companion.CLAZZ_MAIN_SETTINGS_UI) {
                    val item = param.args[0] as? MenuItem ?: return@hookBefore
                    if (item.itemId == MENU_ID_WEKIT) {
                        openSettingsDialog(activity)
                        param.result = true // 消费事件，阻止传递给微信处理
                    }
                }
            }

            WeLogger.i("New Settings: Hook setup complete (Targeting MMActivity)")

        } catch (t: Throwable) {
            WeLogger.e("New Settings: Hook 流程异常", t)
        }
    }

    private fun openSettingsDialog(activity: Activity) {
        try {
            val fixContext = CommonContextWrapper.createAppCompatContext(activity)
            val dialog = MainSettingsDialog(fixContext)
            dialog.show()
        } catch (e: Throwable) {
            WeLogger.e("Failed to open settings dialog", e)
        }
    }

    override fun unload(classLoader: ClassLoader) {}
}