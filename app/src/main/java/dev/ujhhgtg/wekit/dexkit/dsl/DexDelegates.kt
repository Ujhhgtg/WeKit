@file:Suppress("unused")

package dev.ujhhgtg.wekit.dexkit.dsl

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.dexkit.DexMethodDescriptor
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionDiagnostic
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * 解析失败时写入的哨兵描述符，由 [DexMethodDelegate] 与 [DexConstructorDelegate] 共用。
 * 注意与 [DexFieldDelegate] 的字段哨兵区分：两者指向同一个类但形态不同（方法 vs 字段）。
 */
private const val PLACEHOLDER_DESCRIPTOR =
    "Lcom/tencent/mm/ui/LauncherUI;->getInstance()Lcom/tencent/mm/ui/LauncherUI;"

/**
 * 所有 Dex 委托的公共基类，用于统一缓存读写与桌面测试诊断。
 * 每个委托负责自己的序列化/反序列化。
 */
sealed class BaseDexDelegate(val key: String) {
    internal lateinit var owner: BaseFeature

    var diagnostic = DexResolutionDiagnostic(DexResolutionStatus.PENDING)
        private set

    internal fun resetForDexTest() {
        clearResolvedValue()
        diagnostic = DexResolutionDiagnostic(DexResolutionStatus.PENDING)
    }

    protected fun recordSuccess(descriptor: String) {
        diagnostic = DexResolutionDiagnostic(
            status = DexResolutionStatus.SUCCESS,
            descriptor = descriptor,
        )
    }

    protected fun recordExpectedFailure(descriptor: String, reason: String) {
        diagnostic = DexResolutionDiagnostic(
            status = DexResolutionStatus.EXPECTED_FAILURE,
            descriptor = descriptor,
            message = reason,
        )
    }

    protected fun recordUnexpectedPlaceholder(descriptor: String) {
        diagnostic = DexResolutionDiagnostic(
            status = DexResolutionStatus.UNEXPECTED_FAILURE,
            descriptor = descriptor,
            message = "placeholder descriptor was set without an expected-failure classification",
        )
    }

    protected fun recordUnexpectedFailure(error: Throwable) {
        diagnostic = DexResolutionDiagnostic(
            status = DexResolutionStatus.UNEXPECTED_FAILURE,
            message = error.message,
            exceptionType = error::class.java.name,
            stackTrace = error.stackTraceToString(),
        )
    }

    internal fun markBlocked(causeKey: String) {
        if (diagnostic.status == DexResolutionStatus.PENDING) {
            diagnostic = DexResolutionDiagnostic(
                status = DexResolutionStatus.BLOCKED,
                blockedBy = causeKey,
            )
        }
    }

    internal fun markIncomplete() {
        if (diagnostic.status == DexResolutionStatus.PENDING) {
            diagnostic = DexResolutionDiagnostic(DexResolutionStatus.INCOMPLETE)
        }
    }

    protected fun recordDescriptorAfterSet(descriptor: String) {
        if (diagnostic.status == DexResolutionStatus.PENDING) {
            recordSuccess(descriptor)
        }
    }

    protected abstract fun clearResolvedValue()
    abstract fun getDescriptorString(): String?
    abstract val isPlaceholder: Boolean

    /** 从缓存字符串恢复状态 */
    abstract fun loadDescriptor(value: String)

    /** 执行内联查找（如果是内联声明的话） */
    open fun findInline(dexKit: DexKitBridge): Boolean = true
}

// ---------------------------------------------------------------------------
// DexClassDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 类委托 — 自动生成 Key，自动反射获取 Class。
 */
