package moe.ouom.wekit.dexkit

import java.io.Serializable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class DexMethodDescriptor : Serializable {

    val declaringClass: String
    val name: String
    val signature: String

    constructor(desc: String) {
        val a = desc.indexOf("->")
        val b = desc.indexOf('(', a)
        require(a >= 0 && b >= 0) { desc }
        val clz = desc.substring(0, a)
        declaringClass = if (!clz.startsWith("L") && !clz.startsWith("["))
            "L${clz.replace('.', '/')};" else clz
        name = desc.substring(a + 2, b)
        signature = desc.substring(b)
    }

    constructor(clz: String, n: String, s: String) {
        declaringClass = if (!clz.startsWith("L") && !clz.startsWith("["))
            "L${clz.replace('.', '/')};" else clz
        name = n
        signature = s
    }

//    constructor(clz: Class<*>, n: String, s: String) {
//        declaringClass = getTypeSig(clz)
//        name = n
//        signature = s
//    }

    override fun toString() = "$declaringClass->$name$signature"

    val descriptor: String
        get() = toString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        return toString() == other.toString()
    }

    override fun hashCode() = toString().hashCode()

    fun getMethodInstance(classLoader: ClassLoader): Method {
        try {
            var clz = classLoader.loadClass(
                declaringClass.substring(1, declaringClass.length - 1).replace('/', '.')
            )
            for (m in clz.declaredMethods) {
                if (m.name == name && getMethodTypeSig(m) == signature) return m
            }
            while (true) {
                clz = clz.superclass ?: break
                for (m in clz.declaredMethods) {
                    if (Modifier.isPrivate(m.modifiers) || Modifier.isStatic(m.modifiers)) continue
                    if (m.name == name && getMethodTypeSig(m) == signature) return m
                }
            }
            throw NoSuchMethodException("$declaringClass->$name$signature")
        } catch (e: ClassNotFoundException) {
            throw NoSuchMethodException("$declaringClass->$name$signature").initCause(e)
        }
    }

    fun getParameterTypes(): List<String> {
        val params = signature.substring(1, signature.indexOf(')'))
        return splitParameterTypes(params)
    }

    fun getReturnType(): String {
        val index = signature.indexOf(')')
        return signature.substring(index + 1)
    }

    companion object {
        fun getMethodTypeSig(method: Method) = buildString {
            append("(")
            method.parameterTypes.forEach { append(getTypeSig(it)) }
            append(")")
            append(getTypeSig(method.returnType))
        }

        fun getTypeSig(type: Class<*>): String {
            if (type.isPrimitive) return when (type) {
                Int::class.javaPrimitiveType -> "I"
                Void.TYPE -> "V"
                Boolean::class.javaPrimitiveType -> "Z"
                Char::class.javaPrimitiveType -> "C"
                Byte::class.javaPrimitiveType -> "B"
                Short::class.javaPrimitiveType -> "S"
                Float::class.javaPrimitiveType -> "F"
                Long::class.javaPrimitiveType -> "J"
                Double::class.javaPrimitiveType -> "D"
                else -> throw IllegalStateException("Type: ${type.name} is not a primitive type")
            }
            if (type.isArray) return "[${getTypeSig(type.componentType!!)}"
            return "L${type.name.replace('.', '/')};"
        }

        fun splitParameterTypes(s: String): List<String> {
            val list = mutableListOf<String>()
            var i = 0
            while (i < s.length) {
                when (val c = s[i]) {
                    'L' -> {
                        val j = s.indexOf(';', i)
                        list.add(s.substring(i, j + 1))
                        i = j + 1
                    }
                    '[' -> {
                        var j = i
                        while (j < s.length && s[j] == '[') j++
                        if (j < s.length && s[j] == 'L') j = s.indexOf(';', j)
                        list.add(s.substring(i, j + 1))
                        i = j + 1
                    }
                    else -> {
                        list.add(c.toString())
                        i++
                    }
                }
            }
            return list
        }
    }
}
