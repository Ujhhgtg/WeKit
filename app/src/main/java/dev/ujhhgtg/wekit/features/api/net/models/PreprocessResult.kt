package dev.ujhhgtg.wekit.features.api.net.models

import org.json.JSONObject

data class PreprocessResult(
    val json: JSONObject? = null,
    val protoBytes: ByteArray? = null,
    val nativeNetScene: Any? = null,
    val onSendSuccess: (() -> Unit)? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PreprocessResult

        if (json != other.json) return false
        if (!protoBytes.contentEquals(other.protoBytes)) return false
        if (nativeNetScene != other.nativeNetScene) return false
        if (onSendSuccess != other.onSendSuccess) return false

        return true
    }

    override fun hashCode(): Int {
        var result = json?.hashCode() ?: 0
        result = 31 * result + (protoBytes?.contentHashCode() ?: 0)
        result = 31 * result + (nativeNetScene?.hashCode() ?: 0)
        result = 31 * result + (onSendSuccess?.hashCode() ?: 0)
        return result
    }
}
