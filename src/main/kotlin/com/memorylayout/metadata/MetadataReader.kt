package com.memorylayout.metadata

import com.memorylayout.layout.DeclaredLayoutKind

/**
 * Every type an assembly defines, with its instance fields, read from the metadata tables.
 *
 * This is what makes `List<T>`, `string` or `Guid` readable at all: the project has no source for
 * them, and the reference assemblies Unity hands to the IDE carry none of their private fields.
 * The runtime's own assembly does, and this reads it.
 */
object MetadataReader {

    private const val MODULE_TYPE_NAME = "<Module>"

    private const val ARITY_SEPARATOR = '`'

    private const val VISIBILITY_MASK = 0x07

    private const val VISIBILITY_PUBLIC = 0x01

    private const val VISIBILITY_NESTED_PUBLIC = 0x02

    private const val INTERFACE_FLAG = 0x20

    private const val LAYOUT_MASK = 0x18

    private const val LAYOUT_SEQUENTIAL = 0x08

    private const val LAYOUT_EXPLICIT = 0x10

    private const val FIELD_STATIC = 0x10

    private const val FIELD_LITERAL = 0x40

    private const val VALUE_TYPE_BASE = "System.ValueType"

    private const val ENUM_BASE = "System.Enum"

    /** How the decoder spells `System.Object`: as its keyword, like every primitive. */
    private const val OBJECT_BASE = "object"

    private const val VOID_TYPE = "void"

    private val BACKING_FIELD = Regex("^<(.+)>k__BackingField$")

    private const val METHOD_ACCESS_MASK = 0x07

    private const val METHOD_FAMILY = 0x04

    private const val METHOD_FAMILY_OR_ASSEMBLY = 0x05

    private const val METHOD_PUBLIC = 0x06

    /** Constructors, accessors and operators: reached by syntax, never by a member name. */
    private const val METHOD_SPECIAL_NAME = 0x0800

    fun read(bytes: ByteArray, assemblyName: String): List<MetadataType> {
        val image = MetadataImage.read(bytes)
        try {
            return Reading(image, assemblyName).types()
        } catch (outOfBounds: IndexOutOfBoundsException) {
            throw MetadataFormatException("truncated metadata")
        }
    }

    /** One pass over one image; holds the reverse maps the tables only store one way round. */
    private class Reading(private val image: MetadataImage, private val assemblyName: String) {

        private val tables = image.tables

        private val enclosingOf = HashMap<Int, Int>()

        private val genericParametersOf = HashMap<Int, MutableList<Pair<Int, String>>>()

        private val methodGenericParametersOf = HashMap<Int, MutableList<Pair<Int, String>>>()

        /** Property rows by owning type: the first row and the one past the last. */
        private val propertyRangeOf = HashMap<Int, Pair<Int, Int>>()

        private val classLayoutOf = HashMap<Int, Pair<Int, Int>>()

        private val fieldOffsetOf = HashMap<Int, Int>()

        private val signatures = SignatureDecoder(image, this::typeDefinitionName, this::typeReferenceName, this::typeSpecificationName)

