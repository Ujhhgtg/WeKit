package moe.ouom.wekit.hooks.sdk.api

import android.annotation.SuppressLint
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.common.SyncUtils
import moe.ouom.wekit.utils.common.Utils.extractXmlAttr
import moe.ouom.wekit.utils.common.Utils.extractXmlTag
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信消息发送 API
 * 基于: WeChat 8.0.68
 * 适配版本：WeChat 8.0.67 ~ 8.0.68
 */
@SuppressLint("DiscouragedApi")
@HookItem(path = "API/消息发送服务", desc = "提供文本、图片、文件、语音消息发送能力")
class WeMessageApi : ApiHookItem(), IDexFind {

    // -------------------------------------------------------------------------------------
    // 基础消息类
    // -------------------------------------------------------------------------------------
    private val dexClassNetSceneSendMsg by dexClass()
    private val dexClassNetSceneQueue by dexClass()
    val dexClassNetSceneBase by dexClass()
    private val dexClassNetSceneObserverOwner by dexClass()
    private val dexMethodGetSendMsgObject by dexMethod()
    private val dexMethodPostToQueue by dexMethod()
    private val dexMethodShareFile by dexMethod()

    // -------------------------------------------------------------------------------------
    // 图片发送组件
    // -------------------------------------------------------------------------------------
    private val dexClassMvvmBase by dexClass()
    private val dexClassImageSender by dexClass()      // 发送逻辑核心
    private val dexClassImageTask by dexClass()        // 任务数据模型
    private val dexMethodImageSendEntry by dexMethod() // 静态入口方法
    private val dexClassServiceManager by dexClass()   // ServiceManager
    private val dexClassConfigLogic by dexClass()      // ConfigStorageLogic
    private val dexClassImageServiceImpl by dexClass()

    // -------------------------------------------------------------------------------------
    // 语音发送组件
    // -------------------------------------------------------------------------------------
    private val dexClassVoiceParams by dexClass()     // 语音参数模型 (原 rc0.a)
    private val dexClassVoiceTask by dexClass()       // 语音发送任务 (原 uc0.v)
    private val dexClassVoiceNameGen by dexClass()    // 语音文件名生成 (原 py0.g1)
    private val dexClassVFS by dexClass()             // VFS 文件操作 (原 w6)
    private val dexClassPathUtil by dexClass()        // 路径计算工具 (原 h1)
    private val dexClassKernel by dexClass()          // 核心 Kernel (原 j1)
    private val dexMethodKernelGetStorage by dexMethod() // Kernel.getStorage

    // 查找 Service 接口 (sc0.e)
    private val dexClassVoiceServiceInterface by dexClass()

    // Service 实现类
    private val dexClassVoiceServiceImpl by dexClass()
    private val dexMethodVoiceSend by dexMethod()

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
    private var taskConstructor: java.lang.reflect.Constructor<*>? = null
    private var crossParamsClass: Class<*>? = null
    private var crossParamsConstructor: java.lang.reflect.Constructor<*>? = null

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
    private var voiceTaskConstructor: java.lang.reflect.Constructor<*>? = null
    private var voiceServiceInterfaceClass: Class<*>? = null // sc0.e
    private var voiceSendMethod: Method? = null       // gh
    private var voiceDurationField: Field? = null     // 语音时长字段
    private var voiceOffsetField: Field? = null       // 偏移量字段

    // Unsafe
    private var unsafeInstance: Any? = null
    private var allocateInstanceMethod: Method? = null

    companion object {
        private const val TAG = "WeMessageApi"
        private const val KEY_MAP_FIELD = "dexFieldImageMetadataMap"

        @SuppressLint("StaticFieldLeak")
        var INSTANCE: WeMessageApi? = null
    }

