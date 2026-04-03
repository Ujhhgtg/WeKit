package dev.ujhhgtg.wekit.loader.entry.lsp100.codegen

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.loader.entry.lsp100.Lsp100HookImpl
import dev.ujhhgtg.wekit.loader.entry.lsp100.Lsp100HookWrapper
import dev.ujhhgtg.wekit.loader.entry.lsp100.dyn.Lsp100CallbackProxy
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

@Keep
class Lsp100ProxyClassMaker private constructor() {

    private val xposedHookerClassName: String?
    private val beforeInvocationClassName: String?
    private val afterInvocationClassName: String?

    init {
        val templateClass = Lsp100CallbackProxy.P0000000050::class.java

        xposedHookerClassName = templateClass.annotations.firstOrNull()?.annotationClass?.java?.name

        beforeInvocationClassName = try {
            templateClass.getMethod("before", XposedInterface.BeforeHookCallback::class.java)
                .annotations.firstOrNull()?.annotationClass?.java?.name
        } catch (e: NoSuchMethodException) {
            throw UnsupportedOperationException("Method before not found in template class", e)
        }

        afterInvocationClassName = try {
            templateClass.getMethod(
                "after",
                XposedInterface.AfterHookCallback::class.java,
                Lsp100HookWrapper.InvocationParamWrapper::class.java
            ).annotations.firstOrNull()?.annotationClass?.java?.name
        } catch (e: NoSuchMethodException) {
            throw UnsupportedOperationException("Method after not found in template class", e)
        }
    }

    fun createProxyClass(priority: Int): Class<*> {
        val className = getClassNameForPriority(priority)

        Lsp100ProxyClassMaker::class.java.classLoader
            ?.runCatching { loadClass(className) }
            ?.getOrNull()
            ?.let { return it }

        sLoadClassException?.let {
            throw UnsupportedOperationException("reject to try again due to previous exception", it)
        }

        sProxyClassLoader?.runCatching { loadClass(className) }?.getOrNull()?.let { return it }

        val dex = makeClassByteCodeForPriority(priority)
        return loadProxyClassForPriority(className, dex)
    }

    private fun loadProxyClassForPriority(className: String, dex: ByteArray): Class<*> {
        val helper = Lsp100HookImpl.classLoaderHelper
            ?: throw UnsupportedOperationException("ClassLoaderHelper not set")

        sLoadClassException?.let {
            throw UnsupportedOperationException("reject to try again due to previous exception", it)
        }

        if (sProxyClassLoader == null) {
            synchronized(Lsp100ProxyClassMaker::class.java) {
                if (sProxyClassLoader == null) {
                    sProxyClassLoader = helper.createEmptyInMemoryMultiDexClassLoader(
                        Lsp100HookImpl::class.java.classLoader!!
                    )
                }
            }
        }

        sProxyClassLoader!!.runCatching { loadClass(className) }.getOrNull()?.let { return it }

        helper.injectDexToClassLoader(sProxyClassLoader!!, dex, null)

        return try {
            Class.forName(className, true, sProxyClassLoader)
        } catch (e: ClassNotFoundException) {
            sLoadClassException = e
            throw UnsupportedOperationException("Failed to load proxy class", e)
        }
    }

    private fun makeClassByteCodeForPriority(priority: Int): ByteArray {
        return impl1(
            targetClassName = getClassNameForPriority(priority),
            tagValue = priority,
            classNameXposedInterfaceHooker = XposedInterface.Hooker::class.java.name,
            classBeforeHookCallback = XposedInterface.BeforeHookCallback::class.java.name,
            classAfterHookCallback = XposedInterface.AfterHookCallback::class.java.name,
            classNameXposedHooker = xposedHookerClassName,
            classNameBeforeInvocation = beforeInvocationClassName,
            classNameAfterInvocation = afterInvocationClassName
        )
    }

    companion object {
        @JvmStatic
        private var sInstance: Lsp100ProxyClassMaker? = null

        @Volatile
        private var sProxyClassLoader: ClassLoader? = null

        private var sWrapperMethod: Method? = null

        private var sLoadClassException: Throwable? = null

        @JvmStatic
        fun setWrapperMethod(method: Method) {
            sWrapperMethod = method
        }

//        @JvmStatic
//        fun getWrapperMethod(): Method? = sWrapperMethod

        @JvmStatic
        fun getInstance(): Lsp100ProxyClassMaker =
            sInstance ?: Lsp100ProxyClassMaker().also { sInstance = it }

        @JvmStatic
        fun impl1(
            targetClassName: String,
            tagValue: Int,
            classNameXposedInterfaceHooker: String,
            classBeforeHookCallback: String,
            classAfterHookCallback: String,
            classNameXposedHooker: String?,
            classNameBeforeInvocation: String?,
            classNameAfterInvocation: String?
        ): ByteArray {
            val wrapperMethod = sWrapperMethod
                ?: throw UnsupportedOperationException("Wrapper method not set")

            val args = arrayOf<Any?>(
                targetClassName,
                tagValue,
                classNameXposedInterfaceHooker,
                classBeforeHookCallback,
                classAfterHookCallback,
                classNameXposedHooker,
                classNameBeforeInvocation,
                classNameAfterInvocation
            )
            return try {
                wrapperMethod.invoke(null, 1, args) as ByteArray
            } catch (e: ReflectiveOperationException) {
                if (e is InvocationTargetException) {
                    val cause = e.targetException
                    if (cause is RuntimeException) throw cause
                }
                throw UnsupportedOperationException("Failed to invoke wrapper method", e)
            }
        }

        private fun priorityToShortName(priority: Int): String =
            if (priority >= 0) "P%010d".format(priority)
            else "N%010d".format(-priority.toLong())

        private fun getClassNameForPriority(priority: Int): String =
            $$"dev.ujhhgtg.wekit.loader.sbl.lsp100.dyn.Lsp100CallbackProxy$$${
                priorityToShortName(
                    priority
                )
            }"
    }
}
