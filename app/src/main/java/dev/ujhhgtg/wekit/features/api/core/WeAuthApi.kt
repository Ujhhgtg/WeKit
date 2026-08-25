package dev.ujhhgtg.wekit.features.api.core

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import java.lang.reflect.Proxy
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicBoolean

object WeAuthApi : ApiFeature(), IResolveDex {

    override val technicalId = "授权与登录服务"
    override val nameRes = R.string.feature_we_auth_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_auth_api_description

    private const val TAG = "WeAuthApi"

    val classNetSceneJSLogin by dexClass {
        matcher {
            usingEqStrings("MicroMsg.webview.NetSceneJSLogin", "/cgi-bin/mmbiz-bin/js-login")
        }
    }

    fun jsLogin(appId: String, onResult: (String?) -> Unit) {
        // 调用方 (WeChatService.jsLogin) 用无超时的 suspendCancellableCoroutine 包装该回调,
        // 因此必须保证 onResult 不多不少地被调用一次
        val delivered = AtomicBoolean(false)
        fun deliver(code: String?) {
            if (delivered.compareAndSet(false, true)) {
                onResult(code)
            } else {
                WeLogger.w(TAG, "jsLogin result already delivered, ignoring code=$code")
            }
        }

        try {
            val netScene = classNetSceneJSLogin.clazz.createInstance(
                appId,
                LinkedList<String>(),
                1,
                "",
                "",
                0,
                1089,
                null
            )

            val queue = WeDatabaseApi.classMmKernel.clazz.reflekt()
                .firstMethod {
                    returnType = WeNetSceneApi.methodAddNetSceneToQueue.method.declaringClass
                }.invokeStatic()!!

            val getDispatcherMethod = queue.javaClass.methods.first { method ->
                method.parameterCount == 0 &&
                        method.returnType.isInterface &&
                        method.returnType.name.startsWith("com.tencent.mm.")
            }
            val dispatcher = getDispatcherMethod.invoke(queue)

            val doSceneMethod = netScene.javaClass.reflekt().firstMethod {
                name = "doScene"
            }
            val callbackInterface = doSceneMethod.parameterTypes[1]

            val callbackProxy = Proxy.newProxyInstance(
                ClassLoaders.HOST,
                arrayOf(callbackInterface)
            ) { _, method, args ->
                if (method.name == "onSceneEnd") {
                    // onResult 必须且仅被调用一次, 因此每条失败分支都要提前返回并回调 null,
                    // 否则上层的 suspendCancellableCoroutine 会永远挂起
                    try {
                        val errType = args[0] as Int
                        val errCode = args[1] as Int
                        val errMsg = args[2] as? String
                        val scene = args[3]

                        if (errType != 0 || errCode != 0 || scene == null) {
                            WeLogger.w(TAG, "jsLogin failed: errType=$errType, errCode=$errCode, errMsg=$errMsg")
                            deliver(null)
                            return@newProxyInstance null
                        }

                        val reqResp = scene.reflekt().firstMethod {
                            name = "getReqResp"
                            superclass()
                        }.invoke()
                        if (reqResp == null) {
                            WeLogger.w(TAG, "jsLogin succeeded but reqResp is null")
                            deliver(null)
                            return@newProxyInstance null
                        }

                        val respObj = reqResp.reflekt().firstMethod {
                            name = "getRespObj"
                            superclass()
                        }.invoke()
                        if (respObj == null) {
                            WeLogger.w(TAG, "jsLogin succeeded but respObj is null")
                            deliver(null)
                            return@newProxyInstance null
                        }

                        val proto = respObj.reflekt().getField("a")
                        if (proto == null) {
                            WeLogger.w(TAG, "jsLogin succeeded but response proto is null")
                            deliver(null)
                            return@newProxyInstance null
                        }

                        val stringFields = proto.javaClass.declaredFields.filter { it.type == String::class.java }
                        val code = if (stringFields.isNotEmpty()) {
                            WeLogger.d(TAG, "found string fields on response: ${stringFields.map { it.name }}")
                            stringFields[0].isAccessible = true
                            stringFields[0].get(proto) as? String
                        } else null

                        WeLogger.i(TAG, "jsLogin success, code: $code")
                        deliver(code)
                    } catch (t: Throwable) {
                        WeLogger.e(TAG, "error parsing onSceneEnd", t)
                        deliver(null)
                    }
                }
                null
            }

            doSceneMethod.invoke(netScene, dispatcher, callbackProxy)
        } catch (e: Exception) {
            WeLogger.e(TAG, "jsLogin execution failed", e)
            deliver(null)
        }
    }
}
