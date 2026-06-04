package dev.ujhhgtg.wekit.hooks.api.core

import android.annotation.SuppressLint
import android.content.ContentValues
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.condition.type.VagueType
import com.highcapable.kavaref.extension.createInstance
import com.tencent.mm.opensdk.modelmsg.WXFileObject
import com.tencent.mm.opensdk.modelmsg.WXMediaMessage
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.constants.WeChatVersions
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.collections.emptyHashSet
import dev.ujhhgtg.wekit.utils.reflection.BBool
import dev.ujhhgtg.wekit.utils.reflection.BInt
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.reflection.isBuiltin
import dev.ujhhgtg.wekit.utils.reflection.isStatic
import dev.ujhhgtg.wekit.utils.reflection.makeAccessible
import dev.ujhhgtg.wekit.utils.serialization.JsonToXmlConverter
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils.extractXmlAttr
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils.extractXmlTag
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.random.Random


@SuppressLint("DiscouragedApi")
@HookItem(path = "API/消息发送服务", description = "提供文本、图片、文件、语音消息发送能力")
object WeMessageApi : ApiHookItem(), IResolvesDex {

    // -------------------------------------------------------------------------------------
    // 基础消息类
    // -------------------------------------------------------------------------------------
    private val classNetSceneSendMsg by dexClass()
    private val classNetSceneQueue by dexClass()
    val classNetSceneBase by dexClass()
    private val classNetSceneObserverOwner by dexClass()
    private val methodGetSendMsgObject by dexMethod()
    private val methodPostToQueue by dexMethod()
    private val methodShareFile by dexMethod()
    val classMsgInfo by dexClass()
    val classMsgInfoStorage by dexClass()
    val methodMsgInfoStorageInsertMessage by dexMethod()
    val classChattingContext by dexClass()
    val classChattingDataAdapter by dexClass()
    val classTransformChattingComponent by dexClass()
    val methodGetIsTransformed by dexMethod()

    // -------------------------------------------------------------------------------------
    // 图片发送组件
    // -------------------------------------------------------------------------------------
    private val classMvvmBase by dexClass()
    private val classImageSender by dexClass()      // 发送逻辑核心
    private val classImageTask by dexClass()        // 任务数据模型
    private val classServiceManager by dexClass()   // ServiceManager
    private val classConfigLogic by dexClass()      // ConfigStorageLogic
    private val classImageServiceImpl by dexClass()
    private val methodImageSendEntry by dexMethod()

    // -------------------------------------------------------------------------------------
    // 语音发送组件
    // -------------------------------------------------------------------------------------
    private val classVoiceParams by dexClass()          // 语音参数模型 (原 rc0.a)
    private val classVoiceTask by dexClass()            // 语音发送任务 (原 uc0.v)
    private val classVoiceNameGen by dexClass()         // 语音文件名生成 (原 py0.g1)
    private val classVfs by dexClass()                  // VFS 文件操作 (原 w6)
    private val classPathUtil by dexClass()             // 路径计算工具 (原 h1)
    private val classMmKernel by dexClass()             // 核心 Kernel (原 j1)
    private val methodMmKernelGetStorage by dexMethod() // Kernel.getStorage
    private val classVoiceLogic by dexClass()
    private val methodGetAmrFullPath by dexMethod()
    private val methodStartRecvAndSend by dexMethod()
    private val classSceneVoiceService by dexClass()

    // 查找 Service 接口 (sc0.e)
    private val classVoiceServiceInterface by dexClass()

    // Service 实现类
    private val classVoiceServiceImpl by dexClass()
    private val methodSendVoice by dexMethod()

    // -------------------------------------------------------------------------------------
    // 运行时缓存
    // -------------------------------------------------------------------------------------

    // 基础 & 文本
    private var getServiceMethod: Method? = null       // ServiceManager.getService
    private lateinit var getSelfAliasMethod: Method

    // 图片
    private lateinit var imageServiceApiClass: Class<*>
    private lateinit var sendImageMethod: Method
    private lateinit var taskConstructor: Constructor<*>
    private lateinit var crossParamsClass: Class<*>

