package dev.ujhhgtg.wekit.features.items.scripting_python.services

import dev.ujhhgtg.wekit.python.api.PythonLogger
import dev.ujhhgtg.wekit.utils.WeLogger

internal class PythonLogHostImpl(pluginId: String) : PythonLogger {
    private val tag = "Python/$pluginId"

    override fun debug(message: String, vararg arguments: Any?) = WeLogger.d(tag, render(message, arguments))
    override fun info(message: String, vararg arguments: Any?) = WeLogger.i(tag, render(message, arguments))
    override fun warning(message: String, vararg arguments: Any?) = WeLogger.w(tag, render(message, arguments))
    override fun error(message: String, vararg arguments: Any?) = WeLogger.e(tag, render(message, arguments))
    override fun exception(message: String, throwable: Throwable, vararg arguments: Any?) =
        WeLogger.e(tag, render(message, arguments), throwable)

    private fun render(message: String, arguments: Array<out Any?>): String =
        if (arguments.isEmpty()) message else "$message ${arguments.joinToString()}"
}
