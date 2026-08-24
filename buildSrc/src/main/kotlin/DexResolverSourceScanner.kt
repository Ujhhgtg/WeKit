import java.io.File

internal enum class ResolveBlockKind { CUSTOM, INLINE_CLASS, INLINE_FIELD, INLINE_METHOD, INLINE_CONSTRUCTOR }

internal data class ResolveSourceBlock(
    val kind: ResolveBlockKind,
    val startLine: Int,
    val text: String,
)

internal data class DexProducerSource(
    val stableId: String,
    val propertyName: String?,
    val kind: ResolveBlockKind,
    val startLine: Int,
    val fingerprintSource: String,
    val usesOwnerSafetyFingerprint: Boolean,
)

internal data class DexResolverSource(
    val file: File,
    val qualifiedClassName: String,
    val producers: List<DexProducerSource>,
    val ownerSafetySource: String,
    val customOutputPropertyNames: Set<String>,
    val blocks: List<ResolveSourceBlock>,
    internal val sourceLinesByBlock: Map<ResolveSourceBlock, IntArray> = emptyMap(),
)

internal data class DesktopResolverViolation(
    val source: DexResolverSource,
    val block: ResolveSourceBlock,
    val line: Int,
    val expression: String,
) {
    fun render(): String = "${source.file.path}:$line: $expression is unavailable during desktop Dex resolution"
}

internal fun scanDexResolverSource(file: File): DexResolverSource? =
    scanDexResolverSource(file.readText(), file)

internal fun scanDexResolverSource(path: String, sourceText: String): DexResolverSource? =
    scanDexResolverSource(sourceText, File(path))

