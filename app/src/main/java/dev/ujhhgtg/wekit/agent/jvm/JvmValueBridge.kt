package dev.ujhhgtg.wekit.agent.jvm

import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.byte
import dev.ujhhgtg.wekit.utils.reflection.char
import dev.ujhhgtg.wekit.utils.reflection.double
import dev.ujhhgtg.wekit.utils.reflection.float
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.reflection.long
import dev.ujhhgtg.wekit.utils.reflection.short
import dev.ujhhgtg.wekit.utils.reflection.void
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Backs the `builtin-jvm` tool provider. JVM objects cannot be expressed as text, so any
 * non-trivial value the model produces or receives is referred to through an opaque, session-
 * global **handle** like `#12`, held in [JvmObjectRegistry]. Every value the model passes as an
 * argument is a self-describing `kind:payload` **token** (see
 * [dev.ujhhgtg.wekit.agent.jvm.JvmValueBridge.parseValue]); every result is rendered back either
 * inline (primitives/strings/null) or as a freshly-minted handle (see
 * [dev.ujhhgtg.wekit.agent.jvm.JvmValueBridge.render]).
 *
 * The registry is process-global (the tool-invoker layer has no session id); the model manages its
 * lifetime with the `jvm-clear-handles` tool.
 *
 * **Values are held weakly.** `ui-dump-tree` mints a handle for *every* visible view, and it is a
 * factory-ENABLED tool the model calls freely — with strong references, a handful of dumps across a
 * few chat screens pinned every one of those Activities (and their bitmaps) for the lifetime of the
 * WeChat process, since the only way to release them was the MANUAL_APPROVAL `jvm-clear-handles`
 * tool. Weak values let the host reclaim a screen the moment it is genuinely gone; a handle that
 * outlives its object resolves to null and the caller reports it as *expired* (see
 * [missingHandleMessage]) instead of NPE-ing. The map is additionally bounded ([MAX_ENTRIES]) so
 * the leftover empty shells can't grow without limit either.
 */
@Keep
object JvmObjectRegistry {
    /** Upper bound on retained entries; oldest (lowest-id) entries are evicted past it. */
    private const val MAX_ENTRIES = 4096

    /**
     * How many of the most recently stored objects are additionally held **strongly**. Weak-only
     * would break `jvm-new` / `jvm-invoke`: a freshly constructed object nothing else references
     * would be collectable the moment its handle was minted. A short strong tail keeps such values
     * usable for the following calls, while a bulk `ui-dump-tree` (hundreds of views) rotates
     * straight through it, so no Activity is pinned.
     */
    private const val STRONG_TAIL = 64

    private val counter = AtomicInteger(0)
    private val objects = ConcurrentHashMap<Int, WeakReference<Any>>()
    private val strongTail = ArrayDeque<Any>()

    /** Stores [obj], returning its handle string (e.g. `#12`). */
    fun store(obj: Any): String {
        val id = counter.incrementAndGet()
        objects[id] = WeakReference(obj)
        synchronized(strongTail) {
            strongTail.addLast(obj)
            while (strongTail.size > STRONG_TAIL) strongTail.removeFirst()
        }
        if (objects.size > MAX_ENTRIES) evict()
        return "#$id"
    }

    /** Resolves a handle id (with or without the leading `#`) to its object, or null. */
    fun resolve(handle: String): Any? {
        val id = handle.removePrefix("#").trim().toIntOrNull() ?: return null
        return objects[id]?.get()
    }

    /** For eval convenience: resolve by raw int id. */
    fun ref(id: Int): Any? = objects[id]?.get()

    /**
     * A model-readable explanation for a handle that failed to resolve, distinguishing an expired
     * (garbage-collected) handle from one that never existed. Callers surface this as the tool
     * result.
     */
    fun missingHandleMessage(handle: String): String {
        val id =
            handle.removePrefix("#").trim().toIntOrNull()
                ?: return "Malformed handle '$handle' (expected #N)."
        return if (objects.containsKey(id)) {
            "Handle '#$id' has expired — the object it pointed at was garbage-collected (handles are " +
                "weak so they never keep whole screens alive). Re-run the tool that produced it " +
                "(e.g. ui-dump-tree / ui-find-views) to obtain fresh handles."
        } else {
            "No live handle '#$id' (it was never created, or the handle registry has been cleared)."
        }
    }

