package com.memorylayout.metadata

import com.memorylayout.layout.CodeMask
import com.memorylayout.layout.DeclaredLayoutKind
import com.memorylayout.layout.DeclaredType
import com.memorylayout.layout.GenericName
import com.memorylayout.layout.LookupContext
import com.memorylayout.layout.TypeDeclaration
import com.memorylayout.layout.TypeLookup
import com.memorylayout.layout.TypeMatching
import com.memorylayout.layout.TypeScanner
import java.util.IdentityHashMap

/**
 * Writes a [MetadataType] out as the C# declaration it would have been compiled from.
 *
 * The engine only reads source text, and it reads it well: attributes, explicit offsets, auto
 * properties, nested generics. Writing the metadata back as text lets every one of those rules
 * apply unchanged to a type nobody has the source of, instead of growing a second engine that
 * would drift from the first.
 */
object MetadataSourceWriter {

    private const val INDENT = "    "

    fun write(type: MetadataType): String {
        val builder = StringBuilder()
        var depth = 0
        if (type.namespaceName.isNotEmpty()) {
            builder.append("namespace ").append(type.namespaceName).append('\n').append("{\n")
            depth++
        }
        // Containers are only wrappers: they give the declaration its qualified name and nothing
        // else, so what kind of type each one really is does not matter.
        for (container in type.containerNames) {
            line(builder, depth, "public class $container")
            line(builder, depth, "{")
            depth++
        }
        writeDeclaration(builder, depth, type)
        for (container in type.containerNames) {
            depth--
            line(builder, depth, "}")
        }
        if (type.namespaceName.isNotEmpty()) {
            builder.append("}\n")
        }
        return builder.toString()
    }

    private fun writeDeclaration(builder: StringBuilder, depth: Int, type: MetadataType) {
        val attribute = layoutAttributeOf(type)
        if (attribute != null) {
            line(builder, depth, attribute)
        }
        val header = StringBuilder("public ")
        header.append(keywordOf(type.kind)).append(' ').append(type.name)
        if (type.genericParameters.isNotEmpty()) {
            header.append('<').append(type.genericParameters.joinToString(", ")).append('>')
        }
        val baseList = baseListOf(type)
        if (baseList.isNotEmpty()) {
            header.append(" : ").append(baseList)
        }
        line(builder, depth, header.toString())
        line(builder, depth, "{")
        if (type.kind == MetadataTypeKind.STRUCT || type.kind == MetadataTypeKind.CLASS) {
            for (field in type.fields) {
                writeField(builder, depth + 1, field, type.layoutKind)
            }
        }
        if (type.kind != MetadataTypeKind.ENUM) {
            writeMembers(builder, depth + 1, type)
        }
        line(builder, depth, "}")
    }

    /**
     * Properties and methods, written with bodies so that the field reader drops them -- an auto
     * property would be read as a backing field and change the layout. They are there only so a
     * member access through this type can find out what it evaluates to.
     */
    private fun writeMembers(builder: StringBuilder, depth: Int, type: MetadataType) {
        val written = HashSet<String>()
        for (field in type.fields) {
            written.add(field.name)
        }
        for (member in type.members) {
            // An overload has the same name and nearly always the same return type; the first wins.
            if (!written.add(member.name)) {
                continue
            }
            if (member.typeName == SignatureDecoder.UNREADABLE_TYPE) {
                continue
            }
            if (!member.isMethod) {
                line(builder, depth, "public ${member.typeName} ${member.name} { get { } }")
                continue
            }
            var generic = ""
            if (member.genericParameters.isNotEmpty()) {
                generic = "<" + member.genericParameters.joinToString(", ") + ">"
            }
            line(builder, depth, "public ${member.typeName} ${member.name}$generic() { }")
        }
    }

    private fun writeField(builder: StringBuilder, depth: Int, field: MetadataField, layoutKind: DeclaredLayoutKind) {
        if (layoutKind == DeclaredLayoutKind.EXPLICIT && field.explicitOffset != MetadataField.NO_EXPLICIT_OFFSET) {
            line(builder, depth, "[FieldOffset(${field.explicitOffset})]")
        }
        if (field.isAutoPropertyBackingField) {
            line(builder, depth, "public ${field.typeName} ${field.name} { get; set; }")
            return
        }
        line(builder, depth, "public ${field.typeName} ${field.name};")
    }

