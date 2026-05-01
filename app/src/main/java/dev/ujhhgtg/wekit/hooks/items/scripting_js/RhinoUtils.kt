@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.hooks.items.scripting_js

import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

//private lateinit var regExpProxyField: Field

inline fun Context.init(talker: String? = null): ScriptableObject {
    this.isInterpretedMode = true
    val scope = this.initStandardObjects()
    JsApiExposer.exposeApis(scope, talker)
    return scope
}
