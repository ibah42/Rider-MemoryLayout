package com.memorylayout.metadata

import com.memorylayout.layout.CodeMask
import com.memorylayout.layout.DeclaredLayoutKind
import com.memorylayout.layout.LayoutConfidence
import com.memorylayout.layout.LayoutEngine
import com.memorylayout.layout.LayoutNode
import com.memorylayout.layout.LayoutTarget
import com.memorylayout.layout.LookupContext
import com.memorylayout.layout.NodeKind
import com.memorylayout.layout.SourceTypeLookup
import com.memorylayout.layout.TypeLayout
import com.memorylayout.layout.VariableType
import com.memorylayout.layout.VariableTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataSourceTest {

    private fun type(
        name: String,
        kind: MetadataTypeKind,
        fields: List<MetadataField>,
        namespaceName: String = "System",
        containerNames: List<String> = emptyList(),
        genericParameters: List<String> = emptyList(),
        layoutKind: DeclaredLayoutKind = defaultLayoutOf(kind),
        pack: Int = MetadataType.UNSET,
        classSize: Int = MetadataType.UNSET,
    ): MetadataType {
        return MetadataType(
            assemblyName = "mscorlib.dll",
            namespaceName = namespaceName,
            name = name,
            containerNames = containerNames,
            genericParameters = genericParameters,
            kind = kind,
            isPublic = true,
            baseTypeName = "",
            layoutKind = layoutKind,
            pack = pack,
            classSize = classSize,
            fields = fields,
        )
    }

    private fun defaultLayoutOf(kind: MetadataTypeKind): DeclaredLayoutKind {
        if (kind == MetadataTypeKind.CLASS) {
            return DeclaredLayoutKind.AUTO
        }
        return DeclaredLayoutKind.SEQUENTIAL
    }

    private fun field(typeName: String, name: String, offset: Int = MetadataField.NO_EXPLICIT_OFFSET): MetadataField {
        return MetadataField(name = name, typeName = typeName, explicitOffset = offset)
    }

    private fun layoutOf(lookup: MetadataTypeLookup, typeName: String): TypeLayout {
        val declared = lookup.resolve(typeName, LookupContext.EMPTY)
            ?: throw AssertionError("no type $typeName")
        return LayoutEngine(LayoutTarget.X64, lookup).layoutOf(declared)
    }

    private fun node(layout: TypeLayout, fieldName: String): LayoutNode {
        return layout.nodes.firstOrNull { node -> node.kind != NodeKind.PADDING && node.fieldName == fieldName }
            ?: throw AssertionError("no field $fieldName")
    }

    @Test
    fun laysOutAStructFromItsFields() {
        val guid = type(
            "Guid",
            MetadataTypeKind.STRUCT,
            listOf(field("int", "_a"), field("short", "_b"), field("short", "_c"), field("byte", "_d")),
        )
        val layout = layoutOf(MetadataTypeLookup(listOf(guid)), "Guid")
        assertEquals(12, layout.size)
        assertEquals(4, layout.alignment)
        assertEquals(8, node(layout, "_d").offset)
        assertEquals(LayoutConfidence.EXACT, layout.confidence)
        assertTrue(layout.notes.any { note -> note.contains("mscorlib.dll") })
    }

    @Test
    fun honoursExplicitOffsets() {
        val decimal = type(
            "Decimal",
            MetadataTypeKind.STRUCT,
            listOf(field("int", "flags", 0), field("int", "hi", 4), field("int", "lo", 8), field("int", "mid", 12), field("ulong", "ulomidLE", 8)),
            layoutKind = DeclaredLayoutKind.EXPLICIT,
        )
        val layout = layoutOf(MetadataTypeLookup(listOf(decimal)), "Decimal")
        assertEquals(16, layout.size)
        assertEquals(8, node(layout, "ulomidLE").offset)
    }

    @Test
    fun honoursPackAndSize() {
        val packed = type("Packed", MetadataTypeKind.STRUCT, listOf(field("byte", "flag"), field("int", "value")), pack = 1)
        val sized = type("Sized", MetadataTypeKind.STRUCT, listOf(field("byte", "first")), classSize = 16)
        val lookup = MetadataTypeLookup(listOf(packed, sized))
        assertEquals(5, layoutOf(lookup, "Packed").size)
        assertEquals(16, layoutOf(lookup, "Sized").size)
    }

    @Test
    fun laysOutAGenericClassWhoseFieldsAreAllSized() {
        val list = type(
            "List",
            MetadataTypeKind.CLASS,
            listOf(field("T[]", "_items"), field("int", "_size"), field("int", "_version"), field("object", "_syncRoot")),
            namespaceName = "System.Collections.Generic",
            genericParameters = listOf("T"),
        )
        val layout = layoutOf(MetadataTypeLookup(listOf(list)), "List<T>")
        // Mono's order: the two references, then the two ints.
        assertEquals(16, node(layout, "_items").offset)
        assertEquals(24, node(layout, "_syncRoot").offset)
        assertEquals(32, node(layout, "_size").offset)
        assertEquals(36, node(layout, "_version").offset)
        assertEquals(40, layout.size)
    }

    @Test
    fun aBareNameFindsTheOnlyGenericOfThatName() {
        val list = type("List", MetadataTypeKind.CLASS, listOf(field("int", "_size")), genericParameters = listOf("T"))
        val lookup = MetadataTypeLookup(listOf(list))
        assertEquals(1, lookup.rankedCandidates("List", LookupContext.EMPTY).size)
    }

    @Test
    fun sizesAnEnumFieldByItsUnderlyingType() {
        val kind = type("Kind", MetadataTypeKind.ENUM, listOf(field("byte", "value__")))
        val holder = type("Holder", MetadataTypeKind.STRUCT, listOf(field("System.Kind", "kind"), field("System.Kind", "other")))
        val layout = layoutOf(MetadataTypeLookup(listOf(kind, holder)), "Holder")
        assertEquals(2, layout.size)
    }

    @Test
    fun resolvesANestedGenericByItsQualifiedName() {
        val pair = type(
            "KeyValuePair",
            MetadataTypeKind.STRUCT,
            listOf(field("TKey", "key"), field("TValue", "value")),
            namespaceName = "System.Collections.Generic",
            genericParameters = listOf("TKey", "TValue"),
        )
        val entry = type(
            "Entry",
            MetadataTypeKind.STRUCT,
            listOf(field("int", "hashCode"), field("System.Collections.Generic.KeyValuePair<TKey, TValue>", "pair")),
            namespaceName = "System.Collections.Generic",
            containerNames = listOf("Dictionary"),
            genericParameters = listOf("TKey", "TValue"),
        )
        val user = type(
            "User",
            MetadataTypeKind.STRUCT,
            listOf(field("System.Collections.Generic.Dictionary.Entry<int, long>", "entry")),
        )
        val layout = layoutOf(MetadataTypeLookup(listOf(pair, entry, user)), "User")
        assertEquals(24, layout.size)
        assertEquals(LayoutConfidence.EXACT, layout.confidence)
    }

    @Test
    fun readsAKeywordAsItsFrameworkType() {
        val string = type("String", MetadataTypeKind.CLASS, listOf(field("int", "_stringLength"), field("char", "_firstChar")))
        val layout = layoutOf(MetadataTypeLookup(listOf(string)), "string")
        assertEquals(16, node(layout, "_stringLength").offset)
        // _firstChar is the first of the characters: one repeating row from 20, two bytes apart,
        // with the terminating zero counted in. The empty string is 22 bytes, no tail padding.
        val chars = node(layout, "chars")
        assertEquals(NodeKind.REPEAT, chars.kind)
        assertEquals(20, chars.offset)
        assertEquals(2, chars.repeatStride)
        assertEquals("n + 1", chars.repeatCountText)
        assertEquals(22, layout.size)
        assertEquals(0, layout.paddingBytes)
        assertTrue(layout.notes.any { note -> note.contains("2·n") })
    }

    @Test
    fun aCountedStringIsTheWholeAllocation() {
        val string = type("String", MetadataTypeKind.CLASS, listOf(field("int", "_stringLength"), field("char", "_firstChar")))
        val lookup = MetadataTypeLookup(listOf(string))
        val declared = lookup.resolve("string", LookupContext.EMPTY) ?: throw AssertionError("no string")
        val layout = LayoutEngine(LayoutTarget.X64, lookup).layoutOf(declared, emptyList(), 4)
        // 20 + 2 * (4 + 1) = 30 bytes of string, 32 once the collector rounds it.
        assertEquals(32, layout.size)
        assertEquals(2, layout.paddingBytes)
        assertEquals("5", node(layout, "chars").repeatCountText)
    }

    @Test
    fun marksAnAutoPropertyBackingField() {
        val holder = type(
            "Holder",
            MetadataTypeKind.STRUCT,
            listOf(MetadataField(name = "Value", typeName = "int", isAutoPropertyBackingField = true)),
        )
        val layout = layoutOf(MetadataTypeLookup(listOf(holder)), "Holder")
        assertTrue(node(layout, "Value").isAutoProperty)
    }

    @Test
    fun survivesAFieldNamedLikeAKeyword() {
        val holder = type("Holder", MetadataTypeKind.STRUCT, listOf(field("int", Identifiers.sanitize("class"))))
        val layout = layoutOf(MetadataTypeLookup(listOf(holder)), "Holder")
        assertEquals(4, layout.size)
        assertEquals("class_", node(layout, "class_").fieldName)
    }

    @Test
    fun theProjectAnswersBeforeTheAssemblies() {
        val runtimeEntry = type("Entry", MetadataTypeKind.STRUCT, listOf(field("long", "wide")))
        val masked = CodeMask.of("public struct Entry { public byte narrow; }")
        val lookup = CompositeTypeLookup(listOf(SourceTypeLookup(masked, "Test.cs"), MetadataTypeLookup(listOf(runtimeEntry))))
        val resolved = lookup.resolve("Entry", LookupContext.EMPTY) ?: throw AssertionError("no Entry")
        assertEquals("Test.cs", resolved.fileId)
    }

    @Test
    fun sanitizesCompilerGeneratedNames() {
        assertEquals("_buffer_e__FixedBuffer", Identifiers.sanitize("<buffer>e__FixedBuffer"))
        assertEquals("_1st", Identifiers.sanitize("1st"))
        assertEquals("plain", Identifiers.sanitize("plain"))
    }
}

