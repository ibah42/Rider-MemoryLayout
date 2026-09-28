package com.memorylayout.layout

/** A local or parameter a lambda uses from outside itself, and where it is declared. */
data class CapturedVariable(val name: String, val declarationOffset: Int)

/**
 * A lambda or anonymous method, read from source: where it is, what it captures, and the names the
 * compiler will have given to what it generates for it.
 *
 * @param containerQualifiedName the type whose method the lambda sits in; its closure class is a
 *   nested type of this one
 * @param memberName the member as the compiler names it: `Update`, `.ctor` for a constructor or a
 *   field initializer, `get_Health` for a property getter. Lambda methods are named after it --
 *   `<Update>b__0` -- which is how the closure class is found again in the assembly.
 * @param usesInstance whether it reaches `this`: through `this`, `base`, or an instance member
 */
data class LambdaSite(
    val start: Int,
    val bodyStart: Int,
    val bodyEnd: Int,
    val parameterNames: List<String>,
    val containerQualifiedName: String,
    val containerNameOffset: Int,
    val memberName: String,
    val captured: List<CapturedVariable>,
    val usesInstance: Boolean,
)

/**
 * Finds the lambda at the caret and what it captures.
 *
 * The caret is on the `=>` of a lambda, or on the `delegate` of an anonymous method -- the one
 * place that belongs to the lambda itself rather than to a name inside it, so the caret on a
 * variable keeps meaning "the type of this variable".
 *
 * Capture is decided the way the compiler decides it: a name used in the body that resolves to a
 * local or parameter declared outside the lambda but inside the enclosing member is captured; an
 * instance member, `this` or `base` means the lambda needs `this`.
 */
object Lambdas {

    private const val ARROW = "=>"

    private const val DELEGATE_KEYWORD = "delegate"

    private const val SWITCH_KEYWORD = "switch"

    private const val CONST_KEYWORD = "const"

    private const val NONE = -1

    private const val CONSTRUCTOR_NAME = ".ctor"

    private const val STATIC_CONSTRUCTOR_NAME = ".cctor"

    private const val GETTER_PREFIX = "get_"

    private const val SETTER_PREFIX = "set_"

    /** Words that stand before a lambda's parameter without being a type: `async x => ...`. */
    private val LAMBDA_PREFIXES = setOf(
        "async", "static", "return", "await", "in", "out", "ref", "new", "case", "yield", "else",
        "is", "as", "when", "select", "where", "let",
    )

    private val INSTANCE_WORDS = setOf("this", "base")

    private val NOT_NAMES = setOf(
        "new", "return", "var", "true", "false", "null", "default", "typeof", "nameof", "sizeof",
        "is", "as", "in", "out", "ref", "await", "async", "static", "if", "else", "for", "foreach",
        "while", "do", "switch", "case", "break", "continue", "throw", "try", "catch", "finally",
        "using", "lock", "yield", "checked", "unchecked", "delegate", "value", "get", "set",
        "stackalloc", "when", "and", "or", "not", "with", "fixed", "goto",
    )

    fun at(masked: String, source: String, offset: Int, fileId: String, lookup: TypeLookup): LambdaSite? {
        val shape = shapeAt(masked, offset) ?: return null
        val declarations = TypeScanner.scan(masked)
        val container = TypeScanner.declarationAt(declarations, shape.start) ?: return null
        val member = memberStatementAt(masked, container, shape.start) ?: return null
        val memberName = compilerMemberName(masked, container, member, shape.start)
        val instanceMembers = instanceMemberNamesOf(masked, container)
        val innerParameters = lambdaParametersIn(masked, shape.bodyStart, shape.bodyEnd)
        val captured = LinkedHashMap<String, CapturedVariable>()
        var usesInstance = false
        var position = shape.bodyStart
        while (position < shape.bodyEnd) {
            if (!SourceText.isIdentifierStart(masked[position]) || isIdentifierContinuation(masked, position)) {
                position++
                continue
            }
            val name = SourceText.readIdentifier(masked, position)
            val nameStart = position
            position += name.length
            if (name in INSTANCE_WORDS) {
                usesInstance = true
                continue
            }
            if (name in NOT_NAMES || name in shape.parameterNames || name in innerParameters) {
                continue
            }
            if (isMemberAccess(masked, nameStart) || isNamedArgument(masked, position)) {
                continue
            }
            val declaration = VariableTypes.localDeclarationOffset(masked, source, name, nameStart, fileId, lookup)
            if (declaration != NONE && declaresConstant(masked, declaration)) {
                continue
            }
            if (declaration != NONE && declaresFunction(masked, declaration + name.length)) {
                // The member itself or a local function, called by name: code, not a variable.
                // A delegate held in a variable and invoked is still a capture -- its
                // declaration is not followed by a parameter list.
                continue
            }
            val declaredInMember = declaration != NONE && declaration >= member.start
            if (declaredInMember) {
                if (declaration < shape.start && name !in captured) {
                    captured[name] = CapturedVariable(name, declaration)
                }
                continue
            }
            if (name in instanceMembers) {
                usesInstance = true
            }
        }
        return LambdaSite(
            start = shape.start,
            bodyStart = shape.bodyStart,
            bodyEnd = shape.bodyEnd,
            parameterNames = shape.parameterNames,
            containerQualifiedName = container.qualifiedName,
            containerNameOffset = container.nameOffset,
            memberName = memberName,
            captured = captured.values.toList(),
            usesInstance = usesInstance,
        )
    }

