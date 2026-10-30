package dev.ujhhgtg.wekit.dex.dsl

import dev.ujhhgtg.dexsql.matcher.ClassMatcher
import dev.ujhhgtg.dexsql.matcher.ClassSpec
import dev.ujhhgtg.dexsql.matcher.FieldMatcher
import dev.ujhhgtg.dexsql.matcher.FieldSpec
import dev.ujhhgtg.dexsql.matcher.MethodMatcher
import dev.ujhhgtg.dexsql.matcher.MethodSpec
import dev.ujhhgtg.dexsql.matcher.NameMatch
import dev.ujhhgtg.dexsql.matcher.NameSpec
import dev.ujhhgtg.dexsql.meta.DexClassInfo
import dev.ujhhgtg.dexsql.meta.DexFieldInfo
import dev.ujhhgtg.dexsql.meta.DexMethodInfo
import dev.ujhhgtg.wekit.dex.resolution.DexResolutionContext

/**
 * DexKit-style imperative finder for `resolveDex()` bodies:
 *
 * - 0 results: expected failure only when [allowFailure], otherwise a loud error.
 * - >1 results: ambiguity error listing the descriptors.
 * - exactly 1: setDescriptor(...) and return true.
 *
 * The [block] is a DexKit matcher block: it runs during resolution, inside the active
 * DexResolutionContext, so it may read other delegates' `.data`.
 */
fun DexClassDelegate.find(allowFailure: Boolean = false, block: ClassMatcher.() -> Unit): Boolean {
    val matcher = ClassMatcher().apply(block)
    return find(matcher.build(), allowFailure)
}

fun DexMethodDelegate.find(
    allowFailure: Boolean = false,
    block: MethodMatcher.() -> Unit,
): Boolean {
    val matcher = MethodMatcher().apply(block)
    return find(matcher.build(), allowFailure)
}

fun DexConstructorDelegate.find(
    allowFailure: Boolean = false,
    block: MethodMatcher.() -> Unit,
): Boolean {
    val matcher = MethodMatcher().apply(block)
    return find(matcher.build(), allowFailure)
}

fun DexFieldDelegate.find(allowFailure: Boolean = false, block: FieldMatcher.() -> Unit): Boolean {
    val matcher = FieldMatcher().apply(block)
    return find(matcher.build(), allowFailure)
}

internal fun DexClassDelegate.find(spec: ClassSpec, allowFailure: Boolean = false): Boolean =
    resolveMatcher(
        key = key,
        kind = "class",
        allowFailure = allowFailure,
        query = { DexResolutionContext.matcher.findClasses(spec) },
        descriptor = DexClassInfo::descriptor,
        set = { info -> this.setDescriptor(info) },
        markExpectedFailure = ::markExpectedFailure,
        recordFailure = ::recordUnexpectedFailure,
    )

internal fun DexMethodDelegate.find(spec: MethodSpec, allowFailure: Boolean = false): Boolean =
    resolveMatcher(
        key = key,
        kind = "method",
        allowFailure = allowFailure,
        query = { DexResolutionContext.matcher.findMethods(spec) },
        descriptor = DexMethodInfo::descriptor,
        set = ::setDescriptor,
        markExpectedFailure = ::markExpectedFailure,
        recordFailure = ::recordUnexpectedFailure,
    )

internal fun DexConstructorDelegate.find(spec: MethodSpec, allowFailure: Boolean = false): Boolean =
    resolveMatcher(
        key = key,
        kind = "constructor",
        allowFailure = allowFailure,
        query = {
            DexResolutionContext.matcher.findMethods(
                spec.copy(name = NameSpec("<init>", NameMatch.Equals)),
            )
        },
        descriptor = DexMethodInfo::descriptor,
        set = ::setDescriptor,
        markExpectedFailure = ::markExpectedFailure,
        recordFailure = ::recordUnexpectedFailure,
    )

internal fun DexFieldDelegate.find(spec: FieldSpec, allowFailure: Boolean = false): Boolean =
    resolveMatcher(
        key = key,
        kind = "field",
        allowFailure = allowFailure,
        query = { DexResolutionContext.matcher.findFields(spec) },
        descriptor = DexFieldInfo::descriptor,
        set = ::setDescriptor,
        markExpectedFailure = ::markExpectedFailure,
        recordFailure = ::recordUnexpectedFailure,
    )

internal fun <T> resolveMatcher(
    key: String,
    kind: String,
    allowFailure: Boolean,
    query: () -> List<T>,
    descriptor: (T) -> String,
    set: (T) -> Unit,
    markExpectedFailure: () -> Unit,
    recordFailure: (Throwable) -> Unit,
): Boolean {
    try {
        val results = query()
        if (results.isEmpty()) {
            if (!allowFailure) error("DexSQL: no $kind found for key: $key")
            markExpectedFailure()
            return false
        }
        if (results.size > 1) {
            error(
                "DexSQL: multiple " +
                    kind +
                    "s found for key: " +
                    key +
                    ", count: " +
                    results.size +
                    ", descriptors: " +
                    results.joinToString(",") { descriptor(it) },
            )
        }
        set(results.single())
        return true
    } catch (error: Throwable) {
        recordFailure(error)
        throw error
    }
}
