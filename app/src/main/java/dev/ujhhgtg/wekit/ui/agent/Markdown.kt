package dev.ujhhgtg.wekit.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.ujhhgtg.wekit.ui.content.GlobalImageLoader

/**
 * A minimal, pure-Compose Markdown renderer for the WeAgent chat UI. It deliberately covers only
 * the commonly used subset: ATX headings, bold/italic/bold-italic, strikethrough, inline code,
 * fenced code blocks, links, blockquotes, ordered/unordered (nestable) lists, and horizontal rules.
 *
 * Anything it doesn't recognize falls through as plain text, so partial/streaming model output
 * always renders something sensible rather than throwing.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    // A per-instance cache so streaming updates only re-parse the growing tail, not the whole
    // message.
    val parser = remember { IncrementalMarkdownParser() }
    val blocks = remember(markdown) { parser.parse(markdown) }
    val colors =
        MdColors(
            text = color,
            codeBg = MaterialTheme.colorScheme.surfaceVariant,
            divider = MaterialTheme.colorScheme.outlineVariant,
            link = MaterialTheme.colorScheme.primary,
            quoteBar = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        )
    Column(modifier) { MdBlocks(blocks, style, colors) }
}

private data class MdColors(
    val text: Color,
    val codeBg: Color,
    val divider: Color,
    val link: Color,
    val quoteBar: Color,
)

// ---------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------

@Composable
private fun MdBlocks(blocks: List<MdBlock>, style: TextStyle, colors: MdColors) {
    blocks.forEachIndexed { i, block ->
        if (i > 0) Spacer(Modifier.height(6.dp))
        when (block) {
            is MdBlock.Heading -> {
                // h1..h6 -> a shrinking scale on top of the base body style.
                val scale =
                    when (block.level) {
                        1 -> 1.6f
                        2 -> 1.4f
                        3 -> 1.22f
                        4 -> 1.1f
                        5 -> 1.0f
                        else -> 0.92f
                    }
                Text(
                    renderInline(block.text, colors),
                    style =
                        style.copy(
                            fontSize = style.fontSize * scale,
                            fontWeight = FontWeight.Bold,
                            color = colors.text,
                        ),
                )
            }

            is MdBlock.Paragraph ->
                Text(
                    renderInline(block.text, colors),
                    style = style.copy(color = colors.text),
                )

            is MdBlock.CodeBlock -> CodeBlock(block.code, style, colors)

            is MdBlock.Quote ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier.padding(end = 8.dp)
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.quoteBar),
                    )
                    Column(Modifier.weight(1f)) {
                        MdBlocks(
                            block.children,
                            style,
                            colors.copy(text = colors.text.copy(alpha = 0.85f)),
                        )
                    }
                }

            is MdBlock.ListBlock ->
                Column(Modifier.fillMaxWidth()) {
                    block.items.forEachIndexed { idx, item ->
                        if (idx > 0) Spacer(Modifier.height(2.dp))
                        // Task items show a checkbox glyph in place of the bullet/number.
                        val marker =
                            when {
                                item.checked != null -> if (item.checked) "☑  " else "☐  "
                                block.ordered -> "${block.start + idx}. "
                                else -> "•  "
                            }
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                marker,
                                style =
                                    style.copy(
                                        color =
                                            if (item.checked == true) colors.link else colors.text,
                                        fontWeight = FontWeight.Medium,
                                    ),
                            )
                            Column(Modifier.weight(1f)) { MdBlocks(item.blocks, style, colors) }
                        }
                    }
                }

            is MdBlock.Table -> TableBlock(block, style, colors)

            is MdBlock.Image -> MdImage(block, style, colors)

            MdBlock.Rule ->
                Box(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .height(1.dp)
                        .background(colors.divider),
                )
        }
    }
}

@Composable
private fun TableBlock(table: MdBlock.Table, style: TextStyle, colors: MdColors) {
    // Equal-weight columns inside a bordered, horizontally scrollable frame. Column count is taken
    // from the header; short data rows are padded with blanks so cells stay aligned.
    val cols = table.header.size.coerceAtLeast(1)
    fun align(i: Int) = table.align.getOrElse(i) { MdAlign.LEFT }
    val hAlign = { a: MdAlign ->
        when (a) {
            MdAlign.LEFT -> androidx.compose.ui.Alignment.Start
            MdAlign.CENTER -> androidx.compose.ui.Alignment.CenterHorizontally
            MdAlign.RIGHT -> androidx.compose.ui.Alignment.End
        }
    }

    @Composable
    fun cell(text: String, colIdx: Int, header: Boolean) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = hAlign(align(colIdx)),
        ) {
            Text(
                renderInline(text, colors),
                style =
                    style.copy(
                        color = colors.text,
                        fontWeight = if (header) FontWeight.Bold else style.fontWeight,
                    ),
            )
        }
    }

    Box(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(8.dp)).background(colors.codeBg.copy(alpha = 0.4f)),
        ) {
            // Header row.
            Row(Modifier.height(IntrinsicSize.Min)) {
                for (c in 0 until cols) {
                    if (c > 0) VDivider(colors)
                    Box(Modifier.width(120.dp)) {
                        cell(table.header.getOrElse(c) { "" }, c, header = true)
                    }
                }
            }
            HDivider(colors)
            // Data rows.
            table.rows.forEachIndexed { ri, r ->
                if (ri > 0) HDivider(colors)
                Row(Modifier.height(IntrinsicSize.Min)) {
                    for (c in 0 until cols) {
                        if (c > 0) VDivider(colors)
                        Box(Modifier.width(120.dp)) {
                            cell(r.getOrElse(c) { "" }, c, header = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HDivider(colors: MdColors) =
    Box(
        Modifier.fillMaxWidth().height(1.dp).background(colors.divider),
    )

@Composable
private fun VDivider(colors: MdColors) =
    Box(
        Modifier.width(1.dp).fillMaxHeight().background(colors.divider),
    )

@Composable
private fun CodeBlock(code: String, style: TextStyle, colors: MdColors) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeBg)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text(
            code,
            style =
                style.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = style.fontSize * 0.9f,
                    color = colors.text,
                ),
        )
    }
}

@Composable
private fun MdImage(image: MdBlock.Image, style: TextStyle, colors: MdColors) {
    Column(Modifier.fillMaxWidth()) {
        AsyncImage(
            model = image.url,
            contentDescription = image.alt.ifBlank { null },
            imageLoader = GlobalImageLoader,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        )
        // Show the alt text as a caption when present.
        if (image.alt.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                image.alt,
                style =
                    style.copy(
                        fontSize = style.fontSize * 0.85f,
                        color = colors.text.copy(alpha = 0.7f),
                    ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Inline parsing: **bold**, *italic*/_italic_, ***both***, ~~strike~~, `code`, [text](url), \escape
