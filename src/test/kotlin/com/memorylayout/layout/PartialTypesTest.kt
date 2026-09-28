package com.memorylayout.layout

import com.memorylayout.layout.LayoutTestSupport.field
import com.memorylayout.layout.LayoutTestSupport.fieldNames
import com.memorylayout.layout.LayoutTestSupport.layoutOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialTypesTest {

    @Test
    fun readsTheFieldsOfEveryPart() {
        val source = """
            namespace Game
            {
                public partial struct Pair
                {
                    public int first;
                }

                public partial struct Pair
                {
                    public byte second;
                    public void Touch() { }
                }
            }
        """.trimIndent()
        val layout = layoutOf(source, "Pair")
        assertEquals(listOf("first", "second"), fieldNames(layout).filter { name -> name.isNotEmpty() })
        assertEquals(8, layout.size)
        assertEquals(4, field(layout, "second").offset)
        assertTrue(layout.notes.any { note -> note.contains("CS0282") })
    }

    /** The shape of UniTask: the attribute on one part, the fields on another. */
    @Test
    fun appliesAnAttributeFromAPartWithoutFields() {
        val source = """
            using System.Runtime.InteropServices;

            [StructLayout(LayoutKind.Sequential, Pack = 1)]
            public partial struct Packed
            {
                public static Packed Create() => default;
            }

            public readonly partial struct Packed
            {
                readonly byte flag;
                readonly int value;
            }
        """.trimIndent()
        val layout = layoutOf(source, "Packed")
        assertEquals(5, layout.size)
        assertEquals(1, field(layout, "value").offset)
        assertFalse(layout.notes.any { note -> note.contains("CS0282") })
        assertTrue(layout.notes.any { note -> note.startsWith("Partial: 2 declarations") })
    }

    @Test
    fun keepsPartsOfDifferentNamespacesApart() {
        val source = """
            namespace First
            {
                public partial struct Chunk { public int a; }
            }

            namespace Second
            {
                public partial struct Chunk { public long b; }
            }
        """.trimIndent()
        val layout = layoutOf(source, "Chunk")
        assertEquals(4, layout.size)
        assertEquals(listOf("a"), fieldNames(layout))
    }

    @Test
    fun keepsAGenericApartFromItsPlainNamesake() {
        val source = """
            public partial struct Box { public int plain; }
            public partial struct Box<T> { public T value; }
            public partial struct Box { public int second; }
        """.trimIndent()
        val layout = layoutOf(source, "Box")
        assertEquals(listOf("plain", "second"), fieldNames(layout))
        assertEquals(8, layout.size)
    }

    @Test
    fun sizesANestedPartialWhole() {
        val source = """
            public struct Holder
            {
                public Split inner;
                public byte tail;
            }

            public partial struct Split { public int x; }
            public partial struct Split { public int y; }
        """.trimIndent()
        val layout = layoutOf(source, "Holder")
        assertEquals(8, field(layout, "inner").size)
        assertEquals(8, field(layout, "tail").offset)
        assertEquals(12, layout.size)
    }

    @Test
    fun findsTheBaseClassOnAnotherPart() {
        val source = """
            public class Actor { public int health; }
            public partial class Enemy { public int damage; }
            public partial class Enemy : Actor { }
        """.trimIndent()
        val layout = layoutOf(source, "Enemy")
        assertEquals(16, field(layout, "health").offset)
        assertEquals(20, field(layout, "damage").offset)
    }

    @Test
    fun aFieldKnowsTheFileItCameFrom() {
        val firstMasked = CodeMask.of("public partial struct Spread { public int fromFirst; }")
        val secondMasked = CodeMask.of("public partial struct Spread { public int fromSecond; }")
        val firstPart = DeclaredType(TypeScanner.scan(firstMasked).single(), firstMasked, "A.cs")
        val secondPart = DeclaredType(TypeScanner.scan(secondMasked).single(), secondMasked, "B.cs")
        val merged = PartialTypes.merge(listOf(secondPart, firstPart))
        val layout = LayoutEngine(LayoutTarget.X64, SourceTypeLookup(firstMasked, "A.cs")).layoutOf(merged)
        assertEquals(listOf("fromFirst", "fromSecond"), fieldNames(layout))
        assertEquals("A.cs", field(layout, "fromFirst").fileId)
        assertEquals("B.cs", field(layout, "fromSecond").fileId)
        assertEquals("A.cs", merged.fileId)
    }

    @Test
    fun aLonePartialSaysNothingAboutParts() {
        val layout = layoutOf("public partial struct Lone { public int a; }", "Lone")
        assertEquals(4, layout.size)
        assertFalse(layout.notes.any { note -> note.startsWith("Partial:") })
    }
}
