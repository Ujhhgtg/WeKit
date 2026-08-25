package dev.ujhhgtg.wekit.features.items.miniapps

import android.webkit.ValueCallback
import android.webkit.WebView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeWebViewApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.loader.utils.ResourcesInjector
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString

object ErudaConsole : SwitchFeature() {

    override val technicalId = "Eruda 调试面板"
    override val nameRes = R.string.feature_eruda_console_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_eruda_console_description

    private val erudaScript by lazy {
        val resources = HostInfo.application.resources
        ResourcesInjector.injectModuleRes(resources)
        resources.openRawResource(R.raw.eruda)
            .bufferedReader()
            .use { it.readText() }
    }

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        WeWebViewApi.xwebOnPageFinished.hookAfter {
            WeLogger.i(TAG, "injecting into xwebOnPageFinished: ${args[0]}")
            injectEruda(args[0]!!)
        }
        WeWebViewApi.androidOnPageFinished.hookAfter {
            WeLogger.i(TAG, "injecting into androidOnPageFinished: ${args[0]}")
            injectEruda(args[0]!!)
        }
    }

    private fun injectEruda(webView: Any) {
        try {
            when (webView) {
                is WebView -> {
                    webView.evaluateJavascript(erudaScript, null)
                    webView.evaluateJavascript("eruda.init();", null)
                }

                is com.tencent.xweb.WebView -> {
                    webView.evaluateJavascript(erudaScript, null)
                    webView.evaluateJavascript("eruda.init();", null)
                }

                else -> {
                    webView.reflekt().firstMethod {
                        name = "evaluateJavascript"
                        parameters(BString, ValueCallback::class)
                        superclass()
                    }.apply {
                        invoke(erudaScript, null)
                        invoke("eruda.init();", null)
                    }
                }
            }
            WeLogger.i(TAG, "injected eruda")
        } catch (e: Throwable) {
            WeLogger.w(TAG, "failed to inject eruda", e)
        }
    }

    private const val TAG = "ErudaConsole"
}
