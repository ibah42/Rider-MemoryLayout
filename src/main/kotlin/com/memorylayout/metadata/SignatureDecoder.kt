package com.memorylayout.metadata

/**
 * Turns a type signature (II.23.2.12) into the C# a reader would have written for it.
 *
 * Names come out qualified -- `System.Collections.Generic.KeyValuePair<TKey, TValue>` -- because
 * they are resolved again later, possibly by the project's own index, and a bare `Entry` would
 * find whichever `Entry` happened to be nearest. Primitives come out as keywords, which is what
 * the size table knows them by.
 */
class SignatureDecoder(
    private val image: MetadataImage,
    private val typeDefinitionName: (Int) -> String,
    private val typeReferenceName: (Int) -> String,
    private val typeSpecificationName: (Int, List<String>) -> String,
) {

    /** A cursor over one signature blob. */
    private class Cursor(val data: ByteArray) {
        var position = 0

        fun readByte(): Int {
            val value = data[position].toInt() and BYTE_MASK
            position++
            return value
        }

        fun readCompressed(): Int {
            val value = CompressedInteger.read(data, position)
            position += CompressedInteger.sizeAt(data, position)
            return value
        }
    }

    /** A field signature: the FIELD prolog, custom modifiers, then the type. */
    fun fieldType(signature: ByteArray, genericParameters: List<String>): String {
        try {
            val cursor = Cursor(signature)
            if (cursor.readByte() != FIELD_PROLOG) {
                return UNREADABLE_TYPE
            }
            return readType(cursor, genericParameters, emptyList())
        } catch (outOfBounds: IndexOutOfBoundsException) {
            return UNREADABLE_TYPE
        } catch (unsupported: MetadataFormatException) {
            return UNREADABLE_TYPE
        }
    }

    /**
     * A property signature (II.23.2.5), or null for an indexer: `this[int]` has no name a member
     * access could reach it by.
     */
    fun propertyType(signature: ByteArray, genericParameters: List<String>): String? {
        try {
            val cursor = Cursor(signature)
            if (cursor.readByte() and CALLING_CONVENTION_MASK != PROPERTY_PROLOG) {
                return null
            }
            if (cursor.readCompressed() != 0) {
                return null
            }
            return readType(cursor, genericParameters, emptyList())
        } catch (outOfBounds: IndexOutOfBoundsException) {
            return null
        } catch (unsupported: MetadataFormatException) {
            return null
        }
    }

    /** A method's return type (II.23.2.1), its own type parameters spelled by [methodParameters]. */
    fun returnType(signature: ByteArray, genericParameters: List<String>, methodParameters: List<String>): String? {
        try {
            val cursor = Cursor(signature)
            val callingConvention = cursor.readByte()
            if (callingConvention and GENERIC_METHOD_FLAG != 0) {
                cursor.readCompressed()
            }
            cursor.readCompressed()
            return readType(cursor, genericParameters, methodParameters)
        } catch (outOfBounds: IndexOutOfBoundsException) {
            return null
        } catch (unsupported: MetadataFormatException) {
            return null
        }
    }

    /** A TypeSpec blob: a bare type, most often a generic instantiation used as a base class. */
    fun typeSpecification(signature: ByteArray, genericParameters: List<String>): String {
        try {
            return readType(Cursor(signature), genericParameters, emptyList())
        } catch (outOfBounds: IndexOutOfBoundsException) {
            return UNREADABLE_TYPE
        } catch (unsupported: MetadataFormatException) {
            return UNREADABLE_TYPE
        }
    }

    /** A TypeDefOrRef coded index (II.24.2.6), as the base class column stores it. */
    fun typeDefOrRefName(coded: Int, genericParameters: List<String>): String {
        val tag = coded and ((1 shl MetadataTables.TYPE_DEF_OR_REF_BITS) - 1)
        val row = coded ushr MetadataTables.TYPE_DEF_OR_REF_BITS
        return when (MetadataTables.TYPE_DEF_OR_REF[tag]) {
            MetadataTables.TYPE_DEF -> {
                keywordOrName(typeDefinitionName(row))
            }
            MetadataTables.TYPE_REF -> {
                keywordOrName(typeReferenceName(row))
            }
            else -> {
                typeSpecificationName(row, genericParameters)
            }
        }
    }

    private fun readType(cursor: Cursor, genericParameters: List<String>, methodParameters: List<String>): String {
        val elementType = cursor.readByte()
        val primitive = PRIMITIVE_NAMES[elementType]
        if (primitive != null) {
            return primitive
        }
        return when (elementType) {
            ELEMENT_POINTER -> {
                readType(cursor, genericParameters, methodParameters) + "*"
            }
            ELEMENT_BY_REFERENCE -> {
                // Only a ref struct can hold a ref field, and a reference is what it holds.
                readType(cursor, genericParameters, methodParameters) + "*"
            }
            ELEMENT_VALUE_TYPE, ELEMENT_CLASS -> {
                typeDefOrRefName(cursor.readCompressed(), genericParameters)
            }
            ELEMENT_TYPE_PARAMETER -> {
                genericParameters.getOrElse(cursor.readCompressed()) { UNREADABLE_TYPE }
            }
            ELEMENT_METHOD_PARAMETER -> {
                methodParameters.getOrElse(cursor.readCompressed()) { UNREADABLE_TYPE }
            }
            ELEMENT_ARRAY -> {
                readArray(cursor, genericParameters, methodParameters)
            }
            ELEMENT_SINGLE_DIMENSION_ARRAY -> {
                readType(cursor, genericParameters, methodParameters) + "[]"
            }
            ELEMENT_GENERIC_INSTANCE -> {
                readGenericInstance(cursor, genericParameters, methodParameters)
            }
            ELEMENT_REQUIRED_MODIFIER, ELEMENT_OPTIONAL_MODIFIER -> {
                // `volatile`, `in` and friends: a marker in front of the type, not a type.
                cursor.readCompressed()
                readType(cursor, genericParameters, methodParameters)
            }
            ELEMENT_PINNED -> {
                readType(cursor, genericParameters, methodParameters)
            }
            ELEMENT_FUNCTION_POINTER -> {
                // `delegate*<...>` is one pointer; its method signature follows and is not needed,
                // and a field signature ends with its type, so nothing after it is lost.
                cursor.position = cursor.data.size
                POINTER_SIZED_NAME
            }
            else -> {
                throw MetadataFormatException("element type $elementType")
            }
        }
    }

    private fun readGenericInstance(cursor: Cursor, genericParameters: List<String>, methodParameters: List<String>): String {
        cursor.readByte()
        val generic = typeDefOrRefName(cursor.readCompressed(), genericParameters)
        val argumentCount = cursor.readCompressed()
        val arguments = ArrayList<String>(argumentCount)
        for (argumentIndex in 0 until argumentCount) {
            arguments.add(readType(cursor, genericParameters, methodParameters))
        }
        return generic + "<" + arguments.joinToString(", ") + ">"
    }

    /** `T[,]`: the element, the rank, then sizes and lower bounds nobody needs for a reference. */
    private fun readArray(cursor: Cursor, genericParameters: List<String>, methodParameters: List<String>): String {
        val element = readType(cursor, genericParameters, methodParameters)
        val rank = cursor.readCompressed()
        val sizeCount = cursor.readCompressed()
        for (sizeIndex in 0 until sizeCount) {
            cursor.readCompressed()
        }
        val lowerBoundCount = cursor.readCompressed()
        for (boundIndex in 0 until lowerBoundCount) {
            cursor.readCompressed()
        }
        return element + "[" + ",".repeat(maxOf(rank - 1, 0)) + "]"
    }

    private fun keywordOrName(qualifiedName: String): String {
        return KEYWORD_NAMES[qualifiedName] ?: qualifiedName
    }

    companion object {
        /** A type this decoder could not spell; it resolves to nothing and is shown as unknown. */
        const val UNREADABLE_TYPE = "__unreadable"

        private const val POINTER_SIZED_NAME = "nint"

        private const val BYTE_MASK = 0xFF

        private const val FIELD_PROLOG = 0x06

        private const val PROPERTY_PROLOG = 0x08

        /** The low bits of a signature's first byte; `HASTHIS` and friends sit above them. */
        private const val CALLING_CONVENTION_MASK = 0x0F

        private const val GENERIC_METHOD_FLAG = 0x10

        private const val ELEMENT_POINTER = 0x0F
        private const val ELEMENT_BY_REFERENCE = 0x10
        private const val ELEMENT_VALUE_TYPE = 0x11
        private const val ELEMENT_CLASS = 0x12
        private const val ELEMENT_TYPE_PARAMETER = 0x13
        private const val ELEMENT_ARRAY = 0x14
        private const val ELEMENT_GENERIC_INSTANCE = 0x15
        private const val ELEMENT_FUNCTION_POINTER = 0x1B
        private const val ELEMENT_SINGLE_DIMENSION_ARRAY = 0x1D
        private const val ELEMENT_METHOD_PARAMETER = 0x1E
        private const val ELEMENT_REQUIRED_MODIFIER = 0x1F
        private const val ELEMENT_OPTIONAL_MODIFIER = 0x20
        private const val ELEMENT_PINNED = 0x45

        private val PRIMITIVE_NAMES = mapOf(
            0x01 to "void",
            0x02 to "bool",
            0x03 to "char",
            0x04 to "sbyte",
            0x05 to "byte",
            0x06 to "short",
            0x07 to "ushort",
            0x08 to "int",
            0x09 to "uint",
            0x0A to "long",
            0x0B to "ulong",
            0x0C to "float",
            0x0D to "double",
            0x0E to "string",
            0x16 to "System.TypedReference",
            0x18 to "nint",
            0x19 to "nuint",
            0x1C to "object",
        )

        /** A primitive written as a class token rather than an element type still reads as its keyword. */
        private val KEYWORD_NAMES = mapOf(
            "System.String" to "string",
            "System.Object" to "object",
            "System.Boolean" to "bool",
            "System.Char" to "char",
            "System.SByte" to "sbyte",
            "System.Byte" to "byte",
            "System.Int16" to "short",
            "System.UInt16" to "ushort",
            "System.Int32" to "int",
            "System.UInt32" to "uint",
            "System.Int64" to "long",
            "System.UInt64" to "ulong",
            "System.Single" to "float",
            "System.Double" to "double",
            "System.IntPtr" to "nint",
            "System.UIntPtr" to "nuint",
        )
    }
}