    /**
     * `[StructLayout]` only where it says something the keyword does not: a struct is sequential
     * and a class is auto unless told otherwise, exactly as the engine assumes for source.
     */
    private fun layoutAttributeOf(type: MetadataType): String? {
        val isValueType = type.kind == MetadataTypeKind.STRUCT
        val defaultKind: DeclaredLayoutKind
        if (isValueType) {
            defaultKind = DeclaredLayoutKind.SEQUENTIAL
        } else {
            defaultKind = DeclaredLayoutKind.AUTO
        }
        val hasPack = type.pack != MetadataType.UNSET
        val hasSize = type.classSize != MetadataType.UNSET
        if (type.layoutKind == defaultKind && !hasPack && !hasSize) {
            return null
        }
        if (type.kind == MetadataTypeKind.ENUM || type.kind == MetadataTypeKind.INTERFACE) {
            return null
        }
        val arguments = ArrayList<String>()
        arguments.add("LayoutKind." + layoutKindName(type.layoutKind))
        if (hasPack) {
            arguments.add("Pack = ${type.pack}")
        }
        if (hasSize) {
            arguments.add("Size = ${type.classSize}")
        }
        return "[StructLayout(" + arguments.joinToString(", ") + ")]"
    }

    private fun layoutKindName(kind: DeclaredLayoutKind): String {
        return when (kind) {
            DeclaredLayoutKind.SEQUENTIAL -> {
                "Sequential"
            }
            DeclaredLayoutKind.EXPLICIT -> {
                "Explicit"
            }
            DeclaredLayoutKind.AUTO -> {
                "Auto"
            }
        }
    }

    private fun baseListOf(type: MetadataType): String {
        if (type.kind == MetadataTypeKind.ENUM) {
            // An enum's size is its `value__` field's; the engine reads it from the base list.
            return type.fields.firstOrNull()?.typeName ?: ""
        }
        return type.baseTypeName
    }

    private fun keywordOf(kind: MetadataTypeKind): String {
        return when (kind) {
            MetadataTypeKind.CLASS -> {
                "class"
            }
            MetadataTypeKind.STRUCT -> {
                "struct"
            }
            MetadataTypeKind.ENUM -> {
                "enum"
            }
            MetadataTypeKind.INTERFACE -> {
                "interface"
            }
        }
    }

    private fun line(builder: StringBuilder, depth: Int, text: String) {
        builder.append(INDENT.repeat(depth)).append(text).append('\n')
    }
}

/**
 * Resolves type names against the types of a set of assemblies.
 *
 * It sits behind the project's own index, never in front of it: a project that declares its own
 * `Entry` means that one. What this adds is everything the project does not declare -- the BCL,
 * UnityEngine, a package shipped as a DLL.
 */
class MetadataTypeLookup(types: List<MetadataType>) : TypeLookup {

    private val typesBySimpleName = HashMap<String, MutableList<MetadataType>>()

    private val typesByFileId = HashMap<String, MetadataType>()

    private val declaredCache = IdentityHashMap<MetadataType, DeclaredType>()

    private val lock = Any()

    init {
        for (type in types) {
            typesBySimpleName.getOrPut(type.name) { ArrayList() }.add(type)
            typesByFileId.putIfAbsent(fileIdOf(type), type)
        }
    }

    val typeCount: Int
        get() = typesBySimpleName.values.sumOf { list -> list.size }

    override fun resolve(typeName: String, context: LookupContext): DeclaredType? {
        val type = rankedCandidates(typeName, context).firstOrNull() ?: return null
        return declaredTypeOf(type)
    }

    /**
     * Every type the written name could mean, most likely first.
     *
     * A keyword is its framework type: `string` is `System.String`. A bare name is ambiguous in a
     * way it rarely is in source -- the BCL alone has a dozen nested `Enumerator`s -- so a public
     * type beats a hidden one, and the caller's namespace beats both.
     */
    fun rankedCandidates(typeName: String, context: LookupContext): List<MetadataType> {
        val written = KEYWORD_TYPES[typeName.trim()] ?: typeName
        val simpleName = TypeMatching.simpleName(written)
        val arity = GenericName.arityOf(written)
        val candidates = typesBySimpleName[simpleName] ?: return emptyList()
        return candidates
            .filter { type ->
                TypeMatching.matches(type.name, type.qualifiedName, type.genericParameters.size, written) ||
                    matchesOpenDeclaration(type, written, arity)
            }
            .sortedByDescending { type -> scoreOf(type, context) }
    }

