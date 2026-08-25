package dev.ujhhgtg.wekit.features.api.net.listener

import android.os.Handler
import android.os.Looper
import androidx.core.os.postDelayed
import com.tencent.kinda.framework.module.impl.WXPCommReqResp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.net.WePacketManager
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

object WePacketDispatcher : ApiFeature(), IResolveDex {

    override val technicalId = "数据包拦截与篡改服务"
    override val nameRes = R.string.feature_we_packet_dispatcher_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_packet_dispatcher_description

    private const val TAG = "WePacketDispatcher"

    private val classOnGYNetEnd by dexClass {
        searchPackages("com.tencent.mm.network")
        matcher {
            methodCount(1)
            methods {
                add {
                    name = "onGYNetEnd"
                    paramCount = 6
                }
            }
        }
    }

    private val recentRequests = ConcurrentHashMap<String, Long>()

    override fun onEnable() {
        Handler(Looper.getMainLooper()).postDelayed(3000) {
            try {
                val netSceneBaseClass = WeMessageApi.classNetSceneBase.clazz
                val callbackInterface = classOnGYNetEnd.clazz

                netSceneBaseClass.reflekt().firstMethod { name = "dispatch" }.hookBefore {
                    val v0Var = args[1] ?: return@hookBefore
                    val originalCallback = args[2] ?: return@hookBefore

                    // 有时 getUri 返回 null
                    val v0Ref = v0Var.reflekt()
                    val uri = v0Ref.invokeMethod("getUri", superclass = true) as? String? ?: "null"
                    val cgiId = v0Ref.invokeMethod("getType", superclass = true) as Int
                    if (!Preferences.verboseLog && !WePacketManager.hasInterceptors()) return@hookBefore

                    try {
                        val reqWrapper = v0Ref.invokeMethod("getReqObj", superclass = true)!!
                        val reqPbObj = reqWrapper.reflekt().getField("a", superclass = true)!!
                        val reqBytes = try {
                            reqPbObj.reflekt().firstMethodOrNull { name = "toByteArray"; superclass() }
                                ?.invoke() as? ByteArray
                        } catch (e: InvocationTargetException) {
                            // Some WeChat protobufs carry nullable nested fields; if serialization
                            // blows up here, skip tamper and let the original request continue.
                            if (e.cause is NullPointerException) {
                                if (Preferences.verboseLog) {
                                    WeLogger.w(
                                        TAG,
                                        "skip request tamper because toByteArray failed: ${reqPbObj.javaClass.name}",
                                        e.cause ?: e
                                    )
                                }
                                null
                            } else {
                                throw e
                            }
                        } ?: return@hookBefore

                        // 构造唯一标识符
                        val key =
                            "$cgiId|$uri|${reqWrapper.javaClass.name}|${reqPbObj.javaClass.name}|${reqBytes.contentToString()}"

                        // 检查是否在缓存中且时间间隔小于500毫秒
                        val currentTime = System.currentTimeMillis()
                        val lastTime = recentRequests[key]
                        if (lastTime != null && currentTime - lastTime < 500) {
                            // 直接返回，不执行任何请求处理
                            WeLogger.d(TAG, "request skipped (duplicate): $uri")
                            return@hookBefore
                        }

                        // 更新缓存
                        recentRequests[key] = currentTime
                        // 限制缓存大小为10条
                        if (recentRequests.size > 10) {
                            // 移除最旧的条目
                            val oldestEntry = recentRequests.entries.firstOrNull()
                            oldestEntry?.let {
                                recentRequests.remove(it.key)
                            }
                        }

                        WePacketManager.handleRequestTamper(uri, cgiId, reqBytes)?.let { tampered ->
                            reqPbObj.reflekt().invokeMethod("parseFrom", tampered)
                            WeLogger.i(TAG, "tampered request: $uri")
                        }
                    } catch (_: NoSuchElementException) {
                        // ignored; toByteArray might not exist since a might be Integer
                    } catch (e: Exception) {
                        WeLogger.e(TAG, "failed to tamper request", e)
                    }

                    if (Proxy.isProxyClass(originalCallback.javaClass)) return@hookBefore

                    args[2] = Proxy.newProxyInstance(
                        ClassLoaders.HOST,
                        arrayOf(callbackInterface)
                    ) { _, method, args ->
                        when (method.name) {
                            "hashCode" -> return@newProxyInstance originalCallback.hashCode()
                            "toString" -> return@newProxyInstance originalCallback.toString()
                            "equals" -> return@newProxyInstance originalCallback == args?.get(0)
                            "onGYNetEnd" -> {
                                runCatching {
                                    val respV0 = args!![4] ?: v0Var
                                    val respV0Ref = respV0.reflekt()

                                    // 处理 Kinda 框架的 WXPCommReqResp
                                    if (respV0 is WXPCommReqResp) {
                                        val originalRespBytes = respV0.wxpRespData
                                        if (originalRespBytes != null) {
                                            WePacketManager.handleResponseTamper(
                                                uri,
                                                cgiId,
                                                originalRespBytes
                                            )?.let { tampered ->
                                                respV0Ref.invokeMethod("setWXPRespData", tampered, superclass = true)
                                                WeLogger.i(
                                                    TAG,
                                                    "tampered response (WXP): $uri"
                                                )
                                            }
                                        }
                                    }
                                    // 处理标准混淆的 ICommReqResp 实现
                                    else {
                                        val respWrapper = runCatching {
                                            respV0Ref.getField("b", superclass = true)
                                        }.getOrElse { respV0Ref.invokeMethod("getRespObj", superclass = true) }

                                        if (respWrapper != null) {
                                            val respPbObj = runCatching {
                                                respWrapper.reflekt().getField("a", superclass = true)
                                            }.getOrNull()


                                            if (respPbObj != null) {
                                                val respPbObjRef = respPbObj.reflekt()

                                                runCatching {
                                                    val originalRespBytes = respPbObjRef
                                                        .invokeMethod("toByteArray", superclass = true)!! as ByteArray
                                                    WePacketManager.handleResponseTamper(
                                                        uri,
                                                        cgiId,
                                                        originalRespBytes
                                                    )?.let { tampered ->
                                                        respPbObjRef.invokeMethod("parseFrom", tampered)
                                                        WeLogger.i(
                                                            TAG,
                                                            "tampered response (PB): $uri"
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }.onFailure { WeLogger.e(TAG, "failed to tamper inner logic", it) }
                            }
                        }

                        return@newProxyInstance method.invoke(
                            originalCallback,
                            *(args ?: emptyArray())
                        )
                    }
                }
            } catch (ex: IllegalStateException) {
                WeLogger.w(TAG, "exception occurred during entry", ex)
            }
        }
    }
}