class MetadataMembersTest {

    private fun unityTypes(): List<MetadataType> {
        fun type(name: String, kind: MetadataTypeKind, base: String, fields: List<MetadataField>, members: List<MetadataMember>): MetadataType {
            return MetadataType(
                assemblyName = "UnityEngine.CoreModule.dll",
                namespaceName = "UnityEngine",
                name = name,
                containerNames = emptyList(),
                genericParameters = emptyList(),
                kind = kind,
                isPublic = true,
                baseTypeName = base,
                layoutKind = if (kind == MetadataTypeKind.CLASS) DeclaredLayoutKind.AUTO else DeclaredLayoutKind.SEQUENTIAL,
                pack = MetadataType.UNSET,
                classSize = MetadataType.UNSET,
                fields = fields,
                members = members,
            )
        }
        return listOf(
            type(
                "Vector3", MetadataTypeKind.STRUCT, "",
                listOf(MetadataField("x", "float"), MetadataField("y", "float"), MetadataField("z", "float")),
                listOf(
                    MetadataMember("magnitude", "float", isMethod = false),
                    MetadataMember("normalized", "UnityEngine.Vector3", isMethod = false),
                    MetadataMember("Scale", "UnityEngine.Vector3", isMethod = true),
                ),
            ),
            type("Object", MetadataTypeKind.CLASS, "", emptyList(), listOf(MetadataMember("name", "string", isMethod = false))),
            type(
                "Component", MetadataTypeKind.CLASS, "UnityEngine.Object", emptyList(),
                listOf(
                    MetadataMember("transform", "UnityEngine.Transform", isMethod = false),
                    MetadataMember("GetComponent", "T", isMethod = true, genericParameters = listOf("T")),
                ),
            ),
            type("Behaviour", MetadataTypeKind.CLASS, "UnityEngine.Component", emptyList(), emptyList()),
            type("MonoBehaviour", MetadataTypeKind.CLASS, "UnityEngine.Behaviour", emptyList(), emptyList()),
            type(
                "Transform", MetadataTypeKind.CLASS, "UnityEngine.Component", emptyList(),
                listOf(MetadataMember("position", "UnityEngine.Vector3", isMethod = false)),
            ),
            type("Rigidbody", MetadataTypeKind.CLASS, "UnityEngine.Component", emptyList(), emptyList()),
        )
    }

