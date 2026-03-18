package moe.ouom.wekit.loader.abc

import androidx.annotation.Keep
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method

@Keep
interface IHookBridge {
    interface IMemberHookCallback {
        fun beforeHookedMember(param: IMemberHookParam)

        fun afterHookedMember(param: IMemberHookParam)
    }

    interface IMemberHookParam {
        val member: Member

        val thisObject: Any?

        val args: Array<Any?>

        var result: Any?

        var throwable: Throwable?

        var extra: Any?
    }

    interface MemberUnhookHandle {
        val member: Member

        val callback: IMemberHookCallback

        val isHookActive: Boolean

        fun unhook()
    }

    val apiLevel: Int

    val frameworkName: String

    val frameworkVersion: String

    val frameworkVersionCode: Long

    fun hookMethod(member: Member, callback: IMemberHookCallback, priority: Int): MemberUnhookHandle

    val isDeoptimizationSupported: Boolean

    fun deoptimize(member: Member): Boolean

    fun invokeOriginalMethod(method: Method, thisObject: Any?, args: Array<Any?>): Any?

    fun <T> invokeOriginalConstructor(ctor: Constructor<T?>, thisObject: T, args: Array<Any?>)

    fun <T> newInstanceOrigin(constructor: Constructor<T?>, vararg args: Any): T

    val hookCounter: Long

    val hookedMethods: Set<Member?>?

    companion object {
        const val PRIORITY_DEFAULT: Int = 50

        const val PRIORITY_LOWEST: Int = -10000

        const val PRIORITY_HIGHEST: Int = 10000
    }
}
