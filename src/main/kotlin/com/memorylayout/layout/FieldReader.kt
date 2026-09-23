package com.memorylayout.layout

/**
 * Reads the instance fields of a declaration out of masked text.
 *
 * What is deliberately dropped: `const` and `static` members, methods, indexers, nested types,
 * expression-bodied members and properties with real accessor bodies -- none of them take space in
 * an instance. What is deliberately kept: auto-properties, because the compiler gives each one a
 * backing field and that field is as real as any other.
 */
object FieldReader {

    private val DROPPED_MODIFIERS = setOf(
        "public", "private", "protected", "internal", "file",
        "static", "readonly", "volatile", "unsafe", "new", "required", "extern",
        "ref", "partial", "sealed", "override", "virtual", "abstract", "async", "event",
    )

    /**
     * A member carrying one of these is not part of an instance at all.
     *
     * `event` is not among them: a field-like `event Action Died;` is a delegate field the
     * compiler writes, eight bytes in every instance. Only an event with `add` and `remove`
     * stores nothing, and that one has a body, which is what drops it.
     */
    private val EXCLUDING_MODIFIERS = setOf("const", "static")

    /**
     * A property with one of these has no backing field even when its accessors look automatic:
     * an abstract or extern one has no implementation here, and a partial one's accessors are
     * written in its other declaration.
     */
    private val NO_STORAGE_PROPERTY_MODIFIERS = setOf("abstract", "extern", "partial")

    private const val INDEXER_KEYWORD = "this"

    private val ACCESSOR_NAMES = setOf("get", "set", "init")

    private const val FIXED_KEYWORD = "fixed"

    private const val DEFAULT_ENUM_UNDERLYING_TYPE = "int"

    /**
     * The fields of a type as the compiler sees it: every part of a partial type, in the order of
     * [DeclaredType.parts], each field carrying the file it was read from.
     */
    fun readFields(type: DeclaredType): List<FieldDeclaration> {
        if (type.parts.isEmpty()) {
            return readFields(type.maskedSource, type.declaration).map { field -> field.copy(fileId = type.fileId) }
        }
        val fields = ArrayList<FieldDeclaration>()
        for (part in type.parts) {
            for (field in readFields(part.maskedSource, part.declaration)) {
                fields.add(field.copy(fileId = part.fileId))
            }
        }
        return fields
    }

    fun readFields(masked: String, declaration: TypeDeclaration): List<FieldDeclaration> {
        if (declaration.kind == TypeKind.INTERFACE || declaration.kind == TypeKind.ENUM) {
            return emptyList()
        }
        val fields = ArrayList<FieldDeclaration>()
        for (statement in bodyStatements(masked, declaration)) {
            val rawHeader = statement.headerText(masked)
            val (attributes, header) = SourceText.splitAttributes(rawHeader)
            if (statement.hasBody) {
                val property = readAutoProperty(masked, statement, header, attributes)
                if (property != null) {
                    fields.add(property)
                }
                continue
            }
            fields.addAll(readFieldStatement(header, attributes, statement.start))
        }
        addPositionalRecordParameters(masked, declaration, fields)
        return fields
    }

    private fun bodyStatements(masked: String, declaration: TypeDeclaration): List<SourceStatement> {
        if (!declaration.hasBody) {
            return emptyList()
        }
        return SourceText.statementsIn(masked, declaration.bodyStart + 1, declaration.bodyEnd)
    }

    /**
     * A positional record's parameters become fields: `record struct Point(int X, int Y)` has two.
     * They are read from the declaration header rather than the body, which is where every other
     * field comes from.
     */
    private fun addPositionalRecordParameters(
        masked: String,
        declaration: TypeDeclaration,
        fields: MutableList<FieldDeclaration>,
    ) {
        val isRecord = declaration.kind == TypeKind.RECORD_STRUCT || declaration.kind == TypeKind.RECORD_CLASS
        if (!isRecord) {
            return
        }
        val headerEnd: Int
        if (declaration.hasBody) {
            headerEnd = declaration.bodyStart
        } else {
            headerEnd = declaration.headerEnd
        }
        val header = masked.substring(declaration.declarationOffset, headerEnd)
        val parameterListStart = SourceText.indexOfTopLevel(header, '(')
        if (parameterListStart < 0) {
            return
        }
        val parameterListEnd = matchingParenthesis(header, parameterListStart)
        if (parameterListEnd < 0) {
            return
        }
        val parameters = SourceText.splitTopLevel(header.substring(parameterListStart + 1, parameterListEnd), ',')
        val positional = ArrayList<FieldDeclaration>()
        for (parameter in parameters) {
            val declarator = SourceText.collapseWhitespace(parameter)
            if (declarator.isEmpty()) {
                continue
            }
            val parsed = parseDeclarator(declarator, declaration.declarationOffset) ?: continue
            positional.add(parsed.copy(isAutoProperty = true))
        }
        // The compiler emits the positional backing fields before anything written in the body.
        fields.addAll(0, positional)
    }

