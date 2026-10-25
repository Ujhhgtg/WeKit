package dev.ujhhgtg.wekit.features.api.net

import android.os.Handler
import android.os.Looper
import com.tencent.mm.network.v0
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.abc.IResolveDex
import dev.ujhhgtg.wekit.dex.dsl.data
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.dex.matcher.OpcodeMatchType
import dev.ujhhgtg.wekit.dex.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.net.abc.WeRequestCallback
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.reflection.bool
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

object WePacketHelper : ApiFeature(), IResolveDex {
    override val technicalId = "网络数据包服务"
    override val nameRes = R.string.feature_we_packet_helper_name
    override val categoryIds = listOf(FeatureCategoryIds.API)

    // 核心 Protobuf 类
    private val classProtoBase by dexClass {
        usingEqStrings("Cannot use this method")
        method {
            name = "op"
            paramTypes("int", "java.lang.Object[]")
        }
    }
    private val classRawReq by dexClass {
        field {
            type = "byte[]"
        }
        fieldCount = 1
        method {
            name = "<init>"
            paramTypes("byte[]")
        }
        method {
            name = "op"
            paramTypes("int", "java.lang.Object[]")
            returnType = "int"
            opcodes(names = emptyList(), matchType = OpcodeMatchType.Contains, count = 0..10)
        }
        method {
            name = "toByteArray"
            returnType = "byte[]"
            invokeMethod {
                declaredClass = "java.lang.System"
                name = "arraycopy"
            }
        }
    }
    private val classGenericResp by dexClass {
        fieldCount = 0..1
        method {
            name = "<init>"
            opcodes(names = listOf("new-instance"), matchType = OpcodeMatchType.Contains)
        }
        method {
            name = "op"
            paramTypes("int", "java.lang.Object[]")
            returnType = "int"
            opcodes(names = emptyList(), matchType = OpcodeMatchType.Contains, count = 0..10)
        }
    }
    private val classConfigBuilder by dexClass {
        searchPackages("com.tencent.mm.modelbase")
        field {
            type = classProtoBase.data.name
        }
        field {
            type = classProtoBase.data.name
        }
        field {
            type = "java.lang.String"
        }
        fieldCount = 10..Int.MAX_VALUE
    }

    // 业务特定请求类
    private val classNewSendMsgReq by dexClass()
    val classOplogReq by dexClass()

    // 网络
    val classNetScenePat by dexClass {
        superClass(WeMessageApi.classNetSceneBase.data.name)
        usingStrings("/cgi-bin/micromsg-bin/sendpat")
        method {
            name = "getType"
            returnType = "int"
            usingNumbers(849L)
        }
    }
    private val classNetDispatcher by dexClass()
    private val classIOnSceneEnd by dexClass {
        modifiers = Modifier.INTERFACE
        interfaceCount = 0
        methodCount = 1
        method {
            name = "onSceneEnd"
            paramCount = 4
            paramTypes("int", "int", "java.lang.String", WeMessageApi.classNetSceneBase.data.name)
            returnType = "void"
        }
    }
    private val classCallbackIface by dexClass {
        modifiers = 1536
        method {
            returnType = "int"
            paramCount = 5
            paramTypes(
                "int",
                "int",
                "java.lang.String",
                null,
                WeMessageApi.classNetSceneBase.data.name,
            )
        }
    }
    private val classReqResp by dexClass()

    private val methodGetNetQueue by dexMethod {
        val kernelName = WeDatabaseApi.classMmKernel.data.name
        val queueName = WeMessageApi.classNetSceneQueue.data.name
        declaredClass = kernelName
        returnType = queueName
        modifiers = 9
    }

    private val cgiReqClassMap = mutableMapOf<Int, Class<*>>()

    private val signers
        get() = WePacketSigner.signers

    private const val TAG = "WePacketHelper"

    override fun onEnable() {
        // 映射业务请求类
        cgiReqClassMap[522] = classNewSendMsgReq.clazz
        cgiReqClassMap[681] = classOplogReq.clazz
    }

