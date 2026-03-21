package dev.ujhhgtg.wekit.hooks.api.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import com.android.dx.stock.ProxyBuilder
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.ClassLoaderProvider
import com.highcapable.kavaref.extension.createInstance
import com.highcapable.kavaref.extension.toClass
import com.highcapable.kavaref.extension.toClassOrNull
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.nameof.nameof
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.dexkit.DexMethodDescriptor
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.MainSettingsDialog
import dev.ujhhgtg.wekit.utils.KnownPaths
import dev.ujhhgtg.wekit.utils.createDirectoriesNoThrow
import dev.ujhhgtg.wekit.utils.logging.WeLogger
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.nio.file.Path
import kotlin.io.path.div

@SuppressLint("DiscouragedApi", "StaticFieldLeak")
@HookItem(path = "API/设置模块入口")
object WeSettingsInjector : ApiHookItem(), IResolvesDex {

    private val methodSetKey by dexMethod()
    private val methodSetTitle by dexMethod()
    private val methodGetKey by dexMethod()
    private val methodAddPref by dexMethod()

    // method 3
    private val classSettingItemClassesProvider by dexClass()
    private val classBaseSettingItem by dexClass()
    private val classSettingLocation by dexClass()
    private val methodSettingGroupAccountInfoReturns1 by dexMethod()

    private val TAG = nameof(WeSettingsInjector)

    private const val PREFS_KEY = "wekit_settings_entry"
    private const val PREFS_TITLE = "${BuildConfig.TAG} 设置"
    private const val PREFERENCE_CLASS_NAME = "com.tencent.mm.ui.base.preference.Preference"

    @SuppressLint("NonUniqueDexKitData")
    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // 查找 Preference 类
        val prefClass = dexKit.findClass {
            matcher { className = PREFERENCE_CLASS_NAME }
        }.singleOrNull() ?: run {
            WeLogger.e(TAG, "Preference 类未找到")
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
        val getKeyCandidates = prefClass.findMethod {
            matcher {
                paramCount = 0
                returnType = "java.lang.String"
            }
        }

        val targetGetKey = getKeyCandidates.firstOrNull { it.name != "toString" }

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
            }
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
            methodAddPref.find(dexKit, descriptors, allowMultiple = true) {
                searchPackages("com.tencent.mm.ui.base.preference")
                matcher {
                    declaredClass = adapterClass.name
                    paramTypes(PREFERENCE_CLASS_NAME, "int")
                    returnType = "void"
                }
            }
        }

        if (!classSettingItemClassesProvider.find(dexKit, descriptors, throwOnFailure = false) {
            matcher {
                usingEqStrings("Repairer_Setting")

                superClass {
                    usingEqStrings("type")
                }
            }
        }) {
            descriptors += "${nameof(WeSettingsInjector)}:${nameof(classSettingItemClassesProvider)}" to
                    "com.tencent.mm.ui.LauncherUI"
        }

        if (!classBaseSettingItem.find(dexKit, descriptors, throwOnFailure = false) {
            matcher {
                usingEqStrings("", "activity", "context", "intent")

                addMethod {
                    name = "<init>"
                    paramTypes("androidx.appcompat.app.AppCompatActivity")
                }

                addInterface {
                    className("com.tencent.mm.plugin.newtips.model", StringMatchType.StartsWith)
                }
            }
        }) {
            descriptors += "${nameof(WeSettingsInjector)}:${nameof(classBaseSettingItem)}" to
                    "com.tencent.mm.ui.LauncherUI"
        }

        if (!classSettingLocation.find(dexKit, descriptors, throwOnFailure = false) {
            matcher {
                usingEqStrings("SettingLocation(parentGroup=", ", frontItem=")
            }
        }) {
            descriptors += "${nameof(WeSettingsInjector)}:${nameof(classSettingLocation)}" to
                    "com.tencent.mm.ui.LauncherUI"
        }

        if (!methodSettingGroupAccountInfoReturns1.find(dexKit, descriptors, throwOnFailure = false) {
            matcher {
                declaredClass = "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupAccountInfo"
                usingNumbers(1)
                returnType = "int"
            }
        }) {
            descriptors += "${nameof(WeSettingsInjector)}:${nameof(methodSettingGroupAccountInfoReturns1)}" to
                    "Lcom/tencent/mm/ui/LauncherUI;->()Lcom/tencent/mm/ui/LauncherUI;"
        }

        return descriptors
    }

    override fun onEnable() {
        // 尝试 Hook 旧版 UI
        tryHookLegacySettings()

        // 尝试 Hook 新版 UI (8.0.67+)
        // tryHookNewSettingsMethod1()
        tryHookNewSettingsMethod2()
        // tryHookNewSettingsMethod3()
    }

    /**
     * 适配旧版 SettingsUI (基于 PreferenceScreen)
     */
    private fun tryHookLegacySettings() {
        // 检查类是否存在
        val clsSettingsUi = "com.tencent.mm.plugin.setting.ui.setting.SettingsUI"
            .toClassOrNull() ?: return

        val setKeyMethod = methodSetKey.method
        val setTitleMethod = methodSetTitle.method
        val getKeyMethod = methodGetKey.method
        val addPrefMethod = methodAddPref.method

        clsSettingsUi.asResolver().firstMethod {
            name = "initView"
            parameterCount = 0
        }.hookAfter { param ->
            val activity = param.thisObject as Activity
            val context = activity as Context

            try {
                val clsIconPref =
                    "com.tencent.mm.ui.base.preference.IconPreference".toClass()
                val prefInstance = clsIconPref.createInstance(context)

                setKeyMethod.invoke(prefInstance, PREFS_KEY)
                setTitleMethod.invoke(prefInstance, PREFS_TITLE)

                val prefScreen = XposedHelpers.callMethod(activity, "getPreferenceScreen")

                addPrefMethod.invoke(prefScreen, prefInstance, 0)

            } catch (e: Throwable) {
                WeLogger.e(TAG, "插入选项失败", e)
            }
        }

        clsSettingsUi.asResolver().firstMethod { name = "onPreferenceTreeClick" }
            .hookBefore { param ->
                if (param.args.size < 2) return@hookBefore
                val preference = param.args[1] ?: return@hookBefore

                val key = getKeyMethod.invoke(preference) as? String

                if (PREFS_KEY == key) {
                    val activity = param.thisObject as Activity

                    openSettingsDialog(activity)

                    param.result = true
                }
            }
    }