    private fun matchingParenthesis(text: String, openOffset: Int): Int {
        var depth = 0
        var position = openOffset
        while (position < text.length) {
            val current = text[position]
            if (current == '(') {
                depth++
            }
            if (current == ')') {
                depth--
                if (depth == 0) {
                    return position
                }
            }
            position++
        }
        return -1
    }

    private fun readAutoProperty(
        masked: String,
        statement: SourceStatement,
        header: String,
        attributes: List<String>,
    ): FieldDeclaration? {
        val collapsed = SourceText.collapseWhitespace(header)
        if (collapsed.isEmpty()) {
            return null
        }
        if (hasExcludingModifier(collapsed)) {
            return null
        }
        // A `(` means a method; a type keyword means a nested type. Neither has a backing field
        // of its own.
        if (SourceText.indexOfTopLevel(collapsed, '(') >= 0) {
            return null
        }
        // An indexer is `this[...]`. Any other `[` is an array type -- `int[] Values { get; }` is
        // an auto-property like any other, and was once dropped for the bracket alone.
        if (SourceText.containsWord(collapsed, INDEXER_KEYWORD) && collapsed.contains('[')) {
            return null
        }
        for (modifier in NO_STORAGE_PROPERTY_MODIFIERS) {
            if (SourceText.containsWord(collapsed, modifier)) {
                return null
            }
        }
        if (looksLikeTypeDeclaration(collapsed)) {
            return null
        }
        if (!hasOnlyAutoAccessors(masked, statement)) {
            return null
        }
        val declarator = stripModifiers(collapsed)
        val parsed = parseDeclarator(declarator, statement.start) ?: return null
        return parsed.copy(
            isAutoProperty = true,
            explicitOffset = readFieldOffset(attributes),
        )
    }

    private fun hasOnlyAutoAccessors(masked: String, statement: SourceStatement): Boolean {
        val accessors = SourceText.statementsIn(masked, statement.bodyStart + 1, statement.bodyEnd)
        if (accessors.isEmpty()) {
            return false
        }
        for (accessor in accessors) {
            if (accessor.hasBody) {
                return false
            }
            val text = SourceText.collapseWhitespace(accessor.headerText(masked))
            val (_, withoutAttributes) = SourceText.splitAttributes(text)
            val words = withoutAttributes.split(' ').filter { word -> word.isNotEmpty() }
            val name = words.lastOrNull() ?: return false
            if (name !in ACCESSOR_NAMES) {
                return false
            }
            if (withoutAttributes.contains("=>")) {
                return false
            }
        }
        return true
    }

    private fun readFieldStatement(
        header: String,
        attributes: List<String>,
        declarationOffset: Int,
    ): List<FieldDeclaration> {
        var text = SourceText.collapseWhitespace(header)
        if (text.isEmpty()) {
            return emptyList()
        }
        if (text.contains("=>")) {
            // An expression-bodied member: computed on every read, stored nowhere.
            return emptyList()
        }
        if (hasExcludingModifier(text)) {
            return emptyList()
        }
        if (looksLikeTypeDeclaration(text)) {
            return emptyList()
        }
        if (SourceText.indexOfTopLevel(text, '(') >= 0) {
            // A method with no body of its own: abstract, extern or a delegate declaration.
            return emptyList()
        }
        val assignment = SourceText.indexOfTopLevel(text, '=')
        if (assignment >= 0) {
            text = text.substring(0, assignment).trim()
        }
        val isFixedBuffer = SourceText.containsWord(text, FIXED_KEYWORD)
        text = stripModifiers(text)
        val declarators = SourceText.splitTopLevel(text, ',')
        val first = parseDeclarator(SourceText.collapseWhitespace(declarators.first()), declarationOffset)
            ?: return emptyList()
        if (!isFixedBuffer && first.fixedBufferLength > 0) {
            // `int values[4]` is not C#; only a `fixed` buffer writes a length after the name.
            return emptyList()
        }
        val fields = ArrayList<FieldDeclaration>()
        val explicitOffset = readFieldOffset(attributes)
        fields.add(first.copy(explicitOffset = explicitOffset))
        for (index in 1 until declarators.size) {
            val declarator = SourceText.collapseWhitespace(declarators[index])
            if (declarator.isEmpty()) {
                continue
            }
            val name = readDeclaratorName(declarator) ?: continue
            fields.add(
                first.copy(
                    name = name.first,
                    fixedBufferLength = name.second,
                    explicitOffset = explicitOffset,
                )
            )
        }
        return fields
    }

    private fun hasExcludingModifier(text: String): Boolean {
        for (modifier in EXCLUDING_MODIFIERS) {
            if (SourceText.containsWord(text, modifier)) {
                return true
            }
        }
        return false
    }

    private fun looksLikeTypeDeclaration(text: String): Boolean {
        val keywords = listOf("struct", "class", "interface", "enum", "delegate")
        for (keyword in keywords) {
            if (SourceText.containsWord(text, keyword)) {
                return true
            }
        }
        return false
    }

