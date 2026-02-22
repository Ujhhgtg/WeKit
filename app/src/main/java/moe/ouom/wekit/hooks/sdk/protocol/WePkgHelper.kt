package moe.ouom.wekit.hooks.sdk.protocol

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.protocol.intf.WeReqCallback
import moe.ouom.wekit.utils.Initiator.loadClass
import moe.ouom.wekit.utils.ProtoJsonBuilder
import moe.ouom.wekit.utils.WeProtoData
import moe.ouom.wekit.utils.log.WeLogger
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.OpCodeMatchType
import org.luckypray.dexkit.query.matchers.base.IntRange
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

@HookItem(path = "protocol/通用发包服务")
object WePkgHelper : ApiHookItem(), IDexFind {

    // 核心 Protobuf 类
    val dexClsProtoBase by dexClass()
    private val dexClsRawReq by dexClass()
    private val dexClsGenericResp by dexClass()
    val dexClsConfigBuilder by dexClass()

    // 业务特定请求类
    private val dexClsNewSendMsgReq by dexClass()
    val dexClsOplogReq by dexClass()
    private val dexClsNetScenePat by dexClass()

    // 网络
    val dexClsNetSceneBase by dexClass()
    private val dexClsNetQueue by dexClass()
    private val dexClsKernel by dexClass()
    private val dexClsNetDispatcher by dexClass()
    private val dexClsIOnSceneEnd by dexClass()
    private val dexClsCallbackIface by dexClass()
    private val dexClsReqResp by dexClass()

    // 关键方法 //
    private val dexMethodGetNetQueue by dexMethod()
    private val dexMethodNetDispatch by dexMethod()

    private var classLoader: ClassLoader? = null
    private val cgiReqClassMap = mutableMapOf<Int, Class<*>>()

    private val signers = listOf(
        NewSendMsgSigner(),
        EmojiSigner(),
        AppMsgSigner(),
        SendPatSigner { dexClsNetScenePat.clazz }
    )

    const val TAG = "PkgHelper"

    override fun entry(classLoader: ClassLoader) {
        this.classLoader = classLoader

        // 映射业务请求类
        cgiReqClassMap[522] = dexClsNewSendMsgReq.clazz
        cgiReqClassMap[681] = dexClsOplogReq.clazz


        WeLogger.i(TAG, "WePkgHelper 核心组件已加载")
    }

    @SuppressLint("NonUniqueDexKitData")
    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // 查找 Protobuf 基类
        dexClsProtoBase.find(dexKit, descriptors) {
            matcher {
                usingStrings("computeSize error")
                methods {
                    add {
                        name = "op"
                        paramTypes("int", "java.lang.Object[]")
                    }
                }
            }
        }

        // 查找 RawReq
        dexClsRawReq.find(dexKit, descriptors) {
            matcher {
                fields {
                    count(1)
                    add { type = "byte[]" }
                }

                methods {
                    add {
                        name = "<init>"
                        paramTypes("byte[]")
                    }

                    add {
                        name = "op"
                        paramTypes("int", "java.lang.Object[]")
                        returnType = "int"
                        opNames(
                            opNames = emptyList(),
                            matchType = OpCodeMatchType.Contains,
                            opCodeSize = IntRange(0, 10)
                        )
                    }

                    add {
                        name = "toByteArray"
                        returnType = "byte[]"
                        invokeMethods {
                            add {
                                declaredClass = "java.lang.System"
                                name = "arraycopy"
                            }
                        }
                    }
                }
            }
        }


        val wrapperName = dexClsRawReq.clazz.superclass
        if (wrapperName != null) {
            val candidates = dexKit.findClass {
                matcher {
                    superClass = wrapperName.name
                    fields {
                        count(2)
                        add { type = "int" }
                        add { type = "java.util.LinkedList" }
                    }
                }
            }

            for (candidate in candidates) {
                val isMsgReq = dexKit.findMethod {
                    searchInClass(listOf(candidate))
                    matcher {
                        name = "op"
                        addUsingField { name = "BaseRequest" }
                    }
                }.isEmpty()

                if (isMsgReq) {
                    dexClsNewSendMsgReq.setDescriptor(candidate.name)
                    descriptors[dexClsNewSendMsgReq.key] = candidate.name
                    break
                }
            }
        }

