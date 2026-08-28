package dev.ujhhgtg.wekit.python.api

interface PythonPluginHost {
    fun logger(pluginId: String): PythonLogger
    fun hooks(pluginId: String): PythonHookHost
    fun dex(pluginId: String): PythonDexHost
    fun tasks(pluginId: String): PythonTaskHost
    fun beginExecution(pluginId: String, phase: String): Long
    fun finishExecution(pluginId: String, token: Long)
}