// ---------------------------------------------------------------------------------------------

@Composable
private fun renderInline(text: String, colors: MdColors): AnnotatedString {
    val codeBg = colors.codeBg
    val linkColor = colors.link
    return remember(text, codeBg, linkColor) {
        buildAnnotatedString { emitInline(text, 0, text.length, codeBg, linkColor) }
    }
}

/** Recursively appends the span [start, end) of [text], parsing the delimiters we support. */
private fun AnnotatedString.Builder.emitInline(
    text: String,
    start: Int,
    end: Int,
    codeBg: Color,
    linkColor: Color,
) {
    var i = start
    val literal = StringBuilder()
    fun flush() {
        if (literal.isNotEmpty()) {
            append(literal.toString())
            literal.setLength(0)
        }
    }
    while (i < end) {
        val c = text[i]
        when {
            // Backslash escape: the next char is taken literally.
            c == '\\' && i + 1 < end -> {
                literal.append(text[i + 1])
                i += 2
            }

            // Inline code: verbatim, no nested parsing.
            c == '`' -> {
                val close = text.indexOf('`', i + 1)
                if (close in i + 1 until end) {
                    flush()
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(i + 1, close))
                    }
                    i = close + 1
                } else {
                    literal.append(c)
                    i++
                }
            }

            // Link: [label](url)
            c == '[' -> {
                val parsed = parseLink(text, i, end)
                if (parsed != null) {
                    flush()
                    withLink(
                        LinkAnnotation.Url(
                            parsed.url,
                            TextLinkStyles(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                    ) {
                        emitInline(text, parsed.labelStart, parsed.labelEnd, codeBg, linkColor)
                    }
                    i = parsed.next
                } else {
                    literal.append(c)
                    i++
                }
            }

            // ***bold italic***
            c == '*' && matchRun(text, i, end, "***") -> {
                val close = findCloser(text, i + 3, end, "***")
                if (close != -1) {
                    flush()
                    withStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                    ) {
                        emitInline(text, i + 3, close, codeBg, linkColor)
                    }
                    i = close + 3
                } else {
                    literal.append(c)
                    i++
                }
            }

            // **bold**
            (c == '*' || c == '_') && matchRun(text, i, end, "$c$c") -> {
                val delim = "$c$c"
                val close = findCloser(text, i + 2, end, delim)
                if (close != -1) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        emitInline(text, i + 2, close, codeBg, linkColor)
                    }
                    i = close + 2
                } else {
                    literal.append(c)
                    i++
                }
            }

            // ~~strikethrough~~
            c == '~' && matchRun(text, i, end, "~~") -> {
                val close = findCloser(text, i + 2, end, "~~")
                if (close != -1) {
                    flush()
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        emitInline(text, i + 2, close, codeBg, linkColor)
                    }
                    i = close + 2
                } else {
                    literal.append(c)
                    i++
                }
            }

            // *italic* / _italic_
            c == '*' || c == '_' -> {
                val close = findCloser(text, i + 1, end, c.toString())
                if (close != -1) {
                    flush()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        emitInline(text, i + 1, close, codeBg, linkColor)
                    }
                    i = close + 1
                } else {
                    literal.append(c)
                    i++
                }
            }

            else -> {
                literal.append(c)
                i++
            }
        }
    }
    flush()
}

