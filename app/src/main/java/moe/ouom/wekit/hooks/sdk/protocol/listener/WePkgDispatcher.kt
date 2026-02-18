package moe.ouom.wekit.hooks.sdk.protocol.listener

import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.protocol.WePkgHelper
import moe.ouom.wekit.hooks.sdk.protocol.WePkgManager
import moe.ouom.wekit.utils.common.SyncUtils
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Proxy

@HookItem(path = "protocol/wepkg_dispatcher", desc = "WePkg 请求/响应数据包拦截与篡改")
class WePkgDispatcher : ApiHookItem(), IDexFind {
    private val dexClsOnGYNetEnd by dexClass()

    override fun entry(classLoader: ClassLoader) {
        SyncUtils.postDelayed(3000) {
            val netSceneBaseClass = WePkgHelper.INSTANCE?.dexClsNetSceneBase?.clazz
            val callbackInterface = dexClsOnGYNetEnd.clazz

//            hookBuilder()

            if (netSceneBaseClass == null) {
                WeLogger.e("PkgDispatcher", "无法找到 NetSceneBase 类，跳过 WePkg 拦截器注入")
                return@postDelayed
            }

            hookBefore(netSceneBaseClass, "dispatch") { param ->
                val v0Var = param.args[1] ?: return@hookBefore
                val originalCallback = param.args[2] ?: return@hookBefore

                // 有时 getUri 返回 null
                val uri = (XposedHelpers.callMethod(v0Var, "getUri") ?: "null") as String
                val cgiId = XposedHelpers.callMethod(v0Var, "getType") as Int
                try {
                    val reqWrapper = XposedHelpers.callMethod(v0Var, "getReqObj")
                    val reqPbObj = XposedHelpers.getObjectField(reqWrapper, "a") // m.a
                    val reqBytes = XposedHelpers.callMethod(reqPbObj, "toByteArray") as ByteArray

                    WePkgManager.handleRequestTamper(uri, cgiId, reqBytes)?.let { tampered ->
                        XposedHelpers.callMethod(reqPbObj, "parseFrom", tampered)
                        WeLogger.i("PkgDispatcher", "Request Tampered: $uri")
                    }
                } catch (_: Throwable) {
                }

                if (Proxy.isProxyClass(originalCallback.javaClass)) return@hookBefore

                param.args[2] = Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(callbackInterface)
                ) { _, method, args ->
                    when (method.name) {
                        "hashCode" -> return@newProxyInstance originalCallback.hashCode()
                        "toString" -> return@newProxyInstance originalCallback.toString()
                        "equals" -> return@newProxyInstance originalCallback.equals(args?.get(0))
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
                                        WePkgManager.handleResponseTamper(
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
                                                "PkgDispatcher",
                                                "Response Tampered (WXP): $uri"
                                            )
                                        }
                                    }
                                }
                                // 处理标准混淆的 ICommReqResp 实现
                                else {
                                    var respWrapper: Any?
                                    try {
                                        respWrapper = XposedHelpers.getObjectField(respV0, "b")
                                    } catch (_: NoSuchFieldError) {
                                        respWrapper = XposedHelpers.callMethod(respV0, "getRespObj")
                                    }

                                    if (respWrapper != null) {
                                        val respPbObj = try {
                                            XposedHelpers.getObjectField(respWrapper, "a")
                                        } catch (_: NoSuchFieldError) {
                                            null
                                        }

                                        if (respPbObj != null) {
                                            if (respPbObj !is String) {
                                                val originalRespBytes = XposedHelpers.callMethod(
                                                    respPbObj,
                                                    "toByteArray"
                                                ) as ByteArray
                                                WePkgManager.handleResponseTamper(
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
                                                        "PkgDispatcher",
                                                        "Response Tampered (PB): $uri"
                                                    )
                                                }
                                            }
                                            else {
                                                WeLogger.i("PkgDispatcher", "response is String, no need to tamper: $uri")
                                            }
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                WeLogger.e("PkgDispatcher", "Tamper inner logic fail", t)
                            }
                        }
                    }

                    return@newProxyInstance method.invoke(originalCallback, *(args ?: emptyArray()))
                }
            }
        }
    }

    private fun hookBuilder() {
        val builderClass = WePkgHelper.INSTANCE?.dexClsConfigBuilder?.clazz

        try {
            if (builderClass == null) {
                WeLogger.e("WePkgListener-gen", "找不到 Builder 类")
                return
            }
            WeLogger.i("WePkgListener-gen", "start Hook ${builderClass.name}.a() 方法")
            hookAfter(builderClass, "a") { param ->
                val builder = param.thisObject

                val cgiId = try {
                    XposedHelpers.getIntField(builder, "d")
                } catch (_: Throwable) {
                    0
                }
                val funcId = try {
                    XposedHelpers.getIntField(builder, "e")
                } catch (_: Throwable) {
                    0
                }
                val routeId = try {
                    XposedHelpers.getIntField(builder, "f")
                } catch (_: Throwable) {
                    0
                }
                val uri = try {
                    XposedHelpers.getObjectField(builder, "c") as? String ?: ""
                } catch (_: Throwable) {
                    ""
                }

                // 获取 Request 对象的类名
                var reqClassName = "Unknown"
                try {
                    val reqObj = XposedHelpers.getObjectField(builder, "a")
                    if (reqObj != null) {
                        reqClassName = reqObj.javaClass.name
                    }
                } catch (_: Throwable) {
                }

                val configLog = "$cgiId to Triple(\"$reqClassName\", $funcId, $routeId), // $uri"
                WeLogger.w("WePkgListener-gen", configLog)
            }
        } catch (e: Throwable) {
            WeLogger.e("WePkgListener-gen", "Builder Hook 失败: ${e.message}")
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        dexClsOnGYNetEnd.find(dexKit, descriptors, true) {
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