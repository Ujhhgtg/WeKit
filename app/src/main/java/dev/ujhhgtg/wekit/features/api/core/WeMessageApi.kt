package dev.ujhhgtg.wekit.features.api.core

import android.annotation.SuppressLint
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Parcel
import android.os.SystemClock
import com.tencent.mm.api.IEmojiInfo
import com.tencent.mm.opensdk.modelmsg.WXFileObject
import com.tencent.mm.opensdk.modelmsg.WXMediaMessage
import com.tencent.mm.opensdk.modelmsg.WXMiniProgramObject
import com.tencent.mm.opensdk.modelmsg.WXMusicObject
import com.tencent.mm.opensdk.modelmsg.WXMusicVideoObject
import com.tencent.mm.opensdk.modelmsg.WXTextObject
import com.tencent.mm.opensdk.modelmsg.WXVideoObject
import com.tencent.mm.opensdk.modelmsg.WXWebpageObject
import com.tencent.mm.plugin.gif.MMWXGFJNI
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.spec.VagueType
import dev.ujhhgtg.reflekt.spec.typeMatches
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.isBuiltin
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi.cacheFile
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi.downloadFile
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.collections.emptyHashSet
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.reflection.long
import dev.ujhhgtg.wekit.utils.reflection.void
import dev.ujhhgtg.wekit.utils.serialization.JsonToXmlConverter
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils.extractXmlAttr
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils.extractXmlTag
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.base.AccessFlagsMatcher
import org.luckypray.dexkit.result.FieldUsingType
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.random.Random


@SuppressLint("DiscouragedApi")
object WeMessageApi : ApiFeature(), IResolveDex {

    override val technicalId = "消息发送服务"
    override val nameRes = R.string.feature_we_message_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_message_api_description

    private const val NOTIFICATION_THUMBNAIL_MIN_EDGE_DP = 180

    // -------------------------------------------------------------------------------------
    // 基础消息类
    // -------------------------------------------------------------------------------------
    val classNetSceneSendMsg by dexClass {
        matcher {
            methods {
                add {
                    paramCount = 1
                    usingStrings("MicroMsg.NetSceneSendMsg", "markMsgFailed for id:%d")
                }
            }
        }
    }
    internal val classNetSceneQueue by dexClass {
        searchPackages("com.tencent.mm.modelbase")
        matcher {
            methods {
                add {
                    paramCount = 2
                    usingStrings("worker thread has not been se", "MicroMsg.NetSceneQueue")
                }
            }
        }
    }
    val classNetSceneBase by dexClass {
        matcher {
            usingEqStrings("scene security verification not passed, type=")
        }
    }
    private val classNetSceneObserverOwner by dexClass {
        matcher {
            methods {
                add {
                    paramCount = 4
                    usingStrings("MicroMsg.Mvvm.NetSceneObserverOwner")
                }
            }
        }
    }

    // hi0.j1::d() is identical to xv0.a9::e()
    val methodGetSendMsgObject by dexMethod(allowMultiple = true) {
        matcher {
            paramCount = 0
            returnType = classNetSceneObserverOwner.data.name
            modifiers(AccessFlagsMatcher(Modifier.STATIC))
        }
    }
    private val methodPostToQueue by dexMethod {
        searchPackages("com.tencent.mm.modelbase")
        matcher {
            declaredClass = classNetSceneQueue.data.name
            paramTypes(classNetSceneBase.data.name)
            returnType = "boolean"
            usingNumbers(0)
        }
    }

    private val classPatMsgExtension by dexClass {
        matcher {
            usingEqStrings("MicroMsg.PatMsgExtension", "insert pat msg %d %s %s")
        }
    }
    private val ctorNetSceneSendPat by dexConstructor {
        matcher {
            usingEqStrings("MicroMsg.NetSceneSendPat")
            paramCount(4)
        }
    }
    private val ctorNetSceneRevokeMsg by dexConstructor {
        searchPackages("com.tencent.mm.modelsimple")
        matcher {
            usingEqStrings("MicroMsg.NetSceneRevokeMsg")
            paramCount(3)
        }
    }
    private val ctorNetSceneSendMsgLocation by dexConstructor {
        matcher {
            usingEqStrings("MicroMsg.NetSceneSendMsg", "[mergeMsgSource] rawSource:%s args is null:%s flag:%s")
        }
    }
    private val classImportMultiVideo by dexClass {
        matcher {
            usingEqStrings("MicroMsg.GetVideoMetadata", "get video file name, dataString ")
        }
    }
    private val classAppMessage by dexClass {
        matcher {
            usingEqStrings("MicroMsg.AppMessage", "parse amessage xml failed")
        }
    }
    val methodSendAppMsg by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.model.app")
        matcher {
            usingEqStrings("MicroMsg.AppMsgLogic", "summerbig sendAppMsg attachFilePath[%s], content[%s]")
        }
    }
    private val methodShareFile by dexMethod {
        matcher {
            paramTypes(
                "com.tencent.mm.opensdk.modelmsg.WXMediaMessage",
                "java.lang.String",
                "java.lang.String",
                "java.lang.String",
                "int",
                "java.lang.String"
            )
        }
    }
    val classMsgInfo by dexClass {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("MicroMsg.MsgInfo", "[parseNewXmlSysMsg]")
        }
    }
    val classMsgInfoStorage by dexClass {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("MicroMsg.MsgInfoStorage", "deleted dirty msg ,count is %d")
        }
    }
    val methodMsgInfoHandleApiInsertMessage by dexMethod {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("MicroMsg.MsgInfoStorage", "protect:c2c msg should not here")
        }
    }
    val methodMsgInfoStorageInsertMessage by dexMethod {
        matcher {
            declaredClass(classMsgInfoStorage.data.name)
            usingEqStrings("MsgInfo processAddMsg insert db error")
        }
    }
    val classChattingContext by dexClass {
        matcher {
            usingEqStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]")
        }
    }
    internal val methodChattingContextGetTalker by dexMethod {
        matcher {
            declaredClass(classChattingContext.data.name)
            usingEqStrings("getTalker returns null.")
        }
    }
    val classChattingDataAdapter by dexClass {
        matcher {
            usingEqStrings(
                "MicroMsg.ChattingDataAdapterV3",
                "[handleMsgChange] isLockNotify:"
            )
        }
    }
    val classTransformChattingComponent by dexClass {
        searchPackages("com.tencent.mm.ui.chatting.component")
        matcher {
            usingEqStrings("MicroMsg.TransformComponent", "[onChattingPause]")
        }
    }
    val methodGetIsTransformed by dexMethod {
        matcher {
            declaredClass(classMsgInfo.data.name)
            usingNumbers(64, 0)
            usingFields {
                add {
                    type = "int"
                }
            }
            returnType = "boolean"
        }
    }

    // -------------------------------------------------------------------------------------
    // 原生引用文本发送
    // -------------------------------------------------------------------------------------
    private val methodQuoteCompose by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
            returnType = "boolean"
            usingEqStrings(
                "MicroMsg.msgquote.PluginMsgQuote",
                "sendQuoteMsg result:%s msgId:%s result:%s",
                "msg is revoked!",
            )
            usingNumbers(57)
        }
    }
    private val classQuoteMsgItem by dexClass()
    private val classQuoteRelation by dexClass()
    private val methodQuoteNormalizeType by dexMethod()
    private val methodQuoteMsgSource by dexMethod()
    private val methodQuoteStorageGetter by dexMethod()
    private val methodQuoteRelationInsert by dexMethod()
    private val fieldQuoteAppTitle by dexField()
    private val fieldQuoteAppType by dexField()
    private val fieldQuoteAppItem by dexField()

    // -------------------------------------------------------------------------------------
    // 图片发送组件
    // -------------------------------------------------------------------------------------
    private val classMvvmBase by dexClass {
        matcher {
            usingStrings(
                "MicroMsg.Mvvm.MvvmPlugin",
                "onAccountInitialized start"
            )
        }
    }
    private val classImageSender by dexClass()
    private val classImageTask by dexClass(allowFailure = true) {
        matcher { usingStrings("msg_raw_img_send") }
    }
    private val classConfigLogic by dexClass {
        matcher {
            usingEqStrings(
                "MicroMsg.ConfigStorageLogic",
                "get userinfo fail"
            )
        }
    }
    private val classImageServiceImpl by dexClass(allowFailure = true) {
        matcher {
            usingStrings("MicroMsg.ImgUpload.MsgImgFeatureService")
            superClass(classMvvmBase.data.name)
        }
    }
    private val methodImageSendEntry by dexMethod()

    // -------------------------------------------------------------------------------------
    // 语音发送组件
    // -------------------------------------------------------------------------------------
    private val classVoiceParams by dexClass {
        matcher {
            usingEqStrings("toUserName", "fileName", "send_voice_msg")
        }
    }
    private val classVoiceNameGen by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.VoiceLogic", "startRecord insert voicestg success")
        }
    }
    internal val classVfs by dexClass {
        matcher {
            usingStrings("MicroMsg.VFSFileOp", "Cannot resolve path or URI")
        }
    }
    private val classPathUtil by dexClass {
        searchPackages("com.tencent.mm.sdk.platformtools")
        matcher {
            methods {
                add {
                    modifiers = Modifier.PUBLIC or Modifier.STATIC
                    returnType = "java.lang.String"
                    paramTypes(
                        "java.lang.String",
                        "java.lang.String",
                        "java.lang.String",
                        "java.lang.String",
                        "int"
                    )
                }
            }
        }
    }
    internal val methodChattingDataAdapterOnBindViewHolder by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.ChattingDataAdapterV3")
            }
            usingEqStrings("_onBindViewHolder[")
            paramTypes(null, Int::class.java)
            returnType("void")
        }
    }
    private val classVoiceLogic by dexClass {
        matcher {
            usingEqStrings("MicroMsg.VoiceLogic", "startRecord insert voicestg success")
        }
    }
    private val methodGetAmrFullPath by dexMethod {
        matcher {
            usingEqStrings("getAmrFullPath cost: ")
        }
    }
    private val methodStartRecvAndSend by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.SceneVoiceService", "Start Recv[%s] :%s", "Start Send :")
        }
    }
    private val classSceneVoiceService by dexClass {
        matcher {
            usingEqStrings("MicroMsg.SceneVoiceService", "//voicetrymore", "getVoiceService %s")
        }
    }

    // SceneVoiceService.run() — 在服务自身的 looper 线程上派发，避免在 IO 线程直接调用
    // startRecvAndSend 时构造 Handler 抛出 "looper and serial is null!"
    private val methodRunVoiceService by dexMethod(allowFailure = true) {
        matcher {
            paramCount = 0
            usingEqStrings("MicroMsg.SceneVoiceService", "run() %s")
        }
    }

    private val classVoiceServiceInterface by dexClass()

    private val classVoiceServiceImpl by dexClass {
        matcher {
            usingEqStrings(
                "MicroMsg.VoiceMsgAsyncSendFSC",
                "sendAsync only support BaseSendMsgTask Type"
            )
        }
    }
