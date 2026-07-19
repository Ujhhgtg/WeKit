package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.agent.jvm.JvmObjectRegistry
import dev.ujhhgtg.wekit.features.api.ui.WeWebViewApi
import dev.ujhhgtg.wekit.features.core.Param
import dev.ujhhgtg.wekit.features.core.WeKitOperation
import dev.ujhhgtg.wekit.features.core.WeKitOperation.Companion.BUILTIN_WEBVIEW

/**
 * The `builtin-webview` tools for WeAgent. Every function is discovered by the KSP scanner via
 * `@WeKitOperation(group = BUILTIN_WEBVIEW)`. They expose the live WebViews tracked by [WeWebViewApi]
 * (main process only) and the common operations on them.
 *
 * WebViews are referenced the same way as every other JVM object: as `#N` handles from the shared
 * [JvmObjectRegistry] (so they can also be drilled into with the builtin-jvm reflection tools).
 *
 * Side-effect classification: `webview-list` and the getters are `sideEffect = false` (ENABLED);
 * everything that runs JS or navigates is `sideEffect = true` (MANUAL_APPROVAL) — running arbitrary
 * JS in a logged-in WebView is powerful.
 */
object WeWebViewToolBindings {

    private fun resolveWebView(ref: String): Any =
        JvmObjectRegistry.resolve(ref)
            ?: throw IllegalArgumentException("No live handle '$ref' (list with webview-list; it may be gone)")

    // ------------------------------------------------------------------ read-only (ENABLED)

    @WeKitOperation(
        name = "webview-list",
        description = "List all live WebViews currently tracked in WeChat's main process. Returns " +
                "each as a handle (#N) with its runtime class, current URL, and title, so you can then " +
                "run JS or navigate on a chosen one. Note: only main-process WebViews are visible — H5 " +
                "pages and mini-programs run in separate processes and are NOT listed.",
        sideEffect = false,
        group = BUILTIN_WEBVIEW,
    )
    fun webviewList(): String {
        val views = WeWebViewApi.snapshot()
        if (views.isEmpty()) return "No live WebViews in the main process."
        return buildString {
            append("${views.size} live WebView(s):\n")
            views.forEach { wv ->
                val handle = JvmObjectRegistry.store(wv)
                val url = runCatching { WeWebViewApi.getUrl(wv) }.getOrNull()
                val title = runCatching { WeWebViewApi.getTitle(wv) }.getOrNull()
                append("$handle : ${wv.javaClass.name}\n")
                append("    url=${url ?: "?"}\n")
                append("    title=${title ?: "?"}\n")
            }
        }
    }

    @WeKitOperation(
        name = "webview-get-url",
        description = "Get the current URL of a WebView handle (from webview-list).",
        sideEffect = false,
        group = BUILTIN_WEBVIEW,
    )
    fun webviewGetUrl(
        @Param("WebView handle, e.g. #12") ref: String,
    ): String = WeWebViewApi.getUrl(resolveWebView(ref)) ?: "null"

    @WeKitOperation(
        name = "webview-get-title",
        description = "Get the current document title of a WebView handle (from webview-list).",
        sideEffect = false,
        group = BUILTIN_WEBVIEW,
    )
    fun webviewGetTitle(
        @Param("WebView handle, e.g. #12") ref: String,
    ): String = WeWebViewApi.getTitle(resolveWebView(ref)) ?: "null"

    // ------------------------------------------------------------------ actions (MANUAL_APPROVAL)

    @WeKitOperation(
        name = "webview-eval-js",
        description = "Evaluate JavaScript in a WebView handle (from webview-list) and return the " +
                "JSON-encoded result (as Android's evaluateJavascript delivers it — e.g. a string is " +
                "returned quoted). The last expression's value is the result; use JSON.stringify for " +
                "complex objects. Runs in the page's context with its cookies/session — powerful, so it " +
                "requires approval.",
        sideEffect = true,
        group = BUILTIN_WEBVIEW,
    )
    fun webviewEvalJs(
        @Param("WebView handle, e.g. #12") ref: String,
        @Param("JavaScript source to evaluate") script: String,
    ): String = WeWebViewApi.evaluateJavascript(resolveWebView(ref), script) ?: "null"

    @WeKitOperation(
        name = "webview-load-url",
        description = "Navigate a WebView handle to a URL. Accepts http(s):// URLs and javascript: " +
                "URLs.",
        sideEffect = true,
        group = BUILTIN_WEBVIEW,
    )
    fun webviewLoadUrl(
        @Param("WebView handle, e.g. #12") ref: String,
        @Param("URL to load") url: String,
    ): String {
        WeWebViewApi.loadUrl(resolveWebView(ref), url)
        return "Loading $url"
    }

    @WeKitOperation(
        name = "webview-reload",
        description = "Reload the current page of a WebView handle.",
        sideEffect = true,
        group = BUILTIN_WEBVIEW,
    )
    fun webviewReload(
        @Param("WebView handle, e.g. #12") ref: String,
    ): String {
        WeWebViewApi.reload(resolveWebView(ref))
        return "Reloaded."
    }

    @WeKitOperation(
        name = "webview-stop-loading",
        description = "Stop the in-progress page load of a WebView handle.",
        sideEffect = true,
        group = BUILTIN_WEBVIEW,
    )
    fun webviewStopLoading(
        @Param("WebView handle, e.g. #12") ref: String,
    ): String {
        WeWebViewApi.stopLoading(resolveWebView(ref))
        return "Stopped."
    }

    @WeKitOperation(
        name = "webview-go-back",
        description = "Navigate a WebView handle back in its history if possible. Returns whether it " +
                "could go back.",
        sideEffect = true,
        group = BUILTIN_WEBVIEW,
    )
    fun webviewGoBack(
        @Param("WebView handle, e.g. #12") ref: String,
    ): String = if (WeWebViewApi.goBack(resolveWebView(ref))) "Went back." else "Cannot go back."

    @WeKitOperation(
        name = "webview-go-forward",
        description = "Navigate a WebView handle forward in its history if possible. Returns whether " +
                "it could go forward.",
        sideEffect = true,
        group = BUILTIN_WEBVIEW,
    )
    fun webviewGoForward(
        @Param("WebView handle, e.g. #12") ref: String,
    ): String = if (WeWebViewApi.goForward(resolveWebView(ref))) "Went forward." else "Cannot go forward."
}
