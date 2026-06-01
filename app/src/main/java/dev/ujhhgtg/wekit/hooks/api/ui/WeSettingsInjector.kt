package dev.ujhhgtg.wekit.hooks.api.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.android.dx.stock.ProxyBuilder
import com.highcapable.kavaref.extension.createInstance
import com.highcapable.kavaref.extension.toClass
import com.highcapable.kavaref.extension.toClassOrNull
import com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI
import com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI
import com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingUI
import com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingAdditionHeaderSearch
import com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupAccountInfo
import com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupMain
import com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupPersonalInfo
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.base.preference.IconPreference
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.MainSettingsDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirectoriesNoThrow
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.BBool
import dev.ujhhgtg.wekit.utils.reflection.BByte
import dev.ujhhgtg.wekit.utils.reflection.BChar
import dev.ujhhgtg.wekit.utils.reflection.BDouble
import dev.ujhhgtg.wekit.utils.reflection.BFloat
import dev.ujhhgtg.wekit.utils.reflection.BInt
import dev.ujhhgtg.wekit.utils.reflection.BLong
import dev.ujhhgtg.wekit.utils.reflection.BShort
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.byte
import dev.ujhhgtg.wekit.utils.reflection.char
import dev.ujhhgtg.wekit.utils.reflection.double
import dev.ujhhgtg.wekit.utils.reflection.float
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.reflection.isAbstract
import dev.ujhhgtg.wekit.utils.reflection.long
import dev.ujhhgtg.wekit.utils.reflection.resolve
import dev.ujhhgtg.wekit.utils.reflection.short
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import kotlin.io.path.div

@HookItem(path = "API/设置模块入口")
object WeSettingsInjector : ApiHookItem(), IResolvesDex {

    private val methodSetKey by dexMethod()
    private val methodSetTitle by dexMethod()
    private val methodGetKey by dexMethod()
    private val methodAddPref by dexMethod()

    // method 2
    private val classSettingItemClassesProvider by dexClass()
    private val classBaseSettingItem by dexClass()
    private val classSettingLocation by dexClass()
    private val methodSettingGroupAccountInfoReturns1 by dexMethod()

    private val TAG = This.Class.simpleName

    private const val PREFS_KEY = "wekit_settings_entry"
    private const val PREFS_TITLE = "${BuildConfig.TAG} 设置"
    private const val PREFERENCE_CLASS_NAME = "com.tencent.mm.ui.base.preference.Preference"

    @SuppressLint("NonUniqueDexKitData")
    override fun resolveDex(dexKit: DexKitBridge) {
        val prefClass = dexKit.findClass {
            matcher { className = PREFERENCE_CLASS_NAME }
        }.singleOrNull() ?: run {
            WeLogger.e(TAG, "Preference 类未找到")
            return
        }

        methodSetKey.find(dexKit, allowMultiple = true) {
            searchPackages("com.tencent.mm.ui.base.preference")
            matcher {
                declaredClass = PREFERENCE_CLASS_NAME
                returnType = "void"
                paramTypes("java.lang.String")
                usingStrings("Preference")
            }
        }

        val setTitleCandidates = prefClass.findMethod {
            matcher {
                returnType = "void"
                paramTypes("java.lang.CharSequence")
            }
        }
        if (setTitleCandidates.isNotEmpty()) {
            methodSetTitle.setDescriptor(setTitleCandidates.last())
        }

        val getKeyCandidates = prefClass.findMethod {
            matcher {
                paramCount = 0
                returnType = "java.lang.String"
            }
        }

        val targetGetKey = getKeyCandidates.firstOrNull { it.name != "toString" }

        if (targetGetKey != null) {
            methodGetKey.setDescriptor(targetGetKey)
        }

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
            methodAddPref.find(dexKit, allowMultiple = true) {
                searchPackages("com.tencent.mm.ui.base.preference")
                matcher {
                    declaredClass = adapterClass.name
                    paramTypes(PREFERENCE_CLASS_NAME, "int")
                    returnType = "void"
                }
            }
        }

        classSettingItemClassesProvider.find(dexKit, allowFailure = true) {
            matcher {
                usingEqStrings("Repairer_Setting")

                superClass {
                    usingEqStrings("type")
                }
            }
        }

        classBaseSettingItem.find(dexKit, allowFailure = true) {
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
        }

        classSettingLocation.find(dexKit, allowFailure = true) {
            matcher {
                usingEqStrings("SettingLocation(parentGroup=", ", frontItem=")
            }
        }

