package dev.ujhhgtg.wekit.hooks.items.scripting_js

import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.utils.WeLogger
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject

object JsEngine {

    private val TAG = This.Class.simpleName

    fun executeAllOnLoad(rules: Map<String, String>) {
        for (rule in rules) {
            WeLogger.d(TAG, "executing onLoad for rule name='${rule.key}'")
            try {
                executeOnLoad(rule.value)
            } catch (e: Exception) {
                WeLogger.e(TAG, "rule name='${rule.key}' threw during onLoad", e)
            }
        }
    }

    private fun executeOnLoad(script: String) {
        val cx: Context = Context.enter()
        try {
            val scope = cx.init()

            cx.evaluateString(scope, script, "JsScript", 1, null)

            val fn = scope.get("onLoad", scope)
            if (fn == ScriptableObject.NOT_FOUND || fn !is Function) {
                WeLogger.d(TAG, "JS script does not define onLoad()")
                return
            }

            fn.call(cx, scope, scope, arrayOf<Any?>())
        } finally {
            Context.exit()
        }
    }

    fun executeAllOnMessage(
        rules: Map<String, String>,
        talker: String,
        content: String,
        type: Int,
        isSend: Int,
    ) {
        if (content.isBlank()) {
            WeLogger.i(TAG, "message is blank")
            return
        }

        for (rule in rules) {
            WeLogger.d(TAG, "evaluating rule name='${rule.key}'")

            try {
                executeOnMessage(rule.value, talker, content, type, isSend)
            } catch (e: Exception) {
                WeLogger.e(TAG, "rule name='${rule.key}' threw during onMessage", e)
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
            val scope = cx.init()

            cx.evaluateString(scope, script, "JsScript", 1, null)

            val fn = scope.get("onMessage", scope)
            if (fn == ScriptableObject.NOT_FOUND || fn !is Function) {
                WeLogger.w(TAG, "JS script does not define onMessage()")
                return
            }

            fn.call(cx, scope, scope, arrayOf<Any?>(talker, content, type, isSend))
        } finally {
            Context.exit()
        }
    }

    fun executeAllOnRequest(
        uri: String,
        cgiId: Int,
        json: JSONObject,
    ): JSONObject {
        var modifiedJson = json

        for (rule in JsScriptingHook.rules) {
            try {
                val result = executeOnRequest(rule.value, uri, cgiId, modifiedJson)
                if (result != null) {
                    modifiedJson = result
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "rule name='${rule.key}' threw during onRequest", e)
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
            val scope = cx.init()

            val jsonStr = json.toString()
            val jsonObj = cx.evaluateString(scope, "($jsonStr)", "json", 1, null)

            cx.evaluateString(scope, script, "JsScript", 1, null)

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

        for (rule in JsScriptingHook.rules) {
            try {
                val result = executeOnResponse(rule.value, uri, cgiId, modifiedJson)
                if (result != null) {
                    modifiedJson = result
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "rule name='${rule.key}' threw during onResponse", e)
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
            val scope = cx.init()

            val jsonStr = json.toString()
            val jsonObj = cx.evaluateString(scope, "($jsonStr)", "json", 1, null)

            cx.evaluateString(scope, script, "JsScript", 1, null)

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
