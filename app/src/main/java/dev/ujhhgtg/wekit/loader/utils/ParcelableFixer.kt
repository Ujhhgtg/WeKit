package dev.ujhhgtg.wekit.loader.utils

import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.utils.WeLogger

object ParcelableFixer {

    private val TAG = nameOf(ParcelableFixer)

    private var moduleClassLoader: ClassLoader? = null
    private var isInit = false

    fun init(hostClassLoader: ClassLoader, moduleClassLoader: ClassLoader) {
        if (isInit) return
        isInit = true

        this.moduleClassLoader = object : ClassLoader(hostClassLoader) {
            override fun findClass(name: String): Class<*> = moduleClassLoader.loadClass(name)
        }

        hookIntentMethods()
    }

    fun getHybridClassLoader(): ClassLoader? = moduleClassLoader

    private fun fixIntentExtrasClassLoader(intent: Intent?) {
        val cl = moduleClassLoader ?: return
        runCatching { intent?.setExtrasClassLoader(cl) }
    }

    private fun hookIntentMethods() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                (param.thisObject as? Intent)?.let { fixIntentExtrasClassLoader(it) }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val cl = moduleClassLoader ?: return
                (param.result as? Bundle)?.classLoader = cl
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(Intent::class.java, "getExtras", hook)
            XposedHelpers.findAndHookMethod(
                Intent::class.java,
                "getBundleExtra",
                String::class.java,
                hook
            )
            XposedHelpers.findAndHookMethod(
                Intent::class.java,
                "getParcelableExtra",
                String::class.java,
                hook
            )
            XposedHelpers.findAndHookMethod(
                Intent::class.java,
                "getParcelableArrayListExtra",
                String::class.java,
                hook
            )
            XposedHelpers.findAndHookMethod(
                Intent::class.java,
                "getSerializableExtra",
                String::class.java,
                hook
            )
            // Android 13+
            XposedHelpers.findAndHookMethod(
                Intent::class.java,
                "getParcelableExtra",
                String::class.java,
                Class::class.java,
                hook
            )
            XposedHelpers.findAndHookMethod(
                Intent::class.java,
                "getParcelableArrayListExtra",
                String::class.java,
                Class::class.java,
                hook
            )
            XposedHelpers.findAndHookMethod(
                Intent::class.java,
                "getSerializableExtra",
                String::class.java,
                Class::class.java,
                hook
            )
        }.onFailure { WeLogger.w(TAG, "Failed to hook some Intent methods: ${it.message}") }
    }
}
