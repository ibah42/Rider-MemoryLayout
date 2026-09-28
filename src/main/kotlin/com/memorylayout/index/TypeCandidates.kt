package com.memorylayout.index

import com.memorylayout.layout.GenericName
import com.memorylayout.layout.LookupContext
import com.memorylayout.layout.SourceText
import com.memorylayout.layout.TypeKind
import com.intellij.openapi.project.Project

/**
 * Every type a written name could mean, as tabs the window can open: the sources first, then the
 * assemblies, and an array as an array.
 *
 * Shared by the editor action and by a click on a reference in the table, so that `List<int>`
 * means the same thing wherever it was clicked. Blocks on the first read of the assemblies: never
 * call it on the UI thread.
 */
object TypeCandidates {

    private const val ARRAY_SUFFIX = "[]"

    private const val NULLABLE_SUFFIX = "?"

    /** Where an array tab says it comes from, in place of a file. */
    private const val ARRAY_LOCATION = "runtime array"

    /**
     * A variable's written arguments -- the `int` of `List<int>` -- travel with each candidate that
     * can take them, so the window lays out that instantiation rather than the open declaration.
     */
    fun of(project: Project, typeName: String, context: LookupContext): List<IndexedType> {
        // `Exception?` opens `Exception`: on a reference the `?` is only an annotation.
        val written = SourceText.collapseWhitespace(typeName).trim().removeSuffix(NULLABLE_SUFFIX).trim()
        val element = arrayElementOf(written)
        if (element != null) {
            return listOf(arrayEntry(element, context))
        }
        val index = TypeIndexService.getInstance(project)
        var candidates = ProjectTypeLookup(index).rankedCandidates(written, context, allowOpenGeneric = true)
        if (candidates.isEmpty()) {
            candidates = RuntimeAssemblyService.getInstance(project).candidates(written, context)
        }
        val arguments = GenericName.argumentsOf(written)
        if (arguments.isEmpty()) {
            return candidates
        }
        return candidates.map { candidate ->
            if (candidate.arity == arguments.size) {
                candidate.copy(typeArguments = arguments)
            } else {
                candidate
            }
        }
    }

    /**
     * `Item` for `Item[]`, and `int[]` for the jagged `int[][]`, whose elements are references to
     * arrays. Null for anything else, a rectangular `int[,]` included: its bounds live in a block
     * of their own, and that is not laid out here.
     */
    fun arrayElementOf(typeName: String): String? {
        val written = SourceText.collapseWhitespace(typeName).trim()
        if (!written.endsWith(ARRAY_SUFFIX)) {
            return null
        }
        val element = written.dropLast(ARRAY_SUFFIX.length).trim()
        if (element.isEmpty()) {
            return null
        }
        return element
    }

    private fun arrayEntry(element: String, context: LookupContext): IndexedType {
        val name = element + ARRAY_SUFFIX
        return IndexedType(
            simpleName = name,
            arity = 0,
            qualifiedName = name,
            namespaceName = context.namespaceName,
            containerNames = context.containerNames,
            kind = TypeKind.CLASS,
            fileUrl = "",
            filePresentableName = ARRAY_LOCATION,
            declarationOffset = -1,
            arrayElement = ArrayElement(element, context),
        )
    }
}
