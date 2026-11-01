package dev.ujhhgtg.wekit.loader.utils

import android.app.Activity
import android.app.Instrumentation
import android.app.servertransaction.ClientTransaction
import android.app.servertransaction.LaunchActivityItem
import android.app.servertransaction.NewIntentItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.os.UserHandle
import com.android.internal.content.ReferrerIntent
import dev.ujhhgtg.reflekt.utils.fastJavaMethod
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.currentHookBridge
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.reflection.int
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs payload Activities inside the WeChat process.
 *
 * The whole point is process affinity: module pages must live in the host process so they share the
 * injected code, hooks, application context and WeChat APIs. They are therefore launched as a host
 * stub Activity (declared in WeChat's manifest) whose Intent is rewritten here to the real payload
 * Activity before the host instantiates it.
 *
 * The mechanism is hook-only. A previous implementation replaced framework objects
 * (`ActivityThread.mInstrumentation`, `ActivityThread.mH`'s callback, the
 * `IActivityManager`/`IActivityTaskManager`/`IPackageManager` singletons); that made this module
 * fight every other module that wraps the same objects. Instead:
 *
 * - write side: hook `Instrumentation.execStart*` and swap module-Activity Intents for host-stub
 *   Intents carrying an in-process token;
 * - read side: hook `ClientTransaction.preExecute` and put the real Intent back into
 *   `LaunchActivityItem.mIntent` / `NewIntentItem.mIntents` before the client builds its record;
 * - instantiation: hook `Instrumentation.newActivity(ClassLoader, String, Intent)` and hand it the
 *   module ClassLoader for module-Activity class names;
 * - onCreate side effects: hook `Instrumentation.callActivityOnCreate` and inject payload
 *   resources.
 *
 * Hooks only mutate arguments or framework fields; none of them replaces a framework object, so
 * other modules' wrappers/hooks keep working and we never need to know who was installed first.
 */
object ActivityProxy {
    private const val TAG = "ActivityProxy"

    /** Above the default feature priority (50) so infrastructure runs first. */
    private const val HOOK_PRIORITY = 100

    private var initialized = false

    private fun instrumentationMethod(name: String, vararg parameterTypes: Class<*>): Method =
        Instrumentation::class.java.getDeclaredMethod(name, *parameterTypes).makeAccessible()

    private val launchIntentField by lazy {
        LaunchActivityItem::class.java.getDeclaredField("mIntent").makeAccessible()
    }
    private val newIntentsField by lazy {
        NewIntentItem::class.java.getDeclaredField("mIntents").makeAccessible()
    }

    fun init() {
        if (initialized) return

        runCatching {
            installStartActivityHooks()
            installIntentRecoveryHook()
            installActivityInstantiationHook()
            installActivityCreateHooks()

            initialized = true
            WeLogger.i(
                TAG,
                "module activity hooks installed via ${currentHookBridge.hookBridgeName}",
            )
        }
            .onFailure { WeLogger.e(TAG, "failed to install module activity hooks", it) }
    }

    // --- write side: module Activity Intent -> host stub Intent ---

    private fun installStartActivityHooks() {
        val targets =
            listOf(
                instrumentationMethod(
                    "execStartActivity",
                    Context::class.java,
                    IBinder::class.java,
                    IBinder::class.java,
                    Activity::class.java,
                    Intent::class.java,
                    int,
                    Bundle::class.java,
                ),
                instrumentationMethod(
                    "execStartActivity",
                    Context::class.java,
                    IBinder::class.java,
                    IBinder::class.java,
                    String::class.java,
                    Intent::class.java,
                    int,
                    Bundle::class.java,
                ),
                instrumentationMethod(
                    "execStartActivity",
                    Context::class.java,
                    IBinder::class.java,
                    IBinder::class.java,
                    String::class.java,
                    Intent::class.java,
                    int,
                    Bundle::class.java,
                    UserHandle::class.java,
                ),
                instrumentationMethod(
                    "execStartActivities",
                    Context::class.java,
                    IBinder::class.java,
                    IBinder::class.java,
                    Activity::class.java,
                    Array<Intent>::class.java,
                    Bundle::class.java,
                ),
                instrumentationMethod(
                    "execStartActivitiesAsUser",
                    Context::class.java,
                    IBinder::class.java,
                    IBinder::class.java,
                    Activity::class.java,
                    Array<Intent>::class.java,
                    Bundle::class.java,
                    int,
                ),
            )

        targets.forEach { method ->
            val intentIndex = method.parameterTypes.indexOfFirst { it == Intent::class.java }
            if (intentIndex >= 0) {
                method.hookBeforeDirectly(HOOK_PRIORITY) { rewriteStartIntentAt(intentIndex) }
            } else {
                val intentsIndex =
                    method.parameterTypes.indexOfFirst { it == Array<Intent>::class.java }
                check(intentsIndex >= 0) { "${method.name} has no Intent parameter" }
                method.hookBeforeDirectly(HOOK_PRIORITY) { rewriteStartIntentsAt(intentsIndex) }
            }
        }
        WeLogger.i(TAG, "installed ${targets.size} startActivity intent hooks")
    }

    private fun HookParam.rewriteStartIntentAt(index: Int) {
        val intent = args[index] as Intent
        if (shouldProxy(intent)) {
            args[index] = createTokenWrapper(intent)
        }
    }

    private fun HookParam.rewriteStartIntentsAt(index: Int) {
        @Suppress("UNCHECKED_CAST") val intents = args[index] as Array<Intent?>
        intents.forEachIndexed { i, intent ->
            if (intent != null && shouldProxy(intent)) {
                intents[i] = createTokenWrapper(intent)
            }
        }
    }

    private fun createTokenWrapper(raw: Intent): Intent {
        val token = intentCache.put(Intent(raw))
        val className = raw.component!!.className
        val proxyClass = proxyClassFor(className)
        return Intent()
            .apply {
                component = ComponentName(HostInfo.packageName, proxyClass)
                flags = raw.flags
                action = raw.action
                setDataAndType(raw.data, raw.type)
                raw.categories?.forEach { addCategory(it) }
                putExtra(ACTIVITY_PROXY_INTENT_TOKEN, token)
            }
            .also {
                WeLogger.i(
                    TAG,
                    "hijacked module activity start: $className -> $proxyClass",
                )
            }
    }

    // --- read side: host stub Intent -> module Activity Intent ---

    private fun installIntentRecoveryHook() {
        ClientTransaction::preExecute.fastJavaMethod!!.hookBeforeDirectly(HOOK_PRIORITY) {
            recoverTransactionIntents(this)
        }
        WeLogger.i(TAG, "installed ClientTransaction.preExecute intent recovery hook")
    }

    private fun recoverTransactionIntents(param: HookParam) {
        val transaction = param.thisObject as? ClientTransaction ?: return
        val callbacks = transaction.callbacks ?: return
        callbacks.forEach { item ->
            when (item) {
                is LaunchActivityItem -> recoverLaunchActivityItem(item)
                is NewIntentItem -> recoverNewIntentItem(item)
            }
        }
    }

    private fun recoverLaunchActivityItem(item: LaunchActivityItem) {
        val wrapper = launchIntentField.get(item) as? Intent ?: return
        val real = recoverIntent(wrapper) ?: return
        launchIntentField.set(item, real)
    }

    private fun recoverNewIntentItem(item: NewIntentItem) {
        // ReferrerIntent extends Intent; replace each wrapper element with a real ReferrerIntent so
        // the ReferrerIntent-typed overload of Instrumentation.callActivityOnNewIntent keeps
        // matching.
        @Suppress("UNCHECKED_CAST")
        val intents = newIntentsField.get(item) as? MutableList<ReferrerIntent> ?: return
        intents.forEachIndexed { index, referrer ->
            val real = recoverIntent(referrer) ?: return@forEachIndexed
            intents[index] = createReferrerIntent(real, referrer)
        }
    }

    private fun createReferrerIntent(real: Intent, wrapper: ReferrerIntent): ReferrerIntent =
        // mCallerToken and the 3-arg constructor only exist on Android 16+ (API 36).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ReferrerIntent(real, wrapper.mReferrer, wrapper.mCallerToken)
        } else {
            ReferrerIntent(real, wrapper.mReferrer)
        }

    private fun recoverIntent(wrapper: Intent): Intent? {
        if (!wrapper.hasExtra(ACTIVITY_PROXY_INTENT_TOKEN)) return null

        val token = wrapper.getStringExtra(ACTIVITY_PROXY_INTENT_TOKEN)
        return intentCache.pop(token)
            ?: run {
                WeLogger.w(TAG, "module activity intent token expired or lost: $token")
                null
            }
    }

    // --- instantiation + onCreate side effects ---

    private fun installActivityInstantiationHook() {
        "com.tencent.mm.splash.SplashHackInstrumentation"
            .toClass()
            .getDeclaredMethod(
                "newActivity",
                ClassLoader::class.java,
                String::class.java,
                Intent::class.java,
            )
            .hookBeforeDirectly(HOOK_PRIORITY) {
                val className = args[1] as String
                if (shouldProxy(className)) {
                    // Instrumentation.newActivity hands this ClassLoader to the host
                    // AppComponentFactory, so the real module class is created exactly like
                    // any other Activity.
                    args[0] = ClassLoaders.MODULE
                }
            }
        WeLogger.i(TAG, "installed Instrumentation.newActivity hook")
    }

    private fun installActivityCreateHooks() {
        val targets =
            listOf(
                instrumentationMethod(
                    "callActivityOnCreate",
                    Activity::class.java,
                    Bundle::class.java,
                ),
                instrumentationMethod(
                    "callActivityOnCreate",
                    Activity::class.java,
                    Bundle::class.java,
                    PersistableBundle::class.java,
                ),
            )
        targets.forEach { method ->
            method.hookBeforeDirectly(HOOK_PRIORITY) {
                fixActivityCreate(args[0] as Activity)
            }
        }
        WeLogger.i(TAG, "installed ${targets.size} activity onCreate hooks")
    }

    private fun fixActivityCreate(activity: Activity) {
        // Host Activities that host module dialogs/panels need payload resources too.
        ResourcesInjector.injectModuleRes(activity.resources)
    }

    // --- shared routing data ---

    private const val ACTIVITY_PROXY_INTENT_TOKEN = "wekit_target_intent_token"

    // Both must exist in the host manifest. The opaque splash window is used for full settings
    // pages, the translucent one for overlay/dialog-style pages; the host stub's ActivityInfo
    // (theme, orientation, configChanges) is what the framework applies to our Activity.
    private const val SETTINGS_PROXY = "${PackageNames.WECHAT}.app.WeChatSplashActivity"
    private const val TRANSPARENT_PROXY =
        "${PackageNames.WECHAT}.plugin.appbrand.ipc.AppBrandProxyTransparentUI"

    /**
     * Activities reachable through plain package/class prefixes but not launched in the host
     * process (e.g. the installed bootstrap manager app).
     */
    private fun shouldProxy(className: String): Boolean =
        className.startsWith(PackageNames.MODULE) &&
            (className.endsWith("SettingsActivity") || className.endsWith("TransparentActivity"))

    private fun shouldProxy(intent: Intent): Boolean =
        intent.component?.let { shouldProxy(it.className) } == true

    private fun proxyClassFor(className: String): String =
        if (className.endsWith("SettingsActivity")) SETTINGS_PROXY else TRANSPARENT_PROXY

    /**
     * In-process token -> real Intent map. Transient by design; the token is also the cache key.
     */
    @Suppress("ClassName")
    private object intentCache {
        private data class Entry(
            val intent: Intent,
            val timestamp: Long = System.currentTimeMillis(),
        )

        private const val EXPIRE_MS = 60_000L

        private val cache = ConcurrentHashMap<String, Entry>()

        fun put(intent: Intent): String {
            cleanup()
            return UUID.randomUUID().toString().also { cache[it] = Entry(intent) }
        }

        fun pop(token: String?): Intent? {
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