        val protoBaseName = dexClsProtoBase.getDescriptorString() ?: ""
        dexClsConfigBuilder.find(dexKit, descriptors) {
            matcher {
                fields {
                    countMin(10)
                    add { type = protoBaseName }
                    add { type = protoBaseName }
                    add { type = "java.lang.String" }
                }
            }
        }

        // 查找响应 GenericResp
        dexClsGenericResp.find(dexKit, descriptors) {
            matcher {
                fields {
                    countMax(1)
                }

                methods {
                    add {
                        name = "<init>"
                        opNames(listOf("new-instance"), OpCodeMatchType.Contains)
                    }
                    add {
                        name = "op"
                        paramTypes("int", "java.lang.Object[]")
                        returnType = "int"
                        opNames(
                            opNames = emptyList(),
                            matchType = OpCodeMatchType.Contains,
                            opCodeSize = IntRange(0, 10)
                        )
                    }
                }
            }
        }

        // 查找 NetSceneBase
        dexClsNetSceneBase.find(dexKit, descriptors) {
            matcher {
                usingStrings("MicroMsg.NetSceneBase")
                modifiers = Modifier.ABSTRACT
                methods {
                    add { usingNumbers(600000L) }
                }
            }
        }

        // 查找队列与核心单例
        dexClsNetQueue.find(dexKit, descriptors) {
            matcher {
                usingStrings("MicroMsg.NetSceneQueue", "waiting2running waitingQueue_size =")
            }
        }

        dexClsKernel.find(dexKit, descriptors) {
            matcher {
                usingStrings(":appbrand0", ":appbrand1", ":appbrand2")
                methods {
                    add {
                        modifiers = Modifier.STATIC or Modifier.PUBLIC
                        dexClsNetQueue.clazz.let { returnType = it.name }
                    }
                }
            }
        }

        val kernelName = dexClsKernel.getDescriptorString() ?: ""
        val queueName = dexClsNetQueue.getDescriptorString() ?: ""
        dexMethodGetNetQueue.find(dexKit, descriptors) {
            matcher {
                declaredClass = kernelName
                modifiers = Modifier.STATIC or Modifier.PUBLIC
                returnType = queueName
            }
        }

        // 查找分发器与回调
        val netSceneBaseName = dexClsNetSceneBase.getDescriptorString() ?: ""
        dexClsCallbackIface.find(dexKit, descriptors) {
            matcher {
                modifiers = Modifier.INTERFACE or Modifier.ABSTRACT
                methods {
                    add {
                        returnType = "int"
                        paramCount = 5
                        paramTypes("int", "int", "java.lang.String", null, netSceneBaseName)
                    }
                }
            }
        }

        val cbIfaceName = dexClsCallbackIface.getDescriptorString() ?: ""
        if (cbIfaceName.isNotEmpty()) {
            val callbackMethod = dexKit.findMethod {
                searchInClass(listOf(dexClsCallbackIface.getClassData(dexKit)))
                matcher {
                    paramCount = 5
                }
            }.firstOrNull()

            if (callbackMethod != null) {
                val reqRespName = callbackMethod.paramTypes[3].name
                dexClsReqResp.setDescriptor(reqRespName)
                descriptors[dexClsReqResp.key] = reqRespName

                WeLogger.i(TAG, "动态识别 ReqResp 基类: $reqRespName")

                val dispatchMethod = dexKit.findMethod {
                    matcher {
                        modifiers = Modifier.STATIC or Modifier.PUBLIC
                        paramCount = 3
                        paramTypes(reqRespName, cbIfaceName, "boolean")
                    }
                }.firstOrNull()

                if (dispatchMethod != null) {
                    dexClsNetDispatcher.setDescriptor(dispatchMethod.className)
                    dexMethodNetDispatch.setDescriptor(
                        dispatchMethod.className,
                        dispatchMethod.methodName,
                        dispatchMethod.methodSign
                    )
                    descriptors[dexClsNetDispatcher.key] = dispatchMethod.className
                    descriptors[dexMethodNetDispatch.key] =
                        dexMethodNetDispatch.getDescriptorString() ?: ""
                }
            }
        }

