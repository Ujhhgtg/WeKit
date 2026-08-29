package dev.ujhhgtg.wekit.activity.scripting_python

import top.yukonga.scripta.editor.highlight.HighlightSpan
import top.yukonga.scripta.editor.highlight.LineHighlight
import top.yukonga.scripta.editor.highlight.LineState
import top.yukonga.scripta.editor.highlight.SyntaxHighlighter
import top.yukonga.scripta.editor.highlight.TokenType

/**
 * Python 语法高亮插件（行级增量）。跨行结构只有三引号字符串：经 [LineState] 在行间传递，
 * 空行保持状态。行内为一遍游标扫描：注释、字符串（含 f/r/b 前缀与三引号）、数字、关键字、
 * 内置名、装饰器、`def`/`class` 后的名称，以及调用位置（标识符后随 `(`）。
 */
class PythonHighlighter : SyntaxHighlighter {

    override val lineCommentPrefix: String = "#"

    private data class TripleStringState(val quote: Char) : LineState

    override fun highlightLine(text: String, entryState: LineState?): LineHighlight {
        if (entryState is TripleStringState) {
            val close = text.indexOf(entryState.quote.toString().repeat(3))
            if (close < 0) return LineHighlight(listOf(HighlightSpan(0, text.length, TokenType.String)), entryState)
            val closeEnd = close + 3
            val scanner = Scanner(text, closeEnd)
            val rest = scanner.scanLine()
            return LineHighlight(
                listOf(HighlightSpan(0, closeEnd, TokenType.String)) + rest.spans,
                rest.exitState,
            )
        }
        return Scanner(text, 0).scanLine()
    }

    private class Scanner(private val text: String, from: Int) {
        private val spans = ArrayList<HighlightSpan>()
        private var exit: LineState? = null
        private var i = from
        private var lastWord: String? = null

        fun scanLine(): LineHighlight {
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '#' -> {
                        spans.add(HighlightSpan(i, text.length, TokenType.Comment))
                        return done()
                    }

                    c == '"' || c == '\'' -> {
                        if (!scanString()) return done()
                    }

                    c.isDigit() -> scanNumber()

                    c == '@' && lastWord == null && i == indentEnd() -> {
                        val e = wordEnd(i + 1)
                        spans.add(HighlightSpan(i, e, TokenType.Directive))
                        i = e
                    }

                    c.isLetter() || c == '_' -> scanWord()

                    c == '.' && i + 1 < text.length && text[i + 1].isDigit() -> scanNumber()

                    c in "+-*/%<>=!&|^~" -> {
                        spans.add(HighlightSpan(i, i + 1, TokenType.Operator))
                        i++
                        lastWord = null
                    }

                    c in ",:;()[]{}." -> {
                        spans.add(HighlightSpan(i, i + 1, TokenType.Punctuation))
                        i++
                        lastWord = null
                    }

                    else -> {
                        i++
                        lastWord = null
                    }
                }
            }
            return done()
        }

        /** 引号串（含三引号）。返回 false 表示进入跨行三引号态、本行扫描结束。 */
        private fun scanString(): Boolean {
            val quote = text[i]
            if (text.startsWith(quote.toString().repeat(3), i)) {
                val close = text.indexOf(quote.toString().repeat(3), i + 3)
                if (close < 0) {
                    spans.add(HighlightSpan(i, text.length, TokenType.String))
                    exit = TripleStringState(quote)
                    return false
                }
                val end = close + 3
                spans.add(HighlightSpan(i, end, TokenType.String))
                i = end
                return true
            }
            var j = i + 1
            while (j < text.length) {
                if (text[j] == '\\') {
                    j += 2
                    continue
                }
                if (text[j] == quote) {
                    j++
                    break
                }
                j++
            }
            spans.add(HighlightSpan(i, minOf(j, text.length), TokenType.String))
            i = minOf(j, text.length)
            return true
        }

        private fun scanNumber() {
            var j = i
            while (j < text.length) {
                val c = text[j]
                if (c.isDigit() || c == '_' || c in "xXoObBeE" || c == '.' ||
                    ((c == '+' || c == '-') && j > i && (text[j - 1] == 'e' || text[j - 1] == 'E'))
                ) {
                    j++
                } else {
                    break
                }
            }
            spans.add(HighlightSpan(i, j, TokenType.Number))
            i = j
            lastWord = null
        }

        private fun scanWord() {
            val end = wordEnd(i)
            val word = text.substring(i, end)
            // f/r/b/u 前缀 + 引号：前缀并入字符串着色。
            if (word.length <= 2 && word.isNotEmpty() && word.all { it in "fFrRbBuU" } &&
                end < text.length && (text[end] == '"' || text[end] == '\'')
            ) {
                scanString()
                return
            }
            val type = when {
                word in KEYWORDS -> TokenType.Keyword
                word == "None" -> TokenType.Null
                word == "True" || word == "False" -> TokenType.Boolean
                word == "self" || word == "cls" -> TokenType.Variable
                lastWord == "def" -> TokenType.Function
                lastWord == "class" -> TokenType.Type
                word in BUILTINS -> TokenType.Function
                text.startsWith("(", end) -> TokenType.Function
                else -> null
            }
            if (type != null) spans.add(HighlightSpan(i, end, type))
            i = end
            lastWord = word
        }

        private fun wordEnd(from: Int): Int {
            var j = from
            while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
            return j
        }

        private fun indentEnd(): Int {
            var j = 0
            while (j < text.length && text[j] == ' ') j++
            return j
        }

        private fun done() = LineHighlight(spans, exit)
    }

    companion object {
        private val KEYWORDS = setOf(
            "and", "as", "assert", "async", "await", "break", "case", "class", "continue",
            "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
            "if", "import", "in", "is", "lambda", "match", "nonlocal", "not", "or",
            "pass", "raise", "return", "try", "while", "with", "yield",
        )

        private val BUILTINS = setOf(
            "abs", "all", "any", "bin", "bool", "bytearray", "bytes", "callable", "chr",
            "classmethod", "dict", "dir", "divmod", "enumerate", "eval", "exec", "filter",
            "float", "format", "getattr", "hasattr", "hash", "hex", "id", "input", "int",
            "isinstance", "issubclass", "iter", "len", "list", "map", "max", "min", "next",
            "object", "oct", "open", "ord", "pow", "print", "property", "range", "repr",
            "reversed", "round", "set", "setattr", "sorted", "staticmethod", "str", "sum",
            "super", "tuple", "type", "vars", "zip",
        )
    }
}
