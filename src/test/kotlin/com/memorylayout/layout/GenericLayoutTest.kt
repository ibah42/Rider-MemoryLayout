package com.memorylayout.layout

import com.memorylayout.layout.LayoutTestSupport.field
import com.memorylayout.layout.LayoutTestSupport.layoutOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generic types. A written `Box<int>` has a size; the declaration `Box<T>` does not, and saying so
 * is the point -- a window that showed zero for `T` would be inventing a number.
 */
class GenericLayoutTest {

    private val source = """
        struct Pair<TFirst, TSecond>
        {
            TFirst first;
            TSecond second;
        }

        struct Box<T>
        {
            T value;
            int tag;
        }

        class Repo<T>
        {
            T item;
        }

        struct Holder
        {
            Box<int> boxed;
            Box<double> wide;
            Pair<int, byte> pair;
        }
        """

    @Test
    fun anArgumentGivesTheParameterItsSize() {
        val layout = layoutOf(source, "Holder")
        assertEquals(8, field(layout, "boxed").size)
        assertEquals(4, field(layout, "boxed").alignment)
        assertEquals(16, field(layout, "wide").size)
        assertEquals(8, field(layout, "wide").alignment)
        assertEquals(8, field(layout, "pair").size)
        assertEquals(32, layout.size)
        assertEquals(LayoutConfidence.EXACT, layout.confidence)
    }

    @Test
    fun theSameDeclarationIsTwoDifferentSizesUnderTwoArguments() {
        val layout = layoutOf(source, "Holder")
        val narrow = field(layout, "boxed").children.first { node -> node.fieldName == "value" }
        val wide = field(layout, "wide").children.first { node -> node.fieldName == "value" }
        assertEquals("int", narrow.typeName)
        assertEquals(4, narrow.size)
        assertEquals("double", wide.typeName)
        assertEquals(8, wide.size)
    }

    @Test
    fun theMembersOfAnInstantiationCarryAbsoluteOffsets() {
        val layout = layoutOf(source, "Holder")
        val boxed = field(layout, "boxed")
        assertEquals(0, boxed.children.first { node -> node.fieldName == "value" }.offset)
        assertEquals(4, boxed.children.first { node -> node.fieldName == "tag" }.offset)
        val wide = field(layout, "wide")
        assertEquals(8, wide.children.first { node -> node.fieldName == "value" }.offset)
        assertEquals(16, wide.children.first { node -> node.fieldName == "tag" }.offset)
    }

    @Test
    fun twoParametersAreBoundInOrder() {
        val layout = layoutOf(source, "Holder")
        val pair = field(layout, "pair")
        assertEquals(24, pair.offset)
        assertEquals(24, pair.children.first { node -> node.fieldName == "first" }.offset)
        assertEquals(28, pair.children.first { node -> node.fieldName == "second" }.offset)
        assertEquals(4, pair.children.first { node -> node.fieldName == "first" }.size)
        assertEquals(1, pair.children.first { node -> node.fieldName == "second" }.size)
    }

    @Test
    fun anArgumentMayItselfBeGeneric() {
        val layout = layoutOf(
            """
            struct Pair<TFirst, TSecond>
            {
                TFirst first;
                TSecond second;
            }

            struct Box<T>
            {
                T value;
                int tag;
            }

            struct Nested
            {
                Box<Pair<int, byte>> deep;
            }
            """,
            "Nested",
        )
        // Pair<int, byte> is 8 bytes, and Box adds its own int.
        assertEquals(12, field(layout, "deep").size)
        assertEquals(12, layout.size)
    }

    @Test
    fun anOpenDeclarationHasNoSizeAndSaysSo() {
        val layout = layoutOf(source, "Box")
        val value = field(layout, "value")
        assertEquals(NodeKind.UNRESOLVED, value.kind)
        assertEquals(0, value.size)
        assertEquals(LayoutConfidence.APPROXIMATE, layout.confidence)
        assertTrue(layout.notes.any { note -> note.contains("no size here") })
    }

    @Test
    fun aGenericClassFieldIsStillOneReference() {
        val layout = layoutOf(
            """
            class Repo<T>
            {
                T item;
            }

            struct Uses
            {
                Repo<int> repo;
                int tag;
            }
            """,
            "Uses",
        )
        val repo = field(layout, "repo")
        assertTrue(repo.isReference)
        assertEquals(8, repo.size)
        assertTrue(repo.children.isEmpty())
        assertEquals(16, layout.size)
    }

    @Test
    fun arityTellsTwoDeclarationsOfTheSameNameApart() {
        val layout = layoutOf(
            """
            struct Thing
            {
                int a;
            }

            struct Thing<T>
            {
                T a;
                long b;
            }

            struct Uses
            {
                Thing plain;
                Thing<int> generic;
            }
            """,
            "Uses",
        )
        assertEquals(4, field(layout, "plain").size)
        assertEquals(16, field(layout, "generic").size)
    }

    @Test
    fun aGenericTheProjectDoesNotDeclareIsOneReference() {
        // List lives in the BCL, not in the project, so the index cannot find it. Every generic
        // collection and delegate there is a class, and a class field is a pointer -- sizing it
        // at nothing used to move every field after it to the wrong offset.
        val layout = layoutOf(
            """
            class Service
            {
                List<int> items;
                int tag;
            }
            """,
            "Service",
        )
        val items = field(layout, "items")
        assertEquals(8, items.size)
        assertEquals(8, items.alignment)
        assertTrue(items.isReference)
        assertEquals(NodeKind.FIELD, items.kind)
        assertEquals(24, field(layout, "tag").offset)
        assertEquals(32, layout.size)
        assertTrue(layout.notes.any { note -> note.contains("not declared in the project") })
    }

    @Test
    fun aGenericValueTypeOfTheRuntimeIsNotTakenForAClass() {
        val layout = layoutOf(
            """
            struct Window
            {
                Span<byte> bytes;
                int count;
            }
            """,
            "Window",
        )
        // A byref and a length: sixteen bytes on a 64-bit runtime, and not a pointer.
        assertEquals(16, field(layout, "bytes").size)
        assertEquals(8, field(layout, "bytes").alignment)
        assertEquals(16, field(layout, "count").offset)
        assertEquals(24, layout.size)
    }

    @Test
    fun aProjectGenericStructStillWinsOverTheGuess() {
        val layout = layoutOf(source, "Holder")
        // Box is declared here, so it is laid out rather than assumed to be a class.
        assertEquals(8, field(layout, "boxed").size)
        assertTrue(field(layout, "boxed").children.isNotEmpty())
    }

    @Test
    fun aPlainNameTheIndexCannotFindIsStillUnknown() {
        val layout = layoutOf(
            """
            struct Holder
            {
                Missing gap;
                int after;
            }
            """,
            "Holder",
        )
        assertEquals(NodeKind.UNRESOLVED, field(layout, "gap").kind)
        assertEquals(LayoutConfidence.APPROXIMATE, layout.confidence)
    }

    @Test
    fun aGenericStructInsideItselfUnderAnotherArgumentIsNotACycle() {
        val layout = layoutOf(
            """
            struct Box<T>
            {
                T value;
            }

            struct Uses
            {
                Box<Box<int>> twice;
            }
            """,
            "Uses",
        )
        assertEquals(4, field(layout, "twice").size)
        assertEquals(LayoutConfidence.EXACT, layout.confidence)
    }
}