        methodSettingGroupAccountInfoReturns1.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass = "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupAccountInfo"
                usingNumbers(1)
                returnType = "int"
            }
        }
    }

    override fun onEnable() {
        injectLegacy()

        // injectModernMethod1()
        injectModernMethod2()
        // injectModernMethod3()

        hookLauncherUi()
    }

    private fun injectLegacy() {
        val clsSettingsUi = "${PackageNames.WECHAT}.plugin.setting.ui.setting.SettingsUI"
            .toClassOrNull() ?: run {
            WeLogger.w(TAG, "legacy settings class not found, skipping")
            return
        }

        clsSettingsUi.resolve().firstMethod {
            name = "initView"
            parameterCount = 0
        }.hookAfter {
            val activity = thisObject as Activity
            val context = activity as Context

            try {
                val prefInstance = IconPreference(context)

                methodSetKey.method.invoke(prefInstance, PREFS_KEY)
                methodSetTitle.method.invoke(prefInstance, PREFS_TITLE)

                val prefScreen = XposedHelpers.callMethod(activity, "getPreferenceScreen")

                methodAddPref.method.invoke(prefScreen, prefInstance, 0)

            } catch (e: Throwable) {
                WeLogger.e(TAG, "插入选项失败", e)
            }
        }

        clsSettingsUi.resolve().firstMethod { name = "onPreferenceTreeClick" }
            .hookBefore {
                if (args.size < 2) return@hookBefore
                val preference = args[1] ?: return@hookBefore

                val key = methodGetKey.method.invoke(preference) as? String

                if (PREFS_KEY == key) {
                    val activity = thisObject as Activity

                    openSettingsDialog(activity)

                    result = true
                }
            }
    }

