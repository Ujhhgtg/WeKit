package dev.ujhhgtg.wekit.agent.jvm

import bsh.Interpreter
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders

/**
 * A lightweight Java/BeanShell eval backend for the `jvm-eval` tool. Reuses the module's bundled
 * BeanShell interpreter (same engine that powers the Java scripting feature), configured to resolve
 * host + module classes via [ClassLoaders.HYBRID].
 *
 * The interpreter is persistent across calls within the process, so the model can define variables
 * in one call and use them in the next. The handle registry is exposed to scripts as `handles` (so
 * `handles.ref(12)` fetches object `#12`) alongside `hostContext`.
 */
object JvmEvalEngine {
    private val interpreter: Interpreter by lazy {
        Interpreter(null, "").apply {
            classManager.setClassLoader(ClassLoaders.HYBRID)
            runCatching {
                set("handles", JvmObjectRegistry)
                set("hostContext", HostInfo.application)
            }
        }
    }

    /** Evaluates [code] and returns its result, or null for statements with no value. */
    fun eval(code: String): Any? = interpreter.eval(code)
}