private fun scanDexResolverSource(sourceText: String, file: File): DexResolverSource? {
    val clean = stripCommentsPreservingStrings(sourceText)
    val packageName = clean.findCode(Regex("""package\s+([\w.]+)"""))?.groupValues?.get(1)
    val classRegex = Regex("""\b(?:class|object)\s+(\w+)\b""")
    val declarations = clean.findAllCode(classRegex)
    val resolveDexDeclaration = declarations.withIndex().firstNotNullOfOrNull { (index, match) ->
        val braceIndex = clean.indexOfCode('{', match.range.first)
        val closingBraceIndex = clean.indexOfCode('}', match.range.first)
        val nextDeclarationIndex = declarations.getOrNull(index + 1)?.range?.first ?: clean.length
        if (
            braceIndex == -1 ||
            braceIndex >= nextDeclarationIndex ||
            (closingBraceIndex != -1 && braceIndex >= closingBraceIndex)
        ) {
            return@firstNotNullOfOrNull null
        }

        val signature = clean.substring(match.range.first, braceIndex)
        if (signature.contains(":") && Regex("""\bIResolveDex\b""").containsMatchIn(signature)) match else null
    } ?: return null

    val className = resolveDexDeclaration.groupValues[1]
    val fullClassName = if (packageName != null) "$packageName.$className" else className
    val classBodyStart = clean.indexOfCode('{', resolveDexDeclaration.range.last)
    val classBodyEnd = if (classBodyStart == -1) -1 else clean.findBlockEnd(classBodyStart)
    val classBodyDepth = if (classBodyStart == -1) -1 else clean.braceDepthAt(classBodyStart + 1)
    fun isDirectMember(match: MatchResult): Boolean =
        match.range.first > classBodyStart &&
            match.range.first < classBodyEnd &&
            clean.braceDepthAt(match.range.first) == classBodyDepth
    fun isDirectMemberAt(index: Int): Boolean =
        index > classBodyStart && index < classBodyEnd && clean.braceDepthAt(index) == classBodyDepth

    val ownerSafetySource = clean.substring(resolveDexDeclaration.range.first, classBodyEnd + 1).trim()

    val blocks = mutableListOf<ResolveSourceBlock>()
    val sourceLinesByBlock = mutableMapOf<ResolveSourceBlock, IntArray>()
    fun addBlock(kind: ResolveBlockKind, start: Int, end: Int) {
        val block = ResolveSourceBlock(kind, clean.sourceLineAt(start), clean.substring(start, end + 1))
        blocks += block
        sourceLinesByBlock[block] = IntArray(end - start + 1) { clean.sourceLineAt(start + it) }
    }

    data class RawProducer(
        val stableId: String,
        val propertyName: String?,
        val kind: ResolveBlockKind,
        val startLine: Int,
        val source: String,
    )

    val rawProducers = mutableListOf<RawProducer>()
    val delegatePropertyNames = mutableListOf<String>()
    val customOutputPropertyNames = linkedSetOf<String>()
    val resolveDexMatch = clean.findAllCode(Regex("""override\s+fun\s+resolveDex\s*\(""")).firstOrNull(::isDirectMember)
    if (resolveDexMatch != null) {
        val start = clean.indexOfCode('{', resolveDexMatch.range.last)
        val end = if (start == -1) -1 else clean.findBlockEnd(start)
        if (end != -1) {
            addBlock(ResolveBlockKind.CUSTOM, start, end)
            rawProducers += RawProducer(
                stableId = "$fullClassName#resolveDex",
                propertyName = null,
                kind = ResolveBlockKind.CUSTOM,
                startLine = clean.sourceLineAt(resolveDexMatch.range.first),
                source = clean.substring(resolveDexMatch.range.first, end + 1),
            )
        }
    }

    val separatorRegex = Regex("""\b(val|fun|private|public|internal|class|object|override)\b""")
    clean.findAllCode(DEX_DELEGATE_DECLARATION).filter(::isDirectMember).forEach { match ->
        val propertyName = match.groupValues[1]
        delegatePropertyNames += propertyName
        val kind = match.groupValues[2].toResolveBlockKind()
        val factoryStart = match.range.first + match.value.lastIndexOf("dex${match.groupValues[2]}")
        val factoryNameEnd = factoryStart + "dex${match.groupValues[2]}".length
        val firstCodeAfterFactory = clean.nextCodeIndex(factoryNameEnd)
        val factoryEnd = if (firstCodeAfterFactory != -1 && clean.text[firstCodeAfterFactory] == '(') {
            clean.findDelimitedEnd(firstCodeAfterFactory, '(', ')')
        } else {
            factoryNameEnd - 1
        }
        require(factoryEnd != -1) { "Unclosed dex delegate factory call in $fullClassName.$propertyName" }

        val nextOpenBrace = clean.indexOfCode('{', factoryEnd + 1)
        val hasInlineBlock = nextOpenBrace != -1 &&
            nextOpenBrace < classBodyEnd &&
            !clean.containsCodeMatch(separatorRegex, factoryEnd + 1, nextOpenBrace)
        val declarationEnd = if (hasInlineBlock) clean.findBlockEnd(nextOpenBrace) else factoryEnd
        require(declarationEnd != -1) { "Unclosed inline dex block in $fullClassName.$propertyName" }

        if (hasInlineBlock) {
            addBlock(kind, nextOpenBrace, declarationEnd)
            rawProducers += RawProducer(
                stableId = "$fullClassName#$propertyName",
                propertyName = propertyName,
                kind = kind,
                startLine = clean.sourceLineAt(match.range.first),
                source = clean.substring(match.range.first, declarationEnd + 1),
            )
        } else {
            customOutputPropertyNames += propertyName
        }
    }

    val duplicatePropertyNames = delegatePropertyNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    require(duplicatePropertyNames.isEmpty()) {
        "Class $fullClassName declares duplicate Dex delegate property names: ${duplicatePropertyNames.sorted()}"
    }

    if (customOutputPropertyNames.isNotEmpty() && rawProducers.none { it.kind == ResolveBlockKind.CUSTOM }) {
        error(
            "Class $fullClassName declares non-inline Dex outputs " +
                "${customOutputPropertyNames.sorted()} but has no resolveDex() body."
        )
    }

    if (blocks.isEmpty()) {
        error("Class $fullClassName implements IResolveDex but has neither a resolveDex() body nor any inline dex blocks.")
    }

    val helperFunctions = scanDirectHelperFunctions(clean, classBodyStart, classBodyEnd, classBodyDepth)
    val memberPropertyNames = clean.findAllCode(MEMBER_PROPERTY_DECLARATION)
        .filter { isDirectMemberAt(it.range.first) }
        .map { it.groupValues[1] }
        .toSet()
    val helperPropertyNames = memberPropertyNames - delegatePropertyNames.toSet()
    val producers = rawProducers.map { raw ->
        val closure = buildHelperClosure(raw.source, helperFunctions, helperPropertyNames)
        val usesOwnerSafetyFingerprint = !closure.isProven
        DexProducerSource(
            stableId = raw.stableId,
            propertyName = raw.propertyName,
            kind = raw.kind,
            startLine = raw.startLine,
            fingerprintSource = if (usesOwnerSafetyFingerprint) {
                ownerSafetySource
            } else {
                buildString {
                    append(raw.source.trim())
                    closure.helperSources.forEach {
                        append("\n")
                        append(it.trim())
                    }
                }
            },
            usesOwnerSafetyFingerprint = usesOwnerSafetyFingerprint,
        )
    }.sortedBy { it.startLine }

    return DexResolverSource(
        file = file,
        qualifiedClassName = fullClassName,
        producers = producers,
        ownerSafetySource = ownerSafetySource,
        customOutputPropertyNames = customOutputPropertyNames,
        blocks = blocks.sortedBy { it.startLine },
        sourceLinesByBlock = sourceLinesByBlock,
    )
}

