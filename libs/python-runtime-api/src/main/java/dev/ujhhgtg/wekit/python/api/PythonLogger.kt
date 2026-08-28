package dev.ujhhgtg.wekit.python.api

interface PythonLogger {
    fun info(message: String, vararg arguments: Any?)
    fun warning(message: String, vararg arguments: Any?)
    fun error(message: String, vararg arguments: Any?)
    fun exception(message: String, throwable: Throwable, vararg arguments: Any?)
}
