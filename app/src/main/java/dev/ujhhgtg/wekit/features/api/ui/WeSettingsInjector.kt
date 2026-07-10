package dev.ujhhgtg.wekit.features.api.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.tencent.mm.plugin.setting.ui.setting.SettingsUI
import com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI
import com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingUI
import com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingAdditionHeaderSearch
import com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupAccountInfo
import com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupMain
import com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupPersonalInfo
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.base.preference.IconPreference
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isBuiltin
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.activity.settings.SettingsActivity
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

@Feature(name = "设置模块入口", categories = ["API"])
object WeSettingsInjector : ApiFeature(), IResolveDex, WeChatInputBarApi.IInputBarListener {

    private val methodSetKey by dexMethod()
    private val methodSetTitle by dexMethod()
    private val methodGetKey by dexMethod()
    private val methodAddPref by dexMethod()

    // method 2
    private val classSettingItemClassesProvider by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("Repairer_Setting")

            superClass {
                usingEqStrings("type")
            }
        }
    }
    private val classBaseSettingItem by dexClass(allowFailure = true) {
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
    private val classBaseSettingSwitchItem by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("activity", "context", "itemView", "1", "0")

            addMethod {
                returnType = "int"
                usingNumbers(2)
            }

            addMethod {
                name = "<init>"
                paramTypes("androidx.appcompat.app.AppCompatActivity")
                usingNumbers(1)
            }

            superClass {
                usingEqStrings("activity")
            }
        }
    }
    private val classSettingLocation by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("SettingLocation(parentGroup=", ", frontItem=")
        }
    }
    private val methodSettingGroupAccountInfoGetStringId by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupAccountInfo"
            usingEqStrings("SettingGroup_Main_AccountInfo")
            returnType = "java.lang.String"
        }
    }
    private val methodSettingGroupAccountInfoReturns1 by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupAccountInfo"
            usingNumbers(1)
            returnType = "int"
        }
    }
    private val methodSettingGroupPersonalInfoGetGroupNameResId by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupPersonalInfo"
            returnType = "java.lang.Integer"
        }
    }
    private val methodResourceHelperGetStringById by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.ResourceHelper", "get string, resId %d, but context is null")
        }
    }