/** Metadata names made into C# identifiers the text reader accepts. */
object Identifiers {

    private const val REPLACEMENT = '_'

    private const val KEYWORD_SUFFIX = "_"

    /**
     * The words that would make a synthesized declaration unreadable if a field carried one as its
     * name. Metadata allows them -- `@class` is a legal field -- and the text reader does not.
     */
    private val KEYWORDS = setOf(
        "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
        "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
        "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for",
        "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
        "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
        "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed", "short",
        "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true",
        "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual",
        "void", "volatile", "while", "record", "partial", "required", "file",
    )

    /** `<buffer>e__FixedBuffer` becomes `_buffer_e__FixedBuffer`: one name, used on both sides. */
    fun sanitize(name: String): String {
        if (name.isEmpty()) {
            return REPLACEMENT.toString()
        }
        val builder = StringBuilder(name.length)
        for (character in name) {
            if (character.isLetterOrDigit() || character == REPLACEMENT) {
                builder.append(character)
            } else {
                builder.append(REPLACEMENT)
            }
        }
        if (builder[0].isDigit()) {
            builder.insert(0, REPLACEMENT)
        }
        val sanitized = builder.toString()
        if (sanitized in KEYWORDS) {
            return sanitized + KEYWORD_SUFFIX
        }
        return sanitized
    }
}
