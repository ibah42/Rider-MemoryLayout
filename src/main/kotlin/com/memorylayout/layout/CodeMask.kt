package com.memorylayout.layout

/**
 * Blanks out everything in a C# file that is not code: comments, string literals, character
 * literals, preprocessor directives.
 *
 * The result has exactly the same length as the input, with every masked character replaced by a
 * space and every line break kept, so an offset in the mask is the same offset in the document.
 * Everything downstream -- finding declarations, matching braces, splitting statements -- then
 * works on plain text without having to know that a `{` inside a string is not a block.
 *
 * Interpolation holes are masked along with the string that holds them. Their contents are code,
 * but nothing the layout engine looks for ever lives inside one, and blanking them means the
 * braces of `$"{value}"` cannot be mistaken for a block.
 *
 * A directive line (`#if`, `#endif`, `#region Fields`, `#pragma ...`) is blanked whole, but the
 * code between `#if` and `#endif` is kept, every branch of it: the mask has no way to know which
 * symbols are defined. Left in, a directive glues itself to the front of the next statement, so
 * `#endif` followed by `namespace Foo {` reads as a namespace header that does not start with
 * `namespace` -- and every type inside it disappears from the index.
 */
object CodeMask {

    fun of(text: String): String {
        return Masker(text).build()
    }

    private class Masker(private val text: String) {

        private val masked = CharArray(text.length)

        private var position = 0

        fun build(): String {
            // Not whitespace to Kotlin, so a mark left in would start the first statement: a file
            // opening with `namespace Foo {` would get a header that does not start with the word.
            if (text.startsWith(BYTE_ORDER_MARK)) {
                blankThrough(1)
            }
            while (position < text.length) {
                when (text[position]) {
                    '/' -> {
                        maskCommentOrCopy()
                    }
                    '"' -> {
                        maskStringLiteral()
                    }
                    '@' -> {
                        maskVerbatimStringOrCopy()
                    }
                    '\'' -> {
                        maskCharacterLiteral()
                    }
                    '#' -> {
                        maskDirectiveOrCopy()
                    }
                    else -> {
                        copyCurrent()
                    }
                }
            }
            return String(masked)
        }

        /**
         * A `#` opens a directive only as the first thing on its line; indentation in front of it
         * is allowed, and so is the byte order mark on the very first line, which the file on disk
         * still carries when it is read without the editor's decoding.
         */
        private fun maskDirectiveOrCopy() {
            if (!startsLine(position)) {
                copyCurrent()
                return
            }
            // A directive runs to the end of its line and cannot be continued onto the next one,
            // which is exactly the extent of a line comment.
            blankRestOfLine()
        }

        private fun startsLine(offset: Int): Boolean {
            var index = offset - 1
            while (index >= 0) {
                val current = text[index]
                if (current == '\n') {
                    return true
                }
                if (current != ' ' && current != '\t' && current != BYTE_ORDER_MARK) {
                    return false
                }
                index--
            }
            return true
        }

        private fun copyCurrent() {
            masked[position] = text[position]
            position++
        }

        /** A line break is never blanked: line numbers have to survive the mask. */
        private fun blankAt(index: Int) {
            val current = text[index]
            if (current == '\n' || current == '\r') {
                masked[index] = current
            } else {
                masked[index] = ' '
            }
        }

        private fun blankThrough(endExclusive: Int) {
            val limit = minOf(endExclusive, text.length)
            while (position < limit) {
                blankAt(position)
                position++
            }
        }

        private fun maskCommentOrCopy() {
            val next = text.getOrNull(position + 1)
            when (next) {
                '/' -> {
                    blankRestOfLine()
                }
                '*' -> {
                    maskBlockComment()
                }
                else -> {
                    copyCurrent()
                }
            }
        }

        private fun blankRestOfLine() {
            var end = position
            while (end < text.length && text[end] != '\n') {
                end++
            }
            blankThrough(end)
        }

        private fun maskBlockComment() {
            val closing = text.indexOf("*/", position + 2)
            if (closing < 0) {
                blankThrough(text.length)
                return
            }
            blankThrough(closing + 2)
        }

        private fun maskStringLiteral() {
            if (text.startsWith(RAW_STRING_DELIMITER, position)) {
                maskRawStringLiteral()
                return
            }
            blankThrough(position + 1)
            while (position < text.length) {
                val current = text[position]
                when (current) {
                    '\\' -> {
                        blankThrough(position + 2)
                    }
                    '"' -> {
                        blankThrough(position + 1)
                        return
                    }
                    '\n' -> {
                        // A plain string cannot span a line: the file is mid-edit or the quote
                        // belonged to something this masker does not understand. Stop here rather
                        // than blanking the rest of the file.
                        return
                    }
                    else -> {
                        blankThrough(position + 1)
                    }
                }
            }
        }

        /** `"""raw"""`, and any longer run of quotes, which closes on a run of the same length. */
        private fun maskRawStringLiteral() {
            var openingLength = 0
            while (position + openingLength < text.length && text[position + openingLength] == '"') {
                openingLength++
            }
            blankThrough(position + openingLength)
            while (position < text.length) {
                if (text[position] != '"') {
                    blankThrough(position + 1)
                    continue
                }
                var closingLength = 0
                while (position + closingLength < text.length && text[position + closingLength] == '"') {
                    closingLength++
                }
                blankThrough(position + closingLength)
                if (closingLength >= openingLength) {
                    return
                }
            }
        }

        private fun maskVerbatimStringOrCopy() {
            if (text.getOrNull(position + 1) != '"') {
                copyCurrent()
                return
            }
            blankThrough(position + 2)
            while (position < text.length) {
                if (text[position] != '"') {
                    blankThrough(position + 1)
                    continue
                }
                if (text.getOrNull(position + 1) == '"') {
                    blankThrough(position + 2)
                    continue
                }
                blankThrough(position + 1)
                return
            }
        }

        private fun maskCharacterLiteral() {
            blankThrough(position + 1)
            while (position < text.length) {
                val current = text[position]
                when (current) {
                    '\\' -> {
                        blankThrough(position + 2)
                    }
                    '\'' -> {
                        blankThrough(position + 1)
                        return
                    }
                    '\n' -> {
                        return
                    }
                    else -> {
                        blankThrough(position + 1)
                    }
                }
            }
        }
    }

    private const val RAW_STRING_DELIMITER = "\"\"\""

    private const val BYTE_ORDER_MARK = '\uFEFF'
}