private class ParsedLink(val labelStart: Int, val labelEnd: Int, val url: String, val next: Int)

/** Parses `[label](url)` starting at the `[` at [open]; returns null if it isn't a valid link. */
private fun parseLink(text: String, open: Int, end: Int): ParsedLink? {
    var depth = 0
    var i = open
    while (i < end) {
        val ch = text[i]
        if (ch == '\\') {
            i += 2
            continue
        }
        if (ch == '[') depth++
        else if (ch == ']') {
            depth--
            if (depth == 0) break
        }
        i++
    }
    val labelEnd = i
    if (labelEnd >= end || text[labelEnd] != ']') return null
    if (labelEnd + 1 >= end || text[labelEnd + 1] != '(') return null
    val urlStart = labelEnd + 2
    val urlEnd = text.indexOf(')', urlStart)
    if (urlEnd == -1 || urlEnd >= end) return null
    val url = text.substring(urlStart, urlEnd).trim()
    if (url.isEmpty()) return null
    return ParsedLink(open + 1, labelEnd, url, urlEnd + 1)
}

/**
 * True when [delim] occurs at [pos] and isn't itself immediately followed by another delim char.
 */
private fun matchRun(text: String, pos: Int, end: Int, delim: String): Boolean {
    if (pos + delim.length > end) return false
    // The when-branches try longer delimiters first (*** before **), so a plain literal match here
    // is enough — the caller ordering resolves overlaps.
    return text.regionMatches(pos, delim, 0, delim.length)
}

/** Finds the closing [delim] in [start, end), skipping escaped chars and inline-code spans. */
private fun findCloser(text: String, start: Int, end: Int, delim: String): Int {
    var i = start
    while (i < end) {
        val ch = text[i]
        when {
            ch == '\\' -> i += 2
            ch == '`' -> {
                val c = text.indexOf('`', i + 1)
                i = if (c in 0 until end) c + 1 else i + 1
            }

            i + delim.length <= end && text.regionMatches(i, delim, 0, delim.length) -> {
                // A closer can't sit right at the opening (empty span).
                if (i > start) return i else i++
            }

            else -> i++
        }
    }
    return -1
}