//    private fun injectModernMethod1() {
//        val newSettingsCls =
//            "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI"
//                .toClassOrNull() ?: return
//
//        newSettingsCls.asResolver().firstMethod { name = "onCreate" }.hookAfter {
//            if (thisObject.javaClass.name
//                != "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
//            ) return@hookAfter
//
//            val activity = thisObject as Activity
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

    private val GROUP_SETTING_ITEM_CLASS by lazy { SettingGroupMain::class.java }

    // or SettingGroupPrivacyPermission & SettingGroupNotify
    private val PARENT_SETTING_ITEM_CLASS by lazy { SettingAdditionHeaderSearch::class.java }
    private val CHILD_SETTING_ITEM_CLASS by lazy { SettingGroupPersonalInfo::class.java }

    private val customSettingItemClass by lazy {
        // this is only used for resolving method names, so we'll hard-code SettingGroupAccountInfo
        SettingGroupAccountInfo::class.java.declaredMethods.run {
            val mGetGroupItemClass = first { m -> m.returnType == Class::class.java }.name
            val mReturns1 = methodSettingGroupAccountInfoReturns1.method.name
            val mOnClick = first { m -> m.parameterCount == 3 }.name
            val mGetStringId = last { m -> m.returnType == String::class.java }.name
            val mGetSettingLocation =
                last { m -> m.returnType == classSettingLocation.clazz }.name
            val mGetNameResId =
                last { m ->
                    m.returnType == Int::class.javaPrimitiveType &&
                            m.name != methodSettingGroupAccountInfoReturns1.method.name
                }.name

            // non-play 8.0.69: C6, K6, Q6, w6, x6, z6
            // non-play 8.0.70: k7, r7, w7, g7, h7, j7
            // non-play 8.0.71: p7, w7, B7, l7, m7, o7
            // play 8.0.68: E6, M6, T6, z6, B6, D6
            // play 8.0.69: C6, K6, Q6, w6, x6, z6
            WeLogger.d(
                TAG,
                "resolved all method names: $mGetGroupItemClass, $mReturns1, $mOnClick, $mGetStringId, $mGetSettingLocation, $mGetNameResId"
            )

            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    mGetGroupItemClass -> GROUP_SETTING_ITEM_CLASS
                    mReturns1 -> 1
                    mOnClick -> openSettingsDialog(args[0] as Context)
                    mGetStringId -> "SettingGroup_Main_Other_WeKit"
                    mGetSettingLocation -> classSettingLocation.clazz.createInstance(
                        GROUP_SETTING_ITEM_CLASS,
                        PARENT_SETTING_ITEM_CLASS
                    )

                    mGetNameResId -> WEKIT_SETTING_ITEM_NAME_RES_ID
                    else if method.isAbstract -> {
                        when (method.returnType) {
                            bool, BBool -> false
                            byte, BByte -> 0.toByte()
                            short, BShort -> 0.toShort()
                            int, BInt -> 0
                            long, BLong -> 0L
                            float, BFloat -> 0.0f
                            double, BDouble -> 0.0
                            char, BChar -> '\u0000'
                            else -> ProxyBuilder.callSuper(
                                proxy,
                                method,
                                *args
                            )
                        }
                    }

                    else -> ProxyBuilder.callSuper(
                        proxy,
                        method,
                        *args
                    )
                }
            }

            ProxyBuilder.forClass(classBaseSettingItem.clazz)
                .dexCache((KnownPaths.moduleData / "generated_proxy_classes").createDirectoriesNoThrow().toFile())
                .parentClassLoader(ClassLoaders.HOST)
                // AppCompactActivity is shipped with the host app itself, so we mustn't use AppCompatActivity::class here
                .constructorArgTypes("androidx.appcompat.app.AppCompatActivity".toClass())
                .handler(handler)
                .buildProxyClass()
                .also {
                    // if generating a proxy class with buildProxyClass(), instances do not automatically have a handler set
                    it.asResolver().firstConstructor().hookAfter {
                        ProxyBuilder.setInvocationHandler(thisObject, handler)
                    }
                }
        }
    }

    private var contextGetStringUnhook: XC_MethodHook.Unhook? = null

    private fun injectModernMethod2() {
        "${PackageNames.WECHAT}.plugin.setting.ui.setting_new.settings.SettingGroupMain".toClassOrNull()
            ?: run {
                WeLogger.w(TAG, "modern settings class not found, skipping")
                return
            }

        // create dependency chain
        CHILD_SETTING_ITEM_CLASS.resolve()
            .firstMethod {
                returnType = classSettingLocation.clazz
            }
            .hookBefore {
                result = classSettingLocation.clazz.createInstance(
                    GROUP_SETTING_ITEM_CLASS,
                    customSettingItemClass
                )
            }

        // inject into all SettingItem::class map in order to be discovered
        classSettingItemClassesProvider.asResolver().firstMethod()
            .hookAfter {
                val map = result as? Map<*, *>? ?: return@hookAfter
                val originalSet = map.values.first() as LinkedHashSet<*>
                result = mapOf(map.keys.first() to originalSet + customSettingItemClass)
            }

        // inject into page
        BaseSettingPrefUI::class.resolve()
            .firstMethod { name = "superImportUIComponents" }
            .hookAfter {
                if (thisObject !is MainSettingsUI) return@hookAfter

                // a simple way to inject string resource
                contextGetStringUnhook = Context::class.resolve()
                    .firstMethod {
                        name = "getString"
                        parameters(Int::class)
                    }
                    .hookBeforeDirectly {
                        val resId = args[0] as Int
                        if (resId == WEKIT_SETTING_ITEM_NAME_RES_ID)
                            result = "${BuildConfig.TAG} 设置"
                    }

                @Suppress("UNCHECKED_CAST")
                val settingItemClasses = args[0] as HashSet<Class<*>>
                settingItemClasses.add(customSettingItemClass)
            }

        BaseSettingUI::class.asResolver()
            .firstMethod { name = "onDestroy" }
            .hookAfter {
                if (thisObject !is MainSettingsUI) return@hookAfter

                contextGetStringUnhook!!.unhook()
                contextGetStringUnhook = null
            }
    }

//    private fun injectModernMethod3() {
//        if (methodSettingGroupPluginOnClick.isPlaceholder) {
//            WeLogger.w(TAG, "methodSettingGroupPluginOnClick not found, skipping")
//            return
//        }
//        methodSettingGroupPluginOnClick.hookBefore {
//            val context = args[0] as Context
//            openSettingsDialog(context)
//            result = null
//        }
//    }

    private fun hookLauncherUi() {
        LauncherUI::class.resolve().apply {
            firstMethod { name = "onCreate" }
                .hookBefore {
                    val activity = thisObject as Activity
                    val intent = activity.intent ?: return@hookBefore
                    if (intent.hasExtra(BuildConfig.TAG)) {
                        // wait for resources & theme to init
                        Handler(Looper.getMainLooper()).postDelayed({
                            openSettingsDialog(activity)
                        }, 500)
                    }
                }

            firstMethod { name = "onNewIntent" }
                .hookBefore {
                    val activity = thisObject as Activity
                    val intent = args[0] as? Intent? ?: return@hookBefore
                    if (intent.hasExtra(BuildConfig.TAG)) {
                        openSettingsDialog(activity)
                    }
                }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun openSettingsDialog(context: Context) = MainSettingsDialog(context).show()

//    private class SettingsMenuItemClickListener(val context: Context) :
//        MenuItem.OnMenuItemClickListener {
//        override fun onMenuItemClick(p0: MenuItem): Boolean {
//            openSettingsDialog(context)
//            return true
//        }
//    }
}
