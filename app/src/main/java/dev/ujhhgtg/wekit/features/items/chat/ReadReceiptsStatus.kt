package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ReadReceiptsServerMode {
    THIRD_PARTY,
    BUILT_IN,
}

enum class ReadReceiptsRuntimeState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
}

internal data class ReadReceiptsStatus(
    val state: ReadReceiptsRuntimeState,
    val port: Int? = null,
    val error: String? = null,
) {
    companion object {
        private const val MAX_ERROR_CHARS = 256

        fun parse(value: String): Result<ReadReceiptsStatus> = runCatching {
            val status = DefaultJson.parseToJsonElement(value).jsonObject
            val stateValue = status["state"] as? JsonPrimitive
            require(stateValue?.isString == true)
            val state = when (stateValue.content) {
                "stopped" -> ReadReceiptsRuntimeState.STOPPED
                "starting" -> ReadReceiptsRuntimeState.STARTING
                "running" -> ReadReceiptsRuntimeState.RUNNING
                "stopping" -> ReadReceiptsRuntimeState.STOPPING
                "failed" -> ReadReceiptsRuntimeState.FAILED
                else -> error("embedded server returned an unknown state")
            }
            require("port" in status && "error" in status)
            val portElement = status.getValue("port")
            val port = if (portElement is JsonNull) {
                null
            } else {
                val primitive = portElement as? JsonPrimitive
                require(primitive != null && !primitive.isString)
                primitive.intOrNull ?: error("embedded server returned an invalid port")
            }
            val errorElement = status.getValue("error")
            val error = if (errorElement is JsonNull) {
                null
            } else {
                val primitive = errorElement as? JsonPrimitive
                require(primitive?.isString == true)
                primitive.content
            }

            when (state) {
                ReadReceiptsRuntimeState.STOPPED,
                ReadReceiptsRuntimeState.STARTING,
                -> require(port == null && error == null) {
                    "embedded server returned inconsistent inactive state"
                }

                ReadReceiptsRuntimeState.RUNNING -> require(
                    port in 1..65535 && error == null,
                ) {
                    "embedded server returned inconsistent running state"
                }

                ReadReceiptsRuntimeState.STOPPING -> require(
                    (port == null || port in 1..65535) && error == null,
                ) {
                    "embedded server returned inconsistent stopping state"
                }

                ReadReceiptsRuntimeState.FAILED -> require(
                    port == null && !error.isNullOrBlank() && error.length <= MAX_ERROR_CHARS,
                ) {
                    "embedded server returned inconsistent failed state"
                }
            }
            ReadReceiptsStatus(state, port, error)
        }
    }
}