    @SuppressLint("NonUniqueDexKitData")
    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        try {
            WeLogger.i(
                TAG,
                ">>>> 开始查找消息发送 API (Process: ${SyncUtils.getProcessName()}) <<<<"
            )

            // ---------------------------------------------------------------------------------
            // 基础组件查找
            // ---------------------------------------------------------------------------------
            dexClassNetSceneObserverOwner.find(dexKit, descriptors = descriptors) {
                matcher {
                    methods {
                        add {
                            paramCount = 4
                            usingStrings("MicroMsg.Mvvm.NetSceneObserverOwner")
                        }
                    }
                }
            }
            dexClassNetSceneSendMsg.find(dexKit, descriptors = descriptors) {
                matcher {
                    methods {
                        add {
                            paramCount = 1
                            usingStrings("MicroMsg.NetSceneSendMsg", "markMsgFailed for id:%d")
                        }
                    }
                }
            }
            dexClassNetSceneQueue.find(dexKit, descriptors = descriptors) {
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
            dexClassNetSceneBase.find(dexKit, descriptors = descriptors) {
                matcher {
                    methods {
                        add {
                            paramCount = 3
                            usingStrings("scene security verification not passed, type=")
                        }
                    }
                }
            }
            dexMethodGetSendMsgObject.find(dexKit, descriptors, true) {
                matcher {
                    paramCount = 0
                    returnType = dexClassNetSceneObserverOwner.getDescriptorString() ?: ""
                }
            }
            dexMethodPostToQueue.find(dexKit, descriptors, true) {
                searchPackages("com.tencent.mm.modelbase")
                matcher {
                    declaredClass = dexClassNetSceneQueue.getDescriptorString() ?: ""
                    paramTypes(dexClassNetSceneBase.getDescriptorString() ?: "")
                    returnType = "boolean"
                    usingNumbers(0)
                }
            }
            dexMethodShareFile.find(dexKit, descriptors = descriptors) {
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

            // ---------------------------------------------------------------------------------
            // 图片组件查找
            // ---------------------------------------------------------------------------------
            dexClassImageSender.find(dexKit, descriptors = descriptors) {
                matcher {
                    usingStrings(
                        "MicroMsg.ImgUpload.MsgImgSyncSendFSC",
                        "/cgi-bin/micromsg-bin/uploadmsgimg"
                    )
                }
            }

            val senderDesc = descriptors[dexClassImageSender.key]
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
                    descriptors[dexMethodImageSendEntry.key] = sendMethodData.descriptor

                    val taskClassName = sendMethodData.paramTypes[1]
                    descriptors[dexClassImageTask.key] = taskClassName.name

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

                dexClassMvvmBase.find(dexKit, descriptors) {
                    matcher {
                        usingStrings(
                            "MicroMsg.Mvvm.MvvmPlugin",
                            "onAccountInitialized start"
                        )
                    }
                }

                val mvvmBaseDesc = descriptors[dexClassMvvmBase.key]
                if (mvvmBaseDesc != null) {
                    dexClassImageServiceImpl.find(dexKit, descriptors) {
                        matcher {
                            usingStrings("MicroMsg.ImgUpload.MsgImgFeatureService")
                            superClass(mvvmBaseDesc)
                        }
                    }
                }

                // 查找 ServiceManager
                dexClassServiceManager.find(dexKit, descriptors) {
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

                dexClassConfigLogic.find(dexKit, descriptors) {
                    matcher { usingStrings("MicroMsg.ConfigStorageLogic", "get userinfo fail") }
                }

                dexClassImageTask.find(dexKit, descriptors) {
                    matcher { usingStrings("msg_raw_img_send") }
                }
            }

            // ---------------------------------------------------------------------------------
            // 语音/VFS 组件动态查找
            // ---------------------------------------------------------------------------------

            dexClassVFS.find(dexKit, descriptors) {
                matcher {
                    usingStrings("MicroMsg.VFSFileOp", "Cannot resolve path or URI")
                }
            }

            dexClassVoiceNameGen.find(dexKit, descriptors) {
                matcher {
                    usingStrings("CREATE TABLE IF NOT EXISTS voiceinfo ( FileName TEXT PRIMARY KEY")
                }
            }

            dexClassVoiceParams.find(dexKit, descriptors) {
                matcher {
                    methods {
                        add {
                            name = "<init>"
                            returnType = "void"
                            usingStrings("send_voice_msg")
                        }
                    }
                }
            }

            val voiceParamsDesc = descriptors[dexClassVoiceParams.key]
            if (voiceParamsDesc != null) {
                dexClassVoiceTask.find(dexKit, descriptors) {
                    matcher {
                        usingStrings("MicroMsg.VoiceMsg.VoiceMsgSendTask")
                        methods {
                            add {
                                name = "<init>"
                                returnType = "void"
                                paramTypes(voiceParamsDesc)
                            }
                        }
                    }
                }
            }

            dexClassPathUtil.find(dexKit, descriptors) {
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

            // 查找 Kernel
            dexClassKernel.find(dexKit, descriptors) {
                matcher {
                    usingStrings("MicroMsg.MMKernel", "Initialize skeleton")
                }
            }
            // 查找 Kernel.getStorage() 方法
            val kernelDesc = descriptors[dexClassKernel.key]
            if (kernelDesc != null) {
                dexMethodKernelGetStorage.find(dexKit, descriptors, true) {
                    matcher {
                        declaredClass = kernelDesc
                        modifiers = Modifier.PUBLIC or Modifier.STATIC
                        paramCount = 0
                        usingStrings("mCoreStorage not initialized!")
                    }
                }
            }

            // -----------------------------------------------------------------------------
            // 利用异常字符串精准定位 Service 实现类和发送方法
            // -----------------------------------------------------------------------------
            // 定位 VoiceServiceImpl (tc0.k)
            dexClassVoiceServiceImpl.find(dexKit, descriptors, throwOnFailure = false) {
                matcher {
                    usingStrings("MicroMsg.VoiceMsgAsyncSendFSC")
                    // 必须包含 sendSync 方法，且该方法使用特定字符串
                    methods {
                        add {
                            usingStrings("sendSync only support BaseSendMsgTask Type")
                        }
                    }
                }
            }

            // 定位 sendSync 方法 (gh)
            val serviceImplDesc = descriptors[dexClassVoiceServiceImpl.key]
            if (serviceImplDesc != null) {
                dexMethodVoiceSend.find(dexKit, descriptors, true) {
                    matcher {
                        declaredClass = serviceImplDesc
                        usingStrings("sendSync only support BaseSendMsgTask Type")
                        paramCount = 1
                    }
                }

                // 从实现类反推接口
                val implClassData = dexKit.findClass {
                    matcher {
                        className = serviceImplDesc
                    }
                }.firstOrNull()

                if (implClassData != null) {
                    // 遍历所有接口，找到第一个非系统接口作为 Service 接口
                    val targetInterface = implClassData.interfaces.firstOrNull {
                        !it.name.startsWith("java.") && !it.name.startsWith("android.") && !it.name.startsWith(
                            "kotlin."
                        ) && !it.name.startsWith("ki0.")
                    }
                    if (targetInterface != null) {
                        // 将找到的接口名填入 map，防止 HookItemLoader 认为缓存缺失
                        descriptors[dexClassVoiceServiceInterface.key] = targetInterface.descriptor
                        WeLogger.i(TAG, "从实现类反推接口成功: $targetInterface")
                    } else {
                        WeLogger.e(TAG, "反推接口失败：未找到合适的接口")
                    }
                }
            }

            WeLogger.i(TAG, "DexKit 查找结束，共找到 ${descriptors.size} 项")
        } catch (e: Exception) {
            WeLogger.e(TAG, "查找过程崩溃", e)
            throw e
        }
        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        try {
            INSTANCE = this
            WeLogger.i(TAG, "WeMessageApi Entry 初始化...")

            try {
                // 初始化 Unsafe 反射
                initUnsafe()

                // -----------------------------------------------------------------------------
                // 文本/文件组件初始化
                // -----------------------------------------------------------------------------
                netSceneSendMsgClass = dexClassNetSceneSendMsg.clazz
                getSendMsgObjectMethod = dexMethodGetSendMsgObject.method
                postToQueueMethod = dexMethodPostToQueue.method
                shareFileMethod = dexMethodShareFile.method
                p6Method = dexMethodImageSendEntry.method

                try {
                    wxFileObjectClass =
                        classLoader.loadClass("com.tencent.mm.opensdk.modelmsg.WXFileObject")
                    wxMediaMessageClass =
                        classLoader.loadClass("com.tencent.mm.opensdk.modelmsg.WXMediaMessage")
                } catch (e: Exception) {
                    WeLogger.e(TAG, "初始化文件发送组件时失败", e)
                }

                // -----------------------------------------------------------------------------
                // 图片组件初始化
                // -----------------------------------------------------------------------------
                val taskClazz = dexClassImageTask.clazz
                taskConstructor =
                    taskClazz.declaredConstructors.firstOrNull { it.parameterCount == 5 }
                taskConstructor?.isAccessible = true

                if (taskConstructor != null) {
                    crossParamsClass = taskConstructor!!.parameterTypes[4]
                    crossParamsConstructor =
                        crossParamsClass?.declaredConstructors?.firstOrNull { it.parameterCount == 0 }
                    crossParamsConstructor?.isAccessible = true
                } else {
                    WeLogger.e(TAG, "警告: 未找到 ImageTask 构造函数")
                }

                imageMetadataMapField = taskClazz.declaredFields.firstOrNull {
                    Map::class.java.isAssignableFrom(it.type)
                }
                imageMetadataMapField?.isAccessible = true

                // -----------------------------------------------------------------------------
                // 语音/VFS 组件初始化
                // -----------------------------------------------------------------------------

                // VFS
                dexClassVFS.clazz.let { vfsClazz ->
                    vfsReadMethod = vfsClazz.declaredMethods.find {
                        Modifier.isStatic(it.modifiers) &&
                                it.parameterCount == 1 &&
                                it.parameterTypes[0] == String::class.java &&
                                it.returnType == java.io.InputStream::class.java
                    }
                    vfsCopyMethod = vfsClazz.declaredMethods.find {
                        Modifier.isStatic(it.modifiers) &&
                                it.parameterCount == 2 &&
                                it.parameterTypes[0] == String::class.java &&
                                it.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                                it.returnType == java.io.OutputStream::class.java
                    }
                    vfsExistsMethod = vfsClazz.declaredMethods.find {
                        Modifier.isStatic(it.modifiers) &&
                                it.parameterCount == 1 &&
                                it.parameterTypes[0] == String::class.java &&
                                it.returnType == Boolean::class.javaPrimitiveType
                    }
                }

                // Kernel
                kernelStorageMethod = dexMethodKernelGetStorage.method

                // PathUtil
                dexClassPathUtil.clazz.let { pathClazz ->
                    pathGenMethod = pathClazz.declaredMethods.find {
                        Modifier.isStatic(it.modifiers) &&
                                it.parameterCount == 5 &&
                                it.returnType == String::class.java &&
                                it.parameterTypes[4] == Int::class.javaPrimitiveType
                    }
                }

                // Voice Components
                dexClassVoiceNameGen.clazz.let { clazz ->
                    voiceNameGenMethod = clazz.declaredMethods.find {
                        Modifier.isStatic(it.modifiers) && it.parameterCount == 2 &&
                                it.parameterTypes[0] == String::class.java && it.returnType == String::class.java
                    }
                }

                dexClassVoiceParams.clazz.let { clazz ->
                    voiceParamsClass = clazz
                    val intFields =
                        clazz.declaredFields.filter { it.type == Int::class.javaPrimitiveType }
                    if (intFields.isNotEmpty()) {
                        voiceDurationField = intFields.firstOrNull()
                        voiceDurationField?.isAccessible = true
                        if (intFields.size > 1) {
                            voiceOffsetField = intFields[1]
                            voiceOffsetField?.isAccessible = true
                        }
                    }
                }

                dexClassVoiceTask.clazz.let { clazz ->
                    voiceTaskClass = clazz
                    voiceTaskConstructor = clazz.declaredConstructors.find {
                        it.parameterCount == 1 && it.parameterTypes[0] == voiceParamsClass
                    }
                }

                // Voice Service
                // 从 dexFind 结果中恢复接口类
                voiceServiceInterfaceClass = dexClassVoiceServiceInterface.clazz

                // 从 dexFind 结果中恢复方法
                voiceSendMethod = dexMethodVoiceSend.method

                // -----------------------------------------------------------------------------
                // 公共逻辑绑定
                // -----------------------------------------------------------------------------
                bindServiceFramework()
                bindImageBusinessLogic()

                WeLogger.i(TAG, "WeMessageApi 全动态链路初始化成功")
            } catch (e: Exception) {
                WeLogger.e(TAG, "Entry 初始化失败", e)
            }

            WeLogger.i(TAG, "WeMessageApi 初始化完毕")
        } catch (e: Exception) {
            WeLogger.e(TAG, "Entry 初始化异常", e)
        }
    }

    /**
     * 初始化 Unsafe 反射
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun initUnsafe() {
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafeField.isAccessible = true
            unsafeInstance = theUnsafeField.get(null)
            allocateInstanceMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            WeLogger.i(TAG, "Unsafe 能力已就绪")
        } catch (e: Exception) {
            WeLogger.e(TAG, "Unsafe 获取失败", e)
        }
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
        while (currentClass != null && currentClass != Object::class.java) {
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
            val taskClass = dexClassImageTask.clazz

            val serviceObj = getServiceMethod?.invoke(null, apiInterface) ?: return false

            val paramsClass = crossParamsClass ?: return false
            val paramsObj = XposedHelpers.newInstance(paramsClass)
            assignValueToFirstFieldByType(paramsObj, Int::class.javaPrimitiveType!!, 4)

            val taskObj =
                XposedHelpers.newInstance(taskClass, imgPath, 0, getSelfAlias(), toUser, paramsObj)
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
    fun sendFile(talker: String, filePath: String, title: String, appid: String? = null): Boolean {
        return try {
            WeLogger.i(TAG, "[sendFile] 准备发送文件消息: $filePath")
            if (shareFileMethod == null || wxFileObjectClass == null || wxMediaMessageClass == null) return false
            val fileObject = wxFileObjectClass?.newInstance() ?: return false
            wxFileObjectClass?.getField("filePath")?.set(fileObject, filePath)
            val mediaMessage = wxMediaMessageClass?.newInstance() ?: return false
            wxMediaMessageClass?.getField("mediaObject")?.set(mediaMessage, fileObject)
            wxMediaMessageClass?.getField("title")?.set(mediaMessage, title)
            shareFileMethod?.invoke(null, mediaMessage, appid ?: "", "", talker, 2, null)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "[sendFile] File 发送失败", e)
            false
        }
    }

    /** 发送私有路径下的语音文件 */
    fun sendVoice(toUser: String, path: String, durationMs: Int): Boolean {
        return try {
            val selfWxid = getSelfAlias()
            if (selfWxid.isEmpty()) throw IllegalStateException("无法获取 Wxid")

            // 获取 Service 实例
            val serviceInterface = voiceServiceInterfaceClass
                ?: throw IllegalStateException("VoiceService interface not found")

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
                val implClass = dexClassVoiceServiceImpl.clazz
                val instanceField = implClass.declaredFields.find {
                    it.name == "INSTANCE" || it.type == implClass
                }
                if (instanceField != null) {
                    instanceField.isAccessible = true
                    finalServiceObj = instanceField.get(null)
                }
            }

            if (finalServiceObj == null) throw IllegalStateException("无法获取 VoiceService 实例")

            // 准备文件
            val fileName = voiceNameGenMethod?.invoke(null, selfWxid, "amr_") as? String
                ?: throw IllegalStateException("VoiceName Gen Failed")
            val accPath = getAccPath()
            val voice2Root = if (accPath.endsWith("/")) "${accPath}voice2/" else "$accPath/voice2/"
            val destFullPath =
                pathGenMethod?.invoke(null, voice2Root, "msg_", fileName, ".amr", 2) as? String
                    ?: throw IllegalStateException("Path Gen Failed")

            if (!copyFileViaVFS(path, destFullPath)) return false

            // 构造任务
            val paramsObj = XposedHelpers.newInstance(voiceParamsClass, toUser, fileName)
            voiceDurationField?.set(paramsObj, durationMs)
            voiceOffsetField?.set(paramsObj, 0)

            val taskObj = voiceTaskConstructor?.newInstance(paramsObj)
                ?: throw IllegalStateException("Task 构造失败")

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
        return WeAppMsgApi.INSTANCE?.sendXmlAppMsg(toUser, title, appId, null, null, xmlContent)
            ?: false
    }

    /**
     * 使用微信内部 VFS 引擎进行物理拷贝
     */
    private fun copyFileViaVFS(sourcePath: String, destPath: String): Boolean {
        WeLogger.d(TAG, "VFS Copy: $sourcePath -> $destPath")
        return try {
            if (vfsReadMethod == null) throw IllegalStateException("VFS Read Method not found")
            if (vfsCopyMethod == null) throw IllegalStateException("VFS Copy Method not found")

            val input = vfsReadMethod?.invoke(null, sourcePath) as? java.io.InputStream
                ?: throw FileNotFoundException("VFS Open Failed for $sourcePath")

            val output = vfsCopyMethod?.invoke(null, destPath, false) as? java.io.OutputStream
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

    fun getSelfAlias(): String {
        return getSelfAliasMethod?.invoke(null) as? String ?: ""
    }

    private fun bindServiceFramework() {
        val smClazz = dexClassServiceManager.clazz
        getServiceMethod = smClazz.declaredMethods.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 1 && it.parameterTypes[0] == Class::class.java
        }

        val clClazz = dexClassConfigLogic.clazz
        getSelfAliasMethod = clClazz.declaredMethods.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == String::class.java && it.name.length <= 2
        }
    }

    private fun bindImageBusinessLogic() {
        val implClazz = dexClassImageServiceImpl.clazz
        val taskClazz = dexClassImageTask.clazz

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