@file:Suppress("unused")

package dev.ujhhgtg.wekit.dexkit.dsl

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.dexkit.DexMethodDescriptor
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionState
import dev.ujhhgtg.wekit.dexkit.sql.DexSqlQuery
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import dev.ujhhgtg.wekit.dexkit.meta.DexClassInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexFieldInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo
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
 * 所有 Dex 委托的公共基类，用于统一缓存读写与桌面测试诊断。
 * 委托的解析结果完全由 [state] 表示：成功时的描述符只存在于 [DexResolutionState.Success] 中。
 *
 * [key] 仅需在单个 Feature 内唯一，但必须跨构建稳定：缓存与云报告都以它做字段名。
 * 因此不能包含运行时类名（R8 混淆后每次构建都会变）——`property.name` 是编译进
 * 代码的字符串常量，不受混淆影响。
 */
sealed class BaseDexDelegate(val key: String) {
    lateinit var owner: BaseFeature

    var state: DexResolutionState = DexResolutionState.Pending
        private set

    /** Whether the resolved state is final: a found member or an allowed failure. */
    val isSettled: Boolean
        get() = state is DexResolutionState.Success || state == DexResolutionState.ExpectedFailure

    /**
     * Whether resolution found this member on the host; `false` only for an expected failure.
     * Throws while the delegate has no settled state, so a missing resolution is never mistaken
     * for an absent member.
     */
    val isFound: Boolean
        get() = when (val state = state) {
            is DexResolutionState.Success -> true
            DexResolutionState.ExpectedFailure -> false
            else -> error("Dex delegate $key is not resolved: $state")
        }

    fun getDescriptorString(): String? = (state as? DexResolutionState.Success)?.descriptor

    fun requireDescriptor(): String = when (val state = state) {
        is DexResolutionState.Success -> state.descriptor
        DexResolutionState.ExpectedFailure -> error("Dex delegate $key was not found on this host")
        else -> error("Dex delegate $key is not resolved: $state")
    }

    fun resetForResolution() = updateState(DexResolutionState.Pending)

    /** Restores a cached result; a `null` descriptor is a cached expected failure. */
    fun restore(descriptor: String?) =
        updateState(descriptor?.let(DexResolutionState::Success) ?: DexResolutionState.ExpectedFailure)

    protected fun setResolvedDescriptor(descriptor: String) {
        if (state !is DexResolutionState.UnexpectedFailure) updateState(DexResolutionState.Success(descriptor))
    }

    /** Records that this member does not exist on the host, as the resolver allows. */
    fun markExpectedFailure() {
        if (state is DexResolutionState.UnexpectedFailure) return
        WeLogger.w("BaseDexDelegate", "Dex delegate $key not found (expected failure)")
        updateState(DexResolutionState.ExpectedFailure)
    }

    fun recordUnexpectedFailure(error: Throwable) = updateState(
        DexResolutionState.UnexpectedFailure(
            message = error.message,
            exceptionType = error::class.java.name,
            stackTrace = error.stackTraceToString(),
        )
    )

    fun markBlocked(causeKey: String) {
        if (state == DexResolutionState.Pending) updateState(DexResolutionState.Blocked(causeKey))
    }

    fun markIncomplete() {
        if (state == DexResolutionState.Pending) updateState(DexResolutionState.Incomplete)
    }

    private fun updateState(newState: DexResolutionState) {
        state = newState
        clearCachedMember()
    }

    protected abstract fun clearCachedMember()

    /** SQL plan attached by a `dexX(sql, ...)` delegate, or null for DexKit delegates. */
    open val dexSqlSpec: DexSqlSpec? = null

    /** 执行内联查找（如果是内联声明的话） */
    open fun findInline(dexKit: DexKitBridge): Boolean = true
}

// ---------------------------------------------------------------------------
// DexClassDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 类委托 — 自动生成 Key，自动反射获取 Class。
 */
