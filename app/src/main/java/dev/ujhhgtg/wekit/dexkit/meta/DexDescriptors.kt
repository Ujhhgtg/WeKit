package dev.ujhhgtg.wekit.dexkit.meta

import dev.ujhhgtg.wekit.dexkit.sql.classDescriptor
import dev.ujhhgtg.wekit.dexkit.sql.javaType
import dev.ujhhgtg.wekit.utils.reflection.descriptorSplitParameterTypes

private val PRIMITIVE_JAVA_NAMES =
    mapOf(
        'V' to "void",
        'Z' to "boolean",
        'B' to "byte",
        'S' to "short",
        'C' to "char",
        'I' to "int",
        'J' to "long",
        'F' to "float",
        'D' to "double",
    )

/** Java names of the primitive types and void. */
internal val PRIMITIVE_JAVA_TYPE_NAMES: Set<String> = PRIMITIVE_JAVA_NAMES.values.toSet()

/**
 * Converts a DEX type descriptor to a Java type name.
 *
 * "I" -> "int", "[I" -> "int[]", "[[Ljava/lang/String;" -> "java.lang.String[][]", "Lcom/foo/Bar;"
 * -> "com.foo.Bar".
 */
fun descriptorToJavaName(descriptor: String): String {
    require(descriptor.isNotEmpty()) { "Type descriptor must not be blank" }
    var index = 0
    var dimensions = 0
    while (index < descriptor.length && descriptor[index] == '[') {
        dimensions++
        index++
    }
    require(index < descriptor.length) { "Invalid type descriptor: $descriptor" }
    val component = descriptor.substring(index)
    val base =
        when (val kind = component[0]) {
            'L' -> {
                require(component.length >= 3 && component.endsWith(';')) {
                    "Invalid class descriptor: $descriptor"
                }
                component.substring(1, component.length - 1).replace('/', '.')
            }

            in PRIMITIVE_JAVA_NAMES -> PRIMITIVE_JAVA_NAMES.getValue(kind)
            else -> throw IllegalArgumentException("Invalid type descriptor: $descriptor")
        }
    return base + "[]".repeat(dimensions)
}

/**
 * Converts a Java type name to a DEX type descriptor, reusing the same mapping as the SQL DSL:
 * "void" -> "V", "int[]" -> "[I", "java.lang.String[][]" -> "[[Ljava/lang/String;", "com.foo.Bar"
 * -> "Lcom/foo/Bar;", and an existing "L...;" descriptor passes through. An existing array
 * descriptor such as "[I" is kept as-is.
 */
fun javaNameToDescriptor(javaName: String): String {
    val name = javaName.trim()
    require(name.isNotEmpty()) { "Java type name must not be blank" }
    if (name.startsWith("[")) return name
    return javaType(name)
}

/**
 * Object-class descriptor for a class name/descriptor, or null when the name is not an object class
 * (primitive, array or malformed). Metadata lookups are class-document lookups, so array/primitive
 * names must resolve to null instead of a malformed descriptor.
 */
fun objectClassDescriptorOrNull(className: String): String? {
    val name = className.trim()
    if (name.isEmpty()) return null
    if (name.startsWith("[") || name.endsWith("[]")) return null
    if (name in PRIMITIVE_JAVA_TYPE_NAMES) return null
    if (name.startsWith("L") && name.endsWith(";")) {
        return if (name.contains('[') || name.contains(']')) null else name
    }
    if (name.any { it == ';' || it == '[' || it == ']' || it.isWhitespace() }) return null
    return "L" + name.replace('.', '/').trim('/') + ";"
}

/** Converts a dotted class name (or an existing "L...;" descriptor) to a DEX class descriptor. */
fun dottedClassNameToDescriptor(className: String): String = classDescriptor(className)

/** Parsed parts of one DEX method descriptor. */
data class MethodDescriptorParts(
    val declaringClassDescriptor: String,
    val name: String,
    val methodSign: String,
    val returnTypeDescriptor: String,
    val paramDescriptors: List<String>,
)

/** Splits a DEX method descriptor ("Lcom/foo/Bar;->name(I)V") into its canonical parts. */
fun parseMethodDescriptor(descriptor: String): MethodDescriptorParts {
    val arrow = descriptor.indexOf("->")
    require(arrow > 0) { "Invalid method descriptor: $descriptor" }
    val open = descriptor.indexOf('(', arrow)
    require(open > arrow + 2) { "Invalid method descriptor: $descriptor" }
    val close = descriptor.indexOf(')', open)
    require(close > open) { "Invalid method descriptor: $descriptor" }
    val declaringClass = descriptor.substring(0, arrow)
    require(declaringClass.startsWith("L") && declaringClass.endsWith(";")) {
        "Invalid method declaring class descriptor: $descriptor"
    }
    val name = descriptor.substring(arrow + 2, open)
    val returnType = descriptor.substring(close + 1)
    require(returnType.isNotEmpty()) { "Invalid method descriptor: $descriptor" }
    return MethodDescriptorParts(
        declaringClassDescriptor = declaringClass,
        name = name,
        methodSign = descriptor.substring(open),
        returnTypeDescriptor = returnType,
        paramDescriptors = descriptor.substring(open + 1, close).descriptorSplitParameterTypes(),
    )
}

/** Returns the prototype sign of a method descriptor, without the method name. */
fun methodDescriptorSign(descriptor: String): String = parseMethodDescriptor(descriptor).methodSign

/** Parameter type descriptors of a method sign ("(I)V" -> ["I"]). */
fun methodSignParamDescriptors(methodSign: String): List<String> {
    val open = methodSign.indexOf('(')
    val close = methodSign.indexOf(')', open + 1)
    require(open in 0..<close) { "Invalid method sign: $methodSign" }
    return methodSign.substring(open + 1, close).descriptorSplitParameterTypes()
}

/** Return type descriptor of a method sign ("(I)V" -> "V"). */
fun methodSignReturnDescriptor(methodSign: String): String {
    val close = methodSign.indexOf(')')
    require(close >= 0 && close + 1 < methodSign.length) { "Invalid method sign: $methodSign" }
    return methodSign.substring(close + 1)
}

/** Builds a full method descriptor from its declaring class descriptor, name and method sign. */
fun buildMethodDescriptor(
    declaringClassDescriptor: String,
    name: String,
    methodSign: String,
): String {
    require(declaringClassDescriptor.startsWith("L") && declaringClassDescriptor.endsWith(";")) {
        "Invalid method declaring class descriptor: $declaringClassDescriptor"
    }
    require(name.isNotEmpty()) { "Method name must not be blank" }
    require(methodSign.startsWith("(") && methodSign.indexOf(')') > 0) {
        "Invalid method sign: $methodSign"
    }
    return "$declaringClassDescriptor->$name$methodSign"
}
