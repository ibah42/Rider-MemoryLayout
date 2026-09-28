package com.memorylayout.metadata

/**
 * A closure class the compiler generated, matched to a lambda in the source.
 *
 * @param matchedNames the lambda's captures that are fields of this class
 * @param missingNames captures that are not: they live in an enclosing closure this one points at,
 *   or the assembly is older than the source
 */
data class ClosureMatch(
    val type: MetadataType,
    val matchedNames: List<String>,
    val missingNames: List<String>,
)

/**
 * Finds the closure class of a lambda among the types of a compiled assembly.
 *
 * The C# compiler puts every local a lambda captures into a sealed class nested in the lambda's
 * type, `<>c__DisplayClass5_0` -- one per scope, shared by every lambda of that scope -- with a
 * field per captured variable under the variable's own name, `<>4__this` for `this`, and
 * `CS$<>8__locals1` for the enclosing scope's closure. The lambda itself becomes a method of that
 * class named after the member it was written in, `<Update>b__0`. Those names are the whole
 * bridge from source to assembly: nothing else in the metadata says which lambda is which.
 */
object Closures {

    const val DISPLAY_CLASS_PREFIX = "<>c__DisplayClass"

    private const val THIS_FIELD = "<>4__this"

    private const val OUTER_CLOSURE_FIELD_PREFIX = "CS$<>8__locals"

    val THIS_FIELD_NAME = Identifiers.sanitize(THIS_FIELD)

    private val OUTER_CLOSURE_FIELD_NAME_PREFIX = Identifiers.sanitize(OUTER_CLOSURE_FIELD_PREFIX)

    /**
     * The closure class a lambda written in [memberName] of [containerQualifiedName] that captures
     * [capturedNames] was compiled into, or null when the assembly has none that fits.
     *
     * A class qualifies when one of its lambda methods comes from that member, or when it holds
     * one of the captures. The best has a method from the member, then reaches the most captures
     * -- its own fields and those of the enclosing closures it points at -- then the fewest other
     * fields. That picks the innermost scope's closure: a lambda capturing `inner` and `factor`
     * lives in the class holding `inner`, which reaches `factor` through its outer closure field.
     */
    fun find(types: List<MetadataType>, containerQualifiedName: String, memberName: String, capturedNames: List<String>): ClosureMatch? {
        val lambdaPrefix = "<$memberName>"
        val closures = types.filter { type ->
            type.rawName.startsWith(DISPLAY_CLASS_PREFIX) && containerNameOf(type) == containerQualifiedName
        }
        var best: ClosureMatch? = null
        var bestScore = Int.MIN_VALUE
        for (type in closures) {
            val fieldNames = type.fields.map { field -> field.name }.toSet()
            val matched = capturedNames.filter { name -> name in fieldNames }
            val reachable = reachableNames(type, closures)
            val reached = capturedNames.filter { name -> name in reachable }
            val fromMember = type.compilerMethodNames.any { name -> name.startsWith(lambdaPrefix) }
            if (!fromMember && matched.isEmpty()) {
                continue
            }
            var score = reached.size * SCORE_PER_CAPTURE - (fieldNames.size - matched.size)
            if (fromMember) {
                score += SCORE_FROM_MEMBER
            }
            if (score > bestScore) {
                bestScore = score
                best = ClosureMatch(type, matched, capturedNames.filter { name -> name !in reachable })
            }
        }
        return best
    }

    /** Every field name of the closure and of the enclosing closures it points at. */
    private fun reachableNames(type: MetadataType, closures: List<MetadataType>): Set<String> {
        val names = HashSet<String>()
        val visited = HashSet<String>()
        var current: MetadataType? = type
        while (current != null && visited.add(current.qualifiedName)) {
            var outer: MetadataType? = null
            for (field in current.fields) {
                names.add(field.name)
                if (isOuterClosureField(field.name)) {
                    outer = closures.firstOrNull { candidate -> candidate.qualifiedName == field.typeName }
                }
            }
            current = outer
        }
        return names
    }

    /** What a closure field is called in the window: the variable, `this`, or the outer closure. */
    fun labelOf(fieldName: String): String {
        if (fieldName == THIS_FIELD_NAME) {
            return "this"
        }
        if (fieldName.startsWith(OUTER_CLOSURE_FIELD_NAME_PREFIX)) {
            return "outer closure"
        }
        return fieldName
    }

    fun isOuterClosureField(fieldName: String): Boolean {
        return fieldName.startsWith(OUTER_CLOSURE_FIELD_NAME_PREFIX)
    }

    /** `Game.Player` for a closure nested in `Game.Player`. */
    private fun containerNameOf(type: MetadataType): String {
        val parts = ArrayList<String>()
        if (type.namespaceName.isNotEmpty()) {
            parts.add(type.namespaceName)
        }
        parts.addAll(type.containerNames)
        return parts.joinToString(".")
    }

    /** Having a lambda of the member outweighs any number of shared names. */
    private const val SCORE_FROM_MEMBER = 1000

    private const val SCORE_PER_CAPTURE = 10
}
