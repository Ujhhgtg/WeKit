package dev.ujhhgtg.wekit.python.api

interface PythonHookHost {
    fun before(member: PythonMember, callback: PythonHookCallback, priority: Int = 50): PythonHookToken
    fun after(member: PythonMember, callback: PythonHookCallback, priority: Int = 50): PythonHookToken
    fun replace(member: PythonMember, callback: PythonHookCallback, priority: Int = 50): PythonHookToken
}

data class PythonMember(val descriptor: String)

fun interface PythonHookCallback {
    fun invoke(parameter: PythonHookParameter): Any?
}

interface PythonHookParameter {
    val thisObject: Any?
    val args: List<Any?>
    var result: Any?
    var throwable: Throwable?
    fun invokeOriginal(): Any?
}

data class PythonHookToken(val id: String)
