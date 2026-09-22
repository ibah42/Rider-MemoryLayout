package com.memorylayout.layout

import com.memorylayout.layout.LayoutTestSupport.field
import com.memorylayout.layout.LayoutTestSupport.layoutOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a class costs on the heap. Every number here is what the CLR does, not what the engine
 * happens to produce: the header is 8 bytes before the reference, the method table pointer is 8
 * bytes at 0, the base class goes first, and an allocation is never under 24 bytes.
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
    fun theHeaderSitsBeforeTheReference() {
        val layout = layoutOf(source, "Enemy")
        val header = layout.nodes[0]
        assertEquals(NodeKind.RUNTIME, header.kind)
        assertEquals(-8, header.offset)
        assertEquals(8, header.size)
        assertEquals("object header", header.fieldName)
    }

    @Test
    fun theMethodTablePointerIsTheFirstEightBytes() {
        val layout = layoutOf(source, "Enemy")
        val handle = layout.nodes[1]
        assertEquals(NodeKind.RUNTIME, handle.kind)
        assertEquals(0, handle.offset)
        assertEquals(8, handle.size)
        assertEquals("MethodTable*", handle.typeName)
    }

    @Test
    fun theBaseClassComesFirstAndFieldsStartAfterThePointer() {
        val layout = layoutOf(source, "Enemy")
        assertEquals(8, field(layout, "layer").offset)
        assertEquals(12, field(layout, "health").offset)
        assertEquals(16, field(layout, "id").offset)
        assertEquals(24, field(layout, "transform").offset)
        assertEquals(32, field(layout, "team").offset)
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
    fun theSizeIsMeasuredFromTheReference() {
        val layout = layoutOf(source, "Enemy")
        assertEquals(40, layout.size)
        assertEquals(8, layout.alignment)
        assertEquals(LayoutConfidence.RUNTIME_DEFINED, layout.confidence)
        assertFalse(layout.isBlittable)
    }

    @Test
    fun aThirtyTwoBitRuntimeHalvesTheHeaderAndThePointer() {
        val layout = layoutOf(source, "Enemy", LayoutTarget.X86)
        assertEquals(-4, layout.nodes[0].offset)
        assertEquals(4, layout.nodes[0].size)
        assertEquals(4, field(layout, "layer").offset)
        assertEquals(16, field(layout, "transform").offset)
        assertEquals(24, layout.size)
    }

    @Test
    fun anEmptyClassStillCostsTheMinimumAllocation() {
        val layout = layoutOf(
            """
            class Marker
            {
            }
            """,
            "Marker",
        )
        // 16 from the reference plus the 8-byte header: the 24-byte floor of an allocation.
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
        assertEquals(8, field(layout, "layer").offset)
        assertEquals(12, field(layout, "power").offset)
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
        assertEquals(8, logger.offset)
        assertEquals(8, logger.size)
        assertTrue(logger.isReference)
        assertEquals(NodeKind.FIELD, logger.kind)
        assertEquals(16, layout.size)
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
}
