package com.memorylayout.layout

/**
 * Puts the declarations of a `partial` type back together into the one type the compiler sees.
 *
 * C# lets a type be declared in several places -- UniTask is spread over eleven files -- and every
 * part contributes: its fields, its attributes, its base list. A scanner that reads one declaration
 * at a time sees eleven types instead, ten of them with no fields at all, and whichever one a name
 * resolves to decides whether the layout is right or empty. That is what this undoes.
 */
object PartialTypes {

    private const val FILE_SEPARATOR = '/'

    /**
     * Whether two declarations are parts of the same type.
     *
     * Both have to say `partial`: two plain declarations of one name in one namespace are a
     * compile error, not a type, and merging them would draw something that does not exist.
     */
    fun sameType(first: TypeDeclaration, second: TypeDeclaration): Boolean {
        if (!first.isPartial || !second.isPartial) {
            return false
        }
        if (first.kind != second.kind) {
            return false
        }
        if (first.genericParameters.size != second.genericParameters.size) {
            return false
        }
        return first.qualifiedName == second.qualifiedName
    }

    /**
     * One [DeclaredType] out of every part of a type.
     *
     * The parts are ordered by file and then by position, which is as good an order as any:
     * the language leaves the order of fields across parts undefined (warning CS0282), and a
     * stable one at least keeps the window from reshuffling between two openings.
     *
     * The part that declares fields is the one the result stands for -- its name is what the
     * window navigates to, and for UniTask it is the one file out of eleven that says anything
     * about memory. Attributes and base lists are collected from all parts, because
     * `[StructLayout]` may sit on a part with no fields and still governs every field.
     */
    fun merge(parts: List<DeclaredType>): DeclaredType {
        if (parts.size == 1) {
            return parts.first()
        }
        val ordered = parts.sortedWith(
            compareBy<DeclaredType>({ part -> part.fileId }, { part -> part.declaration.declarationOffset })
        )
        val primary = ordered.firstOrNull { part -> hasOwnFields(part) } ?: ordered.first()
        val attributes = ArrayList<String>(primary.declaration.attributes)
        val baseLists = ArrayList<String>()
        if (primary.declaration.baseListText.isNotBlank()) {
            baseLists.add(primary.declaration.baseListText.trim())
        }
        for (part in ordered) {
            if (part === primary) {
                continue
            }
            attributes.addAll(part.declaration.attributes)
            if (part.declaration.baseListText.isNotBlank()) {
                baseLists.add(part.declaration.baseListText.trim())
            }
        }
        val declaration = primary.declaration.copy(
            attributes = attributes,
            baseListText = baseLists.joinToString(", "),
        )
        return DeclaredType(declaration, primary.maskedSource, primary.fileId, ordered, primary.notes)
    }

    /** What the window should say about a merged type, or nothing for a type declared once. */
    fun notesFor(type: DeclaredType): List<String> {
        if (type.parts.size <= 1) {
            return emptyList()
        }
        val partsWithFields = type.parts.filter { part -> hasOwnFields(part) }
        val notes = ArrayList<String>()
        notes.add(
            "Partial: ${type.parts.size} declarations read as one type, " +
                "${partsWithFields.size} of them with fields"
        )
        if (partsWithFields.size > 1 && type.declaration.isValueType) {
            val fileNames = partsWithFields.map { part -> fileNameOf(part.fileId) }.distinct()
            notes.add(
                "C# does not define the order of fields across partial declarations (CS0282); " +
                    "here they follow the files: " + fileNames.joinToString(", ")
            )
        }
        return notes
    }

    private fun hasOwnFields(part: DeclaredType): Boolean {
        return FieldReader.readFields(part.maskedSource, part.declaration).isNotEmpty()
    }

    private fun fileNameOf(fileId: String): String {
        val separator = fileId.lastIndexOf(FILE_SEPARATOR)
        if (separator < 0) {
            return fileId
        }
        return fileId.substring(separator + 1)
    }
}
