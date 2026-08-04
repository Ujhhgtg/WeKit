package dev.ujhhgtg.wekit.features.api.core

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

sealed interface TextStatusResult {
    data class Ready(val status: TextStatus) : TextStatusResult
    data object NoStatus : TextStatusResult
    data class Error(val cause: Throwable) : TextStatusResult
}

data class TextStatus(
    val statusId: String,
    val description: String,
    val iconId: String,
    val emoji: TextStatusEmoji?,
)

data class TextStatusEmoji(
    val md5: String?,
    val url: String?,
    val thumbUrl: String?,
    val attachedText: String?,
)

@Feature(name = "微信状态服务", categories = ["API"], description = "提供读取当前微信状态的能力")
object TextStatusApi : ApiFeature(), IResolveDex {

    private const val TAG = "TextStatusApi"

    private val classTextStatusService by dexClass()
    private val classTextStatusRecord by dexClass {
        matcher {
            addFieldForName("field_UserName")
            addFieldForName("field_StatusID")
            addFieldForName("field_IconID")
            addFieldForName("field_Description")
            addFieldForName("field_ExpireTime")
            addFieldForName("field_EmojiInfo")
        }
    }
    private val methodTextStatusStorageAccessor by dexMethod()
    private val methodLatestStatusByUsername by dexMethod {
        matcher {
            paramTypes(String::class.java)
            usingEqStrings(
                "MicroMsg.TextStatus.StatusInfoAffStorage",
                "getLatestStatusByUserName: failed",
            )
        }
    }

    private val serviceInstance by lazy {
        val serviceClass = classTextStatusService.clazz
        serviceClass.reflekt().firstField {
            type = serviceClass
            modifiers(Modifiers.STATIC)
        }.getStatic()!!
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        val latestStatusMethod = dexKit.getMethodData(
            methodLatestStatusByUsername.getDescriptorString()!!,
        )!!
        val storageInterface = latestStatusMethod.declaredClass!!.interfaces.single { candidate ->
            candidate.methods.any { method ->
                method.methodName == latestStatusMethod.methodName &&
                    method.paramTypeNames == latestStatusMethod.paramTypeNames &&
                    method.returnTypeName == latestStatusMethod.returnTypeName
            }
        }
        val storageAccessor = dexKit.findMethod {
            matcher {
                paramTypes()
                returnType(storageInterface.name)
            }
        }.single { candidate ->
            candidate.declaredClass!!.fields.any { field ->
                field.typeName == candidate.declaredClassName &&
                    Modifier.isStatic(field.modifiers)
            }
        }

        classTextStatusService.setDescriptor(storageAccessor.declaredClass!!)
        methodTextStatusStorageAccessor.setDescriptor(storageAccessor)
    }

    fun read(wxId: String): TextStatusResult = runCatching {
        val storage = methodTextStatusStorageAccessor.method.invoke(serviceInstance)!!
        val value = methodLatestStatusByUsername.method.invoke(storage, wxId)
            ?: return TextStatusResult.NoStatus
        val record = unwrapStatusRecord(value)
        val statusId = record.reflekt().getField("field_StatusID", true) as String?
        val expireTime = (record.reflekt().getField("field_ExpireTime", true) as Number).toLong()
        if (statusId.isNullOrBlank() || expireTime <= System.currentTimeMillis() / 1_000L) {
            return TextStatusResult.NoStatus
        }
        TextStatusResult.Ready(
            TextStatus(
                statusId = statusId,
                description = (record.reflekt().getField("field_Description", true) as String?).orEmpty(),
                iconId = (record.reflekt().getField("field_IconID", true) as String?).orEmpty(),
                emoji = parseEmojiInfo(
                    record.reflekt().getField("field_EmojiInfo", true) as ByteArray?,
                ),
            ),
        ).enrichEmojiUrls()
    }.getOrElse { throwable ->
        WeLogger.e(TAG, "failed to read current TextStatus", throwable)
        TextStatusResult.Error(throwable)
    }

    private fun unwrapStatusRecord(value: Any): Any {
        val recordClass = classTextStatusRecord.clazz
        if (recordClass.isInstance(value)) return value
        return value.reflekt().fields { superclass = true }
            .first { recordClass.isAssignableFrom(it.type) }
            .get()!!
    }

    private fun TextStatusResult.Ready.enrichEmojiUrls(): TextStatusResult.Ready {
        val emoji = status.emoji ?: return this
        val md5 = emoji.md5 ?: return this
        if (!emoji.url.isNullOrBlank() || !emoji.thumbUrl.isNullOrBlank()) return this

        val enrichedEmoji = runCatching {
            WeServiceApi.getEmojiInfoByMd5(md5).reflekt().let { info ->
                emoji.copy(
                    url = (info.getField("field_cdnUrl", true) as String?)?.ifBlank { null },
                    thumbUrl = (info.getField("field_thumbUrl", true) as String?)?.ifBlank { null },
                )
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to resolve TextStatus emoji URL for $md5", it)
        }.getOrNull() ?: return this

        return TextStatusResult.Ready(status.copy(emoji = enrichedEmoji))
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
private data class TextStatusEmojiProto(
    @ProtoNumber(1) val md5: String = "",
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val thumbUrl: String = "",
    @ProtoNumber(11) val attachedText: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
private fun parseEmojiInfo(bytes: ByteArray?): TextStatusEmoji? {
    val payload = bytes ?: return null
    if (payload.isEmpty()) return null
    return runCatching {
        ProtoBuf.decodeFromByteArray<TextStatusEmojiProto>(payload)
    }.onFailure {
        WeLogger.w("TextStatusApi", "failed to decode TextStatus EmojiInfo", it)
    }.getOrNull()?.let { proto ->
        if (proto.md5.isBlank() && proto.url.isBlank() &&
            proto.thumbUrl.isBlank() && proto.attachedText.isBlank()
        ) {
            null
        } else {
            TextStatusEmoji(
                md5 = proto.md5.ifBlank { null },
                url = proto.url.ifBlank { null },
                thumbUrl = proto.thumbUrl.ifBlank { null },
                attachedText = proto.attachedText.ifBlank { null },
            )
        }
    }
}