    // 语音 & VFS
    private lateinit var vfsCopyMethod: Method         // VFS.L (write)
    private lateinit var vfsReadMethod: Method         // VFS.F (read)
    private lateinit var vfsExistsMethod: Method       // VFS.k/e (exists)
    private lateinit var voiceNameGenMethod: Method    // g1.E
    private var storageAccPathMethod: Method? = null  // b0.e (动态解析)
    private lateinit var pathGenMethod: Method         // h1.c
    private lateinit var voiceTaskConstructor: Constructor<*>
    private lateinit var voiceDurationField: Field     // 语音时长字段
    private lateinit var voiceOffsetField: Field       // 偏移量字段

    private val TAG = nameOf(WeMessageApi)

    @SuppressLint("NonUniqueDexKitData")
    override fun resolveDex(dexKit: DexKitBridge) {

        // ---------------------------------------------------------------------------------
        // 基础组件查找
        // ---------------------------------------------------------------------------------

        classChattingDataAdapter.find(dexKit) {
            matcher {
                usingEqStrings(
                    "MicroMsg.ChattingDataAdapterV3",
                    "[handleMsgChange] isLockNotify:"
                )
            }
        }

        classChattingContext.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]")
            }
        }

        classNetSceneObserverOwner.find(dexKit) {
            matcher {
                methods {
                    add {
                        paramCount = 4
                        usingStrings("MicroMsg.Mvvm.NetSceneObserverOwner")
                    }
                }
            }
        }

        classNetSceneSendMsg.find(dexKit) {
            matcher {
                methods {
                    add {
                        paramCount = 1
                        usingStrings("MicroMsg.NetSceneSendMsg", "markMsgFailed for id:%d")
                    }
                }
            }
        }

        classNetSceneQueue.find(dexKit) {
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

        classNetSceneBase.find(dexKit) {
            matcher {
                usingEqStrings("scene security verification not passed, type=")
            }
        }

        methodGetSendMsgObject.find(dexKit, true) {
            matcher {
                paramCount = 0
                returnType = classNetSceneObserverOwner.getDescriptorString() ?: ""
            }
        }

        methodPostToQueue.find(dexKit, true) {
            searchPackages("com.tencent.mm.modelbase")
            matcher {
                declaredClass = classNetSceneQueue.getDescriptorString() ?: ""
                paramTypes(classNetSceneBase.getDescriptorString() ?: "")
                returnType = "boolean"
                usingNumbers(0)
            }
        }

        methodShareFile.find(dexKit) {
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

        classMsgInfo.find(dexKit) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.MsgInfo", "[parseNewXmlSysMsg]")
            }
        }


        classMsgInfoStorage.find(dexKit) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.MsgInfoStorage", "deleted dirty msg ,count is %d")
            }
        }

        methodMsgInfoStorageInsertMessage.find(dexKit) {
            matcher {
                declaredClass(classMsgInfoStorage.clazz)
                usingEqStrings("MsgInfo processAddMsg insert db error")
            }
        }

        classTransformChattingComponent.find(dexKit) {
            searchPackages("com.tencent.mm.ui.chatting.component")
            matcher {
                usingEqStrings("MicroMsg.TransformComponent", "[onChattingPause]")
            }
        }

        methodGetIsTransformed.find(dexKit) {
            matcher {
                declaredClass(classMsgInfo.clazz)
                usingNumbers(64, 0)
                usingFields {
                    add {
                        type = "int"
                    }
                }
                returnType = "boolean"
            }
        }

        // ---------------------------------------------------------------------------------
        // 图片组件查找
        // ---------------------------------------------------------------------------------

        classMvvmBase.find(dexKit) {
            matcher {
                usingStrings(
                    "MicroMsg.Mvvm.MvvmPlugin",
                    "onAccountInitialized start"
                )
            }
        }

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
                declaredClass(classImageSender.clazz)
                modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL
                paramCount(4, 5)
                usingEqStrings("send_mid_size", "send_hevc_mid_size")
            }
        }

        val taskClassName = methodImageSendEntry.method.parameterTypes[1]
        classImageTask.setDescriptor(taskClassName.name)

        // this also seems applicable