//    private val methodSendVoice by dexMethod(allowMultiple = true) {
//        matcher {
//            declaredClass(classVoiceServiceImpl.clazz)
//            paramCount = 1
//            returnType = "void"
//        }
//    }

    // -------------------------------------------------------------------------------------
    // 运行时缓存
    // -------------------------------------------------------------------------------------

    // 基础 & 文本
    private val getSelfAliasMethod: Method by lazy {
        classConfigLogic.reflekt()
            .firstMethod {
                name { it.length <= 2 }
                modifiers(Modifiers.STATIC)
                parameterCount = 0
                returnType = String::class
            }.self
    }

    // 图片
    private val imageServiceApiClass: Class<*> by lazy {
        classImageServiceImpl.clazz.interfaces.first {
            !it.isBuiltin
        }
    }
    private val sendImageMethod: Method by lazy {
        classImageServiceImpl.clazz.declaredMethods.first { m ->
            m.parameterCount == 1 &&
                    m.parameterTypes[0] == classImageTask.clazz &&
                    m.returnType.name.contains("flow", ignoreCase = true)
        }
    }
    private val taskConstructor: Constructor<*> by lazy {
        classImageTask.clazz.reflekt()
            .firstConstructor { parameterCount = 5 }
            .self
    }
    private val crossParamsClass: Class<*> by lazy { taskConstructor.parameterTypes[4] }

    // 语音 & VFS
    private lateinit var vfsCopyMethod: Method         // VFS.L (write)
    private lateinit var vfsReadMethod: Method         // VFS.F (read)
    private lateinit var vfsExistsMethod: Method       // VFS.k/e (exists)
    private val voiceNameGenMethod: Method by lazy {
        classVoiceNameGen.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            parameters(String::class, VagueType)
            returnType = String::class
        }.self
    }
    private val setVoiceMethod: Method by lazy {
        classVoiceNameGen.reflekt().firstMethod {
            parameterCount { it == 3 || it == 4 }
            parameters {
                it[0] == BString && it[1].typeMatches(int) && it[2].typeMatches(int)
            }
            returnType = bool
        }.self
    }
    private var storageAccPathMethod: Method? = null  // b0.e (动态解析)
    private val pathGenMethod: Method by lazy {
        classPathUtil.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            parameters(VagueType, VagueType, VagueType, VagueType, Int::class)
            returnType = String::class
        }.self
    }
    private lateinit var voiceDurationField: Field     // 语音时长字段
    private lateinit var voiceOffsetField: Field       // 偏移量字段

    private const val TAG = "WeMessageApi"
    private const val MAX_EMOJI_DIMENSION = 1024
    private const val STICKER_SEND_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    private enum class StickerFileFormat {
        GIF,
        PNG,
        WEBP,
        WXGF,
        JPEG,
        OTHER,
    }

    private data class StickerFileInfo(
        val format: StickerFileFormat,
        val byteCount: Long,
        val width: Int,
        val height: Int,
    )

    @SuppressLint("NonUniqueDexKitData")
    override fun resolveDex(dexKit: DexKitBridge) {
        val quoteCompose = methodQuoteCompose.data
        val quoteMsgItem = dexKit.findClass {
            matcher { className = "com.tencent.mm.plugin.msgquote.model.MsgQuoteItem" }
        }.single()
        classQuoteMsgItem.setDescriptor(quoteMsgItem)

        val appMessageName = classAppMessage.data.name
        val quoteWrites = quoteCompose.usingFields
            .filter { it.usingType == FieldUsingType.Write }
            .map { it.field }
            .distinctBy { it.descriptor }
        fieldQuoteAppTitle.setDescriptor(
            quoteWrites.single {
                it.className == appMessageName && it.typeName == "java.lang.String"
            }
        )
        fieldQuoteAppType.setDescriptor(
            quoteWrites.single {
                it.className == appMessageName && it.typeName == "int"
            }
        )
        fieldQuoteAppItem.setDescriptor(
            quoteWrites.single {
                it.className == appMessageName && it.typeName == quoteMsgItem.name
            }
        )

        methodQuoteNormalizeType.setDescriptor(
            quoteCompose.invokes.distinctBy { it.descriptor }.single {
                Modifier.isStatic(it.modifiers) &&
                    it.paramTypeNames == listOf("int") &&
                    it.returnTypeName == "int"
            }
        )

        val msgInfoName = classMsgInfo.data.name
        methodQuoteMsgSource.setDescriptor(
            quoteCompose.invokes.distinctBy { it.descriptor }.single {
                !Modifier.isStatic(it.modifiers) &&
                    it.paramTypeNames == listOf(msgInfoName) &&
                    it.returnTypeName == "java.lang.String"
            }
        )

        val quoteRelation = dexKit.findClass {
            matcher { usingStrings("MsgQute{field_msgId=", "field_quotedMsgTalker=") }
        }.single()
        classQuoteRelation.setDescriptor(quoteRelation)
        val quoteStorage = dexKit.findClass {
            matcher {
                usingStrings(
                    "MicroMsg.msgquote.MsgQuoteStorage",
                    "getMsgQuteByMsgId:%s",
                )
            }
        }.single()
        methodQuoteRelationInsert.setDescriptor(
            quoteStorage.methods.single { candidate ->
                candidate.paramTypeNames == listOf(quoteRelation.name) &&
                    candidate.returnTypeName == "boolean" &&
                    candidate.usingFields.any {
                        it.usingType == FieldUsingType.Write &&
                            it.field.name == "field_status"
                    }
            }
        )
        methodQuoteStorageGetter.setDescriptor(
            quoteCompose.invokes.distinctBy { it.descriptor }.single {
                !Modifier.isStatic(it.modifiers) &&
                    it.paramCount == 0 &&
                    it.returnTypeName == quoteStorage.name
            }
        )

        classImageSender.find(dexKit, allowFailure = true) {
            matcher {
                usingStrings(
                    "MicroMsg.ImgUpload.MsgImgSyncSendFSC",
                    "/cgi-bin/micromsg-bin/uploadmsgimg"
                )
            }
        }

        methodImageSendEntry.find(dexKit) {
            matcher {
                declaredClass(classImageSender.data.name)
                modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL
                paramCount(4, 5)
                usingEqStrings("send_mid_size", "send_hevc_mid_size")
            }
        }

        val taskClassName = methodImageSendEntry.data.paramTypeNames[1]
        classImageTask.setDescriptor(taskClassName)

        val imageFeatureServiceNewPathProbes = dexKit.findMethod {
            matcher {
                usingEqStrings("sendRawImgAsyncWithPreBuild[", "send_group_id")
            }
        }

        when (imageFeatureServiceNewPathProbes.size) {
            1 -> {
                methodImgUploadFeatureServiceNewPathProbe.setDescriptor(
                    imageFeatureServiceNewPathProbes.single()
                )
                methodImgUploadFeatureServiceSendImage.find(dexKit) {
                    matcher {
                        declaredClass {
                            usingEqStrings(
                                "MicroMsg.ImgUpload.MsgImgFeatureService",
                                "taskListener",
                                "params",
                            )
                        }
                        paramCount(1)
                        usingEqStrings("params")
                    }
                }
                methodAppInfoSetAppId.find(dexKit) {
                    matcher {
                        declaredClass {
                            usingEqStrings(
                                "appinfo",
                                "appid",
                                "version",
                                "appname",
                                "isforceupdate",
                                "messageaction",
                                "messageext",
                                "mediatagname",
                            )
                        }
                        paramTypes(BString)
                        usingNumbers(0)
                    }
                }
                ctorNetSceneUploadMsgImg.setPlaceholderDescriptor(
                    expectedFailure = true,
                    reason = "new image feature service path is active",
                )
            }

            0 -> {
                methodImgUploadFeatureServiceNewPathProbe.setPlaceholderDescriptor(
                    expectedFailure = true,
                    reason = "send_group_id image path is absent; using legacy NetSceneUploadMsgImg",
                )
                methodImgUploadFeatureServiceSendImage.setPlaceholderDescriptor(
                    expectedFailure = true,
                    reason = "legacy NetSceneUploadMsgImg path is active",
                )
                methodAppInfoSetAppId.setPlaceholderDescriptor(
                    expectedFailure = true,
                    reason = "legacy NetSceneUploadMsgImg path is active",
                )
                ctorNetSceneUploadMsgImg.find(dexKit) {
                    searchPackages("com.tencent.mm.modelimage")
                    matcher {
                        name = "<init>"
                        declaredClass {
                            usingEqStrings(
                                "MicroMsg.NetSceneUploadMsgImg",
                                "/cgi-bin/micromsg-bin/uploadmsgimg",
                            )
                        }
                        paramTypes(
                            int,
                            BString,
                            BString,
                            BString,
                            int,
                            null,
                            int,
                            BString,
                            BString,
                            bool,
                            int,
                        )
                    }
                }
            }

            else -> error(
                "multiple send_group_id image path probes found: " +
                    imageFeatureServiceNewPathProbes.joinToString { it.descriptor }
            )
        }

        val targetInterface = classVoiceServiceImpl.data.interfaces.first {
            !it.name.startsWith("ki0.")
        }
        classVoiceServiceInterface.setDescriptor(targetInterface.name)
    }

    fun convertMsgInfoInstanceFromContentValues(contentValues: ContentValues): Any {
        val msgInfo = classMsgInfo.clazz.createInstance()
        msgInfo.reflekt().firstMethod {
            name = "convertFrom"
            parameters(ContentValues::class, Boolean::class)
            superclass()
        }.invoke(contentValues, true)
        return msgInfo
    }

    fun createSimpleMsgInfoAndInsert(type: Int, talker: String, content: String, currentTime: Long) {
        val values = ContentValues().apply {
            put("msgid", 0)
            put("msgSvrId", currentTime + Random.nextInt())
            put("type", type)
            put("status", 3)
            put("createTime", currentTime)
            put("talker", talker)
            put("content", content)
        }
        val msgInfo = convertMsgInfoInstanceFromContentValues(values)
        methodMsgInfoStorageInsertMessage.method.invoke(
            WeServiceApi.msgInfoStorage,
            msgInfo
        )
    }

    fun revokeMsg(msgInfo: MessageInfo): Boolean {
        return try {
            WeLogger.i(TAG, "revoking message: msgSvrId=${msgInfo.serverId}")
            val netScene = ctorNetSceneRevokeMsg.newInstance(msgInfo.instance, "你撤回了一条消息", "")
            WeNetSceneApi.sendNetScene(netScene)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "revokeMsg failed", e); false
        }
    }

    fun revokeMsgByMsgId(msgId: Long): Boolean {
        return revokeMsg(MessageInfo(getMsgInfoInstanceByMsgSvrId(getMsgSvrIdByMsgId(msgId) ?: return false, null)))
    }

    fun revokeMsgByMsgSvrId(msgSvrId: Long): Boolean {
        return revokeMsg(MessageInfo(getMsgInfoInstanceByMsgSvrId(msgSvrId, null)))
    }

    private val methodGetMsgInfoByTalkerAndSvrId by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.MsgInfoStorage", "build new index last %d")
            }
            paramTypes(BString, long)
            returnType(classMsgInfo.data.name)
            usingEqStrings("msgSvrId=?")
        }
    }

    /** 通过本地 msgId 解析 msgSvrId；找不到或 msgSvrId 为 0 时返回 null。 */
    fun getMsgSvrIdByMsgId(msgId: Long): Long? {
        return try {
            WeDatabaseApi.rawQuery("SELECT msgSvrId FROM message WHERE msgId=?", arrayOf(msgId))
                .use { if (it.moveToFirst()) it.getLong(0).takeIf { v -> v != 0L } else null }
        } catch (e: Exception) {
            WeLogger.e(TAG, "getMsgSvrIdByMsgId failed", e)
            null
        }
    }

    /**
     * 通过 msgSvrId 反查 talker。仅查 message 表 (C2C/群聊); 企业微信、小程序消息等不在此表中会返回 null。
     */
    fun getTalkerByMsgSvrId(msgSvrId: Long): String? {
        return try {
            WeDatabaseApi.rawQuery("SELECT talker FROM message WHERE msgSvrId=?", arrayOf(msgSvrId))
                .use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            WeLogger.e(TAG, "getTalkerByMsgSvrId failed", e)
            null
        }
    }

    private fun getMsgInfoByMsgId(msgId: Long): MessageInfo? {
        return WeDatabaseApi.rawQuery("SELECT * FROM message WHERE msgId=?", arrayOf(msgId)).use {
            if (it.moveToFirst()) MessageInfo(convertMsgInfoInstanceFromCursor(it)) else null
        }
    }

    /**
     * @param talker 会话 username; 传 null 时自动从 message 表反查 (仅覆盖 C2C/群聊)。
     */
    fun getMsgInfoInstanceByMsgSvrId(msgSvrId: Long, talker: String? = null): Any {
        val resolvedTalker = talker ?: getTalkerByMsgSvrId(msgSvrId)
        ?: error("failed to resolve talker for msgSvrId=$msgSvrId")
        return methodGetMsgInfoByTalkerAndSvrId.method.invoke(
            WeServiceApi.msgInfoStorage, resolvedTalker, msgSvrId
        )!!
    }

    fun convertMsgInfoInstanceFromCursor(cursor: Cursor): Any {
        val msgInfo = classMsgInfo.clazz.createInstance()
        msgInfo.reflekt().firstMethod {
            name = "convertFrom"
            parameters(Cursor::class)
            returnType = void
        }.invoke(cursor)
        return msgInfo
    }

    private fun quoteDisplayName(source: MessageInfo): String {
        val sender = source.sender
        if (source.isInGroupChat) {
            WeDatabaseApi.getGroupMemberDisplayName(source.talker, sender)
                .takeIf(String::isNotEmpty)
                ?.let { return it }
        }
        return WeDatabaseApi.getDisplayName(sender)
    }

    private fun quoteSourceContent(source: MessageInfo): String {
        var value = source.actualContent
        val textLikeTypes = setOf(1, 11, 21, 31, 36, 301989937, 1107296305)
        if (source.typeCode !in textLikeTypes) {
            val xmlStart = value.indexOf('<')
            if (xmlStart > 0) value = value.substring(xmlStart)
        }
        if (source.type == MessageType.QUOTE) {
            return try {
                value.substring(0, value.indexOf("<refermsg>")) +
                    "<refermsg>" + value.substring(value.lastIndexOf("</refermsg>"))
            } catch (_: Exception) {
                value
            }
        }
        return try {
            val recordStart = value.indexOf("<recorditem>")
            val recordEnd = value.lastIndexOf("</recorditem>")
            buildString {
                append(value.substring(0, recordStart.coerceAtLeast(0)))
                if (recordStart > 0) append("<recorditem>")
                append(value.substring(recordEnd.coerceAtLeast(0)))
            }
        } catch (_: Exception) {
            value
        }
    }

    private fun createNativeQuoteItem(source: MessageInfo): Any {
        val sourceGenerator = methodQuoteMsgSource.method
        val generatedMsgSource = sourceGenerator.invoke(
            WeServiceApi.getServiceByClass(sourceGenerator.declaringClass),
            source.instance,
        ) as String?
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(methodQuoteNormalizeType.method.invoke(null, source.typeCode) as Int)
            parcel.writeLong(source.serverId)
            parcel.writeString(source.talker)
            parcel.writeString(source.sender)
            parcel.writeString(quoteDisplayName(source))
            parcel.writeString(source.msgSource)
            parcel.writeString(quoteSourceContent(source))
            parcel.writeString(generatedMsgSource.orEmpty())
            parcel.writeInt(0)
            parcel.writeString(extractXmlTag(source.msgSource, "strid"))
            parcel.writeLong(source.createTime / 1000)
            parcel.writeString(null)
            parcel.setDataPosition(0)
            classQuoteMsgItem.clazz.createInstance(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun insertQuoteRelation(localMsgId: Long, source: MessageInfo): Boolean {
        val relation = classQuoteRelation.clazz.createInstance()
        relation.reflekt().apply {
            setField("field_msgId", localMsgId, superclass = true)
            setField("field_quotedMsgId", source.id, superclass = true)
            setField("field_quotedMsgSvrId", source.serverId, superclass = true)
            setField("field_quotedMsgTalker", source.talker, superclass = true)
        }
        val storageGetter = methodQuoteStorageGetter.method
        val pluginInterface = storageGetter.declaringClass.interfaces.single()
        val plugin = WeServiceApi.getServiceByClass(pluginInterface)
        val storage = storageGetter.invoke(plugin)
        return methodQuoteRelationInsert.method.invoke(storage, relation) as Boolean
    }

    private fun sendNativeQuote(talker: String, content: String, source: MessageInfo): Boolean {
        require(talker.isNotEmpty()) { "quote destination is empty" }
        require(content.isNotEmpty()) { "quote content is empty" }
        require(source.id > 0L && source.talker.isNotEmpty()) { "quote source is invalid" }

        // Never parse and resend the stored type-57 XML here. Its outer <fromusername>
        // belongs to the original sender. A fresh AppMessage mirrors ChatFooter's native
        // quote path, so WeChat creates the new envelope while MsgQuoteItem supplies refermsg.
        val appMessage = classAppMessage.clazz.createInstance()
        fieldQuoteAppTitle.field.set(appMessage, content)
        fieldQuoteAppType.field.setInt(appMessage, 57)
        fieldQuoteAppItem.field.set(appMessage, createNativeQuoteItem(source))

        val result = WeAppMsgApi.sendAppMsgObject(talker, appMessage)
        val accepted = result.statusCode == 0 &&
            (result.localMsgId == null || result.localMsgId > 0L)
        if (!accepted) {
            WeLogger.e(
                TAG,
                "sendNativeQuote rejected: destination=$talker, sourceMsgId=${source.id}, " +
                    "sourceMsgSvrId=${source.serverId}, sourceTalker=${source.talker}, " +
                    "statusCode=${result.statusCode}, localMsgId=${result.localMsgId}",
            )
            return false
        }
        val relationInserted = result.localMsgId?.takeIf { it > 0L }?.let { localMsgId ->
            runCatching { insertQuoteRelation(localMsgId, source) }.getOrElse {
                WeLogger.e(
                    TAG,
                    "sendNativeQuote: sent but failed to insert relation for localMsgId=$localMsgId",
                    it,
                )
                false
            }
        }
        WeLogger.i(
            TAG,
                "sendNativeQuote: destination=$talker, sourceMsgId=${source.id}, " +
                "sourceMsgSvrId=${source.serverId}, sourceTalker=${source.talker}, " +
                "contentLength=${content.length}, statusCode=${result.statusCode}, " +
                "localMsgId=${result.localMsgId}, relationInserted=$relationInserted",
        )
        if (relationInserted == false) {
            WeLogger.w(TAG, "sendNativeQuote: message accepted without MsgQuote relation")
        }
        return accepted
    }

    fun sendQuoteText(talker: String, quotedMsgSvrId: Long, content: String): Boolean {
        return try {
            WeLogger.i(
                TAG,
                "sendQuoteText request: destination=$talker, " +
                    "quotedMsgSvrId=$quotedMsgSvrId, contentLength=${content.length}",
            )
            if (quotedMsgSvrId <= 0L) {
                WeLogger.w(TAG, "sendQuoteText: invalid quotedMsgSvrId=$quotedMsgSvrId")
                return false
            }
            val quoted = getMsgInfoInstanceByMsgSvrId(quotedMsgSvrId)
            val quotedInfo = MessageInfo(quoted)
            if (quotedInfo.id <= 0L || quotedInfo.serverId != quotedMsgSvrId || quotedInfo.talker.isEmpty()) {
                WeLogger.w(
                    TAG,
                    "sendQuoteText: source not found for quotedMsgSvrId=$quotedMsgSvrId",
                )
                return false
            }
            val accepted = sendNativeQuote(talker, content, quotedInfo)
            WeLogger.i(
                TAG,
                "sendQuoteText: destination=$talker, quotedMsgSvrId=$quotedMsgSvrId, " +
                    "sourceMsgId=${quotedInfo.id}, accepted=$accepted",
            )
            accepted
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendQuoteText failed", e)
            false
        }
    }

    fun sendQuoteTextByMsgId(talker: String, quotedMsgId: Long, content: String): Boolean {
        return try {
            WeLogger.i(
                TAG,
                "sendQuoteTextByMsgId request: destination=$talker, " +
                    "quotedMsgId=$quotedMsgId, contentLength=${content.length}",
            )
            if (quotedMsgId <= 0L) {
                WeLogger.w(TAG, "sendQuoteTextByMsgId: invalid quotedMsgId=$quotedMsgId")
                return false
            }
            val quotedInfo = getMsgInfoByMsgId(quotedMsgId)
            if (quotedInfo == null || quotedInfo.id <= 0L || quotedInfo.talker.isEmpty()) {
                WeLogger.w(TAG, "sendQuoteTextByMsgId: source not found for quotedMsgId=$quotedMsgId")
                return false
            }
            val accepted = sendNativeQuote(talker, content, quotedInfo)
            WeLogger.i(
                TAG,
                "sendQuoteTextByMsgId: destination=$talker, sourceMsgId=$quotedMsgId, " +
                    "accepted=$accepted",
            )
            accepted
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendQuoteTextByMsgId failed", e)
            false
        }
    }

    fun sendQuoteMsgByMsgId(talker: String, msgId: Long, content: String): Boolean {
        return sendQuoteTextByMsgId(talker, msgId, content)
    }

    fun sendQuoteMsgByMsgSvrId(talker: String, msgSvrId: Long, content: String): Boolean {
        return sendQuoteText(talker, msgSvrId, content)
    }

    /**
     * Sends a local sticker using the same format routing as FunBox's DLC/CVm/GFh path.
     * WeChat's direct EmojiInfo upload accepts GIF and bounded PNG, but raw WebP can leave
     * the resulting type-47 message permanently pending.
     */
    fun sendSticker(toUser: String, path: String): Boolean {
        return runCatching {
            val source = path.asPath
            require(source.isRegularFile()) { "sticker source does not exist" }

            cleanupStickerSendCache()
            val info = inspectStickerFile(source)
            WeLogger.i(
                TAG,
                "sticker inspected: format=${info.format}, bytes=${info.byteCount}, " +
                        "dimensions=${info.width}x${info.height}"
            )

            when (info.format) {
                StickerFileFormat.GIF -> {
                    WeLogger.i(TAG, "sticker send route: direct emoji (GIF)")
                    sendEmoji(toUser, path)
                }

                StickerFileFormat.PNG -> {
                    if (info.fitsDirectEmoji()) {
                        WeLogger.i(TAG, "sticker send route: direct emoji (PNG)")
                        sendEmoji(toUser, path)
                    } else {
                        sendStickerAsImage(toUser, source, "PNG dimensions exceed direct emoji limit")
                    }
                }

                StickerFileFormat.WEBP -> {
                    if (!info.fitsDirectEmoji()) {
                        sendStickerAsImage(toUser, source, "WebP dimensions exceed direct emoji limit")
                    } else {
                        val converted = createStickerSendTemp("png")
                        try {
                            convertStickerToPng(source, converted)
                            WeLogger.i(
                                TAG,
                                "sticker converted: WEBP to PNG, bytes=${converted.fileSize()}"
                            )
                            WeLogger.i(TAG, "sticker send route: direct emoji (converted PNG)")
                            sendEmoji(toUser, converted.absolutePathString())
                        } finally {
                            converted.deleteIfExists()
                        }
                    }
                }

                StickerFileFormat.WXGF -> {
                    val converted = createStickerSendTemp("gif")
                    try {
                        convertWxgfToGif(source, converted)
                        WeLogger.i(
                            TAG,
                            "sticker converted: WXGF to GIF, bytes=${converted.fileSize()}"
                        )
                        WeLogger.i(TAG, "sticker send route: direct emoji (converted GIF)")
                        sendEmoji(toUser, converted.absolutePathString())
                    } finally {
                        converted.deleteIfExists()
                    }
                }

                StickerFileFormat.JPEG,
                StickerFileFormat.OTHER -> sendStickerAsImage(
                    toUser,
                    source,
                    "format is not supported by direct emoji upload"
                )
            }
        }.getOrElse {
            WeLogger.e(TAG, "failed to send sticker", it)
            false
        }
    }

    fun sendEmoji(toUser: String, path: String): Boolean {
        return runCatching {
            val md5 = WeServiceApi.processEmojiPath(path)
            val emojiThumb = WeServiceApi.saveEmojiThumb(md5)

            WeLogger.i(
                TAG,
                "emoji imported: type=${emojiThumb.intField("field_type")}, " +
                        "size=${emojiThumb.intField("field_size")}, " +
                        "start=${emojiThumb.intField("field_start")}, " +
                        "reserved4=${emojiThumb.intField("field_reserved4")}"
            )

            val sendMethod = WeServiceApi.emojiMgrImpl.reflekt().firstMethod {
                parameters {
                    it[0] == BString &&
                            it[1] == WeServiceApi.methodSaveEmojiThumb.method.declaringClass &&
                            it[2] == classMsgInfo.clazz
                }
                returnType = void
            }

            val paramCount = sendMethod.self.parameterCount
            if (paramCount == 4) {
                sendMethod.invoke(toUser, emojiThumb, null, null)
            } else if (paramCount != 5) {
                sendMethod.invoke(toUser, emojiThumb, null)
            } else {
                sendMethod.invoke(toUser, emojiThumb, null, null, 0)
            }

            true
        }.getOrElse {
            WeLogger.e(TAG, "failed to send emoji by path", it)
            false
        }
    }

    private fun inspectStickerFile(path: Path): StickerFileInfo {
        val header = ByteArray(12)
        val headerSize = Files.newInputStream(path).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            offset
        }

        val format = when {
            headerSize >= 3 && header.hasMagic(0, 0x47, 0x49, 0x46) -> StickerFileFormat.GIF
            headerSize >= 8 && header.hasMagic(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> StickerFileFormat.PNG
            headerSize >= 12 &&
                    header.hasMagic(0, 0x52, 0x49, 0x46, 0x46) &&
                    header.hasMagic(8, 0x57, 0x45, 0x42, 0x50) -> StickerFileFormat.WEBP

            headerSize >= 3 && header.hasMagic(0, 0xff, 0xd8, 0xff) -> StickerFileFormat.JPEG
            isWxgf(path) -> StickerFileFormat.WXGF
            else -> StickerFileFormat.OTHER
        }

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (format != StickerFileFormat.WXGF) {
            BitmapFactory.decodeFile(path.absolutePathString(), options)
        }
        return StickerFileInfo(format, path.fileSize(), options.outWidth, options.outHeight)
    }

    private fun StickerFileInfo.fitsDirectEmoji(): Boolean =
        width in 1..MAX_EMOJI_DIMENSION && height in 1..MAX_EMOJI_DIMENSION

    private fun ByteArray.hasMagic(offset: Int, vararg expected: Int): Boolean =
        expected.indices.all { this[offset + it].toInt() and 0xff == expected[it] }

    private fun isWxgf(path: Path): Boolean = runCatching {
        val bytes = path.readBytes()
        bytes.isNotEmpty() && MMWXGFJNI.isWxGF(bytes, bytes.size)
    }.getOrElse {
        WeLogger.w(TAG, "WXGF detection failed", it)
        false
    }

    private fun convertStickerToPng(source: Path, output: Path) {
        val bitmap = BitmapFactory.decodeFile(source.absolutePathString())
            ?: error("failed to decode sticker bitmap")
        try {
            require(bitmap.width in 1..MAX_EMOJI_DIMENSION && bitmap.height in 1..MAX_EMOJI_DIMENSION) {
                "sticker dimensions exceed direct emoji limit"
            }
            output.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "failed to encode sticker as PNG"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun convertWxgfToGif(source: Path, output: Path) {
        val converted = MMWXGFJNI.nativeWxamToGif(source.readBytes())
        require(converted.size >= 3 && converted.hasMagic(0, 0x47, 0x49, 0x46)) {
            "failed to decode WXGF sticker"
        }
        output.writeBytes(converted)
    }

    private fun createStickerSendTemp(extension: String): Path =
        KnownPaths.moduleCache / "sticker-send-converted-${UUID.randomUUID()}.$extension"

    private fun sendStickerAsImage(toUser: String, source: Path, reason: String): Boolean {
        val extension = source.name.substringAfterLast('.', "img")
            .lowercase()
            .takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
            ?: "img"
        val retained = KnownPaths.moduleCache /
                "sticker-send-image-${System.currentTimeMillis()}-${UUID.randomUUID()}.$extension"
        Files.copy(source, retained, StandardCopyOption.REPLACE_EXISTING)
        WeLogger.i(TAG, "sticker send route: ordinary image ($reason), source retained in cache")
        return sendImage(toUser, retained.absolutePathString()).also { success ->
            if (!success) retained.deleteIfExists()
        }
    }

    private fun cleanupStickerSendCache() {
        val cutoff = System.currentTimeMillis() - STICKER_SEND_CACHE_MAX_AGE_MS
        runCatching {
            Files.list(KnownPaths.moduleCache).use { paths ->
                paths.filter {
                    it.isRegularFile() &&
                            it.name.startsWith("sticker-send-") &&
                            Files.getLastModifiedTime(it).toMillis() < cutoff
                }.forEach(Path::deleteIfExists)
            }
        }.onFailure { WeLogger.w(TAG, "failed to clean stale sticker send cache", it) }
    }

    private fun Any.intField(name: String): Int? = runCatching {
        reflekt().firstField { this.name = name; superclass() }.get() as? Int
    }.getOrNull()

    fun sendEmojiByMd5(toUser: String, md5: String): Boolean {
        return runCatching {
            WeLogger.i(TAG, "sending registered emoji")
            val emojiInfo = WeServiceApi.getEmojiInfoByMd5(md5)

            val sendMethod = WeServiceApi.emojiMgrImpl.reflekt().firstMethod {
                parameters {
                    it[0] == BString &&
                            it[1] == WeServiceApi.methodSaveEmojiThumb.method.declaringClass &&
                            it[2] == classMsgInfo.clazz
                }
                returnType = void
            }

            val paramCount = sendMethod.self.parameterCount
            if (paramCount == 4) {
                sendMethod.invoke(toUser, emojiInfo, null, null)
            } else if (paramCount != 5) {
                sendMethod.invoke(toUser, emojiInfo, null)
            } else {
                sendMethod.invoke(toUser, emojiInfo, null, null, 0)
            }

            true
        }.getOrElse {
            WeLogger.e(TAG, "failed to send registered emoji", it)
            false
        }
    }

    fun sendPat(toUser: String, patTargetWxId: String): Boolean {
        return try {
            WeLogger.i(TAG, "sending pat to $patTargetWxId in $toUser")
            // Get PatMsgExtension service instance (C1387 ≈ C1104.m2574 → classPatMsgExtension)
            val patService = WeServiceApi.getServiceByClass(classPatMsgExtension.clazz)
            // First reflection: find method returning String with 2 String params → m1650(patTarget, talker)
            val strMethod = patService.reflekt()
                .firstMethod { parameters(String::class, String::class); returnType = String::class }
                .self
            val str11 = strMethod.invoke(patService, patTargetWxId, toUser) as String
            // timestamp = (int)(System.currentTimeMillis() / 1000)
            val timestamp = (System.currentTimeMillis() / 1000).toInt()
            // Second reflection: find method returning Pair with 6 params → m1650(talker, selfWxId, patTarget, str11, timestamp, 0L)
            val pairMethod = patService.reflekt()
                .firstMethod {
                    parameters(String::class, String::class, String::class, String::class, Int::class.java, Long::class.java)
                    returnType = android.util.Pair::class.java
                }
                .self
            val pair = pairMethod.invoke(patService, toUser, WeApi.selfWxId, patTargetWxId, str11, timestamp, 0L)
            // Dispatch via background thread
            thread {
                try {
                    val netScene = ctorNetSceneSendPat.newInstance(pair, toUser, patTargetWxId, 0)
                    WeNetSceneApi.sendNetScene(netScene)
                } catch (e: Exception) {
                    WeLogger.e(TAG, "sendPat background task failed", e)
                }
            }
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendPat failed", e); false
        }
    }

    fun sendLocation(toUser: String, poiName: String, label: String, x: String, y: String, scale: String): Boolean {
        return try {
            WeLogger.i(TAG, "sending location: $x,$y to $toUser")
            val locJson = """{"msg":{"location":{"poiname":"$poiName","label":"$label","x":"$x","y":"$y","scale":"$scale"}}}"""
            val netScene = ctorNetSceneSendMsgLocation.newInstance(toUser, locJson, 1, 0, null)
            WeNetSceneApi.sendNetScene(netScene)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendLocation failed", e); false
        }
    }

    fun sendShareCard(toUser: String, cardWxId: String): Boolean {
        return try {
            WeLogger.i(TAG, "sending share card $cardWxId to $toUser")
            val json1 = JSONObject()
            val json2 = JSONObject()
            json2.put("username", cardWxId)
            val nickname = WeDatabaseApi.getDisplayName(cardWxId)
            json2.put("nickname", nickname)
            json2.put("certflag", if (cardWxId.startsWith("gh_")) 4928270286903575946L else 4928270274018674058L)
            json1.put("msg", json2)
            val netScene = ctorNetSceneSendMsgLocation.newInstance(toUser, json1.toString(), 1, 0, null)
            WeNetSceneApi.sendNetScene(netScene)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendShareCard failed", e); false
        }
    }

    fun sendVideo(toUser: String, videoPath: String): Boolean {
        return try {
            WeLogger.i(TAG, "sending video: $videoPath to $toUser")
            val thread = classImportMultiVideo.clazz.createInstance(
                HostInfo.application,
                java.util.Collections.singletonList(videoPath),
                null, toUser, 2, null, java.lang.Boolean.TRUE
            ) as Thread
            thread.start()
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendVideo failed", e); false
        }
    }

    override fun onEnable() {
        classVfs.reflekt().apply {
            vfsReadMethod = firstMethod {
                modifiers(Modifiers.STATIC)
                parameters(String::class)
                returnType = InputStream::class
            }.self

            vfsCopyMethod = firstMethod {
                modifiers(Modifiers.STATIC)
                parameters(String::class, Boolean::class)
                returnType = OutputStream::class
            }.self

            vfsExistsMethod = firstMethod {
                modifiers(Modifiers.STATIC)
                parameters(String::class)
                returnType = Boolean::class
            }.self
        }

        classVoiceParams.reflekt().apply {
            val intFields = fields { type = Int::class }
            voiceDurationField = intFields[0].self
            voiceOffsetField = intFields[1].self
        }
    }

    /**
     * 动态解析 AccPath 获取方法
     */
    private fun getAccPath(): String {
        val storageObj = WeDatabaseApi.methodGetStorage.method.invoke(null)
            ?: error("Kernel.getStorage() failed (returned null)")

        if (storageAccPathMethod != null) {
            return storageAccPathMethod!!.invoke(storageObj) as String
        }

        WeLogger.i(TAG, "resolving AccPath method... StorageClass=${storageObj.javaClass.name}")

        var currentClass: Class<*>? = storageObj.javaClass
        var scanCount = 0

        // 递归扫描类继承链
        while (currentClass != null && currentClass != Any::class.java) {
            val methods = currentClass.declaredMethods.filter {
                it.parameterCount == 0 && it.returnType == String::class.java
            }
            scanCount += methods.size

            for (m in methods) {
                try {
                    // 排除干扰项
                    if (m.name == "toString") continue

                    m.makeAccessible()
                    val result = m.invoke(storageObj) as? String

                    // 特征校验：包含 "MicroMsg" 且以 "/" 结尾
                    if (result != null && result.contains("MicroMsg") && result.endsWith("/")) {
                        storageAccPathMethod = m
                        WeLogger.i(TAG, "resolved AccPath method: ${m.name}, path: $result")
                        return result
                    }
                } catch (_: Throwable) {
                    // ignore
                }
            }
            // 向上查找父类
            currentClass = currentClass.superclass
        }

        error("failed to resolve AccPath method (scanned $scanCount candidates, StorageClass=${storageObj.javaClass.name})")
    }

    /** 发送图片消息 */
    fun sendImage(toUser: String, imgPath: String): Boolean {
        return try {
            val serviceObj = WeServiceApi.getServiceByClass(imageServiceApiClass)

            val paramsObj = crossParamsClass.createInstance()
            paramsObj.reflekt()
                .firstField { type = int }
                .set(4)

            val taskObj = classImageTask.clazz.createInstance(
                imgPath,
                0,
                selfCustomWxId,
                toUser,
                paramsObj
            )
            taskObj.reflekt()
                .lastField { type = String::class }
                .set("media_generate_send_img")

            sendImageMethod.invoke(serviceObj, taskObj)

            WeLogger.i(TAG, "image send task submitted")
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to send image message", e)
            false
        }
    }

    private val methodImgUploadFeatureServiceNewPathProbe by dexMethod()
    private val methodImgUploadFeatureServiceSendImage by dexMethod()
    private val methodAppInfoSetAppId by dexMethod()
    private val ctorNetSceneUploadMsgImg by dexConstructor()

    fun sendImageByMd5(toUser: String, md5: String, appMsgAppId: String? = null) {
        if (!methodImgUploadFeatureServiceNewPathProbe.isPlaceholder) {
            val sendImageMethod = methodImgUploadFeatureServiceSendImage.method
            val paramsClass = sendImageMethod.parameterTypes[0]
            val crossParamsClass = paramsClass.reflekt()
                .firstField { type { !it.isBuiltin } }.self.type
            val crossParams = crossParamsClass.createInstance()

            if (appMsgAppId != null) {
                val appInfoClass = methodAppInfoSetAppId.method.declaringClass
                val appInfo = appInfoClass.createInstance()
                methodAppInfoSetAppId.method.invoke(appInfo, appMsgAppId)
                crossParams.reflekt()
                    .firstField {
                        type = appInfoClass
                    }.set(appInfo)
            }

            val params = paramsClass.createInstance(md5, 1, WeApi.selfWxId, toUser, crossParams)
            sendImageMethod.invoke(WeServiceApi.getServiceByClass(sendImageMethod.declaringClass), params)
        } else {
            val xml: String?
            val wxId = WeApi.selfWxId
            if (appMsgAppId != null) {
                val json = JSONObject()
                val json2 = JSONObject()
                val json3 = JSONObject()
                json3.put("appid", appMsgAppId)
                json2.put("appinfo", json3)
                json.put("msg", json2)
                val converter = JsonToXmlConverter(json, emptyHashSet(), emptyHashSet())
                xml = converter.toString()
            } else {
                xml = null
            }
            WeNetSceneApi.sendNetScene(
                ctorNetSceneUploadMsgImg.newInstance(4, wxId, toUser, md5, 1, null, 0, xml, "", true, 0)
            )
        }
    }

    /** 发送文本消息 */
    fun sendText(toUser: String, text: String): Boolean {
        return try {
            WeLogger.i(TAG, "sending text message: $text")
            val sendMsgObject = methodGetSendMsgObject.method.invoke(null) ?: return false
            val msgObj = classNetSceneSendMsg.clazz.createInstance(toUser, text, 1, 0, null)
            methodPostToQueue.method.invoke(sendMsgObject, msgObj) as? Boolean ?: false
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to send text message", e)
            false
        }
    }

    /** 发送文件消息 */
    fun sendFile(talker: String, filePath: String, title: String, appId: String? = null): Boolean {
        return try {
            WeLogger.i(TAG, "sending file message: $filePath")
            val fileObject = WXFileObject()
            fileObject.filePath = filePath
            val mediaMessage = WXMediaMessage()
            mediaMessage.mediaObject = fileObject
            mediaMessage.title = title
            methodShareFile.method.invoke(null, mediaMessage, appId ?: "", "", talker, 2, null)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to send file message", e)
            false
        }
    }

    fun getVoiceFullPath(encPath: String): String {
        val m = methodGetAmrFullPath.method
        var service: Any? = null
        if (!Modifier.isStatic(m.modifiers)) {
            service = WeServiceApi.getServiceByClass(m.declaringClass)
        }
        return methodGetAmrFullPath.method.invoke(service, null, encPath, true) as String
    }

    // WeChat marks a received voice message as read (clearing the unplayed red dot) by
    // flipping the played flag inside the voice content string and persisting it — see
    // w21.x0.q(MsgInfo) (classVoiceLogic), the only static void(MsgInfo) method in that class,
    // invoked from AutoPlay.startPlay before playback. We call it directly so features that
    // consume a voice message without going through playback (e.g. auto speech-to-text) can
    // clear the red dot the same way WeChat would.
    fun markVoicePlayed(msgInfo: MessageInfo) {
        classVoiceLogic.clazz.reflekt()
            .firstMethod {
                parameters(classMsgInfo.clazz)
                returnType = void
                modifiers(Modifiers.STATIC)
            }.invokeStatic(msgInfo.instance)
    }

    fun sendVoice(toUser: String, path: String, durationMs: Int): Boolean {
        var succeeded = runCatching {
//             // 尝试通过 ServiceManager 获取
//             var finalServiceObj: Any? = null
//             if (getServiceMethod != null) {
//                 try {
//                     finalServiceObj = getServiceMethod!!.invoke(null, classVoiceServiceInterface.clazz)
//                 } catch (e: Exception) {
//                     WeLogger.e(TAG, "failed to retrieve ServiceManager, trying singleton fallback", e)
//                 }
//             }
//
//             // 尝试单例 Fallback
//             if (finalServiceObj == null) {
//                 val implClass = classVoiceServiceImpl.clazz
//                 val instanceField = implClass.declaredFields.find {
//                     it.name == "INSTANCE" || it.type == implClass
//                 }
//                 if (instanceField != null) {
//                     instanceField.makeAccessible()
//                     finalServiceObj = instanceField.get(null)
//                 }
//             }
//
//             if (finalServiceObj == null) error("failed to retrieve VoiceService instance")
//
//             // 准备文件
//             val fileName = voiceNameGenMethod.invoke(null, selfCustomWxId, "amr_") as? String
//                 ?: error("VoiceName Gen Failed")
//             val accPath = getAccPath()
//             val voice2Root = if (accPath.endsWith("/")) "${accPath}voice2/" else "$accPath/voice2/"
//             val destFullPath =
//                 pathGenMethod.invoke(null, voice2Root, "msg_", fileName, ".amr", 2) as? String
//                     ?: error("Path Gen Failed")
//
//             if (!copyFileViaVfs(path, destFullPath)) return false
//
//             // 构造任务
//             val paramsObj = classVoiceParams.clazz.createInstance(toUser, fileName)
//             voiceDurationField.set(paramsObj, durationMs)
//             voiceOffsetField.set(paramsObj, 0)
//
//             val taskObj = voiceTaskConstructor.newInstance(paramsObj)
//                 ?: error("failed to construct voice task")
//
//             methodSendVoice.method.invoke(finalServiceObj, taskObj)
//             WeLogger.i(TAG, "sent voice (Service method): $fileName")

            // 准备文件
            val fileName = voiceNameGenMethod.invoke(getReceiverForMethod(voiceNameGenMethod), toUser, "amr_") as? String
                ?: error("failed to generate voice name")
            val accPath = getAccPath()
            val voice2Root = if (accPath.endsWith("/")) "${accPath}voice2/" else "$accPath/voice2/"
            val destFullPath =
                pathGenMethod.invoke(null, voice2Root, "msg_", fileName, ".amr", 2) as? String
                    ?: error("failed to generate path")

            if (!copyFileViaVfs(path, destFullPath)) return false

            // 设置语音信息
            val finalDurationMs = durationMs.coerceIn(1, 60_000)
            val setVoiceReceiver = getReceiverForMethod(setVoiceMethod)
            val setVoiceResult = if (setVoiceMethod.parameterCount == 4) {
                setVoiceMethod.invoke(setVoiceReceiver, fileName, finalDurationMs, 0, null)
            } else {
                setVoiceMethod.invoke(setVoiceReceiver, fileName, finalDurationMs, 0)
            } as? Boolean ?: false

            if (!setVoiceResult) {
                WeLogger.w(TAG, "VoiceLogic.setVoice returned false, still starting voice service: fileName=$fileName, target=$toUser")
            }

            startVoiceService()
        }.onFailure { WeLogger.e(TAG, "failed to send voice (Service method)", it) }.isSuccess

        if (succeeded) return true

        succeeded = runCatching {
            val partialPath = classVoiceLogic.reflekt()
                .firstMethod {
                    parameters(BString, BString)
                    returnType = BString
                }
                .invokeStatic(toUser, "amr_") as String
            val fullPath = getVoiceFullPath(partialPath)

            Files.copy(Path(path), Path(fullPath), StandardCopyOption.REPLACE_EXISTING)

            val actualDuration = if (durationMs > 60000) 60000 else durationMs

            val target = classVoiceLogic.clazz.reflekt()
                .firstMethod {
                    parameters {
                        it[0] == BString && it[1] == int && it[2] == int
                    }
                    returnType = bool
                }.self
            if (target.parameterCount == 4) {
                target.invoke(null, partialPath, actualDuration, 0, null)
            } else {
                target.invoke(null, partialPath, actualDuration, 0)
            }

            val service = classSceneVoiceService.clazz.reflekt()
                .firstMethod {
                    returnType = methodStartRecvAndSend.method.declaringClass
                    modifiers(Modifiers.STATIC)
                }.invokeStatic()!!

            val runMethod = runCatching {
                if (methodRunVoiceService.isPlaceholder) return@runCatching null
                methodRunVoiceService.method.makeAccessible()
            }.onFailure {
                WeLogger.w(TAG, "failed to load SceneVoiceService.run, fallback to startRecvAndSend", it)
            }.getOrNull()

            if (runMethod != null) {
                runMethod.invoke(service)
            } else {
                methodStartRecvAndSend.method.invoke(getReceiverForMethod(methodStartRecvAndSend.method), service)
            }

            WeLogger.i(TAG, "sent voice (WAuxv method): $fullPath")
        }.onFailure { WeLogger.e(TAG, "failed to send voice (WAuxv method)", it) }.isSuccess

        return succeeded
    }

    private fun getReceiverForMethod(method: Method): Any? {
        return if (Modifier.isStatic(method.modifiers)) {
            null
        } else {
            WeServiceApi.getServiceByClass(method.declaringClass)
        }
    }

    private fun startVoiceService() {
        runCatching {
            // 获取 SceneVoiceService 实例
            val serviceType = methodStartRecvAndSend.method.declaringClass

            val getServiceMethod = classSceneVoiceService.reflekt().firstMethod {
                modifiers(Modifiers.STATIC)
                parameters()
                returnType = serviceType
            }.self

            val service = getServiceMethod.invoke(getReceiverForMethod(getServiceMethod))
                ?: error("SceneVoiceService.getVoiceService returned null")

            // 优先调用 run()：它会在服务自身的 looper 线程上派发发送任务。
            // 直接在 IO 线程调用 startRecvAndSend 会因构造 Handler 时无 Looper
            // 抛出 "looper and serial is null!"，导致发送卡死转圈，必须重启微信。
            val runMethod = runCatching {
                if (methodRunVoiceService.isPlaceholder) return@runCatching null
                methodRunVoiceService.method.makeAccessible()
            }.onFailure {
                WeLogger.w(TAG, "failed to load SceneVoiceService.run, fallback to startRecvAndSend", it)
            }.getOrNull()

            if (runMethod != null) {
                runMethod.invoke(service)
            } else {
                methodStartRecvAndSend.method.invoke(getReceiverForMethod(methodStartRecvAndSend.method), service)
            }
            WeLogger.d(TAG, "voice service started successfully")
        }.onFailure {
            WeLogger.e(TAG, "failed to start voice service", it)
        }
    }

    fun sendXmlAppMsg(target: String, xmlContent: String): Boolean {
        val appId = extractXmlAttr(xmlContent, "appid")
        val title = extractXmlTag(xmlContent, "title")

        WeLogger.d(TAG, "appmsg info: appid=$appId, title=$title")
        return WeAppMsgApi.sendXmlAppMsg(target, title, appId, null, null, xmlContent)
    }

    /**
     * 使用微信内部 VFS 引擎进行物理拷贝
     */
    private fun copyFileViaVfs(src: String, dst: String): Boolean {
        WeLogger.d(TAG, "VFS Copy: $src -> $dst")
        return try {
            val input = vfsReadMethod.invoke(null, src) as? InputStream
                ?: error("VFS Open Failed for $src")

            val output = vfsCopyMethod.invoke(null, dst, false) as? OutputStream
                ?: error("VFS Create Failed for $dst")

            input.use { i ->
                output.use { o ->
                    i.copyTo(o)
                }
            }

            // 校验
            val exists = vfsExistsMethod.invoke(null, dst) as? Boolean ?: false
            if (exists) {
                WeLogger.i(TAG, "VFS copy succeeded")
            } else {
                WeLogger.e(TAG, "VFS copy seems successful but actually failed")
            }
            exists
        } catch (e: Exception) {
            WeLogger.e(TAG, "VFS copy failed", e)
            false
        }
    }

    private fun readFileViaVfs(path: String): ByteArray? {
        return (vfsReadMethod.invoke(null, path) as? InputStream)?.use { it.readBytes() }
    }

    fun shareWebpage(
        talker: String,
        title: String,
        description: String,
        webpageUrl: String,
        thumbData: ByteArray?,
        appId: String? = null
    ): Boolean {
        return try {
            val mediaObject = WXWebpageObject()
            mediaObject.webpageUrl = webpageUrl
            val mediaMessage = WXMediaMessage().apply {
                this.title = title
                this.description = description
                this.thumbData = thumbData
                this.mediaObject = mediaObject
            }
            methodShareFile.method.invoke(
                null,
                mediaMessage,
                appId ?: "",
                "",
                talker,
                3,
                null
            )
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "shareWebpage failed", e)
            false
        }
    }

    fun shareVideo(
        talker: String,
        title: String,
        description: String,
        videoUrl: String,
        thumbData: ByteArray?,
        appId: String? = null
    ): Boolean {
        return try {
            val mediaObject = WXVideoObject()
            mediaObject.videoUrl = videoUrl
            val mediaMessage = WXMediaMessage().apply {
                this.title = title
                this.description = description
                this.thumbData = thumbData
                this.mediaObject = mediaObject
            }
            methodShareFile.method.invoke(
                null,
                mediaMessage,
                appId ?: "",
                "",
                talker,
                3,
                null
            )
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "shareVideo failed", e)
            false
        }
    }

    fun shareText(
        talker: String,
        text: String,
        appId: String? = null
    ): Boolean {
        return try {
            val mediaObject = WXTextObject()
            mediaObject.text = text
            val mediaMessage = WXMediaMessage()
            mediaMessage.mediaObject = mediaObject
            methodShareFile.method.invoke(
                null,
                mediaMessage,
                appId ?: "",
                "",
                talker,
                3,
                null
            )
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "shareText failed", e)
            false
        }
    }

    fun shareMusic(
        talker: String,
        title: String,
        description: String,
        musicUrl: String,
        musicDataUrl: String,
        thumbData: ByteArray?,
        appId: String? = null
    ): Boolean {
        return try {
            val mediaObject = WXMusicObject().apply {
                this.musicUrl = musicUrl
                this.musicDataUrl = musicDataUrl
            }
            val mediaMessage = WXMediaMessage().apply {
                this.title = title
                this.description = description
                this.thumbData = thumbData
                this.mediaObject = mediaObject
            }
            methodShareFile.method.invoke(
                null,
                mediaMessage,
                appId ?: "",
                "",
                talker,
                3,
                null
            )
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "shareMusic failed", e)
            false
        }
    }

    fun shareMusicVideo(
        talker: String,
        title: String,
        description: String,
        musicUrl: String,
        musicDataUrl: String,
        singerName: String,
        duration: Int,
        songLyric: String,
        thumbData: ByteArray?,
        appId: String? = null
    ): Boolean {
        return try {
            val mediaObject = WXMusicVideoObject().apply {
                this.musicUrl = musicUrl
                this.musicDataUrl = musicDataUrl
                this.singerName = singerName
                this.duration = duration
                this.songLyric = songLyric
            }
            val mediaMessage = WXMediaMessage().apply {
                this.title = title
                this.description = description
                this.thumbData = thumbData
                this.mediaObject = mediaObject
            }
            methodShareFile.method.invoke(
                null,
                mediaMessage,
                appId ?: "",
                "",
                talker,
                3,
                null
            )
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "shareMusicVideo failed", e)
            false
        }
    }

    fun shareMiniProgram(
        talker: String,
        title: String,
        description: String,
        userName: String,
        path: String,
        thumbData: ByteArray?,
        appId: String? = null
    ): Boolean {
        return try {
            val mediaObject = WXMiniProgramObject().apply {
                webpageUrl = "https://github.com"
                this.userName = userName
                this.path = path
            }
            val mediaMessage = WXMediaMessage().apply {
                this.title = title
                this.description = description
                this.thumbData = thumbData
                this.mediaObject = mediaObject
            }
            methodShareFile.method.invoke(
                null,
                mediaMessage,
                appId ?: "",
                "",
                talker,
                3,
                null
            )
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "shareMiniProgram failed", e)
            false
        }
    }

    fun sendMediaMsg(talker: String, mediaMessage: Any, appId: String?): Boolean {
        return try {
            methodShareFile.method.invoke(
                null,
                mediaMessage,
                appId ?: "",
                "",
                talker,
                3,
                null
            )
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendMediaMsg failed", e)
            false
        }
    }

    val selfCustomWxId: String
        get() {
            return getSelfAliasMethod.invoke(null) as? String ?: ""
        }

    fun getMsgInfoFromTag(tag: Any): Any {
        val mGetMsgInfo = tag.reflekt()
            .firstMethodOrNull {
                returnType = classMsgInfo.clazz
                parameterCount(0)
                superclass()
            }

        return if (mGetMsgInfo != null) {
            mGetMsgInfo.invoke()!!
        } else {
            tag.reflekt()
                .firstField {
                    type = classMsgInfo.clazz
                    superclass()
                }.get()!!
        }
    }

    // ---- 媒体下载/保存 ----

    private val classEmojiFileEncryptMgr by dexClass {
        matcher {
            methods {
                add {
                    usingEqStrings(
                        "MicroMsg.emoji.EmojiFileEncryptMgr",
                        "decode emoji file failed. path is no exist :%s "
                    )
                }
            }
        }
    }

    internal val methodLoadEmojiFile by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.EmojiLoader", "load emoji file ")
            paramTypes("com.tencent.mm.storage.emotion.EmojiInfo", "boolean", null)
        }
    }

    // com.tencent.mm.pluginsdk.model.app 里的 "自动下载文件" Runnable (8069 为 b0, 8074 为 c0),
    // 构造参数为一个 MsgInfo, run() 会解析 appmsg XML、创建 appattach 行并向 CDN 发起下载任务。
    // 这正是微信里点击文件气泡"下载/缓存"所走的逻辑。
    private val classAppAttachAutoDownload by dexClass {
        searchPackages("com.tencent.mm.pluginsdk.model.app")
        matcher {
            methods {
                add {
                    usingEqStrings("MicroMsg.AppMessageExtension", "autoDownloadFile2 %s %s")
                }
            }
        }
    }

    /** 从 message 表读取指定 msgSvrId 消息的 imgPath 字段 (贴纸为 md5, 语音为加密路径)。 */
    private fun queryImgPathByMsgSvrId(msgSvrId: Long): String? {
        val rows = WeDatabaseApi.rawQuery(
            "SELECT imgPath FROM message WHERE msgSvrId=?",
            arrayOf(msgSvrId.toString())
        )
        return rows.use { rows ->
            if (!rows.moveToFirst()) null else rows.getString(0)?.takeIf { it.isNotEmpty() }
        }
    }

    private data class ImgInfoRow(
        val localId: Long,
        val talker: String,
        val bigImgPath: String,
        val hevcPath: String?,
        val midImgPath: String?,
        val thumbImgPath: String?,
        val offset: Long,
        val totalLen: Long,
        /** reserved1: 若 > 0, 表示存在"原图"行, 值为原图行的 id (仅基础行有意义)。 */
        val hdImgId: Long,
    ) {
        val isComplete: Boolean get() = offset == totalLen
    }

    private fun ImgInfoRow(cursor: Cursor) = ImgInfoRow(
        localId = cursor.getLong(0),
        talker = cursor.getString(1) ?: "",
        bigImgPath = cursor.getString(2) ?: "",
        hevcPath = cursor.getString(3),
        midImgPath = cursor.getString(4),
        thumbImgPath = cursor.getString(5),
        offset = cursor.getLong(6),
        totalLen = cursor.getLong(7),
        hdImgId = cursor.getLong(8),
    )

    private const val IMG_INFO_COLUMNS =
        "id, msgTalker, bigImgPath, hevcPath, midImgPath, thumbImgPath, offset, totalLen, reserved1"

    private fun queryImgInfoRow(msgSvrId: Long): ImgInfoRow? {
        val rows = WeDatabaseApi.rawQuery(
            "SELECT $IMG_INFO_COLUMNS FROM ImgInfo2 WHERE msgSvrId=?",
            arrayOf(msgSvrId.toString())
        )
        return rows.use { rows ->
            if (!rows.moveToFirst()) null else ImgInfoRow(rows)
        }
    }

    /** 按 id 查 ImgInfo2 行 (用于查找基础行 reserved1 指向的"原图"行)。 */
    private fun queryImgInfoRowById(id: Long): ImgInfoRow? {
        val rows = WeDatabaseApi.rawQuery(
            "SELECT $IMG_INFO_COLUMNS FROM ImgInfo2 WHERE id=?",
            arrayOf(id.toString())
        )
        return rows.use { rows ->
            if (!rows.moveToFirst()) null else ImgInfoRow(rows)
        }
    }

    private fun queryImgInfoRowUntil(
        msgSvrId: Long,
        deadlineElapsedRealtime: Long,
        pollIntervalMillis: Long,
    ): ImgInfoRow? {
        while (SystemClock.elapsedRealtime() < deadlineElapsedRealtime) {
            queryImgInfoRow(msgSvrId)?.let { return it }
            sleepForPoll(deadlineElapsedRealtime, pollIntervalMillis)
        }
        return null
    }

    private fun sleepForPoll(deadlineElapsedRealtime: Long, pollIntervalMillis: Long) {
        val remaining = deadlineElapsedRealtime - SystemClock.elapsedRealtime()
        if (remaining > 0L) SystemClock.sleep(minOf(pollIntervalMillis, remaining))
    }

    private val imageThumbnailPathMethod by lazy {
        WeServiceApi.imageInfoStorage.reflekt().firstMethod {
            parameters {
                it.size == 3 &&
                        it[0] == classMsgInfo.clazz &&
                        it[1].isEnum &&
                        it[2] == String::class.java
            }
            returnType = String::class.java
        }.self
    }

    private val imageThumbnailType by lazy {
        imageThumbnailPathMethod.parameterTypes[1].enumConstants!!.single {
            (it as Enum<*>).name == "THUMB_IMAGE"
        }
    }

    data class NotificationMediaFile(val path: Path, val mimeType: String)

    private fun detectImageMime(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes.hasMagic(0, 0xff, 0xd8, 0xff) -> "image/jpeg"
        bytes.size >= 8 && bytes.hasMagic(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
        bytes.size >= 3 && bytes.hasMagic(0, 0x47, 0x49, 0x46) -> "image/gif"
        bytes.size >= 12 &&
                bytes.hasMagic(0, 0x52, 0x49, 0x46, 0x46) &&
                bytes.hasMagic(8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"

        else -> null
    }

    private fun extensionForImageMime(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> error("unsupported image MIME type: $mimeType")
    }

    private fun detectImageMime(path: Path): String? {
        if (!path.isRegularFile() || path.fileSize() <= 0L) return null
        val header = ByteArray(12)
        val size = Files.newInputStream(path).use { it.read(header) }
        return detectImageMime(if (size == header.size) header else header.copyOf(size.coerceAtLeast(0)))
    }

    private fun reuseNotificationMedia(destination: Path): NotificationMediaFile? {
        val mimeType = detectImageMime(destination) ?: return null
        Files.setLastModifiedTime(destination, FileTime.fromMillis(System.currentTimeMillis()))
        return NotificationMediaFile(destination, mimeType)
    }

    private fun materializeImageBytes(
        sourceBytes: ByteArray,
        destination: Path,
        deadlineElapsedRealtime: Long,
        minimumEdgePixels: Int? = null,
    ): NotificationMediaFile? {
        reuseNotificationMedia(destination)?.let { return it }
        if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtime) return null

        var bytes = MMWXGFJNI.wxam2PicBuf(
            sourceBytes,
            0,
            MMWXGFJNI.WXAM_SCENE_MISC,
        ) ?: sourceBytes
        var mimeType = detectImageMime(bytes) ?: return null
        if (minimumEdgePixels != null && mimeType != "image/gif") {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            try {
                val shortestEdge = minOf(bitmap.width, bitmap.height)
                if (shortestEdge in 1 until minimumEdgePixels) {
                    val scale = minimumEdgePixels.toFloat() / shortestEdge
                    val scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt(),
                        (bitmap.height * scale).toInt(),
                        true,
                    )
                    try {
                        val output = ByteArrayOutputStream()
                        val format = if (mimeType == "image/jpeg") {
                            Bitmap.CompressFormat.JPEG
                        } else {
                            Bitmap.CompressFormat.PNG
                        }
                        check(scaled.compress(format, 90, output)) {
                            "failed to scale notification thumbnail"
                        }
                        bytes = output.toByteArray()
                        mimeType = if (format == Bitmap.CompressFormat.JPEG) {
                            "image/jpeg"
                        } else {
                            "image/png"
                        }
                    } finally {
                        if (scaled !== bitmap) scaled.recycle()
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }
        if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtime) return null
        destination.parent.createDirectories()

        var temporary: Path? = Files.createTempFile(
            destination.parent,
            ".${destination.name}.",
            ".tmp",
        )
        try {
            temporary!!.writeBytes(bytes)
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            temporary = null
            return NotificationMediaFile(destination, mimeType)
        } finally {
            temporary?.deleteIfExists()
        }
    }

    fun materializeNotificationThumbnail(
        message: MessageInfo,
        destination: Path,
        deadlineElapsedRealtime: Long,
    ): NotificationMediaFile? {
        reuseNotificationMedia(destination)?.let { return it }
        var previousPath: String? = null
        var previousBytes: ByteArray? = null

        while (SystemClock.elapsedRealtime() < deadlineElapsedRealtime) {
            val thumbImgPath = queryImgInfoRow(message.serverId)?.thumbImgPath
            if (!thumbImgPath.isNullOrEmpty()) {
                val resolvedPath = imageThumbnailPathMethod.invoke(
                    WeServiceApi.imageInfoStorage,
                    message.instance,
                    imageThumbnailType,
                    thumbImgPath,
                ) as String?
                val sourceBytes = resolvedPath?.let { path ->
                    runCatching { readFileViaVfs(path) }.getOrNull()
                }
                if (sourceBytes != null) {
                    if (resolvedPath == previousPath && sourceBytes.contentEquals(previousBytes)) {
                        materializeImageBytes(
                            sourceBytes,
                            destination,
                            deadlineElapsedRealtime,
                            minimumEdgePixels = (
                                    HostInfo.application.resources.displayMetrics.density *
                                            NOTIFICATION_THUMBNAIL_MIN_EDGE_DP
                                    ).toInt(),
                        )?.let { return it }
                    }
                    previousPath = resolvedPath
                    previousBytes = sourceBytes
                }
            }
            sleepForPoll(deadlineElapsedRealtime, 50L)
        }
        return null
    }

    fun materializeNotificationLargeImage(
        msgSvrId: Long,
        destination: Path,
        deadlineElapsedRealtime: Long,
    ): NotificationMediaFile? {
        reuseNotificationMedia(destination)?.let { return it }
        val source = ensureImageCachedFile(
            msgSvrId,
            deadlineElapsedRealtime,
            pollIntervalMillis = 50L,
        ) ?: return null
        if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtime) return null
        val sourceBytes = runCatching {
            readFileViaVfs(source.absolutePathString())
        }.getOrNull() ?: return null
        return materializeImageBytes(sourceBytes, destination, deadlineElapsedRealtime)
    }

    /**
     * 解析 ImgInfo2 行对应的、已真正落地到 image2/ 的大图文件。
     * 微信可能把大图存为 bigImgPath / hevcPath / midImgPath 其中之一 (原图/HEVC 场景),
     * 因此逐个尝试并要求文件确实存在且非空。
     */
    private fun resolveExistingImageFile(row: ImgInfoRow): Path? {
        if (!row.isComplete) return null
        return listOfNotNull(row.bigImgPath, row.hevcPath, row.midImgPath)
            .filter { it.isNotEmpty() && !it.startsWith("SERVERID://") }
            .firstNotNullOfOrNull { name ->
                resolveImageFile(name)?.takeIf { it.isRegularFile() && it.fileSize() > 0 }
            }
    }

    /**
     * 确保图片已缓存到微信内部 image2/ 存储, 返回真正落地的图片文件。
     *
     * 若消息带"原图" (发送方勾选原图), ImgInfo2 基础行的 reserved1 会指向另一"原图"行,
     * 该行携带原图自己的 CDN key。微信"查看原图"正是对原图行的 localId 触发下载。
     * 因此: 有原图则下载原图行, 否则下载基础行 (即微信压缩过的大图)。
     *
     * 注意: 不能依赖 ImgInfo2.iscomplete —— 该列 DEFAULT 1, 且微信只按 offset<totalLen?0:1 写它,
     * 未下载的行也会读到 1; 微信内部判定完成用的是 totalLen==offset。同理 bigImgPath 被下载服务
     * 在真正写入文件字节 *之前* 就从 SERVERID:// 改写为最终文件名, 所以只能以磁盘上文件是否存在为准。
     */
    private fun ensureImageCachedFile(
        msgSvrId: Long,
        deadlineElapsedRealtime: Long,
        pollIntervalMillis: Long,
    ): Path? {
        val baseRow = queryImgInfoRowUntil(
            msgSvrId,
            deadlineElapsedRealtime,
            pollIntervalMillis,
        ) ?: return null

        // 有原图行则优先下载原图, 否则退回基础行
        val targetRow = if (baseRow.hdImgId > 0L) {
            queryImgInfoRowById(baseRow.hdImgId)
                ?.also {
                    WeLogger.i(
                        TAG,
                        "image has original (hdImgId=${baseRow.hdImgId}), downloading original",
                    )
                }
                ?: baseRow
        } else {
            baseRow
        }

        // 已在磁盘上则直接返回
        resolveExistingImageFile(targetRow)?.let { return it }

        // 触发 CDN 下载, 轮询直到文件真正落地。talker 用基础行的 (原图行可能未存 msgTalker)。
        if (!triggerDownload(targetRow.localId, targetRow.talker.ifEmpty { baseRow.talker })) return null
        return pollUntilImageFileExists(
            targetRow.localId,
            deadlineElapsedRealtime,
            pollIntervalMillis,
        )
    }

    /**
     * 缓存图片: 让微信把大图从 CDN 下载到它自己的 image2/ 存储 (相当于在聊天里点击图片下载)。
     * 缓存与下载分离 —— 此方法只负责把图片缓存到微信内部, 不解码也不拷贝到 Download/WeKit/。
     * @return 缓存后大图在微信内部的绝对路径, 失败返回 null
     */
    fun cacheImage(msgSvrId: Long): String? {
        return try {
            ensureImageCachedFile(
                msgSvrId,
                SystemClock.elapsedRealtime() + 120_000L,
                pollIntervalMillis = 1_000L,
            )?.absolutePathString()
        } catch (e: Exception) {
            WeLogger.e(TAG, "cacheImage failed", e)
            null
        }
    }

    /**
     * 下载图片: 先确保图片已缓存到微信内部, 再进行 WXGF 解码并保存到 Download/WeKit/。
     * @return 保存到 Download/WeKit/ 后的绝对路径, 失败返回 null
     */
    fun downloadImage(msgSvrId: Long): String? {
        return try {
            val file = ensureImageCachedFile(
                msgSvrId,
                SystemClock.elapsedRealtime() + 120_000L,
                pollIntervalMillis = 1_000L,
            ) ?: return null
            decodeAndSave(file)
        } catch (e: Exception) {
            WeLogger.e(TAG, "downloadImage failed", e)
            null
        }
    }

    internal val methodToggleMessageSelection by dexMethod {
        matcher {
            declaredClass(classChattingDataAdapter.data.name)
            usingNumbers(100)
            usingEqStrings("msgIdTalker")
            returnType(bool)
        }
    }

    private fun triggerDownload(imgLocalId: Long, talker: String): Boolean {
        return try {
            val m = WeServiceApi.methodDownloadImageServiceDownloadImage.method
            val downloadService = m.declaringClass.createInstance()

            val msgIdTalker = methodToggleMessageSelection.method.parameterTypes[0]
                .createInstance(imgLocalId, talker)

            val wClass = m.parameterTypes[5]
            val listener = Proxy.newProxyInstance(wClass.classLoader, arrayOf(wClass)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    "toString" -> "WeKitDownloadCallback"
                    else -> null
                }
            }

            val result = m.invoke(downloadService, imgLocalId, msgIdTalker, 0, null, 0, listener, -1, false)
            WeLogger.i(TAG, "triggerDownload result=$result")
            (result as? Int)?.let { it >= 0 } ?: false
        } catch (e: Exception) {
            WeLogger.e(TAG, "triggerDownload failed", e)
            false
        }
    }

    /** 轮询直到该 ImgInfo2 行的图片文件真正落地到磁盘 (以文件存在为准, 而非 iscomplete 标志)。 */
    private fun pollUntilImageFileExists(
        imgLocalId: Long,
        deadlineElapsedRealtime: Long,
        pollIntervalMillis: Long,
    ): Path? {
        while (SystemClock.elapsedRealtime() < deadlineElapsedRealtime) {
            sleepForPoll(deadlineElapsedRealtime, pollIntervalMillis)
            val row = queryImgInfoRowById(imgLocalId) ?: continue
            resolveExistingImageFile(row)?.let { return it }
        }
        return null
    }

    private fun decodeAndSave(file: Path): String? {
        return try {
            val bytes = file.readBytes()
            // 大图可能是 WXGF 编码, 尝试解码; 解码失败则视为普通图片直接使用原字节
            val decoded = MMWXGFJNI.wxam2PicBuf(
                bytes, 0, MMWXGFJNI.WXAM_SCENE_MISC
            ) ?: bytes

            val outDir = KnownPaths.downloads
            val outFile = outDir / file.name
            outFile.deleteIfExists()
            outFile.writeBytes(decoded)
            WeLogger.i(TAG, "saved: ${outFile.absolutePathString()}")
            outFile.absolutePathString()
        } catch (e: Exception) {
            WeLogger.e(TAG, "decodeAndSave failed", e)
            null
        }
    }

    private fun resolveImageFile(bigImgPath: String): Path? {
        // 少数字段可能已是绝对路径, 直接使用
        if (bigImgPath.startsWith("/")) return bigImgPath.asPath
        // image2/xx/yy/<name> 需要文件名至少 4 个字符
        if (bigImgPath.length < 4) return null
        val microMsgPath = HostInfo.application.filesDir.asPath.parent / "MicroMsg"
        val accountDir = microMsgPath.listDirectoryEntries()
            .filter { Files.isDirectory(it) && it.fileName.toString().length == 32 }
            .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
            ?: return null
        return accountDir / "image2" / bigImgPath.take(2) / bigImgPath.substring(2, 4) / bigImgPath
    }

    /**
     * 根据 md5 解密贴纸；WXGF 转为 GIF，标准图片格式保持原数据。
     * @return 保存后的文件路径, 失败返回 null
     */
    fun decodeStickerToFile(md5: String, destination: Path): Path? =
        decodeStickerToFile(md5, destination, logFailure = true)

    private fun decodeStickerToFile(
        md5: String,
        destination: Path,
        logFailure: Boolean,
    ): Path? {
        var temporary: Path? = null
        return try {
            if (detectImageMime(destination) != null) {
                Files.setLastModifiedTime(destination, FileTime.fromMillis(System.currentTimeMillis()))
                return destination
            }

            destination.parent.createDirectories()
            val emojiInfo = WeServiceApi.getEmojiInfoByMd5(md5)
            val emojiFileEncryptMgr = classEmojiFileEncryptMgr.reflekt()
                .firstMethod {
                    modifiers(Modifiers.STATIC)
                    parameterCount = 0
                }
                .invokeStatic()!!
            val encryptedBytes = emojiFileEncryptMgr.reflekt()
                .firstMethod {
                    parameters(IEmojiInfo::class)
                    returnType = ByteArray::class
                }
                .invoke(emojiInfo) as ByteArray
            val stickerBytes = if (MMWXGFJNI.isWxGF(encryptedBytes, encryptedBytes.size)) {
                MMWXGFJNI.nativeWxamToGif(encryptedBytes)
            } else {
                encryptedBytes
            }
            check(detectImageMime(stickerBytes) != null) { "failed to decode sticker image" }

            temporary = Files.createTempFile(destination.parent, ".${destination.name}.", ".tmp")
            temporary.outputStream().use { output -> output.write(stickerBytes) }
            check(temporary.isRegularFile() && temporary.fileSize() > 0L) {
                "temporary sticker GIF is empty"
            }

            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            temporary = null
            check(destination.isRegularFile() && destination.fileSize() > 0L) {
                "final sticker GIF is empty"
            }
            destination
        } catch (error: Exception) {
            if (logFailure) WeLogger.e(TAG, "decodeStickerToFile failed for md5=$md5", error)
            null
        } finally {
            temporary?.deleteIfExists()
        }
    }

    fun materializeNotificationSticker(
        md5: String,
        destination: Path,
        deadlineElapsedRealtime: Long,
        wait: Boolean,
    ): NotificationMediaFile? {
        decodeStickerToFile(md5, destination, logFailure = false)?.let {
            return NotificationMediaFile(it, detectImageMime(it)!!)
        }
        if (!wait) return null

        startStickerLoad(md5)
        do {
            if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtime) {
                WeLogger.w(TAG, "notification sticker was not ready before deadline: $md5")
                return null
            }
            decodeStickerToFile(md5, destination, logFailure = false)?.let {
                if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtime) return null
                return NotificationMediaFile(it, detectImageMime(it)!!)
            }
            sleepForPoll(deadlineElapsedRealtime, 50L)
        } while (true)
    }

    private fun startStickerLoad(md5: String) {
        val loadMethod = methodLoadEmojiFile.method
        val callbackType = loadMethod.parameterTypes[2]
        val callback = Proxy.newProxyInstance(
            callbackType.classLoader,
            arrayOf(callbackType),
        ) { proxy, callbackMethod, args ->
            when (callbackMethod.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "WeKitNotificationEmojiLoadCallback"
                else -> null
            }
        }
        val receiver = if (Modifier.isStatic(loadMethod.modifiers)) {
            null
        } else {
            loadMethod.declaringClass.reflekt().firstField {
                modifiers(Modifiers.STATIC)
                type = loadMethod.declaringClass
            }.getStatic()!!
        }
        loadMethod.invoke(
            receiver,
            WeServiceApi.getEmojiInfoByMd5(md5),
            true,
            callback,
        )
    }

    /**
     * 根据 md5 解密贴纸, WXGF 转 GIF，标准图片保持原格式并保存到 Download/WeKit/。
     * @return 保存后的文件路径, 失败返回 null
     */
    fun saveStickerByMd5(md5: String, fileName: String? = null): String? {
        val temporary = KnownPaths.downloads / ".sticker-${UUID.randomUUID()}.media"
        return try {
            val decoded = decodeStickerToFile(md5, temporary) ?: return null
            val mimeType = detectImageMime(decoded) ?: return null
            val baseName = fileName?.substringBeforeLast('.', fileName)
                ?: "sticker_${System.currentTimeMillis()}"
            val destination = KnownPaths.downloads /
                    "$baseName.${extensionForImageMime(mimeType)}"
            try {
                Files.move(
                    decoded,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(decoded, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            destination.absolutePathString()
        } catch (error: Exception) {
            WeLogger.e(TAG, "saveStickerByMd5 failed for md5=$md5", error)
            null
        } finally {
            temporary.deleteIfExists()
        }
    }

    /**
     * 根据 msgSvrId 解密贴纸并以合适图片格式保存到 Download/WeKit/。
     * @return 保存后的文件路径, 失败返回 null
     */
    fun cacheAndSaveSticker(msgSvrId: Long): String? {
        val md5 = queryImgPathByMsgSvrId(msgSvrId) ?: run {
            WeLogger.e(TAG, "cacheAndSaveSticker: no imgPath for msgSvrId=$msgSvrId")
            return null
        }
        return saveStickerByMd5(md5)
    }

    /**
     * 根据加密路径解码语音 (silk → mp3) 并保存到 Download/WeKit/。
     * @return 保存后的 mp3 文件路径, 失败返回 null
     */
    fun saveVoiceByEncPath(encPath: String): String? {
        return try {
            val silkOriginalPath = getVoiceFullPath(encPath).asPath
            val silkPath = KnownPaths.downloads / silkOriginalPath.name
            val pcmPath = KnownPaths.downloads / (silkOriginalPath.nameWithoutExtension + ".pcm")
            val mp3Path = KnownPaths.downloads / (silkOriginalPath.nameWithoutExtension + ".mp3")

            silkPath.deleteIfExists()
            silkOriginalPath.copyTo(silkPath, overwrite = true)
            AudioUtils.silkToPcm(silkPath.absolutePathString(), pcmPath.absolutePathString())
            AudioUtils.pcmToMp3(pcmPath.absolutePathString(), mp3Path.absolutePathString())
            pcmPath.deleteIfExists()
            WeLogger.i(TAG, "saved voice: $mp3Path")
            mp3Path.absolutePathString()
        } catch (e: Exception) {
            WeLogger.e(TAG, "saveVoiceByEncPath failed", e)
            null
        }
    }

    /**
     * 根据 msgSvrId 解码语音 (silk → mp3) 并保存到 Download/WeKit/。
     * @return 保存后的 mp3 文件路径, 失败返回 null
     */
    fun cacheAndSaveVoice(msgSvrId: Long): String? {
        val encPath = queryImgPathByMsgSvrId(msgSvrId) ?: run {
            WeLogger.e(TAG, "cacheAndSaveVoice: no imgPath for msgSvrId=$msgSvrId")
            return null
        }
        return saveVoiceByEncPath(encPath)
    }

    // ---- 文件缓存/下载 ----

    private data class AppAttachInfo(val status: Long, val offset: Long, val totalLen: Long, val fileFullPath: String?) {
        // 与微信 d.n0() + status==199 一致: 文件已完整落地
        val isComplete get() = status == 199L || totalLen > 0 && offset == totalLen
    }

    /** 从 appattach 表按 (msgInfoId, msgInfoTalker) 读取文件附件的下载状态与本地路径。 */
    private fun queryAppAttach(msgId: Long, talker: String): AppAttachInfo? {
        val rows = WeDatabaseApi.rawQuery(
            "SELECT status, offset, totalLen, fileFullPath FROM appattach WHERE msgInfoId=? AND msgInfoTalker=?",
            arrayOf(msgId.toString(), talker)
        )
        return rows.use { rows ->
            if (!rows.moveToFirst()) null
            else AppAttachInfo(rows.getLong(0), rows.getLong(1), rows.getLong(2), rows.getString(3))
        }
    }

    /**
     * 触发微信内部下载 (相当于点击文件气泡"下载"): 解析 appmsg XML、创建 appattach 行、向 CDN 发起下载任务。
     * 通过复用微信自己的 autoDownloadFile Runnable 完成。
     */
    private fun triggerFileDownload(msgInfoInstance: Any): Boolean {
        return try {
            val runnable = classAppAttachAutoDownload.clazz.createInstance(msgInfoInstance, isPublic = false)
            (runnable as Runnable).run()
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "triggerFileDownload failed", e)
            false
        }
    }

    private fun pollUntilFileDownloaded(msgId: Long, talker: String): String? {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val info = queryAppAttach(msgId, talker)
            if (info != null && info.isComplete && !info.fileFullPath.isNullOrEmpty()) {
                return info.fileFullPath
            }
            Thread.sleep(1000)
        }
        return null
    }

    /**
     * 缓存文件 (直接使用已有的 msgInfo 实例)。
     *
     * 优先走此重载: 聊天里长按弹出的菜单已经持有微信正在渲染的、字段完整的 msgInfo 实例,
     * 直接复用即可, 无需按 msgSvrId 重新查库重建 —— 重建出的实例可能字段缺失或压根查不到行,
     * 导致微信内部 [op0.q.u]/parse msg 返回 null 后被解引用而抛 NPE。
     *
     * 缓存与下载分离 —— 此方法只负责把文件缓存到微信内部, 不拷贝到 Download/WeKit/。
     * @return 缓存后文件在微信内部的绝对路径, 失败返回 null
     */
    fun cacheFile(msgInfoInstance: Any): String? {
        return try {
            val mi = MessageInfo(msgInfoInstance)
            val talker = mi.talker

            // 已缓存则直接返回
            queryAppAttach(mi.id, talker)?.let { info ->
                if (info.isComplete && !info.fileFullPath.isNullOrEmpty()) return info.fileFullPath
            }

            // 内容为空时微信的下载逻辑会解析 appmsg XML 失败并对 null 解引用抛 NPE,
            // 在此提前拦截, 给出可读的错误而不是把崩溃丢进微信内部。
            if (mi.content.isBlank()) {
                WeLogger.e(TAG, "cacheFile: msgInfo content is empty (msgSvrId=${mi.serverId}), cannot trigger download")
                return null
            }

            if (!triggerFileDownload(msgInfoInstance)) return null
            pollUntilFileDownloaded(mi.id, talker)
        } catch (e: Exception) {
            WeLogger.e(TAG, "cacheFile failed", e)
            null
        }
    }

    /**
     * 缓存文件 (按 msgSvrId 重新查库重建 msgInfo 实例)。
     *
     * 仅供无法拿到现成 msgInfo 实例的调用方 (如远程 API / WeAgent) 使用;
     * 能拿到实例时请改用 [cacheFile] 的实例重载。
     * @param talker 会话 username; 传 null 时自动从 message 表反查 (仅覆盖 C2C/群聊)。
     * @return 缓存后文件在微信内部的绝对路径, 失败返回 null
     */
    fun cacheFile(msgSvrId: Long, talker: String? = null): String? {
        return try {
            val msgInfoInstance = getMsgInfoInstanceByMsgSvrId(msgSvrId, talker)
            cacheFile(msgInfoInstance)
        } catch (e: Exception) {
            WeLogger.e(TAG, "cacheFile failed", e)
            null
        }
    }

    /**
     * 下载文件 (直接使用已有的 msgInfo 实例): 先确保文件已缓存到微信内部, 再拷贝到 Download/WeKit/。
     * @return 拷贝到 Download/WeKit/ 后的绝对路径, 失败返回 null
     */
    fun downloadFile(msgInfoInstance: Any): String? {
        return try {
            val cachedPath = cacheFile(msgInfoInstance) ?: run {
                WeLogger.e(TAG, "downloadFile: failed to cache file")
                return null
            }
            val srcPath = cachedPath.asPath
            val destPath = KnownPaths.downloads / srcPath.name
            destPath.deleteIfExists()
            srcPath.copyTo(destPath, overwrite = true)
            WeLogger.i(TAG, "downloaded file: $destPath")
            destPath.absolutePathString()
        } catch (e: Exception) {
            WeLogger.e(TAG, "downloadFile failed", e)
            null
        }
    }

    /**
     * 下载文件 (按 msgSvrId 重新查库重建 msgInfo 实例)。
     * 能拿到实例时请改用 [downloadFile] 的实例重载。
     * @param talker 会话 username; 传 null 时自动从 message 表反查 (仅覆盖 C2C/群聊)。
     * @return 拷贝到 Download/WeKit/ 后的绝对路径, 失败返回 null
     */
    fun downloadFile(msgSvrId: Long, talker: String? = null): String? {
        return try {
            val msgInfoInstance = getMsgInfoInstanceByMsgSvrId(msgSvrId, talker)
            downloadFile(msgInfoInstance)
        } catch (e: Exception) {
            WeLogger.e(TAG, "downloadFile failed", e)
            null
        }
    }

    fun setReferringMessage(chatFooter: ChatFooter, msgInfoInstance: Any) {
        val quoteMethod = chatFooter.reflekt()
            .firstMethod {
                parameters { args -> args[0] == classMsgInfo.clazz }
                returnType = Boolean::class
            }.self
        if (quoteMethod.parameterCount == 1) quoteMethod.invoke(chatFooter, msgInfoInstance)
        else quoteMethod.invoke(chatFooter, msgInfoInstance, null)
    }
}
