package dev.ujhhgtg.wekit.loader.entry.lsp101

import dev.ujhhgtg.wekit.loader.utils.LibXposedApiByteCodeGenerator
import io.github.libxposed.api.XposedInterface

object Lsp101ExtCmd {

    fun handleQueryExtension(cmd: String): Any? {
        return when (cmd) {
            "GetXposedInterfaceClass" -> XposedInterface::class.java
            "GetLoadPackageParam" -> null
            "GetInitZygoteStartupParam" -> null
            "GetInitErrors" -> emptyList<Throwable?>()
            LibXposedApiByteCodeGenerator.CMD_SET_WRAPPER -> {
                // libxposed API 101 does not require this wrapper,
                // so we just ignore the wrapper method and return true to indicate success.
                true
            }

            else -> null
        }
    }
}