//    private val methodPluginHelperLaunchIntent by dexMethod(allowFailure = true) {
//        matcher {
//            usingEqStrings("MicroMsg.PluginHelper", "start activity, need try load plugin[%B], entry:%s", "start activity error, context is null")
//        }
//    }
//    // FIXME: using multipleIndex here, might find the wrong class
//    private val classIntentAction by dexClass(allowFailure = true, allowMultiple = true, multipleIndex = 1) {
//        searchPackages("com.tencent.mm.plugin.setting.ui.setting_new.uic")
//        matcher {
//            addMethod {
//                name = "<init>"
//                usingEqStrings("activity")
//            }
//
//            addMethod {
//                name = "onCreate"
//            }
//
//            addMethod {
//                name = "onDestroy"
//            }
//
//            addMethod {
//                name = "onResume"
//            }
//
//            superClass {
//                superClass {
//                    className = "com.tencent.mm.ui.component.UIComponent"
//                }
//            }
//        }
//    }

    private const val TAG = "WeSettingsInjector"

    private const val PREFS_KEY = "wekit_settings_entry"
    private const val PREFS_TITLE = "${BuildConfig.TAG} 设置"
    private const val PREFERENCE_CLASS_NAME = "com.tencent.mm.ui.base.preference.Preference"

    @SuppressLint("NonUniqueDexKitData")
    override fun resolveDex(dexKit: DexKitBridge) {
        val prefClass = dexKit.findClass {
            matcher { className = PREFERENCE_CLASS_NAME }
        }.single()

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
    }

    override fun onEnable() {
        runCatching {
            hookLauncherUi()
        }.onFailure { WeLogger.w(TAG, "failed to hook LauncherUI") }

        WeChatInputBarApi.addListener(this)

        runCatching {
            injectLegacy()
        }.onFailure { WeLogger.w(TAG, "failed to hook legacy settings") }

        // injectModernMethod1()
        runCatching {
            injectModernMethod2()
        }.onFailure { WeLogger.w(TAG, "failed to hook modern settings") }
        // injectModernMethod3()
    }

    override fun onDisable() {
        WeChatInputBarApi.removeListener(this)
    }

    override fun onTextChanged(chatFooter: ChatFooter, text: String) {
        if (text != "#wekit") return
        chatFooter.lastText = ""
        openSettingsDialog(chatFooter.context)
    }

    private fun injectLegacy() {
        val clsSettingsUi = "${PackageNames.WECHAT}.plugin.setting.ui.setting.SettingsUI"
            .toClassOrNull() ?: run {
            WeLogger.w(TAG, "legacy settings class not found, skipping")
            return
        }

        clsSettingsUi.reflekt().firstMethod {
            name = "initView"
            parameterCount = 0
        }.hookAfter {
            val context = thisObject as SettingsUI

            try {
                val prefInstance = IconPreference(context)

                methodSetKey.method.invoke(prefInstance, PREFS_KEY)
                methodSetTitle.method.invoke(prefInstance, PREFS_TITLE)

                val prefScreen = context.reflekt().invokeMethod("getPreferenceScreen", superclass = true)

                methodAddPref.method.invoke(prefScreen, prefInstance, 0)

            } catch (e: Throwable) {
                WeLogger.e(TAG, "failed to insert pref item", e)
            }
        }

        clsSettingsUi.reflekt().firstMethod { name = "onPreferenceTreeClick" }
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
//        newSettingsCls.reflekt().firstMethod { name = "onCreate" }.hookAfter {
//            if (thisObject.javaClass.name
//                != "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
//            ) return@hookAfter
//
//            val activity = thisObject as Activity
//            activity.reflekt()
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

    private lateinit var mGetPageGroupItemClass: String
    private lateinit var mGetLevel: String
    private lateinit var mOnClick: String
    private lateinit var mGetKey: String
    private lateinit var mGetSettingLocation: String
    private lateinit var mGetNameResId: String
    private lateinit var mGetGroupNameResId: String
    private lateinit var mGetSwitchState: String
    private lateinit var mGetSwitchProperty: String

    private fun resolveMethodNames() {
        if (::mGetPageGroupItemClass.isInitialized) return

        // this is only used for resolving method names, so we'll hard-code SettingGroupAccountInfo
        SettingGroupAccountInfo::class.java.declaredMethods.run {
            mGetPageGroupItemClass = first { m -> m.returnType == Class::class.java }.name
            mGetLevel = methodSettingGroupAccountInfoReturns1.method.name
            mOnClick = first { m -> m.parameterCount == 3 }.name
            mGetKey = methodSettingGroupAccountInfoGetStringId.method.name
            mGetSettingLocation =
                last { m -> m.returnType == classSettingLocation.clazz }.name
            mGetNameResId =
                last { m ->
                    m.returnType == int &&
                            m.name != methodSettingGroupAccountInfoReturns1.method.name
                }.name
            mGetGroupNameResId = methodSettingGroupPersonalInfoGetGroupNameResId.method.name
            mGetSwitchState = classBaseSettingSwitchItem.reflekt().firstMethod {
                modifiers(Modifier.ABSTRACT)
                returnType = bool
            }.name
            mGetSwitchProperty = classBaseSettingSwitchItem.reflekt().firstMethod {
                modifiers(Modifier.ABSTRACT)
                returnType { !it.isBuiltin }
            }.name

            // non-play 8.0.69: C6, K6, Q6, w6, x6, z6, u6, W6, V6
            // non-play 8.0.70: k7, r7, w7, g7, h7, j7, ...
            // non-play 8.0.71: p7, w7, B7, l7, m7, o7, ...
            // play 8.0.69 (3022): E6, N6, U6, A6, B6, D6, ...
            WeLogger.d(
                TAG,
                "resolved all method names: $mGetPageGroupItemClass, $mGetLevel, $mOnClick, $mGetKey, $mGetSettingLocation, $mGetNameResId, $mGetGroupNameResId, " +
                        "$mGetSwitchState, $mGetSwitchProperty"
            )
        }
    }

    private fun injectModernMethod2() {
        "${PackageNames.WECHAT}.plugin.setting.ui.setting_new.settings.SettingGroupMain".toClassOrNull()
            ?: run {
                WeLogger.w(TAG, "modern settings class not found, skipping")
                return
            }

        resolveMethodNames()

        val settingsManager = WeChatSettingsManager(
            classBaseSettingItem.clazz, classBaseSettingSwitchItem.clazz, classSettingLocation.clazz, classSettingItemClassesProvider.clazz,
            BaseSettingPrefUI::class.java, BaseSettingUI::class.java, methodResourceHelperGetStringById.method,
            mGetPageGroupItemClass, mGetLevel, mOnClick, mGetKey, mGetSettingLocation, mGetNameResId, mGetGroupNameResId, mGetSwitchState, mGetSwitchProperty
        )

        settingsManager.createItem {
            key = "SettingGroup_Main_WeKitTest1"
            title = "WeKit 设置"
            level = 1
            groupTitle = "插件"
            pageClass = SettingGroupMain::class.java
            parentClass = SettingAdditionHeaderSearch::class.java
            childClass = SettingGroupPersonalInfo::class.java
            onClick = { openSettingsDialog(it) }
        }
//
//        val item3 = settingsManager.createItem {
//            key = "SettingGroup_Main_WeKitTest3"
//            title = "测试 2 - WeKit 设置 - 详细日志"
//            level = 1
//            isSwitch = true
//            pageClass = SettingGroupMain::class.java
//            parentClass = item2
//
//            switchState = { Preferences.verboseLog }
//            onSwitchChanged = { Preferences.verboseLog = it }
//        }
//
//        // --- doesn't work ---
//        val group = settingsManager.createItem {
//            key = "SettingGroup_Main_WeKit"
//            title = "测试 3 - WeKit 设置 - 原生"
////            groupTitle = "测试"
//            level = 1
//            pageClass = SettingGroupMain::class.java
//            parentClass = item3
//
//            onClick = {
//                val subPageIntent = Intent {
//                    putExtra("key_config_item", "SettingGroup_Main_WeKit")
//                    putExtra("page_name_kv", "SettingGroup_Main_WeKit")
//                    putExtra("ui_version", 2)
//                    putExtra("setting_from_source", it.intent.getIntExtra("setting_from_source", 2))
//                    putExtra("setting_level", 2)
//                    putExtra("setting_page_time", System.currentTimeMillis().toString())
//                    putStringArrayListExtra("key_intent_action_uic_list", arrayListOf(classIntentAction.clazz.name))
////                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//                }
//
//                methodPluginHelperLaunchIntent.method.invoke(null,
//                    it, "setting", ".ui.setting_new.CommonSettingsUI", subPageIntent, true, null)
//            }
//        }
//
////        val newPagePageClass = SettingGroupMain::class.java
//        val newPagePageClass = group
//
//        val newPageItem1 = settingsManager.createItem {
//            key = "SettingGroup_Main_WeKit_Test1"
//            title = "聊天"
//            groupTitle = "功能"
//            level = 2
//            pageClass = newPagePageClass
//            parentClass = null
//
//            onClick = { CategorySettingsScreen("聊天").show(it) }
//        }
//
//        val newPageItem2 = settingsManager.createItem {
//            key = "SettingGroup_Main_WeKit_Test2"
//            title = "联系人与群组"
//            level = 2
//            pageClass = newPagePageClass
//            parentClass = newPageItem1
//
//            onClick = { CategorySettingsScreen("联系人与群组").show(it) }
//        }
//
//        val newPageItem3 = settingsManager.createItem {
//            key = "SettingGroup_Main_WeKit_Test3"
//            title = "红包与支付"
//            level = 2
//            pageClass = newPagePageClass
//            parentClass = newPageItem2
//
//            onClick = { CategorySettingsScreen("红包与支付").show(it) }
//        }
//        // --- end doesn't work ---
//
//        val item4 = settingsManager.createItem {
//            key = "SettingGroup_Main_WeKitTest4"
//            title = "聊天"
//            groupTitle = "插件 - 功能"
//            level = 1
//            pageClass = SettingGroupMain::class.java
//            parentClass = group
//
//            onClick = { CategorySettingsScreen("聊天").show(it) }
//        }
//
//        val item5 = settingsManager.createItem {
//            key = "SettingGroup_Main_WeKitTest5"
//            title = "聊天 - 阻止消息撤回 3"
//            level = 1
//            isSwitch = true
//            pageClass = SettingGroupMain::class.java
//            parentClass = item4
//
//            switchState = { WePrefs.getBoolOrFalse("阻止消息撤回 3") }
//            onSwitchChanged = { WePrefs.putBool("阻止消息撤回 3", it) }
//        }
//
//        val item6 = settingsManager.createItem {
//            key = "SettingGroup_Main_WeKitTest6"
//            title = "联系人与群组"
//            level = 1
//            pageClass = SettingGroupMain::class.java
//            parentClass = item5
//
//            onClick = { CategorySettingsScreen("联系人与群组").show(it) }
//        }
//
//        val item7 = settingsManager.createItem {
//            key = "SettingGroup_Main_WeKitTest7"
//            title = "红包与支付"
//            level = 1
//            pageClass = SettingGroupMain::class.java
//            parentClass = item6
//            childClass = SettingGroupPersonalInfo::class.java
//
//            onClick = { CategorySettingsScreen("红包与支付").show(it) }
//        }

        settingsManager.install()
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
        LauncherUI::class.reflekt().apply {
            firstMethod { name = "onCreate" }
                .hookBefore {
                    val activity = thisObject as Activity
                    val intent = activity.intent ?: return@hookBefore
                    intent.getStringExtra(BuildConfig.TAG) ?: return@hookBefore
                    // wait for resources & theme to init
                    Handler(Looper.getMainLooper()).postDelayed({
                        openSettingsDialog(activity)
                    }, 500)
                }

            firstMethod { name = "onNewIntent" }
                .hookBefore {
                    val activity = thisObject as Activity
                    val intent = activity.intent ?: return@hookBefore
                    intent.getStringExtra(BuildConfig.TAG) ?: return@hookBefore
                    openSettingsDialog(activity)
                }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    fun openSettingsDialog(context: Context) {
        context.startActivity(Intent(context, SettingsActivity::class.java))
    }

//    private class SettingsMenuItemClickListener(val context: Context) :
//        MenuItem.OnMenuItemClickListener {
//        override fun onMenuItemClick(p0: MenuItem): Boolean {
//            openSettingsDialog(context)
//            return true
//        }
//    }
}
