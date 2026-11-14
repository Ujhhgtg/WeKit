package dev.ujhhgtg.wekit.python.api

import java.io.File

data class PythonPluginRequest(
    val id: String,
    val root: File,
    val entry: String,
    val dataDirectory: File,
    val cacheDirectory: File,
)
