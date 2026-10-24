package dev.ujhhgtg.wekit.features.core

import androidx.activity.ComponentActivity

abstract class ClickableFeature : SwitchFeature() {
    override val shouldEnableOnStartup: Boolean
        get() = _isEnabled || alwaysEnabled

    open val alwaysEnabled: Boolean = false

    open val noSwitchWidget = false

    abstract fun onClick(context: ComponentActivity)
}
