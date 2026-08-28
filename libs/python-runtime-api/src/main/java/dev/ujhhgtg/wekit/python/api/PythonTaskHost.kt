package dev.ujhhgtg.wekit.python.api

interface PythonTaskHost {
    fun main(task: Runnable): PythonTaskHandle
    fun mainAsync(task: Runnable): PythonTaskHandle
    fun spawn(task: Runnable): PythonTaskHandle
}

interface PythonTaskHandle {
    fun cancel()
    fun isDone(): Boolean
}