    private fun stripModifiers(text: String): String {
        var rest = text.trim()
        while (true) {
            val space = rest.indexOf(' ')
            if (space < 0) {
                return rest
            }
            val word = rest.substring(0, space)
            val isModifier = word in DROPPED_MODIFIERS || word == FIXED_KEYWORD
            if (!isModifier) {
                return rest
            }
            rest = rest.substring(space + 1).trim()
        }
    }

    /** `int value`, `Dictionary<int, string> map`, `byte* pointer`, `byte buffer[16]`. */
    private fun parseDeclarator(declarator: String, declarationOffset: Int): FieldDeclaration? {
        val name = readDeclaratorName(declarator) ?: return null
        val nameStart = declarator.lastIndexOf(name.first)
        if (nameStart <= 0) {
            return null
        }
        val typeName = SourceText.collapseWhitespace(declarator.substring(0, nameStart))
        if (typeName.isEmpty()) {
            return null
        }
        return FieldDeclaration(
            typeName = typeName,
            name = name.first,
            declarationOffset = declarationOffset,
            fixedBufferLength = name.second,
        )
    }

    /** The declared name and, for a `fixed` buffer, its element count. */
    private fun readDeclaratorName(declarator: String): Pair<String, Int>? {
        var text = declarator.trim()
        var bufferLength = 0
        if (text.endsWith("]")) {
            val open = text.lastIndexOf('[')
            if (open < 0) {
                return null
            }
            val inside = text.substring(open + 1, text.length - 1).trim()
            val parsed = inside.toIntOrNull()
            if (parsed == null) {
                // `int[] values`: the brackets belong to the type, so there is no name after them.
                return null
            }
            bufferLength = parsed
            text = text.substring(0, open).trim()
        }
        var end = text.length
        while (end > 0 && !SourceText.isIdentifierChar(text[end - 1])) {
            end--
        }
        var start = end
        while (start > 0 && SourceText.isIdentifierChar(text[start - 1])) {
            start--
        }
        if (start >= end) {
            return null
        }
        return Pair(text.substring(start, end), bufferLength)
    }

    private fun readFieldOffset(attributes: List<String>): Int {
        val attribute = attributes.firstOrNull { text -> SourceText.containsWord(text, "FieldOffset") }
            ?: return FieldDeclaration.NO_EXPLICIT_OFFSET
        val open = attribute.indexOf('(')
        val close = attribute.lastIndexOf(')')
        if (open < 0 || close <= open) {
            return FieldDeclaration.NO_EXPLICIT_OFFSET
        }
        return attribute.substring(open + 1, close).trim().toIntOrNull()
            ?: FieldDeclaration.NO_EXPLICIT_OFFSET
    }

    /**
     * What `[StructLayout]` asked for, or the default for this kind of declaration: sequential for
     * a struct, auto for a class.
     */
    fun readLayoutAttribute(declaration: TypeDeclaration): StructLayoutAttribute {
        val defaultKind: DeclaredLayoutKind
        if (declaration.isValueType) {
            defaultKind = DeclaredLayoutKind.SEQUENTIAL
        } else {
            defaultKind = DeclaredLayoutKind.AUTO
        }
        val attribute = declaration.attributes.firstOrNull { text -> SourceText.containsWord(text, "StructLayout") }
            ?: return StructLayoutAttribute(defaultKind)
        var kind = defaultKind
        if (SourceText.containsWord(attribute, "Sequential")) {
            kind = DeclaredLayoutKind.SEQUENTIAL
        }
        if (SourceText.containsWord(attribute, "Explicit")) {
            kind = DeclaredLayoutKind.EXPLICIT
        }
        if (SourceText.containsWord(attribute, "Auto")) {
            kind = DeclaredLayoutKind.AUTO
        }
        return StructLayoutAttribute(
            kind = kind,
            pack = readNamedArgument(attribute, "Pack") ?: StructLayoutAttribute.PACK_UNSET,
            declaredSize = readNamedArgument(attribute, "Size") ?: StructLayoutAttribute.SIZE_UNSET,
        )
    }

    private fun readNamedArgument(attribute: String, name: String): Int? {
        val nameIndex = SourceText.indexOfWord(attribute, name)
        if (nameIndex < 0) {
            return null
        }
        val equals = attribute.indexOf('=', nameIndex)
        if (equals < 0) {
            return null
        }
        var end = equals + 1
        while (end < attribute.length && attribute[end] != ',' && attribute[end] != ')') {
            end++
        }
        return attribute.substring(equals + 1, end).trim().toIntOrNull()
    }

    /** The underlying type of an enum: what stands after its `:`, or `int`. */
    fun readEnumUnderlyingType(declaration: TypeDeclaration): String {
        if (declaration.baseListText.isEmpty()) {
            return DEFAULT_ENUM_UNDERLYING_TYPE
        }
        return declaration.baseListText.trim()
    }
}
