package com.memorylayout.layout

import com.memorylayout.layout.LayoutTestSupport.field
import com.memorylayout.layout.LayoutTestSupport.layoutOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a class costs on the heap in Unity. Every number here is what Mono and IL2CPP do, not what
 * the engine happens to produce: the vtable pointer at 0, the monitor at 8, fields from 16, the
 * base class first, and each class's references ahead of its other fields.
 */
class ClassLayoutTest {

    private val source = """
        interface IThing { }

        class Transform
        {
            float x;
        }

        class Actor
        {
            int layer;
        }

        class Enemy : Actor
        {
            float health;
            int id;
            Transform transform;
            byte team;
        }
        """

    @Test
    fun theVtablePointerIsTheFirstEightBytes() {
        val layout = layoutOf(source, "Enemy")
        val vtable = layout.nodes[0]
        assertEquals(NodeKind.RUNTIME, vtable.kind)
        assertEquals(0, vtable.offset)
        assertEquals(8, vtable.size)
        assertEquals("vtable*", vtable.typeName)
    }

    @Test
    fun theMonitorFollowsTheVtable() {
        val layout = layoutOf(source, "Enemy")
        val monitor = layout.nodes[1]
        assertEquals(NodeKind.RUNTIME, monitor.kind)
        assertEquals(8, monitor.offset)
        assertEquals(8, monitor.size)
        assertEquals("monitor", monitor.fieldName)
    }

    @Test
    fun theBaseClassComesFirstAndReferencesLeadEachClass() {
        val layout = layoutOf(source, "Enemy")
        assertEquals(16, field(layout, "layer").offset)
        // Enemy's own fields: the reference first, then the rest in declaration order.
        assertEquals(24, field(layout, "transform").offset)
        assertEquals(32, field(layout, "health").offset)
        assertEquals(36, field(layout, "id").offset)
        assertEquals(40, field(layout, "team").offset)
    }

    @Test
    fun aSequentialClassKeepsItsDeclarationOrder() {
        val layout = layoutOf(
            """
            class Transform
            {
                float x;
            }

            [StructLayout(LayoutKind.Sequential)]
            class Plain
            {
                int id;
                Transform transform;
            }
            """,
            "Plain",
        )
        assertEquals(16, field(layout, "id").offset)
        assertEquals(24, field(layout, "transform").offset)
    }

    @Test
    fun aFieldOfClassTypeIsOneReference() {
        val layout = layoutOf(source, "Enemy")
        val transform = field(layout, "transform")
        assertTrue(transform.isReference)
        assertEquals(8, transform.size)
        assertTrue(transform.children.isEmpty())
    }

    @Test
    fun theSizeIsTheWholeObject() {
        val layout = layoutOf(source, "Enemy")
        assertEquals(48, layout.size)
        assertEquals(8, layout.alignment)
        assertEquals(LayoutConfidence.RUNTIME_DEFINED, layout.confidence)
        assertFalse(layout.isBlittable)
    }

    @Test
    fun aThirtyTwoBitRuntimeHalvesTheHeaderAndThePointer() {
        val layout = layoutOf(source, "Enemy", LayoutTarget.X86)
        assertEquals(0, layout.nodes[0].offset)
        assertEquals(4, layout.nodes[0].size)
        assertEquals(4, layout.nodes[1].offset)
        assertEquals(8, field(layout, "layer").offset)
        assertEquals(12, field(layout, "transform").offset)
        assertEquals(28, layout.size)
    }

    @Test
    fun anEmptyClassIsJustItsHeader() {
        val layout = layoutOf(
            """
            class Marker
            {
            }
            """,
            "Marker",
        )
        // The vtable and the monitor, and nothing else.
        assertEquals(16, layout.size)
    }

    @Test
    fun anInterfaceInTheBaseListIsNotABaseClass() {
        val layout = layoutOf(
            """
            interface IThing { }

            class Actor
            {
                int layer;
            }

            class Hero : IThing, Actor
            {
                int power;
            }
            """,
            "Hero",
        )
        assertEquals(16, field(layout, "layer").offset)
        assertEquals(20, field(layout, "power").offset)
    }

    @Test
    fun theNotesSayWhatTheHeaderCostsAndThatTheOrderIsNotAPromise() {
        val layout = layoutOf(source, "Enemy")
        assertTrue(layout.notes.any { note -> note.contains("48 B") })
        assertTrue(layout.notes.any { note -> note.contains("LayoutKind.Auto") })
    }

    @Test
    fun anInterfaceFromAnAssemblyIsStillOnePointer() {
        // The shape that first showed this up: ILogger lives in a package, not in the project, so
        // the index cannot find it. Sized at nothing it left the class 8 bytes of padding and an
        // offset nobody could trust.
        val layout = layoutOf(
            """
            class AudioService
            {
                ILogger _logger;
            }
            """,
            "AudioService",
        )
        val logger = field(layout, "_logger")
        assertEquals(16, logger.offset)
        assertEquals(8, logger.size)
        assertTrue(logger.isReference)
        assertEquals(NodeKind.FIELD, logger.kind)
        assertEquals(24, layout.size)
        assertEquals(0, layout.paddingBytes)
        assertTrue(layout.notes.any { note -> note.contains("name says interface") })
    }

    @Test
    fun theInterfaceRuleDoesNotCatchTypesThatMerelyStartWithI() {
        val layout = layoutOf(
            """
            struct Handles
            {
                IntPtr handle;
                Int32 count;
            }
            """,
            "Handles",
        )
        assertEquals(8, field(layout, "handle").size)
        assertFalse(field(layout, "handle").isReference)
        assertEquals(4, field(layout, "count").size)
        assertFalse(field(layout, "count").isReference)
    }

    @Test
    fun aStructIsStillLaidOutFromZero() {
        val layout = layoutOf(
            """
            struct Point
            {
                int x;
                int y;
            }
            """,
            "Point",
        )
        assertEquals(0, layout.nodes[0].offset)
        assertEquals(NodeKind.FIELD, layout.nodes[0].kind)
        assertEquals(8, layout.size)
    }

    @Test
    fun aNullableReferenceIsOnePointerNotANullable() {
        val layout = layoutOf(
            """
            class Failure
            {
                string message;
            }

            struct Entry
            {
                Failure? error;
                string? name;
                int? count;
            }
            """,
            "Entry",
        )
        // `Failure?` and `string?` are the same pointer as without the `?`; only a value type
        // becomes Nullable<T>, with its flag in front of the value.
        val error = field(layout, "error")
        assertTrue(error.isReference)
        assertEquals(8, error.size)
        assertTrue(error.children.isEmpty())
        val name = field(layout, "name")
        assertTrue(name.isReference)
        assertEquals(8, name.size)
        assertEquals(8, field(layout, "count").size)
        assertEquals(2, field(layout, "count").children.count { child -> child.kind == NodeKind.FIELD })
        assertEquals(24, layout.size)
    }
}
