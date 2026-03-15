package moe.ouom.wekit.loader.modern.codegen

import io.github.libxposed.api.XposedInterface
import moe.ouom.wekit.loader.modern.Lsp100HookImpl
import moe.ouom.wekit.loader.modern.Lsp100HookWrapper
import moe.ouom.wekit.loader.modern.dyn.Lsp100CallbackProxy
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Locale
import java.util.Objects

object Lsp100ProxyClassMaker {

    private val mXposedHookerClassName: String?
    private val mBeforeInvocationClassName: String?
    private val mAfterInvocationClassName: String?

    init {
        val templateClass = Lsp100CallbackProxy.P0000000050::class.java
        mXposedHookerClassName = templateClass.annotations.firstOrNull()?.annotationClass?.java?.name

        mBeforeInvocationClassName = try {
            templateClass.getMethod("before", XposedInterface.BeforeHookCallback::class.java)
                .annotations.firstOrNull()?.annotationClass?.java?.name
        } catch (e: NoSuchMethodException) {
            throw UnsupportedOperationException("Method before not found in template class", e)
        }

        mAfterInvocationClassName = try {
            templateClass.getMethod("after",
                XposedInterface.AfterHookCallback::class.java,
                Lsp100HookWrapper.InvocationParamWrapper::class.java
            ).annotations.firstOrNull()?.annotationClass?.java?.name
        } catch (e: NoSuchMethodException) {
            throw UnsupportedOperationException("Method after not found in template class", e)
        }
    }

    fun createProxyClass(priority: Int): Class<*> {
        val className = getClassNameForPriority(priority)
        try {
            return Lsp100ProxyClassMaker::class.java.classLoader!!.loadClass(className)
        } catch (_: ClassNotFoundException) {}

        sLoadClassException?.let {
            throw UnsupportedOperationException("reject to try again due to previous exception", it)
        }

        sProxyClassLoader?.let { loader ->
            try {
                return loader.loadClass(className)
            } catch (_: ClassNotFoundException) {}
        }

        val dex = makeClassByteCodeForPriority(priority)
        return loadProxyClass(className, dex)
    }

    private fun loadProxyClass(className: String, dex: ByteArray): Class<*> {
        val helper = Lsp100HookImpl.INSTANCE.classLoaderHelper
            ?: throw UnsupportedOperationException("ClassLoaderHelper not set")

        sLoadClassException?.let {
            throw UnsupportedOperationException("reject to try again due to previous exception", it)
        }

        if (sProxyClassLoader == null) {
            synchronized(Lsp100ProxyClassMaker::class.java) {
                if (sProxyClassLoader == null) {
                    sProxyClassLoader = helper.createEmptyInMemoryMultiDexClassLoader(
                        Objects.requireNonNull(Lsp100HookImpl::class.java.classLoader)
                    )
                }
            }
        }

        try {
            return sProxyClassLoader!!.loadClass(className)
        } catch (_: ClassNotFoundException) {}

        helper.injectDexToClassLoader(sProxyClassLoader!!, dex, null)

        return try {
            Class.forName(className, true, sProxyClassLoader)
        } catch (e: ClassNotFoundException) {
            sLoadClassException = e
            throw UnsupportedOperationException("Failed to load proxy class", e)
        }
    }

    private fun makeClassByteCodeForPriority(priority: Int): ByteArray {
        val className = getClassNameForPriority(priority)
        return impl1(
            className,
            priority,
            XposedInterface.Hooker::class.java.name,
            XposedInterface.BeforeHookCallback::class.java.name,
            XposedInterface.AfterHookCallback::class.java.name,
            mXposedHookerClassName,
            mBeforeInvocationClassName,
            mAfterInvocationClassName
        )
    }

    private var sProxyClassLoader: ClassLoader? = null
    private var sWrapperMethod: Method? = null
    private var sLoadClassException: Throwable? = null

    fun setWrapperMethod(method: Method) { sWrapperMethod = method }


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
            ?: error("Wrapper method not set")
        val args = arrayOf<Any?>(
            targetClassName, tagValue, classNameXposedInterfaceHooker,
            classBeforeHookCallback, classAfterHookCallback,
            classNameXposedHooker, classNameBeforeInvocation, classNameAfterInvocation
        )
        return try {
            checkNotNull(wrapperMethod.invoke(null, 1, args)) as ByteArray
        } catch (e: ReflectiveOperationException) {
            if (e is InvocationTargetException) {
                val target = e.targetException
                if (target is RuntimeException) throw target
            }
            throw UnsupportedOperationException("Failed to invoke wrapper method", e)
        }
    }

    private fun priorityToShortName(priority: Int): String =
        if (priority >= 0) "P${String.format(Locale.ROOT, "%010d", priority)}"
        else "N${String.format(Locale.ROOT, "%010d", -priority.toLong())}"

    private fun getClassNameForPriority(priority: Int): String =
        $$"moe.ouom.wekit.loader.modern.dyn.Lsp100CallbackProxy$$${priorityToShortName(priority)}"
}
