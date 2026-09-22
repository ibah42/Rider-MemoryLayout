package com.memorylayout.index

import com.memorylayout.layout.DeclaredType
import com.memorylayout.layout.LookupContext
import com.memorylayout.layout.TypeLookup
import com.memorylayout.layout.TypeMatching

/**
 * Resolves type names against the project index.
 *
 * The same ranking answers two different questions: which declaration the engine should use for a
 * field, and -- when more than one is plausible -- which order to offer them to the user in.
 */
class ProjectTypeLookup(private val index: TypeIndexService) : TypeLookup {

    override fun resolve(typeName: String, context: LookupContext): DeclaredType? {
        val best = rankedCandidates(typeName, context).firstOrNull() ?: return null
        return index.declaredTypeOf(best)
    }

    /** Every declaration the written name could mean, most likely first. */
    fun rankedCandidates(typeName: String, context: LookupContext): List<IndexedType> {
        return index.candidates(typeName)
            .filter { entry ->
                TypeMatching.matches(entry.simpleName, entry.qualifiedName, entry.arity, typeName)
            }
            .sortedByDescending { entry ->
                TypeMatching.score(entry.namespaceName, entry.containerNames, context, entry.fileUrl)
            }
    }
}
