@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.hooks.items.scripting_js

import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

fun Context.init(talker: String? = null): ScriptableObject {
    this.isInterpretedMode = true
    val scope = initStandardObjects()
    JsApiExposer.exposeApis(scope, talker)
    return scope
}