class DexClassDelegate(
    key: String,
    private val inlineBlock: ((DexClassDelegate, DexKitBridge) -> Boolean)? = null,
    private val sqlSpec: DexSqlSpec? = null,
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexClassDelegate> {

    override val dexSqlSpec: DexSqlSpec?
        get() = sqlSpec

    private var cachedClass: Class<*>? = null

    val clazz: Class<*>
        get() = cachedClass
            ?: (requireDescriptor().toClassOrNull() ?: error("Class not found for key: $key"))
                .also { cachedClass = it }

    @Suppress("NOTHING_TO_INLINE")
    inline fun reflekt() = clazz.reflekt()

    fun setDescriptor(className: String) = setResolvedDescriptor(className)

    @Suppress("unused")
    fun setDescriptor(c: DexClassInfo) = setDescriptor(c.descriptor)

    fun setDescriptor(c: ClassData) {
        setDescriptor(c.name)
    }

    override fun clearCachedMember() {
        cachedClass = null
    }

    /**
     * 查找 Dex 类。结果直接写入委托自身。
     *
     * A matcher that matches several classes is an ambiguity bug: each delegate must
     * describe exactly one host member (or be an allowed expected failure).
     */
    fun find(
        dexKit: DexKitBridge,
        allowFailure: Boolean = false,
        block: FindClass.() -> Unit
    ): Boolean {
        try {
            val results = dexKit.findClass(block)

            if (results.isEmpty()) {
                if (!allowFailure) error("DexKit: No class found for key: $key")
                markExpectedFailure()
                return false
            }
            if (results.size > 1)
                error(
                    "DexKit: Multiple classes found for key: $key, count: ${results.size}, classes: ${
                    results.joinToString(",") { it.name }
                }")

            setDescriptor(results.single().name)
            return true
        } catch (e: Throwable) {
            recordUnexpectedFailure(e)
            throw e
        }
    }

    fun getClassData(dexKit: DexKitBridge): ClassData =
        dexKit.findClassData(requireDescriptor())!!

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
class DexFieldDelegate(
    key: String,
    private val inlineBlock: ((DexFieldDelegate, DexKitBridge) -> Boolean)? = null,
    private val sqlSpec: DexSqlSpec? = null,
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexFieldDelegate> {

    override val dexSqlSpec: DexSqlSpec?
        get() = sqlSpec

    private var cachedField: Field? = null

    val field: Field
        get() = cachedField ?: getFieldInstance(requireDescriptor()).also { cachedField = it }

    fun setDescriptor(desc: String) = setResolvedDescriptor(desc)

    @Suppress("unused")
    fun setDescriptor(f: DexFieldInfo) = setDescriptor(f.descriptor)

    @Suppress("unused")
    fun setDescriptor(f: FieldData) {
        setDescriptor(f.descriptor)
    }

    override fun clearCachedMember() {
        cachedField = null
    }

    /** 查找 Dex 字段。A multi-row match is an ambiguity bug, not a selection policy. */
    fun find(
        dexKit: DexKitBridge,
        allowFailure: Boolean = false,
        block: FindField.() -> Unit
    ): Boolean {
        try {
            val results = dexKit.findField(block)

            if (results.isEmpty()) {
                if (!allowFailure) error("DexKit: No field found for key: $key")
                markExpectedFailure()
                return false
            }
            if (results.size > 1)
                error(
                    "DexKit: Multiple fields found for key: $key, count: ${results.size}, fields:${
                        results.map { "${it.className}::${it.fieldName}" }
                    }"
                )

            setDescriptor(results.single().descriptor)
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
}

// ---------------------------------------------------------------------------
// DexMethodDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 方法委托 — 自动生成 Key，自动反射获取 Method。
 */
class DexMethodDelegate(
    key: String,
    private val inlineBlock: ((DexMethodDelegate, DexKitBridge) -> Boolean)? = null,
    private val sqlSpec: DexSqlSpec? = null,
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexMethodDelegate> {

    override val dexSqlSpec: DexSqlSpec?
        get() = sqlSpec

    private var cachedMethod: Method? = null

    val method: Method
        get() = cachedMethod
            ?: DexMethodDescriptor(requireDescriptor()).getMethodInstance(ClassLoaders.HOST)
                .also { cachedMethod = it }

    @Deprecated("You shouldn't call .reflekt() on a Method", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Method")

    fun setDescriptor(desc: DexMethodDescriptor) = setResolvedDescriptor(desc.descriptor)

    @Suppress("NOTHING_TO_INLINE")
    inline fun setDescriptor(m: DexMethodInfo) = setDescriptor(DexMethodDescriptor(m.className, m.methodName, m.methodSign))

    @Suppress("NOTHING_TO_INLINE")
    inline fun setDescriptor(m: MethodData) = setDescriptor(DexMethodDescriptor(m.className, m.methodName, m.methodSign))

    fun setDescriptor(className: String, methodName: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, methodName, methodSign))

    override fun clearCachedMember() {
        cachedMethod = null
    }

    /**
     * 查找 Dex 方法。结果直接写入委托自身。
     */
    /** 查找 Dex 方法。A multi-row match is an ambiguity bug, not a selection policy. */
    fun find(
        dexKit: DexKitBridge,
        allowFailure: Boolean = false,
        block: FindMethod.() -> Unit
    ): Boolean {
        try {
            val results = dexKit.findMethod(block)

            if (results.isEmpty()) {
                if (!allowFailure) error("DexKit: No method found for key: $key")
                markExpectedFailure()
                return false
            }
            if (results.size > 1)
                error(
                    "DexKit: Multiple methods found for key: $key, count: ${results.size}, methods:${
                        results.map {
                            "${it.className}::${it.methodName}"
                        }
                    }"
                )

            val m = results.single()
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
class DexConstructorDelegate(
    key: String,
    private val inlineBlock: ((DexConstructorDelegate, DexKitBridge) -> Boolean)? = null,
    private val sqlSpec: DexSqlSpec? = null,
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexConstructorDelegate> {

    override val dexSqlSpec: DexSqlSpec?
        get() = sqlSpec

    private var cachedConstructor: Constructor<*>? = null

    val constructor: Constructor<*>
        get() = cachedConstructor
            ?: DexMethodDescriptor(requireDescriptor()).getConstructorInstance(ClassLoaders.HOST)
                .also { cachedConstructor = it }

    @Deprecated("You shouldn't call .reflekt() on a Constructor", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Constructor")

    fun newInstance(vararg initArgs: Any?): Any = constructor.newInstance(*initArgs)

    fun setDescriptor(desc: DexMethodDescriptor) = setResolvedDescriptor(desc.descriptor)

    @Suppress("unused")
    fun setDescriptor(className: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, "<init>", methodSign))

    @Suppress("NOTHING_TO_INLINE")
    inline fun setDescriptor(m: DexMethodInfo) =
        setDescriptor(DexMethodDescriptor(m.className, "<init>", m.methodSign))

    override fun clearCachedMember() {
        cachedConstructor = null
    }

    /**
     * 查找 Dex 构造函数。结果直接写入委托自身。
     */
    /** 查找 Dex 构造函数。A multi-row match is an ambiguity bug, not a selection policy. */
    fun find(
        dexKit: DexKitBridge,
        allowFailure: Boolean = false,
        block: FindMethod.() -> Unit
    ): Boolean {
        try {
            val results = dexKit.findMethod {
                block()
                if (matcher == null) matcher { name = "<init>" }
                else matcher!!.name = "<init>"
            }

            if (results.isEmpty()) {
                if (!allowFailure) error("DexKit: No constructor found for key: $key")
                markExpectedFailure()
                return false
            }
            if (results.size > 1)
                error("DexKit: Multiple constructors found for key: $key, count: ${results.size}")

            val m = results.single()
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
        val key = property.name
        DexConstructorDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexClass 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexClass(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexClassDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexField 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexField(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexFieldDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexMethod 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexMethod(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexMethodDelegate(key).also { item.registerDexDelegate(it) }
    }

/** Create a SQL-backed method delegate from a [dev.ujhhgtg.wekit.dexkit.sql.sqlMethod] query. */
fun dexMethod(
    query: DexSqlQuery,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexMethodDelegate(key, sqlSpec = DexSqlSpec({ query.toSql() }, allowFailure))
            .also { item.registerDexDelegate(it) }
    }

/** Create a SQL-backed class delegate from a [dev.ujhhgtg.wekit.dexkit.sql.sqlClass] query. */
fun dexClass(
    query: DexSqlQuery,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexClassDelegate(key, sqlSpec = DexSqlSpec({ query.toSql() }, allowFailure))
            .also { item.registerDexDelegate(it) }
    }

/** Create a SQL-backed field delegate from a [dev.ujhhgtg.wekit.dexkit.sql.sqlField] query. */
fun dexField(
    query: DexSqlQuery,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexFieldDelegate(key, sqlSpec = DexSqlSpec({ query.toSql() }, allowFailure))
            .also { item.registerDexDelegate(it) }
    }

/** Create a SQL-backed constructor delegate from a [dev.ujhhgtg.wekit.dexkit.sql.sqlConstructor] query. */
fun dexConstructor(
    query: DexSqlQuery,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexConstructorDelegate(key, sqlSpec = DexSqlSpec({ query.toSql() }, allowFailure))
            .also { item.registerDexDelegate(it) }
    }

/**
 * Create a SQL-backed method delegate from a raw SQL string. Prefer the
 * [dev.ujhhgtg.wekit.dexkit.sql.sqlMethod] overload; raw SQL is an escape hatch
 * for queries the matcher DSL cannot express.
 */
fun dexMethod(
    sql: String,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexMethodDelegate(
            key,
            sqlSpec = DexSqlSpec({ sql }, allowFailure),
        ).also { item.registerDexDelegate(it) }
    }

/** Create a SQL-backed class delegate; the first column must contain a class descriptor. */
fun dexClass(
    sql: String,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexClassDelegate(
            key,
            sqlSpec = DexSqlSpec({ sql }, allowFailure),
        ).also { item.registerDexDelegate(it) }
    }

/** Create a SQL-backed field delegate; the first column must contain a field descriptor. */
fun dexField(
    sql: String,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexFieldDelegate(
            key,
            sqlSpec = DexSqlSpec({ sql }, allowFailure),
        ).also { item.registerDexDelegate(it) }
    }

/** Create a SQL-backed constructor delegate; the first column must contain a constructor descriptor. */
fun dexConstructor(
    sql: String,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexConstructorDelegate(
            key,
            sqlSpec = DexSqlSpec({ sql }, allowFailure),
        ).also { item.registerDexDelegate(it) }
    }

@Suppress("NOTHING_TO_INLINE")
inline fun DexKitBridge.findClassData(clazz: String): ClassData? =
    getClassData(clazz)

val DexClassDelegate.data: DexClassInfo
    get() {
        DexResolutionContext.ensureResolved(this)
        return DexResolutionContext.metadata.classInfoByName(requireDescriptor())
            ?: error("DexSQL metadata missing for class " + requireDescriptor())
    }

val DexMethodDelegate.data: DexMethodInfo
    get() {
        DexResolutionContext.ensureResolved(this)
        return DexResolutionContext.metadata.methodInfo(requireDescriptor())
            ?: error("DexSQL metadata missing for method " + requireDescriptor())
    }

val DexConstructorDelegate.data: DexMethodInfo
    get() {
        DexResolutionContext.ensureResolved(this)
        return DexResolutionContext.metadata.methodInfo(requireDescriptor())
            ?: error("DexSQL metadata missing for constructor " + requireDescriptor())
    }

val DexFieldDelegate.data: DexFieldInfo
    get() {
        DexResolutionContext.ensureResolved(this)
        return DexResolutionContext.metadata.fieldInfo(requireDescriptor())
            ?: error("DexSQL metadata missing for field " + requireDescriptor())
    }

// ---------------------------------------------------------------------------
// 内联查找委托工厂函数
// ---------------------------------------------------------------------------

/**
 * 创建带有内联查找逻辑的 dexConstructor 委托
 */
fun dexConstructor(
    allowFailure: Boolean = false,
    block: FindMethod.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexConstructorDelegate(key, inlineBlock = { delegate, dexKit ->
            delegate.find(dexKit, allowFailure, block)
        }).also { item.registerDexDelegate(it) }
    }

/**
 * 创建带有内联查找逻辑的 dexClass 委托
 */
fun dexClass(
    allowFailure: Boolean = false,
    block: FindClass.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexClassDelegate(key, inlineBlock = { delegate, dexKit ->
            delegate.find(dexKit, allowFailure, block)
        }).also { item.registerDexDelegate(it) }
    }

/**
 * 创建带有内联查找逻辑的 dexField 委托
 */
fun dexField(
    allowFailure: Boolean = false,
    block: FindField.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexFieldDelegate(key, inlineBlock = { delegate, dexKit ->
            delegate.find(dexKit, allowFailure, block)
        }).also { item.registerDexDelegate(it) }
    }

/**
 * 创建带有内联查找逻辑的 dexMethod 委托
 */
fun dexMethod(
    allowFailure: Boolean = false,
    block: FindMethod.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexMethodDelegate(key, inlineBlock = { delegate, dexKit ->
            delegate.find(dexKit, allowFailure, block)
        }).also { item.registerDexDelegate(it) }
    }