private data class HelperFunctionSource(
    val name: String,
    val source: String,
    val bodySource: String,
    val isComplete: Boolean,
)

private data class HelperClosure(
    val isProven: Boolean,
    val helperSources: List<String>,
)

private fun scanDirectHelperFunctions(
    clean: ScannedSource,
    classBodyStart: Int,
    classBodyEnd: Int,
    classBodyDepth: Int,
): Map<String, List<HelperFunctionSource>> =
    clean.findAllCode(MEMBER_FUNCTION_DECLARATION)
        .filter { match ->
            match.range.first > classBodyStart &&
                match.range.first < classBodyEnd &&
                clean.braceDepthAt(match.range.first) == classBodyDepth &&
                match.groupValues[1] != "resolveDex"
        }
        .map { match ->
            val openParenthesis = clean.indexOfCode('(', match.range.first)
            val closeParenthesis = clean.findDelimitedEnd(openParenthesis, '(', ')')
            if (closeParenthesis == -1) {
                return@map HelperFunctionSource(match.groupValues[1], "", "", isComplete = false)
            }
            val bodyStart = clean.indexOfCode('{', closeParenthesis + 1)
            if (
                bodyStart == -1 ||
                bodyStart >= classBodyEnd ||
                clean.containsCodeMatch(MEMBER_SEPARATOR, closeParenthesis + 1, bodyStart)
            ) {
                return@map HelperFunctionSource(match.groupValues[1], "", "", isComplete = false)
            }
            val bodyEnd = clean.findBlockEnd(bodyStart)
            if (bodyEnd == -1 || bodyEnd > classBodyEnd) {
                return@map HelperFunctionSource(match.groupValues[1], "", "", isComplete = false)
            }
            HelperFunctionSource(
                name = match.groupValues[1],
                source = clean.substring(match.range.first, bodyEnd + 1),
                bodySource = clean.substring(bodyStart, bodyEnd + 1),
                isComplete = true,
            )
        }
        .groupBy { it.name }

private fun buildHelperClosure(
    producerSource: String,
    helperFunctions: Map<String, List<HelperFunctionSource>>,
    helperPropertyNames: Set<String>,
): HelperClosure {
    val included = linkedMapOf<String, HelperFunctionSource>()
    val visiting = linkedSetOf<String>()
    var proven = true

    fun visit(source: String) {
        if (stripCommentsPreservingStrings(source).findCode(FUNCTION_REFERENCE) != null) {
            proven = false
        }

        helperPropertyNames.forEach { propertyName ->
            if (stripCommentsPreservingStrings(source).containsCodeIdentifier(propertyName)) {
                proven = false
            }
        }

        helperFunctions.forEach { (name, candidates) ->
            if (!stripCommentsPreservingStrings(source).containsDirectCodeCall(name)) return@forEach
            if (candidates.size != 1 || name in visiting || !candidates.single().isComplete) {
                proven = false
                return@forEach
            }
            if (name in included) return@forEach

            val helper = candidates.single()
            visiting += name
            included[name] = helper
            visit(helper.bodySource)
            visiting -= name
        }
    }

    visit(producerSource)
    return HelperClosure(proven, included.values.map { it.source })
}

