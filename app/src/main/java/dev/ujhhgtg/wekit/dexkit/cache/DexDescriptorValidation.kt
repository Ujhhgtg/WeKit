package dev.ujhhgtg.wekit.dexkit.cache

internal fun isValidDexClassDescriptor(value: String): Boolean =
    value.isNotEmpty() && value.split('.').all(::isValidJavaNamePart)

internal fun isValidDexFieldDescriptor(value: String): Boolean {
    val arrow = value.indexOf("->")
    val colon = value.indexOf(':', arrow + 2)
    if (arrow <= 0 || colon <= arrow + 2 || value.indexOf("->", arrow + 2) >= 0) return false
    if (!isValidObjectType(value.substring(0, arrow))) return false
    if (!isValidMemberName(value.substring(arrow + 2, colon))) return false
    return parseType(value, colon + 1, allowVoid = false) == value.length
}

internal fun isValidDexMethodDescriptor(value: String): Boolean =
    isValidExecutableDescriptor(value, constructor = false)

internal fun isValidDexConstructorDescriptor(value: String): Boolean =
    isValidExecutableDescriptor(value, constructor = true)

private fun isValidExecutableDescriptor(value: String, constructor: Boolean): Boolean {
    val arrow = value.indexOf("->")
    val open = value.indexOf('(', arrow + 2)
    val close = value.indexOf(')', open + 1)
    if (arrow <= 0 || open <= arrow + 2 || close < open || close != value.lastIndexOf(')')) return false
    if (!isValidObjectType(value.substring(0, arrow))) return false
    val name = value.substring(arrow + 2, open)
    if (constructor) {
        if (name != "<init>") return false
    } else if (!isValidMemberName(name) || name == "<init>" || name == "<clinit>") {
        return false
    }
    var index = open + 1
    while (index < close) {
        index = parseType(value, index, allowVoid = false) ?: return false
        if (index > close) return false
    }
    if (index != close) return false
    val returnEnd = parseType(value, close + 1, allowVoid = true) ?: return false
    if (returnEnd != value.length) return false
    return !constructor || value.substring(close + 1) == "V"
}

private fun parseType(value: String, start: Int, allowVoid: Boolean): Int? {
    if (start >= value.length) return null
    var index = start
    while (value.getOrNull(index) == '[') index++
    val arrayDepth = index - start
    if (index >= value.length) return null
    return when (value[index]) {
        'V' -> if (allowVoid && arrayDepth == 0) index + 1 else null
        'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> index + 1
        'L' -> {
            val end = value.indexOf(';', index + 1)
            if (end < 0 || !isValidInternalClassName(value.substring(index + 1, end))) null else end + 1
        }
        else -> null
    }
}

private fun isValidObjectType(value: String): Boolean =
    value.length >= 3 && value.first() == 'L' && value.last() == ';' &&
        isValidInternalClassName(value.substring(1, value.lastIndex))

private fun isValidInternalClassName(value: String): Boolean =
    value.isNotEmpty() && value.split('/').all(::isValidJavaNamePart)

private fun isValidJavaNamePart(value: String): Boolean =
    value.isNotEmpty() && (value.first().isLetter() || value.first() == '_' || value.first() == '$') &&
        value.drop(1).all { it.isLetterOrDigit() || it == '_' || it == '$' }

private fun isValidMemberName(value: String): Boolean =
    isValidJavaNamePart(value)