    /** Number of handles whose object is still alive. */
    fun size(): Int = objects.values.count { it.get() != null }

    fun clear(): Int {
        val n = size()
        objects.clear()
        synchronized(strongTail) { strongTail.clear() }
        return n
    }

    /**
     * Drops entries whose referent has been collected. Called at turn teardown: a full [clear] is
     * deliberately NOT done there, because the registry is process-global while turns are
     * per-session and several sessions can run at once — clearing would yank handles out from under
     * a concurrent turn. Weak values already make the memory release automatic; this only reaps the
     * empty shells.
     */
    fun purgeDead(): Int {
        val before = objects.size
        objects.entries.removeAll { it.value.get() == null }
        return before - objects.size
    }

    /** Evicts dead entries, then the oldest live ones (ids are monotonic) to stay under the cap. */
    private fun evict() {
        purgeDead()
        val excess = objects.size - MAX_ENTRIES
        if (excess > 0) {
            objects.keys.sorted().take(excess).forEach { objects.remove(it) }
        }
    }

    /** A short, model-readable summary of every live handle. */
    fun describeAll(): String {
        val live =
            objects.entries
                .mapNotNull { (id, ref) -> ref.get()?.let { id to it } }
                .sortedBy { it.first }
        if (live.isEmpty()) return "No live handles."
        return live.joinToString("\n") { (id, obj) ->
            "#$id : ${obj.javaClass.name} = ${JvmValueBridge.previewOf(obj)}"
        }
    }
}

/** Thrown for malformed tokens / unresolvable classes; surfaced to the model as the tool result. */
class JvmBridgeException(message: String) : RuntimeException(message)

object JvmValueBridge {
    private const val PREVIEW_MAX = 300

    // -------------------------------------------------------------------------------------------
    // Class / type resolution
    // -------------------------------------------------------------------------------------------

    private val PRIMITIVES: Map<String, Class<*>> =
        mapOf(
            "boolean" to bool,
            "byte" to byte,
            "char" to char,
            "short" to short,
            "int" to int,
            "long" to long,
            "float" to float,
            "double" to double,
            "void" to void,
        )

    /**
     * Resolves a fully-qualified class name to a [Class], supporting primitives (`int`), arrays
     * (`java.lang.String[]`, `int[]`), and any host/module class via
     * [ClassLoaders.HYBRID_FOR_MODULE].
     */
    fun resolveClass(fqcn: String): Class<*> {
        val name = fqcn.trim()
        PRIMITIVES[name]?.let {
            return it
        }
        if (name.endsWith("[]")) {
            val component = resolveClass(name.removeSuffix("[]"))
            return java.lang.reflect.Array.newInstance(component, 0).javaClass
        }
        return runCatching { name.toClass(ClassLoaders.HYBRID_FOR_MODULE) }
            .getOrElse { throw JvmBridgeException("Class not found: $name (${it.message})") }
    }

    // -------------------------------------------------------------------------------------------
    // Value token parsing  (kind:payload)
    // -------------------------------------------------------------------------------------------