// ---------------------------------------------------------------------------------------------
// Block parsing
// ---------------------------------------------------------------------------------------------

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock

    data class Paragraph(val text: String) : MdBlock

    data class CodeBlock(val code: String) : MdBlock

    data class Quote(val children: List<MdBlock>) : MdBlock

    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : MdBlock

    /**
     * One list item; [checked] is null for a plain item, true/false for a `[x]`/`[ ]` task item.
     */
    data class ListItem(val checked: Boolean?, val blocks: List<MdBlock>)

    data class Table(
        val header: List<String>,
        val align: List<MdAlign>,
        val rows: List<List<String>>,
    ) : MdBlock

    data class Image(val alt: String, val url: String) : MdBlock

    data object Rule : MdBlock
}

private enum class MdAlign {
    LEFT,
    CENTER,
    RIGHT,
}

private val headingRe = Regex("""^(#{1,6})\s+(.*)$""")
private val ruleRe = Regex("""^ {0,3}([-*_])( *\1){2,} *$""")
private val fenceRe = Regex("""^ {0,3}(`{3,}|~{3,})\s*(\S*)\s*$""")
private val bulletRe = Regex("""^( *)([-*+])\s+(.*)$""")
private val orderedRe = Regex("""^( *)(\d{1,9})[.)]\s+(.*)$""")

// A task-list marker at the start of an item's content: `[ ]`, `[x]`, or `[X]` then a space.
private val taskMarkerRe = Regex("""^\[([ xX])]\s+(.*)$""")

// A GFM table delimiter row: pipe-separated cells of dashes with optional leading/trailing colons.
private val tableDelimRe = Regex("""^ {0,3}\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")

// An image: ![alt](url). URL may not contain ')' — good enough for the common case.
private val imageRe = Regex("""!\[([^\]]*)]\(\s*([^)\s]+)(?:\s+"[^"]*")?\s*\)""")

private fun splitLines(input: String): List<String> =
    input.replace("\r\n", "\n").replace("\r", "\n").split("\n")

/**
 * A parsed top-level block together with the line index it started at (used by incremental reuse).
 */
private class BlockSpan(val startLine: Int, val block: MdBlock)

private fun parseBlocks(lines: List<String>): List<MdBlock> =
    parseBlockSpans(lines, 0).map { it.block }

/**
 * The core block parser. Emits one [BlockSpan] per top-level block, tagged with the line it began
 * on. The parser is stateless across block boundaries — each outer-loop iteration starts fresh at a
 * boundary and only ever looks forward — so re-parsing from any block's start line reproduces that
 * block and everything after it identically. That property is what makes incremental reuse sound.
 */
