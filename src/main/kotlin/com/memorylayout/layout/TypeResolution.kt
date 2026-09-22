package com.memorylayout.layout

/**
 * Where a type name is being read from: the namespace and the chain of enclosing types.
 *
 * It is what makes `Inner` mean `Outer.Inner` when the name is written inside `Outer`, and what
 * breaks the tie when the same simple name exists in two namespaces.
 */
data class LookupContext(
    val namespaceName: String,
    val containerNames: List<String>,
    val fileId: String = "",
) {
    companion object {
        val EMPTY = LookupContext("", emptyList(), "")
    }
}

/**
 * A type name that resolved to a declaration.
 *
 * @param maskedSource the masked text of the file holding [declaration]; the engine reads the
 *   fields straight out of it, so whoever resolves a name has to hand it over with the declaration
 */
data class DeclaredType(
    val declaration: TypeDeclaration,
    val maskedSource: String,
    val fileId: String = "",
)

/**
 * Turns a type name into a declaration.
 *
 * One implementation reads a single file ([SourceTypeLookup]) and is what the tests use; the one
 * over the project index lives outside this package and answers the same question across files.
 */
fun interface TypeLookup {

    fun resolve(typeName: String, context: LookupContext): DeclaredType?
}

/**
 * How a written name is matched against a declaration, and which candidate wins when several
 * match.
 *
 * Shared on purpose: the single-file lookup here and the project-wide index outside this package
 * have to agree, or the same name would resolve differently depending on where the file sits.
 */
object TypeMatching {

    private const val GLOBAL_PREFIX = "global::"

    /** Highest first: a nested type of the current chain beats a namespace neighbour. */
    const val SCORE_SAME_FILE = 1
    const val SCORE_SAME_NAMESPACE = 2
    const val SCORE_ENCLOSING_TYPE = 4

    /**
     * The name with `global::`, namespaces, generic arguments and a trailing `?` removed:
     * `global::Game.Data.Chunk<int>?` becomes `Chunk`.
     */
    fun simpleName(typeName: String): String {
        var name = SourceText.collapseWhitespace(typeName)
        if (name.startsWith(GLOBAL_PREFIX)) {
            name = name.substring(GLOBAL_PREFIX.length)
        }
        while (name.endsWith("?")) {
            name = name.substring(0, name.length - 1).trim()
        }
        val angle = name.indexOf('<')
        if (angle >= 0) {
            name = name.substring(0, angle)
        }
        val lastDot = name.lastIndexOf('.')
        if (lastDot >= 0) {
            name = name.substring(lastDot + 1)
        }
        return name.trim()
    }

    /** The namespace part a name was written with, or "" when it was written bare. */
    fun writtenQualifier(typeName: String): String {
        var name = SourceText.collapseWhitespace(typeName)
        if (name.startsWith(GLOBAL_PREFIX)) {
            name = name.substring(GLOBAL_PREFIX.length)
        }
        val angle = name.indexOf('<')
        if (angle >= 0) {
            name = name.substring(0, angle)
        }
        val lastDot = name.lastIndexOf('.')
        if (lastDot < 0) {
            return ""
        }
        return name.substring(0, lastDot)
    }

    fun matches(declaration: TypeDeclaration, typeName: String): Boolean {
        return matches(declaration.name, declaration.qualifiedName, declaration.genericParameters.size, typeName)
    }

    /**
     * The name/qualified-name pair rather than a declaration, so that the project index -- which
     * holds those strings and not the declaration itself -- answers by the same rule.
     *
     * Arity is part of the identity: `Box` and `Box<T>` are two different types and may be
     * declared side by side, so a use has to bring the same number of arguments as the
     * declaration takes parameters.
     */
    fun matches(declaredName: String, qualifiedName: String, declaredArity: Int, typeName: String): Boolean {
        if (declaredArity != GenericName.arityOf(typeName)) {
            return false
        }
        if (declaredName != simpleName(typeName)) {
            return false
        }
        val qualifier = writtenQualifier(typeName)
        if (qualifier.isEmpty()) {
            return true
        }
        // `Game.Data.Chunk` may be written against a nested type too: `Outer.Inner`.
        return qualifiedName.endsWith(".$qualifier.$declaredName") ||
            qualifiedName == "$qualifier.$declaredName"
    }

    fun score(declaration: TypeDeclaration, context: LookupContext, fileId: String): Int {
        return score(declaration.namespaceName, declaration.containerNames, context, fileId)
    }

    fun score(
        namespaceName: String,
        containerNames: List<String>,
        context: LookupContext,
        fileId: String,
    ): Int {
        var score = 0
        if (fileId.isNotEmpty() && fileId == context.fileId) {
            score += SCORE_SAME_FILE
        }
        if (namespaceName == context.namespaceName) {
            score += SCORE_SAME_NAMESPACE
        }
        if (containerNames.isNotEmpty() && context.containerNames.containsAll(containerNames)) {
            score += SCORE_ENCLOSING_TYPE
        }
        return score
    }
}

/** Resolves type names against the declarations of a single file. */
class SourceTypeLookup(
    private val maskedSource: String,
    private val fileId: String = "",
) : TypeLookup {

    private val declarations: List<TypeDeclaration> = TypeScanner.scan(maskedSource)

    fun allDeclarations(): List<TypeDeclaration> {
        return declarations
    }

    override fun resolve(typeName: String, context: LookupContext): DeclaredType? {
        var best: TypeDeclaration? = null
        var bestScore = -1
        for (declaration in declarations) {
            if (!TypeMatching.matches(declaration, typeName)) {
                continue
            }
            val score = TypeMatching.score(declaration, context, fileId)
            if (score > bestScore) {
                best = declaration
                bestScore = score
            }
        }
        if (best == null) {
            return null
        }
        return DeclaredType(best, maskedSource, fileId)
    }
}
