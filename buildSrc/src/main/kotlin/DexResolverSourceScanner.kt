import java.io.File

internal enum class ResolveBlockKind { CUSTOM, INLINE_CLASS, INLINE_FIELD, INLINE_METHOD, INLINE_CONSTRUCTOR }

internal data class ResolveSourceBlock(
    val kind: ResolveBlockKind,
    val startLine: Int,
    val text: String,
)

internal data class DexResolverSource(
    val file: File,
    val qualifiedClassName: String,
    /**
     * 从类体直接成员提取的 `technicalId` 字符串字面量；缺失或非字面量时为 null，
     * 由 [GenerateMethodHashesTask] 负责校验并大声失败。
     */
    val technicalId: String?,
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

    val blocks = mutableListOf<ResolveSourceBlock>()
    val sourceLinesByBlock = mutableMapOf<ResolveSourceBlock, IntArray>()
    fun addBlock(kind: ResolveBlockKind, start: Int, end: Int) {
        val block = ResolveSourceBlock(kind, clean.sourceLineAt(start), clean.substring(start, end + 1))
        blocks += block
        sourceLinesByBlock[block] = IntArray(end - start + 1) { clean.sourceLineAt(start + it) }
    }

    val resolveDexMatch = clean.findAllCode(Regex("""override\s+fun\s+resolveDex\s*\(""")).firstOrNull(::isDirectMember)
    if (resolveDexMatch != null) {
        val start = clean.indexOfCode('{', resolveDexMatch.range.last)
        val end = if (start == -1) -1 else clean.findBlockEnd(start)
        if (end != -1) {
            addBlock(ResolveBlockKind.CUSTOM, start, end)
        }
    }

    val technicalId = clean.findAllCode(TECHNICAL_ID)
        .firstOrNull(::isDirectMember)
        ?.groupValues?.get(1)
        ?.unescapeKotlinLiteral()

    val separatorRegex = Regex("""\b(val|fun|private|public|internal|class|object|override)\b""")
    clean.findAllCode(INLINE_DELEGATE).filter(::isDirectMember).forEach { match ->
        val startScan = match.range.last + 1
        val nextOpenBrace = clean.indexOfCode('{', startScan)
        if (nextOpenBrace != -1 && !clean.containsCodeMatch(separatorRegex, startScan, nextOpenBrace)) {
            val end = clean.findBlockEnd(nextOpenBrace)
            if (end != -1 && end < classBodyEnd) {
                addBlock(match.groupValues[1].toResolveBlockKind(), nextOpenBrace, end)
            }
        }
    }

    if (blocks.isEmpty()) {
        error("Class $fullClassName implements IResolveDex but has neither a resolveDex() body nor any inline dex blocks.")
    }

    return DexResolverSource(file, fullClassName, technicalId, blocks.sortedBy { it.startLine }, sourceLinesByBlock)
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

private val INLINE_DELEGATE = Regex("""\bby\s+dex(Class|Field|Method|Constructor)\b""")
private val LIVE_HOST_ACCESS = Regex("""\b(?:class|method|field|ctor)[A-Za-z0-9_]*\.(clazz|method|field|constructor)\b""")
private val HOST_VERSION_ACCESS = Regex("""\bHostInfo\.(versionCode|versionName|isHostGooglePlay)\b""")
private val TECHNICAL_ID = Regex("""\btechnicalId\s*=\s*"((?:[^"\\\n]|\\.)*)"""")

private fun String.unescapeKotlinLiteral(): String {
    val literal = this
    val result = StringBuilder(literal.length)
    var i = 0
    while (i < literal.length) {
        val char = literal[i]
        if (char != '\\' || i + 1 >= literal.length) {
            result.append(char)
            i++
            continue
        }
        when (val next = literal[i + 1]) {
            'n' -> result.append('\n')
            't' -> result.append('\t')
            'r' -> result.append('\r')
            'b' -> result.append('\b')
            'u' -> {
                val hex = literal.substring(i + 2, (i + 6).coerceAtMost(literal.length))
                val codePoint = hex.toIntOrNull(16)
                if (hex.length == 4 && codePoint != null) {
                    result.append(codePoint.toChar())
                    i += 4
                } else {
                    result.append('u')
                }
            }
            else -> result.append(next)
        }
        i += 2
    }
    return result.toString()
}

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
