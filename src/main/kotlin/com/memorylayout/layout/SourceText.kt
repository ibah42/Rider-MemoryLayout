package com.memorylayout.layout

/**
 * One member or statement of a block, as found in the masked text.
 *
 * @param start offset of the first character, attributes included
 * @param headerEnd offset just past the header: the `{` that opens the body, or the `;`
 * @param bodyStart offset of the `{` that opens the body, or [NO_BODY]
 * @param bodyEnd offset of the matching `}`, or [NO_BODY]
 * @param end offset just past the whole statement
 */
data class SourceStatement(
    val start: Int,
    val headerEnd: Int,
    val bodyStart: Int,
    val bodyEnd: Int,
    val end: Int,
) {
    val hasBody: Boolean
        get() = bodyStart != NO_BODY

    fun headerText(masked: String): String {
        return masked.substring(start, headerEnd)
    }

    companion object {
        const val NO_BODY = -1
    }
}

/**
 * Reading the shapes of C# source out of already-masked text: identifiers, balanced groups,
 * statements, attributes.
 *
 * Every function here is a pure function of the string it is given. The text is expected to come
 * from [CodeMask], so a brace or a semicolon found here is always a real one.
 */
object SourceText {

    fun isIdentifierStart(character: Char): Boolean {
        return character.isLetter() || character == '_' || character == '@'
    }

    fun isIdentifierChar(character: Char): Boolean {
        return character.isLetterOrDigit() || character == '_'
    }

    /** The offset of the `}` that closes the `{` at [openOffset], or -1 when the text is unbalanced. */
    fun matchingBrace(masked: String, openOffset: Int): Int {
        var depth = 0
        var position = openOffset
        while (position < masked.length) {
            val current = masked[position]
            if (current == '{') {
                depth++
            }
            if (current == '}') {
                depth--
                if (depth == 0) {
                    return position
                }
            }
            position++
        }
        return -1
    }

    /**
     * Splits a block into the members it holds, one level deep.
     *
     * A `{` only opens a body when nothing assigned to it first: `public Foo field = new Foo { A = 1 };`
     * puts a brace at the same nesting level as a method's, and reading it as a body would swallow
     * the rest of the type. That is what [assignmentSeen] guards.
     */
    fun statementsIn(masked: String, from: Int, to: Int): List<SourceStatement> {
        val statements = ArrayList<SourceStatement>()
        var position = from
        var statementStart = -1
        var parenthesisDepth = 0
        var bracketDepth = 0
        var assignmentSeen = false
        while (position < to) {
            val current = masked[position]
            if (current.isWhitespace()) {
                position++
                continue
            }
            if (statementStart < 0) {
                statementStart = position
            }
            when (current) {
                '(' -> {
                    parenthesisDepth++
                    position++
                }
                ')' -> {
                    if (parenthesisDepth > 0) {
                        parenthesisDepth--
                    }
                    position++
                }
                '[' -> {
                    bracketDepth++
                    position++
                }
                ']' -> {
                    if (bracketDepth > 0) {
                        bracketDepth--
                    }
                    position++
                }
                '=' -> {
                    if (parenthesisDepth == 0 && bracketDepth == 0 && isPlainAssignment(masked, position)) {
                        assignmentSeen = true
                    }
                    position++
                }
                ';' -> {
                    if (parenthesisDepth > 0 || bracketDepth > 0) {
                        position++
                        continue
                    }
                    statements.add(
                        SourceStatement(
                            start = statementStart,
                            headerEnd = position,
                            bodyStart = SourceStatement.NO_BODY,
                            bodyEnd = SourceStatement.NO_BODY,
                            end = position + 1,
                        )
                    )
                    statementStart = -1
                    assignmentSeen = false
                    position++
                }
                '{' -> {
                    if (parenthesisDepth > 0 || bracketDepth > 0) {
                        position++
                        continue
                    }
                    val closing = matchingBrace(masked, position)
                    if (closing < 0) {
                        return statements
                    }
                    if (assignmentSeen) {
                        // An initializer, not a body: the statement still ends at its own `;`.
                        position = closing + 1
                        continue
                    }
                    var end = closing + 1
                    val semicolon = skipToSemicolonAfterBody(masked, end, to)
                    if (semicolon >= 0) {
                        end = semicolon + 1
                    }
                    statements.add(
                        SourceStatement(
                            start = statementStart,
                            headerEnd = position,
                            bodyStart = position,
                            bodyEnd = closing,
                            end = end,
                        )
                    )
                    statementStart = -1
                    assignmentSeen = false
                    position = end
                }
                '}' -> {
                    if (parenthesisDepth > 0 || bracketDepth > 0) {
                        // The close of a brace the `{` branch skipped for the same reason:
                        // `[Attribute(Values = new [] { typeof(int) })]`, `Call(x => { ... })`.
                        position++
                        continue
                    }
                    // The block being walked ended earlier than `to` said. Nothing left to read.
                    return statements
                }
                else -> {
                    position++
                }
            }
        }
        return statements
    }