    /** True when the caret is on a lambda's `=>` or an anonymous method's `delegate`. */
    fun isAt(masked: String, offset: Int): Boolean {
        return shapeAt(masked, offset) != null
    }

    private class Shape(val start: Int, val bodyStart: Int, val bodyEnd: Int, val parameterNames: List<String>)

    private fun shapeAt(masked: String, offset: Int): Shape? {
        val arrow = arrowAround(masked, offset)
        if (arrow != NONE) {
            return lambdaShape(masked, arrow)
        }
        val range = VariableTypes.identifierRangeAt(masked, offset) ?: return null
        if (masked.substring(range.first, range.second) != DELEGATE_KEYWORD) {
            return null
        }
        return anonymousMethodShape(masked, range.first, range.second)
    }

    /** The `=>` the offset is on or right beside, or [NONE]. */
    private fun arrowAround(masked: String, offset: Int): Int {
        for (candidate in (offset - ARROW.length)..offset) {
            if (candidate >= 0 && masked.startsWith(ARROW, candidate)) {
                return candidate
            }
        }
        return NONE
    }

    /**
     * The lambda an arrow belongs to, or null when the arrow is an expression body instead:
     * `int Count => items.Length;`, `void Reset() => count = 0;`, a `switch` arm.
     */
    private fun lambdaShape(masked: String, arrow: Int): Shape? {
        val before = previousCode(masked, arrow - 1)
        if (before < 0) {
            return null
        }
        if (isSwitchArm(masked, arrow)) {
            return null
        }
        val start: Int
        val parameters: List<String>
        if (SourceText.isIdentifierChar(masked[before])) {
            val wordStart = wordStartOf(masked, before)
            if (!SourceText.isIdentifierStart(masked[wordStart])) {
                // `1 => "one"`: a switch arm on a constant.
                return null
            }
            val beforeWord = previousCode(masked, wordStart - 1)
            if (beforeWord >= 0 && masked[beforeWord] == '.') {
                // `Kind.Coins => ...`: an arm matching an enum member, not a parameter.
                return null
            }
            if (isDeclaredWithType(masked, wordStart)) {
                // `Type Name =>`: an expression-bodied property, not a lambda.
                return null
            }
            start = wordStart
            parameters = listOf(masked.substring(wordStart, before + 1))
        } else if (masked[before] == ')') {
            val open = matchingOpen(masked, before, '(', ')')
            if (open < 0 || isMemberParameterList(masked, open)) {
                return null
            }
            start = open
            parameters = parameterNamesIn(masked.substring(open + 1, before))
        } else {
            return null
        }
        val bodyStart = nextCode(masked, arrow + ARROW.length)
        if (bodyStart < 0) {
            return null
        }
        return Shape(start, bodyStart, bodyEndFrom(masked, bodyStart), parameters)
    }

    private fun anonymousMethodShape(masked: String, keywordStart: Int, keywordEnd: Int): Shape? {
        var next = nextCode(masked, keywordEnd)
        if (next < 0) {
            return null
        }
        var parameters = emptyList<String>()
        if (masked[next] == '(') {
            val close = matchingClose(masked, next, '(', ')')
            if (close < 0) {
                return null
            }
            parameters = parameterNamesIn(masked.substring(next + 1, close))
            next = nextCode(masked, close + 1)
        }
        if (next < 0 || masked[next] != '{') {
            // `delegate void Handler();` declares a type, it creates nothing.
            return null
        }
        val end = SourceText.matchingBrace(masked, next)
        if (end < 0) {
            return null
        }
        return Shape(keywordStart, next, end + 1, parameters)
    }

