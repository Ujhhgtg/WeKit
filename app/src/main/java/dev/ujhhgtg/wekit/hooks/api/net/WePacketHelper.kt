package dev.ujhhgtg.wekit.hooks.api.net

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.extension.createInstance
import com.highcapable.kavaref.extension.isSubclassOf
import com.highcapable.kavaref.extension.toClass
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.net.abc.WeRequestCallback
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.reflection.makeAccessible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.OpCodeMatchType
import org.luckypray.dexkit.query.matchers.base.IntRange
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

@HookItem(path = "API/网络数据包服务")
object WePacketHelper : ApiHookItem(), IResolvesDex {

    // 核心 Protobuf 类
    private val classProtoBase by dexClass()
    private val classRawReq by dexClass()
    private val classGenericResp by dexClass()
    private val classConfigBuilder by dexClass()

    // 业务特定请求类
    private val classNewSendMsgReq by dexClass()
    val classOplogReq by dexClass()
    private val classNetScenePat by dexClass()

    // 网络
    val classNetSceneBase by dexClass()
    private val classNetQueue by dexClass()
    private val classMmKernel by dexClass()
    private val classNetDispatcher by dexClass()
    private val classIOnSceneEnd by dexClass()
    private val classCallbackIface by dexClass()
    private val classReqResp by dexClass()

    // 关键方法 //
    private val methodGetNetQueue by dexMethod()
    private val methodNetDispatch by dexMethod()

    private val cgiReqClassMap = mutableMapOf<Int, Class<*>>()

    private val signers = listOf(
        NewSendMsgSigner,
        EmojiSigner,
        AppMsgSigner,
        SendPatSigner { classNetScenePat.clazz }
    )

    private val TAG = nameOf(WePacketHelper)

    override fun onEnable() {
        // 映射业务请求类
        cgiReqClassMap[522] = classNewSendMsgReq.clazz
        cgiReqClassMap[681] = classOplogReq.clazz
    }