    /** `=` alone, as opposed to `==`, `=>`, `<=`, `>=`, `!=`. */
    private fun isPlainAssignment(masked: String, position: Int): Boolean {
        val previous = masked.getOrNull(position - 1)
        if (previous == '=' || previous == '<' || previous == '>' || previous == '!') {
            return false
        }
        val next = masked.getOrNull(position + 1)
        return next != '=' && next != '>'
    }

    /** A declaration body can be followed by a `;`: `struct Inner { };`, `public int X { get; } = 1;`. */
    private fun skipToSemicolonAfterBody(masked: String, from: Int, to: Int): Int {
        var position = from
        while (position < to) {
            val current = masked[position]
            if (current.isWhitespace()) {
                position++
                continue
            }
            if (current == ';') {
                return position
            }
            return -1
        }
        return -1
    }

    /**
     * Splits the leading `[...]` attribute groups off a header.
     *
     * @return the attribute groups with their brackets, and the rest of the header
     */
    fun splitAttributes(header: String): Pair<List<String>, String> {
        val attributes = ArrayList<String>()
        var position = 0
        while (position < header.length) {
            if (header[position].isWhitespace()) {
                position++
                continue
            }
            if (header[position] != '[') {
                break
            }
            val closing = matchingBracket(header, position)
            if (closing < 0) {
                break
            }
            attributes.add(header.substring(position, closing + 1))
            position = closing + 1
        }
        return Pair(attributes, header.substring(position))
    }

    fun matchingBracket(text: String, openOffset: Int): Int {
        var depth = 0
        var position = openOffset
        while (position < text.length) {
            val current = text[position]
            if (current == '[') {
                depth++
            }
            if (current == ']') {
                depth--
                if (depth == 0) {
                    return position
                }
            }
            position++
        }
        return -1
    }

    /**
     * The offset of [character] outside any `()`, `[]` or `<>` group, or -1.
     *
     * The match is tested against the depth *before* the character is counted, so the `(` that
     * opens a top-level group is found rather than hidden by the group it just opened. That is
     * what makes this usable for finding a parameter list at all.
     */
    fun indexOfTopLevel(text: String, character: Char, from: Int = 0): Int {
        var parenthesisDepth = 0
        var bracketDepth = 0
        var angleDepth = 0
        var position = from
        while (position < text.length) {
            val current = text[position]
            val insideGroup = parenthesisDepth > 0 || bracketDepth > 0 || angleDepth > 0
            if (!insideGroup && current == character) {
                return position
            }
            when (current) {
                '(' -> {
                    parenthesisDepth++
                }
                ')' -> {
                    if (parenthesisDepth > 0) {
                        parenthesisDepth--
                    }
                }
                '[' -> {
                    bracketDepth++
                }
                ']' -> {
                    if (bracketDepth > 0) {
                        bracketDepth--
                    }
                }
                '<' -> {
                    angleDepth++
                }
                '>' -> {
                    if (angleDepth > 0) {
                        angleDepth--
                    }
                }
            }
            position++
        }
        return -1
    }

