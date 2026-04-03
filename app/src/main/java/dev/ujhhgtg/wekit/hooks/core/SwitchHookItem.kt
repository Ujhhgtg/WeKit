package dev.ujhhgtg.wekit.hooks.core

import android.content.Context
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger

abstract class SwitchHookItem : BaseHookItem() {

    override fun startup(process: Int) {
        if (process != TargetProcesses.PROC_MAIN) return
        _isEnabled = WePrefs.getBoolOrFalse(path)
        if (_isEnabled) enable()
    }

    protected var _isEnabled = false

    var isEnabled
        get() = _isEnabled
        set(value) {
            if (_isEnabled == value) return
            _isEnabled = value
            if (!value) {
                if (isLoaded) {
                    WeLogger.i(nameOf(SwitchHookItem::class), "disabling $path...")
                    disable()
                    isLoaded = false
                }
            } else {
                WeLogger.i(nameOf(SwitchHookItem::class), "enabling $path...")
                enable()
                isLoaded = true
            }
        }

    private var isLoaded: Boolean = false
    private var toggleCompletionCallback: Runnable? = null

    open fun onBeforeToggle(newState: Boolean, context: Context): Boolean = true

    fun setToggleCompletionCallback(callback: Runnable) {
        toggleCompletionCallback = callback
    }

    fun applyToggle(newState: Boolean) {
        WePrefs.putBool(path, newState)
        isEnabled = newState
        toggleCompletionCallback?.run()
    }
}
