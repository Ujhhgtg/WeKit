package moe.ouom.wekit.hooks.sdk.base

import android.annotation.SuppressLint
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

@SuppressLint("DiscouragedApi")
@HookItem(path = "API/网络请求服务", desc = "提供通用发包能力")
object WeNetworkApi : ApiHookItem(), IDexFind {

    private val dexMethodGetNetSceneQueue by dexMethod()

    private var methodGetMgr: Method? = null

    // 使用 @Volatile 保证多线程可见性
    @Volatile
    private var methodSend: Method? = null

    private var isInitialized = false

    /**
     * 供外部调用的通用发包方法
     */
    fun sendRequest(netScene: Any) {
        if (!isInitialized) {
            WeLogger.e("WeNetworkApi: Not initialized yet!")
            return
        }

        try {
            // 获取 NetSceneQueue 实例
            val queueObj = methodGetMgr?.invoke(null) ?: return

            // 获取发送方法
            val method = getSendMethod(queueObj, netScene.javaClass)

            if (method == null) {
                WeLogger.e("WeNetworkApi: Send method not found for ${netScene.javaClass.simpleName}")
                return
            }

            // 执行发送
            method.invoke(queueObj, netScene)
            WeLogger.d("WeNetworkApi: Request sent -> ${netScene.javaClass.simpleName}")

        } catch (e: Throwable) {
            WeLogger.e("WeNetworkApi: Failed to send request", e)
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

            // 确实没有，开始查找
            WeLogger.i("WeNetworkApi: Cache miss, searching for doScene...")
            val foundMethod = findSendMethodRecursive(queueObj.javaClass, netSceneClass)

            if (foundMethod != null) {
                methodSend = foundMethod
                WeLogger.i("WeNetworkApi: Method found and cached -> ${foundMethod.name}")
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
    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
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
            throw RuntimeException("NetSceneQueue class not found")
        }

        dexMethodGetNetSceneQueue.find(dexKit, allowMultiple = true, descriptors = descriptors) {
            matcher {
                modifiers = Modifier.STATIC
                paramCount = 0
                returnType = netSceneQueueClass.name
            }
        }

        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        try {
            methodGetMgr = dexMethodGetNetSceneQueue.method

            isInitialized = true
            WeLogger.i("WeNetworkApi: Initialized")
        } catch (e: Throwable) {
            WeLogger.e("WeNetworkApi: Init failed", e)
        }
    }

    override fun unload(classLoader: ClassLoader) {
        isInitialized = false
        methodGetMgr = null

        // 重置缓存，防止持有旧 ClassLoader 的引用
        synchronized(this) {
            methodSend = null
        }
    }
}
