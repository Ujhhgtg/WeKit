@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Shared protobuf codec for WeChat CGI request/response bodies.
 *
 * WeChat writes zero-valued scalar fields explicitly on the wire, so [encodeDefaults] is on to
 * stay byte-compatible with the native client. Keeping every `encodeToByteArray`/`decodeFromByteArray`
 * behind this object confines the experimental serialization opt-in to one place; call sites stay clean.
 */
object WeProto {

    val protoBuf = ProtoBuf
    val protoBufWithDefaults = ProtoBuf {
        encodeDefaults = true
    }

    inline fun <reified T> encode(value: T): ByteArray {
        // TODO: pre-process protobuf objects here
        return protoBuf.encodeToByteArray(value)
    }

    inline fun <reified T> encodeWithDefaults(value: T): ByteArray {
        // TODO: pre-process protobuf objects here
        return protoBufWithDefaults.encodeToByteArray(value)
    }

    inline fun <reified T> decode(bytes: ByteArray): T = protoBuf.decodeFromByteArray(bytes)
}
