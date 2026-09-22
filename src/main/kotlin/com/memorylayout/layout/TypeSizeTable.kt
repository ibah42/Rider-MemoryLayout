package com.memorylayout.layout

/**
 * Sizes and alignments of the types the engine knows without reading any source.
 *
 * Three groups live here: the C# primitives, a few BCL value types whose layout is fixed, and the
 * Unity and Unity.Mathematics types that turn up in every second struct in this codebase. The
 * Unity entries are a convenience, not a necessity -- their sources are in the project and would
 * resolve anyway -- but they usually sit in a package folder that the index may not cover, and
 * getting `float3` wrong would be felt everywhere.
 *
 * Adding an entry is the normal way to teach the plugin about a type whose source is not
 * available. Everything else resolves from the project.
 */
object TypeSizeTable {

    private const val BOOL_PROBLEM = "bool is one byte in memory but four when marshalled"

    private const val CHAR_PROBLEM = "char is UTF-16 in memory and depends on the charset when marshalled"

    private const val REFERENCE_PROBLEM = "a reference points at the heap"

    private const val SPAN_PROBLEM = "Span<T> holds a byref into somebody else's memory"

    private const val MEMORY_PROBLEM = "Memory<T> holds a reference to the heap"

    /** The offset and the length a `Memory<T>` carries beside its reference. */
    private const val MEMORY_INDEX_BYTES = 8

    private const val ARRAY_SUFFIX = "[]"

    private const val POINTER_SUFFIX = "*"

    /** size, alignment. */
    private val PRIMITIVES = mapOf(
        "sbyte" to Pair(1, 1),
        "byte" to Pair(1, 1),
        "short" to Pair(2, 2),
        "ushort" to Pair(2, 2),
        "int" to Pair(4, 4),
        "uint" to Pair(4, 4),
        "long" to Pair(8, 8),
        "ulong" to Pair(8, 8),
        "float" to Pair(4, 4),
        "double" to Pair(8, 8),
        "decimal" to Pair(16, 8),
        "Int128" to Pair(16, 8),
        "UInt128" to Pair(16, 8),
        "SByte" to Pair(1, 1),
        "Byte" to Pair(1, 1),
        "Int16" to Pair(2, 2),
        "UInt16" to Pair(2, 2),
        "Int32" to Pair(4, 4),
        "UInt32" to Pair(4, 4),
        "Int64" to Pair(8, 8),
        "UInt64" to Pair(8, 8),
        "Single" to Pair(4, 4),
        "Double" to Pair(8, 8),
        "Decimal" to Pair(16, 8),
        "Half" to Pair(2, 2),
        "Guid" to Pair(16, 4),
        "DateTime" to Pair(8, 8),
        "DateTimeOffset" to Pair(16, 8),
        "TimeSpan" to Pair(8, 8),
        // System.Numerics
        "Vector2" to Pair(8, 4),
        "Vector3" to Pair(12, 4),
        "Vector4" to Pair(16, 4),
        "Quaternion" to Pair(16, 4),
        "Plane" to Pair(16, 4),
        "Matrix4x4" to Pair(64, 4),
        "Matrix3x2" to Pair(24, 4),
        // UnityEngine
        "Color" to Pair(16, 4),
        "Color32" to Pair(4, 1),
        "Rect" to Pair(16, 4),
        "RectInt" to Pair(16, 4),
        "Bounds" to Pair(24, 4),
        "BoundsInt" to Pair(24, 4),
        "Vector2Int" to Pair(8, 4),
        "Vector3Int" to Pair(12, 4),
        "LayerMask" to Pair(4, 4),
        // Unity.Mathematics
        "half" to Pair(2, 2),
        "half2" to Pair(4, 2),
        "half3" to Pair(6, 2),
        "half4" to Pair(8, 2),
        "float2" to Pair(8, 4),
        "float3" to Pair(12, 4),
        "float4" to Pair(16, 4),
        "double2" to Pair(16, 8),
        "double3" to Pair(24, 8),
        "double4" to Pair(32, 8),
        "int2" to Pair(8, 4),
        "int3" to Pair(12, 4),
        "int4" to Pair(16, 4),
        "uint2" to Pair(8, 4),
        "uint3" to Pair(12, 4),
        "uint4" to Pair(16, 4),
        "quaternion" to Pair(16, 4),
        "float2x2" to Pair(16, 4),
        "float3x3" to Pair(36, 4),
        "float4x4" to Pair(64, 4),
        "RigidTransform" to Pair(32, 4),
        // Unity.Entities / Unity.Collections
        "Entity" to Pair(8, 4),
        "FixedString32Bytes" to Pair(32, 2),
        "FixedString64Bytes" to Pair(64, 2),
        "FixedString128Bytes" to Pair(128, 2),
        "FixedString512Bytes" to Pair(512, 2),
        "FixedString4096Bytes" to Pair(4096, 2),
    )

    /** Value types that are perfectly sized but still cannot be blitted, with the reason. */
    private val PRIMITIVE_PROBLEMS = mapOf(
        "bool" to BOOL_PROBLEM,
        "Boolean" to BOOL_PROBLEM,
        "char" to CHAR_PROBLEM,
        "Char" to CHAR_PROBLEM,
        "bool2" to BOOL_PROBLEM,
        "bool3" to BOOL_PROBLEM,
        "bool4" to BOOL_PROBLEM,
    )

    private val BOOLEAN_SIZES = mapOf(
        "bool" to Pair(1, 1),
        "Boolean" to Pair(1, 1),
        "char" to Pair(2, 2),
        "Char" to Pair(2, 2),
        "bool2" to Pair(2, 1),
        "bool3" to Pair(3, 1),
        "bool4" to Pair(4, 1),
    )