        init {
            for (row in 1..tables.rowCount(MetadataTables.NESTED_CLASS)) {
                val nested = tables.value(MetadataTables.NESTED_CLASS, row, MetadataTables.NESTED_CLASS_NESTED)
                val enclosing = tables.value(MetadataTables.NESTED_CLASS, row, MetadataTables.NESTED_CLASS_ENCLOSING)
                enclosingOf[nested] = enclosing
            }
            for (row in 1..tables.rowCount(MetadataTables.GENERIC_PARAM)) {
                val owner = tables.value(MetadataTables.GENERIC_PARAM, row, MetadataTables.GENERIC_PARAM_OWNER)
                val tag = owner and ((1 shl MetadataTables.TYPE_OR_METHOD_DEF_BITS) - 1)
                val ownerRow = owner ushr MetadataTables.TYPE_OR_METHOD_DEF_BITS
                val number = tables.value(MetadataTables.GENERIC_PARAM, row, MetadataTables.GENERIC_PARAM_NUMBER)
                val name = image.string(tables.value(MetadataTables.GENERIC_PARAM, row, MetadataTables.GENERIC_PARAM_NAME))
                val parameter = Pair(number, Identifiers.sanitize(name))
                if (MetadataTables.TYPE_OR_METHOD_DEF[tag] == MetadataTables.TYPE_DEF) {
                    genericParametersOf.getOrPut(ownerRow) { ArrayList() }.add(parameter)
                } else {
                    methodGenericParametersOf.getOrPut(ownerRow) { ArrayList() }.add(parameter)
                }
            }
            val propertyMapCount = tables.rowCount(MetadataTables.PROPERTY_MAP)
            for (row in 1..propertyMapCount) {
                val parent = tables.value(MetadataTables.PROPERTY_MAP, row, MetadataTables.PROPERTY_MAP_PARENT)
                val first = tables.value(MetadataTables.PROPERTY_MAP, row, MetadataTables.PROPERTY_MAP_LIST)
                val end: Int
                if (row < propertyMapCount) {
                    end = tables.value(MetadataTables.PROPERTY_MAP, row + 1, MetadataTables.PROPERTY_MAP_LIST)
                } else {
                    end = tables.rowCount(MetadataTables.PROPERTY) + 1
                }
                propertyRangeOf[parent] = Pair(first, end)
            }
            for (row in 1..tables.rowCount(MetadataTables.CLASS_LAYOUT)) {
                val parent = tables.value(MetadataTables.CLASS_LAYOUT, row, MetadataTables.CLASS_LAYOUT_PARENT)
                val packing = tables.value(MetadataTables.CLASS_LAYOUT, row, MetadataTables.CLASS_LAYOUT_PACKING)
                val size = tables.value(MetadataTables.CLASS_LAYOUT, row, MetadataTables.CLASS_LAYOUT_SIZE)
                classLayoutOf[parent] = Pair(packing, size)
            }
            for (row in 1..tables.rowCount(MetadataTables.FIELD_LAYOUT)) {
                val field = tables.value(MetadataTables.FIELD_LAYOUT, row, MetadataTables.FIELD_LAYOUT_FIELD)
                fieldOffsetOf[field] = tables.value(MetadataTables.FIELD_LAYOUT, row, MetadataTables.FIELD_LAYOUT_OFFSET)
            }
        }

        fun types(): List<MetadataType> {
            val types = ArrayList<MetadataType>()
            val typeCount = tables.rowCount(MetadataTables.TYPE_DEF)
            for (row in 1..typeCount) {
                val rawName = image.string(tables.value(MetadataTables.TYPE_DEF, row, MetadataTables.TYPE_DEF_NAME))
                if (rawName == MODULE_TYPE_NAME) {
                    continue
                }
                types.add(readType(row, typeCount))
            }
            return types
        }

        private fun readType(row: Int, typeCount: Int): MetadataType {
            val flags = tables.value(MetadataTables.TYPE_DEF, row, MetadataTables.TYPE_DEF_FLAGS)
            val genericParameters = genericParametersOf[row]
                ?.sortedBy { parameter -> parameter.first }
                ?.map { parameter -> parameter.second }
                ?: emptyList()
            val baseName = baseTypeNameOf(row, genericParameters)
            val qualifiedName = typeDefinitionName(row)
            val kind = kindOf(flags, baseName, qualifiedName)
            val layout = classLayoutOf[row]
            val chain = containerChainOf(row)
            val outermost = chain.first()
            val visibility = flags and VISIBILITY_MASK
            var declaredBase = ""
            if (kind == MetadataTypeKind.CLASS && baseName.isNotEmpty() && baseName != OBJECT_BASE) {
                declaredBase = baseName
            }
            return MetadataType(
                assemblyName = assemblyName,
                namespaceName = namespaceOf(outermost),
                name = simpleNameOf(row),
                containerNames = chain.dropLast(1).map { container -> simpleNameOf(container) },
                genericParameters = genericParameters,
                kind = kind,
                isPublic = visibility == VISIBILITY_PUBLIC || visibility == VISIBILITY_NESTED_PUBLIC,
                baseTypeName = declaredBase,
                layoutKind = layoutKindOf(flags),
                pack = layout?.first ?: MetadataType.UNSET,
                classSize = layout?.second ?: MetadataType.UNSET,
                fields = fieldsOf(row, typeCount, genericParameters),
                members = propertiesOf(row, genericParameters) + methodsOf(row, typeCount, genericParameters),
            )
        }

