@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "RemoveRedundantQualifierName")

package dev.ujhhgtg.wekit.agent.jvm

import dev.ujhhgtg.reflekt.utils.makeAccessible
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Low-level reflection mechanics for the `builtin-jvm` tools: target resolution, member listing,
 * field get/set, and overload-resolving method/constructor invocation. Kept separate from
 * [JvmValueBridge] (token grammar) and the tool bindings (model-facing wrappers).
 */
object JvmReflector {
    /** A resolved call target: either a static context (instance == null) or an instance. */
    data class Target(val clazz: Class<*>, val instance: Any?)

    /**
     * Resolves a target token:
     * - `ref:#N` / `#N` → the held instance and its runtime class (instance operations)
     * - anything else → a fully-qualified class name (static operations / the Class itself)
     */
    fun resolveTarget(token: String): Target {
        val t = token.trim()
        if (t.startsWith("ref:") || t.startsWith("#")) {
            val obj =
                JvmValueBridge.parseValue(if (t.startsWith("#")) "ref:$t" else t)
                    ?: throw JvmBridgeException("Target handle resolved to null")
            return Target(obj.javaClass, obj)
        }
        return Target(JvmValueBridge.resolveClass(t), null)
    }

    // -------------------------------------------------------------------------------------------
    // Member listing
    // -------------------------------------------------------------------------------------------

    fun listMembers(clazz: Class<*>, filter: String, declaredOnly: Boolean): String {
        val want = filter.lowercase()
        val sb = StringBuilder()
        sb.append("Class: ${clazz.name}\n")
        clazz.superclass?.let { sb.append("Superclass: ${it.name}\n") }
        if (clazz.interfaces.isNotEmpty()) {
            sb.append("Interfaces: ${clazz.interfaces.joinToString { it.name }}\n")
        }

        if (want == "all" || want == "fields") {
            val fields = if (declaredOnly) clazz.declaredFields.toList() else allFields(clazz)
            sb.append("\nFields (${fields.size}):\n")
            fields.sortedBy { it.name }.forEach { sb.append("  ${describeField(it)}\n") }
        }
        if (want == "all" || want == "methods") {
            val methods = if (declaredOnly) clazz.declaredMethods.toList() else allMethods(clazz)
            sb.append("\nMethods (${methods.size}):\n")
            methods.sortedBy { it.name }.forEach { sb.append("  ${describeMethod(it)}\n") }
        }
        if (want == "all" || want == "constructors") {
            val ctors = clazz.declaredConstructors
            sb.append("\nConstructors (${ctors.size}):\n")
            ctors.forEach { sb.append("  ${describeConstructor(it)}\n") }
        }
        return sb.toString().trimEnd()
    }