    /** A block body runs to its brace; an expression body to the `,` `)` or `;` that ends it. */
    private fun bodyEndFrom(masked: String, bodyStart: Int): Int {
        if (masked[bodyStart] == '{') {
            val end = SourceText.matchingBrace(masked, bodyStart)
            if (end < 0) {
                return masked.length
            }
            return end + 1
        }
        var depth = 0
        var position = bodyStart
        while (position < masked.length) {
            when (masked[position]) {
                '(', '[', '{' -> {
                    depth++
                }
                ')', ']', '}' -> {
                    if (depth == 0) {
                        return position
                    }
                    depth--
                }
                ',', ';' -> {
                    if (depth == 0) {
                        return position
                    }
                }
                else -> {
                    // Part of the expression.
                }
            }
            position++
        }
        return masked.length
    }

    /**
     * An arm of a `switch` expression: the arrow sits directly in the braces that follow `switch`.
     * Its left side is a pattern -- `_`, `1`, `Enemy e`, `Kind.Coins` -- and no pattern of it
     * reliably looks unlike a lambda parameter, so the braces decide.
     */
    private fun isSwitchArm(masked: String, arrow: Int): Boolean {
        var depth = 0
        var inEarlierArm = false
        var position = arrow - 1
        while (position >= 0) {
            when (masked[position]) {
                ')', ']', '}' -> {
                    depth++
                }
                '(', '[' -> {
                    if (depth == 0) {
                        return false
                    }
                    depth--
                }
                '{' -> {
                    if (depth == 0) {
                        val before = previousCode(masked, position - 1)
                        return before >= 0 && wordEndingAt(masked, before) == SWITCH_KEYWORD
                    }
                    depth--
                }
                ',' -> {
                    if (depth == 0) {
                        inEarlierArm = true
                    }
                }
                '>' -> {
                    // Another arrow earlier in the same arm: this one belongs to a lambda in the
                    // arm's result, `1 => () => n`, not to the arm.
                    if (depth == 0 && !inEarlierArm && masked.getOrElse(position - 1) { ' ' } == '=') {
                        return false
                    }
                }
                else -> {
                    // Still inside the same arm.
                }
            }
            position--
        }
        return false
    }

    private fun wordEndingAt(masked: String, end: Int): String {
        if (!SourceText.isIdentifierChar(masked[end])) {
            return ""
        }
        return masked.substring(wordStartOf(masked, end), end + 1)
    }

    /** `const float speed = 2;` -- a constant is compiled into every use, nothing is captured. */
    private fun declaresConstant(masked: String, nameStart: Int): Boolean {
        val typeEnd = previousCode(masked, nameStart - 1)
        if (typeEnd < 0) {
            return false
        }
        val typeStart = VariableTypes.typeStartEndingAt(masked, typeEnd) ?: return false
        val modifierEnd = previousCode(masked, typeStart - 1)
        return modifierEnd >= 0 && wordEndingAt(masked, modifierEnd) == CONST_KEYWORD
    }

    /** `(int a, b)` or `(a, b)`: the last identifier of each part is the parameter's name. */
    private fun parameterNamesIn(text: String): List<String> {
        val names = ArrayList<String>()
        for (part in SourceText.splitTopLevel(text, ',')) {
            val trimmed = part.trim()
            var end = trimmed.length
            while (end > 0 && !SourceText.isIdentifierChar(trimmed[end - 1])) {
                end--
            }
            var begin = end
            while (begin > 0 && SourceText.isIdentifierChar(trimmed[begin - 1])) {
                begin--
            }
            if (end > begin) {
                names.add(trimmed.substring(begin, end))
            }
        }
        return names
    }

    /** The parameters of every lambda nested in the body: they are not captures either. */
    private fun lambdaParametersIn(masked: String, from: Int, to: Int): Set<String> {
        val names = HashSet<String>()
        var position = masked.indexOf(ARROW, from)
        while (position in from until to) {
            val shape = lambdaShape(masked, position)
            if (shape != null) {
                names.addAll(shape.parameterNames)
            }
            position = masked.indexOf(ARROW, position + ARROW.length)
        }
        return names
    }

