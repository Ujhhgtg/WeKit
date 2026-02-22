package moe.ouom.wekit.hooks.items.automation

import moe.ouom.wekit.hooks.sdk.base.WeMessageApi
import moe.ouom.wekit.utils.log.WeLogger
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject

object AutomationEngine {

    private const val TAG = "AutomationEngine"

    fun executeAllOnMessage(
        rules: List<AutomationRule>,
        talker: String,
        content: String,
        type: Int,
        isSend: Int,
    ) {
        // if (isSend != 0) return // ignore outgoing
        if (content.isBlank()) {
            WeLogger.i(TAG, "message is blank")
            return
        }

        for (rule in rules) {
            WeLogger.d(TAG, "evaluating rule id=${rule.id} name='${rule.name}'")

            if (!rule.enabled) continue
            try {
                executeOnMessage(rule.script, talker, content, type, isSend)
            } catch (e: Exception) {
                WeLogger.e(TAG, "rule id=${rule.id} name='${rule.name}' threw during onMessage", e)
            }
        }
    }

    private fun executeOnMessage(
        script: String,
        talker: String,
        content: String,
        type: Int,
        isSend: Int,
    ) {
        val cx: Context = Context.enter()
        try {
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()

            JsApiExposer.exposeApis(scope, talker)

            cx.evaluateString(scope, script, "AutomationRule", 1, null)

            val fn = scope.get("onMessage", scope)
            if (fn == ScriptableObject.NOT_FOUND || fn !is Function) {
                WeLogger.w(TAG, "JS script does not define onMessage()")
                return
            }

            val result = fn.call(cx, scope, scope, arrayOf<Any?>(talker, content, type, isSend))
                ?: return

            handleOnMessageReturnValue(result, talker)
        } finally {
            Context.exit()
        }
    }

    private fun handleOnMessageReturnValue(result: Any, talker: String) {
        when (result) {
            is String -> {
                if (result.isNotBlank()) WeMessageApi.sendText(talker, result)
            }
            is NativeObject -> {
                val type    = result["type"]?.toString() ?: "text"
                val content = result["content"]?.toString()
                val path    = result["path"]?.toString()
                val title   = result["title"]?.toString()
                val duration = (result["duration"] as? Number)?.toInt() ?: 0

                when (type) {
                    "text"  -> content?.let { WeMessageApi.sendText(talker, it) }
                    "image" -> path?.let { WeMessageApi.sendImage(talker, it) }
                    "file"  -> path?.let { WeMessageApi.sendFile(talker, it, title ?: path.substringAfterLast('/')) }
                    "voice" -> path?.let { WeMessageApi.sendVoice(talker, it, duration) }
                    else    -> WeLogger.w(TAG, "unknown js return type: $type")
                }
            }
            else -> WeLogger.w(TAG, "onMessage() returned unexpected type: ${result::class.java}")
        }
    }

    fun executeAllOnRequest(
        uri: String,
        cgiId: Int,
        json: JSONObject,
    ): JSONObject {
        var modifiedJson = json

        for (rule in AutomationHook.rules) {
            if (!rule.enabled) continue
            try {
                val result = executeOnRequest(rule.script, uri, cgiId, modifiedJson)
                if (result != null) {
                    modifiedJson = result
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "rule id=${rule.id} name='${rule.name}' threw during onRequest", e)
            }
        }

        return modifiedJson
    }


    private fun executeOnRequest(
        script: String,
        uri: String,
        cgiId: Int,
        json: JSONObject,
    ): JSONObject? {
        val cx: Context = Context.enter()
        try {
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()

            JsApiExposer.exposeApis(scope)

            val jsonStr = json.toString()
            val jsonObj = cx.evaluateString(scope, "($jsonStr)", "json", 1, null)

            cx.evaluateString(scope, script, "AutomationRule", 1, null)

            val fn = scope.get("onRequest", scope)
            if (fn == ScriptableObject.NOT_FOUND || fn !is Function) {
                return null
            }

            val result = fn.call(cx, scope, scope, arrayOf<Any?>(uri, cgiId, jsonObj))
                ?: return null

            val resultStr = when (result) {
                is NativeObject -> {
                    val stringify = scope.get("JSON", scope) as NativeObject
                    val stringifyFn = stringify.get("stringify", stringify) as Function
                    stringifyFn.call(cx, scope, stringify, arrayOf(result)) as String
                }
                is String -> result
                else -> return null
            }

            return JSONObject(resultStr)
        } finally {
            Context.exit()
        }
    }

    fun executeAllOnResponse(
        uri: String,
        cgiId: Int,
        json: JSONObject,
    ): JSONObject {
        var modifiedJson = json

        for (rule in AutomationHook.rules) {
            if (!rule.enabled) continue
            try {
                val result = executeOnResponse(rule.script, uri, cgiId, modifiedJson)
                if (result != null) {
                    modifiedJson = result
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "rule id=${rule.id} name='${rule.name}' threw during onResponse", e)
            }
        }

        return modifiedJson
    }

    private fun executeOnResponse(
        script: String,
        uri: String,
        cgiId: Int,
        json: JSONObject,
    ): JSONObject? {
        val cx: Context = Context.enter()
        try {
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()

            JsApiExposer.exposeApis(scope)

            val jsonStr = json.toString()
            val jsonObj = cx.evaluateString(scope, "($jsonStr)", "json", 1, null)

            cx.evaluateString(scope, script, "AutomationRule", 1, null)

            val fn = scope.get("onResponse", scope)
            if (fn == ScriptableObject.NOT_FOUND || fn !is Function) {
                return null
            }

            val result = fn.call(cx, scope, scope, arrayOf<Any?>(uri, cgiId, jsonObj))
                ?: return null

            val resultStr = when (result) {
                is NativeObject -> {
                    val stringify = scope.get("JSON", scope) as NativeObject
                    val stringifyFn = stringify.get("stringify", stringify) as Function
                    stringifyFn.call(cx, scope, stringify, arrayOf(result)) as String
                }
                is String -> result
                else -> return null
            }

            return JSONObject(resultStr)
        } finally {
            Context.exit()
        }
    }
}