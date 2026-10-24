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

// Temporary spec-object overloads kept internal while resolver bodies migrate to matcher blocks.

internal fun DexClassDelegate.find(
    spec: ClassSpec,
    packages: List<String>,
    excludePackages: List<String> = emptyList(),
    allowFailure: Boolean = false,
): Boolean =
    resolveMatcher(
        key = key,
        kind = "class",
        allowFailure = allowFailure,
        query = { DexResolutionContext.matcher.findClasses(spec, packages, excludePackages) },
        descriptor = DexClassInfo::descriptor,
        set = { info -> this.setDescriptor(info) },
        markExpectedFailure = ::markExpectedFailure,
        recordFailure = ::recordUnexpectedFailure,
    )

internal fun DexMethodDelegate.find(
    spec: MethodSpec,
    packages: List<String>,
    excludePackages: List<String> = emptyList(),
    allowFailure: Boolean = false,
): Boolean =
    resolveMatcher(
        key = key,
        kind = "method",
        allowFailure = allowFailure,
        query = { DexResolutionContext.matcher.findMethods(spec, packages, excludePackages) },
        descriptor = DexMethodInfo::descriptor,
        set = ::setDescriptor,
        markExpectedFailure = ::markExpectedFailure,
        recordFailure = ::recordUnexpectedFailure,
    )

internal fun DexConstructorDelegate.find(
    spec: MethodSpec,
    packages: List<String>,
    excludePackages: List<String> = emptyList(),
    allowFailure: Boolean = false,
): Boolean =
    resolveMatcher(
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

internal fun DexFieldDelegate.find(
    spec: FieldSpec,
    packages: List<String>,
    excludePackages: List<String> = emptyList(),
    allowFailure: Boolean = false,
): Boolean =
    resolveMatcher(
        key = key,
        kind = "field",
        allowFailure = allowFailure,
        query = { DexResolutionContext.matcher.findFields(spec, packages, excludePackages) },
        descriptor = DexFieldInfo::descriptor,
        set = ::setDescriptor,
        markExpectedFailure = ::markExpectedFailure,
        recordFailure = ::recordUnexpectedFailure,
    )
