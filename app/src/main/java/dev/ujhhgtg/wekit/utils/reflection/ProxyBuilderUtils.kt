package dev.ujhhgtg.wekit.utils.reflection

import com.android.dx.stock.ProxyBuilder
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import java.lang.reflect.InvocationHandler
import kotlin.io.path.div

fun <T : Any> createProxyBuilder(
    classLoader: ClassLoader,
    baseClass: Class<T>,
    constructorArgs: Array<Class<*>>,
    handler: InvocationHandler,
    interfaces: Array<Class<*>>,
): ProxyBuilder<T> =
    ProxyBuilder.forClass(baseClass)
        .dexCache((KnownPaths.codeCacheDir / "generated_proxy_classes").createDirsSafe().toFile())
        .parentClassLoader(classLoader)
        .constructorArgTypes(*constructorArgs)
        .implementing(*interfaces)
        .handler(handler)

fun ProxyBuilder<*>.buildClass(handler: InvocationHandler): Class<*> =
    this.buildProxyClass().also {
        // if generating a proxy class with buildProxyClass(), instances do not automatically have a
        // handler set
        it.reflekt().firstConstructor().hookAfterDirectly {
            ProxyBuilder.setInvocationHandler(thisObject, handler)
        }
    }
