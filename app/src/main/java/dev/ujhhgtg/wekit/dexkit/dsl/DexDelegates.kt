package dev.ujhhgtg.wekit.dexkit.dsl

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.ClassLoaderProvider
import com.highcapable.kavaref.extension.toClassOrNull
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.dexkit.DexMethodDescriptor
import dev.ujhhgtg.wekit.hooks.core.BaseHookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.ClassData
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * 所有 Dex 委托的公共接口，用于统一缓存读写。
 * 每个委托负责自己的序列化/反序列化。
 */
sealed interface DexDelegateBase {
    val key: String
    fun getDescriptorString(): String?

    /** 从缓存字符串恢复状态 */
    fun loadDescriptor(value: String)
}

// ---------------------------------------------------------------------------
// DexClassDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 类委托 — 自动生成 Key，自动反射获取 Class。
 */
class DexClassDelegate internal constructor(
    override val key: String
) : ReadOnlyProperty<Any?, DexClassDelegate>, DexDelegateBase {

    private var descriptorString: String? = null
    private var cachedClass: Class<*>? = null

    val clazz: Class<*>
        get() {
            if (descriptorString == "com.tencent.mm.ui.LauncherUI")
                error("Class resolution has failed: $key")
            if (cachedClass == null && descriptorString != null)
                cachedClass = descriptorString!!.toClassOrNull()
            return cachedClass ?: error("Class not found for key: $key")
        }

    fun asResolver() = clazz.asResolver()

    fun setDescriptor(className: String) {
        descriptorString = className
        cachedClass = null
    }

    fun setPlaceholderDescriptor() {
        WeLogger.w(nameOf(DexClassDelegate::class), "setting placeholder for $key")
        setDescriptor("com.tencent.mm.ui.LauncherUI")
    }

    val isPlaceholder
        get() = descriptorString == "com.tencent.mm.ui.LauncherUI"

    override fun getDescriptorString(): String? = descriptorString
    override fun loadDescriptor(value: String) = setDescriptor(value)

    /**
     * 查找 Dex 类。结果直接写入委托自身。
     */
    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        allowFailure: Boolean = false,
        block: FindClass.() -> Unit
    ): Boolean {
        val results = dexKit.findClass(block).toList()

        if (results.isEmpty()) {
            if (!allowFailure) error("DexKit: No class found for key: $key")
            setPlaceholderDescriptor()
            return false
        }
        if (results.size > 1 && !allowMultiple)
            error("DexKit: Multiple classes found for key: $key, count: ${results.size}")

        setDescriptor(results[0].name)
        return true
    }

    fun getClassData(dexKit: DexKitBridge): ClassData =
        dexKit.findClassData(getDescriptorString()!!)!!

    override fun getValue(thisRef: Any?, property: KProperty<*>): DexClassDelegate = this
}

// ---------------------------------------------------------------------------
// DexMethodDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 方法委托 — 自动生成 Key，自动反射获取 Method。
 */
class DexMethodDelegate internal constructor(
    override val key: String
) : ReadOnlyProperty<BaseHookItem?, DexMethodDelegate>, DexDelegateBase {

    private var descriptor: DexMethodDescriptor? = null
    private var cachedMethod: Method? = null

    val method: Method
        get() {
            if (descriptor != null && descriptor!!.name == "Lcom/tencent/mm/ui/LauncherUI;->()Lcom/tencent/mm/ui/LauncherUI;")
                error("Method resolution has failed: $key")
            if (cachedMethod == null && descriptor != null)
                cachedMethod = descriptor!!.getMethodInstance(ClassLoaderProvider.classLoader!!)
            return cachedMethod ?: error("Method not found for key: $key")
        }

    @Deprecated("You shouldn't call .asResolver() on a Method", level = DeprecationLevel.ERROR)
    fun asResolver(): Nothing = error("You shouldn't call .asResolver() on a Method")

    fun setDescriptor(desc: DexMethodDescriptor) {
        descriptor = desc
        cachedMethod = null
    }

    val isPlaceholder
        get() = descriptor != null &&
                descriptor!!.name == "Lcom/tencent/mm/ui/LauncherUI;->getInstance()Lcom/tencent/mm/ui/LauncherUI;"

    fun setDescriptor(className: String, methodName: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, methodName, methodSign))

    fun setPlaceholderDescriptor() {
        WeLogger.w(nameOf(DexMethodDelegate::class), "setting placeholder for $key")
        setDescriptor(DexMethodDescriptor("Lcom/tencent/mm/ui/LauncherUI;->getInstance()Lcom/tencent/mm/ui/LauncherUI;"))
    }

    override fun getDescriptorString(): String? = descriptor?.descriptor

    override fun loadDescriptor(value: String) {
        descriptor = DexMethodDescriptor(value)
        cachedMethod = null
    }

    /**
     * 查找 Dex 方法。结果直接写入委托自身。
     */
    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        allowFailure: Boolean = false,
        resultIndex: Int = 0,
        block: FindMethod.() -> Unit
    ): Boolean {
        val results = dexKit.findMethod(block).toList()

        if (results.isEmpty()) {
            if (!allowFailure) error("DexKit: No method found for key: $key")
            setPlaceholderDescriptor()
            return false
        }
        if (results.size > 1 && !allowMultiple)
            error("DexKit: Multiple methods found for key: $key, count: ${results.size}")

        val m = results[resultIndex]
        setDescriptor(DexMethodDescriptor(m.className, m.methodName, m.methodSign))
        return true
    }

    override fun getValue(thisRef: BaseHookItem?, property: KProperty<*>): DexMethodDelegate = this
}

