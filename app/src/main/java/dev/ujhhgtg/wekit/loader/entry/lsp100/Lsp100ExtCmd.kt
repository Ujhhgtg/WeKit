package dev.ujhhgtg.wekit.loader.entry.lsp100

import dev.ujhhgtg.wekit.loader.entry.lsp100.codegen.Lsp100ProxyClassMaker
import dev.ujhhgtg.wekit.loader.utils.LibXposedApiByteCodeGenerator
import io.github.libxposed.api.XposedInterface

object Lsp100ExtCmd {

    fun handleQueryExtension(cmd: String, arg: Array<Any?>?): Any? {
        return when (cmd) {
            "GetXposedInterfaceClass" -> XposedInterface::class.java
            "GetInitErrors" -> emptyList<Throwable?>()
            LibXposedApiByteCodeGenerator.CMD_SET_WRAPPER -> {
                Lsp100ProxyClassMaker.setWrapperMethod((arg!![0] as java.lang.reflect.Method?)!!)
                true
            }

            "GetLoadPackageParam", "GetInitZygoteStartupParam" -> null
            else -> null
        }
    }
}