    /** `Type Name` in front of the arrow or the parameter list means a member, not a lambda. */
    private fun isDeclaredWithType(masked: String, wordStart: Int): Boolean {
        val typeEnd = previousCode(masked, wordStart - 1)
        if (typeEnd < 0) {
            return false
        }
        val typeStart = VariableTypes.typeStartEndingAt(masked, typeEnd) ?: return false
        val firstWord = SourceText.readIdentifier(masked, typeStart)
        return firstWord !in LAMBDA_PREFIXES
    }

    /** `void Reset() =>` or `Health(int value) =>`: a method's own parameter list, not a lambda's. */
    private fun isMemberParameterList(masked: String, open: Int): Boolean {
        val before = previousCode(masked, open - 1)
        if (before < 0) {
            return false
        }
        var nameEnd = before
        if (masked[before] == '>') {
            val genericOpen = matchingOpen(masked, before, '<', '>')
            if (genericOpen < 0) {
                return false
            }
            nameEnd = previousCode(masked, genericOpen - 1)
        }
        if (nameEnd < 0 || !SourceText.isIdentifierChar(masked[nameEnd])) {
            return false
        }
        val nameStart = wordStartOf(masked, nameEnd)
        val name = masked.substring(nameStart, nameEnd + 1)
        if (name in LAMBDA_PREFIXES) {
            return false
        }
        if (name == "operator" || name == "this") {
            return true
        }
        return isDeclaredWithType(masked, nameStart)
    }

    // ---- the member the lambda sits in ----

    private fun memberStatementAt(masked: String, container: TypeDeclaration, offset: Int): SourceStatement? {
        if (!container.hasBody) {
            return null
        }
        return SourceText.statementsIn(masked, container.bodyStart + 1, container.bodyEnd).firstOrNull { statement ->
            offset >= statement.start && offset <= statement.end
        }
    }

    /**
     * The member's name the way the compiler spells it in a lambda method's name: a method by its
     * name, a constructor and every instance field initializer as `.ctor`, a static one as
     * `.cctor`, a property's accessor as `get_X` / `set_X`.
     */
    private fun compilerMemberName(masked: String, container: TypeDeclaration, member: SourceStatement, offset: Int): String {
        val header = SourceText.collapseWhitespace(SourceText.splitAttributes(member.headerText(masked)).second)
        val isStatic = SourceText.containsWord(header, "static")
        val parenthesis = SourceText.indexOfTopLevel(header, '(')
        val arrow = header.indexOf(ARROW)
        val assignment = plainAssignmentIn(header)
        val beforeBody = listOf(arrow, assignment).filter { index -> index >= 0 }.minOrNull() ?: header.length
        if (parenthesis in 0 until beforeBody) {
            return methodNameOf(header, parenthesis, container, isStatic)
        }
        val initializerEnd: Int
        if (arrow >= 0) {
            initializerEnd = arrow
        } else {
            initializerEnd = header.length
        }
        if (assignment in 0 until initializerEnd) {
            // A field initializer: it runs in the constructor.
            if (isStatic) {
                return STATIC_CONSTRUCTOR_NAME
            }
            return CONSTRUCTOR_NAME
        }
        if (!member.hasBody) {
            return GETTER_PREFIX + propertyNameOf(header.substring(0, beforeBody))
        }
        val propertyName = propertyNameOf(header)
        for (accessor in SourceText.statementsIn(masked, member.bodyStart + 1, member.bodyEnd)) {
            if (offset < accessor.start || offset > accessor.end) {
                continue
            }
            val accessorHeader = SourceText.collapseWhitespace(accessor.headerText(masked))
            if (SourceText.containsWord(accessorHeader, "set") || SourceText.containsWord(accessorHeader, "init")) {
                return SETTER_PREFIX + propertyName
            }
        }
        return GETTER_PREFIX + propertyName
    }

    /** The first `=` that assigns: not part of `=>`, `==`, `<=`, `>=` or `!=`. */
    private fun plainAssignmentIn(header: String): Int {
        for (index in header.indices) {
            if (header[index] != '=') {
                continue
            }
            val previous = header.getOrElse(index - 1) { ' ' }
            val next = header.getOrElse(index + 1) { ' ' }
            if (next == '>' || next == '=' || previous == '=' || previous == '<' || previous == '>' || previous == '!') {
                continue
            }
            return index
        }
        return NONE
    }