    private fun typeAt(sourceWithCaret: String): String {
        val caret = sourceWithCaret.indexOf('|')
        val source = sourceWithCaret.replace("|", "")
        val masked = CodeMask.of(source)
        val lookup = CompositeTypeLookup(listOf(SourceTypeLookup(masked, "Player.cs"), MetadataTypeLookup(unityTypes())))
        val result = VariableTypes.at(masked, source, caret, "Player.cs", lookup)
        if (result !is VariableType.Declared) {
            throw AssertionError("expected a declared type, got $result")
        }
        return result.typeName
    }

    @Test
    fun followsAPropertyOfABaseClassInAnAssembly() {
        val source = "using UnityEngine; class Player : MonoBehaviour { void Update() { transform.posi|tion = default; } }"
        assertEquals("UnityEngine.Vector3", typeAt(source))
    }

    @Test
    fun followsAGenericMethodOfAnAssembly() {
        val source = "using UnityEngine; class Player : MonoBehaviour { void Awake() { var body = GetComponent<Rigidbody>(); bo|dy.name = null; } }"
        assertEquals("Rigidbody", typeAt(source))
    }

    @Test
    fun followsAPropertyOfAStructInAnAssembly() {
        val source = "using UnityEngine; class Player : MonoBehaviour { void Update() { transform.position.normal|ized.x = 0; } }"
        assertEquals("UnityEngine.Vector3", typeAt(source))
    }

    @Test
    fun membersTakeNoRoomInTheLayout() {
        val lookup = MetadataTypeLookup(unityTypes())
        val vector = lookup.resolve("UnityEngine.Vector3", LookupContext.EMPTY) ?: throw AssertionError("no Vector3")
        val layout = LayoutEngine(LayoutTarget.X64, lookup).layoutOf(vector)
        assertEquals(12, layout.size)
        assertEquals(3, layout.nodes.count { node -> node.kind == NodeKind.FIELD })
    }
}
