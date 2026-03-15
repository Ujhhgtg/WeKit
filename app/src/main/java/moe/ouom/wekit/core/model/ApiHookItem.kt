package moe.ouom.wekit.core.model

import moe.ouom.wekit.utils.TargetProcessUtils

abstract class ApiHookItem : BaseHookItem() {

    val targetProcess: Int = targetProcess()

    open fun targetProcess(): Int = TargetProcessUtils.PROC_MAIN
}