    /** Splits at every [separator] that is not inside a `()`, `[]` or `<>` group. */
    fun splitTopLevel(text: String, separator: Char): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        var position = 0
        while (position < text.length) {
            val next = indexOfTopLevel(text, separator, position)
            if (next < 0) {
                break
            }
            parts.add(text.substring(start, next))
            start = next + 1
            position = next + 1
        }
        parts.add(text.substring(start))
        return parts
    }

    /** True when [keyword] appears in [text] as a whole word. */
    fun containsWord(text: String, keyword: String): Boolean {
        return indexOfWord(text, keyword) >= 0
    }

    fun indexOfWord(text: String, keyword: String, from: Int = 0): Int {
        var index = text.indexOf(keyword, from)
        while (index >= 0) {
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + keyword.length)
            val standsAlone = (before == null || !isIdentifierChar(before)) &&
                (after == null || !isIdentifierChar(after))
            if (standsAlone) {
                return index
            }
            index = text.indexOf(keyword, index + 1)
        }
        return -1
    }

    /**
     * Where [name] itself stands in the member declaration that starts at [statementStart], or
     * [statementStart] when it cannot be found.
     *
     * A field remembers where its statement begins, which is its first attribute or modifier; a
     * reader who clicks a row wants the caret on the name. The last match before the declaration
     * ends is taken, not the first: in `Vector3 Vector3;` the first one is the type.
     */
    fun nameOffsetInStatement(masked: String, statementStart: Int, name: String): Int {
        if (statementStart < 0 || statementStart >= masked.length || name.isEmpty()) {
            return statementStart
        }
        var end = statementStart
        var depth = 0
        while (end < masked.length && !endsDeclarationAt(masked[end], depth)) {
            when (masked[end]) {
                '(', '[' -> {
                    depth++
                }
                ')', ']' -> {
                    depth = maxOf(depth - 1, 0)
                }
                else -> {
                    // Part of the declaration.
                }
            }
            end++
        }
        var found = -1
        var index = indexOfWord(masked, name, statementStart)
        while (index in statementStart until end) {
            found = index
            index = indexOfWord(masked, name, index + 1)
        }
        if (found < 0) {
            return statementStart
        }
        return found
    }

    /** A positional record's `;` sits after its parameter list, so only depth 0 ends anything. */
    private fun endsDeclarationAt(character: Char, depth: Int): Boolean {
        if (depth > 0) {
            return false
        }
        return character == ';' || character == '{' || character == '=' || character == '}'
    }

    fun collapseWhitespace(text: String): String {
        val builder = StringBuilder(text.length)
        var previousWasSpace = false
        for (character in text) {
            if (character.isWhitespace()) {
                if (!previousWasSpace && builder.isNotEmpty()) {
                    builder.append(' ')
                }
                previousWasSpace = true
                continue
            }
            builder.append(character)
            previousWasSpace = false
        }
        return builder.toString().trim()
    }

    /** Reads the identifier that starts at [from], or "" when nothing there is one. */
    fun readIdentifier(text: String, from: Int): String {
        if (from >= text.length || !isIdentifierStart(text[from])) {
            return ""
        }
        var end = from + 1
        while (end < text.length && isIdentifierChar(text[end])) {
            end++
        }
        return text.substring(from, end)
    }

    /**
     * The identifier the offset sits in or next to, or "".
     *
     * Next to counts: a caret parked right after the last character of a name is still on that
     * name as far as the reader is concerned, and that is where it lands after double-clicking.
     */
    fun identifierAround(text: String, offset: Int): String {
        if (offset < 0 || offset > text.length) {
            return ""
        }
        var start = offset
        if (start > 0 && !isIdentifierChar(text.getOrElse(start) { ' ' }) && isIdentifierChar(text[start - 1])) {
            start--
        }
        if (start >= text.length || !isIdentifierChar(text[start])) {
            return ""
        }
        while (start > 0 && isIdentifierChar(text[start - 1])) {
            start--
        }
        var end = start
        while (end < text.length && isIdentifierChar(text[end])) {
            end++
        }
        val identifier = text.substring(start, end)
        if (identifier.isEmpty() || identifier[0].isDigit()) {
            return ""
        }
        return identifier
    }

    fun skipWhitespace(text: String, from: Int): Int {
        var position = from
        while (position < text.length && text[position].isWhitespace()) {
            position++
        }
        return position
    }
}
