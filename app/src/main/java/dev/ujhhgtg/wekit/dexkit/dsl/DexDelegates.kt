@file:Suppress("unused")

package dev.ujhhgtg.wekit.dexkit.dsl

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.dexkit.DexMethodDescriptor
import dev.ujhhgtg.wekit.dexkit.meta.DexClassInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexFieldInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionState
import dev.ujhhgtg.wekit.dexkit.sql.DexSqlQuery
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
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

    val isFound: Boolean
        get() = when (val state = state) {
            is DexResolutionState.Success -> true
            DexResolutionState.ExpectedFailure -> false
            else -> error("Dex delegate " + key + " is not resolved: " + state)
        }

    fun getDescriptorString(): String? = (state as? DexResolutionState.Success)?.descriptor

    fun requireDescriptor(): String = when (val state = state) {
        is DexResolutionState.Success -> state.descriptor
        DexResolutionState.ExpectedFailure -> error("Dex delegate " + key + " was not found on this host")
        else -> error("Dex delegate " + key + " is not resolved: " + state)
    }

    fun resetForResolution() = updateState(DexResolutionState.Pending)

    /** Restores a cached result; a `null` descriptor is a cached expected failure. */
    fun restore(descriptor: String?) =
        updateState(descriptor?.let(DexResolutionState::Success) ?: DexResolutionState.ExpectedFailure)

    protected fun setResolvedDescriptor(descriptor: String) {
        if (state !is DexResolutionState.UnexpectedFailure) updateState(DexResolutionState.Success(descriptor))
    }

    fun markExpectedFailure() {
        if (state is DexResolutionState.UnexpectedFailure) return
        WeLogger.w("BaseDexDelegate", "Dex delegate " + key + " not found (expected failure)")
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

    /** SQL plan attached by a `dexX(sql, ...)` / query-matcher delegate, or null if unresolved. */
    open val dexSqlSpec: DexSqlSpec? = null
}

