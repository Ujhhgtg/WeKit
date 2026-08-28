package dev.ujhhgtg.wekit.python.api

interface PythonHookHost {
    fun before(member: Any, callback: PythonHookCallback, priority: Int = 50): PythonHookToken
    fun after(member: Any, callback: PythonHookCallback, priority: Int = 50): PythonHookToken
    fun replace(member: Any, callback: PythonHookCallback, priority: Int = 50): PythonHookToken
    fun invokeOriginal(parameter: Any): Any?
    fun unhook(token: PythonHookToken)
}

interface PythonMemberHandle { val descriptor: String }

data class PythonMember(override val descriptor: String) : PythonMemberHandle

fun interface PythonHookCallback {
    fun invoke(parameter: Any): Any?
}

data class PythonHookToken(val id: String)
