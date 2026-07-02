package dev.ujhhgtg.wekit.features.api.net

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import com.tencent.mm.network.v0
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.isBuiltin
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.net.abc.WeRequestCallback
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.reflection.asClass
import dev.ujhhgtg.wekit.utils.reflection.bool
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

@Feature(name = "网络数据包服务", categories = ["API"])
object WePacketHelper : ApiFeature(), IResolveDex {

    // 核心 Protobuf 类
    private val classProtoBase by dexClass {
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
    private val classRawReq by dexClass {
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
    private val classGenericResp by dexClass {
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
    private val classConfigBuilder by dexClass {
        searchPackages("com.tencent.mm.modelbase")

        matcher {
            fields {
                countMin(10)
                add { type(classProtoBase.clazz) }
                add { type(classProtoBase.clazz)  }
                add { type = "java.lang.String" }
            }
        }
    }

    // 业务特定请求类
    private val classNewSendMsgReq by dexClass()
    val classOplogReq by dexClass()

    // 网络
    val classNetSceneBase by dexClass {
        matcher {
            usingStrings("MicroMsg.NetSceneBase")
            modifiers = Modifier.ABSTRACT
            methods {
                add { usingNumbers(600000L) }
            }
        }
    }
    private val classNetScenePat by dexClass {
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
    private val classNetQueue by dexClass {
        matcher {
            usingStrings("MicroMsg.NetSceneQueue", "waiting2running waitingQueue_size =")
        }
    }
    private val classMmKernel by dexClass {
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
    private val classNetDispatcher by dexClass()
    private val classIOnSceneEnd by dexClass {
        matcher {
            modifiers = Modifier.INTERFACE
            interfaceCount(0)

            methods {
                count = 1
                add {
                    name = "onSceneEnd"
                    paramCount = 4
                    paramTypes("int", "int", "java.lang.String", classNetSceneBase.getDescriptorString()!!)
                    returnType = "void"
                }
            }
        }
    }
    private val classCallbackIface by dexClass {
        matcher {
            modifiers = Modifier.INTERFACE or Modifier.ABSTRACT
            methods {
                add {
                    returnType = "int"
                    paramCount = 5
                    paramTypes("int", "int", "java.lang.String", null, classNetSceneBase.getDescriptorString()!!)
                }
            }
        }
    }
    private val classReqResp by dexClass()

    // 关键方法 //
    private val methodGetNetQueue by dexMethod {
        val kernelName = classMmKernel.getDescriptorString() ?: ""
        val queueName = classNetQueue.getDescriptorString() ?: ""
        matcher {
            declaredClass = kernelName
            modifiers = Modifier.STATIC or Modifier.PUBLIC
            returnType = queueName
        }
    }
//    private val methodNetDispatch by dexMethod()

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
        val wrapperName = classRawReq.clazz.superclass!!
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

        val cbIface = classCallbackIface.clazz
        val callbackMethod = dexKit.findMethod {
            searchInClass(listOf(classCallbackIface.getClassData(dexKit)))
            matcher {
                paramCount = 5
            }
        }.first()

        val reqRespName = callbackMethod.paramTypes[3]
        classReqResp.setDescriptor(reqRespName)

        WeLogger.i(TAG, "found ReqResp base class: $reqRespName")

        val dispatchMethod = dexKit.findMethod {
            matcher {
                modifiers = Modifier.STATIC or Modifier.PUBLIC
                paramCount = 3
                paramTypes(reqRespName.asClass, cbIface, bool)
            }
        }.firstOrNull()

        if (dispatchMethod != null) {
            classNetDispatcher.setDescriptor(dispatchMethod.className)
//            methodNetDispatch.setDescriptor(
//                dispatchMethod.className,
//                dispatchMethod.methodName,
//                dispatchMethod.methodSign
//            )
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
            }.firstOrNull() ?: throw NoSuchElementException("failed to locate wrapper class based on FuncId 681")

            val wrapperClassName = wrapperClassData.name

            val wrapperClass = wrapperClassName.toClass()
            val realProtoClass = wrapperClass.declaredFields.firstOrNull { field ->
                val type = field.type
                !type.isBuiltin && isExtendsBaseProtoBuf(type)
            }?.type ?: throw NoSuchElementException("failed to find field in wrapper class")

            WeLogger.i(TAG, "located oplog successfully: ${realProtoClass.name}")
            classOplogReq.setDescriptor(realProtoClass.name)
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

    /**
     * Send a CGI with a pre-built protobuf request body.
     *
     * Use this when the request proto is complex enough that the JSON->protobuf shortcut
     * ([ProtoJsonBuilder]) can't express it (e.g. nested length-prefixed byte buffers such
     * as the oplog operations). The bytes are dispatched through the generic request path;
     * signer-based CGIs are not supported here.
     */
    fun sendCgiRaw(
        uri: String,
        cgiId: Int,
        funcId: Int,
        routeId: Int,
        reqBytes: ByteArray,
        dslBlock: WeRequestDsl.() -> Unit
    ) {
        val dsl = WeRequestDsl().apply(dslBlock)
        sendCgiRaw(uri, cgiId, funcId, routeId, reqBytes, dsl)
    }

    fun sendCgiRaw(
        uri: String,
        cgiId: Int,
        funcId: Int,
        routeId: Int,
        reqBytes: ByteArray,
        callback: WeRequestCallback? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendGenericCgi(uri, cgiId, funcId, routeId, reqBytes, callback, null)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "[$cgiId] failed to send cgi", e)
                Handler(Looper.getMainLooper()).post { callback?.onFailure(-1, -1, e.message ?: "") }
            }
        }
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
                    val netQueue = classMmKernel.clazz.reflekt().invokeMethod(
                        methodGetNetQueue.method.name, null, superclass = true
                    )!!
                    val cgiType = nativeNetScene.reflekt().invokeMethod("getType", superclass = true) as Int

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
                                netQueue.reflekt().invokeMethod("q", cgiType, proxy, superclass = true)
                            } catch (e: Throwable) {
                                WeLogger.w(TAG, "failed to unregister native callback", e)
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
                    netQueue.reflekt().apply {
                        invokeMethod("a", cgiType, callbackProxy, superclass = true)
                        invokeMethod("g", nativeNetScene, superclass = true)
                    }

                    WeLogger.i(TAG, "[$cgiId] native mode: successfully registered listener and added to queue")
                } else {
                    // 通用发包模式
                    val bytes = ProtoJsonBuilder.makeBytes(jsonObj)
                    sendGenericCgi(uri, cgiId, funcId, routeId, bytes, callback, successAction)
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "[$cgiId] failed to send cgi", e)
                Handler(Looper.getMainLooper()).post { callback?.onFailure(-1, -1, e.message ?: "") }
            }
        }
    }

    /**
     * Dispatch a request through the generic (non-native) request path using raw protobuf bytes.
     */
    private fun sendGenericCgi(
        uri: String,
        cgiId: Int,
        funcId: Int,
        routeId: Int,
        bytes: ByteArray,
        callback: WeRequestCallback?,
        successAction: (() -> Unit)?
    ) {
        val cl = ClassLoaders.HOST
        val finalReqObject: Any

        val specificReqCls = cgiReqClassMap[cgiId]

        if (specificReqCls != null) {
            finalReqObject = specificReqCls.createInstance()
            finalReqObject.reflekt().invokeMethod("parseFrom", bytes, superclass = true)
            WeLogger.i(TAG, "[$cgiId] using specific class: ${specificReqCls.name}")
        } else {
            val rawCls = classRawReq.clazz
            finalReqObject = rawCls.createInstance(bytes)
            WeLogger.i(TAG, "[$cgiId] using generic class: ${rawCls.name}")
        }

        val builder = classConfigBuilder.clazz.createInstance()

        builder.reflekt().apply {
            setField("a", finalReqObject)
            setField("b", classGenericResp.clazz.createInstance())
            setField("c", uri)
            setField("d", cgiId)
            setField("e", funcId)
            setField("f", routeId)
            setField("l", 1)
            setField("n", bytes)
        }

        val rr = builder.reflekt().invokeMethod("a", superclass = true)
        val cbProxy = Proxy.newProxyInstance(
            cl,
            arrayOf(classCallbackIface.clazz),
            ResponseHandler(callback, successAction)
        )

        WeLogger.i(TAG, "[$cgiId] sending cgi...")

        classNetDispatcher.reflekt().firstMethod {
            name = "d"
            parameters(
                classReqResp.clazz,
                classCallbackIface.clazz,
                bool
            )
        }.invoke(null, rr, cbProxy, false)
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

                        try {
                            val v0Class = v0::class.java
                            val rrField = netScene.reflekt().firstFieldOrNull {
                                type { it isSubclassOf v0Class }
                            }

                            val rrObj = if (rrField != null) {
                                rrField.get(netScene)
                            } else {
                                netScene.reflekt().getField("d", superclass = true)
                            }

                            if (rrObj != null) {
                                val respWrapper = rrObj.reflekt().getField("b", superclass = true)!!
                                val protoObj = respWrapper.reflekt().getField("a", superclass = true)!!
                                bytes = protoObj.reflekt().firstMethod { name ="toByteArray"; superclass() }.invoke() as? ByteArray
                            }
                        } catch (e: Throwable) {
                            WeLogger.w(nameOf(NativeResponseHandler::class), "failed to extract response bytes", e)
                        }

                        userCallback?.onSuccess(bytes)
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
                        val respWrapper = reqResp.reflekt().getField("b", superclass = true)!!
                        val yd = respWrapper.reflekt().getField("a", superclass = true)!!
                        val bytes = runCatching {
                            yd.reflekt().invokeMethod("initialProtobufBytes", superclass = true) as? ByteArray
                        }.getOrElse { yd.reflekt().invokeMethod("toByteArray", superclass = true) as? ByteArray }
                        userCallback?.onSuccess(bytes)
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
