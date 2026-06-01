package dev.ujhhgtg.wekit.hooks.items.chat

import android.app.Activity
import android.content.pm.ActivityInfo
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.resolve
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/音视频聊天使用画中画", description = "让微信的音视频聊天使用原生的画中画模式而非悬浮窗 (没写完)")
object PipVoip : SwitchHookItem(), IResolvesDex {

    private val TAG = This.Class.simpleName

//    private val stubActivityProxyClass by lazy {
//        Proxy.getProxyClass(
//            ClassLoaderProvider.classLoader!!,
//            methodGetActivityProxy.method.returnType,
//            "ig0.m0".toClass(),
//            "ig0.u0".toClass()
//        )
//    }

//    private lateinit var originalActivityProxyInstance: Any
//    private lateinit var stubActivityProxyInstance: Any

    override fun onEnable() {
//        stubActivityProxyClass // trigger lazy calculation
//
//        methodGetActivityProxy.hookAfter {
//            if (!::stubActivityProxyInstance.isInitialized) {
//                originalActivityProxyInstance = result
//                val handler = InvocationHandler { _, method, args ->
//                    WeLogger.d(TAG, "method ${method.name} invoked on StubActivityProxy with ${args.size} argument(s)\n")
//                    return@InvocationHandler runCatching {
//                        originalActivityProxyInstance.asResolver()
//                            .firstMethod {
//                                name = method.name
//                                superclass()
//                            }.invoke(*args)
//                    }.onFailure { WeLogger.e(TAG, "exception", it) }.getOrThrow()
//                }
//                stubActivityProxyInstance = stubActivityProxyClass.createInstance(handler)
//            }
//
//            result = stubActivityProxyInstance
//        }

        classVoipActivityProxy.asResolver()
            .firstMethod {
                name = "dealContentView"
            }.hookBefore {
                WeLogger.d(TAG, "dealContentView: ${args[0].javaClass}")
            }

        ActivityInfo::class.resolve()
            .firstConstructor()
            .hookAfter {
                val info = thisObject as ActivityInfo
                if (info.name == "${PackageNames.WECHAT}.plugin.voip.ui.VideoActivity")
                    applyFlags(info)
            }

        Activity::class.resolve()
            .firstMethod {
                name = "onPictureInPictureModeChanged"
                parameterCount = 2
            }.hookBefore {
                if (thisObject.javaClass.simpleName != "VideoActivity") return@hookBefore

                val isInPip = args[0] as Boolean
                if (isInPip) {
                    WeLogger.i(TAG, "currently in pip")
                } else {
                    WeLogger.i(TAG, "currently not in pip")
                }
            }
    }

    private fun applyFlags(info: ActivityInfo) {
        var flags = info.flags
        flags = flags or FLAG_SUPPORTS_PICTURE_IN_PICTURE
        info.flags = flags

        info.asResolver()
            .firstField { name = "resizeMode" }
            .set(RESIZE_MODE_RESIZEABLE)
    }

    private const val FLAG_SUPPORTS_PICTURE_IN_PICTURE = 0x400000
    private const val RESIZE_MODE_RESIZEABLE = 2

    private val classVoipActivityProxy by dexClass()

    override fun resolveDex(dexKit: DexKitBridge) {
        classVoipActivityProxy.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.ILinkVoipVideoActivityProxy-")
            }
        }
    }
}
