package moe.ouom.wekit.core.model

import android.content.Context

abstract class ClickableHookItem : SwitchHookItem() {

    val alwaysEnable: Boolean = alwaysRun()

    open fun alwaysRun(): Boolean = false

    open fun noSwitchWidget(): Boolean = false

    abstract fun onClick(context: Context)
}
