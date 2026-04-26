package dev.ujhhgtg.wekit.hooks.api.core

import android.annotation.SuppressLint
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.reflection.makeAccessible
import java.lang.reflect.Method

@HookItem(path = "API/Unsafe 服务", description = "提供调用 sun.misc.Unsafe 功能的能力")
object WeUnsafeApi : ApiHookItem() {

    private lateinit var theUnsafe: Any
    private lateinit var mAllocateInstance: Method

    @SuppressLint("DiscouragedPrivateApi")
    override fun onEnable() {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
        theUnsafe = theUnsafeField.makeAccessible().get(null)!!
        mAllocateInstance = unsafeClass.getMethod(
            "allocateInstance",
            Class::class.java
        )
    }

    fun allocateInstance(clazz: Class<*>): Any? = mAllocateInstance.invoke(theUnsafe, clazz)
}
