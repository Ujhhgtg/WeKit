package moe.ouom.wekit.core.model

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.utils.TargetProcessUtils
import moe.ouom.wekit.utils.logging.WeLogger

abstract class SwitchHookItem : BaseHookItem() {

    val targetProcess: Int = targetProcess()

    private var _isEnabled: Boolean = false
    var isEnabled: Boolean
        get() = _isEnabled
        set(value) {
            if (_isEnabled == value) return
            _isEnabled = value
            if (!value) {
                if (isLoaded) {
                    WeLogger.i("disabling $path...")
                    try {
                        disable()
                        isLoaded = false
                    } catch (e: Throwable) {
                        WeLogger.e("failed to disable $path", e)
                    }
                }
            } else {
                WeLogger.i("enabling $path...")
                enable()
                isLoaded = true
            }
        }

    fun setEnabledSilently(value: Boolean) {
        _isEnabled = value
    }

    private var isLoaded: Boolean = false
    private var toggleCompletionCallback: Runnable? = null

    open fun targetProcess(): Int = TargetProcessUtils.PROC_MAIN

    open fun onBeforeToggle(newState: Boolean, context: Context): Boolean = true

    fun setToggleCompletionCallback(callback: Runnable) {
        toggleCompletionCallback = callback
    }

    fun applyToggle(newState: Boolean) {
        WePrefs.putBool(path, newState)
        isEnabled = newState
        toggleCompletionCallback?.run()
    }

    final override fun tryExecute(param: XC_MethodHook.MethodHookParam, hookAction: HookAction) {
        if (isEnabled) super.tryExecute(param, hookAction)
    }
}