        try {
            dexClsOplogReq.find(dexKit, descriptors) {
                matcher {
                    dexClsProtoBase.clazz.let { superClass = it.name }
                    usingStrings("/cgi-bin/micromsg-bin/oplog")
                    fields { count(1) }
                    methods {
                        add {
                            name = "op"
                            paramTypes("int", "java.lang.Object[]")
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            val wrapperClassData = dexKit.findClass {
                matcher {
                    methods {
                        add {
                            name = "getFuncId"
                            returnType = "int"
                            usingNumbers(681)
                        }
                        add {
                            name = "toProtoBuf"
                            returnType = "byte[]"
                        }
                    }
                }
            }.firstOrNull() ?: throw NoSuchElementException("无法通过 FuncId 681 定位 Wrapper 类")

            val wrapperClassName = wrapperClassData.name

            val wrapperClass = loadClass(wrapperClassName)
            val realProtoClass = wrapperClass.declaredFields.firstOrNull { field ->
                val type = field.type
                !type.isPrimitive &&
                        !type.name.startsWith("java.") &&
                        isExtendsBaseProtoBuf(type)
            }?.type ?: throw NoSuchElementException("在 Wrapper 类中未找到实体字段")

            WeLogger.i("oplog 定位成功 ${realProtoClass.name}")
            descriptors[dexClsOplogReq.key] = realProtoClass.name
        }

        dexClsIOnSceneEnd.find(dexKit, descriptors) {
            matcher {
                modifiers = Modifier.INTERFACE
                interfaceCount(0)

                methods {
                    count = 1
                    add {
                        name = "onSceneEnd"
                        paramCount = 4
                        paramTypes("int", "int", "java.lang.String", netSceneBaseName)
                        returnType = "void"
                    }
                }
            }
        }

        dexClsNetScenePat.find(dexKit, descriptors) {
            matcher {
                dexClsNetSceneBase.clazz.let { superClass = it.name }

                methods {
                    add {
                        name = "getType"
                        returnType = "int"
                        usingNumbers(849)
                    }
                }
                usingStrings("/cgi-bin/micromsg-bin/sendpat")
            }
        }

        return descriptors
    }

    /**
     * 验证一个类是否继承自微信的 ProtoBuf 基类
     */
    private fun isExtendsBaseProtoBuf(cls: Class<*>?): Boolean {
        var current = cls
        while (current != null && current != Any::class.java) {
            if (current.name.contains("protobuf")
            ) {
                return true
            }
            current = current.getSuperclass()
        }
        return false
    }

    fun sendCgi(
        uri: String,
        cgiId: Int,
        funcId: Int,
        routeId: Int,
        jsonPayload: String,
        dslBlock: WeReqDsl.() -> Unit
    ) {
        val dsl = WeReqDsl().apply(dslBlock)
        sendCgi(uri, cgiId, funcId, routeId, jsonPayload, dsl as WeReqCallback)
    }

    fun sendCgi(
        uri: String,
        cgiId: Int,
        funcId: Int,
        routeId: Int,
        jsonPayload: String,
        callback: WeReqCallback? = null
    ) {
        val loader = classLoader ?: return
        Thread {
            try {
                var jsonObj = JSONObject(jsonPayload)
                var nativeNetScene: Any? = null
                var successAction: (() -> Unit)? = null

                // 签名分发
                val signer = signers.firstOrNull { it.match(cgiId) }
                if (signer != null) {
                    val result = signer.sign(loader, jsonObj)
                    jsonObj = result.json
                    nativeNetScene = result.nativeNetScene
                    successAction = result.onSendSuccess
                }

                // 发送逻辑
                if (nativeNetScene != null) {
                    val netQueue = XposedHelpers.callStaticMethod(
                        dexClsKernel.clazz,
                        dexMethodGetNetQueue.method.name
                    )
                    val cgiType = XposedHelpers.callMethod(nativeNetScene, "getType") as Int

                    val callbackProxy = Proxy.newProxyInstance(
                        loader,
                        arrayOf(dexClsIOnSceneEnd.clazz)
                    ) { proxy, method, args ->
                        when (method.name) {
                            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                            "equals" -> return@newProxyInstance proxy === args?.get(0)
                            "toString" -> return@newProxyInstance "WeKitNativeCallback@${
                                Integer.toHexString(
                                    System.identityHashCode(proxy)
                                )
                            }"
                        }

                        if (method.name == "onSceneEnd" && args != null) {
                            try {
                                XposedHelpers.callMethod(netQueue, "q", cgiType, proxy)
                            } catch (e: Throwable) {
                                WeLogger.w(TAG, "注销原生回调失败: ${e.message}")
                            }

                            NativeResponseHandler(cgiId, callback, successAction).invoke(
                                proxy,
                                method,
                                args
                            )
                        }

                        return@newProxyInstance null
                    }

                    // 注册并入队
                    XposedHelpers.callMethod(netQueue, "a", cgiType, callbackProxy)
                    XposedHelpers.callMethod(netQueue, "g", nativeNetScene)

                    WeLogger.i(TAG, "[$cgiId] 原生模式：已注册监听并入队发送")
                } else {
                    // 通用发包模式
                    val bytes = ProtoJsonBuilder.makeBytes(jsonObj)

                    val finalReqObject: Any

                    val specificReqCls = cgiReqClassMap[cgiId]

                    if (specificReqCls != null) {
                        finalReqObject = XposedHelpers.newInstance(specificReqCls)
                        XposedHelpers.callMethod(finalReqObject, "parseFrom", bytes)
                        WeLogger.i(TAG, "[$cgiId] 使用业务特定类: ${specificReqCls.name}")
                    } else {
                        val rawCls = dexClsRawReq.clazz
                        finalReqObject = XposedHelpers.newInstance(rawCls, bytes)
                        WeLogger.i(TAG, "[$cgiId] 使用通用原始类: ${rawCls.name}")
                    }

                    val builder = dexClsConfigBuilder.clazz.getDeclaredConstructor().newInstance()
                        ?: throw IllegalStateException("ConfigBuilder 实例化失败")

                    XposedHelpers.setObjectField(builder, "a", finalReqObject)
                    XposedHelpers.setObjectField(
                        builder,
                        "b",
                        XposedHelpers.newInstance(dexClsGenericResp.clazz)
                    )
                    XposedHelpers.setObjectField(builder, "c", uri)
                    XposedHelpers.setIntField(builder, "d", cgiId)
                    XposedHelpers.setIntField(builder, "e", funcId)
                    XposedHelpers.setIntField(builder, "f", routeId)
                    XposedHelpers.setIntField(builder, "l", 1)
                    XposedHelpers.setObjectField(builder, "n", bytes)

                    val rr = XposedHelpers.callMethod(builder, "a")
                    val cbProxy = Proxy.newProxyInstance(
                        loader,
                        arrayOf(dexClsCallbackIface.clazz),
                        ResponseHandler(cgiId, callback, successAction)
                    )

                    val methodD = XposedHelpers.findMethodExact(
                        dexClsNetDispatcher.clazz,
                        "d",
                        dexClsReqResp.clazz,
                        dexClsCallbackIface.clazz,
                        Boolean::class.javaPrimitiveType
                    )

                    WeLogger.i(TAG, "[$cgiId] 通用发送中...")
                    methodD.invoke(null, rr, cbProxy, false)
                }

            } catch (e: Throwable) {
                WeLogger.e(TAG, "[$cgiId] 引擎异常", e)
                Handler(Looper.getMainLooper()).post { callback?.onFail(-1, -1, e.message ?: "") }
            }
        }.start()
    }

    // 处理原生 NetScene 的回调
    private class NativeResponseHandler(
        val cgiId: Int,
        val userCallback: WeReqCallback?,
        val successAction: (() -> Unit)?
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            if (method.declaringClass == Any::class.java) return null

            // void onSceneEnd(int errType, int errCode, String errMsg, m1 netScene);
            if (method.name == "onSceneEnd" && args != null) {
                val errType = args[0] as Int
                val errCode = args[1] as Int
                val errMsg = args[2] as? String ?: "null"
                val netScene = args[3]

                Handler(Looper.getMainLooper()).post {
                    if (errType == 0 && errCode == 0) {
                        successAction?.invoke()

                        var bytes: ByteArray? = null
                        var json = "{}"

                        try {
                            val loader = netScene.javaClass.classLoader
                            val v0Class =
                                XposedHelpers.findClass("com.tencent.mm.network.v0", loader)
                            val rrField = netScene.javaClass.declaredFields.firstOrNull {
                                v0Class.isAssignableFrom(it.type)
                            }

                            val rrObj = if (rrField != null) {
                                rrField.isAccessible = true
                                rrField.get(netScene)
                            } else {
                                XposedHelpers.getObjectField(netScene, "d")
                            }

                            if (rrObj != null) {
                                val respWrapper = XposedHelpers.getObjectField(rrObj, "b")
                                val protoObj = XposedHelpers.getObjectField(respWrapper, "a")
                                bytes =
                                    XposedHelpers.callMethod(protoObj, "toByteArray") as? ByteArray
                                if (bytes != null) {
                                    json = WeProtoData().also { it.fromBytes(bytes) }.toJSON()
                                        .toString()
                                }
                            }
                        } catch (e: Throwable) {
                            WeLogger.w("NativeResponseHandler", "提取回包 Bytes 失败: ${e.message}")
                        }

                        userCallback?.onSuccess(json, bytes)
                    } else {
                        userCallback?.onFail(errType, errCode, errMsg)
                    }
                }
            }
            return null
        }
    }

    // 处理通用发包的回调
    private class ResponseHandler(
        val cgiId: Int,
        val userCallback: WeReqCallback?,
        val successAction: (() -> Unit)?
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            if (method.declaringClass == Any::class.java) return null
            if (method.name == "callback" && args != null) {
                val errType = args[0] as Int
                val errCode = args[1] as Int
                val reqResp = args[3]
                Handler(Looper.getMainLooper()).post {
                    if (errType == 0 && errCode == 0) {
                        successAction?.invoke()
                        val respWrapper = XposedHelpers.getObjectField(reqResp, "b")
                        val yd = XposedHelpers.getObjectField(respWrapper, "a")
                        val bytes = try {
                            XposedHelpers.callMethod(yd, "initialProtobufBytes") as? ByteArray
                        } catch (_: Throwable) {
                            null
                        }
                            ?: XposedHelpers.callMethod(yd, "toByteArray") as? ByteArray
                        val json =
                            if (bytes != null) WeProtoData().also { it.fromBytes(bytes) }.toJSON()
                                .toString() else "{}"
                        userCallback?.onSuccess(json, bytes)
                    } else {
                        userCallback?.onFail(
                            errType,
                            errCode,
                            args[2] as? String ?: "null (No Error Message)"
                        )
                    }
                }
                return 0
            }
            return null
        }
    }
}
