package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.agent.jvm.JvmEvalEngine
import dev.ujhhgtg.wekit.agent.jvm.JvmObjectRegistry
import dev.ujhhgtg.wekit.agent.jvm.JvmReflector
import dev.ujhhgtg.wekit.agent.jvm.JvmValueBridge
import dev.ujhhgtg.wekit.features.core.Param
import dev.ujhhgtg.wekit.features.core.WeKitOperation
import dev.ujhhgtg.wekit.features.core.WeKitOperation.Companion.BUILTIN_JVM

/**
 * The `builtin-jvm` tool provider: direct JVM reflection over WeChat's (and Android's) runtime, plus
 * a Java/BeanShell eval escape hatch. Every `@AgentTool` here is discovered by the KSP scanner and
 * grouped under [BUILTIN_JVM].
 *
 * ## Expressing objects, types and values (see [JvmValueBridge])
 *  - **Objects** the model cannot write out as text are held in a process-global handle registry and
 *    referenced by opaque ids like `#12`. Any tool that returns a non-primitive stores it and reports
 *    `#N : <type> = <preview>`.
 *  - **Targets** (for static vs. instance access) are either `ref:#12` (an instance held by a handle)
 *    or a fully-qualified class name like `com.tencent.mm.X` (static context / the Class itself).
 *  - **Values** (arguments, field values) are self-describing `kind:payload` tokens:
 *    `null`, `str:hello`, `int:42`, `long:42`, `dbl:3.1`, `flt:1.5`, `short:1`, `byte:1`, `bool:true`,
 *    `char:A`, `class:java.lang.String`, `ref:#12`, `enum:com.x.E#VALUE`.
 *
 * ## Side-effect classification (§3.2)
 * Read-only introspection (`list-members`, `get-field`, `inspect`, `list-handles`) is
 * `sideEffect = false` (factory-default ENABLED). Anything that runs code or mutates state
 * (`set-field`, `invoke-method`, `new-instance`, `eval`, `clear-handles`) is `sideEffect = true`
 * (factory-default MANUAL_APPROVAL).
 *
 * ## Threading
 * Calls that touch UI (`Activity`/`View`) must run on WeChat's main thread. `invoke-method`,
 * `new-instance` and `eval` take an optional `onMainThread` flag (default false → runs on the
 * current IO thread); when true the call is marshaled to the main looper with a 10s timeout.
 */
object WeJvmToolBindings {

    // ---------------------------------------------------------------------
    // Read-only introspection (sideEffect = false)
    // ---------------------------------------------------------------------

    @WeKitOperation(
        name = "jvm-list-members",
        description = "List fields, methods and/or constructors of a class or object. `target` is a " +
                "fully-qualified class name (e.g. android.app.Activity) or a handle (ref:#12). " +
                "`filter` = all | fields | methods | constructors. `declaredOnly` = only this class " +
                "(not inherited).",
        sideEffect = false,
        group = BUILTIN_JVM,
    )
    fun jvmListMembers(
        @Param("Fully-qualified class name, or a handle like ref:#12") target: String,
        @Param("all | fields | methods | constructors (defaults to all)") filter: String?,
        @Param("If true, only members declared on this class, not inherited (defaults false)") declaredOnly: Boolean?,
    ): String = guard {
        val t = JvmReflector.resolveTarget(target)
        JvmReflector.listMembers(t.clazz, filter ?: "all", declaredOnly ?: false)
    }

    @WeKitOperation(
        name = "jvm-get-field",
        description = "Read a static or instance field. `target` is a class name (static) or a handle " +
                "(ref:#12, instance). Walks superclasses. Returns the value (inline for primitives/" +
                "strings, or a new handle for objects).",
        sideEffect = false,
        group = BUILTIN_JVM,
    )
    fun jvmGetField(
        @Param("Fully-qualified class name, or a handle like ref:#12") target: String,
        @Param("Field name") name: String,
    ): String = guard {
        val t = JvmReflector.resolveTarget(target)
        JvmValueBridge.render(JvmReflector.getField(t, name))
    }

    @WeKitOperation(
        name = "jvm-inspect",
        description = "Describe a live handle (its runtime class and toString) or a class. `target` is " +
                "a handle (ref:#12) or a fully-qualified class name.",
        sideEffect = false,
        group = BUILTIN_JVM,
    )
    fun jvmInspect(
        @Param("A handle like ref:#12, or a fully-qualified class name") target: String,
    ): String = guard {
        val t = JvmReflector.resolveTarget(target)
        if (t.instance != null) {
            "Handle → runtime class ${t.instance.javaClass.name}\nvalue = ${JvmValueBridge.previewOf(t.instance)}"
        } else {
            val c = t.clazz
            buildString {
                append("Class: ${c.name}\n")
                append("Modifiers: ${java.lang.reflect.Modifier.toString(c.modifiers)}\n")
                c.superclass?.let { append("Superclass: ${it.name}\n") }
                if (c.interfaces.isNotEmpty()) append("Interfaces: ${c.interfaces.joinToString { it.name }}\n")
                append("isEnum=${c.isEnum} isInterface=${c.isInterface} isAbstract=${java.lang.reflect.Modifier.isAbstract(c.modifiers)}")
            }
        }
    }

