package dev.ujhhgtg.wekit.dex.matcher

import dev.ujhhgtg.wekit.dex.meta.descriptorToJavaName
import dev.ujhhgtg.wekit.dex.meta.javaNameToDescriptor

/**
 * Shared name <-> descriptor conversion and validation for the matcher.
 *
 * DEX descriptors ("[B", "[Lx/Y;", "[[I", "Lx/Y;", "I") pass through / round-trip; Java type names
 * ("int", "byte[]", "com.x.Y[][]", "com.x.Y") convert to their DEX form. Dotted->slashed conversion
 * is NEVER applied to an array/primitive name, so malformed descriptors like "L[a/b[];" cannot be
 * produced. Every candidate-SQL emission, evaluator type/name comparison and raw-document prefilter
 * must go through these helpers.
 */

/** Primitive/void Java type names (no dataset class metadata). */
internal val PRIMITIVE_JAVA_TYPE_NAMES: Set<String> =
    setOf(
        "void",
        "boolean",
        "byte",
        "short",
        "char",
        "int",
        "long",
        "float",
        "double",
    )

private val PRIMITIVE_DESCRIPTOR_NAMES: Map<Char, String> =
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

private val PRIMITIVE_NAME_DESCRIPTORS: Map<String, String> =
    PRIMITIVE_DESCRIPTOR_NAMES.entries.associate { (descriptor, name) ->
        name to descriptor.toString()
    }

/** True for a DEX array type descriptor ("[B", "[Lx/Y;", "[[I"). */
internal fun isDexArrayDescriptor(value: String): Boolean = value.startsWith("[")

/** True for a DEX single-letter primitive/void descriptor ("V","I","Z", ...). */
internal fun isDexPrimitiveDescriptor(value: String): Boolean =
    value.length == 1 && value[0] in PRIMITIVE_DESCRIPTOR_NAMES

/**
 * True when [value] is a syntactically valid DEX type descriptor: an array descriptor, a primitive
 * descriptor, or an object descriptor "Lx/Y;". Never used to convert; only to decide pass-through.
 */
internal fun isDexTypeDescriptor(value: String): Boolean {
    if (value.isEmpty()) return false
    if (value.startsWith("[")) {
        // Arrays: strip every dimension, then the element must be a primitive or a well-formed
        // object descriptor. Rejecting the body explicitly keeps malformed forms such as
        // "[Lcom/x/c[];" out of candidate SQL.
        var element = value.substring(1)
        while (element.startsWith("[")) element = element.substring(1)
        return isDexTypeDescriptor(element)
    }
    if (isDexPrimitiveDescriptor(value)) return true
    if (!value.startsWith("L") || !value.endsWith(";") || value.length <= 2) return false
    if (value.contains('[') || value.contains(']')) return false
    return descriptorToJavaNameOrNull(value) != null
}

private fun descriptorToJavaNameOrNull(descriptor: String): String? =
    try {
        descriptorToJavaName(descriptor)
    } catch (_: IllegalArgumentException) {
        null
    }

/** True for primitive, void or array type names in Java-name OR DEX-descriptor form. */
internal fun isPrimitiveOrArrayTypeName(name: String): Boolean {
    if (name.isEmpty()) return false
    if (isDexArrayDescriptor(name)) return true
    if (isDexPrimitiveDescriptor(name)) return true
    if (name in PRIMITIVE_JAVA_TYPE_NAMES) return true
    return name.endsWith("[]")
}

/**
 * Normalizes a type name to a Java type name for name/type comparisons: "[B" -> "byte[]", "[Lx/Y;"
 * -> "x.Y[]", "int" -> "int", "com.x.Y[][]" -> unchanged. Invalid input is returned trimmed
 * unchanged (never throws).
 */
internal fun javaTypeNameOf(name: String): String {
    val value = name.trim()
    if (value.isEmpty()) return value
    if (isDexTypeDescriptor(value)) {
        return descriptorToJavaNameOrNull(value) ?: value
    }
    return value
}

/**
 * Safe type-name -> DEX type descriptor conversion used by candidate SQL emission: primitives, Java
 * arrays, dotted object names, and existing DEX type descriptors. Returns null for malformed input
 * instead of producing a malformed descriptor.
 */
