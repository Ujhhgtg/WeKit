package dev.ujhhgtg.wekit.features.api.ui

import android.content.Context
import android.view.View
import androidx.annotation.Keep
import com.android.dx.stock.ProxyBuilder
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.loader.utils.ParcelableFixer
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.buildClass
import dev.ujhhgtg.wekit.utils.reflection.createProxyBuilder
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

@Keep // keep the names of the marker classes to prevent class name clashing with WeChat's own classes
class WeChatSettingsManager(
    private val classBaseSettingItem: Class<*>,
    private val classBaseSettingGroup: Class<*>,
    private val classBaseSettingSwitchItem: Class<*>,
    private val classSettingLocation: Class<*>,
    private val classSettingItemClassesProvider: Class<*>,
    private val classSettingSwitchConvert: Class<*>,
    private val mGetPageGroupItemClass: String,
    private val mGetLevel: String,
    private val mOnClick: String,
    private val mGetKey: String,
    private val mGetSettingLocation: String,
    private val mGetNameResId: String,
    private val mGetGroupNameResId: String,
    private val mGetDescriptionResId: String,
    private val mGetSwitchState: String,
    private val mGetSwitchProperty: String
) {
    private val registeredItems = CopyOnWriteArrayList<ItemRegistration>()
    private var itemIndexCounter = 0
    private var mainClickAreaId = 0
    private val refreshMethodNames by lazy {
        classBaseSettingItem.superclass!!.reflekt().methods {
            parameterCount = 0
            returnType = Void.TYPE
            modifiers(Modifier.FINAL)
        }.map { it.name }
    }

    // 依靠 Marker 接口组合隔离 Proxy 类缓存。16 个 Marker 可提供 65535 个不同组合，
    // 足以让每个分类及其全部功能都拥有独立的动态类。
    interface M0; interface M1; interface M2; interface M3; interface M4; interface M5; interface M6; interface M7; interface M8; interface M9; interface M10
    interface M11; interface M12; interface M13; interface M14; interface M15; interface M16; interface M17; interface M18; interface M19; interface M20
    interface M21; interface M22; interface M23; interface M24; interface M25; interface M26; interface M27; interface M28; interface M29; interface M30
    private val markers = arrayOf(
        M0::class.java, M1::class.java, M2::class.java, M3::class.java,
        M4::class.java, M5::class.java, M6::class.java, M7::class.java,
        M8::class.java, M9::class.java, M10::class.java, M11::class.java,
        M12::class.java, M13::class.java, M14::class.java, M15::class.java,
    )

    class SettingItemSpec {
        var key: String = ""
        var titleResId: Int = 0
        var groupTitleResId: Int? = null
        var descriptionResIdProvider: (() -> Int?)? = null
        var pageClass: Class<*>? = null
        var parentClass: Class<*>? = null
        var childClass: Class<*>? = null
        var level: Int = 1
        /** Returns true when WeKit handled the click; false delegates to the native base class. */
        var onClick: ((Context) -> Boolean)? = null

        // 可点击进入子页的组专用配置
        var isGroup: Boolean = false

        // 开关项专用配置
        var isSwitch: Boolean = false
        var switchState: (() -> Boolean)? = null
        var onSwitchBound: ((Runnable) -> Unit)? = null
        var onSwitchChanged: ((Context, Boolean) -> Boolean)? = null
    }

    private class ItemRegistration(
        val spec: SettingItemSpec,
        val proxyClass: Class<*>,
    )

    fun createItem(init: SettingItemSpec.() -> Unit): Class<*> {
        val spec = SettingItemSpec().apply(init)
        require(spec.titleResId != 0) { "${spec.key} does not have a title resource" }
        requireNotNull(spec.pageClass) { "${spec.key} does not have a page class" }

        require(!(spec.isGroup && spec.isSwitch)) { "${spec.key} cannot be both a group and a switch" }
        val targetBaseClass = when {
            spec.isSwitch -> classBaseSettingSwitchItem
            spec.isGroup -> classBaseSettingGroup
            else -> classBaseSettingItem
        }

        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                mGetPageGroupItemClass -> spec.pageClass
                mGetLevel -> spec.level
                mOnClick -> {
                    if (spec.isSwitch) {
                        ProxyBuilder.callSuper(proxy, method, *args)
                    } else {
                        val context = args[0] as Context
                        if (spec.onClick?.invoke(context) != true) {
                            ProxyBuilder.callSuper(proxy, method, *args)
                        }
                    }
                }

                mGetKey -> spec.key
                mGetSettingLocation -> {
                    classSettingLocation.createInstance(spec.pageClass, spec.parentClass)
                }

                mGetNameResId -> spec.titleResId
                mGetGroupNameResId -> spec.groupTitleResId
                mGetDescriptionResId -> spec.descriptionResIdProvider?.invoke()

                // 处理开关独有方法
                mGetSwitchState if spec.isSwitch -> {
                    spec.switchState?.invoke() ?: false
                }

                mGetSwitchProperty if spec.isSwitch -> {
                    val switchHandlerClass = method.returnType
                    val context = proxy.reflekt()
                        .invokeMethod("getContext", superclass = true) as Context
                    spec.onSwitchBound?.invoke(Runnable { refreshItem(proxy) })
                    createSwitchHandlerProxy(switchHandlerClass, spec, proxy, context)
                }

                else -> ProxyBuilder.callSuper(proxy, method, *args)
            }
        }

        val markerSignature = ++itemIndexCounter
        require(markerSignature < (1 shl markers.size)) {
            "WeChatSettingsManager supports at most ${(1 shl markers.size) - 1} generated setting classes"
        }
        val markerInterfaces = markers.filterIndexed { index, _ ->
            markerSignature and (1 shl index) != 0
        }.toTypedArray()
        val proxyClass = createProxyBuilder(
            ParcelableFixer.hybridClassLoader,
            targetBaseClass,
            arrayOf("androidx.appcompat.app.AppCompatActivity".toClass()),
            handler,
            markerInterfaces
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

    private fun createSwitchHandlerProxy(
        switchHandlerClass: Class<*>,
        spec: SettingItemSpec,
        item: Any,
        context: Context,
    ): Any {
        val switchClassHandler = InvocationHandler { _, _, args ->
            if (spec.onSwitchChanged?.invoke(context, args[0] as Boolean) == false) {
                refreshItem(item)
            }
        }

        return Proxy.newProxyInstance(switchHandlerClass.classLoader, arrayOf(switchHandlerClass), switchClassHandler)
    }

    private fun refreshItem(item: Any) {
        refreshMethodNames.forEach { methodName ->
            item.reflekt().firstMethod {
                name = methodName
                superclass = true
            }.invoke()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun install() {
        classSettingItemClassesProvider.reflekt().firstMethod()
            .hookAfterDirectly {
                val originalMap = result as? Map<Any, Any> ?: return@hookAfterDirectly
                val mutMap = originalMap.toMutableMap()
                val existingCollection = mutMap[SETTINGS_SCAN_TAG] as? Collection<Any>
                    ?: return@hookAfterDirectly
                val updatedSet = LinkedHashSet(existingCollection)
                updatedSet.addAll(registeredItems.map { it.proxyClass })
                mutMap[SETTINGS_SCAN_TAG] = updatedSet
                result = mutMap
            }

        // ProxyBuilder 在不同宿主版本上不一定会覆盖从更上层基类继承的具体方法。
        // 直接挂在微信基类上，确保动态项的真实 summary resource id 始终可见。
        classBaseSettingItem.superclass!!.reflekt().firstMethod { name = mGetDescriptionResId }
            .hookBeforeDirectly {
                val currentItem = thisObject!!
                val registration = registeredItems.firstOrNull {
                    it.proxyClass == currentItem.javaClass
                } ?: return@hookBeforeDirectly
                result = registration.spec.descriptionResIdProvider?.invoke()
            }

        // 微信的 Switch converter 会禁用整行点击。等其完成最终绑定后，仅为 WeKit
        // ClickableFeature 对应的动态 Switch row 显式安装主区域点击；尾部 MMSwitchBtn
        // 是可点击子 View，会继续消费自己的事件并走原生 switch listener。
        classSettingSwitchConvert.reflekt().firstMethod { parameterCount = 6 }
            .hookAfterDirectly {
                val holder = args[0]!!
                val model = args[1]!!
                val currentItem = model.reflekt().firstField {
                    type { classBaseSettingItem.superclass!!.isAssignableFrom(it) }
                    superclass = true
                }.get()!!
                val registration = registeredItems.firstOrNull {
                    it.proxyClass == currentItem.javaClass
                } ?: return@hookAfterDirectly
                val onClick = registration.spec.onClick ?: return@hookAfterDirectly
                val itemView = holder.reflekt()
                    .getField("itemView", superclass = true) as View
                if (mainClickAreaId == 0) {
                    mainClickAreaId = itemView.resources.getIdentifier(
                        MAIN_CLICK_AREA_RESOURCE_NAME,
                        "id",
                        WECHAT_PACKAGE,
                    )
                    require(mainClickAreaId != 0) { "WeChat setting main click area was not found" }
                }
                itemView.findViewById<View>(mainClickAreaId)
                    .setOnClickListener { view -> onClick(view.context) }
            }

    }

    private companion object {
        const val SETTINGS_SCAN_TAG = "Repairer_Setting"
        const val MAIN_CLICK_AREA_RESOURCE_NAME = "m7k"
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