    private fun allFields(clazz: Class<*>): List<Field> {
        val out = LinkedHashMap<String, Field>()
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredFields.forEach { out.putIfAbsent("${it.declaringClass.name}#${it.name}", it) }
            c = c.superclass
        }
        return out.values.toList()
    }

    private fun allMethods(clazz: Class<*>): List<Method> {
        val out = LinkedHashMap<String, Method>()
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.forEach {
                val sig = "${it.name}(${it.parameterTypes.joinToString { p -> p.name }})"
                out.putIfAbsent(sig, it)
            }
            c = c.superclass
        }
        return out.values.toList()
    }

    private fun describeField(f: Field): String =
        "${mods(f.modifiers)} ${f.type.simpleName} ${f.name}"

    private fun describeMethod(m: Method): String =
        "${mods(m.modifiers)} ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"

    private fun describeConstructor(c: Constructor<*>): String =
        "${mods(c.modifiers)} <init>(${c.parameterTypes.joinToString { it.simpleName }})"

    private fun mods(m: Int): String = Modifier.toString(m).ifBlank { "package-private" }

    // -------------------------------------------------------------------------------------------
    // Field access
    // -------------------------------------------------------------------------------------------

    fun getField(target: Target, name: String): Any? {
        val field = findField(target.clazz, name)
        return field.get(target.instance)
    }

    fun setField(target: Target, name: String, value: Any?) {
        val field = findField(target.clazz, name)
        field.set(target.instance, value)
    }

    private fun findField(clazz: Class<*>, name: String): Field {
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredFields
                .firstOrNull { it.name == name }
                ?.let {
                    return it.makeAccessible()
                }
            c = c.superclass
        }
        throw JvmBridgeException("No field '$name' on ${clazz.name} (or its superclasses)")
    }

    // -------------------------------------------------------------------------------------------
    // Method / constructor invocation with overload resolution
    // -------------------------------------------------------------------------------------------

    fun invokeMethod(
        target: Target,
        name: String,
        args: Array<Any?>,
        paramTypes: List<String>?,
    ): Any? {
        val candidates = allMethods(target.clazz).filter { it.name == name }
        if (candidates.isEmpty()) {
            throw JvmBridgeException("No method '$name' on ${target.clazz.name}")
        }
        val method =
            selectOverload(candidates, args, paramTypes) { it.parameterTypes }
                ?: throw JvmBridgeException(
                    "No overload of '$name' matches ${args.size} arg(s). Candidates:\n" +
                        candidates.joinToString("\n") { "  " + describeMethod(it) },
                )
        method.makeAccessible()
        return method.invoke(target.instance, *coerce(args, method.parameterTypes))
    }

    fun newInstance(clazz: Class<*>, args: Array<Any?>, paramTypes: List<String>?): Any {
        val candidates = clazz.declaredConstructors.toList()
        val ctor =
            selectOverload(candidates, args, paramTypes) { it.parameterTypes }
                ?: throw JvmBridgeException(
                    "No constructor of ${clazz.name} matches ${args.size} arg(s). Candidates:\n" +
                        candidates.joinToString("\n") { "  " + describeConstructor(it) },
                )
        ctor.makeAccessible()
        return ctor.newInstance(*coerce(args, ctor.parameterTypes))
    }

    /**
     * Picks the best overload. If [paramTypes] is given it must match exactly (by FQCN of the
     * declared parameter types). Otherwise, filters by arity then by argument assignability,
     * preferring the most specific match.
     */
    private fun <M> selectOverload(
        candidates: List<M>,
        args: Array<Any?>,
        paramTypes: List<String>?,
        params: (M) -> Array<Class<*>>,
    ): M? {
        if (paramTypes != null) {
            val wanted = paramTypes.map { JvmValueBridge.resolveClass(it) }
            return candidates.firstOrNull { c -> params(c).toList() == wanted }
        }
        val byArity = candidates.filter { params(it).size == args.size }
        if (byArity.size == 1) return byArity.first()
        // Prefer overloads whose parameter types accept the supplied args.
        val assignable = byArity.filter { c -> argsAssignable(args, params(c)) }
        return assignable.firstOrNull() ?: byArity.firstOrNull()
    }

    private fun argsAssignable(args: Array<Any?>, types: Array<Class<*>>): Boolean {
        if (args.size != types.size) return false
        for (i in args.indices) {
            val a = args[i] ?: continue // null accepts any non-primitive
            val t = boxed(types[i])
            if (!t.isAssignableFrom(a.javaClass)) return false
        }
        return true
    }

    /**
     * Widening/coercion for numeric primitives so `int:1` can feed a `long`/`double` param, etc.
     */
    private fun coerce(args: Array<Any?>, types: Array<Class<*>>): Array<Any?> {
        val out = arrayOfNulls<Any?>(args.size)
        for (i in args.indices) {
            val a = args[i]
            val t = types[i]
            out[i] =
                if (
                    a is Number && (t.isPrimitive || Number::class.java.isAssignableFrom(boxed(t)))
                ) {
                    when (boxed(t)) {
                        java.lang.Integer::class.java -> a.toInt()
                        java.lang.Long::class.java -> a.toLong()
                        java.lang.Double::class.java -> a.toDouble()
                        java.lang.Float::class.java -> a.toFloat()
                        java.lang.Short::class.java -> a.toShort()
                        java.lang.Byte::class.java -> a.toByte()
                        else -> a
                    }
                } else {
                    a
                }
        }
        return out
    }

    private fun boxed(c: Class<*>): Class<*> =
        when (c) {
            Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            Character.TYPE -> Character::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            else -> c
        }
}
