package dev.ujhhgtg.wekit.features.items.scripting_python.services

import com.tencent.mm.boot.BuildConfig as WeChatBuildConfig
import dev.ujhhgtg.wekit.dexkit.matcher.DexMatcherSession
import dev.ujhhgtg.wekit.dexkit.matcher.MatcherSpecJson
import dev.ujhhgtg.wekit.dexkit.matcher.NameSpec
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo
import dev.ujhhgtg.wekit.dexkit.withDexSql
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginScope
import dev.ujhhgtg.wekit.python.api.PythonDexHost
import dev.ujhhgtg.wekit.python.api.PythonMemberKind
import dev.ujhhgtg.wekit.python.api.PythonResolvedClass
import dev.ujhhgtg.wekit.python.api.PythonResolvedField
import dev.ujhhgtg.wekit.python.api.PythonResolvedMember
import dev.ujhhgtg.wekit.utils.HostInfo
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Executes Python `wekit.dex` spec queries through the standalone matcher session.
 *
 * Each query acquires the shared runtime DexSQL lease ([withDexSql]), creates a [DexMatcherSession]
 * over that bridge/metadata, converts the results to plain descriptor data, and releases the lease.
 * Queries are serialized on one daemon thread; no matcher objects are involved.
 */
class PythonDexHostImpl(private val scope: PythonPluginScope) : PythonDexHost {
    override fun findClasses(
        specJson: String,
    ): List<PythonResolvedClass> = dexQuery { session ->
        session.findClasses(MatcherSpecJson.classSpec(specJson)).map { info ->
            PythonResolvedClass(
                name = info.name,
                descriptor = info.descriptor,
                modifiers = info.modifiers,
                hostVersion = hostVersion,
                hostBuildTag = WeChatBuildConfig.BUILD_TAG,
            )
        }
    }

    override fun findMethods(
        specJson: String,
    ): List<PythonResolvedMember> = dexQuery { session ->
        session.findMethods(MatcherSpecJson.methodSpec(specJson)).map { info ->
            info.toResolved(PythonMemberKind.METHOD)
        }
    }

    override fun findConstructors(
        specJson: String,
    ): List<PythonResolvedMember> = dexQuery { session ->
        val spec = MatcherSpecJson.methodSpec(specJson)
        session
            .findMethods(spec.copy(name = NameSpec("<init>")))
            .filter { it.isConstructor }
            .map { info -> info.toResolved(PythonMemberKind.CONSTRUCTOR) }
    }

    override fun findFields(
        specJson: String,
    ): List<PythonResolvedField> = dexQuery { session ->
        session.findFields(MatcherSpecJson.fieldSpec(specJson)).map { info ->
            PythonResolvedField(
                descriptor = info.descriptor,
                name = info.name,
                declaredClass = info.declaredClassName,
                typeName = info.typeName,
                modifiers = info.modifiers,
                hostVersion = hostVersion,
                hostBuildTag = WeChatBuildConfig.BUILD_TAG,
            )
        }
    }

    private fun DexMethodInfo.toResolved(kind: PythonMemberKind): PythonResolvedMember =
        PythonResolvedMember(
            descriptor = descriptor,
            name = name,
            declaredClass = declaredClassName,
            returnType = returnTypeName,
            paramTypes = paramTypeNames,
            modifiers = modifiers,
            kind = kind,
            hostVersion = hostVersion,
            hostBuildTag = WeChatBuildConfig.BUILD_TAG,
        )

    private fun <T> dexQuery(block: (DexMatcherSession) -> T): T = query {
        withDexSql { runtime ->
            DexMatcherSession(runtime.bridge, runtime.metadata).use(block)
        }
    }

    private fun <T> query(block: () -> T): T {
        check(!scope.isClosed) { "Python plugin scope is closed: " + scope.pluginId }
        return try {
            executor
                .submit<T> {
                    check(!scope.isClosed) { "Python plugin scope is closed: " + scope.pluginId }
                    block()
                }
                .get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private val hostVersion
        get() = HostInfo.versionName + " (" + HostInfo.versionCode + ")"

    private companion object {
        val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "WeKit-Python-Dex").apply { isDaemon = true }
        }
    }
}
