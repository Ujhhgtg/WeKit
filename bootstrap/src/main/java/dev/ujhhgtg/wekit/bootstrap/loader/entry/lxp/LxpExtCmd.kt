package dev.ujhhgtg.wekit.bootstrap.loader.entry.lxp

import io.github.libxposed.api.XposedInterface

object LxpExtCmd {

    fun handleQueryExtension(cmd: String): Any? {
        return when (cmd) {
            "GetXposedInterfaceClass" -> XposedInterface::class.java
            "GetInitErrors" -> emptyList<Throwable?>()
            else -> null
        }
    }
}