//        classImageTask.find(dexKit) {
//            matcher {
//                usingEqStrings("imgPath", "fromUsername", "toUsername", "crossParams", "msg_raw_img_send")
//            }
//        }

        classImageServiceImpl.find(dexKit, allowFailure = true) {
            matcher {
                usingStrings("MicroMsg.ImgUpload.MsgImgFeatureService")
                superClass(classMvvmBase.getDescriptorString()!!)
            }
        }

        classImageTask.find(dexKit, allowFailure = true) {
            matcher { usingStrings("msg_raw_img_send") }
        }

        // 查找 ServiceManager
        classServiceManager.find(dexKit) {
            matcher {
                usingStrings("MicroMsg.ServiceManager")
                methods {
                    add {
                        modifiers = Modifier.PUBLIC or Modifier.STATIC
                        paramCount = 1
                        paramTypes(Class::class.java.name)
                    }
                }
            }
        }

        classConfigLogic.find(dexKit) {
            matcher {
                usingEqStrings(
                    "MicroMsg.ConfigStorageLogic",
                    "get userinfo fail"
                )
            }
        }

        if ((HostInfo.versionCode >= WeChatVersions.MM_8_0_67 && !HostInfo.isHostGooglePlay) ||
            HostInfo.versionCode >= WeChatVersions.MM_8_0_66_PLAY && HostInfo.isHostGooglePlay
        ) {
            methodImgUploadFeatureServiceSendImage.find(dexKit) {
                matcher {
                    declaredClass {
                        usingEqStrings("MicroMsg.ImgUpload.MsgImgFeatureService", "taskListener", "params")
                    }

                    paramCount(1)
                    usingEqStrings("params")
                }
            }

            methodAppInfoSetAppId.find(dexKit) {
                matcher {
                    declaredClass {
                        usingEqStrings("appinfo", "appid", "version", "appname", "isforceupdate", "messageaction", "messageext", "mediatagname")
                    }

                    paramTypes(BString)
                    usingNumbers(0)
                }
            }

            ctorNetSceneUploadMsgImg.setPlaceholderDescriptor()
        } else {
            methodImgUploadFeatureServiceSendImage.setPlaceholderDescriptor()

            methodAppInfoSetAppId.setPlaceholderDescriptor()

            ctorNetSceneUploadMsgImg.find(dexKit) {
                searchPackages("com.tencent.mm.modelimage")
                matcher {
                    name = "<init>"
                    declaredClass {
                        usingEqStrings("MicroMsg.NetSceneUploadMsgImg", "/cgi-bin/micromsg-bin/uploadmsgimg")
                    }
                    paramTypes(int, BString, BString, BString, int, null, int, BString, BString, bool, int)
                }
            }
        }

        // ---------------------------------------------------------------------------------
        // 语音/VFS 组件动态查找
        // ---------------------------------------------------------------------------------

        classVfs.find(dexKit) {
            matcher {
                usingStrings("MicroMsg.VFSFileOp", "Cannot resolve path or URI")
            }
        }

        classVoiceNameGen.find(dexKit) {
            matcher {
                usingStrings("CREATE TABLE IF NOT EXISTS voiceinfo ( FileName TEXT PRIMARY KEY")
            }
        }

        classVoiceParams.find(dexKit) {
            matcher {
                usingEqStrings("toUserName", "fileName", "send_voice_msg")
            }
        }

        classVoiceTask.find(dexKit) {
            matcher {
                usingStrings("MicroMsg.VoiceMsg.VoiceMsgSendTask")
                methods {
                    add {
                        name = "<init>"
                        paramTypes(classVoiceParams.clazz)
                    }
                }
            }
        }

        classVoiceLogic.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.VoiceLogic", "startRecord insert voicestg success")
            }
        }

        methodGetAmrFullPath.find(dexKit) {
            matcher {
                usingEqStrings("getAmrFullPath cost: ")
            }
        }

        methodStartRecvAndSend.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.SceneVoiceService", "Start Recv[%s] :%s", "Start Send :")
            }
        }

        classSceneVoiceService.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.SceneVoiceService", "//voicetrymore", "getVoiceService %s")
            }
        }

        classPathUtil.find(dexKit) {
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

        classMmKernel.find(dexKit) {
            matcher {
                usingStrings("MicroMsg.MMKernel", "Initialize skeleton")
            }
        }

        methodMmKernelGetStorage.find(dexKit, true) {
            matcher {
                declaredClass(classMmKernel.clazz)
                modifiers = Modifier.PUBLIC or Modifier.STATIC
                paramCount = 0
                usingStrings("mCoreStorage not initialized!")
            }
        }

        // 定位 VoiceServiceImpl (tc0.k)
        classVoiceServiceImpl.find(dexKit) {
            matcher {
                usingEqStrings(
                    "MicroMsg.VoiceMsgAsyncSendFSC",
                    "sendAsync only support BaseSendMsgTask Type"
                )
            }
        }

        // 定位 sendSync 方法 (gh)
        methodSendVoice.find(dexKit, true) {
            matcher {
                declaredClass(classVoiceServiceImpl.clazz)
                // 8.0.65 sendSync 方法打错字打成 sendAsync 了没绷住
//                    usingStrings("sendSync only support BaseSendMsgTask Type")
//                    paramCount = 1
                paramCount = 1
                returnType = "void"
            }
        }

        // 遍历所有接口，找到第一个非系统接口作为 Service 接口
        val targetInterface = classVoiceServiceImpl.clazz.interfaces.first {
            !it.isBuiltin && !it.name.startsWith("ki0.") // FIXME: might change with WeChat version
        }
        classVoiceServiceInterface.setDescriptor(targetInterface.name)
    }

    fun convertMsgInfoFromContentValues(contentValues: ContentValues, boolValue: Boolean): Any {
        val msgInfo = classMsgInfo.clazz.createInstance()
        msgInfo.asResolver().firstMethod {
            name = "convertFrom"
            parameters(ContentValues::class, Boolean::class)
            superclass()
        }.invoke(contentValues, boolValue)
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
        val msgInfo = convertMsgInfoFromContentValues(values, true)
        methodMsgInfoStorageInsertMessage.method.invoke(
            WeServiceApi.messageInfoStorage,
            msgInfo
        )
    }

    override fun onEnable() {
        // -----------------------------------------------------------------------------
        // 图片组件初始化
        // -----------------------------------------------------------------------------
        taskConstructor = classImageTask.clazz.asResolver()
            .firstConstructor { parameterCount = 5 }
            .self

        crossParamsClass = taskConstructor.parameterTypes[4]

        // -----------------------------------------------------------------------------
        // 语音/VFS 组件初始化
        // -----------------------------------------------------------------------------

        // VFS
        classVfs.asResolver().apply {
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

        pathGenMethod = classPathUtil.asResolver().firstMethod {
            modifiers(Modifiers.STATIC)
            parameters(VagueType, VagueType, VagueType, VagueType, Int::class)
            returnType = String::class
        }.self

        // Voice Components
        voiceNameGenMethod = classVoiceNameGen.asResolver().firstMethod {
            modifiers(Modifiers.STATIC)
            parameters(String::class, VagueType)
            returnType = String::class
        }.self

        classVoiceParams.asResolver().apply {
            val intFields = field { type = Int::class }
            voiceDurationField = intFields[0].self
            voiceOffsetField = intFields[1].self
        }

        voiceTaskConstructor = classVoiceTask.asResolver()
            .firstConstructor {
                parameters(classVoiceParams.clazz)
            }.self

        getServiceMethod = classServiceManager.asResolver()
            .firstMethod {
                modifiers(Modifiers.STATIC)
                parameters(Class::class)
            }.self

        getSelfAliasMethod = classConfigLogic.asResolver()
            .firstMethod {
                name { it.length <= 2 }
                modifiers(Modifiers.STATIC)
                parameterCount = 0
                returnType = String::class
            }.self

        imageServiceApiClass = classImageServiceImpl.clazz.interfaces.first {
            !it.isBuiltin
        }

        sendImageMethod = classImageServiceImpl.clazz.declaredMethods.first { m ->
            m.parameterCount == 1 &&
                    m.parameterTypes[0] == classImageTask.clazz &&
                    m.returnType.name.contains("flow", ignoreCase = true)
        }

        crossParamsClass = classImageTask.clazz.declaredConstructors
            .first { it.parameterCount == 5 }.parameterTypes[4]
    }

    /**
     * 动态解析 AccPath 获取方法
     */
    private fun getAccPath(): String {
        val storageObj = methodMmKernelGetStorage.method.invoke(null)
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
            val serviceObj = getServiceMethod?.invoke(null, imageServiceApiClass) ?: return false

            val paramsObj = crossParamsClass.createInstance()
            paramsObj.asResolver()
                .firstField { type = int }
                .set(4)

            val taskObj = classImageTask.clazz.createInstance(
                imgPath,
                0,
                selfCustomWxId,
                toUser,
                paramsObj
            )
            taskObj.asResolver()
                .lastField { type = String::class }
                .set("media_generate_send_img")

            sendImageMethod.invoke(serviceObj, taskObj)

            WeLogger.i(TAG, "sent image message to $toUser")
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to send image message", e)
            false
        }
    }

    private val methodImgUploadFeatureServiceSendImage by dexMethod()
    private val methodAppInfoSetAppId by dexMethod()
    private val ctorNetSceneUploadMsgImg by dexConstructor()

    fun sendImageByMd5(toUser: String, md5: String, appMsgAppId: String? = null) {
        if (HostInfo.versionCode >= WeChatVersions.MM_8_0_67 && !HostInfo.isHostGooglePlay ||
            HostInfo.versionCode >= WeChatVersions.MM_8_0_66_PLAY && HostInfo.isHostGooglePlay
        ) {
            val sendImageMethod = methodImgUploadFeatureServiceSendImage.method
            val paramsClass = sendImageMethod.parameterTypes[0]
            val crossParamsClass = paramsClass.asResolver()
                .firstField { type { !it.isBuiltin } }.self.type
            val crossParams = crossParamsClass.createInstance()

            if (appMsgAppId != null) {
                val appInfoClass = methodAppInfoSetAppId.method.declaringClass
                val appInfo = appInfoClass.createInstance()
                methodAppInfoSetAppId.method.invoke(appInfo, appMsgAppId)
                crossParams.asResolver()
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
            WeNetSceneApi.addNetSceneToQueue(
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

    /** 发送私有路径下的语音文件 */
    fun sendVoice(toUser: String, path: String, durationMs: Int): Boolean {
        var succeeded = runCatching {
            // 尝试通过 ServiceManager 获取
            var finalServiceObj: Any? = null
            if (getServiceMethod != null) {
                try {
                    finalServiceObj = getServiceMethod!!.invoke(null, classVoiceServiceInterface.clazz)
                } catch (e: Exception) {
                    WeLogger.e(TAG, "failed to retrieve ServiceManager, trying singleton fallback", e)
                }
            }

            // 尝试单例 Fallback
            if (finalServiceObj == null) {
                val implClass = classVoiceServiceImpl.clazz
                val instanceField = implClass.declaredFields.find {
                    it.name == "INSTANCE" || it.type == implClass
                }
                if (instanceField != null) {
                    instanceField.makeAccessible()
                    finalServiceObj = instanceField.get(null)
                }
            }

            if (finalServiceObj == null) error("failed to retrieve VoiceService instance")

            // 准备文件
            val fileName = voiceNameGenMethod.invoke(null, selfCustomWxId, "amr_") as? String
                ?: error("VoiceName Gen Failed")
            val accPath = getAccPath()
            val voice2Root = if (accPath.endsWith("/")) "${accPath}voice2/" else "$accPath/voice2/"
            val destFullPath =
                pathGenMethod.invoke(null, voice2Root, "msg_", fileName, ".amr", 2) as? String
                    ?: error("Path Gen Failed")

            if (!copyFileViaVfs(path, destFullPath)) return false

            // 构造任务
            val paramsObj = classVoiceParams.clazz.createInstance(toUser, fileName)
            voiceDurationField.set(paramsObj, durationMs)
            voiceOffsetField.set(paramsObj, 0)

            val taskObj = voiceTaskConstructor.newInstance(paramsObj)
                ?: error("failed to construct voice task")

            methodSendVoice.method.invoke(finalServiceObj, taskObj)
            WeLogger.i(TAG, "sent voice (method 1): $fileName")
        }.onFailure { WeLogger.e(TAG, "failed to send voice (method 1)", it) }.isSuccess

        if (succeeded) return true

        succeeded = runCatching {
            val partialPath = classVoiceLogic.asResolver()
                .firstMethod {
                    parameters(BString, BString)
                    returnType = BString
                }
                .invoke(toUser, "amr_") as String
            val mGetAmrFullPath = methodGetAmrFullPath.method
            val fullPath = if (mGetAmrFullPath.isStatic) {
                mGetAmrFullPath.invoke(null, partialPath, true)
            } else {
                mGetAmrFullPath.invoke(WeServiceApi.getServiceByClass(mGetAmrFullPath.declaringClass), null, partialPath, true)
            } as String

            Files.copy(Path(path), Path(fullPath), StandardCopyOption.REPLACE_EXISTING)

            val target = classVoiceLogic.clazz.asResolver()
                .firstMethod {
                    parameters {
                        it[0] == BString && it[1] == BInt && it[2] == BInt
                    }
                    returnType = BBool
                }.self
            if (target.parameterCount == 4) {
                target.invoke(null, fullPath, durationMs, 0, null)
            } else {
                target.invoke(null, fullPath, durationMs, 0)
            }

            val service = classSceneVoiceService.clazz.asResolver()
                .firstMethod {
                    returnType = methodStartRecvAndSend.method.declaringClass
                    modifiers { it.contains(Modifiers.STATIC) }
                }.invoke()!!

            methodStartRecvAndSend.method.invoke(null, service)

            WeLogger.i(TAG, "sent voice (method 2): $fullPath")
        }.onFailure { WeLogger.e(TAG, "failed to send voice (method 2)", it) }.isSuccess

        return succeeded
    }

    fun sendXmlAppMsg(toUser: String, xmlContent: String): Boolean {
        val appId = extractXmlAttr(xmlContent, "appid")
        val title = extractXmlTag(xmlContent, "title")

        WeLogger.d(TAG, "appmsg info: appid=$appId, title=$title")
        return WeAppMsgApi.sendXmlAppMsg(toUser, title, appId, null, null, xmlContent)
    }

    /**
     * 使用微信内部 VFS 引擎进行物理拷贝
     */
    private fun copyFileViaVfs(sourcePath: String, destPath: String): Boolean {
        WeLogger.d(TAG, "VFS Copy: $sourcePath -> $destPath")
        return try {
            val input = vfsReadMethod.invoke(null, sourcePath) as? InputStream
                ?: error("VFS Open Failed for $sourcePath")

            val output = vfsCopyMethod.invoke(null, destPath, false) as? OutputStream
                ?: error("VFS Create Failed for $destPath")

            input.use { i ->
                output.use { o ->
                    i.copyTo(o)
                }
            }

            // 校验
            val exists = vfsExistsMethod.invoke(null, destPath) as? Boolean ?: false
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

    val selfCustomWxId: String
        get() {
            return getSelfAliasMethod.invoke(null) as? String ?: ""
        }

    fun getMsgInfoFromTag(tag: Any): Any {
        val mGetMsgInfo = tag.asResolver()
            .optional()
            .firstMethodOrNull {
                returnType = classMsgInfo.clazz
                parameterCount(0)
                superclass()
            }

        return if (mGetMsgInfo != null) {
            mGetMsgInfo.invoke()!!
        } else {
            tag.asResolver()
                .firstField {
                    type = classMsgInfo.clazz
                    superclass()
                }.get()!!
        }
    }
}
