@file:Suppress("unused")

package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import dev.ujhhgtg.reflekt.Reflect
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.TimelineObjectProto
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.Intent
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.reflection.long
import dev.ujhhgtg.wekit.utils.reflection.void
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.time.Duration.Companion.milliseconds

@Feature(
    name = "朋友圈服务",
    categories = ["API"],
    description = "提供操作朋友圈的能力"
)
object WeMomentsApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeMomentsApi"
    private const val SNS_VIDEO_SCENE_TIMELINE_OFFLINE = 31
    private const val SNS_VIDEO_SCENE_FINISH_REMAINING = 36
    private const val FALLBACK_VIDEO_CREATE_TIME = 1

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

    // setUploadList 的列表元素, 实况图片必须经此路径构造。
    val classSnsUploadElement by dexClass {
        matcher {
            usingEqStrings("MicroMsg.SnsUploadElment", "path:%s model:%s")

            addMethod {
                name = "<init>"
                paramTypes(BString, int)
            }
        }
    }

    // 媒体上传元素构造器: (path, type)。
    val ctorSnsUploadElement by dexConstructor {
        matcher {
            declaredClass(classSnsUploadElement.clazz)
            paramCount(2)
            paramTypes("java.lang.String", "int")
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

    val methodGenCdnMediaId by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classSnsVideoLogic.clazz)
            modifiers = Modifier.STATIC
            paramCount(2)
            paramTypes("int", null)
            returnType(String::class.java)
            usingEqStrings("genCdnMediaId")
        }
    }

    val methodGetSnsVideoFullPath by dexMethod {
        matcher {
            declaredClass(classSnsVideoLogic.clazz)
            modifiers = Modifier.STATIC
            paramCount(2)
            paramTypes(String::class.java, null)
            returnType(String::class.java)
            usingEqStrings(
                "getSnsVideoFullPath",
                "getSnsVideoFullPath have flag %s, %s >>"
            )
        }
    }

    val methodIsSnsVideoDownloadFinished by dexMethod {
        matcher {
            declaredClass(classSnsVideoLogic.clazz)
            modifiers = Modifier.STATIC
            paramCount(2)
            paramTypes(String::class.java, null)
            returnType(String::class.java)
            usingEqStrings(
                "isDownloadFinish",
                "it don't download video[%s] finish. file[%b], return null."
            )
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

    val methodGetSnsVideoService by dexMethod {
        matcher {
            declaredClass(classSnsCore.clazz)
            modifiers = Modifier.STATIC
            paramCount(0)
            usingStrings("getSnsVideoService", "com.tencent.mm.plugin.sns.model.SnsCore")
        }
    }

    val methodDownloadVideo by dexMethod {
        matcher {
            declaredClass(methodGetSnsVideoService.method.returnType)
            paramCount(7)
            paramTypes(null, "int", "java.lang.String", "boolean", "boolean", "int", "java.lang.String")
            returnType(bool)
            usingEqStrings("addSnsVideoTask", "com.tencent.mm.plugin.sns.model.SnsVideoService")
        }
    }

    // 朋友圈图片下载服务 (DownloadManager), 相当于视频的 SnsVideoService。
    // 点击查看大图时微信正是走这里从 CDN 拉取大图到本地 image2/。
    val classSnsDownloadManager by dexClass {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            usingEqStrings("MicroMsg.DownloadManager", "addBatchDownloadSns do not need download.")
        }
    }

    // SnsCore.getSnsDownManager(): 取 DownloadManager 单例, 与 getSnsVideoService 同构。
    val methodGetSnsDownManager by dexMethod {
        matcher {
            declaredClass(classSnsCore.clazz)
            modifiers = Modifier.STATIC
            paramCount(0)
            usingStrings("getSnsDownManager", "com.tencent.mm.plugin.sns.model.SnsCore")
        }
    }

    // DownloadManager.addDownLoadSns(mediaObj, downType, decodeElement, sourceScene) 的 4 参便捷重载。
    val methodAddDownLoadSns by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            declaredClass(classSnsDownloadManager.clazz)
            paramCount(4)
            returnType(bool)
            usingEqStrings("addDownLoadSns", "com.tencent.mm.plugin.sns.model.DownloadManager")
        }
    }

    // 图片来源场景枚举 (com.tencent.mm.storage.t6): 朋友圈图片用 "timeline" 实例。
    val classSnsSourceScene by dexClass {
        matcher {
            usingEqStrings("timeline", "album_friend", "album_self", "album_stranger", "comment_detail")
        }
    }

    private val methodExportVideoToAlbum by dexMethod {
        matcher {
            modifiers = Modifier.STATIC
            paramCount(4)
            paramTypes(Context::class.java, String::class.java, String::class.java, null)
            returnType(String::class.java)
            usingEqStrings("[+] Called exportVideo, src: %s", "exportVideoImpl fail")
        }
    }

    private val methodExportImageToAlbum by dexMethod {
        matcher {
            modifiers = Modifier.STATIC
            paramCount(3)
            paramTypes(Context::class.java, String::class.java, String::class.java)
            returnType(String::class.java)
            usingEqStrings("[+] Called exportImage, src: %s", "exportImageImpl")
        }
    }

    private val classGalleryEntryUi by dexClass {
        matcher {
            usingEqStrings("MicroMsg.GalleryEntryUI", "query souce: ", "doRedirect %s")
        }
    }

    private val classSnsUploadUi by dexClass {
        matcher {
            usingEqStrings("MicroMsg.SnsUploadUI", "customizeInputView", "initView")
        }
    }

    private val classSnsUiAction by dexClass {
        matcher {
            usingEqStrings(
                "MicroMsg.SnsActivity",
                "onAcvityResult requestCode:",
                "KTouchCameraTime"
            )
        }
    }

    private val methodSnsUiActionOnActivityResult by dexMethod {
        matcher {
            declaredClass(classSnsUiAction.clazz)
            paramCount(3)
            paramTypes(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, android.content.Intent::class.java)
            returnType(Void.TYPE)
            usingEqStrings("onActivityResult", "com.tencent.mm.plugin.sns.ui.SnsUIAction")
        }
    }

    private val methodSnsUploadOnCreate by dexMethod {
        matcher {
            declaredClass(classSnsUploadUi.clazz)
            paramCount(1)
            paramTypes(Bundle::class.java)
            returnType(Void.TYPE)
            usingEqStrings("onCreate", "com.tencent.mm.plugin.sns.ui.SnsUploadUI")
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

    // "timeline" 场景静态实例 (com.tencent.mm.storage.t6 的静态字段之一)。
    private val timelineSourceScene: Any by lazy {
        val sceneClass = classSnsSourceScene.clazz
        val nameField = sceneClass.reflekt().firstField {
            type = String::class
            modifiers { !it.contains(Modifiers.STATIC) }
        }
        sceneClass.reflekt().fields {
            type = sceneClass
            modifiers(Modifiers.STATIC)
        }.firstNotNullOf { field ->
            val instance = field.getStatic() ?: return@firstNotNullOf null
            if (nameField.self.get(instance) == "timeline") instance else null
        }
    }

    private val pendingAlbumRepostText = AtomicReference<String?>(null)

    private data class GalleryEditorMedia(
        val coverPaths: ArrayList<String>,
        val mediaItems: ArrayList<Parcelable>
    )

    private data class VideoMetadata(
        val durationMs: Int,
        val width: Int,
        val height: Int,
        val size: Int
    )

    private val classGalleryLivePhotoMediaItem by dexClass {
        matcher {
            className = "com.tencent.mm.plugin.gallery.model.GalleryItem\$LivePhotoMediaItem"
        }
    }

    private val galleryLivePhotoMediaItemClass: Class<*> by lazy {
        classGalleryLivePhotoMediaItem.clazz
    }

    private val galleryImageMediaItemClass: Class<*> by lazy {
        galleryLivePhotoMediaItemClass.superclass
            ?: error("Gallery live-photo item superclass missing")
    }

    private val galleryImageMediaItemCtor by lazy {
        galleryImageMediaItemClass.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    private val galleryLivePhotoMediaItemCtor by lazy {
        galleryLivePhotoMediaItemClass.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    private data class LivePhotoFieldAccessors(
        val duration: Field?,
        val width: Field?,
        val height: Field?,
        val size: Field?,
        val parsed: Field?,
        val valid: Field?
    )

    private val livePhotoFieldAccessors: LivePhotoFieldAccessors by lazy {
        resolveLivePhotoFieldAccessors()
    }

    private val albumRepostDescriptionInjector = WeStartActivityApi.IStartActivityListener { _, intent ->
        injectPendingAlbumRepostText(intent, requireSnsUploadTarget = true)
    }

    override fun onEnable() {
        WeStartActivityApi.addListener(albumRepostDescriptionInjector)
        methodSnsUploadOnCreate.hookBefore {
            val intent = (thisObject as? Activity)?.intent ?: return@hookBefore
            injectPendingAlbumRepostText(intent, requireSnsUploadTarget = false)
        }
    }

    private fun injectPendingAlbumRepostText(intent: android.content.Intent, requireSnsUploadTarget: Boolean) {
        val text = pendingAlbumRepostText.get() ?: return
        if (requireSnsUploadTarget && intent.component?.className != classSnsUploadUi.clazz.name) return

        if (!intent.hasExtra("Kdescription") || intent.getStringExtra("Kdescription").isNullOrEmpty()) {
            intent.putExtra("Kdescription", text)
            WeLogger.i(TAG, "injected Moments repost description into ${intent.component?.className}")
        }
        pendingAlbumRepostText.compareAndSet(text, null)
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

    private fun copyRegularFile(src: String, dest: String): Boolean {
        return runCatching {
            java.io.File(src).inputStream().use { input ->
                java.io.File(dest).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrElse {
            WeLogger.e(TAG, "failed to copy file from $src to $dest", it)
            false
        }
    }

    fun copyExistingFile(src: String, dest: String): Boolean {
        return if (vfsFileExists(src)) {
            copyVfsFile(src, dest)
        } else if (java.io.File(src).isFile) {
            copyRegularFile(src, dest)
        } else {
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

    fun postText(content: String, sdkId: String? = null, sdkAppName: String? = null): Boolean {
        return try {
            val helper = ctorUploadPackHelper.constructor.newInstance(2, null)
            methodSetContentDes.method.invoke(helper, content)
            if (!sdkId.isNullOrEmpty()) {
                methodSetSdkId.method.invoke(helper, sdkId)
            }
            if (!sdkAppName.isNullOrEmpty()) {
                methodSetSdkAppName.method.invoke(helper, sdkAppName)
            }
            val localId = methodCommit.method.invoke(helper) as Int
            localId > 0
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendText failed", e)
            false
        }
    }

    fun postTextAndImages(text: String, imagePaths: List<String>, sdkId: String? = null, sdkAppName: String? = null): Boolean {
        return try {
            WeLogger.i(TAG, "IMG-DBG postTextAndImages: ${imagePaths.joinToString { p -> "$p(size=${runCatching { java.io.File(p).length() }.getOrDefault(-1L)}, isFile=${java.io.File(p).isFile})" }}")
            val helper = ctorUploadPackHelper.constructor.newInstance(1, null)
            methodSetContentDes.method.invoke(helper, text)
            imagePaths.forEach { path ->
                val added = methodAddImageMediaObjByPath.method.invoke(helper, path, "")
                WeLogger.i(TAG, "IMG-DBG addImageMediaObjByPath($path) -> $added")
            }
            if (!sdkId.isNullOrEmpty()) {
                methodSetSdkId.method.invoke(helper, sdkId)
            }
            if (!sdkAppName.isNullOrEmpty()) {
                methodSetSdkAppName.method.invoke(helper, sdkAppName)
            }
            val localId = methodCommit.method.invoke(helper) as Int
            WeLogger.i(TAG, "IMG-DBG postTextAndImages commit localId=$localId")
            localId > 0
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendTextAndImages failed", e)
            false
        }
    }

    fun postTextAndImages2(text: String, images: List<String>, sdkId: String? = null, sdkAppName: String? = null): Boolean {
        return try {
            val helper = ctorUploadPackHelper.constructor.newInstance(1, null)
            methodSetContentDes.method.invoke(helper, text)

            val mediaList = ArrayList<Any>()
            images.forEach { image ->
                val mediaObj = classSnsMediaObj.clazz.createInstance(image, 2)
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
            WeLogger.e(TAG, "sendTextAndImages2 failed", e)
            false
        }
    }

    fun postTextAndVideo(context: Context, text: String, videoPath: String, thumbPath: String, sdkId: String? = null, sdkAppName: String? = null): Boolean {
        return try {
            val cacheDir = context.externalCacheDir ?: context.cacheDir
            val tempVideo = cacheDir.asPath / "wekit_moments_temp_${System.currentTimeMillis()}.mp4"
            val tempVideoPath = tempVideo.absolutePathString()

            val tempThumb = cacheDir.asPath / "wekit_moments_temp_${System.currentTimeMillis()}.jpg"
            val tempThumbPath = tempThumb.absolutePathString()
            if (!copyExistingFile(thumbPath, tempThumbPath)) return false

            if (copyExistingFile(videoPath, tempVideoPath)) {
                val helper = ctorUploadPackHelper.constructor.newInstance(15, null)
                methodSetContentDes.method.invoke(helper, text)
                methodAddSightObjectByPath.method.invoke(helper, tempVideoPath, tempThumbPath, "", "")
                if (!sdkId.isNullOrEmpty()) {
                    methodSetSdkId.method.invoke(helper, sdkId)
                }
                if (!sdkAppName.isNullOrEmpty()) {
                    methodSetSdkAppName.method.invoke(helper, sdkAppName)
                }
                val localId = methodCommit.method.invoke(helper) as Int
                localId > 0
            } else {
                false
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendTextAndVideo failed", e)
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

//    val classMediaObj: Class<*> by lazy {
//        classUploadPackHelper.clazz.declaredMethods.first {
//            it.parameterTypes.size == 3 &&
//            it.parameterTypes[0] == String::class.java &&
//            it.parameterTypes[1] == Int::class.javaPrimitiveType &&
//            it.parameterTypes[2] == String::class.java &&
//            it.returnType != Void.TYPE
//        }.returnType
//    }

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

    private const val TIMELINE_OBJECT_CLASS = "com.tencent.mm.protocal.protobuf.TimeLineObject"

    private val timelineObjectClass: Class<*> by lazy { TIMELINE_OBJECT_CLASS.toClass() }

    /**
     * 从 [snsInfo] 反射解析出原生 TimeLineObject（长按菜单场景外，例如后台扫描时使用）。
     */
    fun getNativeTimeline(snsInfo: Any?): Any? {
        val normalized = normalizeSnsInfo(snsInfo) ?: return null
        return runCatching {
            normalized.reflekt().firstMethodOrNull {
                parameters()
                superclass()
                returnType { timelineObjectClass.isAssignableFrom(it) }
            }?.invoke()
        }.getOrElse { error ->
            WeLogger.e(TAG, "failed to resolve native TimeLineObject", error)
            null
        }
    }

    /**
     * 从原生 TimeLineObject 中取出原生 MediaObj 列表（图片/视频路径解析需要）。
     */
    fun getNativeMediaList(nativeTimeline: Any): LinkedList<*>? {
        return runCatching {
            val nativeContentObj = nativeTimeline.reflekt().getField("ContentObj")!!
            nativeContentObj.reflekt().firstField {
                // single-char field name in 'a'..'r'
                name { it.length == 1 && it[0] >= 'a' && it[0] <= 'r' }
                type = LinkedList::class
            }.get() as? LinkedList<*>
        }.getOrElse { error ->
            WeLogger.e(TAG, "failed to resolve native media list", error)
            null
        }
    }

    data class MomentContent(
        val contentText: String,
        val type: Int,
        val mediaList: List<TimelineObjectProto.MediaObjProto>,
        val nativeMediaList: LinkedList<*>,
        val snsTableId: String?,
        val createTime: Int
    ) {
        /** 该朋友圈是否包含至少一张实况图片。 */
        val hasLivePhoto: Boolean
            get() = mediaList.any { it.livePhotoVideo != null }
    }

    /**
     * 综合 proto 与原生对象, 提取转发所需的朋友圈内容。
     * [nativeTimeline] 可显式传入（长按菜单已有），否则从 [snsInfo] 反射解析。
     */
    fun getMomentContent(snsInfo: Any?, nativeTimeline: Any? = null): MomentContent? {
        val proto = getTimelineProto(snsInfo) ?: return null
        val contentObj = proto.contentObj ?: return null
        val native = nativeTimeline ?: getNativeTimeline(snsInfo) ?: return null
        val nativeMediaList = getNativeMediaList(native) ?: return null
        return MomentContent(
            contentText = proto.contentDesc ?: "",
            type = contentObj.type,
            mediaList = contentObj.mediaList,
            nativeMediaList = nativeMediaList,
            snsTableId = getSnsTableId(snsInfo),
            createTime = proto.createTime
        )
    }

    /**
     * 解析后的单个媒体项。[videoPath] 非空表示这是一张实况图片, 且视频已成功定位;
     * 若为实况图片但视频缺失（未下载）, [videoPath] 为 null（退化为静态图）。
     */
    data class ResolvedMedia(
        val imagePath: String,
        val videoPath: String?
    )

    data class ResolvedMoment(
        val items: List<ResolvedMedia>,
        /** 存在实况图片但其视频未能定位（未下载）, 导致退化为静态图。 */
        val degradedLivePhotos: Boolean
    )

    data class ResolvedVideo(
        val videoPath: String,
        val thumbPath: String
    )

    /**
     * 从原生媒体对象反射取出实况视频子对象。
     * 子对象按「字段类型 == 父媒体对象自身类」定位, 避免依赖混淆字段名。
     */
    fun getNativeLivePhotoVideo(nativeMedia: Any): Any? {
        return runCatching {
            val cls = nativeMedia.javaClass
            var current: Class<*>? = cls
            while (current != null) {
                val field = current.declaredFields.firstOrNull { it.type == cls }
                if (field != null) {
                    field.isAccessible = true
                    return field.get(nativeMedia)
                }
                current = current.superclass
            }
            null
        }.getOrElse {
            WeLogger.e(TAG, "failed to resolve native live photo video sub-object", it)
            null
        }
    }

    /**
     * 定位实况图片视频组件的本地缓存路径。
     * 视频未必已下载（与普通朋友圈视频一致, 需先播放一次）, 缺失返回 null。
     */
    fun getLivePhotoVideoPath(nativeVideoObj: Any): String? {
        return runCatching {
            val path = methodGetSnsVideoPath.method.invoke(null, nativeVideoObj) as? String
            if (path.isNullOrEmpty() || !(vfsFileExists(path) || java.io.File(path).isFile)) null else path
        }.getOrElse {
            WeLogger.e(TAG, "failed to get live photo video path", it)
            null
        }
    }

    private fun getNativeMediaId(nativeMedia: Any): String? =
        nativeMedia.reflekt().fields { type = BString }
            .firstNotNullOfOrNull { field ->
                runCatching { field.get() as? String }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() && !it.startsWith("http") }
            }

    private fun getNativeCdnMediaId(nativeMedia: Any, createTime: Int): String? {
        return runCatching {
            methodGenCdnMediaId.method.invoke(null, createTime, nativeMedia) as? String
        }.getOrElse {
            WeLogger.e(TAG, "failed to generate Moments video cdn media id", it)
            null
        }?.takeIf { it.isNotBlank() }
    }

    private fun triggerVideoDownload(
        nativeMedia: Any,
        snsTableId: String?,
        createTime: Int? = null,
        scene: Int = SNS_VIDEO_SCENE_TIMELINE_OFFLINE
    ): Boolean {
        return runCatching {
            val manager = methodGetSnsVideoService.method.invoke(null)
            val fallbackMediaId = getNativeMediaId(nativeMedia)?.takeIf { it.isNotBlank() }
            val effectiveCreateTime = createTime?.takeIf { it > 0 }
            val mediaId = effectiveCreateTime?.let { getNativeCdnMediaId(nativeMedia, it) }
                ?: fallbackMediaId
                ?: return@runCatching false
            val localId = snsTableId?.takeIf { it.isNotBlank() } ?: fallbackMediaId ?: mediaId
            val videoCreateTime = effectiveCreateTime ?: FALLBACK_VIDEO_CREATE_TIME
            val result = methodDownloadVideo.method.invoke(manager, nativeMedia, videoCreateTime, localId, false, true, scene, mediaId) as? Boolean == true
            WeLogger.i(TAG, "trigger Moments video download: sns=$localId, media=$mediaId, createTime=$videoCreateTime, scene=$scene, result=$result")
            result
        }.getOrElse {
            WeLogger.e(TAG, "failed to trigger Moments video download", it)
            false
        }
    }

    private fun videoFileSize(path: String): Long {
        val fsSize = regularFileSize(path)
        if (fsSize >= 0L) return fsSize
        if (!vfsFileExists(path)) return -1L
        return vfsFileSize(path)
    }

    private fun isUsableLivePhotoVideoPath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        if (!(vfsFileExists(path) || java.io.File(path).isFile)) return false
        val size = videoFileSize(path)
        if (size <= 0L) return false
        val metadata = probeVideoMetadata(path)
        val usable = metadata.durationMs > 0 && metadata.width > 0 && metadata.height > 0
        if (!usable) {
            WeLogger.w(TAG, "live photo video not ready: path=$path, size=$size, duration=${metadata.durationMs}, dimensions=${metadata.width}x${metadata.height}")
        }
        return usable
    }

    private suspend fun ensureLivePhotoVideosCached(
        content: MomentContent,
        timeoutMs: Long = 90_000,
        intervalMs: Long = 500
    ): Boolean {
        if (!content.hasLivePhoto) return true

        for (index in content.mediaList.indices) {
            if (content.mediaList[index].livePhotoVideo == null) continue
            val nativeMedia = content.nativeMediaList.getOrNull(index) ?: return false
            val nativeVideo = getNativeLivePhotoVideo(nativeMedia)
            if (nativeVideo == null) {
                WeLogger.w(TAG, "live photo video sub-object missing: index=$index")
                return false
            }
            ensureLivePhotoVideoCached(content, index, nativeVideo, timeoutMs, intervalMs)
                ?: return false
        }
        return true
    }

    private suspend fun ensureLivePhotoVideoCached(
        content: MomentContent,
        index: Int,
        nativeVideo: Any,
        timeoutMs: Long,
        intervalMs: Long
    ): String? {
        getLivePhotoVideoPath(nativeVideo)?.takeIf { isUsableLivePhotoVideoPath(it) }?.let { path ->
            WeLogger.i(TAG, "live photo video already cached: index=$index, path=$path, size=${videoFileSize(path)}")
            return path
        }

        triggerVideoDownload(nativeVideo, content.snsTableId, content.createTime.takeIf { it > 0 }, SNS_VIDEO_SCENE_TIMELINE_OFFLINE)
        val start = SystemClock.elapsedRealtime()
        var retriggeredFinish = false
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            getLivePhotoVideoPath(nativeVideo)?.takeIf { isUsableLivePhotoVideoPath(it) }?.let { path ->
                WeLogger.i(TAG, "live photo video cached after ${SystemClock.elapsedRealtime() - start}ms: index=$index, path=$path, size=${videoFileSize(path)}")
                return path
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            if (!retriggeredFinish && elapsed >= 15_000) {
                // Retry with the scene used by FlexibleVideoView finishRemaining; this helps complete partial live-photo downloads.
                triggerVideoDownload(nativeVideo, content.snsTableId, content.createTime.takeIf { it > 0 }, SNS_VIDEO_SCENE_FINISH_REMAINING)
                retriggeredFinish = true
            }
            delay(intervalMs.milliseconds)
        }

        return getLivePhotoVideoPath(nativeVideo)?.takeIf { isUsableLivePhotoVideoPath(it) }.also { path ->
            if (path == null) {
                WeLogger.w(TAG, "live photo video download timed out: index=$index, timeout=${timeoutMs}ms")
            }
        }
    }

    private suspend fun waitForPath(
        timeoutMs: Long = 60_000,
        intervalMs: Long = 500,
        resolve: () -> String?
    ): String? {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            resolve()?.takeIf { vfsFileExists(it) || java.io.File(it).isFile }?.let { return it }
            delay(intervalMs.milliseconds)
        }
        return resolve()?.takeIf { vfsFileExists(it) || java.io.File(it).isFile }
    }

    fun isMomentUploadActivity(activity: Activity): Boolean =
        activity.javaClass.name == classSnsUploadUi.clazz.name

    suspend fun ensureVideoPaths(context: Context, content: MomentContent): ResolvedVideo? =
        withContext(Dispatchers.Main) {
            val nativeMedia = content.nativeMediaList.firstOrNull() ?: return@withContext null
            fetchFullVideoPath(content.snsTableId, content.nativeMediaList)
                ?: fetchFinishedVideoPath(content.snsTableId, content.nativeMediaList)
                ?: fetchUsableCachedVideoPath(context, content.nativeMediaList)
                ?: run {
                    triggerVideoDownload(nativeMedia, content.snsTableId, content.createTime)
                    waitForVideoPath(context, content.snsTableId, content.nativeMediaList)
                }
        }?.let { videoPath ->
            val thumbPath = fetchVideoThumbPath(content.nativeMediaList)
                ?.takeIf { vfsFileExists(it) || java.io.File(it).isFile }
                ?: generateVideoThumb(context, videoPath)
                ?: return null
            ResolvedVideo(videoPath, thumbPath)
        }

    fun generateVideoThumbForUpload(context: Context, videoPath: String): String? =
        generateVideoThumb(context, videoPath)

    private fun generateVideoThumb(context: Context, videoPath: String): String? {
        return runCatching {
            val cacheDir = context.externalCacheDir ?: context.cacheDir
            val localVideo = java.io.File(cacheDir, "wekit_moments_thumb_src_${System.currentTimeMillis()}.mp4")
            val localVideoPath = localVideo.absolutePath
            val sourcePath = if (java.io.File(videoPath).isFile) {
                videoPath
            } else {
                if (!copyExistingFile(videoPath, localVideoPath)) return null
                localVideoPath
            }

            val thumbFile = java.io.File(cacheDir, "wekit_moments_thumb_${System.currentTimeMillis()}.jpg")
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(sourcePath)
                val bitmap = retriever.frameAtTime ?: return null
                FileOutputStream(thumbFile).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                }
            } finally {
                retriever.release()
            }
            thumbFile.absolutePath
        }.getOrElse {
            WeLogger.e(TAG, "failed to generate Moments video thumb", it)
            null
        }
    }

    private fun isUsableVideoPath(context: Context, path: String): Boolean {
        if (!(vfsFileExists(path) || java.io.File(path).isFile)) return false

        val localVideo = if (java.io.File(path).isFile) {
            null
        } else {
            val cacheDir = context.externalCacheDir ?: context.cacheDir
            java.io.File(cacheDir, "wekit_moments_probe_${System.currentTimeMillis()}.mp4")
        }
        val sourcePath = localVideo?.let { file ->
            if (!copyExistingFile(path, file.absolutePath)) return false
            file.absolutePath
        } ?: path

        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(sourcePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val usable = duration > 0L && width > 0 && height > 0
                WeLogger.i(TAG, "probe Moments cached video: path=$path, duration=$duration, size=${width}x$height, usable=$usable")
                usable
            } finally {
                retriever.release()
                localVideo?.delete()
            }
        }.getOrElse {
            localVideo?.delete()
            WeLogger.e(TAG, "failed to probe Moments cached video: $path", it)
            false
        }
    }

    /**
     * 将朋友圈媒体逐项解析为可转发的本地路径 (支持静态图与实况图片混合)。
     * 任一封面图缺失则返回 null; 实况视频缺失时该项退化为静态图并置 [ResolvedMoment.degradedLivePhotos]。
     */
    fun resolveMediaItems(content: MomentContent, warnOnThumb: Boolean = false): ResolvedMoment? {
        val mediaList = content.mediaList
        if (mediaList.isEmpty()) return null

        val items = ArrayList<ResolvedMedia>()
        var degraded = false
        for (index in mediaList.indices) {
            val nativeMedia = content.nativeMediaList.getOrNull(index) ?: return null
            val imagePath = getCachedImagePath(mediaList[index], nativeMedia, warnOnThumb) ?: return null

            var videoPath: String? = null
            if (mediaList[index].livePhotoVideo != null) {
                val nativeVideo = getNativeLivePhotoVideo(nativeMedia)
                videoPath = nativeVideo?.let { getLivePhotoVideoPath(it)?.takeIf { path -> isUsableLivePhotoVideoPath(path) } }
                if (videoPath == null) degraded = true
            }
            items.add(ResolvedMedia(imagePath, videoPath))
        }
        return ResolvedMoment(items, degraded)
    }

    /**
     * 后台转发混合媒体相册 (静态图 + 实况图片), 完整保留实况视频。
     * 经 UploadPackHelper.setUploadList 构造: 实况项父元素挂载视频子元素,
     * setUploadList 内部会注册文件并递归处理子元素 (fillLivePhotoData)。
     */
    fun postTextAndMixedMedia(text: String, items: List<ResolvedMedia>): Boolean {
        if (items.isEmpty()) return false
        return try {
            val hasLive = items.any { it.videoPath != null }
            // 有实况用相册类型 54, 否则普通多图类型 1 (与微信 commitInternal 一致)
            val helper = ctorUploadPackHelper.constructor.newInstance(if (hasLive) 54 else 1, null)
            methodSetContentDes.method.invoke(helper, text)

            // 子元素与封面时间戳按字段类型唯一定位; 缩略图是多个 String 之一, 按声明顺序索引定位。
            @Suppress("UNCHECKED_CAST")
            val elemRef = classSnsUploadElement.reflekt() as Reflect<Any>
            val elementClass = classSnsUploadElement.clazz
            val childField = elemRef.firstField { type = elementClass }
            val coverTsField = elemRef.firstField { type = long }
            // 缩略图非关键 (封面图才是展示用的封面), 索引定位失败也不影响转发, 单独兜底
            val thumbField = elemRef.fields {
                type = BString
                modifiers { !it.contains(Modifiers.FINAL) }
            }[3]

            val elements = ArrayList<Any>()
            for (item in items) {
                // 父元素 = 静态图/封面, type 2
                val parent = ctorSnsUploadElement.newInstance(item.imagePath, 2)
                if (item.videoPath != null) {
                    // 子元素 = 实况视频, type 6 (sight); 缩略图沿用封面图
                    val child = ctorSnsUploadElement.newInstance(item.videoPath, 6)
                    runCatching { thumbField.set(child, item.imagePath) }
                    coverTsField.set(child, 0L)
                    childField.set(parent, child)
                }
                elements.add(parent)
            }
            methodSetUploadList.method.invoke(helper, elements)
            val localId = methodCommit.method.invoke(helper) as Int
            localId > 0
        } catch (e: Exception) {
            WeLogger.e(TAG, "postTextAndMixedMedia failed", e)
            false
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

    fun sendImagesInUi(context: Context, mediaMd5s: List<String>, text: String? = null) {
        context.startActivity(Intent {
            setClassName(PackageNames.WECHAT, MOMENTS_CLASS)
            putStringArrayListExtra("sns_kemdia_path_list", mediaMd5s.toCollection(ArrayList()))
            putExtra("Kdescription", text ?: "")
        })
    }

    fun sendVideoInUi(context: Context, videoPath: String, thumbPath: String, text: String? = null) {
        context.startActivity(Intent {
            setClassName(PackageNames.WECHAT, MOMENTS_CLASS)
            putExtra("Ksnsupload_type", 14)
            putExtra("KSightPath", videoPath)
            putExtra("KSightThumbPath", thumbPath)
            putExtra("Kdescription", text ?: "")
        })
    }

    fun saveVideoToAlbum(context: Context, path: String): Boolean {
        return saveVideoToAlbumPath(context, path) != null
    }

    fun saveVideoToAlbumPath(context: Context, path: String): String? {
        return runCatching {
            methodExportVideoToAlbum.method.invoke(null, context, path, null, null) as? String
        }.getOrElse {
            WeLogger.e(TAG, "failed to save Moments video to album: $path", it)
            null
        }
    }

    fun saveImageToAlbumPath(context: Context, path: String): String? {
        return runCatching {
            methodExportImageToAlbum.method.invoke(null, context, path, null) as? String
        }.getOrElse {
            WeLogger.e(TAG, "failed to save Moments image to album: $path", it)
            null
        }
    }

    suspend fun openMomentLivePhotoEditorFromAlbumResult(
        activity: Activity,
        text: String,
        content: MomentContent,
        source: Any? = null
    ): ActionResult {
        return try {
            if (!content.hasLivePhoto) {
                return ActionResult(success = false, sent = false, message = "这条朋友圈不包含实况图片")
            }

            ensureImagePathsCached(content.mediaList, content.nativeMediaList)
                ?: return ActionResult(success = false, sent = false, message = "图片下载失败或超时")
            if (!ensureLivePhotoVideosCached(content)) {
                return ActionResult(success = false, sent = false, message = "实况视频下载失败或超时，请稍后重试")
            }

            val resolved = resolveMediaItems(content)
                ?: return ActionResult(success = false, sent = false, message = "未找到本地缓存的图片")
            if (resolved.degradedLivePhotos) {
                return ActionResult(success = false, sent = false, message = "实况视频未缓存，请先播放一次后再试")
            }

            val editorMedia = prepareGalleryEditorMedia(activity, resolved.items)
                ?: return ActionResult(success = false, sent = false, message = "实况图片保存到相册失败")

            if (openMomentMixedMediaEditorFromAlbumResult(activity, text, editorMedia, source)) {
                ActionResult(success = true, sent = false, message = "已打开实况图片编辑界面")
            } else {
                ActionResult(success = false, sent = false, message = "实况图片自动选择失败")
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "openMomentLivePhotoEditorFromAlbumResult failed", e)
            ActionResult(success = false, sent = false, message = e.message ?: "打开实况图片编辑界面异常", error = e)
        }
    }

    private fun prepareGalleryEditorMedia(context: Context, items: List<ResolvedMedia>): GalleryEditorMedia? {
        if (items.isEmpty()) return null
        val coverPaths = ArrayList<String>()
        val mediaItems = ArrayList<Parcelable>()

        for ((index, item) in items.withIndex()) {
            val albumImagePath = saveImageToAlbumPath(context, item.imagePath)
                ?: materializeImageToTemp(context, item.imagePath, index)
                ?: return null
            coverPaths.add(albumImagePath)

            val mediaItem = if (item.videoPath != null) {
                val albumVideoPath = saveVideoToAlbumPath(context, item.videoPath)
                    ?: return null
                createGalleryLivePhotoMediaItem(index, albumVideoPath, albumImagePath)
            } else {
                createGalleryImageMediaItem(index, albumImagePath)
            } as? Parcelable ?: return null
            mediaItems.add(mediaItem)
        }

        return GalleryEditorMedia(coverPaths, mediaItems)
    }

    private fun createGalleryImageMediaItem(index: Int, imagePath: String): Any {
        val mediaId = System.currentTimeMillis() + index
        return galleryImageMediaItemCtor.newInstance(mediaId, imagePath, imagePath, "image/jpeg")
    }

    private fun createGalleryLivePhotoMediaItem(index: Int, videoPath: String, coverPath: String): Any {
        val mediaId = System.currentTimeMillis() + index
        val item = galleryLivePhotoMediaItemCtor.newInstance(mediaId, videoPath, coverPath, "image/jpeg")
        val metadata = probeVideoMetadata(videoPath)

        // 只按 LivePhotoMediaItem.toString() 暴露的语义标签定位字段，不读取 8069/8074 的混淆字段名。
        val fields = livePhotoFieldAccessors
        fields.duration?.setInt(item, metadata.durationMs)
        fields.width?.setInt(item, metadata.width)
        fields.height?.setInt(item, metadata.height)
        fields.size?.setInt(item, metadata.size)
        fields.valid?.getInt(item)?.takeIf { it != 0 }?.let { readyValue ->
            fields.parsed?.setInt(item, readyValue)
        }
        WeLogger.i(
            TAG,
            "prepared gallery live photo item: cover=$coverPath(${regularFileSize(coverPath)}), " +
                "video=$videoPath(${regularFileSize(videoPath)}), duration=${metadata.durationMs}, " +
                "size=${metadata.width}x${metadata.height}/${metadata.size}"
        )
        return item
    }

    private fun resolveLivePhotoFieldAccessors(): LivePhotoFieldAccessors {
        val probe = galleryLivePhotoMediaItemCtor.newInstance(
            -1L,
            "wekit_live_probe_video",
            "wekit_live_probe_cover",
            "image/jpeg"
        )
        val intRoles = mutableMapOf<String, Field>()
        val intFields = galleryLivePhotoMediaItemClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .onEach { it.isAccessible = true }

        intFields.forEachIndexed { index, field ->
            val original = field.getInt(probe)
            val marker = livePhotoProbeMarker(index)
            field.setInt(probe, marker)
            val text = probe.toString()
            when {
                hasLivePhotoMetric(text, "videoDuration", marker) -> intRoles["duration"] = field
                hasLivePhotoMetric(text, "videoWidth", marker) -> intRoles["width"] = field
                hasLivePhotoMetric(text, "videoHeight", marker) -> intRoles["height"] = field
                hasLivePhotoMetric(text, "videoSize", marker) -> intRoles["size"] = field
                hasLivePhotoMetric(text, "isParsedVideo", marker) -> intRoles["parsed"] = field
                hasLivePhotoMetric(text, "isValid", marker) -> intRoles["valid"] = field
            }
            field.setInt(probe, original)
        }

        return LivePhotoFieldAccessors(
            duration = intRoles["duration"],
            width = intRoles["width"],
            height = intRoles["height"],
            size = intRoles["size"],
            parsed = intRoles["parsed"],
            valid = intRoles["valid"]
        ).also { fields ->
            WeLogger.i(
                TAG,
                "resolved Gallery live-photo fields semantically: " +
                    "duration=${fields.duration != null}, width=${fields.width != null}, " +
                    "height=${fields.height != null}, size=${fields.size != null}, " +
                    "parsed=${fields.parsed != null}, valid=${fields.valid != null}"
            )
        }
    }

    private fun livePhotoProbeMarker(index: Int): Int =
        "WeKitLivePhotoFieldProbe".hashCode() xor index

    private fun hasLivePhotoMetric(text: String, label: String, value: Number): Boolean =
        text.contains("$label=$value")

    private fun probeVideoMetadata(videoPath: String): VideoMetadata {
        var durationMs = 0
        var width = 0
        var height = 0
        var tempVideo: java.io.File? = null
        runCatching {
            val sourcePath = if (java.io.File(videoPath).isFile) {
                videoPath
            } else {
                val cacheDir = HostInfo.application.externalCacheDir ?: HostInfo.application.cacheDir
                val temp = java.io.File(cacheDir, "wekit_moments_probe_live_${System.currentTimeMillis()}.mp4")
                if (!copyExistingFile(videoPath, temp.absolutePath)) return@runCatching
                tempVideo = temp
                temp.absolutePath
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(sourcePath)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }
        }.onFailure {
            WeLogger.e(TAG, "failed to probe live photo video metadata: $videoPath", it)
        }
        tempVideo?.delete()
        return VideoMetadata(durationMs, width, height, videoFileSize(videoPath).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
    }

    fun openMomentVideoEditorFromAlbumResult(activity: Activity, text: String, videoPath: String, source: Any? = null): Boolean {
        val resultIntent = android.content.Intent().apply {
            putStringArrayListExtra("key_select_video_list", arrayListOf(videoPath))
            putExtra("isTakePhoto", false)
            putExtra("key_extra_data", Bundle())
        }
        return dispatchMomentAlbumResult(activity, text, resultIntent, source, "video: $videoPath")
    }

    private fun openMomentMixedMediaEditorFromAlbumResult(
        activity: Activity,
        text: String,
        media: GalleryEditorMedia,
        source: Any? = null
    ): Boolean {
        // In WeChat 8.0.74 SnsUIAction forwards key_select_multi_pic_item only when
        // pc4.d.B() is enabled. User logs show SnsUploadUI received KMulti_Pic_Item_List=null,
        // so live photos were downgraded to plain images. Keep using the official SnsUploadUI,
        // but pass the final gallery extras directly to avoid losing the live-photo list.
        return openMomentMixedMediaEditorDirect(activity, text, media)
    }

    private fun openMomentMixedMediaEditorDirect(
        activity: Activity,
        text: String,
        media: GalleryEditorMedia
    ): Boolean {
        return runCatching {
            val uploadIntent = android.content.Intent().apply {
                setClassName(PackageNames.WECHAT, classSnsUploadUi.clazz.name)
                putExtra("KSnsFrom", 14)
                putExtra("KSnsPostManu", true)
                putExtra("KTouchCameraTime", (System.currentTimeMillis() / 1000).toInt())
                putExtra("KFilterId", 0)
                putExtra("Kdescription", text)
                putExtra("Kis_take_photo", false)
                putStringArrayListExtra("sns_kemdia_path_list", media.coverPaths)
                putStringArrayListExtra("sns_media_latlong_list", arrayListOf<String>())
                putParcelableArrayListExtra("KMulti_Pic_Item_List", media.mediaItems)
            }
            activity.startActivityForResult(uploadIntent, 6)
            WeLogger.i(
                TAG,
                "opened Moments mixed media editor directly: items=${media.mediaItems.size}, " +
                    "covers=${media.coverPaths.size}, activity=${activity.javaClass.name}"
            )
            true
        }.getOrElse {
            WeLogger.e(TAG, "failed to open Moments mixed media editor directly", it)
            false
        }
    }

    private fun dispatchMomentAlbumResult(
        activity: Activity,
        text: String,
        resultIntent: android.content.Intent,
        source: Any?,
        description: String
    ): Boolean {
        pendingAlbumRepostText.set(text)
        return runCatching {
            val existingSnsUiAction = findSnsUiAction(source) ?: findSnsUiAction(activity)
            val snsUiAction = existingSnsUiAction ?: createSnsUiAction(activity)
            if (snsUiAction != null) {
                methodSnsUiActionOnActivityResult.method.invoke(snsUiAction, 14, Activity.RESULT_OK, resultIntent)
                WeLogger.i(
                    TAG,
                    "dispatched Moments album result through ${if (existingSnsUiAction != null) "existing" else "new"} SnsUIAction: " +
                        "description=$description, activity=${activity.javaClass.name}, source=${source?.javaClass?.name}, " +
                        "action=${snsUiAction.javaClass.name}"
                )
            } else {
                val onActivityResult = findActivityResultMethod(activity)
                onActivityResult.invoke(activity, 14, Activity.RESULT_OK, resultIntent)
                WeLogger.i(TAG, "dispatched Moments album result through Activity: description=$description, activity=${activity.javaClass.name}")
            }
            true
        }.getOrElse {
            pendingAlbumRepostText.set(null)
            WeLogger.e(TAG, "failed to dispatch Moments album result: $description", it)
            false
        }
    }

    private fun createSnsUiAction(activity: Activity): Any? {
        return runCatching {
            classSnsUiAction.clazz
                .getDeclaredConstructor(Activity::class.java)
                .apply { isAccessible = true }
                .newInstance(activity)
        }.getOrElse {
            WeLogger.e(TAG, "failed to create SnsUIAction for ${activity.javaClass.name}", it)
            null
        }
    }

    private fun findSnsUiAction(source: Any?): Any? =
        findSnsUiAction(source, java.util.Collections.newSetFromMap(java.util.IdentityHashMap()), 0)

    private fun findSnsUiAction(source: Any?, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || depth > 4 || !visited.add(source)) return null
        if (classSnsUiAction.clazz.isAssignableFrom(source.javaClass)) return source

        source.reflekt().firstFieldOrNull {
            type { it isSubclassOf classSnsUiAction.clazz }
            superclass()
        }?.get()?.let { return it }

        source.reflekt().fields().forEach { field ->
            val value = runCatching { field.get() }.getOrNull() ?: return@forEach
            if (value.javaClass.name.startsWith("java.") || value.javaClass.name.startsWith("android.")) return@forEach
            findSnsUiAction(value, visited, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findActivityResultMethod(activity: Activity): Method {
        var clazz: Class<*>? = activity.javaClass
        while (clazz != null) {
            runCatching {
                return clazz.getDeclaredMethod(
                    "onActivityResult",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    android.content.Intent::class.java
                ).apply { isAccessible = true }
            }
            clazz = clazz.superclass
        }
        error("onActivityResult not found in ${activity.javaClass.name}")
    }

    fun openAlbumForMomentPublish(context: Context, text: String, maxSelectCount: Int, queryMediaType: Int) {
        pendingAlbumRepostText.set(text)
        val intent = Intent {
            setClassName(PackageNames.WECHAT, classGalleryEntryUi.clazz.name)
            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("max_select_count", maxSelectCount)
            putExtra("query_source_type", 4)
            putExtra("query_media_type", queryMediaType)
            putExtra("show_header_view", true)
            putExtra("key_check_third_party_video", true)
            putExtra("key_can_select_video_and_pic", false)
            putExtra("key_send_raw_image", true)
            putExtra("KSnsFrom", 14)
        }
        if (context is Activity) {
            context.startActivityForResult(intent, 14)
        } else {
            context.startActivity(intent)
        }
    }

    fun openAlbumForMomentImagePublish(context: Context, text: String, count: Int) {
        openAlbumForMomentPublish(context, text, count.coerceIn(1, 9), 1)
    }

    fun openAlbumForMomentVideoPublish(context: Context, text: String) {
        openAlbumForMomentPublish(context, text, 1, 3)
    }

    /** 普通文件系统文件大小; 非普通 FS 文件 (可能走 VFS) 返回 -1。 */
    private fun regularFileSize(path: String): Long {
        val f = java.io.File(path)
        return if (f.isFile) f.length() else -1L
    }

    // VFS 文件大小方法 w6.l(String): 与 w6.m(String)(mtime) 同签名, 用探针区分 —
    // 大小落在 [1, 1e12) 而 mtime 是 ~1.7e12 的毫秒时间戳。
    @Volatile private var resolvedVfsSizeMethod: Method? = null
    @Volatile private var vfsSizeResolveTried = false

    /** 解析并缓存 VFS size 方法, 用一个已知存在的路径 [probePath] 做探针区分 size vs mtime。 */
    private fun resolveVfsSizeMethod(probePath: String?): Method? {
        resolvedVfsSizeMethod?.let { return it }
        if (vfsSizeResolveTried && probePath == null) return null

        val methods = classVfs.clazz.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java && it.returnType == Long::class.javaPrimitiveType
        }.onEach { it.isAccessible = true }

        if (methods.size == 1) {
            resolvedVfsSizeMethod = methods[0]; vfsSizeResolveTried = true
            return resolvedVfsSizeMethod
        }
        if (probePath == null) return null

        // 探针: size 应为合理正数且远小于毫秒时间戳 (1e12)。取满足条件者。
        for (m in methods) {
            val v = runCatching { m.invoke(null, probePath) as? Long }.getOrNull() ?: continue
            if (v in 1 until 1_000_000_000_000L) {
                resolvedVfsSizeMethod = m; vfsSizeResolveTried = true
                WeLogger.i(TAG, "IMG-DBG resolved VFS size method: ${m.name} -> probe=$v")
                return m
            }
        }
        vfsSizeResolveTried = true
        WeLogger.w(TAG, "IMG-DBG could not disambiguate VFS size method (candidates=${methods.map { it.name }})")
        return null
    }

    /** VFS 文件大小; 无法解析时返回 -1。 */
    private fun vfsFileSize(path: String, probePath: String? = path): Long {
        val m = resolveVfsSizeMethod(probePath) ?: return -1L
        return runCatching { m.invoke(null, path) as? Long ?: -1L }.getOrElse { -1L }
    }

    /**
     * 图片文件是否可用于转发: 必须存在且内容非空。
     * 微信在真正写入大图字节前可能先创建 0 字节 (或未下载的) snsb_ 占位, 仅凭 vfsFileExists 会误判 (转发后空白)。
     * 与聊天图片的 resolveExistingImageFile (fileSize>0) 判定一致。
     */
    private fun imageFileUsable(path: String?): Boolean {
        if (path == null) return false
        val fsSize = regularFileSize(path)
        if (fsSize > 0L) return true
        if (fsSize == 0L) return false
        // 非普通 FS 文件: 走 VFS, 要求存在且大小 > 0
        if (!vfsFileExists(path)) return false
        val vfsSize = vfsFileSize(path)
        // 无法解析大小方法时退回存在性 (保持旧行为, 避免误伤)
        return if (vfsSize < 0L) true else vfsSize > 0L
    }

    /** 该媒体的大图 (原图) 理论落地路径, 与磁盘是否存在无关。 */
    private fun resolveBigImagePath(media: TimelineObjectProto.MediaObjProto, nativeMedia: Any): String? {
        val pg = methodGetAccSnsPath.method.invoke(null) as String
        val mediaId = media.id ?: return null
        val dir = methodGetMediaFilePath.method.invoke(null, pg, mediaId) as String
        val bigName = methodGetSnsBigName.method.invoke(null, nativeMedia) as String
        return dir + bigName
    }

    /** 该媒体的缩略图理论落地路径。 */
    private fun resolveThumbImagePath(media: TimelineObjectProto.MediaObjProto, nativeMedia: Any): String? {
        val pg = methodGetAccSnsPath.method.invoke(null) as String
        val mediaId = media.id ?: return null
        val dir = methodGetMediaFilePath.method.invoke(null, pg, mediaId) as String
        val thumbName = methodGetSnsThumbName.method.invoke(null, nativeMedia) as String
        return dir + thumbName
    }

    /**
     * 解析单张图片的本地缓存路径（优先原图, 否则回退缩略图）。
     * [warnOnThumb] 为 true 时回退到缩略图会弹出提示（前台交互场景使用）。
     */
    fun getCachedImagePath(
        media: TimelineObjectProto.MediaObjProto,
        nativeMedia: Any,
        warnOnThumb: Boolean = false
    ): String? {
        return try {
            val bigPath = resolveBigImagePath(media, nativeMedia)
            if (bigPath != null && vfsFileExists(bigPath)) {
                bigPath
            } else {
                if (warnOnThumb) showToast("警告: 正在使用缩略图, 建议先查看一次图片以下载原图!")
                val thumbPath = resolveThumbImagePath(media, nativeMedia)
                if (thumbPath != null && vfsFileExists(thumbPath)) thumbPath else null
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get cached image path", e)
            null
        }
    }

    /** [DEBUG] 打印一次图片下载相关 dexkit 解析结果, 便于排查空指针/解析失败。 */
    private fun logImageDownloadResolution() {
        runCatching {
            WeLogger.i(TAG, "IMG-DBG resolution: " +
                "downloadManagerClass=${runCatching { classSnsDownloadManager.clazz.name }.getOrElse { "ERR:$it" }}, " +
                "getSnsDownManager=${runCatching { methodGetSnsDownManager.method.toString() }.getOrElse { "ERR:$it" }}, " +
                "addDownLoadSns=${runCatching { methodAddDownLoadSns.method.toString() }.getOrElse { "ERR:$it" }}, " +
                "sceneClass=${runCatching { classSnsSourceScene.clazz.name }.getOrElse { "ERR:$it" }}, " +
                "timelineScene=${runCatching { timelineSourceScene.toString() }.getOrElse { "ERR:$it" }}")
        }
    }

    /**
     * 触发微信从 CDN 下载该朋友圈图片的大图 (相当于点击查看大图), 复用微信自己的 DownloadManager。
     * 与视频的 triggerVideoDownload 同构。
     */
    private fun triggerImageDownload(nativeMedia: Any): Boolean {
        return runCatching {
            val manager = methodGetSnsDownManager.method.invoke(null)
            WeLogger.i(TAG, "IMG-DBG trigger start: managerNull=${manager == null}, looper=${android.os.Looper.myLooper()}")

            // 复刻查看大图时的调用: DownloadManager.addDownLoadSns(mediaObj, 2, null, timelineScene)。
            // downType=2 才会选中原图 url (bd4.g, .../0) 并落地 snsb_; downType=1 选的是缩略图 url。
            // decodeElement 传 null (查看大图路径 tryGetSnsBm 亦传 null), 仅需文件落地, 无需解码回调。
            val result = methodAddDownLoadSns.method.invoke(manager, nativeMedia, 2, null, timelineSourceScene) as? Boolean == true
            WeLogger.i(TAG, "IMG-DBG trigger Moments image download (downType=2): result=$result")
            result
        }.getOrElse {
            WeLogger.e(TAG, "IMG-DBG failed to trigger Moments image download", it)
            false
        }
    }

    /**
     * 确保单张图片的大图已缓存到微信内部 image2/。若未缓存则触发 CDN 下载并轮询等待。
     * @return 落地的大图路径; 超时后退回缩略图 (仍缺失则 null)。
     */
    /** [DEBUG] 列出目标目录里已落地的文件名, 便于确认下载究竟写到哪个文件名下。 */
    private fun dumpImageDir(bigPath: String?) {
        runCatching {
            if (bigPath == null) return
            val dir = java.io.File(bigPath).parent ?: return
            val entries = java.io.File(dir).listFiles()?.joinToString { "${it.name}(${it.length()})" } ?: "<dir missing or not a regular FS dir>"
            WeLogger.i(TAG, "IMG-DBG dir=$dir contents=[$entries]")
        }
    }

    suspend fun ensureImageCached(
        media: TimelineObjectProto.MediaObjProto,
        nativeMedia: Any,
        timeoutMs: Long = 60_000,
        intervalMs: Long = 500
    ): String? {
        val bigPath = resolveBigImagePath(media, nativeMedia)
        val thumbPathInit = runCatching { resolveThumbImagePath(media, nativeMedia) }.getOrNull()
        WeLogger.i(TAG, "IMG-DBG ensureImageCached: protoId=${media.id}, " +
            "bigPath=$bigPath, bigExists=${bigPath?.let { vfsFileExists(it) }}, bigFsSize=${bigPath?.let { regularFileSize(it) }}, bigVfsSize=${bigPath?.let { vfsFileSize(it, thumbPathInit ?: it) }}, bigUsable=${imageFileUsable(bigPath)}, " +
            "thumbPath=$thumbPathInit, thumbVfsSize=${thumbPathInit?.let { vfsFileSize(it) }}")
        logImageDownloadResolution()

        // 只有大图确实有内容才算已缓存 (排除 0 字节占位)
        if (imageFileUsable(bigPath)) return bigPath

        val triggered = triggerImageDownload(nativeMedia)
        WeLogger.i(TAG, "IMG-DBG triggerImageDownload returned=$triggered")
        dumpImageDir(bigPath)

        if (bigPath != null) {
            val start = SystemClock.elapsedRealtime()
            var ticks = 0
            while (SystemClock.elapsedRealtime() - start < timeoutMs) {
                if (imageFileUsable(bigPath)) {
                    WeLogger.i(TAG, "IMG-DBG big image usable after ${SystemClock.elapsedRealtime() - start}ms: $bigPath (vfsSize=${vfsFileSize(bigPath)})")
                    return bigPath
                }
                if (++ticks % 6 == 0) { // every ~3s
                    WeLogger.i(TAG, "IMG-DBG still waiting (${SystemClock.elapsedRealtime() - start}ms) bigVfsSize=${vfsFileSize(bigPath)}...")
                    dumpImageDir(bigPath)
                }
                delay(intervalMs.milliseconds)
            }
            WeLogger.w(TAG, "IMG-DBG timed out waiting for big image after ${timeoutMs}ms: $bigPath (vfsSize=${vfsFileSize(bigPath)})")
            dumpImageDir(bigPath)
        }
        // 超时: 尽量退回可用的缩略图, 避免完全空白
        val thumbPath = resolveThumbImagePath(media, nativeMedia)
        return when {
            imageFileUsable(bigPath) -> bigPath
            imageFileUsable(thumbPath) -> { WeLogger.w(TAG, "IMG-DBG falling back to thumb: $thumbPath"); thumbPath }
            else -> { WeLogger.e(TAG, "IMG-DBG neither big nor thumb usable; repost will be blank"); null }
        }
    }

    /**
     * 确保朋友圈中全部图片的大图已缓存, 任一彻底缺失 (含缩略图) 则返回 null。
     * 先并发触发所有未缓存图片的下载, 再逐张等待落地, 缩短总耗时。
     */
    suspend fun ensureImagePathsCached(
        mediaList: List<TimelineObjectProto.MediaObjProto>,
        nativeMediaList: LinkedList<*>,
        timeoutMs: Long = 60_000
    ): ArrayList<String>? {
        if (mediaList.isEmpty()) return null

        // 先对所有未落地 (或仅有空占位) 的大图触发下载
        for (index in mediaList.indices) {
            val nativeMedia = nativeMediaList.getOrNull(index) ?: return null
            val bigPath = runCatching { resolveBigImagePath(mediaList[index], nativeMedia) }.getOrNull()
            if (!imageFileUsable(bigPath)) {
                triggerImageDownload(nativeMedia)
            }
        }

        // 再逐张等待
        val paths = ArrayList<String>()
        for (index in mediaList.indices) {
            val nativeMedia = nativeMediaList.getOrNull(index) ?: return null
            val path = ensureImageCached(mediaList[index], nativeMedia, timeoutMs) ?: return null
            paths.add(path)
        }
        WeLogger.i(TAG, "IMG-DBG ensureImagePathsCached resolved ${paths.size} paths: ${paths.joinToString { "${java.io.File(it).name}(fs=${regularFileSize(it)},vfs=${vfsFileSize(it)})" }}")
        return paths
    }

    /**
     * 把 (可能位于 VFS 内的) 缓存图片实体化为真实文件, 供编辑界面 (SnsUploadUI) 读取。
     * 编辑界面通过标准文件 IO / MediaStore 读图, 无法解析微信 VFS 逻辑路径 (会显示黑图),
     * 因此必须先拷出到真实文件 —— 与视频转发先导出到相册文件同理。
     */
    private fun materializeImageToTemp(context: Context, srcPath: String, index: Int): String? {
        return runCatching {
            // 已是可读的真实文件则直接用
            if (regularFileSize(srcPath) > 0L) return srcPath

            val cacheDir = context.externalCacheDir ?: context.cacheDir
            val dest = cacheDir.asPath / "wekit_moments_img_${System.currentTimeMillis()}_$index.jpg"
            val destPath = dest.absolutePathString()
            if (!copyExistingFile(srcPath, destPath)) {
                WeLogger.e(TAG, "IMG-DBG materialize failed (copy): $srcPath")
                return null
            }
            val size = regularFileSize(destPath)
            WeLogger.i(TAG, "IMG-DBG materialized $srcPath -> $destPath (size=$size)")
            if (size <= 0L) null else destPath
        }.getOrElse {
            WeLogger.e(TAG, "IMG-DBG materializeImageToTemp failed: $srcPath", it)
            null
        }
    }

    /**
     * 编辑界面用: 确保图片已缓存 (必要时下载) 并实体化为真实文件路径。
     * 任一图片无法得到可用真实文件则返回 null。
     */
    suspend fun ensureImagePathsForEditor(
        context: Context,
        mediaList: List<TimelineObjectProto.MediaObjProto>,
        nativeMediaList: LinkedList<*>,
        timeoutMs: Long = 60_000
    ): ArrayList<String>? {
        val cached = ensureImagePathsCached(mediaList, nativeMediaList, timeoutMs) ?: return null
        val real = ArrayList<String>()
        for ((index, path) in cached.withIndex()) {
            val materialized = materializeImageToTemp(context, path, index) ?: return null
            real.add(materialized)
        }
        WeLogger.i(TAG, "IMG-DBG ensureImagePathsForEditor: ${real.joinToString { "${java.io.File(it).name}(${regularFileSize(it)})" }}")
        return real
    }

    /**
     * 解析朋友圈中全部图片的本地缓存路径, 任一缺失则返回 null。
     */
    fun prepareImagePaths(
        mediaList: List<TimelineObjectProto.MediaObjProto>,
        nativeMediaList: LinkedList<*>,
        warnOnThumb: Boolean = false
    ): ArrayList<String>? {
        if (mediaList.isEmpty()) return null
        val paths = ArrayList<String>()
        for (index in mediaList.indices) {
            val nativeMedia = nativeMediaList.getOrNull(index) ?: return null
            val cachedPath = getCachedImagePath(mediaList[index], nativeMedia, warnOnThumb) ?: return null
            paths.add(cachedPath)
        }
        return paths
    }

    fun fetchVideoPath(nativeMediaList: LinkedList<*>): String? {
        val nativeMediaObj = nativeMediaList.firstOrNull() ?: return null
        return runCatching {
            methodGetSnsVideoPath.method.invoke(null, nativeMediaObj) as? String
        }.getOrElse {
            WeLogger.e(TAG, "failed to get moment video path", it)
            null
        }
    }

    private fun fetchUsableCachedVideoPath(context: Context, nativeMediaList: LinkedList<*>): String? {
        val path = fetchVideoPath(nativeMediaList)?.takeIf { it.isNotBlank() } ?: return null
        if (!isUsableVideoPath(context, path)) return null
        val mediaId = nativeMediaList.firstOrNull()?.let { getNativeMediaId(it) }
        WeLogger.i(TAG, "resolved usable cached Moments video: media=$mediaId, path=$path")
        return path
    }

    fun fetchFullVideoPath(snsTableId: String?, nativeMediaList: LinkedList<*>): String? {
        val nativeMediaObj = nativeMediaList.firstOrNull() ?: return null
        return runCatching {
            val tableId = snsTableId ?: getNativeMediaId(nativeMediaObj) ?: ""
            val path = methodGetSnsVideoFullPath.method.invoke(null, tableId, nativeMediaObj) as? String
            if (path.isNullOrEmpty() || !(vfsFileExists(path) || java.io.File(path).isFile)) {
                val theoreticalPath = fetchVideoPath(nativeMediaList)
                WeLogger.i(
                    TAG,
                    "Moments full video path missing: sns=$tableId, media=${getNativeMediaId(nativeMediaObj)}, full=$path, theoretical=$theoreticalPath, theoreticalExists=${theoreticalPath?.let { vfsFileExists(it) || java.io.File(it).isFile }}"
                )
                null
            } else {
                WeLogger.i(TAG, "resolved full Moments video: sns=$tableId, path=$path")
                path
            }
        }.getOrElse {
            WeLogger.e(TAG, "failed to get full moment video path", it)
            null
        }
    }

    fun fetchFinishedVideoPath(snsTableId: String?, nativeMediaList: LinkedList<*>): String? {
        val nativeMediaObj = nativeMediaList.firstOrNull() ?: return null
        return runCatching {
            val tableId = snsTableId ?: getNativeMediaId(nativeMediaObj) ?: ""
            val path = methodIsSnsVideoDownloadFinished.method.invoke(null, tableId, nativeMediaObj) as? String
            if (path.isNullOrEmpty() || !(vfsFileExists(path) || java.io.File(path).isFile)) {
                val theoreticalPath = fetchVideoPath(nativeMediaList)
                WeLogger.i(
                    TAG,
                    "Moments video not finished: sns=$tableId, media=${getNativeMediaId(nativeMediaObj)}, theoretical=$theoreticalPath, theoreticalExists=${theoreticalPath?.let { vfsFileExists(it) || java.io.File(it).isFile }}"
                )
                null
            } else {
                WeLogger.i(TAG, "resolved finished Moments video: sns=$tableId, path=$path")
                path
            }
        }.getOrElse {
            WeLogger.e(TAG, "failed to get finished moment video path", it)
            null
        }
    }

    private suspend fun waitForFinishedVideoPath(
        snsTableId: String?,
        nativeMediaList: LinkedList<*>,
        timeoutMs: Long = 90_000,
        intervalMs: Long = 500
    ): String? {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            fetchFinishedVideoPath(snsTableId, nativeMediaList)?.let { return it }
            delay(intervalMs.milliseconds)
        }
        return fetchFinishedVideoPath(snsTableId, nativeMediaList)
    }

    private suspend fun waitForVideoPath(
        context: Context,
        snsTableId: String?,
        nativeMediaList: LinkedList<*>,
        timeoutMs: Long = 90_000,
        intervalMs: Long = 500
    ): String? {
        val start = SystemClock.elapsedRealtime()
        var nextProbeAt = start
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            fetchFullVideoPath(snsTableId, nativeMediaList)?.let { return it }
            fetchFinishedVideoPath(snsTableId, nativeMediaList)?.let { return it }
            val now = SystemClock.elapsedRealtime()
            if (now >= nextProbeAt) {
                fetchUsableCachedVideoPath(context, nativeMediaList)?.let { return it }
                nextProbeAt = now + 2_500
            }
            delay(intervalMs.milliseconds)
        }
        return fetchFullVideoPath(snsTableId, nativeMediaList)
            ?: fetchFinishedVideoPath(snsTableId, nativeMediaList)
            ?: fetchUsableCachedVideoPath(context, nativeMediaList)
    }

    fun fetchVideoThumbPath(nativeMediaList: LinkedList<*>): String? {
        val nativeMediaObj = nativeMediaList.firstOrNull() ?: return null
        return runCatching {
            methodGetSnsVideoThumbImagePath.method.invoke(null, nativeMediaObj) as? String
        }.getOrElse {
            WeLogger.e(TAG, "failed to get moment video thumb path", it)
            null
        }
    }

    /**
     * 一键（后台）转发指定朋友圈, 直接加入发送队列, 不经过编辑界面。
     * [nativeTimeline] 可显式传入, 否则从 [snsInfo] 反射解析。
     */
    fun quickForward(snsInfo: Any?, nativeTimeline: Any? = null): ActionResult {
        val content = getMomentContent(snsInfo, nativeTimeline)
            ?: return ActionResult(success = false, sent = false, message = "无法解析朋友圈内容")
        return quickForward(content)
    }

    /**
     * 一键转发, 但在转发前先强制把图片/视频从 CDN 缓存到本地 (相当于自动点开一次), 避免转发后空白。
     * [nativeTimeline] 可显式传入, 否则从 [snsInfo] 反射解析。
     */
    suspend fun quickForwardEnsuringCached(snsInfo: Any?, nativeTimeline: Any? = null): ActionResult {
        val content = getMomentContent(snsInfo, nativeTimeline)
            ?: return ActionResult(success = false, sent = false, message = "无法解析朋友圈内容")
        return quickForwardEnsuringCached(content)
    }

    suspend fun quickForwardEnsuringCached(content: MomentContent): ActionResult {
        val text = content.contentText
        return try {
            when (content.type) {
                15, 5 -> { // 视频
                    val video = ensureVideoPaths(HostInfo.application, content)
                        ?: return ActionResult(success = false, sent = false, message = "视频下载失败或超时")
                    val ok = postTextAndVideo(HostInfo.application, text, video.videoPath, video.thumbPath)
                    if (ok) ActionResult(success = true, sent = true, message = "已加入发送队列")
                    else ActionResult(success = false, sent = false, message = "转发失败")
                }

                1, 54 -> { // 图片 / 实况相册: 先确保图片缓存到位, 再转发
                    if (content.hasLivePhoto) {
                        // 实况相册: 先把静态封面图缓存到位 (实况视频缺失时会自动退化为静态图)
                        ensureImagePathsCached(content.mediaList, content.nativeMediaList)
                            ?: return ActionResult(success = false, sent = false, message = "图片下载失败或超时")
                        return quickForward(content)
                    }
                    val paths = ensureImagePathsCached(content.mediaList, content.nativeMediaList)
                        ?: return ActionResult(success = false, sent = false, message = "图片下载失败或超时")
                    val ok = postTextAndImages(text, paths)
                    if (ok) ActionResult(success = true, sent = true, message = "已加入发送队列")
                    else ActionResult(success = false, sent = false, message = "转发失败")
                }

                else -> quickForward(content)
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "quickForwardEnsuringCached failed", e)
            ActionResult(success = false, sent = false, message = e.message ?: "转发出现异常", error = e)
        }
    }

    fun quickForward(content: MomentContent): ActionResult {
        val text = content.contentText
        return try {
            val ok = when (content.type) {
                1, 54 -> { // 图片 / 实况相册 (可混合静态图与实况图片)
                    if (content.hasLivePhoto) {
                        val resolved = resolveMediaItems(content)
                            ?: return ActionResult(success = false, sent = false, message = "未找到本地缓存的图片")
                        val sent = postTextAndMixedMedia(text, resolved.items)
                        if (sent && resolved.degradedLivePhotos) {
                            return ActionResult(success = true, sent = true, message = "已加入发送队列 (部分实况视频未下载, 已按静态图转发)")
                        }
                        sent
                    } else {
                        val paths = prepareImagePaths(content.mediaList, content.nativeMediaList)
                            ?: return ActionResult(success = false, sent = false, message = "未找到本地缓存的图片")
                        postTextAndImages(text, paths)
                    }
                }

                15, 5 -> { // 视频
                    val videoPath = fetchFinishedVideoPath(content.snsTableId, content.nativeMediaList)
                    val thumbPath = fetchVideoThumbPath(content.nativeMediaList)
                    if (videoPath == null || thumbPath == null) {
                        return ActionResult(success = false, sent = false, message = "未找到本地缓存的视频, 请播放一次后再转发")
                    }
                    postTextAndVideo(HostInfo.application, text, videoPath, thumbPath)
                }

                else -> postText(text) // 文字
            }

            if (ok) {
                ActionResult(success = true, sent = true, message = "已加入发送队列")
            } else {
                ActionResult(success = false, sent = false, message = "转发失败")
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "quickForward failed", e)
            ActionResult(success = false, sent = false, message = e.message ?: "转发出现异常", error = e)
        }
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