private fun parseBlockSpans(lines: List<String>, from: Int): List<BlockSpan> {
    val blocks = mutableListOf<BlockSpan>()
    var i = from
    var start = from
    fun emit(b: MdBlock) {
        blocks += BlockSpan(start, b)
    }
    while (i < lines.size) {
        val line = lines[i]

        // Blank line: skip.
        if (line.isBlank()) {
            i++
            continue
        }

        // Everything below emits the block that begins on this line.
        start = i

        // Fenced code block.
        val fence = fenceRe.matchEntire(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val body = StringBuilder()
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (
                    l.trimStart().startsWith(marker.substring(0, 1).repeat(3)) &&
                        l.trim().all { it == marker[0] } &&
                        l.trim().length >= marker.length
                ) {
                    i++
                    break
                }
                if (body.isNotEmpty()) body.append('\n')
                body.append(l)
                i++
            }
            emit(MdBlock.CodeBlock(body.toString()))
            continue
        }

        // Horizontal rule.
        if (ruleRe.matches(line)) {
            emit(MdBlock.Rule)
            i++
            continue
        }

        // ATX heading.
        val h = headingRe.matchEntire(line.trimStart())
        if (h != null) {
            emit(
                MdBlock.Heading(
                    h.groupValues[1].length,
                    h.groupValues[2].trim().trimEnd('#').trim(),
                ),
            )
            i++
            continue
        }

        // Blockquote: gather consecutive `>`-prefixed lines, strip one level, recurse.
        if (line.trimStart().startsWith(">")) {
            val inner = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                inner += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                i++
            }
            emit(MdBlock.Quote(parseBlocks(inner)))
            continue
        }

        // Lists.
        if (bulletRe.matches(line) || orderedRe.matches(line)) {
            val (list, consumed) = parseList(lines, i)
            emit(list)
            i = consumed
            continue
        }

        // GFM table: a `|`-bearing header line immediately followed by a delimiter line.
        if (line.contains('|') && i + 1 < lines.size && tableDelimRe.matches(lines[i + 1])) {
            val header = splitRow(line)
            val align =
                splitRow(lines[i + 1]).map { cell ->
                    val l = cell.startsWith(":")
                    val r = cell.endsWith(":")
                    when {
                        l && r -> MdAlign.CENTER
                        r -> MdAlign.RIGHT
                        else -> MdAlign.LEFT
                    }
                }
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                rows += splitRow(lines[i])
                i++
            }
            emit(MdBlock.Table(header, align, rows))
            continue
        }

        // Paragraph: accumulate until a blank line or a line that starts a different block.
        val para = StringBuilder()
        while (i < lines.size) {
            val l = lines[i]
            if (
                l.isBlank() ||
                    fenceRe.matches(l) ||
                    ruleRe.matches(l) ||
                    headingRe.matchEntire(l.trimStart()) != null ||
                    l.trimStart().startsWith(">") ||
                    bulletRe.matches(l) ||
                    orderedRe.matches(l) ||
                    l.contains('|') && i + 1 < lines.size && tableDelimRe.matches(lines[i + 1])
            )
                break
            if (para.isNotEmpty()) para.append('\n')
            para.append(l.trim())
            i++
        }
        if (para.isNotEmpty()) splitParagraph(para.toString()).forEach { emit(it) }
    }
    return blocks
}

/**
 * Incremental parse cache for streaming. Because appended text can only affect the *last* top-level
 * block (all earlier blocks were terminated by lines that already exist and won't change), we cache
 * every block but the last and re-parse only from the last block's start line on each update. Falls
 * back to a full parse if the stable prefix diverges (an edit, retry, or session switch).
 */
private class IncrementalMarkdownParser {
    private var prevLines: List<String> = emptyList()
    private var stableBlocks: List<MdBlock> = emptyList()
    private var resumeLine: Int = 0

    fun parse(input: String): List<MdBlock> {
        val lines = splitLines(input)

        // Reuse only if the stable prefix [0, resumeLine) is byte-identical to last time.
        val reusable =
            resumeLine in 0..lines.size &&
                resumeLine <= prevLines.size &&
                sameUpTo(lines, prevLines, resumeLine)

        val spans =
            if (reusable) parseBlockSpans(lines, resumeLine)
            else {
                stableBlocks = emptyList()
                parseBlockSpans(lines, 0)
            }

        val result: List<MdBlock>
        if (spans.isEmpty()) {
            // No block emitted from the tail (e.g. only trailing blanks); keep the stable prefix.
            result = stableBlocks
        } else {
            // Hold back the final block — it may still grow — and promote the rest to stable.
            val newlyStable = spans.subList(0, spans.size - 1).map { it.block }
            stableBlocks = if (reusable) stableBlocks + newlyStable else newlyStable
            resumeLine = spans.last().startLine
            result = stableBlocks + spans.last().block
        }
        prevLines = lines
        return result
    }

    private fun sameUpTo(a: List<String>, b: List<String>, n: Int): Boolean {
        if (a.size < n || b.size < n) return false
        for (k in 0 until n) if (a[k] != b[k]) return false
        return true
    }
}

