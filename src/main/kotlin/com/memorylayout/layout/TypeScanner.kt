package com.memorylayout.layout

/**
 * A type declaration found in a file.
 *
 * @param containerNames the enclosing type names, outermost first; empty for a top-level type
 * @param baseListText what stands after the `:` of the declaration, `where` clauses excluded
 * @param bodyStart offset of the `{` that opens the body
 * @param bodyEnd offset of the matching `}`
 */
data class TypeDeclaration(
    val kind: TypeKind,
    val name: String,
    val genericParameters: List<String>,
    val namespaceName: String,
    val containerNames: List<String>,
    val attributes: List<String>,
    val baseListText: String,
    val declarationOffset: Int,
    val nameOffset: Int,
    val headerEnd: Int,

    /** Offset of the `{` that opens the body, or [NO_BODY] for `record struct Point(int X);`. */
    val bodyStart: Int,
    val bodyEnd: Int,
    val isPartial: Boolean,
) {
    /** `Namespace.Outer.Inner`, without generic arity. */
    val qualifiedName: String
        get() {
            val parts = ArrayList<String>()
            if (namespaceName.isNotEmpty()) {
                parts.add(namespaceName)
            }
            parts.addAll(containerNames)
            parts.add(name)
            return parts.joinToString(".")
        }

    /** `Inner<T>` as a reader would write it, for the window's title. */
    val displayName: String
        get() {
            if (genericParameters.isEmpty()) {
                return name
            }
            return name + "<" + genericParameters.joinToString(", ") + ">"
        }

    val isValueType: Boolean
        get() = kind == TypeKind.STRUCT || kind == TypeKind.RECORD_STRUCT || kind == TypeKind.ENUM

    val hasBody: Boolean
        get() = bodyStart != NO_BODY

    /** The last offset that still counts as inside this declaration. */
    val endOffset: Int
        get() {
            if (hasBody) {
                return bodyEnd
            }
            return headerEnd
        }

    companion object {
        const val NO_BODY = -1
    }
}

/**
 * Finds every type declaration in a file, with its body range and the namespace it sits in.
 *
 * Works on masked text (see [CodeMask]) and nothing else: no PSI, no resolve. In Rider the C#
 * syntax tree lives on the backend and is out of reach from a frontend plugin, which is why this
 * exists at all.
 */
object TypeScanner {

    private val TYPE_KEYWORDS = listOf("struct", "class", "interface", "enum", "record")

    private const val NAMESPACE_KEYWORD = "namespace"

    private const val PARTIAL_KEYWORD = "partial"

    fun scan(masked: String): List<TypeDeclaration> {
        val declarations = ArrayList<TypeDeclaration>()
        scanBlock(masked, 0, masked.length, "", emptyList(), declarations)
        return declarations
    }

    /**
     * The innermost declaration whose body holds [offset]. The declaration line itself counts as
     * inside, so putting the caret on the name of a struct shows that struct.
     */
    fun declarationAt(declarations: List<TypeDeclaration>, offset: Int): TypeDeclaration? {
        var best: TypeDeclaration? = null
        for (declaration in declarations) {
            val inside = offset >= declaration.declarationOffset && offset <= declaration.endOffset
            if (!inside) {
                continue
            }
            if (best == null || declaration.declarationOffset > best.declarationOffset) {
                best = declaration
            }
        }
        return best
    }

    private fun scanBlock(
        masked: String,
        from: Int,
        to: Int,
        namespaceName: String,
        containerNames: List<String>,
        output: MutableList<TypeDeclaration>,
    ) {
        var currentNamespace = namespaceName
        for (statement in SourceText.statementsIn(masked, from, to)) {
            val rawHeader = statement.headerText(masked)
            val (attributes, header) = SourceText.splitAttributes(rawHeader)
            val headerOffset = statement.start + (rawHeader.length - header.length)
            val namespacePart = readNamespaceName(header)
            if (namespacePart != null) {
                if (!statement.hasBody) {
                    // File-scoped: `namespace Foo;` governs everything below it in this block.
                    currentNamespace = combine(currentNamespace, namespacePart)
                    continue
                }
                scanBlock(
                    masked,
                    statement.bodyStart + 1,
                    statement.bodyEnd,
                    combine(currentNamespace, namespacePart),
                    containerNames,
                    output,
                )
                continue
            }
            val declaration = readTypeDeclaration(
                header,
                headerOffset,
                attributes,
                currentNamespace,
                containerNames,
                statement,
            )
            if (declaration == null) {
                continue
            }
            output.add(declaration)
            if (!statement.hasBody) {
                continue
            }
            scanBlock(
                masked,
                statement.bodyStart + 1,
                statement.bodyEnd,
                currentNamespace,
                containerNames + declaration.name,
                output,
            )
        }
    }

