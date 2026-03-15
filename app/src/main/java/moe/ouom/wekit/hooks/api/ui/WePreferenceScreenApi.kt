//package moe.ouom.wekit.hooks.sdk.ui
//
//import android.app.Activity
//import android.widget.BaseAdapter
//import com.highcapable.kavaref.KavaRef.Companion.asResolver
//import com.highcapable.kavaref.condition.type.Modifiers
//import com.highcapable.kavaref.condition.type.VagueType
//import com.highcapable.kavaref.extension.createInstance
//import com.highcapable.kavaref.extension.toClass
//import moe.ouom.wekit.core.dsl.dexMethod
//import moe.ouom.wekit.core.model.ApiHookItem
//import moe.ouom.wekit.dexkit.DexMethodDescriptor
//import moe.ouom.wekit.dexkit.intf.IDexFind
//import moe.ouom.wekit.hooks.core.annotation.HookItem
//import moe.ouom.wekit.utils.log.WeLogger
//import org.luckypray.dexkit.DexKitBridge
//import java.util.concurrent.CopyOnWriteArrayList
//
//@HookItem(path = "API/配置界面修改服务", desc = "为其他功能提供修改配置界面的能力")
//object WePreferenceScreenApi : ApiHookItem(), IDexFind {
//
//    private val TAG = nameof(WePreferenceScreenApi)
//
//    interface IPrefItemsProvider {
//        fun getMenuItems(activity: Activity, baseAdapter: BaseAdapter): List<PrefItem>
//    }
//
//    data class PrefItem(
//        val key: String,
//        val title: String,
//        val content: String,
//        val onClick: (Activity, BaseAdapter) -> Unit
//    )
//
//    private val providers = CopyOnWriteArrayList<IPrefItemsProvider>()
//
//    fun addProvider(provider: IPrefItemsProvider) {
//        if (!providers.contains(provider)) {
//            providers.add(provider)
//            WeLogger.i(TAG, "provider added, current provider count: ${providers.size}")
//        } else {
//            WeLogger.w(TAG, "provider already exists, ignored")
//        }
//    }
//
//    fun removeProvider(provider: IPrefItemsProvider) {
//        val removed = providers.remove(provider)
//        WeLogger.i(
//            TAG,
//            "provider remove ${if (removed) "succeeded" else "failed"}, current provider count: ${providers.size}"
//        )
//    }
//
//    private val methodPrefSetKey by dexMethod()
//    private val methodPrefSetTitle by dexMethod()
//    private val methodPrefSetContent by dexMethod()
//    private const val PREF_CLASS_NAME = "com.tencent.mm.ui.base.preference.Preference"
//    private const val PREF_ACTIVITY_CLASS_NAME = "com.tencent.mm.ui.base.preference.MMPreference"
//    private lateinit var preferenceClass: Class<*>
//    private lateinit var preferenceActivityClass: Class<*>
//
//    override fun entry(classLoader: ClassLoader) {
//        preferenceClass = PREF_CLASS_NAME.toClass(classLoader)
//        preferenceActivityClass = PREF_ACTIVITY_CLASS_NAME.toClass(classLoader)
//
//        "com.tencent.mm.ui.MMActivity".toClass(classLoader).asResolver()
//            .firstMethod {
//                name = "initView"
//            }
//            .hookBefore { param ->
//                val activity = param.thisObject as Activity
//                WeLogger.i(TAG, "initView called, ${activity.javaClass.name}")
//                if (!preferenceActivityClass.isAssignableFrom(activity.javaClass)) return@hookBefore
//
//                val prefScreen = activity.asResolver()
//                    .firstMethod {
//                        name = "getPreferenceScreen"
//                    }
//                    .invoke()!! as BaseAdapter
//
//                for (provider in providers) {
//                    try {
//                        for (item in provider.getMenuItems(activity, prefScreen)) {
//                            val pref = preferenceClass.createInstance(activity)
//                            methodPrefSetKey.method.invoke(pref, item.key)
//                            methodPrefSetTitle.method.invoke(pref, item.title)
//                            methodPrefSetContent.method.invoke(pref, item.content)
//                            prefScreen.asResolver()
//                                .firstMethod {
//                                    modifiers(Modifiers.FINAL)
//                                    parameters(preferenceClass, Int::class)
//                                    superclass()
//                                }
//                                .invoke(pref, 1)
//                        }
//                    }
//                    catch (ex: Exception) {
//                        WeLogger.i(TAG, "provider ${provider.javaClass.name} threw while providing menu items", ex)
//                    }
//                }
//            }
//
//        preferenceActivityClass.asResolver()
//            .firstMethod {
//                name = "onPreferenceTreeClick"
//                parameters(VagueType, preferenceClass)
//                returnType(Boolean::class.java)
//            }
//            .hookBefore { param ->
//                val activity = param.thisObject as Activity
//                val prefScreen = activity.asResolver()
//                    .firstMethod {
//                        name = "getPreferenceScreen"
//                    }
//                    .invoke()!! as BaseAdapter
//
//                val pref = param.args[1]
//                // FIXME: probably need to modify this based on wechat version
//                val key = pref.asResolver()
//                    .field {
//                        type = String::class
//                        superclass()
//                    }[0].get()!! as String
//
//                for (provider in providers) {
//                    try {
//                        for (item in provider.getMenuItems(activity, prefScreen)) {
//                            if (item.key == key) {
//                                item.onClick(activity, prefScreen)
//                            }
//                        }
//                    }
//                    catch (ex: Exception) {
//                        WeLogger.i(TAG, "provider ${provider.javaClass.name} threw while providing menu items", ex)
//                    }
//                }
//            }
//    }
//
//    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
//        val descriptors = mutableMapOf<String, String>()
//
//        methodPrefSetKey.find(dexKit, descriptors) {
//            matcher {
//                declaredClass = PREF_CLASS_NAME
//                paramTypes(String::class.java)
//                usingEqStrings("Preference does not have a key assigned.")
//            }
//        }
//
//        methodPrefSetTitle.find(dexKit, descriptors) {
//            matcher {
//                declaredClass = PREF_CLASS_NAME
//                paramTypes(CharSequence::class.java)
//                usingNumbers(0)
//            }
//        }
//
//        // dexkit's bug: does not respect usingNumbers() / usingNumbers = listOf
//        val methods = dexKit.findMethod {
//            matcher {
//                declaredClass = PREF_CLASS_NAME
//                paramTypes(CharSequence::class.java)
//            }
//        }.toList()
//        val methodData = methods.first { data ->
//            data.methodName != methodPrefSetTitle.method.name
//        }
//        val desc = DexMethodDescriptor(
//            methodData.className,
//            methodData.methodName,
//            methodData.methodSign
//        )
//        methodPrefSetContent.setDescriptor(desc)
//        descriptors.let { it[methodPrefSetContent.key] = desc.descriptor }
//
//        return descriptors
//    }
//}