    private val REFERENCE_TYPE_NAMES = setOf(
        "string", "String", "object", "Object", "dynamic", "Delegate", "Action", "Func",
    )

    private val POINTER_SIZED_NAMES = setOf(
        "nint", "nuint", "IntPtr", "UIntPtr",
    )

    /**
     * What this type name means, or null when nothing here knows it and the project has to be
     * searched instead.
     *
     * Suffixes are read first: `Foo*` is a pointer and `Foo[]` an array whatever `Foo` is, so
     * neither needs the type itself to be known.
     */
    fun metricsFor(typeName: String, target: LayoutTarget): TypeMetrics? {
        val name = normalize(typeName)
        if (name.isEmpty()) {
            return null
        }
        if (name.endsWith(POINTER_SUFFIX)) {
            return TypeMetrics(target.pointerSize, target.pointerSize)
        }
        if (name.endsWith("]")) {
            // `T[]`, `T[,]` and every other array: the field holds a reference.
            return referenceMetrics(target)
        }
        val simpleName = simpleNameOf(name)
        if (looksLikeInterface(simpleName)) {
            return referenceMetrics(target)
        }
        if (GenericName.arityOf(name) > 0) {
            // A generic is either one of the few value types named below, or something the
            // project index has to answer for. Never guessed here: a project's own generic struct
            // must win over anything this table thinks, and it is the index that knows about it.
            return genericValueMetrics(TypeMatching.simpleName(name), target)
        }
        if (simpleName in POINTER_SIZED_NAMES) {
            return TypeMetrics(target.pointerSize, target.pointerSize)
        }
        if (simpleName in REFERENCE_TYPE_NAMES) {
            return referenceMetrics(target)
        }
        val booleanSize = BOOLEAN_SIZES[simpleName]
        if (booleanSize != null) {
            return TypeMetrics(
                size = booleanSize.first,
                alignment = booleanSize.second,
                blittableProblem = PRIMITIVE_PROBLEMS[simpleName],
            )
        }
        val primitive = PRIMITIVES[simpleName] ?: return null
        return TypeMetrics(primitive.first, primitive.second)
    }

    /**
     * `ILogger`, `IDisposable`, `IComparable<T>`: an interface by the convention every C# codebase
     * follows, and therefore one reference however it was declared.
     *
     * This exists because the interfaces that matter usually come from an assembly rather than
     * from the project's own sources -- `ILogger` is in a NuGet package, and the index only knows
     * what it can read. Without this rule such a field resolves to nothing, takes zero bytes, and
     * every offset after it is wrong while the window looks confident about it. The name is weak
     * evidence, but a field of unknown size is not evidence at all, it is a silent lie.
     *
     * `Int32` is not caught by it: the second character has to be a letter, so the rule needs
     * `I` followed by a capital and then a lower-case letter somewhere, which no primitive alias
     * has.
     */
    fun looksLikeInterface(simpleName: String): Boolean {
        if (simpleName.length < MINIMUM_INTERFACE_NAME) {
            return false
        }
        if (simpleName[0] != 'I' || !simpleName[1].isUpperCase()) {
            return false
        }
        return simpleName.drop(INTERFACE_PREFIX_LENGTH).any { character ->
            character.isLowerCase()
        }
    }

    /**
     * The generic value types worth knowing without a declaration.
     *
     * Everything else generic in the BCL that a field is likely to hold -- `List`, `Dictionary`,
     * `HashSet`, `Queue`, `Task`, `Action`, `Func`, `Lazy` -- is a class, so it is one reference
     * and the engine says so when the index cannot find it. These four are the value types that
     * would be sized wrongly by that rule, so they are named.
     *
     * `Span<T>` is a byref and an int; `Memory<T>` an object reference and two ints. Neither can
     * be blitted, and `Span` cannot even be a field of a normal struct -- but a `ref struct` can
     * hold one, and this is what it costs there.
     */
    private fun genericValueMetrics(bareName: String, target: LayoutTarget): TypeMetrics? {
        val pointer = target.pointerSize
        return when (bareName) {
            "Span", "ReadOnlySpan" -> {
                TypeMetrics(pointer * 2, pointer, blittableProblem = SPAN_PROBLEM)
            }
            "Memory", "ReadOnlyMemory" -> {
                TypeMetrics(roundUpToPointer(pointer + MEMORY_INDEX_BYTES, pointer), pointer, blittableProblem = MEMORY_PROBLEM)
            }
            else -> {
                null
            }
        }
    }

    private fun roundUpToPointer(value: Int, pointer: Int): Int {
        return (value + pointer - 1) / pointer * pointer
    }

    fun referenceMetrics(target: LayoutTarget): TypeMetrics {
        return TypeMetrics(
            size = target.pointerSize,
            alignment = target.pointerSize,
            isReferenceType = true,
            blittableProblem = REFERENCE_PROBLEM,
        )
    }

    /** True when the name is one this table answers for; used to skip the project index. */
    fun knows(typeName: String, target: LayoutTarget): Boolean {
        return metricsFor(typeName, target) != null
    }

    private fun normalize(typeName: String): String {
        var name = SourceText.collapseWhitespace(typeName)
        if (name.startsWith(GLOBAL_PREFIX)) {
            name = name.substring(GLOBAL_PREFIX.length)
        }
        return name
    }

    /** `System.Numerics.Vector3` and `Vector3` are the same entry; generic arity is not stripped. */
    private fun simpleNameOf(name: String): String {
        val lastDot = name.lastIndexOf('.')
        if (lastDot < 0) {
            return name
        }
        return name.substring(lastDot + 1)
    }

    private const val GLOBAL_PREFIX = "global::"

    private const val MINIMUM_INTERFACE_NAME = 3

    private const val INTERFACE_PREFIX_LENGTH = 2
}