//    private fun tryHookNewSettingsMethod1() {
//        val newSettingsCls =
//            "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI"
//                .toClassOrNull() ?: return
//
//        newSettingsCls.asResolver().firstMethod { name = "onCreate" }.hookAfter { param ->
//            if (param.thisObject.javaClass.name
//                != "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
//            ) return@hookAfter
//
//            val activity = param.thisObject as Activity
//            activity.asResolver()
//                .firstMethod {
//                    name = "addTextOptionMenu"
//                    parameters(
//                        Int::class,
//                        String::class,
//                        MenuItem.OnMenuItemClickListener::class
//                    )
//                    superclass()
//                }
//                .invoke(0, BuildConfig.TAG, SettingsMenuItemClickListener(activity))
//        }
//    }

    private const val WEKIT_SETTING_ITEM_NAME_RES_ID = -1337

    private fun createSettingItemClass(
        cacheDir: Path,
    ): Class<*> {
        val baseClass = classBaseSettingItem.clazz
        val settingGroupMainClass =
            "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupMain".toClass()
        val settingGroupAccountInfoClass =
            "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupAccountInfo".toClass()
        settingGroupAccountInfoClass.declaredMethods
            .apply {
                val mGetClass = this.first { m -> m.returnType == Class::class.java }.name // C6
                val mReturns1 = methodSettingGroupAccountInfoReturns1.method.name // K6
                val mOnClick = this.first { m -> m.parameterCount == 3 }.name // Q6
                val mGetStringId = this.last { m -> m.returnType == String::class.java }.name // w6
                val mGetSettingLocation =
                    this.last { m -> m.returnType == classSettingLocation.clazz }.name // x6
                val mGetNameResId =
                    this.last { m ->
                        m.returnType == Int::class.javaPrimitiveType &&
                        m.name != methodSettingGroupAccountInfoReturns1.method.name }.name // z6
                // non-play 8069: C6, K6, Q6, w6, x6, z6
                // play 8068: E6, M6, T6, z6, B6, D6
                WeLogger.d(
                    TAG,
                    "resolved all method names: $mGetClass, $mReturns1, $mOnClick, $mGetStringId, $mGetSettingLocation, $mGetNameResId"
                )

                val handler = InvocationHandler { proxy, method, args ->
                    when (method.name) {
                        mGetClass -> return@InvocationHandler settingGroupMainClass
                        mReturns1 -> return@InvocationHandler 1
                        mOnClick -> {
                            openSettingsDialog(args[0] as Context)
                        }

                        mGetStringId -> return@InvocationHandler "SettingGroup_Main_Other_WeKit"
                        mGetSettingLocation -> return@InvocationHandler classSettingLocation.clazz.createInstance(
                            settingGroupMainClass,
                            "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingAdditionHeaderSearch".toClass()
                        )

                        mGetNameResId -> return@InvocationHandler WEKIT_SETTING_ITEM_NAME_RES_ID
                        else -> return@InvocationHandler ProxyBuilder.callSuper(
                            proxy,
                            method,
                            *args
                        )
                    }
                }

                return ProxyBuilder.forClass(baseClass)
                    .dexCache(cacheDir.toFile())
                    .parentClassLoader(ClassLoaderProvider.classLoader!!)
                    // WeChat has a custom AppCompactActivity, so we mustn't use AppCompatActivity::class here
                    .constructorArgTypes("androidx.appcompat.app.AppCompatActivity".toClass())
                    .handler(handler)
                    .buildProxyClass()
                    .also {
                        // if generating a proxy class with buildProxyClass(), instances do not automatically have a handler set
                        it.asResolver().firstConstructor().hookAfter { param ->
                            ProxyBuilder.setInvocationHandler(param.thisObject, handler)
                        }
                    }
            }

    }

    private lateinit var customSettingItemClass: Class<*>

    private fun tryHookNewSettingsMethod2() {
        val settingGroupMainClass =
            "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupMain".toClassOrNull()
                ?: return

        if (!::customSettingItemClass.isInitialized)
            customSettingItemClass = createSettingItemClass(
                (KnownPaths.moduleData / "generated_proxy_classes").createDirectoriesNoThrow()
            )

        // a simple way to inject string resource
        Context::class.asResolver()
            .firstMethod {
                name = "getString"
                parameters(Int::class)
            }
            .hookBefore { param ->
                val resId = param.args[0] as Int
                if (resId == WEKIT_SETTING_ITEM_NAME_RES_ID)
                    param.result = "${BuildConfig.TAG} 设置"
            }

        // create dependency chain
        "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupPersonalInfo".toClass()
            .asResolver()
            .firstMethod {
                returnType = classSettingLocation.clazz
            }
            .hookBefore { param ->
                param.result = classSettingLocation.clazz.createInstance(
                    settingGroupMainClass.toClass(),
                    customSettingItemClass
                )
            }

        // inject into all SettingItem::class map in order to be discovered
        classSettingItemClassesProvider.asResolver().firstMethod()
            .hookAfter { param ->
                val map = param.result as? Map<*, *>? ?: return@hookAfter
                val originalSet = map.values.first() as LinkedHashSet<*>
                param.result = mapOf(map.keys.first() to originalSet + customSettingItemClass)
            }

        // inject into page
        "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI"
            .toClass().asResolver().firstMethod { name = "superImportUIComponents" }
            .hookAfter { param ->
                if (param.thisObject.javaClass.name
                    != "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
                ) return@hookAfter

                @Suppress("UNCHECKED_CAST")
                val settingItemClasses = param.args[0] as HashSet<Class<*>>
                settingItemClasses.add(customSettingItemClass)
            }
    }

//    private fun tryHookNewSettingsMethod3() {
//        methodSettingGroupPluginOnClick.hookBefore { param ->
//            val context = param.args[0] as Context
//            openSettingsDialog(context)
//            param.result = null
//        }
//    }

    private fun openSettingsDialog(context: Context) {
        MainSettingsDialog(context).show()
    }

//    private class SettingsMenuItemClickListener(val context: Context) :
//        MenuItem.OnMenuItemClickListener {
//        override fun onMenuItemClick(p0: MenuItem): Boolean {
//            openSettingsDialog(context)
//            return true
//        }
//    }
}