// ---------------------------------------------------------------------------
// DexConstructorDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 构造函数委托 — 自动生成 Key，自动反射获取 Constructor。
 */
class DexConstructorDelegate internal constructor(
    override val key: String
) : ReadOnlyProperty<BaseHookItem?, DexConstructorDelegate>, DexDelegateBase {

    private var descriptor: DexMethodDescriptor? = null
    private var cachedConstructor: Constructor<*>? = null

    val constructor: Constructor<*>
        get() {
            if (cachedConstructor == null && descriptor != null)
                cachedConstructor = descriptor!!.getConstructorInstance(ClassLoaderProvider.classLoader!!)
            return cachedConstructor ?: error("Constructor not found for key: $key")
        }

    @Deprecated("You shouldn't call .asResolver() on a Constructor", level = DeprecationLevel.ERROR)
    fun asResolver(): Nothing = error("You shouldn't call .asResolver() on a Constructor")

    fun newInstance(vararg initArgs: Any?): Any = constructor.newInstance(*initArgs)

    fun setDescriptor(desc: DexMethodDescriptor) {
        descriptor = desc
        cachedConstructor = null
    }

    fun setDescriptor(className: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, "<init>", methodSign))

    override fun getDescriptorString(): String? = descriptor?.descriptor

    override fun loadDescriptor(value: String) {
        descriptor = DexMethodDescriptor(value)
        cachedConstructor = null
    }

    /**
     * 查找 Dex 构造函数。结果直接写入委托自身。
     */
    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        throwOnFailure: Boolean = true,
        resultIndex: Int = 0,
        block: FindMethod.() -> Unit
    ): Boolean {
        val results = dexKit.findMethod {
            block()
            if (matcher == null) matcher { name = "<init>" }
            else matcher!!.name = "<init>"
        }.toList()

        if (results.isEmpty()) {
            if (throwOnFailure) error("DexKit: No constructor found for key: $key")
            return false
        }
        if (results.size > 1 && !allowMultiple)
            error("DexKit: Multiple constructors found for key: $key, count: ${results.size}")

        val m = results[resultIndex]
        setDescriptor(DexMethodDescriptor(m.className, "<init>", m.methodSign))
        return true
    }

    override fun getValue(thisRef: BaseHookItem?, property: KProperty<*>): DexConstructorDelegate = this
}

// ---------------------------------------------------------------------------
// 委托工厂函数 — 自动注册到父 HookItem
// ---------------------------------------------------------------------------

/**
 * 创建 dexConstructor 委托，并将其注册到所属 HookItem 的委托列表中。
 * Key 格式："类名:变量名"
 */
fun dexConstructor(): PropertyDelegateProvider<BaseHookItem?, ReadOnlyProperty<BaseHookItem?, DexConstructorDelegate>> =
    PropertyDelegateProvider { thisRef, property ->
        val key = "${thisRef!!::class.java.simpleName}:${property.name}"
        DexConstructorDelegate(key).also { thisRef.registerDexDelegate(it) }
    }

/**
 * 创建 dexClass 委托，并将其注册到所属 HookItem 的委托列表中。
 * Key 格式："类名:变量名"
 */
fun dexClass(): PropertyDelegateProvider<BaseHookItem?, ReadOnlyProperty<BaseHookItem?, DexClassDelegate>> =
    PropertyDelegateProvider { thisRef, property ->
        val key = "${thisRef!!::class.java.simpleName}:${property.name}"
        DexClassDelegate(key).also { thisRef.registerDexDelegate(it) }
    }

/**
 * 创建 dexMethod 委托，并将其注册到所属 HookItem 的委托列表中。
 * Key 格式："类名:变量名"
 */
fun dexMethod(): PropertyDelegateProvider<BaseHookItem?, ReadOnlyProperty<BaseHookItem?, DexMethodDelegate>> =
    PropertyDelegateProvider { thisRef, property ->
        val key = "${thisRef!!::class.java.simpleName}:${property.name}"
        DexMethodDelegate(key).also { thisRef.registerDexDelegate(it) }
    }

// ---------------------------------------------------------------------------
// DexKitBridge 扩展
// ---------------------------------------------------------------------------

fun DexKitBridge.findClassData(clazz: String): ClassData? =
    findClass { matcher { className = clazz } }.singleOrNull()
