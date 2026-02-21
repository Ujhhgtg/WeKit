package moe.ouom.wekit.hooks.sdk.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.view.MenuItem
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.createInstance
import com.highcapable.kavaref.extension.toClass
import com.highcapable.kavaref.extension.toClassOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.DexMethodDescriptor
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.CommonContextWrapper
import moe.ouom.wekit.ui.creator.dialog.MainSettingsDialog
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

@SuppressLint("DiscouragedApi")
@HookItem(path = "API/设置模块入口")
object WeSettingsInjector : ApiHookItem(), IDexFind {

    private val methodSetKey by dexMethod()
    private val methodSetTitle by dexMethod()
    private val methodGetKey by dexMethod()
    private val methodAddPref by dexMethod()

    private const val TAG = "WeSettingInjector"
    private const val KEY_WEKIT_ENTRY = "wekit_settings_entry"
    private const val TITLE_WEKIT_ENTRY = "WeKit 设置"
    private const val PREFERENCE_CLASS_NAME = "com.tencent.mm.ui.base.preference.Preference"

    @SuppressLint("NonUniqueDexKitData")
    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // 查找 Preference 类
        val prefClass = dexKit.findClass {
            matcher { className = PREFERENCE_CLASS_NAME }
        }.singleOrNull() ?: run {
            WeLogger.e("WeSettingInjector: Preference 类未找到")
            return descriptors
        }

        // 查找 setKey 方法
        methodSetKey.find(dexKit, allowMultiple = true, descriptors = descriptors) {
            searchPackages("com.tencent.mm.ui.base.preference")
            matcher {
                declaredClass = PREFERENCE_CLASS_NAME
                returnType = "void"
                paramTypes("java.lang.String")
                usingStrings("Preference")
            }
        }

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
                DexMethodDescriptor(
                    target.className,
                    target.methodName,
                    target.methodSign
                )
            )
            methodSetTitle.getDescriptorString()?.let {
                descriptors[methodSetTitle.key] = it
            }
        }

        // 查找 getKey 方法
        WeLogger.d("WeSettingInjector", "Searching for getKey method in ${prefClass.name}")
        val getKeyCandidates = prefClass.findMethod {
            matcher {
                paramCount = 0
                returnType = "java.lang.String"
            }
        }
        WeLogger.d(
            "WeSettingInjector",
            "Found ${getKeyCandidates.size} String methods with 0 params: ${getKeyCandidates.map { it.name }}"
        )

        val targetGetKey = getKeyCandidates.firstOrNull { it.name != "toString" }
        WeLogger.d("WeSettingInjector", "Selected getKey method: ${targetGetKey?.name}")

        if (targetGetKey != null) {
            methodGetKey.setDescriptor(
                DexMethodDescriptor(
                    targetGetKey.className,
                    targetGetKey.methodName,
                    targetGetKey.methodSign
                )
            )
            methodGetKey.getDescriptorString()?.let {
                descriptors[methodGetKey.key] = it
                WeLogger.d("WeSettingInjector", "Successfully saved getKey descriptor: $it")
            }
        } else {
            WeLogger.e("WeSettingInjector", "Failed to find getKey method!")
        }

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
            methodAddPref.find(dexKit, allowMultiple = true, descriptors = descriptors) {
                searchPackages("com.tencent.mm.ui.base.preference")
                matcher {
                    declaredClass = adapterClass.name
                    paramTypes(PREFERENCE_CLASS_NAME, "int")
                    returnType = "void"
                }
            }
        }

        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        // 尝试 Hook 旧版 UI
        tryHookLegacySettings(classLoader)

        // 尝试 Hook 新版 UI (8.0.67+)
        // tryHookNewSettingsMethod1(classLoader)

        // 尝试 Hook 新版 UI (8.0.67+), WAuxiliary 方法
        tryHookNewSettings(classLoader)
    }

    /**
     * 适配旧版 SettingsUI (基于 PreferenceScreen)
     */
    private fun tryHookLegacySettings(classLoader: ClassLoader) {
        try {
            // 检查类是否存在
            val clsSettingsUi = "com.tencent.mm.plugin.setting.ui.setting.SettingsUI"
                .toClassOrNull(classLoader) ?: return

            val setKeyMethod = methodSetKey.method
            val setTitleMethod = methodSetTitle.method
            val getKeyMethod = methodGetKey.method
            val addPrefMethod = methodAddPref.method

            val mInitView = clsSettingsUi.asResolver().firstMethod {
                name = "initView"
                parameterCount = 0
            }

            mInitView.hookAfter { param: XC_MethodHook.MethodHookParam ->
                val activity = param.thisObject as Activity
                val context = activity as Context

                try {
                    val clsIconPref = "com.tencent.mm.ui.base.preference.IconPreference".toClass(classLoader)
                    val prefInstance = clsIconPref.createInstance(context)

                    setKeyMethod.invoke(prefInstance, KEY_WEKIT_ENTRY)
                    setTitleMethod.invoke(prefInstance, TITLE_WEKIT_ENTRY)

                    val prefScreen = XposedHelpers.callMethod(activity, "getPreferenceScreen")

                    addPrefMethod.invoke(prefScreen, prefInstance, 0)

                } catch (e: Throwable) {
                    WeLogger.e("WeSettingInjector: 插入选项失败", e)
                }
            }

            WeLogger.i("WeSettingInjector: Created WeKit setting")

            clsSettingsUi.hookBefore( "onPreferenceTreeClick") { param ->
                if (param.args.size < 2) return@hookBefore
                val preference = param.args[1] ?: return@hookBefore

                val key = getKeyMethod.invoke(preference) as? String
                WeLogger.d("WeKit Debug: Click key = $key")

                if (KEY_WEKIT_ENTRY == key) {
                    val activity = param.thisObject as Activity

                    val ctx = CommonContextWrapper.createAppCompatContext(activity)
                    val dialog = MainSettingsDialog(ctx)
                    dialog.show()

                    param.result = true
                }
            }

            WeLogger.i("WeSettingInjector: Hooked onPreferenceTreeClick")

        } catch (t: Throwable) {
            WeLogger.e("Legacy Settings: Hook 流程异常", t)
        }
    }

    private fun tryHookNewSettings(classLoader: ClassLoader) {
        val newSettingsCls = "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI".toClassOrNull(classLoader) ?: return

        newSettingsCls.hookAfter("onCreate") { param ->
            if (param.thisObject.javaClass
                == "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI".toClassOrNull(classLoader)) {
                val activity = param.thisObject as Activity
                activity.asResolver()
                    .firstMethod {
                        name = "addTextOptionMenu"
                        parameters(Int::class, String::class, MenuItem.OnMenuItemClickListener::class)
                        superclass()
                    }
                    .invoke(0, "WeKit", OnSettingsInjectedMenuItemClickListener(activity))
            }
        }
    }

    private fun openSettingsDialog(activity: Activity) {
        try {
            MainSettingsDialog(activity).show()
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to open settings dialog", e)
        }
    }

    override fun unload(classLoader: ClassLoader) {}

    private class OnSettingsInjectedMenuItemClickListener(val activity: Activity) : MenuItem.OnMenuItemClickListener {
        override fun onMenuItemClick(p0: MenuItem): Boolean {
            try {
                MainSettingsDialog(activity).show()
            } catch (e: Throwable) {
                WeLogger.e(TAG, "failed to open settings dialog", e)
            }
            return true
        }
    }
}