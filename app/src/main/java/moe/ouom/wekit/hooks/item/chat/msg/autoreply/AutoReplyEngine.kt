package moe.ouom.wekit.hooks.item.chat.msg.autoreply

import moe.ouom.wekit.hooks.sdk.api.WeMessageApi
import moe.ouom.wekit.util.log.WeLogger
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

object AutoReplyEngine {

    private const val TAG = "AutoReplyEngine"

    /**
     * Evaluate all enabled rules against an incoming message.
     * Returns after the first matching rule fires.
     */
    fun evaluate(
        rules: List<AutoReplyRule>,
        talker: String,
        content: String,
        type: Int,
        isSend: Int,
    ) {
        val api = WeMessageApi.INSTANCE
        if (api == null) {
            WeLogger.e(TAG, "WeMessageApi not initialized")
            return
        }

        if (isSend != 0) return // ignore outgoing
        if (content.isBlank()) {
            WeLogger.i(TAG, "message is blank")
            return
        }

        for (rule in rules) {
            WeLogger.d(TAG, "evaluating rule id=${rule.id} name='${rule.name}'")

            if (!rule.enabled) continue
            try {
                when (rule.matchRule) {
                    // first matching rule wins — break after any successful dispatch
                    is MatchRule.Exact -> {
                        if (content == rule.matchRule.pattern) {
                            api.sendText(talker, rule.replyText)
                            break
                        }
                    }
                    is MatchRule.Contains -> {
                        if (content.contains(rule.matchRule.keyword)) {
                            api.sendText(talker, rule.replyText)
                            break
                        }
                    }
                    is MatchRule.Regex -> {
                        if (Regex(rule.matchRule.pattern).containsMatchIn(content)) {
                            api.sendText(talker, rule.replyText)
                            break
                        }
                    }
                    is MatchRule.JavaScript -> {
                        executeJs(rule.matchRule.script, talker, content, type, isSend)
                        // TODO: let js return whether message is consumed,
                        //       currently just unconditionally breaks
                        break
                    }
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "rule id=${rule.id} name='${rule.name}' threw during evaluation", e)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // JS execution
    // -----------------------------------------------------------------------------------------

    private fun executeJs(
        script: String,
        talker: String,
        content: String,
        type: Int,
        isSend: Int,
    ) {
        val cx: Context = Context.enter()
        try {
            cx.optimizationLevel = -1          // required on Android (no JIT)
            val scope = cx.initStandardObjects()

            // Expose send*() APIs so JS can call them directly
            exposeSendApis(scope, talker)

            // Evaluate the user script (defines onMessage)
            cx.evaluateString(scope, script, "AutoReplyRule", 1, null)

            // Call onMessage(talker, content, type, isSend)
            val fn = scope.get("onMessage", scope)
            if (fn == ScriptableObject.NOT_FOUND || fn !is Function) {
                WeLogger.w(TAG, "JS script does not define onMessage()")
                return
            }

            val result = fn.call(cx, scope, scope, arrayOf<Any?>(talker, content, type, isSend))
                ?: return

            dispatchJsResult(result, talker)
        } finally {
            Context.exit()
        }
    }

    /**
     * Inject sendText / sendImage / sendFile / sendVoice into the Rhino scope
     * so user scripts can call them without going through the return value.
     */
    private fun exposeSendApis(scope: ScriptableObject, talker: String) {
        val api = WeMessageApi.INSTANCE ?: return

        val apiBootstrap = """
            function sendText(to, text)                          { _sendText(to, text); }
            function sendImage(to, path)                         { _sendImage(to, path); }
            function sendFile(to, path, title)                   { _sendFile(to, path, title); }
            function sendVoice(to, path, durationMs)             { _sendVoice(to, path, durationMs); }
            // Convenience: reply to the current talker without specifying 'to'
            function replyText(text)                             { _sendText('$talker', text); }
            function replyImage(path)                            { _sendImage('$talker', path); }
            function replyFile(path, title)                      { _sendFile('$talker', path, title); }
            function replyVoice(path, durationMs)                { _sendVoice('$talker', path, durationMs); }
        """.trimIndent()

        // Bind the native _send* functions using ScriptableObject
        fun nativeFn(argCount: Int, block: (Array<Any?>) -> Unit) =
            object : BaseFunction() {
                override fun getArity() = argCount
                override fun call(
                    cx: Context, scope: Scriptable,
                    thisObj: Scriptable, args: Array<Any?>,
                ) = block(args).let { Context.getUndefinedValue() }
            }

        ScriptableObject.putProperty(scope, "_sendText",
            nativeFn(2) { args ->
                val to   = args.getOrNull(0)?.toString() ?: return@nativeFn
                val text = args.getOrNull(1)?.toString() ?: return@nativeFn
                api.sendText(to, text)
            }
        )
        ScriptableObject.putProperty(scope, "_sendImage",
            nativeFn(2) { args ->
                val to   = args.getOrNull(0)?.toString() ?: return@nativeFn
                val path = args.getOrNull(1)?.toString() ?: return@nativeFn
                api.sendImage(to, path)
            }
        )
        ScriptableObject.putProperty(scope, "_sendFile",
            nativeFn(3) { args ->
                val to    = args.getOrNull(0)?.toString() ?: return@nativeFn
                val path  = args.getOrNull(1)?.toString() ?: return@nativeFn
                val title = args.getOrNull(2)?.toString() ?: path.substringAfterLast('/')
                api.sendFile(to, path, title)
            }
        )
        ScriptableObject.putProperty(scope, "_sendVoice",
            nativeFn(3) { args ->
                val to         = args.getOrNull(0)?.toString() ?: return@nativeFn
                val path       = args.getOrNull(1)?.toString() ?: return@nativeFn
                val durationMs = (args.getOrNull(2) as? Number)?.toInt() ?: 0
                api.sendVoice(to, path, durationMs)
            }
        )

        val cx = Context.getCurrentContext()!!
        cx.evaluateString(scope, apiBootstrap, "ApiBootstrap", 1, null)
    }

    /**
     * Dispatch a value returned from onMessage():
     *   - String          → sendText
     *   - NativeObject    → dispatch by .type field
     */
    private fun dispatchJsResult(result: Any, talker: String) {
        val api = WeMessageApi.INSTANCE ?: return
        when (result) {
            is String -> {
                if (result.isNotBlank()) api.sendText(talker, result)
            }
            is NativeObject -> {
                val type    = result["type"]?.toString() ?: "text"
                val content = result["content"]?.toString()
                val path    = result["path"]?.toString()
                val title   = result["title"]?.toString()
                val duration = (result["duration"] as? Number)?.toInt() ?: 0

                when (type) {
                    "text"  -> content?.let { api.sendText(talker, it) }
                    "image" -> path?.let { api.sendImage(talker, it) }
                    "file"  -> path?.let { api.sendFile(talker, it, title ?: path.substringAfterLast('/')) }
                    "voice" -> path?.let { api.sendVoice(talker, it, duration) }
                    else    -> WeLogger.w(TAG, "Unknown JS reply type: $type")
                }
            }
            else -> WeLogger.w(TAG, "onMessage() returned unexpected type: ${result::class.java}")
        }
    }
}