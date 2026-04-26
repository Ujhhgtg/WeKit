package dev.ujhhgtg.wekit.hooks.api.net.abc

import dev.ujhhgtg.wekit.hooks.api.net.models.SignResult
import org.json.JSONObject

interface ISigner {
    fun match(cgiId: Int): Boolean
    fun sign(cl: ClassLoader, json: JSONObject): SignResult
}
