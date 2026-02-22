package moe.ouom.wekit.util.script

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import moe.ouom.wekit.constants.MMVersion
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.util.log.WeLogger
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import java.text.MessageFormat
import java.util.*
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

/**
 * Rhino JavaScript执行器
 */
class JsExecutor private constructor() {
    private var mRhinoContext: Context? = null
    private var mScope: Scriptable? = null
    private var mScriptEngine: ScriptEngine? = null
    private val mMainHandler = Handler(Looper.getMainLooper())
    private var mInitialized = false
    private var mAppContext: Context? = null

    companion object {
        @Volatile
        private var INSTANCE: JsExecutor? = null

        @JvmStatic
        fun getInstance(): JsExecutor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: JsExecutor().also { INSTANCE = it }
            }
        }

        /**
         * Rhino MessageProvider 降级实现
         */
        private class FallbackMessageProvider : ScriptRuntime.MessageProvider {
            private val enBundle = try {
                ResourceBundle.getBundle(
                    "org.mozilla.javascript.resources.Messages",
                    Locale.ENGLISH
                )
            } catch (_: Exception) {
                null
            }

            // 始终使用英文资源 不尝试当前 locale
            private fun getRawMessage(key: String): String {
                // 只尝试英文消息
                enBundle?.let {
                    try {
                        return it.getString(key)
                    } catch (_: MissingResourceException) {
                        // 忽略
                    }
                }
                // 如果英文资源也失败 返回 key 本身
                return key
            }

            // 实现带参数格式化的方法
            override fun getMessage(messageId: String, arguments: Array<out Any>?): String {
                val pattern = getRawMessage(messageId)
                return if (!arguments.isNullOrEmpty()) {
                    MessageFormat.format(pattern, *arguments)
                } else {
                    pattern
                }
            }
        }

        /**
         * 初始化 Rhino MessageProvider
         * 反射修改 一次生效 全局有效
         */
        private fun initRhinoMessageProvider() {
            try {
                val field = ScriptRuntime::class.java.getDeclaredField("messageProvider")
                field.isAccessible = true
                field.set(null, FallbackMessageProvider())
                WeLogger.i("Rhino MessageProvider initialized with fallback")
            } catch (e: Exception) {
                WeLogger.e("Failed to init Rhino MessageProvider", e)
            }
        }
    }

    /**
     * 初始化Rhino引擎
     * @param applicationContext Application级别的Context
     */
    fun initialize(applicationContext: Context) {
        if (mInitialized) {
            WeLogger.w("JsExecutor already initialized")
            return
        }

        // 反射修改 MessageProvider（只需一次）
        initRhinoMessageProvider()
        // 保存 ApplicationContext 引用
        mAppContext = applicationContext.applicationContext
        initializeInternal()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initializeInternal() {
        if (mInitialized) {
            return
        }

        try {
            val context = mAppContext ?: throw IllegalStateException("ApplicationContext not set")
            // 获取模块 ClassLoader
            val moduleClassLoader = this.javaClass.classLoader
            // 使用模块 ClassLoader 加载
            val engineManager = ScriptEngineManager(moduleClassLoader)

            val rhinoEngine = engineManager.getEngineByName("rhino")

            mScriptEngine = rhinoEngine
            mInitialized = true
            WeLogger.i("JsExecutor initialized with Rhino")

            // 初始化 ScriptFileManager 和 ScriptEvalManager
            initRelatedManagers(context)

        } catch (e: Exception) {
            WeLogger.e("Rhino init failed: ${e.message}")
            mRhinoContext = null
            mScope = null
            mScriptEngine = null
            mAppContext = null
        }
    }

    /**
     * 初始化相关的管理器
     */
    private fun initRelatedManagers(context: Context) {
        try {
            // 初始化 ScriptFileManager
            val scriptFileManager = ScriptFileManager.getInstance()
            if (!scriptFileManager.isInitialized()) {
                scriptFileManager.initialize(context)
                WeLogger.i("JsExecutor: ScriptFileManager initialized")
            }

            // 初始化 ScriptEvalManager
            val scriptEvalManager = ScriptEvalManager.getInstance()
            if (!scriptEvalManager.isInitialized()) {
                scriptEvalManager.initialize(scriptFileManager)
                WeLogger.i("JsExecutor: ScriptEvalManager initialized")
            }

        } catch (e: Exception) {
            WeLogger.e("Failed to init related managers: ${e.message}")
        }
    }

    /**
     * 注入脚本接口
     * @param sendCgi CGI发送函数
     * @param protoUtils 协议工具对象
     * @param dataBaseUtils 数据库工具对象
     * @param messageUtils 消息工具对象
     */
    @Suppress("unused")
    fun injectScriptInterfaces(
        sendCgi: Any,
        protoUtils: Any,
        dataBaseUtils: Any,
        messageUtils: Any
    ) {
        try {
            mScriptEngine?.put("wekit", object {
                fun log(vararg args: Any?) {
                    val message = args.joinToString(" ") { it?.toString() ?: "null" }
                    ScriptLogger.getInstance().info(message)
                }

                fun isMMAtLeast(field: String) = runCatching {
                    HostInfo.getVersionCode() >= MMVersion::class.java.getField(field).getInt(null)
                }.getOrDefault(false)

                fun sendCgi(uri: String, cgiId: Int, funcId: Int, routeId: Int, jsonPayload: String) {
                    sendCgi(uri, cgiId, funcId, routeId, jsonPayload)
                }

                @JvmField
                val proto: Any = protoUtils
                @JvmField
                val database: Any = dataBaseUtils
                @JvmField
                val message: Any = messageUtils
            })

            WeLogger.i("JsExecutor: Injected Rhino logging interface")

        } catch (e: Exception) {
            WeLogger.e("Failed to inject logging interface: ${e.message}")
        }
    }

    /**
     * 执行 JavaScript 并返回结果（同步）
     */
    fun executeJs(jsCode: String): String? {
        if (!mInitialized) {
            WeLogger.w("Rhino engine not ready")
            return null
        }

        return try {
            val result = mScriptEngine?.eval(jsCode)?.toString()
            result
        } catch (e: Exception) {
            WeLogger.e("JS exec error: ${e.message}", e)
            e.message
        }
    }

    /**
     * 检查Rhino引擎是否已初始化
     */
    fun isInitialized(): Boolean {
        return mInitialized
    }

    /**
     * 关闭Rhino引擎
     */
    fun close() {
        mMainHandler.post {
            mRhinoContext = null
            mScope = null
            mScriptEngine = null
            mInitialized = false
        }
    }
}