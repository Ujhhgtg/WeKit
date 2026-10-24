@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class FavInfoProto(
    @ProtoNumber(1) val chatInfo: ChatInfoProto,
    @ProtoNumber(2) val voiceInfo: VoiceInfoProto,
) {

    @Serializable
    data class ChatInfoProto(
        @ProtoNumber(2) val senderId: String,
    )

    @Serializable
    data class VoiceInfoProto(
        @ProtoNumber(10) val duration: Int,
        @ProtoNumber(16) val fileCacheType: String,
        @ProtoNumber(17) val md5Checksum: String,
        @ProtoNumber(19) val fileSize: Int,
        @ProtoNumber(20) val fileCacheName: String,
        @ProtoNumber(21) val filePath: String? = null,
    )
}
