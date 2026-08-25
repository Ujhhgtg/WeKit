package dev.ujhhgtg.wekit.features.api.ui

import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.ui.WeWebViewApi.evaluateJavascript
import dev.ujhhgtg.wekit.features.api.ui.WeWebViewApi.loadUrl
import dev.ujhhgtg.wekit.features.api.ui.WeWebViewApi.reload
import dev.ujhhgtg.wekit.features.api.ui.WeWebViewApi.tracked
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 主进程 WebView 追踪服务。
 *
 * 通过 hook [android.webkit.WebView] 与 [com.tencent.xweb.WebView] 的构造方法，维护一个当前进程内所有
 * 存活 WebView 实例的弱引用集合（[WeakHashMap] 支撑，实例被回收后自动移除）。WeAgent 的 `builtin-webview`
 * 工具借此枚举 WebView 并对其调用 [evaluateJavascript]、[loadUrl]、[reload] 等常用能力。
 *
 * 说明：WeAgent 运行于微信主进程，因此只能追踪到主进程内构造的 WebView。H5（`:tools`）与小程序
 * （`:appbrand`）运行在独立进程，其 WebView 不在此集合内 —— 这是进程隔离的固有限制。
 *
 * 两种 WebView 并不共享带这些方法的公共父类（xweb 的是 FrameLayout 包装器），因此所有操作均通过反射按
 * 方法名调用，同时适配二者（与 [dev.ujhhgtg.wekit.features.items.miniapps.ErudaConsole] 的做法一致）。
 */
object WeWebViewApi : ApiFeature(), IResolveDex {

    override val technicalId = "WebView 追踪服务"
    override val nameRes = R.string.feature_we_web_view_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_web_view_api_description

    private const val TAG = "WeWebViewApi"

    /** Guards [tracked] — WebView constructors may run off the main thread. */
    private val lock = Any()

    /** Weak set of live WebViews (both android.webkit.WebView and com.tencent.xweb.WebView). */
    private val tracked = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    private fun track(webView: Any?) {
        if (webView == null) return
        synchronized(lock) { tracked.add(webView) }
    }

    /** A snapshot of all currently-live tracked WebViews (dead entries already evicted by GC). */
    fun snapshot(): List<Any> = synchronized(lock) { tracked.filterNotNull() }

    internal val xwebOnPageFinished by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.page")
        matcher {
            declaredClass {
                usingEqStrings(
                    "MicroMsg.AppBrandWebView",
                    "onReceivedHttpError, WebResourceRequest url = %s, ErrWebResourceResponse mimeType = %s, status = %d"
                )
                superClass {
                    className("com.tencent.xweb", StringMatchType.StartsWith)
                }
            }
            paramTypes("com.tencent.xweb.WebView", "java.lang.String", "android.graphics.Bitmap")
            returnType = "void"
        }
    }
    internal val androidOnPageFinished by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.page")
        matcher {
            declaredClass {
                superClass = "android.webkit.WebViewClient"
            }
            paramCount = 2
            returnType = "void"
        }
    }

    override fun onEnable() {
        xwebOnPageFinished.hookAfter {
            WeLogger.i(TAG, "injecting into xwebOnPageFinished: ${args[0]}")
            track(args[0])
        }
        androidOnPageFinished.hookAfter {
            WeLogger.i(TAG, "injecting into androidOnPageFinished: ${args[0]}")
            track(args[0])
        }
    }

    // -------------------------------------------------------------------------------------------
    // Threading — WebView methods must run on the thread that created the WebView (its UI thread,
    // normally the main thread). Tools run on Dispatchers.IO, so we marshal and block for a result.
    // -------------------------------------------------------------------------------------------

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Runs [block] on the main thread, blocking the caller up to [timeoutMs] for its result. */
    private fun <T> onMain(timeoutMs: Long, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS))
            throw RuntimeException("WebView main-thread call timed out after ${timeoutMs}ms")
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * Reflectively invokes a method by name on either WebView type. Both `android.webkit.WebView`
     * and `com.tencent.xweb.WebView` expose the same method names but share no common supertype that
     * declares them, so we resolve by name (searching superclasses) rather than a static cast.
     */
    private fun call(webView: Any, method: String, paramCount: Int, vararg args: Any?): Any? =
        webView.reflekt()
            .firstMethod { name = method; parameterCount = paramCount; superclass() }
            .invoke(*args)

    // -------------------------------------------------------------------------------------------
    // Operations — all marshaled onto the main thread; all reflective so both WebView types work.
    // -------------------------------------------------------------------------------------------

    /**
     * Evaluates [script] in [webView] and returns the JSON-encoded result string (as Android's
     * `evaluateJavascript` callback delivers it), or throws on timeout. The async WebView callback
     * is bridged back to the blocking caller via a latch.
     */
    fun evaluateJavascript(webView: Any, script: String, timeoutMs: Long = 10_000L): String? {
        val resultLatch = CountDownLatch(1)
        var jsResult: String? = null
        onMain(timeoutMs) {
            val cb = ValueCallback<String> { value -> jsResult = value; resultLatch.countDown() }
            call(webView, "evaluateJavascript", 2, script, cb)
        }
        // The eval callback fires asynchronously after we post; wait for it (bounded).
        if (!resultLatch.await(timeoutMs, TimeUnit.MILLISECONDS))
            throw RuntimeException("evaluateJavascript timed out after ${timeoutMs}ms (no result callback)")
        return jsResult
    }

    fun loadUrl(webView: Any, url: String, timeoutMs: Long = 5_000L) =
        onMain(timeoutMs) { call(webView, "loadUrl", 1, url); Unit }

    fun reload(webView: Any, timeoutMs: Long = 5_000L) =
        onMain(timeoutMs) { call(webView, "reload", 0); Unit }

    fun stopLoading(webView: Any, timeoutMs: Long = 5_000L) =
        onMain(timeoutMs) { call(webView, "stopLoading", 0); Unit }

    fun goBack(webView: Any, timeoutMs: Long = 5_000L): Boolean = onMain(timeoutMs) {
        val can = call(webView, "canGoBack", 0) as? Boolean ?: false
        if (can) call(webView, "goBack", 0)
        can
    }

    fun goForward(webView: Any, timeoutMs: Long = 5_000L): Boolean = onMain(timeoutMs) {
        val can = call(webView, "canGoForward", 0) as? Boolean ?: false
        if (can) call(webView, "goForward", 0)
        can
    }

    fun getUrl(webView: Any, timeoutMs: Long = 5_000L): String? =
        onMain(timeoutMs) { call(webView, "getUrl", 0) as? String }

    fun getTitle(webView: Any, timeoutMs: Long = 5_000L): String? =
        onMain(timeoutMs) { call(webView, "getTitle", 0) as? String }

    override fun onDisable() {
        synchronized(lock) { tracked.clear() }
    }
}
