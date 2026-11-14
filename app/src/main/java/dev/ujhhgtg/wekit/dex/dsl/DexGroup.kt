package dev.ujhhgtg.wekit.dex.dsl

import dev.ujhhgtg.dexsql.matcher.ClassMatcher
import dev.ujhhgtg.dexsql.matcher.FieldMatcher
import dev.ujhhgtg.dexsql.matcher.MethodMatcher
import dev.ujhhgtg.dexsql.meta.DexClassInfo
import dev.ujhhgtg.dexsql.meta.DexFieldInfo
import dev.ujhhgtg.dexsql.meta.DexMethodInfo
import dev.ujhhgtg.wekit.dex.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.core.BaseFeature
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

class DexGroup
private constructor(
    private val probe: () -> BaseDexDelegate,
    private val whenFound: Boolean,
) {
    fun isAvailable(): Boolean {
        val target = probe()
        DexResolutionContext.ensureResolved(target)
        return target.isFound == whenFound
    }

    fun dexClass(allowFailure: Boolean = false, block: ClassMatcher.() -> Unit) =
        attach(dev.ujhhgtg.wekit.dex.dsl.dexClass(allowFailure, block))

    fun dexMethod(allowFailure: Boolean = false, block: MethodMatcher.() -> Unit) =
        attach(dev.ujhhgtg.wekit.dex.dsl.dexMethod(allowFailure, block))

    fun dexField(allowFailure: Boolean = false, block: FieldMatcher.() -> Unit) =
        attach(dev.ujhhgtg.wekit.dex.dsl.dexField(allowFailure, block))

    fun dexConstructor(allowFailure: Boolean = false, block: MethodMatcher.() -> Unit) =
        attach(dev.ujhhgtg.wekit.dex.dsl.dexConstructor(allowFailure, block))

    fun dexClassFrom(allowFailure: Boolean = false, block: () -> DexClassInfo?) =
        attach(dev.ujhhgtg.wekit.dex.dsl.dexClassFrom(allowFailure, block))

    fun dexMethodFrom(allowFailure: Boolean = false, block: () -> DexMethodInfo?) =
        attach(dev.ujhhgtg.wekit.dex.dsl.dexMethodFrom(allowFailure, block))

    fun dexFieldFrom(allowFailure: Boolean = false, block: () -> DexFieldInfo?) =
        attach(dev.ujhhgtg.wekit.dex.dsl.dexFieldFrom(allowFailure, block))

    fun dexConstructorFrom(allowFailure: Boolean = false, block: () -> DexMethodInfo?) =
        attach(dev.ujhhgtg.wekit.dex.dsl.dexConstructorFrom(allowFailure, block))

    private fun <T : BaseDexDelegate> attach(
        provider: PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, T>>,
    ): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, T>> =
        PropertyDelegateProvider { owner, property ->
            provider.provideDelegate(owner, property).also {
                it.getValue(owner, property).group = this
            }
        }

    companion object {
        fun whenFound(probe: () -> BaseDexDelegate) = DexGroup(probe, true)

        fun whenAbsent(probe: () -> BaseDexDelegate) = DexGroup(probe, false)
    }
}

fun dexGroup(
    whenFound: (() -> BaseDexDelegate)? = null,
    whenAbsent: (() -> BaseDexDelegate)? = null,
): DexGroup {
    require((whenFound == null) != (whenAbsent == null)) {
        "Specify exactly one of whenFound or whenAbsent"
    }
    return if (whenFound != null) DexGroup.whenFound(whenFound)
    else DexGroup.whenAbsent(whenAbsent!!)
}

fun BaseDexDelegate.dexClass(allowFailure: Boolean = false, block: ClassMatcher.() -> Unit) =
    DexGroup.whenFound { this }.dexClass(allowFailure, block)

fun BaseDexDelegate.dexMethod(allowFailure: Boolean = false, block: MethodMatcher.() -> Unit) =
    DexGroup.whenFound { this }.dexMethod(allowFailure, block)

fun BaseDexDelegate.dexField(allowFailure: Boolean = false, block: FieldMatcher.() -> Unit) =
    DexGroup.whenFound { this }.dexField(allowFailure, block)

fun BaseDexDelegate.dexConstructor(allowFailure: Boolean = false, block: MethodMatcher.() -> Unit) =
    DexGroup.whenFound { this }.dexConstructor(allowFailure, block)

fun BaseDexDelegate.dexClassFrom(allowFailure: Boolean = false, block: () -> DexClassInfo?) =
    DexGroup.whenFound { this }.dexClassFrom(allowFailure, block)

fun BaseDexDelegate.dexMethodFrom(allowFailure: Boolean = false, block: () -> DexMethodInfo?) =
    DexGroup.whenFound { this }.dexMethodFrom(allowFailure, block)

fun BaseDexDelegate.dexFieldFrom(allowFailure: Boolean = false, block: () -> DexFieldInfo?) =
    DexGroup.whenFound { this }.dexFieldFrom(allowFailure, block)

fun BaseDexDelegate.dexConstructorFrom(allowFailure: Boolean = false, block: () -> DexMethodInfo?) =
    DexGroup.whenFound { this }.dexConstructorFrom(allowFailure, block)

fun dexClassFrom(
    allowFailure: Boolean = false,
    block: () -> DexClassInfo?,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { owner, property ->
        DexClassDelegate(property.name).apply {
            computedDescriptor = { block()?.name }
            allowComputedFailure = allowFailure
            owner.registerDexDelegate(this)
        }
    }

fun dexMethodFrom(
    allowFailure: Boolean = false,
    block: () -> DexMethodInfo?,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { owner, property ->
        DexMethodDelegate(property.name).apply {
            computedDescriptor = { block()?.descriptor }
            allowComputedFailure = allowFailure
            owner.registerDexDelegate(this)
        }
    }

fun dexFieldFrom(
    allowFailure: Boolean = false,
    block: () -> DexFieldInfo?,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { owner, property ->
        DexFieldDelegate(property.name).apply {
            computedDescriptor = { block()?.descriptor }
            allowComputedFailure = allowFailure
            owner.registerDexDelegate(this)
        }
    }

fun dexConstructorFrom(
    allowFailure: Boolean = false,
    block: () -> DexMethodInfo?,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { owner, property ->
        DexConstructorDelegate(property.name).apply {
            computedDescriptor = {
                block()
                    ?.also { require(it.isConstructor) { "Expected constructor: $it" } }
                    ?.descriptor
            }
            allowComputedFailure = allowFailure
            owner.registerDexDelegate(this)
        }
    }
