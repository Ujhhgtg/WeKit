package dev.ujhhgtg.wekit.utils.monet

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.WeLogger

object FlutterMonet {
    private var lastReport: String? = null

    fun start(owner: BaseFeature) {
        val flutter = "io.flutter.embedding.engine.FlutterJNI".toClass().reflekt()
        listOf("nativeRunBundleAndSnapshotFromLibrary", "nativeSpawn").forEach { methodName ->
            flutter
                .methods { name = methodName }
                .forEach { method ->
                    with(owner) { method.hookBefore { install() } }
                }
        }
        install()
    }

    fun update(colors: IntArray, dark: Boolean) {
        Native.updatePalette(colors, dark)
        Native.setEnabled(true)
    }

    fun stop() {
        Native.setEnabled(false)
    }

    private fun install() {
        val report = Native.install() ?: return
        if (report != lastReport) {
            lastReport = report
            WeLogger.i("FlutterMonet", report)
        }
    }

    private object Native {
        init {
            NativeLoader.requireLoaded("wekit_native")
        }

        external fun install(): String?

        external fun updatePalette(colors: IntArray, dark: Boolean)

        external fun setEnabled(enabled: Boolean)
    }
}
