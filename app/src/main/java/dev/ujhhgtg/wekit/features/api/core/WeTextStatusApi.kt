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
import dev.ujhhgtg.wekit.dexkit.sql.sqlMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.lang.reflect.Modifier
import dev.ujhhgtg.wekit.dexkit.matcher.NameSpec
import dev.ujhhgtg.wekit.dexkit.matcher.FieldSpec
import dev.ujhhgtg.wekit.dexkit.matcher.ClassSpec
import dev.ujhhgtg.wekit.dexkit.dsl.dexClassQuery
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.dexkit.matcher.TypeSpec
import dev.ujhhgtg.wekit.dexkit.matcher.StringSpec
import dev.ujhhgtg.wekit.dexkit.matcher.NameMatch
import dev.ujhhgtg.wekit.dexkit.matcher.MethodSpec
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo

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
    private val classTextStatusRecord by dexClassQuery {
        uniqueClass(
            ClassSpec(
                fields = listOf(
                    FieldSpec(name = NameSpec("field_UserName")),
                    FieldSpec(name = NameSpec("field_StatusID")),
                    FieldSpec(name = NameSpec("field_IconID")),
                    FieldSpec(name = NameSpec("field_Description")),
                    FieldSpec(name = NameSpec("field_ExpireTime")),
                    FieldSpec(name = NameSpec("field_EmojiInfo")),
                ),
            )
        )
    }
    private val methodTextStatusStorageAccessor by dexMethod()
    private val methodLatestStatusByUsername by dexMethod(
        sqlMethod {
            paramTypes("java.lang.String")
            usingEqStrings("MicroMsg.TextStatus.StatusInfoAffStorage", "getLatestStatusByUserName: failed")
        },
    )
    private val methodTextStatusTopicInfo by dexMethod(
        sqlMethod {
            usingEqStrings("Super calls with default arguments not supported in this target, function: getTopicInfo")
        },
    )
    private val methodSetTextStatusIcon by dexMethod(
        sqlMethod {
            usingEqStrings("Super calls with default arguments not supported in this target, function: setIcon")
        },
    )
    private val methodTextStatusIconHelperAccessor by dexMethod()
    private val methodTextStatusDescription by dexMethod()
    private val methodCurrentStatusActions by dexMethod()

    private val serviceInstance by lazy {
        val serviceClass = classTextStatusService.clazz
        serviceClass.reflekt().firstField {
            type = serviceClass
            modifiers(Modifiers.STATIC)
        }.get()!!
    }

    private val textStatusIconHelper by lazy {
        methodTextStatusIconHelperAccessor.method.invoke(null)!!
    }

    override fun resolveDex() {
        val latestStatusMethod = methodLatestStatusByUsername.data
        val storageInterface = latestStatusMethod.declaredClass!!.interfaces.single { candidate ->
            candidate.methods.any { method ->
                method.name == latestStatusMethod.name &&
                    method.paramTypeNames == latestStatusMethod.paramTypeNames &&
                    method.returnTypeName == latestStatusMethod.returnTypeName
            }
        }
        val storageAccessor = DexResolutionContext.matcher.findMethods(
            MethodSpec(
                paramTypes = emptyList(),
                returnType = ClassSpec(className = NameSpec(storageInterface.name)),
            ),
        ).single { candidate ->
            candidate.declaredClass!!.fields.any { field ->
                field.typeName == candidate.declaredClassName &&
                    Modifier.isStatic(field.modifiers)
            }
        }

        val topicInfoMethod = methodTextStatusTopicInfo.data
        val setIconMethod = methodSetTextStatusIcon.data
        val iconHelperType = setIconMethod.paramTypeNames.first()
        val iconHelperAccessor = DexResolutionContext.matcher.findMethods(
            MethodSpec(
                paramTypes = emptyList(),
                returnType = ClassSpec(className = NameSpec(iconHelperType)),
                modifiers = Modifier.STATIC,
            ),
        ).single()
        val statusDescriptionMethod = DexResolutionContext.matcher.findMethods(
            MethodSpec(
                declaredClass = ClassSpec(className = NameSpec(iconHelperType)),
                paramTypes = listOf(TypeSpec(topicInfoMethod.returnTypeName)),
                returnType = ClassSpec(className = NameSpec(String::class.java.name)),
            ),
        ).single()
        val statusType = latestStatusMethod.returnTypeName
        val currentStatusActionsMethod = DexResolutionContext.matcher.findMethods(
            MethodSpec(
                paramTypes = listOf(TypeSpec(Context::class.java.name), TypeSpec(statusType)),
                returnType = ClassSpec(className = NameSpec(Void.TYPE.name)),
                usingStrings = listOf(
                    StringSpec("MicroMsg.MMSubMenuHelper", NameMatch.Equals),
                    StringSpec("show, menu empty", NameMatch.Equals),
                ),
            ),
        ).single { candidate ->
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

private fun DexMethodInfo.usesFieldThroughDirectInvoke(fieldName: String): Boolean =
    usingFields.any { it.field.fieldName == fieldName } ||
        invokes.any { invoked ->
            invoked.usingFields.any { it.field.fieldName == fieldName }
        }

@Serializable
private data class TextStatusEmojiProto(
    @ProtoNumber(1) val md5: String = "",
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val thumbUrl: String = "",
    @ProtoNumber(11) val attachedText: String = "",
)

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