        private fun propertiesOf(row: Int, genericParameters: List<String>): List<MetadataMember> {
            val range = propertyRangeOf[row] ?: return emptyList()
            val members = ArrayList<MetadataMember>()
            for (propertyRow in range.first until range.second) {
                val rawName = image.string(tables.value(MetadataTables.PROPERTY, propertyRow, MetadataTables.PROPERTY_NAME))
                val signature = image.blob(tables.value(MetadataTables.PROPERTY, propertyRow, MetadataTables.PROPERTY_SIGNATURE))
                val typeName = signatures.propertyType(signature, genericParameters) ?: continue
                members.add(MetadataMember(Identifiers.sanitize(rawName), typeName, isMethod = false))
            }
            return members
        }

        /** Public and protected methods; what a script can call is what it can take a type from. */
        private fun methodsOf(row: Int, typeCount: Int, genericParameters: List<String>): List<MetadataMember> {
            val first = tables.value(MetadataTables.TYPE_DEF, row, MetadataTables.TYPE_DEF_METHOD_LIST)
            val end: Int
            if (row < typeCount) {
                end = tables.value(MetadataTables.TYPE_DEF, row + 1, MetadataTables.TYPE_DEF_METHOD_LIST)
            } else {
                end = tables.rowCount(MetadataTables.METHOD_DEF) + 1
            }
            val members = ArrayList<MetadataMember>()
            for (methodRow in first until end) {
                val flags = tables.value(MetadataTables.METHOD_DEF, methodRow, MetadataTables.METHOD_FLAGS)
                if (flags and METHOD_SPECIAL_NAME != 0) {
                    continue
                }
                val access = flags and METHOD_ACCESS_MASK
                if (access != METHOD_PUBLIC && access != METHOD_FAMILY && access != METHOD_FAMILY_OR_ASSEMBLY) {
                    continue
                }
                val methodParameters = methodGenericParametersOf[methodRow]
                    ?.sortedBy { parameter -> parameter.first }
                    ?.map { parameter -> parameter.second }
                    ?: emptyList()
                val signature = image.blob(tables.value(MetadataTables.METHOD_DEF, methodRow, MetadataTables.METHOD_SIGNATURE))
                val returnType = signatures.returnType(signature, genericParameters, methodParameters) ?: continue
                if (returnType == VOID_TYPE) {
                    continue
                }
                val rawName = image.string(tables.value(MetadataTables.METHOD_DEF, methodRow, MetadataTables.METHOD_NAME))
                members.add(MetadataMember(Identifiers.sanitize(rawName), returnType, isMethod = true, genericParameters = methodParameters))
            }
            return members
        }

        private fun kindOf(flags: Int, baseName: String, qualifiedName: String): MetadataTypeKind {
            if (flags and INTERFACE_FLAG != 0) {
                return MetadataTypeKind.INTERFACE
            }
            // System.Enum itself derives from ValueType and is still a class, as is ValueType.
            if (qualifiedName == ENUM_BASE) {
                return MetadataTypeKind.CLASS
            }
            return when (baseName) {
                ENUM_BASE -> {
                    MetadataTypeKind.ENUM
                }
                VALUE_TYPE_BASE -> {
                    MetadataTypeKind.STRUCT
                }
                else -> {
                    MetadataTypeKind.CLASS
                }
            }
        }

        private fun layoutKindOf(flags: Int): DeclaredLayoutKind {
            return when (flags and LAYOUT_MASK) {
                LAYOUT_SEQUENTIAL -> {
                    DeclaredLayoutKind.SEQUENTIAL
                }
                LAYOUT_EXPLICIT -> {
                    DeclaredLayoutKind.EXPLICIT
                }
                else -> {
                    DeclaredLayoutKind.AUTO
                }
            }
        }

        private fun baseTypeNameOf(row: Int, genericParameters: List<String>): String {
            val extends = tables.value(MetadataTables.TYPE_DEF, row, MetadataTables.TYPE_DEF_EXTENDS)
            if (extends == 0) {
                return ""
            }
            return signatures.typeDefOrRefName(extends, genericParameters)
        }

