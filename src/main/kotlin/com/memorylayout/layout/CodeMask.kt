package com.memorylayout.layout

/**
 * Blanks out everything in a C# file that is not code: comments, string literals, character
 * literals.
 *
 * The result has exactly the same length as the input, with every masked character replaced by a
 * space and every line break kept, so an offset in the mask is the same offset in the document.
 * Everything downstream -- finding declarations, matching braces, splitting statements -- then
 * works on plain text without having to know that a `{` inside a string is not a block.
 *
 * Interpolation holes are masked along with the string that holds them. Their contents are code,
 * but nothing the layout engine looks for ever lives inside one, and blanking them means the
 * braces of `$"{value}"` cannot be mistaken for a block.
 */
object CodeMask {

    fun of(text: String): String {
        return Masker(text).build()
    }

    private class Masker(private val text: String) {

        private val masked = CharArray(text.length)

        private var position = 0

        fun build(): String {
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
                    else -> {
                        copyCurrent()
                    }
                }
            }
            return String(masked)
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
                    maskLineComment()
                }
                '*' -> {
                    maskBlockComment()
                }
                else -> {
                    copyCurrent()
                }
            }
        }

        private fun maskLineComment() {
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
}