/** Splits a table row on unescaped `|`, dropping the optional leading/trailing pipe. */
private fun splitRow(line: String): List<String> {
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var k = 0
    val s = line.trim()
    while (k < s.length) {
        when (val ch = s[k]) {
            '\\' if k + 1 < s.length -> {
                cur.append(ch).append(s[k + 1])
                k += 2
            }

            '|' -> {
                cells += cur.toString().trim()
                cur.setLength(0)
                k++
            }

            else -> {
                cur.append(ch)
                k++
            }
        }
    }
    cells += cur.toString().trim()
    // Drop empties produced by a leading/trailing pipe.
    if (cells.isNotEmpty() && cells.first().isEmpty()) cells.removeAt(0)
    if (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.lastIndex)
    return cells
}

/**
 * Splits a paragraph's raw text into a run of blocks, lifting each `![alt](url)` image into its own
 * [MdBlock.Image] and keeping the surrounding text as [MdBlock.Paragraph]s. Inline (non-image)
 * links are left untouched for the inline renderer.
 */
private fun splitParagraph(text: String): List<MdBlock> {
    val matches = imageRe.findAll(text).toList()
    if (matches.isEmpty()) return listOf(MdBlock.Paragraph(text))
    val out = mutableListOf<MdBlock>()
    var last = 0
    for (m in matches) {
        val before = text.substring(last, m.range.first)
        if (before.isNotBlank()) out += MdBlock.Paragraph(before.trim())
        out += MdBlock.Image(m.groupValues[1].trim(), m.groupValues[2].trim())
        last = m.range.last + 1
    }
    val tail = text.substring(last)
    if (tail.isNotBlank()) out += MdBlock.Paragraph(tail.trim())
    return out
}

private class ListItemMatch(
    val indent: Int,
    val ordered: Boolean,
    val number: Int,
    val content: String,
    val contentCol: Int,
)

private fun matchListItem(line: String): ListItemMatch? {
    orderedRe.matchEntire(line)?.let {
        val indent = it.groupValues[1].length
        val content = it.groupValues[3]
        return ListItemMatch(
            indent,
            true,
            it.groupValues[2].toInt(),
            content,
            line.length - content.length,
        )
    }
    bulletRe.matchEntire(line)?.let {
        val indent = it.groupValues[1].length
        val content = it.groupValues[3]
        return ListItemMatch(indent, false, 0, content, line.length - content.length)
    }
    return null
}

/**
 * Parses a list starting at [from]; returns the block and the index of the first unconsumed line.
 */
private fun parseList(lines: List<String>, from: Int): Pair<MdBlock.ListBlock, Int> {
    val first = matchListItem(lines[from])!!
    val baseIndent = first.indent
    val ordered = first.ordered
    val items = mutableListOf<MdBlock.ListItem>()
    var i = from
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }
        // A marker at the base indent with the same ordering continues this list; anything else
        // (shallower, deeper, different type, or a non-list line) ends it. Deeper items are folded
        // into the current item below as continuation lines and re-parsed recursively.
        val m = matchListItem(line)
        if (m == null || m.indent != baseIndent || m.ordered != ordered) break

        // A GFM task marker `[ ]`/`[x]` right after the bullet turns this into a checkbox item
        // (unordered lists only, matching GFM).
        var checked: Boolean? = null
        var content = m.content
        if (!ordered) {
            taskMarkerRe.matchEntire(content)?.let {
                checked = it.groupValues[1].trim().lowercase() == "x"
                content = it.groupValues[2]
            }
        }

        // Collect this item's own line plus deeper-indented / blank continuation lines.
        val itemLines = mutableListOf(content)
        i++
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank()) {
                itemLines += ""
                i++
                continue
            }
            val leading = l.length - l.trimStart().length
            if (leading > baseIndent) {
                itemLines += l.substring(minOf(m.contentCol, leading))
                i++
            } else break
        }
        items += MdBlock.ListItem(checked, parseBlocks(itemLines))
    }
    return MdBlock.ListBlock(ordered, first.number, items) to i
}
