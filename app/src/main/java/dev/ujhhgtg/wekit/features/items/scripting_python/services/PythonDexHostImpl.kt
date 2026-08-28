package dev.ujhhgtg.wekit.features.items.scripting_python.services

import com.tencent.mm.boot.BuildConfig as WeChatBuildConfig
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginScope
import dev.ujhhgtg.wekit.python.api.PythonClassMatcher
import dev.ujhhgtg.wekit.python.api.PythonDexHost
import dev.ujhhgtg.wekit.python.api.PythonFieldMatcher
import dev.ujhhgtg.wekit.python.api.PythonMemberKind
import dev.ujhhgtg.wekit.python.api.PythonMethodMatcher
import dev.ujhhgtg.wekit.python.api.PythonResolvedClass
import dev.ujhhgtg.wekit.python.api.PythonResolvedField
import dev.ujhhgtg.wekit.python.api.PythonResolvedMember
import dev.ujhhgtg.wekit.python.api.PythonStringMatchMode
import dev.ujhhgtg.wekit.python.api.PythonStringMatcher
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.reflection.withDexKit
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher as DexClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher as DexFieldMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher as DexMethodMatcher
import org.luckypray.dexkit.query.matchers.base.StringMatcher
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException

internal class PythonDexHostImpl(private val scope: PythonPluginScope) : PythonDexHost {
    override fun findClasses(matcher: PythonClassMatcher): List<PythonResolvedClass> = query {
        withDexKit { dexKit ->
            dexKit.findClass {
                applyScope(matcher.searchPackages, matcher.excludePackages, matcher.ignorePackagesCase)
                matcher { applyPython(matcher) }
            }.map { data ->
                PythonResolvedClass(data.name, data.descriptor, hostVersion, WeChatBuildConfig.BUILD_TAG)
            }
        }
    }

    override fun findMethods(matcher: PythonMethodMatcher): List<PythonResolvedMember> =
        findMethodData(matcher, PythonMemberKind.METHOD)

    override fun findConstructors(matcher: PythonMethodMatcher): List<PythonResolvedMember> =
        findMethodData(
            matcher.copy(name = PythonStringMatcher("<init>", PythonStringMatchMode.EQUALS)),
            PythonMemberKind.CONSTRUCTOR,
        )

    override fun findFields(matcher: PythonFieldMatcher): List<PythonResolvedField> = query {
        withDexKit { dexKit ->
            dexKit.findField {
                applyScope(matcher.searchPackages, matcher.excludePackages, matcher.ignorePackagesCase)
                matcher { applyPython(matcher) }
            }.map { data -> PythonResolvedField(data.descriptor, hostVersion, WeChatBuildConfig.BUILD_TAG) }
        }
    }

    private fun findMethodData(
        matcher: PythonMethodMatcher,
        kind: PythonMemberKind,
    ): List<PythonResolvedMember> = query {
        withDexKit { dexKit ->
            dexKit.findMethod {
                applyScope(matcher.searchPackages, matcher.excludePackages, matcher.ignorePackagesCase)
                matcher { applyPython(matcher) }
            }.map { data ->
                PythonResolvedMember(data.descriptor, hostVersion, WeChatBuildConfig.BUILD_TAG, kind)
            }
        }
    }

    private fun org.luckypray.dexkit.query.FindClass.applyScope(
        search: List<String>,
        exclude: List<String>,
        ignoreCase: Boolean,
    ) {
        if (search.isNotEmpty()) searchPackages(search)
        if (exclude.isNotEmpty()) excludePackages(exclude)
        if (ignoreCase) ignorePackagesCase(true)
    }

    private fun org.luckypray.dexkit.query.FindMethod.applyScope(
        search: List<String>,
        exclude: List<String>,
        ignoreCase: Boolean,
    ) {
        if (search.isNotEmpty()) searchPackages(search)
        if (exclude.isNotEmpty()) excludePackages(exclude)
        if (ignoreCase) ignorePackagesCase(true)
    }

    private fun org.luckypray.dexkit.query.FindField.applyScope(
        search: List<String>,
        exclude: List<String>,
        ignoreCase: Boolean,
    ) {
        if (search.isNotEmpty()) searchPackages(search)
        if (exclude.isNotEmpty()) excludePackages(exclude)
        if (ignoreCase) ignorePackagesCase(true)
    }

    private fun PythonStringMatcher.toDexMatcher() = StringMatcher(value, mode.toDexMode(), ignoreCase)

    private fun PythonClassMatcher.toDexMatcher() = DexClassMatcher().apply { applyPython(this@toDexMatcher) }