        private fun fieldsOf(row: Int, typeCount: Int, genericParameters: List<String>): List<MetadataField> {
            val first = tables.value(MetadataTables.TYPE_DEF, row, MetadataTables.TYPE_DEF_FIELD_LIST)
            val end: Int
            if (row < typeCount) {
                end = tables.value(MetadataTables.TYPE_DEF, row + 1, MetadataTables.TYPE_DEF_FIELD_LIST)
            } else {
                end = tables.rowCount(MetadataTables.FIELD) + 1
            }
            val fields = ArrayList<MetadataField>()
            for (fieldRow in first until end) {
                val flags = tables.value(MetadataTables.FIELD, fieldRow, MetadataTables.FIELD_FLAGS)
                if (flags and (FIELD_STATIC or FIELD_LITERAL) != 0) {
                    continue
                }
                val rawName = image.string(tables.value(MetadataTables.FIELD, fieldRow, MetadataTables.FIELD_NAME))
                val signature = image.blob(tables.value(MetadataTables.FIELD, fieldRow, MetadataTables.FIELD_SIGNATURE))
                val typeName = signatures.fieldType(signature, genericParameters)
                val backingField = BACKING_FIELD.find(rawName)
                val name: String
                if (backingField != null) {
                    name = Identifiers.sanitize(backingField.groupValues[1])
                } else {
                    name = Identifiers.sanitize(rawName)
                }
                fields.add(
                    MetadataField(
                        name = name,
                        typeName = typeName,
                        explicitOffset = fieldOffsetOf[fieldRow] ?: MetadataField.NO_EXPLICIT_OFFSET,
                        isAutoPropertyBackingField = backingField != null,
                    )
                )
            }
            return fields
        }

        /** The type and every type enclosing it, outermost first. */
        private fun containerChainOf(row: Int): List<Int> {
            val chain = ArrayList<Int>()
            var current: Int? = row
            while (current != null && current !in chain) {
                chain.add(current)
                current = enclosingOf[current]
            }
            chain.reverse()
            return chain
        }

        private fun simpleNameOf(row: Int): String {
            val rawName = image.string(tables.value(MetadataTables.TYPE_DEF, row, MetadataTables.TYPE_DEF_NAME))
            return Identifiers.sanitize(rawName.substringBefore(ARITY_SEPARATOR))
        }

        private fun namespaceOf(row: Int): String {
            return image.string(tables.value(MetadataTables.TYPE_DEF, row, MetadataTables.TYPE_DEF_NAMESPACE))
        }

        /** `System.Collections.Generic.List.Enumerator`: namespace, containers, name, no arity. */
        fun typeDefinitionName(row: Int): String {
            val chain = containerChainOf(row)
            val parts = ArrayList<String>()
            val namespaceName = namespaceOf(chain.first())
            if (namespaceName.isNotEmpty()) {
                parts.add(namespaceName)
            }
            for (member in chain) {
                parts.add(simpleNameOf(member))
            }
            return parts.joinToString(".")
        }

        /** The same for a type another assembly defines; nesting is a chain of TypeRef scopes. */
        fun typeReferenceName(row: Int): String {
            val names = ArrayList<String>()
            var namespaceName = ""
            var current = row
            var steps = 0
            while (steps < MAX_NESTING) {
                val rawName = image.string(tables.value(MetadataTables.TYPE_REF, current, MetadataTables.TYPE_REF_NAME))
                names.add(Identifiers.sanitize(rawName.substringBefore(ARITY_SEPARATOR)))
                namespaceName = image.string(tables.value(MetadataTables.TYPE_REF, current, MetadataTables.TYPE_REF_NAMESPACE))
                val scope = tables.value(MetadataTables.TYPE_REF, current, MetadataTables.TYPE_REF_SCOPE)
                val tag = scope and ((1 shl MetadataTables.RESOLUTION_SCOPE_BITS) - 1)
                if (MetadataTables.RESOLUTION_SCOPE[tag] != MetadataTables.TYPE_REF) {
                    break
                }
                current = scope ushr MetadataTables.RESOLUTION_SCOPE_BITS
                steps++
            }
            names.reverse()
            if (namespaceName.isEmpty()) {
                return names.joinToString(".")
            }
            return namespaceName + "." + names.joinToString(".")
        }

        fun typeSpecificationName(row: Int, genericParameters: List<String>): String {
            val signature = image.blob(tables.value(MetadataTables.TYPE_SPEC, row, MetadataTables.TYPE_SPEC_SIGNATURE))
            return signatures.typeSpecification(signature, genericParameters)
        }

        companion object {
            private const val MAX_NESTING = 32
        }
    }
}
