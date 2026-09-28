package com.memorylayout.layout

/** What the name under the caret turned out to be, when it is a variable rather than a type. */
sealed class VariableType {

    /**
     * The type the variable was declared with, written as the declaration wrote it, and where --
     * `List<int>` read inside `Game.Spawner` is resolved from there.
     */
    data class Declared(val variableName: String, val typeName: String, val context: LookupContext) : VariableType()

    /** A variable whose declaration does not say its type: `var enemy = Spawn();`. */
    data class Unknown(val variableName: String, val reason: String) : VariableType()
}

/**
 * Finds the declared type of the variable under the caret: a local, a parameter, a `foreach` or
 * pattern variable, a field, a property, a method's return -- and a member access through any of
 * them, `transform.position` or `GetComponent<Rigidbody>().velocity`.
 *
 * This is static typing done on text: the name is looked up where C# would look it up (the
 * enclosing blocks, then the members of the enclosing types and their bases), and the answer is
 * the type written at the declaration. What the text cannot say -- `var` initialised by an
 * arbitrary call -- is reported as unknown rather than guessed.
 */
object VariableTypes {

    private const val MAX_DEPTH = 8

    private const val VAR_KEYWORD = "var"

    private const val THIS_KEYWORD = "this"

    private const val BASE_KEYWORD = "base"

    /** Words that stand before a name without being its type: `return x`, `new Foo`, `out x`. */
    private val NOT_A_TYPE = setOf(
        "return", "new", "in", "out", "ref", "is", "as", "case", "else", "do", "throw", "yield",
        "await", "goto", "using", "namespace", "class", "struct", "interface", "enum", "record",
        "delegate", "operator", "where", "from", "select", "let", "into", "orderby", "join",
        "group", "by", "on", "equals", "when", "and", "or", "not", "typeof", "nameof", "sizeof",
        "default", "checked", "unchecked", "lock", "fixed", "stackalloc", "public", "private",
        "protected", "internal", "static", "readonly", "const", "volatile", "extern", "override",
        "virtual", "abstract", "sealed", "partial", "async", "unsafe", "params", "this", "base",
        "implicit", "explicit", "event", "required", "scoped", "file", "get", "set", "init",
        "add", "remove", "if", "while", "for", "foreach", "switch", "catch", "finally", "try",
    )

    /** Keywords that open a statement whose parentheses scope what they declare. */
    private val SCOPING_STATEMENTS = setOf("for", "foreach", "using", "catch", "fixed")

    /** Collections whose element is their single type argument. */
    private val SINGLE_ELEMENT_COLLECTIONS = setOf(
        "List", "IList", "IReadOnlyList", "ICollection", "IReadOnlyCollection", "IEnumerable",
        "HashSet", "ISet", "SortedSet", "Queue", "Stack", "LinkedList", "Span", "ReadOnlySpan",
        "Memory", "ReadOnlyMemory", "NativeArray", "NativeList", "NativeSlice", "NativeQueue",
        "NativeHashSet", "UnsafeList", "ImmutableArray", "ImmutableList", "Collection",
        "ReadOnlyCollection", "ObservableCollection", "ConcurrentQueue", "ConcurrentStack",
        "ConcurrentBag", "BlockingCollection", "ArraySegment",
    )

    /** Collections of pairs: `foreach` over them yields a `KeyValuePair<K, V>`. */
    private val PAIR_COLLECTIONS = setOf(
        "Dictionary", "IDictionary", "IReadOnlyDictionary", "SortedDictionary", "SortedList",
        "ConcurrentDictionary", "NativeHashMap", "NativeParallelHashMap",
    )

    private const val PAIR_TYPE = "System.Collections.Generic.KeyValuePair"

    private val TYPE_TEXT_CHARACTERS = Regex("^[\\p{L}\\p{N}_.,<>\\[\\]?*: ]+$")

    /**
     * The variable under [offset], or null when the name there is not one -- it is a type, a
     * namespace, a keyword -- and the caller should treat it as a type name instead.
     *
     * @param source the unmasked text, consulted only to tell what a literal initialiser is
     */
    fun at(masked: String, source: String, offset: Int, fileId: String, lookup: TypeLookup): VariableType? {
        val range = identifierRangeAt(masked, offset) ?: return null
        val name = masked.substring(range.first, range.second)
        if (name in NOT_A_TYPE || name == VAR_KEYWORD) {
            return null
        }
        return Resolver(masked, source, fileId, lookup).variableAt(range.first, range.second, 0)
    }

    /**
     * Where the local variable or parameter [name], used at [useOffset], is declared -- the offset
     * of the name in its declaration -- or -1 when it is not a local: a field, a type, unknown.
     */
    fun localDeclarationOffset(masked: String, source: String, name: String, useOffset: Int, fileId: String, lookup: TypeLookup): Int {
        return Resolver(masked, source, fileId, lookup).localDeclarationOffset(name, useOffset)
    }