internal fun findDesktopIncompatibleAccesses(source: DexResolverSource): List<DesktopResolverViolation> =
    source.blocks.flatMap { block ->
        val blockSource = stripCommentsPreservingStrings(block.text)
        (blockSource.findAllCode(LIVE_HOST_ACCESS) + blockSource.findAllCode(HOST_VERSION_ACCESS))
            .sortedBy { it.range.first }
            .map { match ->
                DesktopResolverViolation(
                    source = source,
                    block = block,
                    line = source.sourceLinesByBlock[block]?.get(match.range.first)
                        ?: block.startLine + block.text.take(match.range.first).count { it == '\n' },
                    expression = match.value,
                )
            }
    }

private fun String.toResolveBlockKind(): ResolveBlockKind = when (this) {
    "Class" -> ResolveBlockKind.INLINE_CLASS
    "Field" -> ResolveBlockKind.INLINE_FIELD
    "Method" -> ResolveBlockKind.INLINE_METHOD
    "Constructor" -> ResolveBlockKind.INLINE_CONSTRUCTOR
    else -> error("Unsupported dex resolver delegate kind: $this")
}

private val DEX_DELEGATE_DECLARATION =
    Regex("""\b(?:val|var)\s+(\w+)(?:\s*:[^=\n]+)?\s+by\s+dex(Class|Field|Method|Constructor)\b""")
private val MEMBER_PROPERTY_DECLARATION = Regex("""\b(?:val|var)\s+(\w+)\b""")
private val MEMBER_FUNCTION_DECLARATION = Regex("""\bfun\s+(\w+)\s*\(""")
private val MEMBER_SEPARATOR = Regex("""\b(?:val|var|fun|class|object|override)\b""")
private val FUNCTION_REFERENCE = Regex("""::\s*\w+""")
private val LIVE_HOST_ACCESS = Regex("""\b(?:class|method|field|ctor)[A-Za-z0-9_]*\.(clazz|method|field|constructor)\b""")
private val HOST_VERSION_ACCESS = Regex("""\bHostInfo\.(versionCode|versionName|isHostGooglePlay)\b""")

