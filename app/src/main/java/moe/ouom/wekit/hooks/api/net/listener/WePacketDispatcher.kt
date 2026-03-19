package moe.ouom.wekit.hooks.api.net.listener

import android.os.Handler
import android.os.Looper
import androidx.core.os.postDelayed
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.ClassLoaderProvider
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.abc.IResolvesDex
import moe.ouom.wekit.hooks.api.net.WePacketHelper
import moe.ouom.wekit.hooks.api.net.WePacketManager
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.utils.logging.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

@HookItem(path = "API/数据包拦截与篡改服务", desc = "响应数据包拦截与篡改")
object WePacketDispatcher : ApiHookItem(), IResolvesDex {

    private val TAG = nameof(WePacketDispatcher)
    private val classOnGYNetEnd by dexClass()

    // 缓存最近 10 条记录，避免因脚本引起的无限递归
    private val recentRequests = ConcurrentHashMap<String, Long>()

    override fun onEnable() {
        Handler(Looper.getMainLooper()).postDelayed(3000) {
            try {
                val netSceneBaseClass = WePacketHelper.classNetSceneBase.clazz
                val callbackInterface = classOnGYNetEnd.clazz

                netSceneBaseClass.asResolver().firstMethod { name = "dispatch" }.hookBefore { param ->
                    val v0Var = param.args[1] ?: return@hookBefore
                    val originalCallback = param.args[2] ?: return@hookBefore

                    // 有时 getUri 返回 null
                    val uri = (XposedHelpers.callMethod(v0Var, "getUri") ?: "null") as String
                    val cgiId = XposedHelpers.callMethod(v0Var, "getType") as Int
                    try {
                        val reqWrapper = XposedHelpers.callMethod(v0Var, "getReqObj")
                        val reqPbObj = XposedHelpers.getObjectField(reqWrapper, "a") // m.a
                        val reqBytes =
                            XposedHelpers.callMethod(reqPbObj, "toByteArray") as ByteArray

                        // 构造唯一标识符
                        val key =
                            "$cgiId|$uri|${reqWrapper?.javaClass?.name}|${reqPbObj?.javaClass?.name}|${reqBytes.contentToString()}"
                        // 检查是否在缓存中且时间间隔小于500毫秒
                        val currentTime = System.currentTimeMillis()
                        val lastTime = recentRequests[key]
                        if (lastTime != null && currentTime - lastTime < 500) {
                            // 直接返回，不执行任何请求处理
                            WeLogger.i(TAG, "Request skipped (duplicate): $uri")
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
                            XposedHelpers.callMethod(reqPbObj, "parseFrom", tampered)
                            WeLogger.i(TAG, "Request Tampered: $uri")
                        }
                    } catch (_: Throwable) {
                    }

                    if (Proxy.isProxyClass(originalCallback.javaClass)) return@hookBefore

                    param.args[2] = Proxy.newProxyInstance(
                        ClassLoaderProvider.classLoader!!,
                        arrayOf(callbackInterface)
                    ) { _, method, args ->
                        when (method.name) {
                            "hashCode" -> return@newProxyInstance originalCallback.hashCode()
                            "toString" -> return@newProxyInstance originalCallback.toString()
                            "equals" -> return@newProxyInstance originalCallback == args?.get(0)
                            "onGYNetEnd" -> {
                                try {
                                    val respV0 = args!![4] ?: v0Var
                                    val className = respV0.javaClass.name

                                    // 处理 Kinda 框架的 WXPCommReqResp
                                    if (className == "com.tencent.kinda.framework.module.impl.WXPCommReqResp") {
                                        val originalRespBytes = XposedHelpers.callMethod(
                                            respV0,
                                            "getWXPRespData"
                                        ) as? ByteArray
                                        if (originalRespBytes != null) {
                                            WePacketManager.handleResponseTamper(
                                                uri,
                                                cgiId,
                                                originalRespBytes
                                            )?.let { tampered ->
                                                XposedHelpers.callMethod(
                                                    respV0,
                                                    "setWXPRespData",
                                                    tampered
                                                )
                                                WeLogger.i(
                                                    TAG,
                                                    "Response Tampered (WXP): $uri"
                                                )
                                            }
                                        }
                                    }
                                    // 处理标准混淆的 ICommReqResp 实现
                                    else {
                                        val respWrapper = try {
                                            XposedHelpers.getObjectField(respV0, "b")
                                        } catch (_: NoSuchFieldError) {
                                            XposedHelpers.callMethod(respV0, "getRespObj")
                                        }

                                        if (respWrapper != null) {
                                            val respPbObj = try {
                                                respWrapper.asResolver().firstField { name = "a" }
                                                    .get()
                                            } catch (_: NoSuchFieldException) {
                                                null
                                            }

                                            if (respPbObj != null) {
                                                try {
                                                    val originalRespBytes = respPbObj.asResolver()
                                                        .firstMethod { name = "toByteArray" }
                                                        .invoke() as ByteArray
                                                    WePacketManager.handleResponseTamper(
                                                        uri,
                                                        cgiId,
                                                        originalRespBytes
                                                    )?.let { tampered ->
                                                        XposedHelpers.callMethod(
                                                            respPbObj,
                                                            "parseFrom",
                                                            tampered
                                                        )
                                                        WeLogger.i(
                                                            TAG,
                                                            "Response Tampered (PB): $uri"
                                                        )
                                                    }
                                                } catch (_: NoSuchElementException) {
                                                } catch (_: NoSuchMethodException) {
                                                }
                                            }
                                        }
                                    }
                                } catch (t: Throwable) {
                                    WeLogger.e(TAG, "Tamper inner logic fail", t)
                                }
                            }
                        }

                        return@newProxyInstance method.invoke(
                            originalCallback,
                            *(args ?: emptyArray())
                        )
                    }
                }
            } catch (ex: IllegalStateException) {
                WeLogger.w(TAG, "exception occurred during entry: dex not resolved yet", ex)
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        classOnGYNetEnd.find(dexKit, descriptors, true) {
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
        return descriptors
    }
}