    /** Start and end of the identifier the offset sits in or right after. */
    fun identifierRangeAt(text: String, offset: Int): Pair<Int, Int>? {
        if (offset < 0 || offset > text.length) {
            return null
        }
        var start = offset
        if (start == text.length || !SourceText.isIdentifierChar(text[start])) {
            if (start == 0 || !SourceText.isIdentifierChar(text[start - 1])) {
                return null
            }
            start--
        }
        while (start > 0 && SourceText.isIdentifierChar(text[start - 1])) {
            start--
        }
        var end = offset
        while (end < text.length && SourceText.isIdentifierChar(text[end])) {
            end++
        }
        if (end <= start || !SourceText.isIdentifierStart(text[start])) {
            return null
        }
        return Pair(start, end)
    }

    /** A type the text named, and where it was named. */
    private class Typed(val typeName: String, val context: LookupContext)

    /** A declaration found for a name: its written type, and what follows it when that is `var`. */
    private class Found(
        val typeText: String,
        val context: LookupContext,
        val initializerStart: Int = NONE,
        val foreachSourceStart: Int = NONE,
        val nameOffset: Int = NONE,
    )

    /** One step of `a.b().c`: a name, and for a call the type arguments it was written with. */
    private class ChainPart(val name: String, val offset: Int, val isCall: Boolean, val typeArguments: List<String>)

    private const val NONE = -1

