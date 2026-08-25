package dev.ujhhgtg.wekit.features.core

import android.content.Context
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger

abstract class SwitchFeature : BaseFeature() {

    /** Optional order override within each concrete settings category. Lower values appear first. */
    open val displayOrder: Int? = null

    /**
     * Default state when the user has never toggled this feature.
     *
     * Public so the settings UI can seed its switches with the same default [startup] uses —
     * otherwise a feature defaulting to on would show as off until first toggled.
     */
    open val defaultEnabled: Boolean = false

    /** Whether this feature should load in the current process. Defaults to the main process only. */
    protected open val shouldLoadInCurrentProcess: Boolean
        get() = TargetProcesses.isInMain

    /** Whether the feature should be active at startup, given the cached preference. */
    protected open val shouldEnableOnStartup: Boolean
        get() = _isEnabled

    internal fun loadPersistedState() {
        _isEnabled = WePrefs.getBoolOrDef(technicalId, defaultEnabled)
    }

    final override fun startup() {
        if (!shouldLoadInCurrentProcess) return
        if (shouldEnableOnStartup) enable()
    }

    /** Cached user preference (desired state). Distinct from [isActive], the runtime truth. */
    @Suppress("PropertyName")
    protected var _isEnabled = false

    var isEnabled
        get() = _isEnabled
        set(value) {
            if (_isEnabled == value) return
            _isEnabled = value
            if (value) {
                WeLogger.i("SwitchFeature", "enabling $technicalPath...")
                enable()
            } else {
                WeLogger.i("SwitchFeature", "disabling $technicalPath...")
                disable()
            }
        }

    private var toggleCompletionCallback: Runnable? = null

    open fun onBeforeToggle(newState: Boolean, context: Context): Boolean = true

    fun setToggleCompletionCallback(callback: Runnable) {
        toggleCompletionCallback = callback
    }

    fun applyToggle(newState: Boolean) {
        WePrefs.putBool(technicalId, newState)
        isEnabled = newState
        toggleCompletionCallback?.run()
    }
}
