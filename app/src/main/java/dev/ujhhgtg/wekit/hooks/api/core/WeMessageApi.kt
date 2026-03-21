package dev.ujhhgtg.wekit.hooks.api.core

import android.annotation.SuppressLint
import android.content.ContentValues
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.condition.type.VagueType
import com.highcapable.kavaref.extension.createInstance
import com.highcapable.kavaref.extension.toClass
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.nameof.nameof
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.XmlUtils.extractXmlAttr
import dev.ujhhgtg.wekit.utils.XmlUtils.extractXmlTag
import dev.ujhhgtg.wekit.utils.logging.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

@SuppressLint("DiscouragedApi")
@HookItem(path = "API/消息发送服务", desc = "提供文本、图片、文件、语音消息发送能力")
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

    // -------------------------------------------------------------------------------------
    // 图片发送组件
    // -------------------------------------------------------------------------------------
    private val classMvvmBase by dexClass()
    private val classImageSender by dexClass()      // 发送逻辑核心
    private val classImageTask by dexClass()        // 任务数据模型
    private val methodImageSendEntry by dexMethod() // 静态入口方法
    private val classServiceManager by dexClass()   // ServiceManager
    private val classConfigLogic by dexClass()      // ConfigStorageLogic
    private val classImageServiceImpl by dexClass()

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

    // 查找 Service 接口 (sc0.e)
    private val classVoiceServiceInterface by dexClass()

    // Service 实现类
    private val classVoiceServiceImpl by dexClass()
    private val methodSendVoice by dexMethod()

    // -------------------------------------------------------------------------------------
    // 运行时缓存
    // -------------------------------------------------------------------------------------

    // 基础 & 文本
    private var netSceneSendMsgClass: Class<*>? = null
    private var getSendMsgObjectMethod: Method? = null
    private var postToQueueMethod: Method? = null
    private var shareFileMethod: Method? = null
    private var getServiceMethod: Method? = null       // ServiceManager.getService
    private var getSelfAliasMethod: Method? = null

    // 图片
    private var p6Method: Method? = null
    private var imageMetadataMapField: Field? = null
    private var imageServiceApiClass: Class<*>? = null
    private var sendImageMethod: Method? = null
    private var taskConstructor: Constructor<*>? = null
    private var crossParamsClass: Class<*>? = null
    private var crossParamsConstructor: Constructor<*>? = null

    // 文件
    private var wxFileObjectClass: Class<*>? = null
    private var wxMediaMessageClass: Class<*>? = null

    // 语音 & VFS
    private var vfsCopyMethod: Method? = null         // VFS.L (write)
    private var vfsReadMethod: Method? = null         // VFS.F (read)
    private var vfsExistsMethod: Method? = null       // VFS.k/e (exists)
    private var voiceNameGenMethod: Method? = null    // g1.E
    private var kernelStorageMethod: Method? = null   // j1.u
    private var storageAccPathMethod: Method? = null  // b0.e (动态解析)
    private var pathGenMethod: Method? = null         // h1.c
    private var voiceParamsClass: Class<*>? = null
    private var voiceTaskClass: Class<*>? = null
    private var voiceTaskConstructor: Constructor<*>? = null
    private var voiceServiceInterfaceClass: Class<*>? = null // sc0.e
    private var voiceSendMethod: Method? = null       // gh
    private var voiceDurationField: Field? = null     // 语音时长字段
    private var voiceOffsetField: Field? = null       // 偏移量字段

    // Unsafe
    private var unsafeInstance: Any? = null
    private var allocateInstanceMethod: Method? = null

    private val TAG = nameof(WeMessageApi)
    private const val KEY_MAP_FIELD = "dexFieldImageMetadataMap"

    @SuppressLint("NonUniqueDexKitData")
    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // ---------------------------------------------------------------------------------
        // 基础组件查找
        // ---------------------------------------------------------------------------------

        classChattingDataAdapter.find(dexKit, descriptors) {
            matcher {
                usingEqStrings(
                    "MicroMsg.ChattingDataAdapterV3",
                    "[handleMsgChange] isLockNotify:"
                )
            }
        }

        classChattingContext.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]")
            }
        }

        classNetSceneObserverOwner.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        paramCount = 4
                        usingStrings("MicroMsg.Mvvm.NetSceneObserverOwner")
                    }
                }
            }
        }

        classNetSceneSendMsg.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        paramCount = 1
                        usingStrings("MicroMsg.NetSceneSendMsg", "markMsgFailed for id:%d")
                    }
                }
            }
        }

        classNetSceneQueue.find(dexKit, descriptors) {
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

        classNetSceneBase.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("scene security verification not passed, type=")
            }
        }

        methodGetSendMsgObject.find(dexKit, descriptors, true) {
            matcher {
                paramCount = 0
                returnType = classNetSceneObserverOwner.getDescriptorString() ?: ""
            }
        }

        methodPostToQueue.find(dexKit, descriptors, true) {
            searchPackages("com.tencent.mm.modelbase")
            matcher {
                declaredClass = classNetSceneQueue.getDescriptorString() ?: ""
                paramTypes(classNetSceneBase.getDescriptorString() ?: "")
                returnType = "boolean"
                usingNumbers(0)
            }
        }

        methodShareFile.find(dexKit, descriptors = descriptors) {
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

        classMsgInfo.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.MsgInfo", "[parseNewXmlSysMsg]")
            }
        }


        classMsgInfoStorage.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.MsgInfoStorage", "deleted dirty msg ,count is %d")
            }
        }

        methodMsgInfoStorageInsertMessage.find(dexKit, descriptors) {
            matcher {
                declaredClass(classMsgInfoStorage.clazz)
                usingEqStrings("MsgInfo processAddMsg insert db error")
            }
        }

        classTransformChattingComponent.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui.chatting.component")
            matcher {
                usingEqStrings("MicroMsg.TransformComponent", "[onChattingPause]")
            }
        }

        // ---------------------------------------------------------------------------------
        // 图片组件查找
        // ---------------------------------------------------------------------------------

        classMvvmBase.find(dexKit, descriptors) {
            matcher {
                usingStrings(
                    "MicroMsg.Mvvm.MvvmPlugin",
                    "onAccountInitialized start"
                )
            }
        }

        classImageSender.find(dexKit, descriptors, throwOnFailure = false) {
            matcher {
                usingStrings(
                    "MicroMsg.ImgUpload.MsgImgSyncSendFSC",
                    "/cgi-bin/micromsg-bin/uploadmsgimg"
                )
            }
        }

        val senderDesc = descriptors[classImageSender.key]
        if (senderDesc != null) {
            val sendMethodData = dexKit.findMethod {
                matcher {
                    declaredClass = senderDesc
                    modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL
                    paramCount = 4
                    paramTypes(senderDesc, null, null, null)
                }
            }.singleOrNull()

            if (sendMethodData != null) {
                descriptors[methodImageSendEntry.key] = sendMethodData.descriptor

                val taskClassName = sendMethodData.paramTypes[1]
                descriptors[classImageTask.key] = taskClassName.name

                val taskDescriptorForSearch = "L" + taskClassName.name.replace(".", "/") + ";"
                val mapFieldData = dexKit.findField {
                    matcher {
                        declaredClass = taskDescriptorForSearch
                        type = "java.util.Map"
                    }
                }.singleOrNull()

                if (mapFieldData != null) {
                    descriptors["${javaClass.simpleName}:$KEY_MAP_FIELD"] =
                        mapFieldData.descriptor
                }
            }

            val mvvmBaseDesc = descriptors[classMvvmBase.key]
            if (mvvmBaseDesc != null) {
                classImageServiceImpl.find(dexKit, descriptors) {
                    matcher {
                        usingStrings("MicroMsg.ImgUpload.MsgImgFeatureService")
                        superClass(mvvmBaseDesc)
                    }
                }
            }

            classImageTask.find(dexKit, descriptors) {
                matcher { usingStrings("msg_raw_img_send") }
            }
        }

        // 查找 ServiceManager
        classServiceManager.find(dexKit, descriptors) {
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

        classConfigLogic.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("MicroMsg.ConfigStorageLogic",
                    "get userinfo fail")
            }
        }

        // ---------------------------------------------------------------------------------
        // 语音/VFS 组件动态查找
        // ---------------------------------------------------------------------------------

        classVfs.find(dexKit, descriptors) {
            matcher {
                usingStrings("MicroMsg.VFSFileOp", "Cannot resolve path or URI")
            }
        }

        classVoiceNameGen.find(dexKit, descriptors) {
            matcher {
                usingStrings("CREATE TABLE IF NOT EXISTS voiceinfo ( FileName TEXT PRIMARY KEY")
            }
        }

        classVoiceParams.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("toUserName", "fileName", "send_voice_msg")
            }
        }

        classVoiceTask.find(dexKit, descriptors) {
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

        classPathUtil.find(dexKit, descriptors) {
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

        classMmKernel.find(dexKit, descriptors) {
            matcher {
                usingStrings("MicroMsg.MMKernel", "Initialize skeleton")
            }
        }

        methodMmKernelGetStorage.find(dexKit, descriptors, true) {
            matcher {
                declaredClass(classMmKernel.clazz)
                modifiers = Modifier.PUBLIC or Modifier.STATIC
                paramCount = 0
                usingStrings("mCoreStorage not initialized!")
            }
        }

        // 定位 VoiceServiceImpl (tc0.k)
        classVoiceServiceImpl.find(dexKit, descriptors, throwOnFailure = false) {
            matcher {
                usingStrings("MicroMsg.VoiceMsgAsyncSendFSC")
                methods {
                    add {
                        usingStrings("sendSync only support BaseSendMsgTask Type")
                    }
                }
            }
        }

        // 定位 sendSync 方法 (gh)
        methodSendVoice.find(dexKit, descriptors, true) {
            matcher {
                declaredClass(classVoiceServiceImpl.clazz)
                usingStrings("sendSync only support BaseSendMsgTask Type")
                paramCount = 1
            }
        }

        // 遍历所有接口，找到第一个非系统接口作为 Service 接口
        val targetInterface = classVoiceServiceImpl.clazz.interfaces.firstOrNull {
            !it.name.startsWith("java.") && !it.name.startsWith("android.") && !it.name.startsWith(
                "kotlin."
            ) && !it.name.startsWith("ki0.") // FIXME: might change with WeChat version
        }
        if (targetInterface != null) {
            descriptors[classVoiceServiceInterface.key] = targetInterface.name
            WeLogger.i(TAG, "从实现类反推接口成功: ${targetInterface.name}")
        } else {
            WeLogger.e(TAG, "反推接口失败：未找到合适的接口")
        }

        return descriptors
    }

    fun createMsgInfoFromContentValues(contentValues: ContentValues, boolValue: Boolean): Any {
        val msgInfo = classMsgInfo.clazz.createInstance()
        msgInfo.asResolver().firstMethod {
            name = "convertFrom"
            parameters(ContentValues::class, Boolean::class)
            superclass()
        }.invoke(contentValues, boolValue)
        return msgInfo
    }

    override fun onEnable() {
        // 初始化 Unsafe 反射
        initUnsafe()

        // -----------------------------------------------------------------------------
        // 文本/文件组件初始化
        // -----------------------------------------------------------------------------
        netSceneSendMsgClass = classNetSceneSendMsg.clazz
        getSendMsgObjectMethod = methodGetSendMsgObject.method
        postToQueueMethod = methodPostToQueue.method
        shareFileMethod = methodShareFile.method
        p6Method = methodImageSendEntry.method

        wxFileObjectClass = "com.tencent.mm.opensdk.modelmsg.WXFileObject".toClass()
        wxMediaMessageClass = "com.tencent.mm.opensdk.modelmsg.WXMediaMessage".toClass()

        // -----------------------------------------------------------------------------
        // 图片组件初始化
        // -----------------------------------------------------------------------------
        val taskClazz = classImageTask.clazz
        taskConstructor = taskClazz.asResolver()
            .firstConstructor { parameterCount = 5 }
            .self

        crossParamsClass = taskConstructor!!.parameterTypes[4]
        crossParamsConstructor = crossParamsClass?.asResolver()
            ?.firstConstructor { emptyParameters() }
            ?.self

        imageMetadataMapField = taskClazz.asResolver()
            .firstField { type { Map::class.java.isAssignableFrom(it) } }
            .self

        // -----------------------------------------------------------------------------
        // 语音/VFS 组件初始化
        // -----------------------------------------------------------------------------

        // VFS
        classVfs.asResolver().let { vfs ->
            vfsReadMethod = vfs.firstMethod {
                modifiers(Modifiers.STATIC)
                parameters(String::class)
                returnType = InputStream::class
            }.self

            vfsCopyMethod = vfs.firstMethod {
                modifiers(Modifiers.STATIC)
                parameters(String::class, Boolean::class)
                returnType = OutputStream::class
            }.self

            vfsExistsMethod = vfs.firstMethod {
                modifiers(Modifiers.STATIC)
                parameters(String::class)
                returnType = Boolean::class
            }.self
        }

        // Kernel
        kernelStorageMethod = methodMmKernelGetStorage.method

        // PathUtil
        classPathUtil.asResolver().let { pathUtil ->
            pathGenMethod = pathUtil.firstMethod {
                modifiers(Modifiers.STATIC)
                parameters(VagueType, VagueType, VagueType, VagueType, Int::class)
                returnType = String::class
            }.self
        }

        // Voice Components
        classVoiceNameGen.asResolver().let { voice ->
            voiceNameGenMethod = voice.firstMethod {
                modifiers(Modifiers.STATIC)
                parameters(String::class, VagueType)
                returnType = String::class
            }.self
        }

        voiceParamsClass = classVoiceParams.clazz
        classVoiceParams.asResolver().let { voiceParams ->
            val intFields = voiceParams.field { type = Int::class }
            voiceDurationField = intFields.firstOrNull()?.self
            voiceOffsetField = intFields.getOrNull(1)?.self
        }

        voiceTaskClass = classVoiceTask.clazz
        classVoiceTask.asResolver().let { voiceTask ->
            voiceTaskConstructor = voiceTask.firstConstructor {
                parameters(voiceParamsClass!!)
            }.self
        }

        // Voice Service
        voiceServiceInterfaceClass = classVoiceServiceInterface.clazz
        voiceSendMethod = methodSendVoice.method

        bindServiceFramework()
        bindImageBusinessLogic()
    }

    /**
     * 初始化 Unsafe 反射
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun initUnsafe() {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
        theUnsafeField.isAccessible = true
        unsafeInstance = theUnsafeField.get(null)
        allocateInstanceMethod = unsafeClass.getMethod(
            "allocateInstance",
            Class::class.java
        )
    }

    /**
     * 动态解析 AccPath 获取方法
     */
    private fun getAccPath(): String {
        val storageObj = kernelStorageMethod?.invoke(null)
            ?: throw IllegalStateException("Kernel.getStorage() failed (returned null)")

        if (storageAccPathMethod != null) {
            return storageAccPathMethod!!.invoke(storageObj) as String
        }

        WeLogger.i(TAG, "开始动态解析 AccPath 方法... StorageClass=${storageObj.javaClass.name}")

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

                    m.isAccessible = true
                    val result = m.invoke(storageObj) as? String

                    // 特征校验：包含 "MicroMsg" 且以 "/" 结尾
                    if (result != null && result.contains("MicroMsg") && result.endsWith("/")) {
                        storageAccPathMethod = m
                        WeLogger.i(TAG, "AccPath 方法解析成功: ${m.name}, 路径: $result")
                        return result
                    }
                } catch (_: Throwable) {
                    // ignore
                }
            }
            // 向上查找父类
            currentClass = currentClass.superclass
        }

        throw IllegalStateException("无法解析 AccPath 方法 (扫描了 $scanCount 个候选项, StorageClass=${storageObj.javaClass.name})")
    }

    /** 发送图片消息 */
    fun sendImage(toUser: String, imgPath: String): Boolean {
        return try {
            val apiInterface = imageServiceApiClass ?: return false
            val taskClass = classImageTask.clazz

            val serviceObj = getServiceMethod?.invoke(null, apiInterface) ?: return false

            val paramsClass = crossParamsClass ?: return false
            val paramsObj = XposedHelpers.newInstance(paramsClass)
            assignValueToFirstFieldByType(paramsObj, Int::class.javaPrimitiveType!!, 4)

            val taskObj =
                XposedHelpers.newInstance(
                    taskClass,
                    imgPath,
                    0,
                    selfCustomWxId,
                    toUser,
                    paramsObj
                )
            assignValueToLastFieldByType(taskObj, String::class.java, "media_generate_send_img")

            sendImageMethod?.invoke(serviceObj, taskObj)

            WeLogger.i(TAG, "[sendImage] 任务已提交: $toUser")
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "[sendImage] 图片发送流程失败", e)
            false
        }
    }


    /** 发送文本消息 */
    fun sendText(toUser: String, text: String): Boolean {
        return try {
            WeLogger.i(TAG, "[sendText] 准备发送文本消息: $text")
            val sendMsgObject = getSendMsgObjectMethod?.invoke(null) ?: return false
            val constructor = netSceneSendMsgClass?.getConstructor(
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Any::class.java
            ) ?: return false
            val msgObj = constructor.newInstance(toUser, text, 1, 0, null)
            postToQueueMethod?.invoke(sendMsgObject, msgObj) as? Boolean ?: false
        } catch (e: Exception) {
            WeLogger.e(TAG, "[sendText] Text 发送失败", e)
            false
        }
    }

    /** 发送文件消息 */
    fun sendFile(talker: String, filePath: String, title: String, appId: String? = null): Boolean {
        return try {
            WeLogger.i(TAG, "[sendFile] 准备发送文件消息: $filePath")
            if (shareFileMethod == null || wxFileObjectClass == null || wxMediaMessageClass == null) return false
            val fileObject = wxFileObjectClass?.createInstance() ?: return false
            wxFileObjectClass?.getField("filePath")?.set(fileObject, filePath)
            val mediaMessage = wxMediaMessageClass?.createInstance() ?: return false
            wxMediaMessageClass?.getField("mediaObject")?.set(mediaMessage, fileObject)
            wxMediaMessageClass?.getField("title")?.set(mediaMessage, title)
            shareFileMethod?.invoke(null, mediaMessage, appId ?: "", "", talker, 2, null)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "[sendFile] File 发送失败", e)
            false
        }
    }

    /** 发送私有路径下的语音文件 */
    fun sendVoice(toUser: String, path: String, durationMs: Int): Boolean {
        return try {
            // 获取 Service 实例
            val serviceInterface = voiceServiceInterfaceClass
                ?: error("VoiceService interface not found")

            // 尝试通过 ServiceManager 获取
            var finalServiceObj: Any? = null
            if (getServiceMethod != null) {
                try {
                    finalServiceObj = getServiceMethod!!.invoke(null, serviceInterface)
                } catch (e: Exception) {
                    WeLogger.e(TAG, "ServiceManager 获取失败，尝试单例 fallback", e)
                }
            }

            // 尝试单例 Fallback
            if (finalServiceObj == null) {
                val implClass = classVoiceServiceImpl.clazz
                val instanceField = implClass.declaredFields.find {
                    it.name == "INSTANCE" || it.type == implClass
                }
                if (instanceField != null) {
                    instanceField.isAccessible = true
                    finalServiceObj = instanceField.get(null)
                }
            }

            if (finalServiceObj == null) error("无法获取 VoiceService 实例")

            // 准备文件
            val fileName = voiceNameGenMethod?.invoke(null, selfCustomWxId, "amr_") as? String
                ?: error("VoiceName Gen Failed")
            val accPath = getAccPath()
            val voice2Root = if (accPath.endsWith("/")) "${accPath}voice2/" else "$accPath/voice2/"
            val destFullPath =
                pathGenMethod?.invoke(null, voice2Root, "msg_", fileName, ".amr", 2) as? String
                    ?: error("Path Gen Failed")

            if (!copyFileViaVfs(path, destFullPath)) return false

            // 构造任务
            val paramsObj = voiceParamsClass!!.createInstance(toUser, fileName)
            voiceDurationField?.set(paramsObj, durationMs)
            voiceOffsetField?.set(paramsObj, 0)

            val taskObj = voiceTaskConstructor?.newInstance(paramsObj)
                ?: error("Task 构造失败")

            voiceSendMethod?.invoke(finalServiceObj, taskObj)
            WeLogger.i(TAG, "语音发送指令已下发: $fileName")
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "语音发送流程崩溃", e)
            false
        }
    }

    fun sendXmlAppMsg(toUser: String, xmlContent: String): Boolean {
        val appId = extractXmlAttr(xmlContent, "appid")
        val title = extractXmlTag(xmlContent, "title")

        WeLogger.d(TAG, "解析信息: AppId=$appId, Title=$title")
        return WeAppMsgApi.sendXmlAppMsg(toUser, title, appId, null, null, xmlContent)
    }

    /**
     * 使用微信内部 VFS 引擎进行物理拷贝
     */
    private fun copyFileViaVfs(sourcePath: String, destPath: String): Boolean {
        WeLogger.d(TAG, "VFS Copy: $sourcePath -> $destPath")
        return try {
            if (vfsReadMethod == null) throw IllegalStateException("VFS Read Method not found")
            if (vfsCopyMethod == null) throw IllegalStateException("VFS Copy Method not found")

            val input = vfsReadMethod?.invoke(null, sourcePath) as? InputStream
                ?: throw FileNotFoundException("VFS Open Failed for $sourcePath")

            val output = vfsCopyMethod?.invoke(null, destPath, false) as? OutputStream
                ?: throw IOException("VFS Create Failed for $destPath")

            input.use { i ->
                output.use { o ->
                    i.copyTo(o)
                }
            }

            // 校验
            val exists = vfsExistsMethod?.invoke(null, destPath) as? Boolean ?: false
            if (exists) {
                WeLogger.i(TAG, "VFS 拷贝成功")
            } else {
                WeLogger.e(TAG, "VFS 拷贝看似成功但文件不存在")
            }
            exists
        } catch (e: Exception) {
            WeLogger.e(TAG, "VFS 拷贝异常: ${e.javaClass.simpleName} - ${e.message}", e)
            false
        }
    }

    val selfCustomWxId: String
        get() {
            return getSelfAliasMethod?.invoke(null) as? String ?: ""
        }

    private fun bindServiceFramework() {
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
    }

    private fun bindImageBusinessLogic() {
        val implClazz = classImageServiceImpl.clazz
        val taskClazz = classImageTask.clazz

        imageServiceApiClass = implClazz.interfaces.firstOrNull {
            !it.name.startsWith("java.") && !it.name.startsWith("android.")
        }

        sendImageMethod = implClazz.declaredMethods.firstOrNull { m ->
            m.parameterCount == 1 &&
                    m.parameterTypes[0] == taskClazz &&
                    m.returnType.name.contains("flow", ignoreCase = true)
        }

        taskClazz.declaredConstructors.firstOrNull { it.parameterCount == 5 }?.let {
            crossParamsClass = it.parameterTypes[4]
        }
    }

    private fun assignValueToFirstFieldByType(obj: Any, type: Class<*>, value: Any) {
        obj.javaClass.declaredFields.firstOrNull { it.type == type }?.let {
            it.isAccessible = true
            it.set(obj, value)
        }
    }

    private fun assignValueToLastFieldByType(obj: Any, type: Class<*>, value: Any) {
        obj.javaClass.declaredFields.lastOrNull { it.type == type }?.let {
            it.isAccessible = true
            it.set(obj, value)
        }
    }
}