    override fun resolveDex() {
        val wrapperName = classRawReq.data.superClass!!.name
        val candidates = DexResolutionContext.findClasses {
            superClass {
                className = wrapperName
            }
            fieldCount = 2
            field {
                type = "int"
            }
            field {
                type = "java.util.LinkedList"
            }
        }

        for (candidate in candidates) {
            val isMsgReq =
                DexResolutionContext.findMethods {
                        declaredClass = candidate.name
                        name = "op"
                        usingField {
                            name = "BaseRequest"
                        }
                    }
                    .isEmpty()

            if (isMsgReq) {
                classNewSendMsgReq.setDescriptor(candidate.name)
                break
            }
        }

        val cbIface = classCallbackIface.data.name
        val callbackMethod =
            DexResolutionContext.findMethods {
                    declaredClass = cbIface
                    paramCount = 5
                }
                .first()

        val reqRespName = callbackMethod.paramTypeNames[3]
        classReqResp.setDescriptor(reqRespName)

        WeLogger.i(TAG, "found ReqResp base class: $reqRespName")

        val dispatchMethod =
            DexResolutionContext.findMethods {
                    modifiers = Modifier.STATIC or Modifier.PUBLIC
                    paramCount = 3
                    paramTypes(reqRespName, cbIface, "boolean")
                }
                .firstOrNull()

        if (dispatchMethod != null) {
            classNetDispatcher.setDescriptor(dispatchMethod.declaredClassName)
        }

        val directOplogReq = DexResolutionContext.findClasses {
            superClass {
                className = classProtoBase.data.name
            }
            usingString("/cgi-bin/micromsg-bin/oplog")
            fieldCount = 1
            method {
                name = "op"
                paramTypes("int", "java.lang.Object[]")
            }
        }

        when (directOplogReq.size) {
            1 -> {
                classOplogReq.setDescriptor(directOplogReq.single())
            }

            0 -> {
                val wrapperClassData =
                    DexResolutionContext.findClasses {
                            method {
                                name = "getFuncId"
                                returnType = "int"
                                usingNumbers(681)
                            }
                            method {
                                name = "toProtoBuf"
                                returnType = "byte[]"
                            }
                        }
                        .firstOrNull()
                        ?: throw NoSuchElementException(
                            "failed to locate wrapper class based on FuncId 681",
                        )

                val protoBaseName = classProtoBase.data.name
                val realProtoClass =
                    wrapperClassData.fields
                        .mapNotNull { field ->
                            DexResolutionContext.metadata.classInfoByName(field.typeName)
                        }
                        .firstOrNull { fieldType ->
                            generateSequence(fieldType) { it.superClass }
                                .any { it.name == protoBaseName }
                        }
                        ?: throw NoSuchElementException(
                            "failed to find protobuf field in FuncId 681 wrapper",
                        )

                WeLogger.i(TAG, "located oplog successfully: ${realProtoClass.name}")
                classOplogReq.setDescriptor(realProtoClass)
            }

            else -> {
                error(
                    "multiple direct oplog request classes found: ${directOplogReq.joinToString { it.name }}",
                )
            }
        }
    }

    fun sendCgi(
        uri: String,
        cgiId: Int,
        funcId: Int,
        routeId: Int,
        reqJson: String,
        dslBlock: WeRequestDsl.() -> Unit,
    ) {
        val dsl = WeRequestDsl().apply(dslBlock)
        sendCgi(uri, cgiId, funcId, routeId, reqJson, dsl)
    }

    /**
     * Send a CGI with a pre-built protobuf request body.
     *
     * Use this when the request proto is complex enough that the JSON->protobuf shortcut
     * ([ProtoJsonBuilder]) can't express it (e.g. nested length-prefixed byte buffers such as the
     * oplog operations). The bytes are dispatched through the generic request path; signer-based
     * CGIs are not supported here.
     */
    fun sendCgi(
        uri: String,
        cgiId: Int,
        funcId: Int,
        routeId: Int,
        reqBytes: ByteArray,
        dslBlock: WeRequestDsl.() -> Unit,
    ) {
        val dsl = WeRequestDsl().apply(dslBlock)
        sendCgi(uri, cgiId, funcId, routeId, reqBytes, dsl)
    }