    private class Resolver(
        private val masked: String,
        private val source: String,
        private val fileId: String,
        private val lookup: TypeLookup,
    ) {
        private val declarations = TypeScanner.scan(masked)

        private val localLookup = SourceTypeLookup(masked, fileId)

        fun variableAt(nameStart: Int, nameEnd: Int, depth: Int): VariableType? {
            val name = masked.substring(nameStart, nameEnd)
            val before = previousCodeIndex(nameStart - 1)
            if (before >= 0 && masked[before] == '.' && !isRangeOperator(before)) {
                val chain = readChainBackward(before - 1) ?: return null
                val owner = typeOfChain(chain, depth + 1) ?: return null
                val member = memberOf(owner, name, callTypeArgumentsAt(nameEnd), depth + 1) ?: return null
                return VariableType.Declared(name, clean(member.typeName), member.context)
            }
            val found = declarationOf(name, nameStart) ?: return null
            return typeOfFound(name, found, depth)
        }

        private fun typeOfFound(name: String, found: Found, depth: Int): VariableType {
            if (found.typeText != VAR_KEYWORD) {
                return VariableType.Declared(name, clean(found.typeText), found.context)
            }
            if (found.foreachSourceStart != NONE) {
                val collection = typeOfExpression(found.foreachSourceStart, depth + 1)
                    ?: return VariableType.Unknown(name, "$name is `var` over a collection whose type the text does not say")
                val element = elementTypeOf(collection.typeName)
                    ?: return VariableType.Unknown(name, "$name is `var` over ${collection.typeName}, whose element type is not known")
                return VariableType.Declared(name, clean(element), collection.context)
            }
            if (found.initializerStart != NONE) {
                val initialized = typeOfExpression(found.initializerStart, depth + 1)
                    ?: return VariableType.Unknown(
                        name,
                        "$name is declared with `var`, and its initializer is not something the text alone can type",
                    )
                return VariableType.Declared(name, clean(initialized.typeName), initialized.context)
            }
            return VariableType.Unknown(name, "$name is declared with `var` and no initializer the text can read")
        }

        // ---- finding the declaration of a name ----

        private fun declarationOf(name: String, useStart: Int): Found? {
            val local = localDeclarationOf(name, useStart)
            if (local != null) {
                return local
            }
            for (enclosing in enclosingTypesAt(useStart)) {
                val owner = Typed(writtenNameOf(enclosing), contextOf(enclosing))
                val member = memberOf(owner, name, emptyList(), 0) ?: continue
                return Found(member.typeName, member.context)
            }
            return null
        }

        fun localDeclarationOffset(name: String, useStart: Int): Int {
            return localDeclarationOf(name, useStart)?.nameOffset ?: NONE
        }

        /** Backwards from the use, the nearest declaration of the name whose scope reaches it. */
        private fun localDeclarationOf(name: String, useStart: Int): Found? {
            var position = useStart
            while (position >= 0) {
                val candidate = lastWordAtOrBefore(name, position)
                if (candidate < 0) {
                    return null
                }
                position = candidate - 1
                val candidateEnd = candidate + name.length
                val typeStart = typeStartBefore(candidate) ?: continue
                val follower = followerOf(candidateEnd) ?: continue
                if (!isInScope(candidate, useStart)) {
                    continue
                }
                val typeText = SourceText.collapseWhitespace(masked.substring(typeStart, candidate))
                val context = contextAt(useStart)
                return when (follower) {
                    Follower.INITIALIZER -> {
                        Found(typeText, context, initializerStart = initializerStartAfter(candidateEnd), nameOffset = candidate)
                    }
                    Follower.FOREACH -> {
                        Found(typeText, context, foreachSourceStart = foreachSourceAfter(candidateEnd), nameOffset = candidate)
                    }
                    else -> {
                        Found(typeText, context, nameOffset = candidate)
                    }
                }
            }
            return null
        }

        private enum class Follower {
            INITIALIZER,
            FOREACH,
            END,
            CALL,
        }

        /** What follows a name decides whether it is being declared: `x =`, `x;`, `x)`, `x in`. */
        private fun followerOf(nameEnd: Int): Follower? {
            val next = nextCodeIndex(nameEnd)
            if (next < 0) {
                return Follower.END
            }
            val character = masked[next]
            return when (character) {
                '=' -> {
                    classifyEquals(next)
                }
                ';', ',', ')', '{', ':' -> {
                    Follower.END
                }
                '(' -> {
                    Follower.CALL
                }
                'i' -> {
                    classifyWordIn(next)
                }
                else -> {
                    null
                }
            }
        }

        private fun classifyEquals(position: Int): Follower? {
            val following = masked.getOrElse(position + 1) { ' ' }
            if (following == '=') {
                return null
            }
            if (following == '>') {
                // `int Count => items.Length;`: an expression-bodied property is declared here.
                return Follower.END
            }
            return Follower.INITIALIZER
        }

        private fun classifyWordIn(position: Int): Follower? {
            if (SourceText.readIdentifier(masked, position) == "in") {
                return Follower.FOREACH
            }
            return null
        }

        /**
         * Where the type in front of a declared name starts, or null when what stands there is not
         * a type: `return x`, `a > x`, `cond ? x : y` all have a name that is not being declared.
         */
        private fun typeStartBefore(nameStart: Int): Int? {
            val end = previousCodeIndex(nameStart - 1)
            if (end < 0) {
                return null
            }
            val start = typeStartEndingAt(masked, end) ?: return null
            val text = masked.substring(start, end + 1)
            if (!TYPE_TEXT_CHARACTERS.matches(SourceText.collapseWhitespace(text))) {
                return null
            }
            val firstWord = SourceText.readIdentifier(text, 0)
            if (firstWord in NOT_A_TYPE) {
                return null
            }
            val beforeType = previousCodeIndex(start - 1)
            if (beforeType >= 0 && masked[beforeType] == '.') {
                // `a.b c` is not C#, but `x = a.b` followed by a name on the next line is a
                // missing semicolon -- either way `b` was not a type here.
                return null
            }
            return start
        }

        private fun isInScope(candidate: Int, use: Int): Boolean {
            if (candidate > use) {
                return false
            }
            val paren = openParenthesisBefore(candidate)
            if (paren >= 0 && parenthesesScopeTheirContents(paren)) {
                val close = matchingParenthesis(paren)
                if (close < 0 || use <= close) {
                    return true
                }
                return isInBodyAfter(close, candidate, use)
            }
            val block = openBraceBefore(candidate)
            if (block < 0) {
                return true
            }
            val blockEnd = SourceText.matchingBrace(masked, block)
            return use > block && (blockEnd < 0 || use < blockEnd)
        }

        /**
         * Parameters of a method, a lambda, a `for` or a `catch` live in what follows the
         * parentheses. An `out var` or a pattern inside a call or an `if` leaks into the enclosing
         * block instead, which is what C# does with them.
         */
        private fun parenthesesScopeTheirContents(paren: Int): Boolean {
            val before = previousCodeIndex(paren - 1)
            if (before < 0) {
                return true
            }
            if (SourceText.isIdentifierChar(masked[before])) {
                val wordStart = wordStartOf(before)
                val word = masked.substring(wordStart, before + 1)
                if (word in SCOPING_STATEMENTS) {
                    return true
                }
                // A declaration `Type Name(` rather than a call `Name(`.
                return typeStartBefore(wordStart) != null
            }
            if (masked[before] == '>') {
                val genericStart = typeStartEndingAt(masked, before) ?: return false
                return typeStartBefore(genericStart) != null
            }
            // A bare `(a, b) =>` lambda, or a tuple.
            val close = matchingParenthesis(paren)
            if (close < 0) {
                return false
            }
            val after = nextCodeIndex(close + 1)
            return after >= 0 && masked.startsWith("=>", after)
        }

        private fun isInBodyAfter(close: Int, candidate: Int, use: Int): Boolean {
            var position = close + 1
            var depth = 0
            while (position < masked.length) {
                val character = masked[position]
                when (character) {
                    '(' -> {
                        depth++
                    }
                    ')' -> {
                        depth--
                    }
                    '{' -> {
                        if (depth == 0) {
                            val end = SourceText.matchingBrace(masked, position)
                            return use > position && (end < 0 || use < end)
                        }
                    }
                    ';' -> {
                        if (depth == 0) {
                            return use < position
                        }
                    }
                    '}' -> {
                        return use < position
                    }
                    else -> {
                        // Anything else belongs to the header: `where`, `: base(...)`, `=>`.
                    }
                }
                position++
            }
            return use > candidate
        }

        // ---- members of types ----

        /** The type of a field, property or method of [owner], looking through its bases. */
        private fun memberOf(owner: Typed, memberName: String, callTypeArguments: List<String>, depth: Int): Typed? {
            if (depth > MAX_DEPTH) {
                return null
            }
            val declared = resolveType(owner) ?: return null
            val arguments = GenericName.bind(
                declared.declaration.genericParameters,
                GenericName.argumentsOf(owner.typeName),
            )
            val parts: List<DeclaredType>
            if (declared.parts.isEmpty()) {
                parts = listOf(declared)
            } else {
                parts = declared.parts
            }
            for (part in parts) {
                val found = memberInPart(part, memberName, callTypeArguments) ?: continue
                return Typed(GenericName.substitute(found, arguments), contextOf(part))
            }
            for (base in baseTypesOf(declared)) {
                val baseName = GenericName.substitute(base, arguments)
                val inBase = memberOf(Typed(baseName, contextOf(declared)), memberName, callTypeArguments, depth + 1)
                if (inBase != null) {
                    return inBase
                }
            }
            return null
        }

        private fun resolveType(owner: Typed): DeclaredType? {
            val written = clean(owner.typeName)
            return lookup.resolve(written, owner.context) ?: localLookup.resolve(written, owner.context)
        }

        private fun memberInPart(part: DeclaredType, memberName: String, callTypeArguments: List<String>): String? {
            for (field in FieldReader.readFields(part.maskedSource, part.declaration)) {
                if (field.name == memberName) {
                    return field.typeName
                }
            }
            if (!part.declaration.hasBody) {
                return null
            }
            val text = part.maskedSource
            val statements = SourceText.statementsIn(text, part.declaration.bodyStart + 1, part.declaration.bodyEnd)
            for (statement in statements) {
                val header = SourceText.splitAttributes(statement.headerText(text)).second
                val memberType = memberTypeInHeader(header, memberName, callTypeArguments) ?: continue
                return memberType
            }
            return null
        }

        /**
         * `public Vector3 position { get ... }`, `public T GetComponent<T>()`, `int Count => ...`:
         * the type in front of the member's name, with a generic method's own parameters bound to
         * what the call wrote.
         */
        private fun memberTypeInHeader(header: String, memberName: String, callTypeArguments: List<String>): String? {
            var from = 0
            while (true) {
                val position = SourceText.indexOfWord(header, memberName, from)
                if (position < 0) {
                    return null
                }
                from = position + memberName.length
                val after = SourceText.skipWhitespace(header, from)
                val next = header.getOrElse(after) { ';' }
                if (next != '(' && next != '<' && next != '=' && next != ';' && after < header.length) {
                    continue
                }
                val typeEnd = previousNonSpace(header, position - 1)
                if (typeEnd < 0) {
                    continue
                }
                val typeStart = typeStartEndingAt(header, typeEnd) ?: continue
                val typeText = SourceText.collapseWhitespace(header.substring(typeStart, typeEnd + 1))
                if (SourceText.readIdentifier(typeText, 0) in NOT_A_TYPE) {
                    continue
                }
                if (next != '<' || callTypeArguments.isEmpty()) {
                    return typeText
                }
                val close = matchingAngle(header, after)
                if (close < 0) {
                    return typeText
                }
                val parameters = GenericName.splitTopLevel(header.substring(after + 1, close))
                return GenericName.substitute(typeText, GenericName.bind(parameters, callTypeArguments))
            }
        }

        private fun baseTypesOf(declared: DeclaredType): List<String> {
            val text = declared.declaration.baseListText
            if (text.isBlank() || declared.declaration.kind == TypeKind.ENUM) {
                return emptyList()
            }
            return GenericName.splitTopLevel(text).map { name -> name.trim() }.filter { name -> name.isNotEmpty() }
        }

        // ---- expressions: initializers, foreach sources, member chains ----

        /** The type of the expression starting at [start], when it is one the text can type. */
        private fun typeOfExpression(start: Int, depth: Int): Typed? {
            if (depth > MAX_DEPTH) {
                return null
            }
            // The mask blanks a string literal out entirely, quotes included, so a literal is
            // looked for in the source before the masked text is read at all.
            var sourcePosition = start
            while (sourcePosition < source.length && source[sourcePosition].isWhitespace()) {
                sourcePosition++
            }
            val literal = literalTypeAt(sourcePosition)
            if (literal != null) {
                return Typed(literal, contextAt(start))
            }
            val position = nextCodeIndex(start)
            if (position < 0) {
                return null
            }
            val context = contextAt(position)
            val word = SourceText.readIdentifier(masked, position)
            when (word) {
                "new" -> {
                    val typeStart = nextCodeIndex(position + word.length)
                    val typeText = readTypeForward(typeStart) ?: return null
                    return Typed(typeText, context)
                }
                "default" -> {
                    val open = nextCodeIndex(position + word.length)
                    if (open < 0 || masked[open] != '(') {
                        return null
                    }
                    val close = matchingParenthesis(open)
                    return Typed(SourceText.collapseWhitespace(masked.substring(open + 1, close)), context)
                }
                "stackalloc" -> {
                    val typeText = readTypeForward(nextCodeIndex(position + word.length)) ?: return null
                    return Typed("Span<$typeText>", context)
                }
                "true", "false" -> {
                    return Typed("bool", context)
                }
                "await" -> {
                    return null
                }
                else -> {
                    // Read on below.
                }
            }
            if (masked[position] == '(') {
                return castTypeAt(position, context)
            }
            if (!SourceText.isIdentifierStart(masked[position])) {
                return null
            }
            val chain = readChainForward(position) ?: return null
            val afterChain = nextCodeIndex(chain.second)
            if (afterChain >= 0 && SourceText.readIdentifier(masked, afterChain) == "as") {
                val typeText = readTypeForward(nextCodeIndex(afterChain + 2)) ?: return null
                return Typed(typeText, context)
            }
            if (afterChain >= 0 && masked[afterChain] !in EXPRESSION_ENDS) {
                // `a + b`, `a ? b : c`, `a[i]`: an operator the text cannot evaluate.
                return null
            }
            return typeOfChain(chain.first, depth + 1)
        }

        private fun castTypeAt(open: Int, context: LookupContext): Typed? {
            val close = matchingParenthesis(open)
            if (close < 0) {
                return null
            }
            val inner = SourceText.collapseWhitespace(masked.substring(open + 1, close))
            if (inner.isEmpty() || !TYPE_TEXT_CHARACTERS.matches(inner)) {
                return null
            }
            val after = nextCodeIndex(close + 1)
            if (after < 0) {
                return null
            }
            val character = masked[after]
            if (!SourceText.isIdentifierStart(character) && character != '(') {
                return null
            }
            return Typed(inner, context)
        }

        /** What a literal is, read from the unmasked text -- the mask blanks every literal out. */
        private fun literalTypeAt(position: Int): String? {
            val character = source.getOrElse(position) { ' ' }
            if (character == '"' || character == '@' || character == '$') {
                return "string"
            }
            if (character == '\'') {
                return "char"
            }
            if (!character.isDigit()) {
                return null
            }
            var end = position
            while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '.' || source[end] == '_')) {
                end++
            }
            val literal = source.substring(position, end).lowercase()
            if (literal.startsWith("0x") || literal.startsWith("0b")) {
                return integerLiteralType(literal)
            }
            if (literal.endsWith("f")) {
                return "float"
            }
            if (literal.endsWith("m")) {
                return "decimal"
            }
            if (literal.endsWith("d") || literal.contains('.') || literal.contains('e')) {
                return "double"
            }
            return integerLiteralType(literal)
        }

        private fun integerLiteralType(literal: String): String {
            if (literal.endsWith("ul") || literal.endsWith("lu")) {
                return "ulong"
            }
            if (literal.endsWith("l")) {
                return "long"
            }
            if (literal.endsWith("u")) {
                return "uint"
            }
            return "int"
        }

        /** `a.b().c<T>()`: the type the whole chain evaluates to, one member at a time. */
        private fun typeOfChain(chain: List<ChainPart>, depth: Int): Typed? {
            if (chain.isEmpty() || depth > MAX_DEPTH) {
                return null
            }
            val first = chain.first()
            var current: Typed = typeOfChainHead(first, depth) ?: return null
            for (part in chain.drop(1)) {
                current = memberOf(current, part.name, part.typeArguments, depth + 1) ?: return null
            }
            return current
        }

        private fun typeOfChainHead(head: ChainPart, depth: Int): Typed? {
            when (head.name) {
                THIS_KEYWORD -> {
                    val enclosing = enclosingTypesAt(head.offset).firstOrNull() ?: return null
                    return Typed(writtenNameOf(enclosing), contextOf(enclosing))
                }
                BASE_KEYWORD -> {
                    val enclosing = enclosingTypesAt(head.offset).firstOrNull() ?: return null
                    val base = GenericName.splitTopLevel(enclosing.baseListText).firstOrNull() ?: return null
                    return Typed(base.trim(), contextOf(enclosing))
                }
                else -> {
                    // Read on below.
                }
            }
            if (head.isCall) {
                // `GetComponent<T>()` on the enclosing type or one of its bases.
                for (enclosing in enclosingTypesAt(head.offset)) {
                    val owner = Typed(writtenNameOf(enclosing), contextOf(enclosing))
                    val member = memberOf(owner, head.name, head.typeArguments, depth + 1)
                    if (member != null) {
                        return member
                    }
                }
                return null
            }
            val variable = variableAt(head.offset, head.offset + head.name.length, depth + 1)
            if (variable is VariableType.Declared) {
                return Typed(variable.typeName, variable.context)
            }
            if (variable is VariableType.Unknown) {
                return null
            }
            // Not a variable: a type name, and what follows is a static member of it.
            return Typed(head.name, contextAt(head.offset))
        }

        /** The chain ending at [end], read backwards: `this.body.velocity` gives three parts. */
        private fun readChainBackward(end: Int): List<ChainPart>? {
            val parts = ArrayList<ChainPart>()
            var position = previousCodeIndex(end)
            while (position >= 0) {
                var isCall = false
                var typeArguments = emptyList<String>()
                if (masked[position] == ')') {
                    val open = matchingParenthesisBackward(position)
                    if (open < 0) {
                        return null
                    }
                    isCall = true
                    position = previousCodeIndex(open - 1)
                    if (position < 0) {
                        return null
                    }
                }
                if (masked[position] == '>') {
                    val open = matchingAngleBackward(masked, position)
                    if (open < 0) {
                        return null
                    }
                    typeArguments = GenericName.splitTopLevel(masked.substring(open + 1, position))
                    position = previousCodeIndex(open - 1)
                    if (position < 0) {
                        return null
                    }
                }
                if (!SourceText.isIdentifierChar(masked[position])) {
                    return null
                }
                val wordStart = wordStartOf(position)
                parts.add(ChainPart(masked.substring(wordStart, position + 1), wordStart, isCall, typeArguments))
                val before = previousCodeIndex(wordStart - 1)
                if (before < 0 || masked[before] != '.' || isRangeOperator(before)) {
                    break
                }
                position = previousCodeIndex(before - 1)
            }
            if (parts.isEmpty()) {
                return null
            }
            parts.reverse()
            return parts
        }

        /** The chain starting at [start], and where it ends. */
        private fun readChainForward(start: Int): Pair<List<ChainPart>, Int>? {
            val parts = ArrayList<ChainPart>()
            var position = start
            while (position >= 0 && position < masked.length && SourceText.isIdentifierStart(masked[position])) {
                val name = SourceText.readIdentifier(masked, position)
                val nameStart = position
                position += name.length
                var typeArguments = emptyList<String>()
                var isCall = false
                var next = nextCodeIndex(position)
                if (next >= 0 && masked[next] == '<') {
                    val close = matchingAngle(masked, next)
                    val afterClose = nextCodeIndex(close + 1)
                    if (close > 0 && afterClose >= 0 && masked[afterClose] == '(') {
                        typeArguments = GenericName.splitTopLevel(masked.substring(next + 1, close))
                        next = afterClose
                    }
                }
                if (next >= 0 && masked[next] == '(') {
                    val close = matchingParenthesis(next)
                    if (close < 0) {
                        return null
                    }
                    isCall = true
                    position = close + 1
                    next = nextCodeIndex(position)
                }
                parts.add(ChainPart(name, nameStart, isCall, typeArguments))
                if (next < 0 || masked[next] != '.' || isRangeOperator(next)) {
                    break
                }
                position = nextCodeIndex(next + 1)
            }
            if (parts.isEmpty()) {
                return null
            }
            return Pair(parts, position)
        }

        /** Type arguments a call right after the name was written with: `GetComponent<Rigidbody>()`. */
        private fun callTypeArgumentsAt(nameEnd: Int): List<String> {
            val next = nextCodeIndex(nameEnd)
            if (next < 0 || masked[next] != '<') {
                return emptyList()
            }
            val close = matchingAngle(masked, next)
            if (close < 0) {
                return emptyList()
            }
            val afterClose = nextCodeIndex(close + 1)
            if (afterClose < 0 || masked[afterClose] != '(') {
                return emptyList()
            }
            return GenericName.splitTopLevel(masked.substring(next + 1, close))
        }

        /** `Foo`, `Game.Foo<int, Bar>` read forwards; stops before `(`, `{` or `[`. */
        private fun readTypeForward(start: Int): String? {
            if (start < 0 || start >= masked.length || !SourceText.isIdentifierStart(masked[start])) {
                return null
            }
            var position = start
            while (position < masked.length) {
                val name = SourceText.readIdentifier(masked, position)
                if (name.isEmpty()) {
                    return null
                }
                position += name.length
                if (position < masked.length && masked[position] == '<') {
                    val close = matchingAngle(masked, position)
                    if (close < 0) {
                        return null
                    }
                    position = close + 1
                }
                if (position < masked.length && masked[position] == '.') {
                    position++
                    continue
                }
                break
            }
            return SourceText.collapseWhitespace(masked.substring(start, position))
        }

        private fun initializerStartAfter(nameEnd: Int): Int {
            val equals = nextCodeIndex(nameEnd)
            return equals + 1
        }

        private fun foreachSourceAfter(nameEnd: Int): Int {
            val inKeyword = nextCodeIndex(nameEnd)
            return inKeyword + 2
        }

        // ---- context ----

        private fun enclosingTypesAt(offset: Int): List<TypeDeclaration> {
            return declarations
                .filter { declaration -> offset >= declaration.declarationOffset && offset <= declaration.endOffset }
                .sortedByDescending { declaration -> declaration.declarationOffset }
        }

        private fun contextAt(offset: Int): LookupContext {
            val enclosing = enclosingTypesAt(offset).firstOrNull() ?: return LookupContext("", emptyList(), fileId)
            return contextOf(enclosing)
        }

        private fun contextOf(declaration: TypeDeclaration): LookupContext {
            return LookupContext(declaration.namespaceName, declaration.containerNames + declaration.name, fileId)
        }

        private fun contextOf(declared: DeclaredType): LookupContext {
            val declaration = declared.declaration
            return LookupContext(declaration.namespaceName, declaration.containerNames + declaration.name, declared.fileId)
        }

        /** `Game.Box<T>`: qualified, with its own parameters, so it resolves to itself. */
        private fun writtenNameOf(declaration: TypeDeclaration): String {
            if (declaration.genericParameters.isEmpty()) {
                return declaration.qualifiedName
            }
            return declaration.qualifiedName + "<" + declaration.genericParameters.joinToString(", ") + ">"
        }

        // ---- scanning ----

        private fun previousCodeIndex(from: Int): Int {
            var position = from
            while (position >= 0 && masked[position].isWhitespace()) {
                position--
            }
            return position
        }

        private fun nextCodeIndex(from: Int): Int {
            if (from < 0) {
                return NONE
            }
            var position = from
            while (position < masked.length && masked[position].isWhitespace()) {
                position++
            }
            if (position >= masked.length) {
                return NONE
            }
            return position
        }

        private fun isRangeOperator(dot: Int): Boolean {
            return masked.getOrElse(dot - 1) { ' ' } == '.' || masked.getOrElse(dot + 1) { ' ' } == '.'
        }

        private fun wordStartOf(lastCharacter: Int): Int {
            var start = lastCharacter
            while (start > 0 && SourceText.isIdentifierChar(masked[start - 1])) {
                start--
            }
            return start
        }

        private fun lastWordAtOrBefore(word: String, from: Int): Int {
            var position = minOf(from, masked.length - word.length)
            while (position >= 0) {
                if (masked.startsWith(word, position) && isWordBoundary(position, position + word.length)) {
                    return position
                }
                position--
            }
            return NONE
        }

        private fun isWordBoundary(start: Int, end: Int): Boolean {
            val before = masked.getOrElse(start - 1) { ' ' }
            val after = masked.getOrElse(end) { ' ' }
            return !SourceText.isIdentifierChar(before) && before != '@' && !SourceText.isIdentifierChar(after)
        }

        /** The innermost `(` still open at [position], not looking past the statement it is in. */
        private fun openParenthesisBefore(position: Int): Int {
            var depth = 0
            var index = position - 1
            while (index >= 0) {
                when (masked[index]) {
                    ')' -> {
                        depth++
                    }
                    '(' -> {
                        if (depth == 0) {
                            return index
                        }
                        depth--
                    }
                    '{', '}', ';' -> {
                        return NONE
                    }
                    else -> {
                        // Part of the statement.
                    }
                }
                index--
            }
            return NONE
        }

        private fun openBraceBefore(position: Int): Int {
            var depth = 0
            var index = position - 1
            while (index >= 0) {
                when (masked[index]) {
                    '}' -> {
                        depth++
                    }
                    '{' -> {
                        if (depth == 0) {
                            return index
                        }
                        depth--
                    }
                    else -> {
                        // Inside the block.
                    }
                }
                index--
            }
            return NONE
        }

        private fun matchingParenthesis(open: Int): Int {
            var depth = 0
            var index = open
            while (index < masked.length) {
                if (masked[index] == '(') {
                    depth++
                }
                if (masked[index] == ')') {
                    depth--
                    if (depth == 0) {
                        return index
                    }
                }
                index++
            }
            return NONE
        }

        private fun matchingParenthesisBackward(close: Int): Int {
            var depth = 0
            var index = close
            while (index >= 0) {
                if (masked[index] == ')') {
                    depth++
                }
                if (masked[index] == '(') {
                    depth--
                    if (depth == 0) {
                        return index
                    }
                }
                index--
            }
            return NONE
        }
    }

    private val EXPRESSION_ENDS = setOf(';', ',', ')', '}', ']')

    /** The element a `foreach` over this type yields, or null when it is not a known collection. */
    fun elementTypeOf(collectionType: String): String? {
        val written = SourceText.collapseWhitespace(collectionType).trimEnd('?')
        if (written.endsWith("]")) {
            val open = written.lastIndexOf('[')
            return written.substring(0, open).trim()
        }
        if (written == "string") {
            return "char"
        }
        val simpleName = TypeMatching.simpleName(written)
        val arguments = GenericName.argumentsOf(written)
        if (simpleName in SINGLE_ELEMENT_COLLECTIONS && arguments.size == 1) {
            return arguments.first()
        }
        if (simpleName in PAIR_COLLECTIONS && arguments.size == 2) {
            return "$PAIR_TYPE<${arguments[0]}, ${arguments[1]}>"
        }
        return null
    }

    /**
     * The type as the window should open it: an array or a pointer shows its element, `T?` shows
     * `T`, and `ref`/`readonly` in front of a local's type are not part of it.
     */
    fun clean(typeName: String): String {
        var text = SourceText.collapseWhitespace(typeName)
        var changed = true
        while (changed) {
            changed = false
            for (prefix in listOf("ref ", "readonly ", "scoped ", "in ", "out ", "params ")) {
                if (text.startsWith(prefix)) {
                    text = text.substring(prefix.length).trim()
                    changed = true
                }
            }
            if (text.endsWith("?") || text.endsWith("*")) {
                text = text.dropLast(1).trim()
                changed = true
            }
            if (text.endsWith("]")) {
                val open = text.lastIndexOf('[')
                if (open > 0) {
                    text = text.substring(0, open).trim()
                    changed = true
                }
            }
        }
        return text
    }

    /**
     * Where a type that ends at [end] (inclusive) starts: `Dictionary<int, Foo>[]?` read from the
     * right, or null when the text there is not the end of a type.
     */
    fun typeStartEndingAt(text: String, end: Int): Int? {
        var position = end
        while (position >= 0 && (text[position] == '?' || text[position] == '*')) {
            // `int?` and `byte*` are written attached; `flag ? a : b` and `a * b` are not.
            if (position == 0 || text[position - 1].isWhitespace()) {
                return null
            }
            position--
        }
        while (position >= 0 && text[position] == ']') {
            val open = text.lastIndexOf('[', position)
            if (open < 0) {
                return null
            }
            val inside = text.substring(open + 1, position)
            if (inside.any { character -> character != ',' && !character.isWhitespace() }) {
                return null
            }
            position = previousNonSpace(text, open - 1)
            while (position >= 0 && text[position] == '?') {
                position = previousNonSpace(text, position - 1)
            }
        }
        if (position < 0) {
            return null
        }
        if (text[position] == '>') {
            val open = matchingAngleBackward(text, position)
            if (open < 0) {
                return null
            }
            position = previousNonSpace(text, open - 1)
            if (position < 0) {
                return null
            }
        }
        if (!SourceText.isIdentifierChar(text[position])) {
            return null
        }
        var start = position
        while (true) {
            while (start > 0 && SourceText.isIdentifierChar(text[start - 1])) {
                start--
            }
            if (!SourceText.isIdentifierStart(text[start])) {
                return null
            }
            // `System.Guid` and `global::Game.Foo` keep going; a space ends the type.
            if (start >= 2 && text[start - 1] == ':' && text[start - 2] == ':') {
                start -= 3
                if (start < 0 || !SourceText.isIdentifierChar(text[start])) {
                    return null
                }
                continue
            }
            if (start >= 1 && text[start - 1] == '.') {
                start -= 2
                if (start < 0 || !SourceText.isIdentifierChar(text[start])) {
                    return null
                }
                continue
            }
            return start
        }
    }

    private fun previousNonSpace(text: String, from: Int): Int {
        var position = from
        while (position >= 0 && text[position].isWhitespace()) {
            position--
        }
        return position
    }

    fun matchingAngle(text: String, open: Int): Int {
        var depth = 0
        var index = open
        while (index < text.length) {
            when (text[index]) {
                '<' -> {
                    depth++
                }
                '>' -> {
                    depth--
                    if (depth == 0) {
                        return index
                    }
                }
                ';', '{', '}', '=' -> {
                    return NONE
                }
                else -> {
                    // Inside the angle brackets.
                }
            }
            index++
        }
        return NONE
    }

    private fun matchingAngleBackward(text: String, close: Int): Int {
        var depth = 0
        var index = close
        while (index >= 0) {
            when (text[index]) {
                '>' -> {
                    depth++
                }
                '<' -> {
                    depth--
                    if (depth == 0) {
                        return index
                    }
                }
                ';', '{', '}', '=', '(', ')', '&', '|' -> {
                    // `a < b && c > d`: a comparison, not a generic.
                    return NONE
                }
                else -> {
                    // Inside the angle brackets.
                }
            }
            index--
        }
        return NONE
    }
}
