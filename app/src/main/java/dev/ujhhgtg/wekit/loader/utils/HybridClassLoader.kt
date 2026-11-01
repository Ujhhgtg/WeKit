package dev.ujhhgtg.wekit.loader.utils

import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URL
import java.util.Collections
import java.util.Enumeration
import java.util.IdentityHashMap

object HybridClassLoader : ClassLoader(ClassLoaders.BOOT) {
    lateinit var moduleParentClassLoader: ClassLoader
    lateinit var moduleClassLoader: ClassLoader
    lateinit var hostClassLoader: ClassLoader
    private val resolvingResourceLoaders =
        ThreadLocal.withInitial<MutableSet<ClassLoader>> {
            Collections.newSetFromMap(IdentityHashMap())
        }

    private val moduleFindClassMethod: Method by lazy {
        ClassLoader::class.java.getDeclaredMethod("findClass", String::class.java).apply {
            isAccessible = true
        }
    }

    private const val PREFIX_HOST = "HOST."

    override fun findClass(name: String): Class<*> {
        if (name.startsWith(PREFIX_HOST)) {
            if (::hostClassLoader.isInitialized) {
                return hostClassLoader.loadClass(name.removePrefix(PREFIX_HOST))
            }
            throw ClassNotFoundException(
                "hostClassLoader is not initialized. Class: $name",
            )
        }

        if (SHARED_PREFIXES.any(name::startsWith)) {
            return moduleParentClassLoader.loadClass(name)
        }

        if (::moduleClassLoader.isInitialized) {
            try {
                return findModuleOwnClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        if (::moduleParentClassLoader.isInitialized) {
            try {
                return moduleParentClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        if (::hostClassLoader.isInitialized) {
            try {
                return hostClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        throw ClassNotFoundException(name)
    }

    override fun findResource(name: String): URL? {
        val resolving = resolvingResourceLoaders.get()!!
        try {
            for (loader in resourceLoaders()) {
                if (!resolving.add(loader)) continue
                try {
                    loader.getResource(name)?.let {
                        return it
                    }
                } finally {
                    resolving.remove(loader)
                }
            }
            return null
        } finally {
            if (resolving.isEmpty()) resolvingResourceLoaders.remove()
        }
    }

    override fun findResources(name: String): Enumeration<URL> {
        val urls = linkedMapOf<String, URL>()
        val resolving = resolvingResourceLoaders.get()!!
        try {
            for (loader in resourceLoaders()) {
                if (!resolving.add(loader)) continue
                try {
                    val resources = loader.getResources(name)
                    while (resources.hasMoreElements()) {
                        val resource = resources.nextElement()
                        urls.putIfAbsent(resource.toExternalForm(), resource)
                    }
                } finally {
                    resolving.remove(loader)
                }
            }
        } finally {
            if (resolving.isEmpty()) resolvingResourceLoaders.remove()
        }
        return Collections.enumeration(urls.values)
    }

    override fun getResources(name: String): Enumeration<URL> {
        val resources = super.getResources(name)
        val urls = linkedMapOf<String, URL>()
        while (resources.hasMoreElements()) {
            val resource = resources.nextElement()
            urls.putIfAbsent(resource.toExternalForm(), resource)
        }
        return Collections.enumeration(urls.values)
    }

    private fun resourceLoaders(): List<ClassLoader> = buildList {
        // The parent's resource-only bridge owns the sealed APK ZIP; don't recurse through MODULE.
        if (::moduleParentClassLoader.isInitialized) add(moduleParentClassLoader)
        if (::hostClassLoader.isInitialized) add(hostClassLoader)
    }

    private fun findModuleOwnClass(name: String): Class<*> =
        try {
            moduleFindClassMethod.invoke(moduleClassLoader, name) as Class<*>
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private val SHARED_PREFIXES =
        listOf(
            "dev.ujhhgtg.wekit.loader.abc.",
            "dev.ujhhgtg.wekit.loader.api.",
            "dev.ujhhgtg.wekit.bootstrap.",
            "dev.ujhhgtg.reflekt.",
            "kotlin.",
        )
}