class DexClassDelegate internal constructor(
    key: String,
    private val inlineBlock: ((DexClassDelegate, DexKitBridge) -> Boolean)? = null
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexClassDelegate> {

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

    @Suppress("NOTHING_TO_INLINE")
    inline fun reflekt() = clazz.reflekt()

    fun setDescriptor(className: String) {
        descriptorString = className
        cachedClass = null
        recordDescriptorAfterSet(className)
    }

    @Suppress("unused")
    fun setDescriptor(c: ClassData) {
        setDescriptor(c.name)
    }

    fun setPlaceholderDescriptor(
        expectedFailure: Boolean = false,
        reason: String? = null,
    ) {
        WeLogger.w("DexClassDelegate", "setting placeholder for $key")
        setDescriptor("com.tencent.mm.ui.LauncherUI")
        if (expectedFailure) {
            recordExpectedFailure("com.tencent.mm.ui.LauncherUI", reason ?: "allowed Dex class resolution failure")
        } else {
            recordUnexpectedPlaceholder("com.tencent.mm.ui.LauncherUI")
        }
    }

    override val isPlaceholder
        get() = descriptorString == "com.tencent.mm.ui.LauncherUI"

    override fun getDescriptorString(): String? = descriptorString
    override fun loadDescriptor(value: String) = setDescriptor(value)

    override fun clearResolvedValue() {
        descriptorString = null
        cachedClass = null
    }

    /**
     * 查找 Dex 类。结果直接写入委托自身。
     */
    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        allowFailure: Boolean = false,
        multipleIndex: Int = 0,
        block: FindClass.() -> Unit
    ): Boolean {
        try {
            val results = dexKit.findClass(block)

            if (results.isEmpty()) {
                if (!allowFailure) error("DexKit: No class found for key: $key")
                setPlaceholderDescriptor(
                    expectedFailure = true,
                    reason = "allowFailure=true produced no class result",
                )
                return false
            }
            if (results.size > 1 && !allowMultiple)
                error(
                    "DexKit: Multiple classes found for key: $key, count: ${results.size}, classes: ${
                    results.joinToString(",") { it.name }
                }")

            setDescriptor(results[multipleIndex].name)
            return true
        } catch (e: Throwable) {
            recordUnexpectedFailure(e)
            throw e
        }
    }

    fun getClassData(dexKit: DexKitBridge): ClassData =
        dexKit.findClassData(getDescriptorString()!!)!!

    override fun findInline(dexKit: DexKitBridge): Boolean {
        return inlineBlock?.invoke(this, dexKit) ?: true
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexClassDelegate = this
}

// ---------------------------------------------------------------------------
// DexFieldDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 字段委托 — 自动生成 Key，自动反射获取 Field。
 */
class DexFieldDelegate internal constructor(
    key: String,
    private val inlineBlock: ((DexFieldDelegate, DexKitBridge) -> Boolean)? = null
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexFieldDelegate> {

    private var descriptorString: String? = null
    private var cachedField: Field? = null

    val field: Field
        get() {
            if (descriptorString == PLACEHOLDER_FIELD_DESCRIPTOR)
                error("Field resolution has failed: $key")
            if (cachedField == null && descriptorString != null)
                cachedField = getFieldInstance(descriptorString!!)
            return cachedField ?: error("Field not found for key: $key")
        }

    fun setDescriptor(desc: String) {
        descriptorString = desc
        cachedField = null
        recordDescriptorAfterSet(desc)
    }

    @Suppress("unused")
    fun setDescriptor(f: FieldData) {
        setDescriptor(f.descriptor)
    }

    fun setPlaceholderDescriptor(
        expectedFailure: Boolean = false,
        reason: String? = null,
    ) {
        WeLogger.w("DexFieldDelegate", "setting placeholder for $key")
        setDescriptor(PLACEHOLDER_FIELD_DESCRIPTOR)
        if (expectedFailure) {
            recordExpectedFailure(PLACEHOLDER_FIELD_DESCRIPTOR, reason ?: "allowed Dex field resolution failure")
        } else {
            recordUnexpectedPlaceholder(PLACEHOLDER_FIELD_DESCRIPTOR)
        }
    }

    override val isPlaceholder
        get() = descriptorString == PLACEHOLDER_FIELD_DESCRIPTOR

    override fun getDescriptorString(): String? = descriptorString
    override fun loadDescriptor(value: String) = setDescriptor(value)

    override fun clearResolvedValue() {
        descriptorString = null
        cachedField = null
    }

    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        allowFailure: Boolean = false,
        resultIndex: Int = 0,
        block: FindField.() -> Unit
    ): Boolean {
        try {
            val results = dexKit.findField(block)

            if (results.isEmpty()) {
                if (!allowFailure) error("DexKit: No field found for key: $key")
                setPlaceholderDescriptor(
                    expectedFailure = true,
                    reason = "allowFailure=true produced no field result",
                )
                return false
            }
            if (results.size > 1 && !allowMultiple)
                error(
                    "DexKit: Multiple fields found for key: $key, count: ${results.size}, fields:${
                        results.map { "${it.className}::${it.fieldName}" }
                    }"
                )

            setDescriptor(results[resultIndex].descriptor)
            return true
        } catch (e: Throwable) {
            recordUnexpectedFailure(e)
            throw e
        }
    }

    override fun findInline(dexKit: DexKitBridge): Boolean {
        return inlineBlock?.invoke(this, dexKit) ?: true
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexFieldDelegate = this

    private fun getFieldInstance(descriptor: String): Field {
        val arrow = descriptor.indexOf("->")
        val colon = descriptor.indexOf(':', arrow)
        require(arrow >= 0 && colon >= 0) { descriptor }
        val className = descriptor.substring(1, arrow - 1).replace('/', '.')
        val fieldName = descriptor.substring(arrow + 2, colon)
        var current: Class<*>? = ClassLoaders.HOST.loadClass(className)
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException(descriptor)
    }

    companion object {
        private const val PLACEHOLDER_FIELD_DESCRIPTOR =
            "Lcom/tencent/mm/ui/LauncherUI;->INSTANCE:Lcom/tencent/mm/ui/LauncherUI;"
    }
}

// ---------------------------------------------------------------------------
// DexMethodDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 方法委托 — 自动生成 Key，自动反射获取 Method。
 */
class DexMethodDelegate internal constructor(
    key: String,
    private val inlineBlock: ((DexMethodDelegate, DexKitBridge) -> Boolean)? = null
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexMethodDelegate> {

    private var descriptor: DexMethodDescriptor? = null
    private var cachedMethod: Method? = null

    val method: Method
        get() {
            if (isPlaceholder)
                error("Method resolution has failed: $key")
            if (cachedMethod == null && descriptor != null)
                cachedMethod = descriptor!!.getMethodInstance(ClassLoaders.HOST)
            return cachedMethod ?: error("Method not found for key: $key")
        }

    @Deprecated("You shouldn't call .reflekt() on a Method", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Method")

    fun setDescriptor(desc: DexMethodDescriptor) {
        descriptor = desc
        cachedMethod = null
        recordDescriptorAfterSet(desc.descriptor)
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun setDescriptor(m: MethodData) = setDescriptor(DexMethodDescriptor(m.className, m.methodName, m.methodSign))

    override val isPlaceholder
        get() = descriptor?.descriptor == PLACEHOLDER_DESCRIPTOR

    fun setDescriptor(className: String, methodName: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, methodName, methodSign))

    fun setPlaceholderDescriptor(
        expectedFailure: Boolean = false,
        reason: String? = null,
    ) {
        WeLogger.w("DexMethodDelegate", "setting placeholder for $key")
        setDescriptor(DexMethodDescriptor(PLACEHOLDER_DESCRIPTOR))
        if (expectedFailure) {
            recordExpectedFailure(PLACEHOLDER_DESCRIPTOR, reason ?: "allowed Dex method resolution failure")
        } else {
            recordUnexpectedPlaceholder(PLACEHOLDER_DESCRIPTOR)
        }
    }

    override fun getDescriptorString(): String? = descriptor?.descriptor

    override fun loadDescriptor(value: String) {
        descriptor = DexMethodDescriptor(value)
        cachedMethod = null
        recordDescriptorAfterSet(value)
    }

    override fun clearResolvedValue() {
        descriptor = null
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
        try {
            val results = dexKit.findMethod(block)

            if (results.isEmpty()) {
                if (!allowFailure) error("DexKit: No method found for key: $key")
                setPlaceholderDescriptor(
                    expectedFailure = true,
                    reason = "allowFailure=true produced no method result",
                )
                return false
            }
            if (results.size > 1 && !allowMultiple)
                error(
                    "DexKit: Multiple methods found for key: $key, count: ${results.size}, methods:${
                        results.map {
                            "${it.className}::${it.methodName}"
                        }
                    }"
                )

            val m = results[resultIndex]
            setDescriptor(DexMethodDescriptor(m.className, m.methodName, m.methodSign))
            return true
        } catch (e: Throwable) {
            recordUnexpectedFailure(e)
            throw e
        }
    }

    override fun findInline(dexKit: DexKitBridge): Boolean {
        return inlineBlock?.invoke(this, dexKit) ?: true
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexMethodDelegate = this
}

// ---------------------------------------------------------------------------
// DexConstructorDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 构造函数委托 — 自动生成 Key，自动反射获取 Constructor。
 */
class DexConstructorDelegate internal constructor(
    key: String,
    private val inlineBlock: ((DexConstructorDelegate, DexKitBridge) -> Boolean)? = null
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexConstructorDelegate> {

    private var descriptor: DexMethodDescriptor? = null
    private var cachedConstructor: Constructor<*>? = null

    val constructor: Constructor<*>
        get() {
            if (isPlaceholder)
                error("Constructor resolution has failed: $key")
            if (cachedConstructor == null && descriptor != null)
                cachedConstructor = descriptor!!.getConstructorInstance(ClassLoaders.HOST)
            return cachedConstructor ?: error("Constructor not found for key: $key")
        }

    override val isPlaceholder
        get() = descriptor?.descriptor == PLACEHOLDER_DESCRIPTOR

    @Deprecated("You shouldn't call .reflekt() on a Constructor", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Constructor")

    fun newInstance(vararg initArgs: Any?): Any = constructor.newInstance(*initArgs)

    fun setDescriptor(desc: DexMethodDescriptor) {
        descriptor = desc
        cachedConstructor = null
        recordDescriptorAfterSet(desc.descriptor)
    }

    fun setPlaceholderDescriptor(
        expectedFailure: Boolean = false,
        reason: String? = null,
    ) {
        WeLogger.w("DexConstructorDelegate", "setting placeholder for $key")
        setDescriptor(DexMethodDescriptor(PLACEHOLDER_DESCRIPTOR))
        if (expectedFailure) {
            recordExpectedFailure(PLACEHOLDER_DESCRIPTOR, reason ?: "allowed Dex constructor resolution failure")
        } else {
            recordUnexpectedPlaceholder(PLACEHOLDER_DESCRIPTOR)
        }
    }

    @Suppress("unused")
    fun setDescriptor(className: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, "<init>", methodSign))

    override fun getDescriptorString(): String? = descriptor?.descriptor

    override fun loadDescriptor(value: String) {
        descriptor = DexMethodDescriptor(value)
        cachedConstructor = null
        recordDescriptorAfterSet(value)
    }

    override fun clearResolvedValue() {
        descriptor = null
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
        try {
            val results = dexKit.findMethod {
                block()
                if (matcher == null) matcher { name = "<init>" }
                else matcher!!.name = "<init>"
            }

            if (results.isEmpty()) {
                if (throwOnFailure) error("DexKit: No constructor found for key: $key")
                setPlaceholderDescriptor(
                    expectedFailure = true,
                    reason = "throwOnFailure=false produced no constructor result",
                )
                return false
            }
            if (results.size > 1 && !allowMultiple)
                error("DexKit: Multiple constructors found for key: $key, count: ${results.size}")

            val m = results[resultIndex]
            setDescriptor(DexMethodDescriptor(m.className, "<init>", m.methodSign))
            return true
        } catch (e: Throwable) {
            recordUnexpectedFailure(e)
            throw e
        }
    }

    override fun findInline(dexKit: DexKitBridge): Boolean {
        return inlineBlock?.invoke(this, dexKit) ?: true
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexConstructorDelegate = this
}

// ---------------------------------------------------------------------------
// 委托工厂函数 — 自动注册到父 Feature
// ---------------------------------------------------------------------------

/**
 * 创建 dexConstructor 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexConstructor(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexConstructorDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexClass 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexClass(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexClassDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexField 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexField(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexFieldDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexMethod 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexMethod(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexMethodDelegate(key).also { item.registerDexDelegate(it) }
    }

@Suppress("NOTHING_TO_INLINE")
inline fun DexKitBridge.findClassData(clazz: String): ClassData? =
    getClassData(clazz)

val DexClassDelegate.data: ClassData
    get() {
        DexResolutionContext.ensureResolved(this)
        return DexResolutionContext.dexKit.getClassData(getDescriptorString()!!)!!
    }

val DexMethodDelegate.data: MethodData
    get() {
        DexResolutionContext.ensureResolved(this)
        return DexResolutionContext.dexKit.getMethodData(getDescriptorString()!!)!!
    }

val DexConstructorDelegate.data: MethodData
    get() {
        DexResolutionContext.ensureResolved(this)
        return DexResolutionContext.dexKit.getMethodData(getDescriptorString()!!)!!
    }

val DexFieldDelegate.data: FieldData
    get() {
        DexResolutionContext.ensureResolved(this)
        return DexResolutionContext.dexKit.getFieldData(getDescriptorString()!!)!!
    }

// ---------------------------------------------------------------------------
// 内联查找委托工厂函数
// ---------------------------------------------------------------------------

/**
 * 创建带有内联查找逻辑的 dexConstructor 委托
 */
fun dexConstructor(
    allowMultiple: Boolean = false,
    throwOnFailure: Boolean = true,
    resultIndex: Int = 0,
    block: FindMethod.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexConstructorDelegate(key) { delegate, dexKit ->
            delegate.find(dexKit, allowMultiple, throwOnFailure, resultIndex, block)
        }.also { item.registerDexDelegate(it) }
    }

/**
 * 创建带有内联查找逻辑的 dexClass 委托
 */
fun dexClass(
    allowMultiple: Boolean = false,
    allowFailure: Boolean = false,
    multipleIndex: Int = 0,
    block: FindClass.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexClassDelegate(key) { delegate, dexKit ->
            delegate.find(dexKit, allowMultiple, allowFailure, multipleIndex, block)
        }.also { item.registerDexDelegate(it) }
    }

/**
 * 创建带有内联查找逻辑的 dexField 委托
 */
fun dexField(
    allowMultiple: Boolean = false,
    allowFailure: Boolean = false,
    resultIndex: Int = 0,
    block: FindField.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexFieldDelegate(key) { delegate, dexKit ->
            delegate.find(dexKit, allowMultiple, allowFailure, resultIndex, block)
        }.also { item.registerDexDelegate(it) }
    }

/**
 * 创建带有内联查找逻辑的 dexMethod 委托
 */
fun dexMethod(
    allowMultiple: Boolean = false,
    allowFailure: Boolean = false,
    resultIndex: Int = 0,
    block: FindMethod.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexMethodDelegate(key) { delegate, dexKit ->
            delegate.find(dexKit, allowMultiple, allowFailure, resultIndex, block)
        }.also { item.registerDexDelegate(it) }
    }