    fun sendCgi(
        uri: String,
        cgiId: Int,
        funcId: Int,
        routeId: Int,
        reqBytes: ByteArray,
        callback: WeRequestCallback? = null,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendGenericCgi(uri, cgiId, funcId, routeId, reqBytes, callback, null)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "[$cgiId] failed to send cgi", e)
                Handler(Looper.getMainLooper()).post {
                    callback?.onFailure(-1, -1, e.message ?: "")
                }
            }
        }
    }

    fun sendCgi(
        uri: String,
        cgiId: Int,
        funcId: Int,
        routeId: Int,
        jsonPayload: String,
        callback: WeRequestCallback? = null,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val cl = ClassLoaders.HOST
            try {
                var jsonObj = JSONObject(jsonPayload)
                var nativeNetScene: Any? = null
                var successAction: (() -> Unit)? = null

                // 签名分发
                val signer = signers.firstOrNull { it.matchesJson(cgiId) }
                if (signer != null) {
                    val result = signer.preprocessJson(cl, jsonObj)
                    result.json?.let { jsonObj = it }
                    nativeNetScene = result.nativeNetScene
                    successAction = result.onSendSuccess
                }

                // 发送逻辑
                if (nativeNetScene != null) {
                    val netQueue =
                        WeDatabaseApi.classMmKernel.clazz
                            .reflekt()
                            .invokeMethod(
                                methodGetNetQueue.method.name,
                                superclass = true,
                            )!!
                    val cgiType =
                        nativeNetScene.reflekt().invokeMethod("getType", superclass = true) as Int

                    val callbackProxy =
                        Proxy.newProxyInstance(
                            cl,
                            arrayOf(classIOnSceneEnd.clazz),
                        ) { proxy, method, args ->
                            when (method.name) {
                                "hashCode" -> {
                                    return@newProxyInstance System.identityHashCode(proxy)
                                }

                                "equals" -> {
                                    return@newProxyInstance proxy === args?.get(0)
                                }

                                "toString" -> {
                                    return@newProxyInstance "WeKitNativeCallback@${
                                        Integer.toHexString(
                                            System.identityHashCode(proxy),
                                        )
                                    }"
                                }
                            }

                            if (method.name == "onSceneEnd" && args != null) {
                                try {
                                    netQueue
                                        .reflekt()
                                        .invokeMethod("q", cgiType, proxy, superclass = true)
                                } catch (e: Throwable) {
                                    WeLogger.w(TAG, "failed to unregister native callback", e)
                                }

                                NativeResponseHandler(callback, successAction)
                                    .invoke(
                                        proxy,
                                        method,
                                        args,
                                    )
                            }

                            return@newProxyInstance null
                        }

                    // 注册并入队
                    netQueue.reflekt().apply {
                        invokeMethod("a", cgiType, callbackProxy, superclass = true)
                        invokeMethod("g", nativeNetScene, superclass = true)
                    }

                    WeLogger.i(
                        TAG,
                        "[$cgiId] native mode: successfully registered listener and added to queue",
                    )
                } else {
                    // 通用发包模式
                    val bytes = ProtoJsonBuilder.makeBytes(jsonObj)
                    sendGenericCgi(uri, cgiId, funcId, routeId, bytes, callback, successAction)
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "[$cgiId] failed to send cgi", e)
                Handler(Looper.getMainLooper()).post {
                    callback?.onFailure(-1, -1, e.message ?: "")
                }
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
        successAction: (() -> Unit)?,
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
            setField("a", finalReqObject, superclass = true)
            setField("b", classGenericResp.clazz.createInstance(), superclass = true)
            setField("c", uri, superclass = true)
            setField("d", cgiId, superclass = true)
            setField("e", funcId, superclass = true)
            setField("f", routeId, superclass = true)
            setField("l", 1, superclass = true)
            setField("n", bytes, superclass = true)
        }

        val rr = builder.reflekt().invokeMethod("a", superclass = true)
        val cbProxy =
            Proxy.newProxyInstance(
                cl,
                arrayOf(classCallbackIface.clazz),
                ResponseHandler(callback, successAction),
            )

        WeLogger.i(TAG, "[$cgiId] sending cgi...")

        classNetDispatcher
            .reflekt()
            .firstMethod {
                name = "d"
                parameters(
                    classReqResp.clazz,
                    classCallbackIface.clazz,
                    bool,
                )
            }
            .invoke(rr, cbProxy, false)
    }

    // 处理原生 NetScene 的回调
    private class NativeResponseHandler(
        val userCallback: WeRequestCallback?,
        val successAction: (() -> Unit)?,
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
                            val rrField =
                                netScene.reflekt().firstFieldOrNull {
                                    type { it isSubclassOf v0Class }
                                }

                            val rrObj =
                                if (rrField != null) {
                                    rrField.get()
                                } else {
                                    netScene.reflekt().getField("d", superclass = true)
                                }

                            if (rrObj != null) {
                                val respWrapper = rrObj.reflekt().getField("b", superclass = true)!!
                                val protoObj =
                                    respWrapper.reflekt().getField("a", superclass = true)!!
                                bytes =
                                    protoObj
                                        .reflekt()
                                        .firstMethod {
                                            name = "toByteArray"
                                            superclass()
                                        }
                                        .invoke() as? ByteArray
                            }
                        } catch (e: Throwable) {
                            WeLogger.w(
                                "NativeResponseHandler",
                                "failed to extract response bytes",
                                e,
                            )
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
        val successAction: (() -> Unit)?,
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
                            yd.reflekt().invokeMethod("initialProtobufBytes", superclass = true)
                                as? ByteArray
                        }
                            .getOrElse {
                                yd.reflekt().invokeMethod("toByteArray", superclass = true)
                                    as? ByteArray
                            }
                        userCallback?.onSuccess(bytes)
                    } else {
                        userCallback?.onFailure(
                            errType,
                            errCode,
                            args[2] as? String ?: "null (No Error Message)",
                        )
                    }
                }
                return 0
            }
            return null
        }
    }
}
