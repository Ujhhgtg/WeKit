package dev.ujhhgtg.wekit.python.api

class PythonRuntimeStartupException(
    val phase: String,
    val library: String? = null,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
