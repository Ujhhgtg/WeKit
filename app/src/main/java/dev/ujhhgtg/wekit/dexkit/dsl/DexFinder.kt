package dev.ujhhgtg.wekit.dexkit.dsl

import dev.ujhhgtg.wekit.dexkit.matcher.ClassSpec
import dev.ujhhgtg.wekit.dexkit.matcher.FieldSpec
import dev.ujhhgtg.wekit.dexkit.matcher.MethodSpec
import dev.ujhhgtg.wekit.dexkit.matcher.NameMatch
import dev.ujhhgtg.wekit.dexkit.matcher.NameSpec
import dev.ujhhgtg.wekit.dexkit.meta.DexClassInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexFieldInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext

/**
 * Imperative spec-based resolver extensions for `resolveDex()` bodies (the DexKit-free
 * replacement for the removed `find(dexKit) { matcher { ... } }` overloads).
 *
 * Semantics mirror the deleted DexKit delegate find():
 * - 0 results: expected failure only when [allowFailure], otherwise a loud error.
 * - >1 results: ambiguity error listing the descriptors.
 * - exactly 1: setDescriptor(...) and return true.
 *
 * Requires an active DexResolutionContext (these run inside resolveDex / query-matcher blocks).
 */

fun DexClassDelegate.find(
    spec: ClassSpec,
    packages: List<String> = emptyList(),
    excludePackages: List<String> = emptyList(),
    allowFailure: Boolean = false,
): Boolean = resolveMatcher(
    key = key,
    kind = "class",
    allowFailure = allowFailure,
    query = { DexResolutionContext.matcher.findClasses(spec, packages, excludePackages) },
    descriptor = DexClassInfo::descriptor,
    set = { info -> this.setDescriptor(info) },
    markExpectedFailure = ::markExpectedFailure,
    recordFailure = ::recordUnexpectedFailure,
)

fun DexMethodDelegate.find(
    spec: MethodSpec,
    packages: List<String> = emptyList(),
    excludePackages: List<String> = emptyList(),
    allowFailure: Boolean = false,
): Boolean = resolveMatcher(
    key = key,
    kind = "method",
    allowFailure = allowFailure,
    query = { DexResolutionContext.matcher.findMethods(spec, packages, excludePackages) },
    descriptor = DexMethodInfo::descriptor,
    set = ::setDescriptor,
    markExpectedFailure = ::markExpectedFailure,
    recordFailure = ::recordUnexpectedFailure,
)

fun DexConstructorDelegate.find(
    spec: MethodSpec,
    packages: List<String> = emptyList(),
    excludePackages: List<String> = emptyList(),
    allowFailure: Boolean = false,
): Boolean = resolveMatcher(
    key = key,
    kind = "constructor",
    allowFailure = allowFailure,
    query = {
        DexResolutionContext.matcher.findMethods(
            spec.copy(name = NameSpec("<init>", NameMatch.Equals)),
            packages,
            excludePackages,
        )
    },
    descriptor = DexMethodInfo::descriptor,
    set = ::setDescriptor,
    markExpectedFailure = ::markExpectedFailure,
    recordFailure = ::recordUnexpectedFailure,
)

fun DexFieldDelegate.find(
    spec: FieldSpec,
    packages: List<String> = emptyList(),
    excludePackages: List<String> = emptyList(),
    allowFailure: Boolean = false,
): Boolean = resolveMatcher(
    key = key,
    kind = "field",
    allowFailure = allowFailure,
    query = { DexResolutionContext.matcher.findFields(spec, packages, excludePackages) },
    descriptor = DexFieldInfo::descriptor,
    set = ::setDescriptor,
    markExpectedFailure = ::markExpectedFailure,
    recordFailure = ::recordUnexpectedFailure,
)

private fun <T> resolveMatcher(
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
            if (!allowFailure) error("DexKit-style: no " + kind + " found for key: " + key)
            markExpectedFailure()
            return false
        }
        if (results.size > 1) {
            error(
                "DexKit-style: multiple " + kind + "s found for key: " + key +
                    ", count: " + results.size + ", descriptors: " +
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
