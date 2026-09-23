package com.memorylayout.layout

/**
 * The vocabulary the layout engine speaks.
 *
 * Data only, no behaviour, and no dependency on anything outside the standard library: the whole
 * `layout` package is a pure function of the source text, so it runs in a plain unit test with no
 * IDE around it.
 *
 * The engine hands back a tree of byte ranges rather than a list of table rows on purpose. The
 * table is one view of it; a cache-line view, a diff of two targets or anything else drawn later
 * reads the same tree instead of asking the engine for a second shape.
 */

/** What a declaration declares. */
enum class TypeKind {
    STRUCT,
    CLASS,
    INTERFACE,
    ENUM,
    RECORD_STRUCT,
    RECORD_CLASS,
}

/** The `LayoutKind` of a `[StructLayout]` attribute, or the default one for the declaration. */
enum class DeclaredLayoutKind {
    /** Fields sit in declaration order. The default for a struct. */
    SEQUENTIAL,

    /** Every field carries its own `[FieldOffset]`. */
    EXPLICIT,

    /** The runtime places fields as it sees fit. The default for a class. */
    AUTO,
}

/**
 * The machine the numbers are for.
 *
 * Only the pointer size differs today, and it is what makes a reference field, an `IntPtr` and a
 * `nint` four bytes instead of eight. The 8-byte primitives keep their natural alignment on both:
 * the CLR aligns a `long` or a `double` inside a struct to 8 on x86 as well.
 */
enum class LayoutTarget(val pointerSize: Int) {
    X64(8),
    X86(4),
}

/**
 * What `[StructLayout]` asked for.
 *
 * @param pack the `Pack` argument: the alignment no field may exceed. [PACK_UNSET] means the
 *   attribute did not say, which is not the same as `Pack = 0` meaning "platform default".
 * @param declaredSize the `Size` argument: the minimum total size. [SIZE_UNSET] when absent.
 */
data class StructLayoutAttribute(
    val kind: DeclaredLayoutKind,
    val pack: Int = PACK_UNSET,
    val declaredSize: Int = SIZE_UNSET,
) {
    companion object {
        const val PACK_UNSET = 0
        const val SIZE_UNSET = 0
    }
}

/**
 * One field that takes up space in an instance.
 *
 * Constants and statics never reach this class -- they are dropped while reading, because they
 * are not part of an instance at all.
 *
 * @param typeName the type exactly as the source writes it, whitespace collapsed
 * @param name the field name; for an auto-property, the property's own name
 * @param declarationOffset where the declaration starts in the document, for navigation
 * @param explicitOffset the `[FieldOffset]` argument, or [NO_EXPLICIT_OFFSET]
 * @param fixedBufferLength the element count of a `fixed` buffer, or 0 when this is not one
 * @param isAutoProperty the space is taken by a compiler-generated backing field
 */
data class FieldDeclaration(
    val typeName: String,
    val name: String,
    val declarationOffset: Int = -1,
    val explicitOffset: Int = NO_EXPLICIT_OFFSET,
    val fixedBufferLength: Int = 0,
    val isAutoProperty: Boolean = false,

    /**
     * Which file [declarationOffset] is an offset into. Empty until the field is read through a
     * [DeclaredType]: the parts of a partial type sit in different files, so the type's own file
     * is not enough to navigate to one of its fields.
     */
    val fileId: String = "",
) {
    companion object {
        const val NO_EXPLICIT_OFFSET = -1
    }
}

/**
 * How much room a type needs and how it has to be aligned.
 *
 * @param isReferenceType a pointer to the heap rather than the data itself: the layout of what it
 *   points at is the runtime's business, and the struct holding it stops being blittable
 * @param blittableProblem why this type cannot be copied to native memory byte for byte, or null
 *   when it can. `bool` and `char` have one even though their size is perfectly well known.
 */
data class TypeMetrics(
    val size: Int,
    val alignment: Int,
    val isReferenceType: Boolean = false,
    val blittableProblem: String? = null,
)

/** What a node in the layout tree is. */
enum class NodeKind {
    /** A real field. */
    FIELD,

    /** Bytes nobody owns: alignment padding between fields, or the tail padding of the struct. */
    PADDING,

    /** A field whose type could not be resolved, so its size is a guess and the rest follows it. */
    UNRESOLVED,

    /**
     * Bytes the runtime owns rather than the programmer: the object header and the method table
     * pointer of a class. They are not fields and no source declares them, but they are what the
     * first field's offset is measured from, so leaving them out would be a lie by omission.
     */
    RUNTIME,
}

/**
 * One byte range of the instance.
 *
 * Offsets are absolute: a child of a nested struct reports its offset from the start of the
 * outermost type, which is what a reader comparing two fields actually wants.
 *
 * @param overlapsPrevious this node starts before the previous one ends. Only `LayoutKind.Explicit`
 *   can produce it, and it is usually deliberate (a union), which is why it is a flag and not an
 *   error.
 */
data class LayoutNode(
    val kind: NodeKind,
    val offset: Int,
    val size: Int,
    val alignment: Int,
    val typeName: String,
    val fieldName: String,
    val declarationOffset: Int = -1,

    /** Which file [declarationOffset] is an offset into; a nested type's fields carry its file. */
    val fileId: String = "",
    val isAutoProperty: Boolean = false,

    /**
     * The field holds a pointer to the heap rather than the data itself: a class, an interface, an
     * array, a string or a delegate. There is nothing below it to expand -- what it points at is
     * laid out by the runtime, in its own allocation.
     */
    val isReference: Boolean = false,
    val overlapsPrevious: Boolean = false,
    val children: List<LayoutNode> = emptyList(),
) {
    val endOffset: Int
        get() = offset + size
}

/** How much the numbers can be trusted. */
enum class LayoutConfidence {
    /** Every field resolved, and the rules that placed them are the ones the runtime follows. */
    EXACT,

    /** At least one type could not be resolved, so every offset after it is a guess. */
    APPROXIMATE,

    /**
     * The declaration is one the runtime is free to lay out on its own: a class, or an explicit
     * `LayoutKind.Auto`. What is shown is the sequential model, which is a useful picture and not
     * a promise.
     */
    RUNTIME_DEFINED,
}

/** The finished layout of one type. */
data class TypeLayout(
    val displayName: String,
    val qualifiedName: String,
    val target: LayoutTarget,
    val declaredLayoutKind: DeclaredLayoutKind,
    val size: Int,
    val alignment: Int,
    val paddingBytes: Int,
    val nodes: List<LayoutNode>,
    val confidence: LayoutConfidence,

    /** Empty when the type is blittable; otherwise every reason it is not, in field order. */
    val blittableProblems: List<String> = emptyList(),

    /** Anything the header should say out loud: unresolved types, overlaps, an applied `Size`. */
    val notes: List<String> = emptyList(),
) {
    val isBlittable: Boolean
        get() = blittableProblems.isEmpty()

    val paddingShare: Double
        get() {
            if (size <= 0) {
                return 0.0
            }
            return paddingBytes.toDouble() / size.toDouble()
        }
}