private class ScannedSource(
    val text: String,
    private val codeMask: BooleanArray,
    private val sourceIndices: IntArray,
    private val sourceLineNumbers: IntArray,
) {
    val length: Int get() = text.length

    fun substring(startIndex: Int, endIndex: Int): String = text.substring(startIndex, endIndex)

    fun indexOfCode(char: Char, startIndex: Int): Int {
        for (i in startIndex.coerceAtLeast(0) until text.length) {
            if (text[i] == char && codeMask[i]) return i
        }
        return -1
    }

    fun nextCodeIndex(startIndex: Int): Int {
        for (i in startIndex.coerceAtLeast(0) until text.length) {
            if (codeMask[i] && !text[i].isWhitespace()) return i
        }
        return -1
    }

    fun findDelimitedEnd(openIndex: Int, open: Char, close: Char): Int {
        if (openIndex !in text.indices || text[openIndex] != open || !codeMask[openIndex]) return -1
        var depth = 0
        for (i in openIndex until text.length) {
            if (!codeMask[i]) continue
            when (text[i]) {
                open -> depth++
                close -> if (--depth == 0) return i
            }
        }
        return -1
    }

    fun findBlockEnd(openBraceIndex: Int): Int {
        var depth = 0
        for (i in openBraceIndex until text.length) {
            if (!codeMask[i]) continue
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    fun braceDepthAt(index: Int): Int {
        var depth = 0
        for (i in 0 until index.coerceIn(0, text.length)) {
            if (!codeMask[i]) continue
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depth
    }

    fun sourceLineAt(index: Int): Int = sourceLineNumbers[sourceIndices[index]]

    fun findCode(regex: Regex): MatchResult? = regex.findAll(text).firstOrNull { codeMask[it.range.first] }
    fun findAllCode(regex: Regex): List<MatchResult> = regex.findAll(text).filter { codeMask[it.range.first] }.toList()
    fun containsCodeMatch(regex: Regex, startIndex: Int, endIndex: Int): Boolean =
        regex.findAll(text, startIndex.coerceIn(0, text.length))
            .takeWhile { it.range.first < endIndex }
            .any { codeMask[it.range.first] }

    fun containsCodeIdentifier(name: String): Boolean =
        findAllCode(Regex("""\b${Regex.escape(name)}\b""")).isNotEmpty()

    fun containsDirectCodeCall(name: String): Boolean =
        findAllCode(Regex("""(?<![.\w])${Regex.escape(name)}\s*\(""")).isNotEmpty() ||
            findAllCode(Regex("""\bthis\s*\.\s*${Regex.escape(name)}\s*\(""")).isNotEmpty()
}

private sealed class LexContext {
    class Code(val isTemplate: Boolean) : LexContext() {
        var braceDepth = 0
    }

    object NormalString : LexContext()
    object RawString : LexContext()
    object CharLiteral : LexContext()
}

private fun stripCommentsPreservingStrings(source: String): ScannedSource {
    val text = StringBuilder(source.length)
    val codeMask = BooleanArray(source.length)
    val sourceIndices = IntArray(source.length)
    val sourceLineNumbers = IntArray(source.length)
    var sourceLine = 1
    source.forEachIndexed { index, char ->
        sourceLineNumbers[index] = sourceLine
        if (char == '\n') sourceLine++
    }

    fun emit(char: Char, isCode: Boolean, sourceIndex: Int) {
        codeMask[text.length] = isCode
        sourceIndices[text.length] = sourceIndex
        text.append(char)
    }

    val stack = mutableListOf<LexContext>(LexContext.Code(isTemplate = false))
    fun pop() = stack.removeAt(stack.size - 1)
    var i = 0
    while (i < source.length) {
        val char = source[i]
        when (val context = stack.last()) {
            is LexContext.Code -> when {
                source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') i++
                source.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < source.length) {
                        if (source.startsWith("/*", i)) {
                            depth++
                            i += 2
                        } else if (source.startsWith("*/", i)) {
                            depth--
                            i += 2
                            if (depth == 0) break
                        } else {
                            i++
                        }
                    }
                }

                source.startsWith("\"\"\"", i) -> {
                    repeat(3) { emit(source[i + it], false, i + it) }
                    i += 3
                    stack.add(LexContext.RawString)
                }

                char == '"' -> {
                    emit(char, false, i)
                    i++
                    stack.add(LexContext.NormalString)
                }

                char == '\'' -> {
                    emit(char, false, i)
                    i++
                    stack.add(LexContext.CharLiteral)
                }

                char == '{' -> {
                    context.braceDepth++
                    emit(char, true, i)
                    i++
                }

                char == '}' -> {
                    if (context.isTemplate && context.braceDepth == 0) {
                        pop()
                        emit(char, false, i)
                    } else {
                        context.braceDepth--
                        emit(char, true, i)
                    }
                    i++
                }

                else -> {
                    emit(char, true, i)
                    i++
                }
            }

            LexContext.NormalString -> when {
                char == '\\' && i + 1 < source.length -> {
                    emit(char, false, i)
                    emit(source[i + 1], false, i + 1)
                    i += 2
                }

                char == '"' -> {
                    emit(char, false, i)
                    i++
                    pop()
                }

                char == '$' && i + 1 < source.length && source[i + 1] == '{' -> {
                    emit(char, false, i)
                    emit('{', false, i + 1)
                    i += 2
                    stack.add(LexContext.Code(isTemplate = true))
                }

                char == '\n' -> {
                    emit(char, false, i)
                    i++
                    pop()
                }

                else -> {
                    emit(char, false, i)
                    i++
                }
            }

            LexContext.RawString -> when {
                source.startsWith("\"\"\"", i) -> {
                    var run = 0
                    while (i + run < source.length && source[i + run] == '"') run++
                    repeat(run) { emit(source[i + it], false, i + it) }
                    i += run
                    pop()
                }

                char == '$' && i + 1 < source.length && source[i + 1] == '{' -> {
                    emit(char, false, i)
                    emit('{', false, i + 1)
                    i += 2
                    stack.add(LexContext.Code(isTemplate = true))
                }

                else -> {
                    emit(char, false, i)
                    i++
                }
            }

            LexContext.CharLiteral -> when {
                char == '\\' && i + 1 < source.length -> {
                    emit(char, false, i)
                    emit(source[i + 1], false, i + 1)
                    i += 2
                }

                char == '\'' || char == '\n' -> {
                    emit(char, false, i)
                    i++
                    pop()
                }

                else -> {
                    emit(char, false, i)
                    i++
                }
            }
        }
    }
    return ScannedSource(
        text.toString(),
        codeMask.copyOf(text.length),
        sourceIndices.copyOf(text.length),
        sourceLineNumbers,
    )
}
