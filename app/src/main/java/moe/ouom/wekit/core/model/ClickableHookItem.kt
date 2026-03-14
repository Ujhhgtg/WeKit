package moe.ouom.wekit.core.model

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.config.WePrefs
import moe.ouom.wekit.utils.common.SyncUtils
import moe.ouom.wekit.utils.log.WeLogger

abstract class ClickableHookItem : BaseHookItem() {

    companion object {
        private val TAG = nameof(ClickableHookItem)
    }

    val targetProcess: Int = targetProcess()
    val alwaysRun: Boolean = alwaysRun()
    var isEnabled: Boolean = false
    private var isLoaded: Boolean = false
    private var toggleCompletionCallback: Runnable? = null

    fun setToggleCompletionCallback(callback: Runnable) {
        toggleCompletionCallback = callback
    }

    fun applyToggle(newState: Boolean) {
        WePrefs.putBool(path, newState)
        setIsEnabled(newState)
        toggleCompletionCallback?.run()
    }

    open fun targetProcess(): Int = SyncUtils.PROC_MAIN

    open fun alwaysRun(): Boolean = false

    fun setIsEnabled(enabled: Boolean) {
        if (this.isEnabled == enabled) return
        this.isEnabled = enabled
        if (!enabled) {
            if (isLoaded) {
                WeLogger.i("unloading $TAG: $path")
                try {
                    unload()
                    isLoaded = false
                } catch (e: Throwable) {
                    WeLogger.e("failed to unload $path", e)
                }
            }
        } else {
            WeLogger.i("loading $TAG: $path")
            load()
            isLoaded = true
        }
    }

    final override fun tryExecute(param: XC_MethodHook.MethodHookParam, hookAction: HookAction) {
        if (isEnabled) super.tryExecute(param, hookAction)
    }

    open fun noSwitchWidget(): Boolean = false

    open fun onBeforeToggle(newState: Boolean, context: Context): Boolean = true

    abstract fun onClick(context: Context)
}
