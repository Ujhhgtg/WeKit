@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf

import dev.ujhhgtg.wekit.features.api.net.WePacketSigner
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Shared protobuf codec for WeChat CGI request/response bodies.
 *
 * WeChat writes zero-valued scalar fields explicitly on the wire, so [encodeDefaults] is on to stay
 * byte-compatible with the native client. Keeping every `encodeToByteArray`/`decodeFromByteArray`
 * behind this object confines the experimental serialization opt-in to one place; call sites stay
 * clean.
 */
object WeProto {

    val protoBuf = ProtoBuf
    val protoBufWithDefaults = ProtoBuf {
        encodeDefaults = true
    }

    @Deprecated(
        message =
            "You might want encodeWithDefaults() instead. Use encode() when you are absolutely sure.",
        level = DeprecationLevel.WARNING,
    )
    inline fun <reified T : Any> encode(value: T): ByteArray {
        val processed = WePacketSigner.preprocess(value)
        return protoBuf.encodeToByteArray(processed)
    }

    inline fun <reified T : Any> encodeWithDefaults(value: T): ByteArray {
        val processed = WePacketSigner.preprocess(value)
        return protoBufWithDefaults.encodeToByteArray(processed)
    }

    inline fun <reified T> decode(bytes: ByteArray): T = protoBuf.decodeFromByteArray(bytes)
}
