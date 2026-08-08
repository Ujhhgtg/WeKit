package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.json.JsonNull
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
        fun parse(value: String): Result<ReadReceiptsStatus> = runCatching {
            val status = DefaultJson.parseToJsonElement(value).jsonObject
            val state = when (status["state"]?.jsonPrimitive?.content) {
                "stopped" -> ReadReceiptsRuntimeState.STOPPED
                "starting" -> ReadReceiptsRuntimeState.STARTING
                "running" -> ReadReceiptsRuntimeState.RUNNING
                "stopping" -> ReadReceiptsRuntimeState.STOPPING
                "failed" -> ReadReceiptsRuntimeState.FAILED
                else -> error("embedded server returned an unknown state")
            }
            val port = status["port"]?.jsonPrimitive?.intOrNull
            val error = status["error"]
                ?.takeUnless { it is JsonNull }
                ?.jsonPrimitive
                ?.content

            when (state) {
                ReadReceiptsRuntimeState.RUNNING -> require(port in 1..65535) {
                    "embedded server did not report its bound port"
                }

                ReadReceiptsRuntimeState.FAILED -> require(!error.isNullOrBlank()) {
                    "embedded server did not report its failure"
                }

                else -> require(port == null || port in 1..65535) {
                    "embedded server reported an invalid port"
                }
            }
            ReadReceiptsStatus(state, port, error)
        }
    }
}
