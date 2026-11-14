package dev.ujhhgtg.wekit.loader.environment

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.currentHookBridge
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Capture only normsg's verified targets, before WeKit asks the bridge to change their ART state.
 */
object MethodAccessFlags {
    private const val TAG = "MethodAccessFlags"
    private val originals = ConcurrentHashMap<Int, NormsgMethodCodec.Flags>()
    private val rawField by lazy {
        Executable::class.reflekt().firstField {
            name = "accessFlags"
            type = Int::class.java
        }
    }
    private val getter by lazy {
        Executable::class.reflekt().firstMethod {
            name = "getAccessFlags"
            parameters()
            returnType = Int::class.java
        }
    }

    fun snapshot(): Map<Int, NormsgMethodCodec.Flags> = originals.toMap()

    fun captureBeforeHook(member: Executable) {
        if (member !is Method) return
        val target = targets[member.declaringClass.name to member.name] ?: return
        if (
            member.returnType.name != target.result ||
                member.parameterTypes.map { it.name } != target.parameters
        )
            return
        if (originals.containsKey(target.tag)) return
        // A direct bridge user may have installed it already; that is not a clean baseline.
        if (currentHookBridge.hookedMethods?.contains(member) == true) {
            WeLogger.w(TAG, "already hooked; no accessFlags baseline for $member")
            return
        }
        val flags =
            NormsgMethodCodec.Flags(
                (getter.invoke(member) as Int).toLong(),
                (rawField.get(member) as Int).toLong(),
            )
        originals.putIfAbsent(target.tag, flags)
        WeLogger.d(TAG, "captured field206.${target.tag} accessFlags: $flags")
    }

    private data class Target(val tag: Int, val result: String, val parameters: List<String>)

    private val targets =
        mapOf(
            "com.tencent.wcdb.database.SQLiteDatabase" to
                "insertWithOnConflict" to
                Target(
                    7,
                    "long",
                    listOf(
                        "java.lang.String",
                        "java.lang.String",
                        "android.content.ContentValues",
                        "int",
                    ),
                ),
            "java.lang.Throwable" to
                "getStackTrace" to
                Target(8, "[Ljava.lang.StackTraceElement;", emptyList()),
            "java.lang.Thread" to
                "getStackTrace" to
                Target(9, "[Ljava.lang.StackTraceElement;", emptyList()),
            "java.lang.Thread" to
                "dispatchUncaughtException" to
                Target(12, "void", listOf("java.lang.Throwable")),
            "android.app.Activity" to "onResume" to Target(14, "void", emptyList()),
            "com.eclipsesource.mmv8.V8" to
                "batchExecuteScripts" to
                Target(
                    15,
                    "java.lang.Object",
                    listOf(
                        "java.util.ArrayList",
                        "java.lang.String",
                        "com.eclipsesource.mmv8.ExecuteDetails",
                    ),
                ),
            "com.tencent.mm.appbrand.commonjni.AppBrandCommonBindingJni" to
                "evaluateScript" to
                Target(16, "void", listOf("java.lang.String", "android.webkit.ValueCallback")),
        )
}