    /**
     * `List` with the caret on it -- no arguments written -- means the generic `List<T>` when no
     * plain `List` exists. Only for a bare name: a written `Box<int>` keeps its arity.
     */
    private fun matchesOpenDeclaration(type: MetadataType, written: String, arity: Int): Boolean {
        if (arity != 0 || type.genericParameters.isEmpty()) {
            return false
        }
        if (type.containerNames.isNotEmpty()) {
            return false
        }
        return TypeMatching.writtenQualifier(written).isEmpty() ||
            type.namespaceName == TypeMatching.writtenQualifier(written)
    }

    private fun scoreOf(type: MetadataType, context: LookupContext): Int {
        var score = 0
        if (type.namespaceName == context.namespaceName) {
            score += SCORE_SAME_NAMESPACE
        }
        if (type.isPublic) {
            score += SCORE_PUBLIC
        }
        if (type.containerNames.isEmpty()) {
            score += SCORE_TOP_LEVEL
        }
        if (type.genericParameters.isEmpty()) {
            score += SCORE_NOT_GENERIC
        }
        return score
    }

    /** The type a window tab was opened on, found again by the identity it was given. */
    fun typeWithFileId(fileId: String): MetadataType? {
        return typesByFileId[fileId]
    }

    fun declaredTypeOf(type: MetadataType): DeclaredType {
        synchronized(lock) {
            val cached = declaredCache[type]
            if (cached != null) {
                return cached
            }
        }
        val source = MetadataSourceWriter.write(type)
        val masked = CodeMask.of(source)
        val declaration = TypeScanner.scan(masked).lastOrNull { candidate ->
            candidate.qualifiedName == type.qualifiedName
        } ?: TypeScanner.scan(masked).last()
        val declared = DeclaredType(
            declaration = declaration,
            maskedSource = masked,
            fileId = fileIdOf(type),
            notes = notesFor(type, declaration),
        )
        synchronized(lock) {
            declaredCache[type] = declared
        }
        return declared
    }

    private fun notesFor(type: MetadataType, declaration: TypeDeclaration): List<String> {
        val notes = ArrayList<String>()
        notes.add("Read from the metadata of ${type.assemblyName}, not from source")
        if (type.qualifiedName == STRING_TYPE) {
            notes.add(
                "The characters continue in the same object after ${declaration.name}'s last field, " +
                    "two bytes each plus a terminating zero: a string of n characters is 2·n bytes " +
                    "larger than shown"
            )
        }
        return notes
    }

    companion object {
        /** Marks a declaration that has no file: nothing to navigate to. */
        const val FILE_ID_PREFIX = "metadata:"

        private const val STRING_TYPE = "System.String"

        private const val ARITY_MARK = "`"

        private const val SCORE_SAME_NAMESPACE = 8

        private const val SCORE_PUBLIC = 4

        private const val SCORE_TOP_LEVEL = 2

        private const val SCORE_NOT_GENERIC = 1

        fun fileIdOf(type: MetadataType): String {
            return FILE_ID_PREFIX + type.assemblyName + "!" + type.qualifiedName +
                ARITY_MARK + type.genericParameters.size
        }

        /** The C# keywords, as the framework types they stand for. */
        val KEYWORD_TYPES = mapOf(
            "string" to "System.String",
            "object" to "System.Object",
            "bool" to "System.Boolean",
            "char" to "System.Char",
            "sbyte" to "System.SByte",
            "byte" to "System.Byte",
            "short" to "System.Int16",
            "ushort" to "System.UInt16",
            "int" to "System.Int32",
            "uint" to "System.UInt32",
            "long" to "System.Int64",
            "ulong" to "System.UInt64",
            "float" to "System.Single",
            "double" to "System.Double",
            "decimal" to "System.Decimal",
            "nint" to "System.IntPtr",
            "nuint" to "System.UIntPtr",
        )
    }
}

/** The project first, then whatever else knows types: the first one that answers wins. */
class CompositeTypeLookup(private val lookups: List<TypeLookup>) : TypeLookup {

    override fun resolve(typeName: String, context: LookupContext): DeclaredType? {
        for (lookup in lookups) {
            val resolved = lookup.resolve(typeName, context)
            if (resolved != null) {
                return resolved
            }
        }
        return null
    }
}
