package moe.ouom.wekit.hooks.api.net.model

import org.json.JSONObject

data class SignResult(
    val json: JSONObject,
    val nativeNetScene: Any? = null,
    val onSendSuccess: (() -> Unit)? = null
)