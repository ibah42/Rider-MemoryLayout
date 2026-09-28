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

    /**
     * Every type the written name could mean, most likely first.
     *
     * A partial type is offered once, however many files declare it: eleven `UniTask` lines in the
     * chooser would be eleven ways of asking for the same layout.
     *
     * @param allowOpenGeneric a bare name that matches nothing exactly may mean a generic
     *   declaration of that name -- the caret on `MessageLogState` in `MessageLogState<TPayload>`.
     *   For opening a type by name only; resolving a field keeps arity strict.
     */
    fun rankedCandidates(
        typeName: String,
        context: LookupContext,
        allowOpenGeneric: Boolean = false,
    ): List<IndexedType> {
        val named = index.candidates(typeName)
        var matching = named.filter { entry ->
            TypeMatching.matches(entry.simpleName, entry.qualifiedName, entry.arity, typeName)
        }
        if (matching.isEmpty() && allowOpenGeneric) {
            matching = named.filter { entry ->
                TypeMatching.matchesOpenDeclaration(entry.simpleName, entry.qualifiedName, entry.arity, typeName)
            }
        }
        val ranked = matching
            .sortedByDescending { entry ->
                TypeMatching.score(entry.namespaceName, entry.containerNames, context, entry.fileUrl)
            }
        val distinct = ArrayList<IndexedType>()
        for (entry in ranked) {
            val alreadyOffered = distinct.any { offered -> offered.isPartOfSameType(entry) }
            if (!alreadyOffered) {
                distinct.add(entry)
            }
        }
        return distinct
    }
}