    private fun DexClassMatcher.applyPython(matcher: PythonClassMatcher) {
        matcher.descriptor?.let(::descriptor)
        matcher.name?.let { className(it.toDexMatcher()) }
        matcher.sourceFile?.let { source(it.toDexMatcher()) }
        matcher.modifiers?.let(::modifiers)
        matcher.superClass?.let(::superClass)
        matcher.interfaces.forEach(::addInterface)
        matcher.usingStrings.forEach { addUsingString(it.toDexMatcher()) }
        matcher.fields.forEach { addField(it.toDexMatcher()) }
        matcher.methods.forEach { addMethod(it.toDexMatcher()) }
        if (matcher.allOf.isNotEmpty()) allOf(matcher.allOf.map { it.toDexMatcher() })
        if (matcher.anyOf.isNotEmpty()) anyOf(matcher.anyOf.map { it.toDexMatcher() })
        if (matcher.noneOf.isNotEmpty()) noneOf(matcher.noneOf.map { it.toDexMatcher() })
    }

    private fun PythonMethodMatcher.toDexMatcher() = DexMethodMatcher().apply { applyPython(this@toDexMatcher) }

    private fun DexMethodMatcher.applyPython(matcher: PythonMethodMatcher) {
        matcher.descriptor?.let(::descriptor)
        matcher.name?.let { name(it.toDexMatcher()) }
        matcher.modifiers?.let(::modifiers)
        matcher.declaredClass?.let(::declaredClass)
        matcher.returnType?.let(::returnType)
        matcher.parameterTypes?.let(::paramTypes)
        matcher.parameterCount?.let(::paramCount)
        matcher.protoShorty?.let(::protoShorty)
        if (matcher.opCodes.isNotEmpty()) opCodes(matcher.opCodes)
        if (matcher.opNames.isNotEmpty()) opNames(matcher.opNames)
        matcher.usingStrings.forEach { addUsingString(it.toDexMatcher()) }
        if (matcher.usingNumbers.isNotEmpty()) usingNumbers(matcher.usingNumbers)
        matcher.usingFields.forEach(::addUsingField)
        matcher.invokedMethods.forEach(::addInvoke)
        matcher.callerMethods.forEach(::addCaller)
        if (matcher.allOf.isNotEmpty()) allOf(matcher.allOf.map { it.toDexMatcher() })
        if (matcher.anyOf.isNotEmpty()) anyOf(matcher.anyOf.map { it.toDexMatcher() })
        if (matcher.noneOf.isNotEmpty()) noneOf(matcher.noneOf.map { it.toDexMatcher() })
    }

    private fun PythonFieldMatcher.toDexMatcher() = DexFieldMatcher().apply { applyPython(this@toDexMatcher) }

    private fun DexFieldMatcher.applyPython(matcher: PythonFieldMatcher) {
        matcher.descriptor?.let(::descriptor)
        matcher.name?.let { name(it.toDexMatcher()) }
        matcher.modifiers?.let(::modifiers)
        matcher.declaredClass?.let(::declaredClass)
        matcher.type?.let(::type)
        matcher.readMethods.forEach { addReadMethod(it.toDexMatcher()) }
        matcher.writeMethods.forEach { addWriteMethod(it.toDexMatcher()) }
        if (matcher.allOf.isNotEmpty()) allOf(matcher.allOf.map { it.toDexMatcher() })
        if (matcher.anyOf.isNotEmpty()) anyOf(matcher.anyOf.map { it.toDexMatcher() })
        if (matcher.noneOf.isNotEmpty()) noneOf(matcher.noneOf.map { it.toDexMatcher() })
    }

    private fun PythonStringMatchMode.toDexMode(): StringMatchType = when (this) {
        PythonStringMatchMode.CONTAINS -> StringMatchType.Contains
        PythonStringMatchMode.STARTS_WITH -> StringMatchType.StartsWith
        PythonStringMatchMode.ENDS_WITH -> StringMatchType.EndsWith
        PythonStringMatchMode.REGEX -> StringMatchType.SimilarRegex
        PythonStringMatchMode.EQUALS -> StringMatchType.Equals
    }

    private fun <T> query(block: () -> T): T {
        check(!scope.isClosed) { "Python plugin scope is closed: ${scope.pluginId}" }
        return try {
            executor.submit<T> {
                check(!scope.isClosed) { "Python plugin scope is closed: ${scope.pluginId}" }
                block()
            }.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private val hostVersion get() = "${HostInfo.versionName} (${HostInfo.versionCode})"

    private companion object {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "WeKit-Python-DexKit").apply { isDaemon = true }
        }
    }
}