internal fun typeDescriptorOrNull(name: String): String? {
    val value = name.trim()
    if (value.isEmpty()) return null
    if (value.startsWith("[")) return if (isDexTypeDescriptor(value)) value else null
    if (isDexTypeDescriptor(value)) return value
    if (isPrimitiveOrArrayTypeName(value)) {
        // Java array/primitive names only (descriptor forms already returned above).
        return try {
            javaNameToDescriptor(value)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    if (value.contains(';') || value.contains('[') || value.contains(']')) return null
    return "L" + value.replace('.', '/').trim('/') + ";"
}

/**
 * Safe class-name -> DEX class descriptor conversion. Returns null for primitives, arrays and
 * malformed forms (those have no dataset class metadata and must never be used as class names).
 * Accepts dotted Java names and existing "Lx/Y;" descriptors.
 */
internal fun classDescriptorOrNull(name: String): String? {
    val value = name.trim()
    if (value.isEmpty()) return null
    if (value.startsWith("[")) return null
    if (value.length == 1 && isDexPrimitiveDescriptor(value)) return null
    if (value in PRIMITIVE_JAVA_TYPE_NAMES) return null
    if (value.endsWith("[]")) return null
    if (value.startsWith("L") && value.endsWith(";")) {
        return if (isDexTypeDescriptor(value)) value else null
    }
    if (value.contains('/') || value.contains(';') || value.contains('[') || value.contains(']')) {
        return null
    }
    return "L" + value.replace('.', '/').trim('/') + ";"
}

/** Dotted Java class name for an object class name/descriptor; null for primitives/arrays. */
internal fun dottedClassNameOrNull(name: String): String? {
    val descriptor = classDescriptorOrNull(name) ?: return null
    return descriptorToJavaNameOrNull(descriptor)
}

/** True when [descriptor] is a usable method ref/full descriptor ("Lx/Y;->name(...)ret"). */
internal fun isRealMethodDescriptor(descriptor: String): Boolean {
    val arrow = descriptor.indexOf("->")
    if (arrow <= 0) return false
    val declaring = descriptor.substring(0, arrow)
    if (!declaring.startsWith("L") || !declaring.endsWith(";")) return false
    if (descriptorToJavaNameOrNull(declaring) == null) return false
    val open = descriptor.indexOf('(', arrow + 2)
    if (open <= arrow + 2) return false
    val close = descriptor.indexOf(')', open)
    if (close <= open || close + 1 >= descriptor.length) return false
    return descriptorToJavaNameOrNull(descriptor.substring(close + 1)) != null
}

/** True when [descriptor] is a usable field ref/full descriptor ("Lx/Y;->name:type"). */
internal fun isRealFieldDescriptor(descriptor: String): Boolean {
    val arrow = descriptor.indexOf("->")
    if (arrow <= 0) return false
    val declaring = descriptor.substring(0, arrow)
    if (!declaring.startsWith("L") || !declaring.endsWith(";")) return false
    if (descriptorToJavaNameOrNull(declaring) == null) return false
    val colon = descriptor.indexOf(':', arrow + 2)
    if (colon <= arrow + 2 || colon + 1 >= descriptor.length) return false
    if (descriptor.substring(arrow + 2, colon).isEmpty()) return false
    return descriptorToJavaNameOrNull(descriptor.substring(colon + 1)) != null
}

/** Name-match comparison where both the matcher value and actual value may be descriptors. */
internal fun nameSpecMatchesJavaName(spec: NameSpec, actualJavaName: String): Boolean {
    val expected = javaTypeNameOf(spec.value)
    val actual = javaTypeNameOf(actualJavaName)
    return when (spec.match) {
        NameMatch.Equals -> actual == expected
        NameMatch.StartsWith -> actual.startsWith(expected)
        NameMatch.Contains -> actual.contains(expected)
        NameMatch.EndsWith -> actual.endsWith(expected)
    }
}

/** TypeSpec comparison where both sides may be Java names or DEX type descriptors. */
internal fun typeSpecMatchesJavaName(spec: TypeSpec, actualJavaName: String): Boolean {
    val expected = javaTypeNameOf(spec.value)
    val actual = javaTypeNameOf(actualJavaName)
    return when (spec.match) {
        NameMatch.Equals -> actual == expected
        NameMatch.StartsWith -> actual.startsWith(expected)
        NameMatch.Contains -> actual.contains(expected)
        NameMatch.EndsWith -> actual.endsWith(expected)
    }
}