    private fun methodNameOf(header: String, parenthesis: Int, container: TypeDeclaration, isStatic: Boolean): String {
        var end = parenthesis
        while (end > 0 && header[end - 1].isWhitespace()) {
            end--
        }
        if (end > 0 && header[end - 1] == '>') {
            val open = header.lastIndexOf('<', end - 1)
            if (open > 0) {
                end = open
            }
        }
        var begin = end
        while (begin > 0 && SourceText.isIdentifierChar(header[begin - 1])) {
            begin--
        }
        val name = header.substring(begin, end)
        if (name == container.name) {
            if (isStatic) {
                return STATIC_CONSTRUCTOR_NAME
            }
            return CONSTRUCTOR_NAME
        }
        return name
    }

    private fun propertyNameOf(header: String): String {
        val trimmed = header.trim()
        var end = trimmed.length
        while (end > 0 && !SourceText.isIdentifierChar(trimmed[end - 1])) {
            end--
        }
        var begin = end
        while (begin > 0 && SourceText.isIdentifierChar(trimmed[begin - 1])) {
            begin--
        }
        return trimmed.substring(begin, end)
    }

    /** Names of the container's instance members: using one of them means capturing `this`. */
    private fun instanceMemberNamesOf(masked: String, container: TypeDeclaration): Set<String> {
        val names = HashSet<String>()
        for (field in FieldReader.readFields(masked, container)) {
            names.add(field.name)
        }
        if (!container.hasBody) {
            return names
        }
        for (statement in SourceText.statementsIn(masked, container.bodyStart + 1, container.bodyEnd)) {
            val header = SourceText.collapseWhitespace(SourceText.splitAttributes(statement.headerText(masked)).second)
            if (SourceText.containsWord(header, "static") || SourceText.containsWord(header, "const")) {
                continue
            }
            val parenthesis = SourceText.indexOfTopLevel(header, '(')
            if (parenthesis >= 0) {
                names.add(methodNameOf(header, parenthesis, container, false))
                continue
            }
            names.add(propertyNameOf(header.substringBefore("=>").substringBefore("=")))
        }
        return names
    }

    // ---- scanning ----

    private fun declaresFunction(masked: String, nameEnd: Int): Boolean {
        val next = nextCode(masked, nameEnd)
        return next >= 0 && (masked[next] == '(' || masked[next] == '<')
    }

    private fun isIdentifierContinuation(masked: String, position: Int): Boolean {
        return position > 0 && (SourceText.isIdentifierChar(masked[position - 1]) || masked[position - 1] == '@')
    }

    private fun isMemberAccess(masked: String, nameStart: Int): Boolean {
        val before = previousCode(masked, nameStart - 1)
        return before >= 0 && masked[before] == '.' && masked.getOrElse(before - 1) { ' ' } != '.'
    }

    /** `Spawn(count: 3)`: `count` names a parameter of the call, it reads nothing. */
    private fun isNamedArgument(masked: String, nameEnd: Int): Boolean {
        val after = nextCode(masked, nameEnd)
        if (after < 0 || masked[after] != ':') {
            return false
        }
        return masked.getOrElse(after + 1) { ' ' } != ':'
    }

    private fun previousCode(masked: String, from: Int): Int {
        var position = from
        while (position >= 0 && masked[position].isWhitespace()) {
            position--
        }
        return position
    }

    private fun nextCode(masked: String, from: Int): Int {
        var position = from
        while (position < masked.length && masked[position].isWhitespace()) {
            position++
        }
        if (position >= masked.length) {
            return NONE
        }
        return position
    }

    private fun wordStartOf(masked: String, lastCharacter: Int): Int {
        var start = lastCharacter
        while (start > 0 && SourceText.isIdentifierChar(masked[start - 1])) {
            start--
        }
        return start
    }

    private fun matchingOpen(masked: String, close: Int, open: Char, closing: Char): Int {
        var depth = 0
        var position = close
        while (position >= 0) {
            if (masked[position] == closing) {
                depth++
            }
            if (masked[position] == open) {
                depth--
                if (depth == 0) {
                    return position
                }
            }
            position--
        }
        return NONE
    }

    private fun matchingClose(masked: String, openPosition: Int, open: Char, closing: Char): Int {
        var depth = 0
        var position = openPosition
        while (position < masked.length) {
            if (masked[position] == open) {
                depth++
            }
            if (masked[position] == closing) {
                depth--
                if (depth == 0) {
                    return position
                }
            }
            position++
        }
        return NONE
    }
}