    private fun combine(outer: String, inner: String): String {
        if (outer.isEmpty()) {
            return inner
        }
        return "$outer.$inner"
    }

    private fun readNamespaceName(header: String): String? {
        val keywordIndex = SourceText.indexOfWord(header, NAMESPACE_KEYWORD)
        if (keywordIndex != 0) {
            return null
        }
        return header.substring(NAMESPACE_KEYWORD.length).trim()
    }

    private fun readTypeDeclaration(
        header: String,
        headerOffset: Int,
        attributes: List<String>,
        namespaceName: String,
        containerNames: List<String>,
        statement: SourceStatement,
    ): TypeDeclaration? {
        // A keyword search stops at the parameter list: `record` is contextual in C#, so
        // `void Save(Record record)` is a method with an ordinarily named parameter, and a
        // positional record's own keyword always stands in front of its parameter list.
        val parameterListStart = SourceText.indexOfTopLevel(header, '(')
        val searchLimit: Int
        if (parameterListStart < 0) {
            searchLimit = header.length
        } else {
            searchLimit = parameterListStart
        }
        val foundKeyword = findTypeKeyword(header, searchLimit) ?: return null
        val keywordPosition = foundKeyword.first
        val keyword = foundKeyword.second
        var afterKeyword = keywordPosition + keyword.length
        var kind = kindOf(keyword)
        if (keyword == "record") {
            val following = SourceText.readIdentifier(header, SourceText.skipWhitespace(header, afterKeyword))
            if (following == "struct" || following == "class") {
                afterKeyword = SourceText.skipWhitespace(header, afterKeyword) + following.length
                if (following == "struct") {
                    kind = TypeKind.RECORD_STRUCT
                } else {
                    kind = TypeKind.RECORD_CLASS
                }
            }
        }
        val nameStart = SourceText.skipWhitespace(header, afterKeyword)
        val name = SourceText.readIdentifier(header, nameStart)
        if (name.isEmpty()) {
            return null
        }
        val afterName = nameStart + name.length
        val genericParameters = readGenericParameters(header, afterName)
        return TypeDeclaration(
            kind = kind,
            name = name,
            genericParameters = genericParameters,
            namespaceName = namespaceName,
            containerNames = containerNames,
            attributes = attributes,
            baseListText = readBaseList(header, afterName),
            declarationOffset = headerOffset,
            nameOffset = headerOffset + nameStart,
            headerEnd = statement.headerEnd,
            bodyStart = statement.bodyStart,
            bodyEnd = statement.bodyEnd,
            isPartial = SourceText.containsWord(header.substring(0, keywordPosition), PARTIAL_KEYWORD),
        )
    }

    private fun findTypeKeyword(header: String, limit: Int): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        for (keyword in TYPE_KEYWORDS) {
            val index = SourceText.indexOfWord(header, keyword)
            if (index < 0 || index >= limit) {
                continue
            }
            if (best == null || index < best.first) {
                best = Pair(index, keyword)
            }
        }
        return best
    }

    private fun kindOf(keyword: String): TypeKind {
        return when (keyword) {
            "struct" -> TypeKind.STRUCT
            "class" -> TypeKind.CLASS
            "interface" -> TypeKind.INTERFACE
            "enum" -> TypeKind.ENUM
            else -> TypeKind.RECORD_CLASS
        }
    }

    private fun readGenericParameters(header: String, afterName: Int): List<String> {
        val start = SourceText.skipWhitespace(header, afterName)
        if (start >= header.length || header[start] != '<') {
            return emptyList()
        }
        val end = matchingAngle(header, start)
        if (end < 0) {
            return emptyList()
        }
        return SourceText.splitTopLevel(header.substring(start + 1, end), ',')
            .map { part -> SourceText.collapseWhitespace(part) }
            .filter { part -> part.isNotEmpty() }
    }

    private fun matchingAngle(text: String, openOffset: Int): Int {
        var depth = 0
        var position = openOffset
        while (position < text.length) {
            val current = text[position]
            if (current == '<') {
                depth++
            }
            if (current == '>') {
                depth--
                if (depth == 0) {
                    return position
                }
            }
            position++
        }
        return -1
    }

    /** What stands after the `:`, up to a `where` clause. For an enum this is its underlying type. */
    private fun readBaseList(header: String, afterName: Int): String {
        val colon = SourceText.indexOfTopLevel(header, ':', afterName)
        if (colon < 0) {
            return ""
        }
        var tail = header.substring(colon + 1)
        val whereIndex = SourceText.indexOfWord(tail, "where")
        if (whereIndex >= 0) {
            tail = tail.substring(0, whereIndex)
        }
        return SourceText.collapseWhitespace(tail)
    }
}
