package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import android.content.Context
import androidx.annotation.Keep
import com.android.dx.stock.ProxyBuilder
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.loader.utils.ParcelableFixer
import dev.ujhhgtg.wekit.utils.HookHandle
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.buildClass
import dev.ujhhgtg.wekit.utils.reflection.createProxyBuilder
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Keep // keep the names of the marker classes to prevent class name clashing with WeChat's own classes
class WeChatSettingsManager(
    private val classBaseSettingItem: Class<*>,
    private val classBaseSettingSwitchItem: Class<*>,
    private val classSettingLocation: Class<*>,
    private val classSettingItemClassesProvider: Class<*>,
    private val classBaseSettingPrefUI: Class<*>,
    private val classBaseSettingUI: Class<*>,
    private val methodResourceHelperGetStringById: Method,
    private val mGetPageGroupItemClass: String,
    private val mGetLevel: String,
    private val mOnClick: String,
    private val mGetKey: String,
    private val mGetSettingLocation: String,
    private val mGetNameResId: String,
    private val mGetGroupNameResId: String,
    private val mGetSwitchState: String,
    private val mGetSwitchProperty: String
) {
    private val registeredItems = CopyOnWriteArrayList<ItemRegistration>()
    private val stringPool = ConcurrentHashMap<Int, String>()
    private var dynamicResIdCounter = -2000
    private var itemIndexCounter = 0

    private var contextGetStringUnhook: HookHandle? = null
    private var resourcesGetStringUnhook: HookHandle? = null

    // 依靠 Marker 接口隔离 Proxy 类缓存
    interface M0; interface M1; interface M2; interface M3; interface M4; interface M5; interface M6; interface M7; interface M8; interface M9; interface M10
    interface M11; interface M12; interface M13; interface M14; interface M15; interface M16; interface M17; interface M18; interface M19; interface M20
    interface M21; interface M22; interface M23; interface M24; interface M25; interface M26; interface M27; interface M28; interface M29; interface M30
    private val markers = arrayOf(
        M0::class.java, M1::class.java, M2::class.java, M3::class.java, M4::class.java, M5::class.java, M6::class.java, M7::class.java, M8::class.java, M9::class.java, M10::class.java,
        M11::class.java, M12::class.java, M13::class.java, M14::class.java, M15::class.java, M16::class.java, M17::class.java, M18::class.java, M19::class.java, M20::class.java,
        M21::class.java, M22::class.java, M23::class.java, M24::class.java, M25::class.java, M26::class.java, M27::class.java, M28::class.java, M29::class.java, M30::class.java,
    )

    class SettingItemSpec {
        var key: String = ""
        var title: String = ""
        var groupTitle: String? = null
        var pageClass: Class<*>? = null
        var parentClass: Class<*>? = null
        var childClass: Class<*>? = null
        var level: Int = 1
        var onClick: ((Activity) -> Unit)? = null

        // 开关项专用配置
        var isSwitch: Boolean = false
        var switchState: (() -> Boolean)? = null
        var onSwitchChanged: ((Boolean) -> Unit)? = null
    }

    private class ItemRegistration(val spec: SettingItemSpec, val proxyClass: Class<*>)

    private fun allocateString(value: String): Int {
        val id = dynamicResIdCounter--
        stringPool[id] = value
        return id
    }

    fun createItem(init: SettingItemSpec.() -> Unit): Class<*> {
        val spec = SettingItemSpec().apply(init)
        requireNotNull(spec.pageClass) { "${spec.title} does not have a page class" }
        val titleResId = allocateString(spec.title)
        val groupResId = spec.groupTitle?.let { allocateString(it) } ?: titleResId

        val targetBaseClass = if (spec.isSwitch) classBaseSettingSwitchItem else classBaseSettingItem

        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                mGetPageGroupItemClass -> spec.pageClass
                mGetLevel -> spec.level
                mOnClick -> {
                    if (spec.isSwitch) {
                        ProxyBuilder.callSuper(proxy, method, *args)
                    } else {
                        val activity = args[0] as Activity
                        spec.onClick?.invoke(activity) ?: ProxyBuilder.callSuper(proxy, method, *args)
                    }
                }

                mGetKey -> spec.key
                mGetSettingLocation -> {
                    classSettingLocation.createInstance(spec.pageClass, spec.parentClass)
                }

                mGetNameResId -> titleResId
                mGetGroupNameResId -> if (spec.groupTitle != null) groupResId else null

                // 处理开关独有方法
                mGetSwitchState if spec.isSwitch -> {
                    spec.switchState?.invoke() ?: false
                }

                mGetSwitchProperty if spec.isSwitch -> {
                    val switchHandlerClass = method.returnType
                    createSwitchHandlerProxy(switchHandlerClass, spec)
                }

                else -> ProxyBuilder.callSuper(proxy, method, *args)
            }
        }

        val markerInterface = if (itemIndexCounter < markers.size) markers[itemIndexCounter++] else java.io.Serializable::class.java
        val proxyClass = createProxyBuilder(
            ParcelableFixer.hybridClassLoader,
            targetBaseClass,
            arrayOf("androidx.appcompat.app.AppCompatActivity".toClass()),
            handler,
            arrayOf(markerInterface)
        ).buildClass(handler)

        spec.childClass?.let { childClass ->
            val resolvedPage = spec.pageClass ?: proxyClass
            childClass.reflekt()
                .firstMethod { returnType = classSettingLocation }
                .hookBeforeDirectly {
                    result = classSettingLocation.createInstance(resolvedPage, proxyClass)
                }
        }

        registeredItems.add(ItemRegistration(spec, proxyClass))
        return proxyClass
    }

    private fun createSwitchHandlerProxy(switchHandlerClass: Class<*>, spec: SettingItemSpec): Any {
        val switchClassHandler = InvocationHandler { _, _, args ->
            spec.onSwitchChanged?.invoke(args[0] as Boolean)
        }

        return Proxy.newProxyInstance(switchHandlerClass.classLoader, arrayOf(switchHandlerClass), switchClassHandler)
    }

    @Suppress("UNCHECKED_CAST")
    fun install() {
        classSettingItemClassesProvider.reflekt().firstMethod()
            .hookAfterDirectly {
                val originalMap = result as? Map<Any, Any> ?: return@hookAfterDirectly
                val mutMap = originalMap.toMutableMap()

                val groupedByPage = registeredItems.groupBy { it.spec.pageClass ?: it.proxyClass }
                for ((page, items) in groupedByPage) {
                    val classesToAdd = items.map { it.proxyClass }
                    val existingCollection = mutMap[page] as? Collection<Any>

                    if (existingCollection != null) {
                        val updatedSet = LinkedHashSet(existingCollection)
                        updatedSet.addAll(classesToAdd)
                        mutMap[page] = updatedSet
                    } else {
                        mutMap[page] = LinkedHashSet(classesToAdd)
                    }
                }
                result = mutMap
            }

        classBaseSettingPrefUI.reflekt()
            .firstMethod { name = "superImportUIComponents" }
            .hookAfterDirectly {
                val currentUiName = thisObject!!.javaClass.name
                if (!currentUiName.endsWith("MainSettingsUI") && !currentUiName.endsWith("CommonSettingsUI")) return@hookAfterDirectly

                @Suppress("UNCHECKED_CAST")
                val layoutComponentSet = args[0] as? HashSet<Class<*>> ?: return@hookAfterDirectly

                for (item in registeredItems) {
                    layoutComponentSet.add(item.proxyClass)
                }

                contextGetStringUnhook = Context::class.reflekt()
                    .firstMethod { name = "getString"; parameters(Int::class) }
                    .hookBeforeDirectly {
                        stringPool[args[0] as Int]?.let { result = it }
                    }

                resourcesGetStringUnhook = methodResourceHelperGetStringById.hookBeforeDirectly {
                    stringPool[args[1] as Int]?.let { result = it }
                }
            }

        classBaseSettingUI.reflekt()
            .firstMethod { name = "onDestroy" }
            .hookAfterDirectly {
                val currentUiName = thisObject!!.javaClass.name
                if (!currentUiName.endsWith("MainSettingsUI") && !currentUiName.endsWith("CommonSettingsUI")) return@hookAfterDirectly

                contextGetStringUnhook?.unhook(); contextGetStringUnhook = null
                resourcesGetStringUnhook?.unhook(); resourcesGetStringUnhook = null
            }
    }
}
