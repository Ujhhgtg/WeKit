package dev.ujhhgtg.wekit.features

/**
 * Finds which Feature objects each Feature object's source references.
 *
 * KSP exposes declarations but not expression bodies, so this works on the source text: comments
 * and string literal text are blanked (string template expressions stay code), then identifiers
 * are resolved the way the Kotlin compiler would find a top-level object by simple name — explicit
 * import (including aliases), star import, or same package — plus fully qualified usages.
 *
 * References inside a feature object's own body belong to that feature. References outside every
 * feature body in the file (helper functions and classes) belong to every feature in that file.
 */
internal object FeatureReferenceScanner {

    data class FeatureSymbol(val qualifiedName: String, val simpleName: String, val packageName: String)

    /**
     * @param features every discovered feature, used to resolve references
     * @param fileFeatures the features declared in the file whose [source] is given
     * @return referenced qualified names per feature declared in the file (self excluded)
     */
    fun scan(
        source: String,
        packageName: String,
        fileFeatures: List<FeatureSymbol>,
        features: List<FeatureSymbol>,
    ): Map<String, Set<String>> {
        val code = blankCommentsAndStrings(source)
        val byQualifiedName = features.associateBy { it.qualifiedName }

        val visible = mutableMapOf<String, String>()
        features.filter { it.packageName == packageName }.forEach { visible[it.simpleName] = it.qualifiedName }
        IMPORT.findAll(code).forEach { match ->
            val path = match.groupValues[1]
            val alias = match.groupValues[2]
            if (path.endsWith(".*")) {
                val importedPackage = path.removeSuffix(".*")
                features.filter { it.packageName == importedPackage }.forEach { visible[it.simpleName] = it.qualifiedName }
            } else {
                byQualifiedName[path]?.let { visible[alias.ifEmpty { it.simpleName }] = it.qualifiedName }
            }
        }

        val body = code.replace(HEADER_LINE) { " ".repeat(it.value.length) }
        val bodyRanges = fileFeatures.associateWith { featureBodyRange(body, it.simpleName) }

        val references = fileFeatures.associateWith { mutableSetOf<String>() }
        fun attribute(offset: Int, qualifiedName: String) {
            val owner = bodyRanges.entries.firstOrNull { (_, range) -> range != null && offset in range }?.key
            if (owner != null) {
                references.getValue(owner) += qualifiedName
            } else {
                references.values.forEach { it += qualifiedName }
            }
        }

        IDENTIFIER.findAll(body).forEach { match ->
            val start = match.range.first
            if (start > 0 && body[start - 1] == '.') return@forEach
            if (DECLARATION_BEFORE.containsMatchIn(body.substring(maxOf(0, start - 16), start))) return@forEach
            visible[match.value]?.let { attribute(start, it) }
        }
        features.forEach { feature ->
            Regex("""(?<![\w.])${Regex.escape(feature.qualifiedName)}\b""").findAll(body).forEach {
                attribute(it.range.first, feature.qualifiedName)
            }
        }

        return references.map { (feature, names) -> feature.qualifiedName to (names - feature.qualifiedName) }.toMap()
    }

    private val IMPORT = Regex("""(?m)^\s*import\s+([\w.]+(?:\.\*)?)(?:\s+as\s+(\w+))?""")
    private val HEADER_LINE = Regex("""(?m)^\s*(?:import|package)\s+[^\n]*""")
    private val IDENTIFIER = Regex("""\b[A-Za-z_]\w*\b""")
    private val DECLARATION_BEFORE = Regex("""\b(?:object|class|interface)\s+$""")

    /** Offsets strictly inside the braces of `object <simpleName>`, or null for a body-less object. */
    private fun featureBodyRange(code: String, simpleName: String): IntRange? {
        val declaration = Regex("""\bobject\s+${Regex.escape(simpleName)}\b""").find(code) ?: return null
        var index = declaration.range.last + 1
        var parens = 0
        while (index < code.length) {
            when (code[index]) {
                '(' -> parens++
                ')' -> parens--
                '{' -> if (parens == 0) break
                '\n' -> if (parens == 0 && nextDeclarationStarts(code, index + 1)) return null
            }
            index++
        }
        if (index >= code.length) return null
        var depth = 0
        for (end in index until code.length) {
            when (code[end]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return (index + 1) until end
            }
        }
        return null
    }

    private val NEXT_DECLARATION = java.util.regex.Pattern.compile(
        """\s*(?:@|(?:private|internal|public|abstract|sealed|data|open|enum)\b|(?:object|class|interface|fun|val|var)\b)""",
    )

    private fun nextDeclarationStarts(code: String, from: Int): Boolean =
        NEXT_DECLARATION.matcher(code).region(from, code.length).lookingAt()

    /**
     * Replaces comments and string literal text with spaces, preserving offsets and newlines.
     * `${...}` template expressions inside strings are kept because they are code.
     */
    fun blankCommentsAndStrings(source: String): String {
        val out = StringBuilder(source)
        // Open `${` templates: (raw string?, brace depth before the template's `{`).
        val templateStack = ArrayDeque<Pair<Boolean, Int>>()
        var braceDepth = 0
        var i = 0
        fun blank(from: Int, to: Int) {
            for (k in from until to) if (out[k] != '\n') out[k] = ' '
        }

        /** Scans string text from [start]; returns the index after the closing quote or the `${`. */
        fun scanString(start: Int, raw: Boolean): Int {
            var j = start
            while (j < source.length) {
                if (!raw && source[j] == '\\') {
                    j += 2
                    continue
                }
                if (source.startsWith("\${", j)) {
                    blank(start, j)
                    templateStack.addLast(raw to braceDepth)
                    braceDepth++
                    return j + 2
                }
                if (raw && source.startsWith("\"\"\"", j)) {
                    var end = j + 3
                    while (end < source.length && source[end] == '"') end++
                    blank(start, end - 3)
                    return end
                }
                if (!raw && source[j] == '"') {
                    blank(start, j)
                    return j + 1
                }
                if (!raw && source[j] == '\n') {
                    blank(start, j)
                    return j
                }
                j++
            }
            blank(start, source.length)
            return source.length
        }

        while (i < source.length) {
            val c = source[i]
            when {
                source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i).let { if (it == -1) source.length else it }
                    blank(i, end)
                    i = end
                }
                source.startsWith("/*", i) -> {
                    var depth = 0
                    var j = i
                    while (j < source.length) {
                        if (source.startsWith("/*", j)) {
                            depth++
                            j += 2
                        } else if (source.startsWith("*/", j)) {
                            depth--
                            j += 2
                            if (depth == 0) break
                        } else {
                            j++
                        }
                    }
                    blank(i, j)
                    i = j
                }
                source.startsWith("\"\"\"", i) -> i = scanString(i + 3, raw = true)
                c == '"' -> i = scanString(i + 1, raw = false)
                c == '\'' -> {
                    var j = i + 1
                    while (j < source.length && source[j] != '\'' && source[j] != '\n') {
                        if (source[j] == '\\') j++
                        j++
                    }
                    blank(i + 1, minOf(j, source.length))
                    i = j + 1
                }
                c == '{' -> {
                    braceDepth++
                    i++
                }
                c == '}' -> {
                    braceDepth--
                    val template = templateStack.lastOrNull()
                    if (template != null && template.second == braceDepth) {
                        templateStack.removeLast()
                        i = scanString(i + 1, template.first)
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
        return out.toString()
    }
}
