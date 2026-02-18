package moe.ouom.wekit.utils

import moe.ouom.wekit.utils.log.WeLogger

object Initiator {
    private var sHostClassLoader: ClassLoader? = null
    private var sPluginParentClassLoader: ClassLoader? = null
    private val sClassCache = HashMap<String?, Class<*>?>(16)


    @JvmStatic
    fun init(classLoader: ClassLoader) {
        sHostClassLoader = classLoader
        sPluginParentClassLoader = Initiator::class.java.classLoader
    }

    val pluginClassLoader: ClassLoader?
        get() = Initiator::class.java.classLoader

    val hostClassLoader: ClassLoader
        get() = sHostClassLoader!!

    /**
     * Load a class, if the class is not found, null will be returned.
     *
     * @param className The class name.
     * @return The class, or null if not found.
     */
    fun load(className: String?): Class<*>? {
        var className = className
        if (sPluginParentClassLoader == null || className.isNullOrEmpty()) {
            return null
        }
        if (className.endsWith(";") || className.contains("/")) {
            className = className.replace('/', '.')
            if (className.endsWith(";")) {
                if (className[0] == 'L') {
                    className = className.substring(1, className.length - 1)
                } else {
                    className = className.substring(0, className.length - 1)
                }
            }
        }
        return try {
            sHostClassLoader!!.loadClass(className)
        } catch (_: ClassNotFoundException) {
            null
        }
    }

    fun findClassWithSynthetics(className1: String, className2: String): Class<*>? {
        val clazz = load(className1)
        if (clazz != null) {
            return clazz
        }
        return load(className2)
    }

    fun findClassWithSynthetics(
        className1: String, className2: String,
        className3: String, vararg index: Int
    ): Class<*>? {
        val cache = sClassCache[className1]
        if (cache != null) {
            return cache
        }
        var clazz = findClassWithSyntheticsImpl(className1, *index)
        if (clazz != null) {
            sClassCache[className1] = clazz
            return clazz
        }
        clazz = findClassWithSyntheticsImpl(className2, *index)
        if (clazz != null) {
            sClassCache[className1] = clazz
            return clazz
        }
        clazz = findClassWithSyntheticsImpl(className3, *index)
        if (clazz != null) {
            sClassCache[className1] = clazz
            return clazz
        }
        WeLogger.e("Initiator/E class $className1 not found")
        return null
    }

    /**
     * Load a class, if the class is not found, a ClassNotFoundException will be thrown.
     *
     * @param className The class name.
     * @return The class.
     * @throws ClassNotFoundException If the class is not found.
     */
    @Throws(ClassNotFoundException::class)
    fun loadClass(className: String?): Class<*> {
        val ret: Class<*> = load(className) ?: throw ClassNotFoundException(className)
        return ret
    }

    @Throws(ClassNotFoundException::class)
    fun loadClassEither(vararg classNames: String): Class<*> {
        for (className in classNames) {
            val ret = load(className)
            if (ret != null) {
                return ret
            }
        }
        throw ClassNotFoundException("Class not found for names: " + classNames.contentToString())
    }

    private fun findClassWithSyntheticsImpl(className: String, vararg index: Int): Class<*>? {
        val clazz = load(className)
        if (clazz != null) {
            return clazz
        }
        if (index != null) {
            for (i in index) {
                val cref = load("$className$$i")
                if (cref != null) {
                    try {
                        return cref.getDeclaredField("this$0").type
                    } catch (_: ReflectiveOperationException) { }
                }
            }
        }
        return null
    }


    private fun findClassWithSyntheticsSilently(
        className: String,
        vararg index: Int
    ): Class<*>? {
        val cache = sClassCache[className]
        if (cache != null) {
            return cache
        }
        var clazz = load(className)
        if (clazz != null) {
            sClassCache[className] = clazz
            return clazz
        }
        clazz = findClassWithSyntheticsImpl(className, *index)
        if (clazz != null) {
            sClassCache[className] = clazz
            return clazz
        }
        return null
    }

    fun findClassWithSynthetics0(
        className1: String,
        className2: String,
        vararg index: Int
    ): Class<*>? {
        val cache = sClassCache[className1]
        if (cache != null) {
            return cache
        }
        var clazz = findClassWithSyntheticsImpl(className1, *index)
        if (clazz != null) {
            sClassCache[className1] = clazz
            return clazz
        }
        clazz = findClassWithSyntheticsImpl(className2, *index)
        if (clazz != null) {
            sClassCache[className1] = clazz
            return clazz
        }
        return null
    }

    fun findClassWithSynthetics(
        className1: String,
        className2: String,
        vararg index: Int
    ): Class<*>? {
        val ret = findClassWithSynthetics0(className1, className2, *index)
        logErrorIfNotFound(ret, className1)
        return ret
    }


    fun findClassWithSynthetics(className: String, vararg index: Int): Class<*>? {
        val cache = sClassCache[className]
        if (cache != null) {
            return cache
        }
        var clazz = load(className)
        if (clazz != null) {
            sClassCache[className] = clazz
            return clazz
        }
        clazz = findClassWithSyntheticsImpl(className, *index)
        if (clazz != null) {
            sClassCache[className] = clazz
            return clazz
        }
        WeLogger.e("Initiator/E class $className not found")
        return null
    }

    private fun logErrorIfNotFound(c: Class<*>?, name: String) {
        if (c == null) {
            WeLogger.e("Initiator/E class $name not found")
        }
    }
}
