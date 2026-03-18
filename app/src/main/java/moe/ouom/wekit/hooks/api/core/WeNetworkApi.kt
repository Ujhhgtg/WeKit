package moe.ouom.wekit.hooks.api.core

import android.annotation.SuppressLint
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.abc.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.utils.logging.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

@SuppressLint("DiscouragedApi")
@HookItem(path = "API/网络请求服务", desc = "提供通用发包能力")
object WeNetworkApi : ApiHookItem(), IResolvesDex {

    private val TAG = nameof(WeNetworkApi)

    private val methodGetNetSceneQueue by dexMethod()

    private var methodGetMgr: Method? = null

    @Volatile
    private var methodSend: Method? = null

    private var isInitialized = false

    /**
     * 供外部调用的通用发包方法
     */
    fun sendRequest(netScene: Any) {
        if (!isInitialized) {
            WeLogger.e(TAG, "not initialized yet!")
            return
        }

        try {
            // 获取 NetSceneQueue 实例
            val queueObj = methodGetMgr?.invoke(null) ?: return

            // 获取发送方法
            val method = getSendMethod(queueObj, netScene.javaClass)

            if (method == null) {
                WeLogger.e(TAG, "send method not found for ${netScene.javaClass.simpleName}")
                return
            }

            // 执行发送
            method.invoke(queueObj, netScene)
            WeLogger.d(TAG, "request sent -> ${netScene.javaClass.simpleName}")

        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to send request", e)
        }
    }

    /**
     * 获取 cached method，如果为空则执行查找
     * 采用双重检查锁定 (DCL) 避免每次调用都 synchronized
     */
    private fun getSendMethod(queueObj: Any, netSceneClass: Class<*>): Method? {
        // 如果已经有值，直接返回，不进入同步块
        if (methodSend != null) {
            return methodSend
        }

        synchronized(this) {
            // 进入同步块后再次检查，防止并发初始化
            if (methodSend != null) {
                return methodSend
            }

            val foundMethod = findSendMethodRecursive(queueObj.javaClass, netSceneClass)

            if (foundMethod != null) {
                methodSend = foundMethod
            }

            return methodSend
        }
    }

    /**
     * 查找逻辑
     */
    private fun findSendMethodRecursive(
        queueClass: Class<*>,
        netSceneClass: Class<*>
    ): Method? {
        val candidates = ArrayList<Method>()

        for (method in queueClass.declaredMethods) {
            if (!Modifier.isPublic(method.modifiers)) continue
            val params = method.parameterTypes

            if (params.size == 1) {
                val paramType = params[0]
                // 核心判断逻辑：参数兼容 NetScene 且返回 Boolean
                // 微信的 doScene 通常参数是 NetSceneBase，它是具体 NetScene 的父类
                // 所以 paramType.isAssignableFrom(netSceneClass) 会为 true
                if (paramType.isAssignableFrom(netSceneClass) &&
                    !paramType.isPrimitive &&
                    paramType != String::class.java
                ) {

                    if (method.returnType == Boolean::class.javaPrimitiveType ||
                        method.returnType == Boolean::class.java
                    ) {
                        candidates.add(method)
                    }
                }
            }
        }

        return candidates.firstOrNull()
    }

    // Dex 查找逻辑
    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // 查找 NetSceneQueue 类
        val netSceneQueueClass = dexKit.findClass {
            matcher {
                methods {
                    add {
                        paramCount = 4
                        usingStrings("MicroMsg.Mvvm.NetSceneObserverOwner")
                    }
                }
            }
        }.singleOrNull()

        if (netSceneQueueClass == null) {
            error("NetSceneQueue class not found")
        }

        methodGetNetSceneQueue.find(dexKit, allowMultiple = true, descriptors = descriptors) {
            matcher {
                modifiers = Modifier.STATIC
                paramCount = 0
                returnType = netSceneQueueClass.name
            }
        }

        return descriptors
    }

    override fun onEnable() {
        methodGetMgr = methodGetNetSceneQueue.method

        isInitialized = true
        WeLogger.i(TAG, "initialized")
    }

    override fun onDisable() {
        isInitialized = false
        methodGetMgr = null

        // 重置缓存，防止持有旧 ClassLoader 的引用
        synchronized(this) {
            methodSend = null
        }
    }
}