    @SuppressLint("NonUniqueDexKitData")
    override fun resolveDex(dexKit: DexKitBridge) {
        // 查找 Protobuf 基类
        classProtoBase.find(dexKit) {
            matcher {
                usingEqStrings("Cannot use this method")
                methods {
                    add {
                        name = "op"
                        paramTypes("int", "java.lang.Object[]")
                    }
                }
            }
        }

        // 查找 RawReq
        classRawReq.find(dexKit) {
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


        val wrapperName = classRawReq.clazz.superclass
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
                    classNewSendMsgReq.setDescriptor(candidate.name)
                    break
                }
            }
        }

        val protoBaseName = classProtoBase.getDescriptorString() ?: ""
        classConfigBuilder.find(dexKit) {
            searchPackages("com.tencent.mm.modelbase")

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
        classGenericResp.find(dexKit) {
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
        classNetSceneBase.find(dexKit) {
            matcher {
                usingStrings("MicroMsg.NetSceneBase")
                modifiers = Modifier.ABSTRACT
                methods {
                    add { usingNumbers(600000L) }
                }
            }
        }

        // 查找队列与核心单例
        classNetQueue.find(dexKit) {
            matcher {
                usingStrings("MicroMsg.NetSceneQueue", "waiting2running waitingQueue_size =")
            }
        }

        classMmKernel.find(dexKit) {
            matcher {
                usingStrings(":appbrand0", ":appbrand1", ":appbrand2")
                methods {
                    add {
                        modifiers = Modifier.STATIC or Modifier.PUBLIC
                        classNetQueue.clazz.let { returnType = it.name }
                    }
                }
            }
        }

        val kernelName = classMmKernel.getDescriptorString() ?: ""
        val queueName = classNetQueue.getDescriptorString() ?: ""
        methodGetNetQueue.find(dexKit) {
            matcher {
                declaredClass = kernelName
                modifiers = Modifier.STATIC or Modifier.PUBLIC
                returnType = queueName
            }
        }

        // 查找分发器与回调
        val netSceneBaseName = classNetSceneBase.getDescriptorString() ?: ""
        classCallbackIface.find(dexKit) {
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

        val cbIfaceName = classCallbackIface.getDescriptorString() ?: ""
        if (cbIfaceName.isNotEmpty()) {
            val callbackMethod = dexKit.findMethod {
                searchInClass(listOf(classCallbackIface.getClassData(dexKit)))
                matcher {
                    paramCount = 5
                }
            }.firstOrNull()

            if (callbackMethod != null) {
                val reqRespName = callbackMethod.paramTypes[3].name
                classReqResp.setDescriptor(reqRespName)

                WeLogger.i(TAG, "动态识别 ReqResp 基类: $reqRespName")

                val dispatchMethod = dexKit.findMethod {
                    matcher {
                        modifiers = Modifier.STATIC or Modifier.PUBLIC
                        paramCount = 3
                        paramTypes(reqRespName, cbIfaceName, "boolean")
                    }
                }.firstOrNull()

                if (dispatchMethod != null) {
                    classNetDispatcher.setDescriptor(dispatchMethod.className)
                    methodNetDispatch.setDescriptor(
                        dispatchMethod.className,
                        dispatchMethod.methodName,
                        dispatchMethod.methodSign
                    )
                }
            }
        }

        try {
            classOplogReq.find(dexKit) {
                matcher {
                    classProtoBase.clazz.let { superClass = it.name }
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

            val wrapperClass = wrapperClassName.toClass()
            val realProtoClass = wrapperClass.declaredFields.firstOrNull { field ->
                val type = field.type
                !type.isPrimitive &&
                        !type.name.startsWith("java.") &&
                        isExtendsBaseProtoBuf(type)
            }?.type ?: throw NoSuchElementException("在 Wrapper 类中未找到实体字段")

            WeLogger.i(TAG, "oplog 定位成功 ${realProtoClass.name}")
            classOplogReq.setDescriptor(realProtoClass.name)
        }

        classIOnSceneEnd.find(dexKit) {
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

        classNetScenePat.find(dexKit) {
            matcher {
                classNetSceneBase.clazz.let { superClass = it.name }

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
        dslBlock: WeRequestDsl.() -> Unit
    ) {
        val dsl = WeRequestDsl().apply(dslBlock)
        sendCgi(uri, cgiId, funcId, routeId, jsonPayload, dsl)
    }

    fun sendCgi(
        uri: String,
        cgiId: Int,
        funcId: Int,
        routeId: Int,
        jsonPayload: String,
        callback: WeRequestCallback? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val cl = ClassLoaders.HOST
            try {
                var jsonObj = JSONObject(jsonPayload)
                var nativeNetScene: Any? = null
                var successAction: (() -> Unit)? = null

                // 签名分发
                val signer = signers.firstOrNull { it.match(cgiId) }
                if (signer != null) {
                    val result = signer.sign(cl, jsonObj)
                    jsonObj = result.json
                    nativeNetScene = result.nativeNetScene
                    successAction = result.onSendSuccess
                }

                // 发送逻辑
                if (nativeNetScene != null) {
                    val netQueue = XposedHelpers.callStaticMethod(
                        classMmKernel.clazz,
                        methodGetNetQueue.method.name
                    )
                    val cgiType = XposedHelpers.callMethod(nativeNetScene, "getType") as Int

                    val callbackProxy = Proxy.newProxyInstance(
                        cl,
                        arrayOf(classIOnSceneEnd.clazz)
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

                            NativeResponseHandler(callback, successAction).invoke(
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
                        val rawCls = classRawReq.clazz
                        finalReqObject = XposedHelpers.newInstance(rawCls, bytes)
                        WeLogger.i(TAG, "[$cgiId] 使用通用原始类: ${rawCls.name}")
                    }

                    val builder = classConfigBuilder.clazz.createInstance()

                    XposedHelpers.setObjectField(builder, "a", finalReqObject)
                    XposedHelpers.setObjectField(
                        builder,
                        "b",
                        XposedHelpers.newInstance(classGenericResp.clazz)
                    )
                    XposedHelpers.setObjectField(builder, "c", uri)
                    XposedHelpers.setIntField(builder, "d", cgiId)
                    XposedHelpers.setIntField(builder, "e", funcId)
                    XposedHelpers.setIntField(builder, "f", routeId)
                    XposedHelpers.setIntField(builder, "l", 1)
                    XposedHelpers.setObjectField(builder, "n", bytes)

                    val rr = XposedHelpers.callMethod(builder, "a")
                    val cbProxy = Proxy.newProxyInstance(
                        cl,
                        arrayOf(classCallbackIface.clazz),
                        ResponseHandler(callback, successAction)
                    )

                    val methodD = XposedHelpers.findMethodExact(
                        classNetDispatcher.clazz,
                        "d",
                        classReqResp.clazz,
                        classCallbackIface.clazz,
                        Boolean::class.javaPrimitiveType
                    )

                    WeLogger.i(TAG, "[$cgiId] 通用发送中...")
                    methodD.invoke(null, rr, cbProxy, false)
                }

            } catch (e: Throwable) {
                WeLogger.e(TAG, "[$cgiId] 引擎异常", e)
                Handler(Looper.getMainLooper()).post { callback?.onFailure(-1, -1, e.message ?: "") }
            }
        }
    }

    // 处理原生 NetScene 的回调
    private class NativeResponseHandler(
        val userCallback: WeRequestCallback?,
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
                                it.type isSubclassOf v0Class
                            }

                            val rrObj = if (rrField != null) {
                                rrField.makeAccessible().get(netScene)
                            } else {
                                XposedHelpers.getObjectField(netScene, "d")
                            }

                            if (rrObj != null) {
                                val respWrapper = XposedHelpers.getObjectField(rrObj, "b")
                                val protoObj = XposedHelpers.getObjectField(respWrapper, "a")
                                bytes =
                                    XposedHelpers.callMethod(protoObj, "toByteArray") as? ByteArray
                                if (bytes != null) {
                                    json = WeProtoData.fromBytes(bytes).toJsonObject().toString()
                                }
                            }
                        } catch (e: Throwable) {
                            WeLogger.w("NativeResponseHandler", "提取回包 Bytes 失败: ${e.message}")
                        }

                        userCallback?.onSuccess(json, bytes)
                    } else {
                        userCallback?.onFailure(errType, errCode, errMsg)
                    }
                }
            }
            return null
        }
    }

    // 处理通用发包的回调
    private class ResponseHandler(
        val userCallback: WeRequestCallback?,
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
                            if (bytes != null) WeProtoData.fromBytes(bytes)
                                .toJsonObject()
                                .toString() else "{}"
                        userCallback?.onSuccess(json, bytes)
                    } else {
                        userCallback?.onFailure(
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
