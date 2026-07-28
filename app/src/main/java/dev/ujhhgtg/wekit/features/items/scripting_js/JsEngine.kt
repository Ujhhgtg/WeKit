package dev.ujhhgtg.wekit.features.items.scripting_js

import com.dokar.quickjs.QuickJs
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Executes every script in one persistent quickjs-kt runtime. */
object JsEngine {
    private const val TAG = "JsEngine"
    private val entryPoints = listOf("onLoad", "onMessage", "onRequest", "onResponse")
    private val slots = ConcurrentHashMap<String, ScriptSlot>()

    private class ScriptSlot {
        var source: String? = null
        var runtime: ScriptRuntime? = null

        @Volatile
        var entryPoints: Set<String> = emptySet()

        fun close() {
            runtime?.close()
            runtime = null
            source = null
            entryPoints = emptySet()
        }
    }

    /** A QuickJS runtime cannot outlive its script source or the feature enablement. */
    private class ScriptRuntime {
        private val quickJs = QuickJs.create(Dispatchers.Default)
        private val apiSession = JsApiExposer.createSession { id, args ->
            invokeCallback(id, args)
        }

        init {
            JsApiExposer.exposeApis(quickJs, apiSession)
            evaluate(JsApiExposer.BOOTSTRAP)
        }

        fun load(source: String, filename: String) {
            evaluate(source, filename)
        }

        fun hasFunction(name: String): Boolean = evaluate(
            "typeof globalThis[${JSONObject.quote(name)}] === 'function'"
        ) as? Boolean ?: false

        fun invokeEntry(name: String, args: List<Any?>): Any? = invoke(
            function = "__wekitInvokeEntry",
            firstArgument = JSONObject.quote(name),
            args = args,
        )

        fun invokeCallback(id: Long, args: List<Any?>): Any? = invoke(
            function = "__wekitInvokeCallback",
            firstArgument = id.toString(),
            args = args,
        )

        private fun invoke(function: String, firstArgument: String, args: List<Any?>): Any? = synchronized(this) {
            val invocationId = apiSession.enqueueInvocation(args)
            try {
                evaluate("globalThis[${JSONObject.quote(function)}]($firstArgument,$invocationId)", function)
            } finally {
                apiSession.discardInvocation(invocationId)
            }
        }

        private fun evaluate(source: String, filename: String = "script.js"): Any? = runBlocking {
            quickJs.evaluate<Any?>(source, filename)
        }

        fun close() {
            apiSession.clear()
            quickJs.close()
        }
    }

    fun anyScriptDefines(entry: String): Boolean = JsScriptingHook.scripts.keys.any { name ->
        val slot = slots[name] ?: return@any true
        entry in slot.entryPoints
    }

    private fun scriptDefines(name: String, entry: String): Boolean {
        val slot = slots[name] ?: return true
        return entry in slot.entryPoints
    }

    fun invalidateCache() {
        slots.values.forEach { synchronized(it) { it.close() } }
        slots.clear()
    }

    private fun <T> withScript(name: String, source: String, body: (ScriptRuntime) -> T): T {
        val slot = slots.computeIfAbsent(name) { ScriptSlot() }
        return synchronized(slot) {
            val runtime = slot.runtime?.takeIf { slot.source == source } ?: run {
                slot.close()
                ScriptRuntime().also {
                    it.load(source, name)
                    slot.runtime = it
                    slot.source = source
                }
            }
            try {
                body(runtime)
            } finally {
                slot.entryPoints = entryPoints.filterTo(HashSet()) { runtime.hasFunction(it) }
            }
        }
    }

    fun executeAllOnLoad(scripts: Map<String, String>) {
        for ((name, source) in scripts) {
            try {
                withScript(name, source) { runtime ->
                    if (runtime.hasFunction("onLoad")) runtime.invokeEntry("onLoad", emptyList())
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "script name='$name' threw during onLoad", e)
            }
        }
    }

    fun executeAllOnMessage(
        scripts: Map<String, String>,
        talker: String,
        content: String,
        type: Int,
        isSend: Int,
    ) {
        if (content.isBlank()) {
            WeLogger.i(TAG, "message is blank")
            return
        }
        for ((name, source) in scripts) {
            if (!scriptDefines(name, "onMessage")) continue
            try {
                withScript(name, source) { runtime ->
                    if (!runtime.hasFunction("onMessage")) return@withScript
                    val previous = JsApiExposer.beginMessageContext(talker)
                    try {
                        runtime.invokeEntry("onMessage", listOf(talker, content, type, isSend))
                    } finally {
                        JsApiExposer.endMessageContext(previous)
                    }
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "script name='$name' threw during onMessage", e)
            }
        }
    }

    fun executeAllOnRequest(uri: String, cgiId: Int, json: JSONObject): JSONObject? =
        executeAllWithJson("onRequest", uri, cgiId, json)

    fun executeAllOnResponse(uri: String, cgiId: Int, json: JSONObject): JSONObject? =
        executeAllWithJson("onResponse", uri, cgiId, json)

    private fun executeAllWithJson(entry: String, uri: String, cgiId: Int, json: JSONObject): JSONObject? {
        val original = json.toString()
        var current = json
        var serialized = original
        for ((name, source) in JsScriptingHook.scripts) {
            if (!scriptDefines(name, entry)) continue
            try {
                val result = withScript(name, source) { runtime ->
                    if (!runtime.hasFunction(entry)) return@withScript null
                    runtime.invokeEntry(entry, listOf(uri, cgiId, JsApiExposer.structuredValue(current.toKotlinValue())))
                }
                val map = result as? Map<*, *> ?: continue
                val next = JSONObject(map.entries.associate { it.key.toString() to it.value.toJsonCompatible() })
                current = next
                serialized = next.toString()
            } catch (e: Exception) {
                WeLogger.e(TAG, "script name='$name' threw during $entry", e)
            }
        }
        return current.takeIf { serialized != original }
    }

    private fun Any?.toJsonCompatible(): Any? = when (this) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject(entries.associate { it.key.toString() to it.value.toJsonCompatible() })
        is Iterable<*> -> JSONArray(map { it.toJsonCompatible() })
        else -> this
    }

    private fun JSONObject.toKotlinValue(): Map<String, Any?> = keys().asSequence().associateWith { key ->
        get(key).toKotlinValue()
    }

    private fun Any?.toKotlinValue(): Any? = when (this) {
        is JSONObject -> toKotlinValue()
        is JSONArray -> (0 until length()).map { get(it).toKotlinValue() }
        JSONObject.NULL -> null
        else -> this
    }
}