    /**
     * Parses one value token into a runtime value.
     * - `null` → null
     * - `str:<text>` → String
     * - `int:/long:/short:/byte:` → integral primitives
     * - `dbl:/flt:` → floating primitives
     * - `bool:<true|false>` → boolean
     * - `char:<c>` → char
     * - `class:<fqcn>` → Class object
     * - `ref:#N` / `#N` → object from the handle registry
     * - `enum:<fqcn>#NAME` → enum constant
     */
    fun parseValue(token: String): Any? {
        val t = token.trim()
        if (t == "null") return null
        val idx = t.indexOf(':')
        // Bare handle shorthand.
        if (t.startsWith("#")) return resolveRef(t)
        if (idx < 0) {
            throw JvmBridgeException(
                "Malformed value token '$token' (expected kind:payload, e.g. str:hello or ref:#3)",
            )
        }
        val kind = t.substring(0, idx)
        val payload = t.substring(idx + 1)
        return when (kind) {
            "str" -> {
                payload
            }

            "int" -> {
                payload.trim().toIntOrNull()
                    ?: throw JvmBridgeException("int: expects an integer, got '$payload'")
            }

            "long" -> {
                payload.trim().toLongOrNull()
                    ?: throw JvmBridgeException("long: expects an integer, got '$payload'")
            }

            "short" -> {
                payload.trim().toShortOrNull()
                    ?: throw JvmBridgeException("short: expects a short, got '$payload'")
            }

            "byte" -> {
                payload.trim().toByteOrNull()
                    ?: throw JvmBridgeException("byte: expects a byte, got '$payload'")
            }

            "dbl",
            "double" -> {
                payload.trim().toDoubleOrNull()
                    ?: throw JvmBridgeException("dbl: expects a number, got '$payload'")
            }

            "flt",
            "float" -> {
                payload.trim().toFloatOrNull()
                    ?: throw JvmBridgeException("flt: expects a number, got '$payload'")
            }

            "bool",
            "boolean" -> {
                when (payload.trim().lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw JvmBridgeException("bool: expects true|false, got '$payload'")
                }
            }

            "char" -> {
                payload.firstOrNull() ?: throw JvmBridgeException("char: expects a character")
            }

            "class" -> {
                resolveClass(payload)
            }

            "ref" -> {
                resolveRef(payload)
            }

            "enum" -> {
                parseEnum(payload)
            }

            else -> {
                throw JvmBridgeException("Unknown value kind '$kind' in token '$token'")
            }
        }
    }

    private fun resolveRef(handle: String): Any =
        JvmObjectRegistry.resolve(handle)
            ?: throw JvmBridgeException(JvmObjectRegistry.missingHandleMessage(handle))

    @Suppress("UNCHECKED_CAST")
    private fun parseEnum(payload: String): Any {
        val hash = payload.lastIndexOf('#')
        if (hash < 0) throw JvmBridgeException("enum: expects <fqcn>#NAME, got '$payload'")
        val cls = resolveClass(payload.substring(0, hash))
        val name = payload.substring(hash + 1)
        if (!cls.isEnum) throw JvmBridgeException("${cls.name} is not an enum")
        return (cls.enumConstants as Array<Enum<*>>).firstOrNull { it.name == name }
            ?: throw JvmBridgeException("No enum constant ${cls.name}#$name")
    }

    /** Parses a list of value tokens (method/constructor args). */
    fun parseArgs(tokens: List<String>?): Array<Any?> =
        (tokens ?: emptyList()).map { parseValue(it) }.toTypedArray()

    // -------------------------------------------------------------------------------------------
    // Result rendering
    // -------------------------------------------------------------------------------------------

    /** A truncated, single-line preview of an object's toString. */
    fun previewOf(value: Any?): String {
        if (value == null) return "null"
        val raw = runCatching {
            value.toString()
        }
            .getOrElse { "<toString threw ${it.javaClass.simpleName}>" }
        val oneLine = raw.replace('\n', ' ').replace('\r', ' ')
        return if (oneLine.length > PREVIEW_MAX) oneLine.take(PREVIEW_MAX) + "…" else oneLine
    }

    /**
     * Renders a returned value for the model. Primitives / boxed primitives / String / null are
     * shown inline with their type; everything else is stored in the registry and returned as a `#N
     * : <type> = <preview>` handle so it can be referenced in later calls.
     */
    fun render(value: Any?): String {
        if (value == null) return "null"
        if (isInline(value)) return "${value.javaClass.simpleName} = ${previewOf(value)}"
        val handle = JvmObjectRegistry.store(value)
        return "$handle : ${value.javaClass.name} = ${previewOf(value)}"
    }

    private fun isInline(value: Any): Boolean =
        when (value) {
            is CharSequence,
            is Boolean,
            is Char,
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double -> true

            else -> false
        }

    // -------------------------------------------------------------------------------------------
    // Main-thread marshaling
    // -------------------------------------------------------------------------------------------

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Runs [block] on the WeChat main (UI) thread and returns its result, blocking the calling (IO)
     * thread up to [timeoutMs]. Needed for View/Activity work. If already on the main thread, runs
     * inline.
     */
    fun <T> onMain(timeoutMs: Long = 10_000L, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw JvmBridgeException("Main-thread call timed out after ${timeoutMs}ms")
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
