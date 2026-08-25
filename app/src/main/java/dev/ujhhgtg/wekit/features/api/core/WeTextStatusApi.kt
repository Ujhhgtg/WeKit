package dev.ujhhgtg.wekit.features.api.core

import android.content.Context
import android.widget.ImageView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

sealed interface TextStatusResult {
    data class Ready(val status: TextStatus) : TextStatusResult
    data object NoStatus : TextStatusResult
    data class Error(val cause: Throwable) : TextStatusResult
}

data class TextStatus(
    val statusId: String,
    val description: String,
    val userText: String,
    val iconId: String,
    val attachedEmoji: TextStatusEmoji?,
)

data class TextStatusEmoji(
    val md5: String?,
    val url: String?,
    val thumbUrl: String?,
    val attachedText: String?,
)

object WeTextStatusApi : ApiFeature(), IResolveDex {

    override val technicalId = "微信状态服务"
    override val nameRes = R.string.feature_we_text_status_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_text_status_api_description

    private const val TAG = "WeTextStatusApi"

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
    private val methodTextStatusTopicInfo by dexMethod {
        matcher {
            usingEqStrings(
                "Super calls with default arguments not supported in this target, function: getTopicInfo",
            )
        }
    }
    private val methodSetTextStatusIcon by dexMethod {
        matcher {
            usingEqStrings(
                "Super calls with default arguments not supported in this target, function: setIcon",
            )
        }
    }
    private val methodTextStatusIconHelperAccessor by dexMethod()
    private val methodTextStatusDescription by dexMethod()
    private val methodCurrentStatusActions by dexMethod()

    private val serviceInstance by lazy {
        val serviceClass = classTextStatusService.clazz
        serviceClass.reflekt().firstField {
            type = serviceClass
            modifiers(Modifiers.STATIC)
        }.getStatic()!!
    }

    private val textStatusIconHelper by lazy {
        methodTextStatusIconHelperAccessor.method.invoke(null)!!
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        val latestStatusMethod = methodLatestStatusByUsername.data
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

        val topicInfoMethod = methodTextStatusTopicInfo.data
        val setIconMethod = methodSetTextStatusIcon.data
        val iconHelperType = setIconMethod.paramTypeNames.first()
        val iconHelperAccessor = dexKit.findMethod {
            matcher {
                paramTypes()
                returnType(iconHelperType)
                modifiers(Modifier.STATIC)
            }
        }.single()
        val statusDescriptionMethod = dexKit.findMethod {
            matcher {
                declaredClass(iconHelperType)
                paramTypes(topicInfoMethod.returnTypeName)
                returnType(String::class.java)
            }
        }.single()
        val statusType = latestStatusMethod.returnTypeName
        val currentStatusActionsMethod = dexKit.findMethod {
            matcher {
                paramTypes(Context::class.java.name, statusType)
                returnType(Void.TYPE)
                usingEqStrings(
                    "MicroMsg.MMSubMenuHelper",
                    "show, menu empty",
                )
            }
        }.single { candidate ->
            candidate.usesFieldThroughDirectInvoke("field_UserName")
        }

        classTextStatusService.setDescriptor(storageAccessor.declaredClass!!)
        methodTextStatusStorageAccessor.setDescriptor(storageAccessor)
        methodTextStatusIconHelperAccessor.setDescriptor(iconHelperAccessor)
        methodTextStatusDescription.setDescriptor(statusDescriptionMethod)
        methodCurrentStatusActions.setDescriptor(currentStatusActionsMethod)
    }

    fun read(wxId: String): TextStatusResult = runCatching {
        val current = readCurrentStatus(wxId) ?: return TextStatusResult.NoStatus
        val topicInfo = methodTextStatusTopicInfo.method.invoke(
            null,
            current.hostObject,
            false,
            1,
            null,
        )!!
        TextStatusResult.Ready(
            TextStatus(
                statusId = current.statusId,
                description = methodTextStatusDescription.method.invoke(
                    textStatusIconHelper,
                    topicInfo,
                ) as String,
                userText = (current.record.reflekt().getField("field_Description", true) as String?).orEmpty(),
                iconId = (current.record.reflekt().getField("field_IconID", true) as String?).orEmpty(),
                attachedEmoji = parseEmojiInfo(
                    current.record.reflekt().getField("field_EmojiInfo", true) as ByteArray?,
                ),
            ),
        ).enrichEmojiUrls()
    }.getOrElse { throwable ->
        WeLogger.e(TAG, "failed to read current TextStatus", throwable)
        TextStatusResult.Error(throwable)
    }

    fun openCurrentStatusActions(context: Context, wxId: String): Boolean = runCatching {
        val current = readCurrentStatus(wxId) ?: return false
        val actionMethod = methodCurrentStatusActions.method
        val showParam = actionMethod.declaringClass.declaredConstructors
            .single().parameterTypes.single().createInstance()
        val logic = actionMethod.declaringClass.createInstance(showParam)
        logic.reflekt().firstField {
            type = Context::class
        }.set(context)
        logic.reflekt().firstField {
            type = actionMethod.parameterTypes[1]
        }.set(current.hostObject)
        actionMethod.invoke(logic, context, current.hostObject)
        true
    }.getOrElse { throwable ->
        WeLogger.e(TAG, "failed to open current TextStatus actions", throwable)
        false
    }

    fun renderIcon(imageView: ImageView, iconId: String) {
        methodSetTextStatusIcon.method.invoke(
            null,
            textStatusIconHelper,
            imageView,
            iconId,
            null,
            null,
            null,
            false,
            false,
            124,
            null,
        )
    }

    private fun readCurrentStatus(wxId: String): CurrentStatus? {
        val storage = methodTextStatusStorageAccessor.method.invoke(serviceInstance)!!
        val hostObject = methodLatestStatusByUsername.method.invoke(storage, wxId) ?: return null
        val record = unwrapStatusRecord(hostObject)
        val statusId = record.reflekt().getField("field_StatusID", true) as String?
        val expireTime = (record.reflekt().getField("field_ExpireTime", true) as Number).toLong()
        if (statusId.isNullOrBlank() || expireTime <= System.currentTimeMillis() / 1_000L) {
            return null
        }
        return CurrentStatus(hostObject, record, statusId)
    }

    private fun unwrapStatusRecord(value: Any): Any {
        val recordClass = classTextStatusRecord.clazz
        if (recordClass.isInstance(value)) return value
        return value.reflekt().fields { superclass = true }
            .first { recordClass.isAssignableFrom(it.type) }
            .get()!!
    }

    private fun TextStatusResult.Ready.enrichEmojiUrls(): TextStatusResult.Ready {
        val emoji = status.attachedEmoji ?: return this
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

        return TextStatusResult.Ready(status.copy(attachedEmoji = enrichedEmoji))
    }

    private data class CurrentStatus(
        val hostObject: Any,
        val record: Any,
        val statusId: String,
    )
}

private fun MethodData.usesFieldThroughDirectInvoke(fieldName: String): Boolean =
    usingFields.any { it.field.fieldName == fieldName } ||
        invokes.any { invoked ->
            invoked.usingFields.any { it.field.fieldName == fieldName }
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
        WeLogger.w("WeTextStatusApi", "failed to decode TextStatus EmojiInfo", it)
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