class DexClassDelegate(
    key: String,
    private val sqlSpec: DexSqlSpec? = null,
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexClassDelegate> {
    override val dexSqlSpec: DexSqlSpec? get() = sqlSpec

    private var cachedClass: Class<*>? = null

    val clazz: Class<*>
        get() = cachedClass
            ?: (requireDescriptor().toClassOrNull() ?: error("Class not found for key: " + key))
                .also { cachedClass = it }

    @Suppress("NOTHING_TO_INLINE")
    inline fun reflekt() = clazz.reflekt()

    fun setDescriptor(className: String) = setResolvedDescriptor(className)

    fun setDescriptor(c: DexClassInfo) = setDescriptor(c.name)

    override fun clearCachedMember() {
        cachedClass = null
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexClassDelegate = this
}

class DexFieldDelegate(
    key: String,
    private val sqlSpec: DexSqlSpec? = null,
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexFieldDelegate> {
    override val dexSqlSpec: DexSqlSpec? get() = sqlSpec

    private var cachedField: Field? = null

    val field: Field
        get() = cachedField ?: getFieldInstance(requireDescriptor()).also { cachedField = it }

    fun setDescriptor(desc: String) = setResolvedDescriptor(desc)

    fun setDescriptor(f: DexFieldInfo) = setDescriptor(f.descriptor)

    override fun clearCachedMember() {
        cachedField = null
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

class DexMethodDelegate(
    key: String,
    private val sqlSpec: DexSqlSpec? = null,
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexMethodDelegate> {
    override val dexSqlSpec: DexSqlSpec? get() = sqlSpec

    private var cachedMethod: Method? = null

    val method: Method
        get() = cachedMethod
            ?: DexMethodDescriptor(requireDescriptor()).getMethodInstance(ClassLoaders.HOST)
                .also { cachedMethod = it }

    @Deprecated("You shouldn't call .reflekt() on a Method", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Method")

    fun setDescriptor(desc: DexMethodDescriptor) = setResolvedDescriptor(desc.descriptor)

    /** Raw full method descriptor, e.g. for imperative resolveDex() bodies. */
    fun setDescriptor(desc: String) = setResolvedDescriptor(desc)

    @Suppress("NOTHING_TO_INLINE")
    inline fun setDescriptor(m: DexMethodInfo) =
        setDescriptor(DexMethodDescriptor(m.className, m.methodName, m.methodSign))

    fun setDescriptor(className: String, methodName: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, methodName, methodSign))

    override fun clearCachedMember() {
        cachedMethod = null
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexMethodDelegate = this
}

class DexConstructorDelegate(
    key: String,
    private val sqlSpec: DexSqlSpec? = null,
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexConstructorDelegate> {
    override val dexSqlSpec: DexSqlSpec? get() = sqlSpec

    private var cachedConstructor: Constructor<*>? = null

    val constructor: Constructor<*>
        get() = cachedConstructor
            ?: DexMethodDescriptor(requireDescriptor()).getConstructorInstance(ClassLoaders.HOST)
                .also { cachedConstructor = it }

    @Deprecated("You shouldn't call .reflekt() on a Constructor", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Constructor")

    fun newInstance(vararg initArgs: Any?): Any = constructor.newInstance(*initArgs)

    fun setDescriptor(desc: DexMethodDescriptor) = setResolvedDescriptor(desc.descriptor)

    /** Raw full constructor descriptor, e.g. for imperative resolveDex() bodies. */
    fun setDescriptor(desc: String) = setResolvedDescriptor(desc)

    fun setDescriptor(className: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, "<init>", methodSign))

    @Suppress("NOTHING_TO_INLINE")
    inline fun setDescriptor(m: DexMethodInfo) =
        setDescriptor(DexMethodDescriptor(m.className, "<init>", m.methodSign))

    override fun clearCachedMember() {
        cachedConstructor = null
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexConstructorDelegate = this
}

// ---------------------------------------------------------------------------
// 委托工厂函数 — 自动注册到父 Feature
// ---------------------------------------------------------------------------

fun dexConstructor(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        DexConstructorDelegate(property.name).also { item.registerDexDelegate(it) }
    }

fun dexClass(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        DexClassDelegate(property.name).also { item.registerDexDelegate(it) }
    }

fun dexField(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        DexFieldDelegate(property.name).also { item.registerDexDelegate(it) }
    }

fun dexMethod(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        DexMethodDelegate(property.name).also { item.registerDexDelegate(it) }
    }

fun dexMethod(
    query: DexSqlQuery,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        DexMethodDelegate(property.name, sqlSpec = DexSqlSpec({ query.toSql() }, allowFailure))
            .also { item.registerDexDelegate(it) }
    }

fun dexClass(
    query: DexSqlQuery,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        DexClassDelegate(property.name, sqlSpec = DexSqlSpec({ query.toSql() }, allowFailure))
            .also { item.registerDexDelegate(it) }
    }

fun dexField(
    query: DexSqlQuery,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        DexFieldDelegate(property.name, sqlSpec = DexSqlSpec({ query.toSql() }, allowFailure))
            .also { item.registerDexDelegate(it) }
    }

fun dexConstructor(
    query: DexSqlQuery,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        DexConstructorDelegate(property.name, sqlSpec = DexSqlSpec({ query.toSql() }, allowFailure))
            .also { item.registerDexDelegate(it) }
    }

fun dexMethod(
    sql: String,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        DexMethodDelegate(property.name, sqlSpec = DexSqlSpec({ sql }, allowFailure))
            .also { item.registerDexDelegate(it) }
    }

fun dexClass(
    sql: String,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        DexClassDelegate(property.name, sqlSpec = DexSqlSpec({ sql }, allowFailure))
            .also { item.registerDexDelegate(it) }
    }

fun dexField(
    sql: String,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        DexFieldDelegate(property.name, sqlSpec = DexSqlSpec({ sql }, allowFailure))
            .also { item.registerDexDelegate(it) }
    }

fun dexConstructor(
    sql: String,
    allowFailure: Boolean = false,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        DexConstructorDelegate(property.name, sqlSpec = DexSqlSpec({ sql }, allowFailure))
            .also { item.registerDexDelegate(it) }
    }

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