    @WeKitOperation(
        name = "jvm-list-handles",
        description = "List every live object handle currently held (id, type, preview). Handles are " +
                "produced by other jvm tools when they return objects.",
        sideEffect = false,
        group = BUILTIN_JVM,
    )
    fun jvmListHandles(): String = guard { JvmObjectRegistry.describeAll() }

    // ---------------------------------------------------------------------
    // Mutating / code-executing (sideEffect = true)
    // ---------------------------------------------------------------------

    @WeKitOperation(
        name = "jvm-set-field",
        description = "Write a static or instance field. `target` is a class name (static) or handle " +
                "(ref:#12). `value` is a value token (e.g. str:hello, int:42, ref:#3, null).",
        sideEffect = true,
        group = BUILTIN_JVM,
    )
    fun jvmSetField(
        @Param("Fully-qualified class name, or a handle like ref:#12") target: String,
        @Param("Field name") name: String,
        @Param("Value token: null | str: | int: | long: | dbl: | bool: | char: | class: | ref:#N | enum:") value: String,
    ): String = guard {
        val t = JvmReflector.resolveTarget(target)
        JvmReflector.setField(t, name, JvmValueBridge.parseValue(value))
        "OK — set ${t.clazz.simpleName}.$name"
    }

    @WeKitOperation(
        name = "jvm-invoke-method",
        description = "Invoke a static or instance method with overload resolution. `target` is a class " +
                "name (static) or handle (ref:#12, instance). `args` is a list of value tokens. Optional " +
                "`paramTypes` (list of fully-qualified type names, e.g. [\"java.lang.String\",\"int\"]) " +
                "forces an exact overload. Set `onMainThread` true for UI/View calls.",
        sideEffect = true,
        group = BUILTIN_JVM,
    )
    fun jvmInvokeMethod(
        @Param("Fully-qualified class name, or a handle like ref:#12") target: String,
        @Param("Method name") name: String,
        @Param("Argument value tokens, in order (empty if none)") args: List<String>?,
        @Param("Optional exact parameter types (FQCNs) to disambiguate overloads") paramTypes: List<String>?,
        @Param("Run on the WeChat main/UI thread (defaults false)") onMainThread: Boolean?,
    ): String = guard {
        val t = JvmReflector.resolveTarget(target)
        val parsed = JvmValueBridge.parseArgs(args)
        val result = maybeOnMain(onMainThread) { JvmReflector.invokeMethod(t, name, parsed, paramTypes) }
        JvmValueBridge.render(result)
    }

    @WeKitOperation(
        name = "jvm-new-instance",
        description = "Construct a new instance via a constructor with overload resolution. `className` " +
                "is fully-qualified. `args` is a list of value tokens. Optional `paramTypes` forces an " +
                "exact constructor. Returns a handle to the new object.",
        sideEffect = true,
        group = BUILTIN_JVM,
    )
    fun jvmNewInstance(
        @Param("Fully-qualified class name to instantiate") className: String,
        @Param("Constructor argument value tokens, in order (empty if none)") args: List<String>?,
        @Param("Optional exact parameter types (FQCNs) to disambiguate constructors") paramTypes: List<String>?,
        @Param("Run on the WeChat main/UI thread (defaults false)") onMainThread: Boolean?,
    ): String = guard {
        val clazz = JvmValueBridge.resolveClass(className)
        val parsed = JvmValueBridge.parseArgs(args)
        val result = maybeOnMain(onMainThread) { JvmReflector.newInstance(clazz, parsed, paramTypes) }
        JvmValueBridge.render(result)
    }

    @WeKitOperation(
        name = "jvm-eval",
        description = "Evaluate a Java/BeanShell expression or statements and return the result. The " +
                "interpreter is persistent (variables defined in one call survive to the next) and can " +
                "reference the host classloader. `handles.ref(12)` fetches object #12; `hostContext` is " +
                "the app Context. Set `onMainThread` true for UI/View code.",
        sideEffect = true,
        group = BUILTIN_JVM,
    )
    fun jvmEval(
        @Param("Java/BeanShell source. Last expression's value is returned.") code: String,
        @Param("Run on the WeChat main/UI thread (defaults false)") onMainThread: Boolean?,
    ): String = guard {
        val result = maybeOnMain(onMainThread) { JvmEvalEngine.eval(code) }
        JvmValueBridge.render(result)
    }

    @WeKitOperation(
        name = "jvm-clear-handles",
        description = "Release every live object handle to free memory. Existing #ids become invalid.",
        sideEffect = true,
        group = BUILTIN_JVM,
    )
    fun jvmClearHandles(): String = guard {
        val n = JvmObjectRegistry.clear()
        "Cleared $n handle(s)."
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private inline fun <T> maybeOnMain(onMainThread: Boolean?, crossinline block: () -> T): T =
        if (onMainThread == true) JvmValueBridge.onMain { block() } else block()

    /** Renders any thrown exception (including the target's cause) as a model-readable string. */
    private inline fun guard(block: () -> String): String = try {
        block()
    } catch (e: java.lang.reflect.InvocationTargetException) {
        val cause = e.targetException ?: e
        "Error: ${cause.javaClass.name}: ${cause.message}"
    } catch (e: Throwable) {
        "Error: ${e.javaClass.simpleName}: ${e.message}"
    }
}
