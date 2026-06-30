package dev.ujhhgtg.wekit.features.api.ui

import android.content.Context
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.TimelineObjectProto
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.Intent
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.reflection.long
import dev.ujhhgtg.wekit.utils.reflection.void
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier

@Feature(
    name = "朋友圈服务",
    categories = ["API"],
    description = "提供操作朋友圈的能力"
)
object WeMomentsApi : ApiFeature(), IResolveDex {

    private val TAG = This.Class.simpleName

    data class ActionResult(
        val success: Boolean,
        val sent: Boolean,
        val message: String,
        val error: Throwable? = null
    )

    private const val SNS_INFO_CLASS = "com.tencent.mm.plugin.sns.storage.SnsInfo"
    private const val LIKE_COMMENT_TYPE = 1

    private val classSnsService by dexClass {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            usingEqStrings(
                "MicroMsg.SnsService",
                "can not add Comment"
            )
        }
    }
    private val methodSendLike by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classSnsService.clazz)
            modifiers = Modifier.STATIC
            paramTypes(SNS_INFO_CLASS, "int", null, "int")
        }
    }
    private val methodCancelLike by dexMethod {
        matcher {
            declaredClass(classSnsService.clazz)
            modifiers = Modifier.STATIC
            paramTypes(String::class.java)
            returnType(Void.TYPE)
        }
    }
    private val methodGetSnsInfoByLocalId by dexMethod {
        matcher {
            paramTypes("int")
            returnType(SNS_INFO_CLASS)
            usingStrings(
                "getByLocalId",
                "select *,rowid from SnsInfo  where SnsInfo.rowid="
            )
        }
    }
    private val methodGetSnsInfoStorage by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            modifiers = Modifier.STATIC
            paramCount(0)
            returnType(methodGetSnsInfoByLocalId.method.declaringClass)
            usingStrings(
                "com.tencent.mm.plugin.sns.model.SnsCore",
                "getSnsInfoStorage"
            )
        }
    }
    private val methodGetSnsInfoBySnsId by dexMethod {
        matcher {
            declaredClass(methodGetSnsInfoByLocalId.method.declaringClass)
            paramTypes("long")
            returnType(SNS_INFO_CLASS)
            usingStrings("select *,rowid from SnsInfo  where SnsInfo.snsId=")
        }
    }

    private val snsInfoClass by lazy { SNS_INFO_CLASS.toClass() }

    private val sendLikeMethod: Method by lazy {
        classSnsService.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            parameterCount(4)
            parameters {
                it[0] == snsInfoClass &&
                it[1] == int &&
                it[3] == int
            }
            returnType { it != void }
        }.self
    }

    val classUploadPackHelper by dexClass {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            usingEqStrings("MicroMsg.UploadPackHelper", "commit sns info ret %d, typeFlag %d sightMd5 %s")
        }
    }

    val classSnsMediaObj by dexClass {
        matcher {
            usingEqStrings("MicroMsg.snsMediaStorage", "convertImg2WxamWithoutZip origPath:%s OutOfMemoryError! rollback")
        }
    }

    val ctorUploadPackHelper by dexConstructor {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            declaredClass(classUploadPackHelper.clazz)
            paramCount(2)
        }
    }

    val methodCommit by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            declaredClass(classUploadPackHelper.clazz)
            usingEqStrings("commit", "com.tencent.mm.plugin.sns.model.UploadPackHelper")
        }
    }

    val methodSetContentDes by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            declaredClass(classUploadPackHelper.clazz)
            usingEqStrings("setContentDes", "com.tencent.mm.plugin.sns.model.UploadPackHelper")
        }
    }

    val methodSetSdkId by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            declaredClass(classUploadPackHelper.clazz)
            usingEqStrings("setSdkId", "com.tencent.mm.plugin.sns.model.UploadPackHelper")
        }
    }

    val methodSetSdkAppName by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            declaredClass(classUploadPackHelper.clazz)
            usingEqStrings("setSdkAppName", "com.tencent.mm.plugin.sns.model.UploadPackHelper")
        }
    }

    val methodSetUploadList by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            declaredClass(classUploadPackHelper.clazz)
            usingEqStrings("setUploadList", "com.tencent.mm.plugin.sns.model.UploadPackHelper")
        }
    }

    val methodAddImageMediaObjByPath by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            declaredClass(classUploadPackHelper.clazz)
            returnType(bool)
            paramCount(2)
            paramTypes(String::class.java, String::class.java)
            usingStrings("addImageMediaObjByPath", "com.tencent.mm.plugin.sns.model.UploadPackHelper")
        }
    }

    val methodAddSightObjectByPath by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            declaredClass(classUploadPackHelper.clazz)
            returnType(bool)
            paramCount(4)
            paramTypes(String::class.java, String::class.java, String::class.java, String::class.java)
            usingStrings("addSightObjectByPath", "com.tencent.mm.plugin.sns.model.UploadPackHelper")
        }
    }

    val classSnsUtil by dexClass {
        matcher {
            usingEqStrings("MicroMsg.SnsUtil", "getSnsBigName")
        }
    }

    val methodGetSnsBigName by dexMethod {
        matcher {
            declaredClass(classSnsUtil.clazz)
            usingEqStrings("getSnsBigName")
        }
    }

    val methodGetSnsThumbName by dexMethod {
        matcher {
            declaredClass(classSnsUtil.clazz)
            usingEqStrings("getSnsThumbName")
        }
    }

    val classSnsPathHelper by dexClass {
        matcher {
            usingEqStrings("getImageFilePath", "com.tencent.mm.plugin.sns.model.SnsPathHelper")
        }
    }

    val methodGetMediaFilePath by dexMethod {
        matcher {
            declaredClass(classSnsPathHelper.clazz)
            usingEqStrings("getMediaFilePath")
        }
    }

    val classSnsVideoLogic by dexClass {
        matcher {
            usingEqStrings("MicroMsg.SnsVideoLogic", "getSnsVideoPath", "com.tencent.mm.plugin.sns.model.SnsVideoLogic")
        }
    }

    val methodGetSnsVideoPath by dexMethod {
        matcher {
            declaredClass(classSnsVideoLogic.clazz)
            usingEqStrings("getSnsVideoPath")
        }
    }

    val methodGetSnsVideoThumbImagePath by dexMethod {
        matcher {
            declaredClass(classSnsVideoLogic.clazz)
            usingEqStrings("getSnsVideoThumbImagePath")
        }
    }

    val classSnsCore by dexClass {
        matcher {
            usingEqStrings("com.tencent.mm.plugin.sns.model.SnsCore", "getSnsInfoStorage")
        }
    }

    val methodGetAccSnsPath by dexMethod {
        matcher {
            declaredClass(classSnsCore.clazz)
            modifiers = Modifier.STATIC
            paramCount(0)
            returnType(String::class.java)
            usingStrings("getAccSnsPath", "com.tencent.mm.plugin.sns.model.SnsCore")
        }
    }

    val classVfs by dexClass {
        searchPackages("com.tencent.mm.vfs")
        matcher {
            usingEqStrings("MicroMsg.VFSFileOp", "readFileAsString(\"%s\" failed: %s")
        }
    }

    val vfsReadMethod by lazy {
        classVfs.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            parameters(String::class)
            returnType = InputStream::class
        }
    }

    val vfsCopyMethod by lazy {
        classVfs.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            parameters(String::class, Boolean::class)
            returnType = OutputStream::class
        }
    }

    val vfsExistsMethod by lazy {
        classVfs.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            parameters(String::class)
            returnType = Boolean::class
        }
    }

    fun copyVfsFile(src: String, dest: String): Boolean {
        return try {
            val input = vfsReadMethod.invoke(null, src) as? InputStream ?: return false
            val output = vfsCopyMethod.invoke(null, dest, false) as? OutputStream
            if (output == null) {
                input.close()
                return false
            }
            input.use { inStream ->
                output.use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to copy VFS file from $src to $dest", e)
            false
        }
    }

    fun vfsFileExists(path: String): Boolean {
        return try {
            vfsExistsMethod.invoke(null, path) as Boolean
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to check VFS file exists: $path", e)
            false
        }
    }

    fun sendText(content: String, sdkId: String? = null, sdkAppName: String? = null): Boolean {
        return try {
            val helper = ctorUploadPackHelper.constructor.newInstance(2, null)
            methodSetContentDes.method.invoke(helper, content)
            if (!sdkId.isNullOrEmpty()) {
                methodSetSdkId.method.invoke(helper, sdkId)
            }
            if (!sdkAppName.isNullOrEmpty()) {
                methodSetSdkAppName.method.invoke(helper, sdkAppName)
            }
            methodCommit.method.invoke(helper)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "uploadText failed", e)
            false
        }
    }

    fun sendTextAndPicList(content: String, picPaths: List<String>, sdkId: String? = null, sdkAppName: String? = null): Boolean {
        return try {
            val helper = ctorUploadPackHelper.constructor.newInstance(1, null)
            methodSetContentDes.method.invoke(helper, content)

            val mediaList = ArrayList<Any>()
            for (picPath in picPaths) {
                val mediaObj = classSnsMediaObj.clazz.createInstance(picPath, 2)
                mediaList.add(mediaObj)
            }

            methodSetUploadList.method.invoke(helper, mediaList)

            if (!sdkId.isNullOrEmpty()) {
                methodSetSdkId.method.invoke(helper, sdkId)
            }
            if (!sdkAppName.isNullOrEmpty()) {
                methodSetSdkAppName.method.invoke(helper, sdkAppName)
            }
            methodCommit.method.invoke(helper)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "uploadTextAndPicList failed", e)
            false
        }
    }

    fun like(snsInfo: Any?, sourceScene: Int = 0): ActionResult =
        sendLike(snsInfo, sourceScene, skipIfAlreadyLiked = true)

    fun like(context: WeMomentsContextMenuApi.MomentsContext, sourceScene: Int = 0): ActionResult =
        like(context.snsInfo, sourceScene)

    fun forceLike(snsInfo: Any?, sourceScene: Int = 0): ActionResult =
        sendLike(snsInfo, sourceScene, skipIfAlreadyLiked = false)

    fun forceLike(context: WeMomentsContextMenuApi.MomentsContext, sourceScene: Int = 0): ActionResult =
        forceLike(context.snsInfo, sourceScene)

    fun unlike(snsInfo: Any?): ActionResult {
        val normalized = normalizeSnsInfo(snsInfo)
            ?: return ActionResult(success = false, sent = false, message = "snsInfo is null or unsupported")

        val snsTableId = getSnsTableId(normalized)
            ?: return ActionResult(success = false, sent = false, message = "sns table id is unavailable")

        return runCatching {
            methodCancelLike.method.invoke(null, snsTableId)
            ActionResult(success = true, sent = true, message = "cancel like request sent")
        }.getOrElse { error ->
            WeLogger.e(TAG, "failed to send Moments unlike request", error)
            ActionResult(success = false, sent = false, message = error.message ?: "failed to send cancel like request", error = error)
        }
    }

    fun unlike(context: WeMomentsContextMenuApi.MomentsContext): ActionResult =
        unlike(context.snsInfo)

    fun isLiked(snsInfo: Any?): Boolean {
        val normalized = normalizeSnsInfo(snsInfo) ?: return false
        return readLikeFlag(normalized) != 0
    }

    fun isDeleted(snsInfo: Any?): Boolean {
        val normalized = normalizeSnsInfo(snsInfo) ?: return false
        return normalized.reflekt().firstMethodOrNull { name = "isDeadSource"; parameters(); superclass() }?.invoke() as? Boolean == true
    }

    fun getContent(snsInfo: Any?): ByteArray? {
        val normalized = normalizeSnsInfo(snsInfo) ?: return null
        return normalized.reflekt().firstFieldOrNull { name = "field_content"; superclass() }?.get() as? ByteArray
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun getContentText(snsInfo: Any?): String? {
        val bytes = getContent(snsInfo) ?: return null
        return try {
            val proto = ProtoBuf.decodeFromByteArray<TimelineObjectProto>(bytes)
            proto.contentDesc
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to decode TimeLineObjectProto", e)
            null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun getTimelineProto(snsInfo: Any?): TimelineObjectProto? {
        val bytes = getContent(snsInfo) ?: return null
        return try {
            ProtoBuf.decodeFromByteArray<TimelineObjectProto>(bytes)
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to decode TimeLineObjectProto", e)
            null
        }
    }

    val classMediaObj: Class<*> by lazy {
        classUploadPackHelper.clazz.declaredMethods.first {
            it.parameterTypes.size == 3 &&
            it.parameterTypes[0] == String::class.java &&
            it.parameterTypes[1] == Int::class.javaPrimitiveType &&
            it.parameterTypes[2] == String::class.java &&
            it.returnType != Void.TYPE
        }.returnType
    }

    fun isLiked(context: WeMomentsContextMenuApi.MomentsContext): Boolean =
        isLiked(context.snsInfo)

    fun getSnsTableId(snsInfo: Any?): String? {
        val normalized = normalizeSnsInfo(snsInfo) ?: return null
        return normalized.reflekt().firstMethodOrNull { name = "getSnsId"; parameters(); superclass() }?.invoke() as? String
            ?: buildSnsTableId(normalized)
    }

    fun getSnsTableId(context: WeMomentsContextMenuApi.MomentsContext): String? =
        getSnsTableId(context.snsInfo)

    fun getOwnerWxId(snsInfo: Any?): String? {
        val normalized = normalizeSnsInfo(snsInfo) ?: return null
        return normalized.reflekt().firstMethodOrNull { name = "getUserName"; parameters(); superclass() }?.invoke() as? String
            ?: normalized.reflekt().firstFieldOrNull { name = "field_userName"; superclass() }?.get() as? String
    }

    fun getOwnerWxId(context: WeMomentsContextMenuApi.MomentsContext): String? =
        getOwnerWxId(context.snsInfo)

    fun getSnsInfoBySnsId(snsId: Long): Any? {
        if (snsId == 0L) return null
        return runCatching {
            val storage = methodGetSnsInfoStorage.method.invoke(null)
            methodGetSnsInfoBySnsId.method.invoke(storage, snsId)
        }.getOrElse { error ->
            WeLogger.e(TAG, "failed to get Moments snsInfo by snsId=$snsId", error)
            null
        }
    }

    private const val MOMENTS_CLASS = "${PackageNames.WECHAT}.plugin.sns.ui.SnsUploadUI"

    fun sendTextInUi(context: Context, text: String) {
        context.startActivity(Intent {
            setClassName(PackageNames.WECHAT, MOMENTS_CLASS)
            putExtra("Ksnsupload_type", 9)
            putExtra("Kdescription", text)
        })
    }

    fun sendImagesInUi(context: Context, mediaMd5s: List<String>) {
        context.startActivity(Intent {
            setClassName(PackageNames.WECHAT, MOMENTS_CLASS)
            putStringArrayListExtra("sns_kemdia_path_list", mediaMd5s.toCollection(ArrayList()))
            putExtra("Kdescription", "")
        })
    }

    fun sendVideoInUi(context: Context, videoPath: String, text: String? = null) {
        context.startActivity(Intent {
            setClassName(PackageNames.WECHAT, MOMENTS_CLASS)
            putExtra("Ksnsupload_type", 14)
            putExtra("KSightPath", videoPath)
            putExtra("KSightThumbPath", videoPath)
            putExtra("Kdescription", text ?: "")
        })
    }

    private fun sendLike(
        snsInfo: Any?,
        sourceScene: Int,
        skipIfAlreadyLiked: Boolean
    ): ActionResult {
        val normalized = normalizeSnsInfo(snsInfo)
            ?: return ActionResult(success = false, sent = false, message = "snsInfo is null or unsupported")

        if (!isValidSnsInfo(normalized)) {
            return ActionResult(success = false, sent = false, message = "snsInfo is invalid")
        }
        if (skipIfAlreadyLiked && readLikeFlag(normalized) != 0) {
            return ActionResult(success = true, sent = false, message = "already liked")
        }

        return runCatching {
            sendLikeMethod().invoke(null, normalized, LIKE_COMMENT_TYPE, null, sourceScene)
            ActionResult(success = true, sent = true, message = "like request sent")
        }.getOrElse { error ->
            WeLogger.e(TAG, "failed to send Moments like request", error)
            ActionResult(success = false, sent = false, message = error.message ?: "failed to send like request", error = error)
        }
    }

    private fun sendLikeMethod(): Method =
        runCatching { methodSendLike.method }.getOrElse { sendLikeMethod }

    private fun normalizeSnsInfo(snsInfo: Any?): Any? {
        if (snsInfo == null) return null

        return runCatching {
            if (snsInfoClass.isInstance(snsInfo)) {
                WeLogger.d(TAG, "snsInfo is SnsInfo, returning directly")
                return snsInfo
            }

            WeLogger.d(TAG, "unwrapping snsInfo...")
            snsInfo.javaClass.reflekt()
                .firstMethodOrNull {
                    parameterCount = 0
                    returnType { snsInfoClass.isAssignableFrom(it) }
                    superclass()
                }
                ?.invoke(snsInfo)
        }.getOrElse { error ->
            WeLogger.e(TAG, "failed to normalize snsInfo", error)
            null
        }
    }

    private fun isValidSnsInfo(snsInfo: Any): Boolean {
        (snsInfo.reflekt().firstMethodOrNull { name = "isValid"; parameters(); superclass() }?.invoke() as? Boolean)?.let { return it }
        snsInfo.reflekt().firstFieldOrNull { name = "field_snsId"; superclass() }?.get()?.let { it as? Number }?.toLong()?.let { return it != 0L }
        return true
    }

    private fun readLikeFlag(snsInfo: Any): Int {
        return (snsInfo.reflekt().firstMethodOrNull { name = "getLikeFlag"; parameters(); superclass() }?.invoke() as? Number)?.toInt()
            ?: snsInfo.reflekt().firstFieldOrNull { name = "field_likeFlag"; superclass() }?.get()?.let { it as? Number }?.toInt()
            ?: 0
    }

    private fun buildSnsTableId(snsInfo: Any): String? {
        val snsId = snsInfo.reflekt().firstFieldOrNull { name = "field_snsId"; superclass() }?.get()?.let { it as? Number }?.toLong() ?: return null
        if (snsId == 0L) return null

        val isAd = snsInfo.reflekt().firstMethodOrNull { name = "isAd"; parameters(); superclass() }?.invoke() as? Boolean == true
        snsInfoClass.reflekt().firstMethodOrNull {
            name = "getSnsId"
            parameters(bool, long)
        }?.let { method ->
            return runCatching { method.invoke(null, isAd, snsId) as? String }.getOrNull()
        }
        return if (isAd) "ad_table_$snsId" else "sns_table_$snsId"
    }
}
