package dev.ujhhgtg.wekit.python.api

import java.util.concurrent.Callable

interface PythonTaskHost {
    fun main(task: Callable<Any?>): PythonTaskHandle
    fun mainAsync(task: Callable<Any?>): PythonTaskHandle
    fun spawn(task: Callable<Any?>): PythonTaskHandle
}

interface PythonTaskHandle {
    fun cancel()
    fun isDone(): Boolean
    fun awaitResult(): Any?
}
