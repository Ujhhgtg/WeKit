package moe.ouom.wekit.loader.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.PersistableBundle
import android.os.TestLooperManager
import android.view.KeyEvent
import android.view.MotionEvent
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.constants.PackageNames
import moe.ouom.wekit.utils.HostInfo
import moe.ouom.wekit.utils.ModuleRes
import moe.ouom.wekit.utils.logging.WeLogger
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ActivityProxy {

    private val TAG = nameof(ActivityProxy)
    private var stubHooked = false

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun initForStubActivity(ctx: Context) {
        if (stubHooked) return
        runCatching {
            val clazzActivityThread = Class.forName("android.app.ActivityThread")
            val currentActivityThread = clazzActivityThread
                .getDeclaredMethod("currentActivityThread")
                .also { it.isAccessible = true }
                .invoke(null)

            // Hook Instrumentation
            val mInstrumentationField = clazzActivityThread.getDeclaredField("mInstrumentation")
                .also { it.isAccessible = true }
            val instrumentation =
                mInstrumentationField.get(currentActivityThread) as Instrumentation
            if (instrumentation !is ProxyInstrumentation) {
                mInstrumentationField.set(
                    currentActivityThread,
                    ProxyInstrumentation(instrumentation)
                )
            }

            // Hook Handler (mH)
            val oriHandler = clazzActivityThread.getDeclaredField("mH")
                .also { it.isAccessible = true }
                .get(currentActivityThread) as Handler
            val callbackField = Handler::class.java.getDeclaredField("mCallback")
                .also { it.isAccessible = true }
            val current = callbackField.get(oriHandler) as? Handler.Callback
            if (current == null || current.javaClass.name != ProxyHandlerCallback::class.java.name) {
                callbackField.set(oriHandler, ProxyHandlerCallback(current))
            }

            hookIActivityManager()
            hookPackageManager(ctx, requireNotNull(currentActivityThread), clazzActivityThread)

            stubHooked = true
        }.onFailure { WeLogger.e(TAG, "Failed to init stub activity hooks", it) }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun hookIActivityManager() {
        val singletonClass = Class.forName("android.util.Singleton")
        val instanceField =
            singletonClass.getDeclaredField("mInstance").also { it.isAccessible = true }

        fun hookSingleton(singleton: Any?, iface: Class<*>) {
            if (singleton == null) return
            runCatching {
                singletonClass.getDeclaredMethod("get").also { it.isAccessible = true }
                    .invoke(singleton)
            }
            val instance = instanceField.get(singleton) ?: run {
                WeLogger.e(TAG, "Instance is null for ${iface.simpleName}, aborting hook.")
                return
            }
            val proxy = Proxy.newProxyInstance(
                ActivityProxy::class.java.classLoader,
                arrayOf(iface),
                IActivityManagerHandler(instance)
            )
            instanceField.set(singleton, proxy)
        }

        val (_, defField) = runCatching {
            val c = Class.forName("android.app.ActivityManagerNative")
            c to c.getDeclaredField("gDefault")
        }.getOrElse {
            val c = Class.forName("android.app.ActivityManager")
            c to c.getDeclaredField("IActivityManagerSingleton")
        }
        defField.isAccessible = true
        hookSingleton(defField.get(null), Class.forName("android.app.IActivityManager"))

        // Android 10+ (Q)
        runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val singleton = atmClass.getDeclaredField("IActivityTaskManagerSingleton")
                .also { it.isAccessible = true }.get(null)
            hookSingleton(singleton, Class.forName("android.app.IActivityTaskManager"))
        }
    }

    @SuppressLint("PrivateApi")
    private fun hookPackageManager(
        ctx: Context,
        sCurrentActivityThread: Any,
        clazzActivityThread: Class<*>
    ) {
        runCatching {
            val sPackageManagerField = clazzActivityThread.getDeclaredField("sPackageManager")
                .also { it.isAccessible = true }
            val packageManagerImpl = sPackageManagerField.get(sCurrentActivityThread)
            val iPackageManagerInterface = Class.forName("android.content.pm.IPackageManager")

            val pm = ctx.packageManager
            val mPmField = pm.javaClass.getDeclaredField("mPM").also { it.isAccessible = true }

            val pmProxy = Proxy.newProxyInstance(
                iPackageManagerInterface.classLoader,
                arrayOf(iPackageManagerInterface),
                PackageManagerInvocationHandler(packageManagerImpl)
            )
            sPackageManagerField.set(sCurrentActivityThread, pmProxy)
            mPmField.set(pm, pmProxy)
        }.onFailure { WeLogger.e(TAG, "Failed to hook PackageManager (Non-fatal)", it) }
    }

    // --- Inner types ---

    object ActProxyMgr {
        const val ACTIVITY_PROXY_INTENT_TOKEN = "wekit_target_intent_token"
        const val STUB_DEFAULT_ACTIVITY =
            "com.tencent.mm.plugin.facedetect.ui.FaceTransparentStubUI"

        fun isModuleProxyActivity(className: String?): Boolean =
            className?.startsWith(PackageNames.THIS) == true
    }

    class IActivityManagerHandler(private val origin: Any) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            val mutableArgs = args ?: emptyArray()
            if (method.name.startsWith("startActivity")) {
                mutableArgs.forEachIndexed { i, arg ->
                    when (arg) {
                        is Intent -> if (shouldProxy(arg)) mutableArgs[i] = createTokenWrapper(arg)
                        is Array<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val intents = arg as? Array<Intent?> ?: return@forEachIndexed
                            intents.forEachIndexed { j, intent ->
                                if (intent != null && shouldProxy(intent)) intents[j] =
                                    createTokenWrapper(intent)
                            }
                        }
                    }
                }
            }
            return try {
                method.invoke(origin, *mutableArgs)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        private fun shouldProxy(intent: Intent): Boolean =
            intent.component?.let { ActProxyMgr.isModuleProxyActivity(it.className) } == true

        private fun createTokenWrapper(raw: Intent): Intent {
            val token = IntentTokenCache.put(Intent(raw))
            return Intent().apply {
                component = ComponentName(PackageNames.WECHAT, ActProxyMgr.STUB_DEFAULT_ACTIVITY)
                flags = raw.flags
                action = raw.action
                setDataAndType(raw.data, raw.type)
                raw.categories?.forEach { addCategory(it) }
                putExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT_TOKEN, token)
                ParcelableFixer.getHybridClassLoader()?.let { setExtrasClassLoader(it) }
            }.also {
                WeLogger.i(
                    TAG,
                    "hijacked startActivity via token: ${raw.component!!.className} -> ${ActProxyMgr.STUB_DEFAULT_ACTIVITY}"
                )
            }
        }
    }

    class ProxyHandlerCallback(private val next: Handler.Callback?) : Handler.Callback {
        override fun handleMessage(msg: Message): Boolean {
            when (msg.what) {
                100 -> handleLaunchActivity(msg)     // LAUNCH_ACTIVITY (< Android 9)
                159 -> handleExecuteTransaction(msg) // EXECUTE_TRANSACTION (>= Android 9)
            }
            return runCatching { next?.handleMessage(msg) == true }
                .onFailure { WeLogger.e(TAG, "Next callback failed", it) }
                .getOrDefault(false)
        }

        private fun unwrapIntent(wrapper: Intent?): Intent? {
            wrapper ?: return null
            val cl = ParcelableFixer.getHybridClassLoader()
            cl?.let { wrapper.setExtrasClassLoader(it) }
            if (!wrapper.hasExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT_TOKEN)) return null

            val token = wrapper.getStringExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT_TOKEN)
            val real = IntentTokenCache.getAndRemove(token) ?: run {
                WeLogger.w(TAG, "Token expired or lost in Handler: $token")
                return null
            }
            cl?.let {
                real.setExtrasClassLoader(it)
                real.extras?.classLoader = it
            }
            return real
        }

        private fun handleLaunchActivity(msg: Message) {
            runCatching {
                val record = msg.obj
                val intentField =
                    record.javaClass.getDeclaredField("intent").also { it.isAccessible = true }
                val wrapper = intentField.get(record) as? Intent
                unwrapIntent(wrapper)?.let { intentField.set(record, it) }
            }.onFailure { WeLogger.e(TAG, "handleLaunchActivity error", it) }
        }

        private fun handleExecuteTransaction(msg: Message) {
            runCatching {
                val transaction = msg.obj
                val callbacks = transaction.javaClass.getDeclaredMethod("getCallbacks")
                    .also { it.isAccessible = true }
                    .invoke(transaction) as? List<*> ?: return

                callbacks.forEach { item ->
                    if (item != null && item.javaClass.name.contains("LaunchActivityItem")) {
                        val intentField = item.javaClass.getDeclaredField("mIntent")
                            .also { it.isAccessible = true }
                        val wrapper = intentField.get(item) as? Intent
                        unwrapIntent(wrapper)?.let { intentField.set(item, it) }
                    }
                }
            }.onFailure { WeLogger.e(TAG, "handleExecuteTransaction error", it) }
        }
    }

    @SuppressLint("NewApi")
    class ProxyInstrumentation(private val base: Instrumentation) : Instrumentation() {

        private fun tryRecoverIntent(intent: Intent?): Intent? {
            intent ?: return null
            if (!intent.hasExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT_TOKEN)) return null
            val cl = ParcelableFixer.getHybridClassLoader()
            cl?.let { intent.setExtrasClassLoader(it) }
            val token = intent.getStringExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT_TOKEN)
            return IntentTokenCache.getAndRemove(token)?.also { real ->
                cl?.let { real.setExtrasClassLoader(it); real.extras?.classLoader = it }
            }
        }

        override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity {
            var resolvedIntent = intent
            var resolvedClass = className
            tryRecoverIntent(intent)?.takeIf { it.component != null }?.let {
                resolvedIntent = it
                resolvedClass = it.component!!.className
                WeLogger.w(
                    "ProxyInstrumentation",
                    "Recovered intent in newActivity fallback: $resolvedClass"
                )
            }
            return try {
                base.newActivity(cl, resolvedClass, resolvedIntent)
            } catch (e: ClassNotFoundException) {
                if (ActProxyMgr.isModuleProxyActivity(resolvedClass)) {
                    checkNotNull(javaClass.classLoader).loadClass(resolvedClass)
                        .getDeclaredConstructor().newInstance() as Activity
                } else throw e
            }
        }

        override fun newActivity(
            clazz: Class<*>,
            context: Context,
            token: IBinder,
            application: Application,
            intent: Intent,
            info: ActivityInfo,
            title: CharSequence,
            parent: Activity?,
            id: String?,
            lastNonConfigurationInstance: Any?
        ): Activity =
            base.newActivity(
                clazz,
                context,
                token,
                application,
                intent,
                info,
                title,
                parent,
                id,
                lastNonConfigurationInstance
            )

        override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
            if (ActProxyMgr.isModuleProxyActivity(activity.javaClass.name)) {
                checkAndInjectResources(activity)
                ParcelableFixer.getHybridClassLoader()?.let { cl ->
                    runCatching {
                        Activity::class.java.getDeclaredField("mClassLoader")
                            .also { it.isAccessible = true }.set(activity, cl)
                    }
                    activity.intent?.let { intent ->
                        intent.setExtrasClassLoader(cl)
                        intent.extras?.classLoader = cl
                    }
                }
            }
            base.callActivityOnCreate(activity, icicle)
        }

        override fun callActivityOnCreate(
            activity: Activity,
            icicle: Bundle?,
            persistentState: PersistableBundle?
        ) {
            checkAndInjectResources(activity)
            base.callActivityOnCreate(activity, icicle, persistentState)
        }

        private fun checkAndInjectResources(activity: Activity) {
            if (ActProxyMgr.isModuleProxyActivity(activity.javaClass.name)) {
                ModuleRes.init(activity, PackageNames.THIS)
            }
        }

        override fun onCreate(arguments: Bundle?) = base.onCreate(arguments)
        override fun start() = base.start()
        override fun onStart() = base.onStart()
        override fun onException(obj: Any?, e: Throwable?) = base.onException(obj, e)
        override fun sendStatus(resultCode: Int, results: Bundle?) =
            base.sendStatus(resultCode, results)

        override fun addResults(results: Bundle?) = base.addResults(results)
        override fun finish(resultCode: Int, results: Bundle?) = base.finish(resultCode, results)
        override fun setAutomaticPerformanceSnapshots() = base.setAutomaticPerformanceSnapshots()
        override fun startPerformanceSnapshot() = base.startPerformanceSnapshot()
        override fun endPerformanceSnapshot() = base.endPerformanceSnapshot()
        override fun onDestroy() = base.onDestroy()
        override fun getContext(): Context? = base.context
        override fun getComponentName(): ComponentName? = base.componentName
        override fun getTargetContext(): Context? = base.targetContext
        override fun getProcessName(): String? = base.processName
        override fun isProfiling() = base.isProfiling
        override fun startProfiling() = base.startProfiling()
        override fun stopProfiling() = base.stopProfiling()
        override fun setInTouchMode(inTouch: Boolean) = base.setInTouchMode(inTouch)
        override fun waitForIdle(recipient: Runnable?) = base.waitForIdle(recipient)
        override fun waitForIdleSync() = base.waitForIdleSync()
        override fun runOnMainSync(runner: Runnable?) = base.runOnMainSync(runner)
        override fun startActivitySync(intent: Intent): Activity? = base.startActivitySync(intent)
        override fun startActivitySync(intent: Intent, options: Bundle?): Activity =
            base.startActivitySync(intent, options)

        override fun addMonitor(monitor: ActivityMonitor?) = base.addMonitor(monitor)
        override fun addMonitor(
            filter: IntentFilter?,
            result: ActivityResult?,
            block: Boolean
        ): ActivityMonitor? = base.addMonitor(filter, result, block)

        override fun addMonitor(
            cls: String?,
            result: ActivityResult?,
            block: Boolean
        ): ActivityMonitor? = base.addMonitor(cls, result, block)

        override fun checkMonitorHit(monitor: ActivityMonitor?, minHits: Int) =
            base.checkMonitorHit(monitor, minHits)

        override fun waitForMonitor(monitor: ActivityMonitor?): Activity? =
            base.waitForMonitor(monitor)

        override fun waitForMonitorWithTimeout(
            monitor: ActivityMonitor?,
            timeOut: Long
        ): Activity? = base.waitForMonitorWithTimeout(monitor, timeOut)

        override fun removeMonitor(monitor: ActivityMonitor?) = base.removeMonitor(monitor)
        override fun invokeMenuActionSync(targetActivity: Activity?, id: Int, flag: Int) =
            base.invokeMenuActionSync(targetActivity, id, flag)

        override fun invokeContextMenuAction(targetActivity: Activity?, id: Int, flag: Int) =
            base.invokeContextMenuAction(targetActivity, id, flag)

        override fun sendStringSync(text: String?) = base.sendStringSync(text)
        override fun sendKeySync(event: KeyEvent?) = base.sendKeySync(event)
        override fun sendKeyDownUpSync(key: Int) = base.sendKeyDownUpSync(key)
        override fun sendCharacterSync(keyCode: Int) = base.sendCharacterSync(keyCode)
        override fun sendPointerSync(event: MotionEvent?) = base.sendPointerSync(event)
        override fun sendTrackballEventSync(event: MotionEvent?) =
            base.sendTrackballEventSync(event)

        override fun newApplication(
            cl: ClassLoader?,
            className: String?,
            context: Context?
        ): Application = base.newApplication(cl, className, context)

        override fun callApplicationOnCreate(app: Application?) = base.callApplicationOnCreate(app)
        override fun callActivityOnDestroy(activity: Activity?) =
            base.callActivityOnDestroy(activity)

        override fun callActivityOnRestoreInstanceState(
            activity: Activity,
            savedInstanceState: Bundle
        ) = base.callActivityOnRestoreInstanceState(activity, savedInstanceState)

        override fun callActivityOnRestoreInstanceState(
            activity: Activity,
            savedInstanceState: Bundle?,
            persistentState: PersistableBundle?
        ) = base.callActivityOnRestoreInstanceState(activity, savedInstanceState, persistentState)

        override fun callActivityOnPostCreate(activity: Activity, savedInstanceState: Bundle?) =
            base.callActivityOnPostCreate(activity, savedInstanceState)

        override fun callActivityOnPostCreate(
            activity: Activity,
            savedInstanceState: Bundle?,
            persistentState: PersistableBundle?
        ) = base.callActivityOnPostCreate(activity, savedInstanceState, persistentState)

        override fun callActivityOnNewIntent(activity: Activity?, intent: Intent?) =
            base.callActivityOnNewIntent(activity, intent)

        override fun callActivityOnStart(activity: Activity?) = base.callActivityOnStart(activity)
        override fun callActivityOnRestart(activity: Activity?) =
            base.callActivityOnRestart(activity)

        override fun callActivityOnResume(activity: Activity?) = base.callActivityOnResume(activity)
        override fun callActivityOnStop(activity: Activity?) = base.callActivityOnStop(activity)
        override fun callActivityOnSaveInstanceState(activity: Activity, outState: Bundle) =
            base.callActivityOnSaveInstanceState(activity, outState)

        override fun callActivityOnSaveInstanceState(
            activity: Activity,
            outState: Bundle,
            outPersistentState: PersistableBundle
        ) = base.callActivityOnSaveInstanceState(activity, outState, outPersistentState)

        override fun callActivityOnPause(activity: Activity?) = base.callActivityOnPause(activity)
        override fun callActivityOnUserLeaving(activity: Activity?) =
            base.callActivityOnUserLeaving(activity)

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated")
        override fun startAllocCounting() = base.startAllocCounting()

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated")
        override fun stopAllocCounting() = base.stopAllocCounting()
        override fun getAllocCounts(): Bundle = base.allocCounts
        override fun getBinderCounts(): Bundle = base.binderCounts
        override fun getUiAutomation(): UiAutomation = base.uiAutomation
        override fun getUiAutomation(flags: Int): UiAutomation = base.getUiAutomation(flags)
        override fun acquireLooperManager(looper: Looper): TestLooperManager =
            base.acquireLooperManager(looper)
    }

    class PackageManagerInvocationHandler(private val target: Any?) : InvocationHandler {
        init {
            requireNotNull(target) { "IPackageManager is null" }
        }

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (method.name == "getActivityInfo" && args != null) {
                var component: ComponentName? = null
                for (arg in args) {
                    when (arg) {
                        is ComponentName -> component = arg
                    }
                }
                if (component != null && ActProxyMgr.isModuleProxyActivity(component.className)) {
                    return CounterfeitActivityInfoFactory.makeProxyActivityInfo(component.className)
                }
            }
            return try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    object CounterfeitActivityInfoFactory {
        fun makeProxyActivityInfo(className: String) = ActivityInfo().apply {
            name = className
            packageName = PackageNames.WECHAT
            enabled = true
            exported = false
            processName = PackageNames.WECHAT
            applicationInfo = runCatching { HostInfo.appInfo }.getOrElse {
                ApplicationInfo().apply { packageName = PackageNames.WECHAT }
            }
            launchMode = ActivityInfo.LAUNCH_MULTIPLE
        }
    }

    private object IntentTokenCache {
        private data class Entry(
            val intent: Intent,
            val timestamp: Long = System.currentTimeMillis()
        )

        private val cache = ConcurrentHashMap<String, Entry>()
        private const val EXPIRE_MS = 60_000L

        fun put(intent: Intent): String {
            cleanup()
            return UUID.randomUUID().toString().also { cache[it] = Entry(intent) }
        }

        fun getAndRemove(token: String?): Intent? {
            token ?: return null
            val entry = cache.remove(token) ?: return null
            return entry.intent.takeIf { System.currentTimeMillis() - entry.timestamp <= EXPIRE_MS }
        }

        private fun cleanup() {
            val now = System.currentTimeMillis()
            cache.entries.removeIf { now - it.value.timestamp > EXPIRE_MS }
        }
    }
}
