package com.memorylayout.layout

import com.memorylayout.layout.LayoutTestSupport.field
import com.memorylayout.layout.LayoutTestSupport.fieldNames
import com.memorylayout.layout.LayoutTestSupport.layoutOf
import com.memorylayout.layout.LayoutTestSupport.topLevelPadding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expected number here is what the CLR actually does on x64, not what the engine happens to
 * produce. When one of them changes, the question is which of the two is wrong -- see the C#
 * cross-check described in README.md.
 */
class LayoutEngineTest {

    @Test
    fun padsBetweenFields() {
        val layout = layoutOf(
            """
            struct Small
            {
                byte flag;
                int value;
            }
            """,
            "Small",
        )
        assertEquals(8, layout.size)
        assertEquals(4, layout.alignment)
        assertEquals(0, field(layout, "flag").offset)
        assertEquals(4, field(layout, "value").offset)
        assertEquals(listOf(Pair(1, 3)), topLevelPadding(layout))
        assertEquals(3, layout.paddingBytes)
        assertEquals(LayoutConfidence.EXACT, layout.confidence)
        assertTrue(layout.isBlittable)
    }

    @Test
    fun padsTheTailToTheStructAlignment() {
        val layout = layoutOf(
            """
            struct Tail
            {
                int value;
                byte flag;
            }
            """,
            "Tail",
        )
        assertEquals(8, layout.size)
        assertEquals(listOf(Pair(5, 3)), topLevelPadding(layout))
    }

    @Test
    fun packsFieldsWhenPackIsOne() {
        val layout = layoutOf(
            """
            [StructLayout(LayoutKind.Sequential, Pack = 1)]
            struct Packed
            {
                byte flag;
                int value;
            }
            """,
            "Packed",
        )
        assertEquals(5, layout.size)
        assertEquals(1, layout.alignment)
        assertEquals(1, field(layout, "value").offset)
        assertEquals(0, layout.paddingBytes)
    }

    @Test
    fun raisesTheTotalToTheDeclaredSize() {
        val layout = layoutOf(
            """
            [StructLayout(LayoutKind.Sequential, Size = 16)]
            struct Reserved
            {
                int value;
            }
            """,
            "Reserved",
        )
        assertEquals(16, layout.size)
        assertEquals(listOf(Pair(4, 12)), topLevelPadding(layout))
    }

    @Test
    fun anEmptyStructStillTakesOneByte() {
        val layout = layoutOf("struct Empty { }", "Empty")
        assertEquals(1, layout.size)
        assertEquals(1, layout.alignment)
    }

    @Test
    fun explicitOffsetsOverlap() {
        val layout = layoutOf(
            """
            [StructLayout(LayoutKind.Explicit)]
            struct Union
            {
                [FieldOffset(0)] int asInt;
                [FieldOffset(0)] float asFloat;
                [FieldOffset(4)] byte tag;
            }
            """,
            "Union",
        )
        assertEquals(8, layout.size)
        assertEquals(4, layout.alignment)
        assertEquals(0, field(layout, "asInt").offset)
        assertEquals(0, field(layout, "asFloat").offset)
        assertEquals(4, field(layout, "tag").offset)
        assertTrue(field(layout, "asFloat").overlapsPrevious)
    }

    @Test
    fun nestedStructChildrenCarryAbsoluteOffsets() {
        val layout = layoutOf(
            """
            struct Inner
            {
                int value;
                byte flag;
            }

            struct Outer
            {
                byte header;
                Inner inner;
            }
            """,
            "Outer",
        )
        assertEquals(12, layout.size)
        assertEquals(4, layout.alignment)
        assertEquals(4, field(layout, "inner").offset)
        assertEquals(8, field(layout, "inner").size)
        assertEquals(4, field(layout, "value").offset)
        assertEquals(8, field(layout, "flag").offset)
        // Three bytes before the nested struct, three inside its own tail.
        assertEquals(6, layout.paddingBytes)
    }

    @Test
    fun constantsAndStaticsTakeNoSpace() {
        val layout = layoutOf(
            """
            struct WithStatics
            {
                public const int Limit = 4;
                public static int Shared;
                public int value;
            }
            """,
            "WithStatics",
        )
        assertEquals(listOf("value"), fieldNames(layout))
        assertEquals(4, layout.size)
    }

    @Test
    fun autoPropertiesTakeSpaceAndRealOnesDoNot() {
        val layout = layoutOf(
            """
            struct Properties
            {
                public int Stored { get; set; }
                public byte Flag { get; init; }
                public int Computed => Stored + 1;
                public int WithBody
                {
                    get { return Stored; }
                }
            }
            """,
            "Properties",
        )
        assertEquals(listOf("Stored", "Flag"), fieldNames(layout))
        assertTrue(field(layout, "Stored").isAutoProperty)
        assertEquals(8, layout.size)
    }

    @Test
    fun fixedBufferTakesItsWholeLength() {
        val layout = layoutOf(
            """
            unsafe struct Buffer
            {
                fixed byte data[10];
                int value;
            }
            """,
            "Buffer",
        )
        assertEquals(10, field(layout, "data").size)
        assertEquals(12, field(layout, "value").offset)
        assertEquals(16, layout.size)
    }

    @Test
    fun severalDeclaratorsShareOneType() {
        val layout = layoutOf(
            """
            struct Several
            {
                public int first, second;
                public byte flag;
            }
            """,
            "Several",
        )
        assertEquals(listOf("first", "second", "flag"), fieldNames(layout))
        assertEquals(4, field(layout, "second").offset)
        assertEquals(12, layout.size)
    }

    @Test
    fun nullableValueTypeCarriesItsFlag() {
        val layout = layoutOf(
            """
            struct WithNullable
            {
                int? value;
            }
            """,
            "WithNullable",
        )
        assertEquals(8, layout.size)
        assertEquals(4, layout.alignment)
        assertFalse(layout.isBlittable)
    }

    @Test
    fun aReferenceFieldIsPointerSizedAndNotBlittable() {
        val source = """
            struct WithReference
            {
                string name;
                int value;
            }
            """
        val onSixtyFour = layoutOf(source, "WithReference", LayoutTarget.X64)
        assertEquals(16, onSixtyFour.size)
        assertEquals(8, onSixtyFour.alignment)
        assertEquals(8, field(onSixtyFour, "value").offset)
        assertFalse(onSixtyFour.isBlittable)

        val onThirtyTwo = layoutOf(source, "WithReference", LayoutTarget.X86)
        assertEquals(8, onThirtyTwo.size)
        assertEquals(4, onThirtyTwo.alignment)
        assertEquals(4, field(onThirtyTwo, "value").offset)
    }

    @Test
    fun anEnumFieldIsItsUnderlyingType() {
        val layout = layoutOf(
            """
            enum State : byte
            {
                Idle,
                Running,
            }

            struct WithEnum
            {
                State state;
                int value;
            }
            """,
            "WithEnum",
        )
        assertEquals(1, field(layout, "state").size)
        assertEquals(4, field(layout, "value").offset)
        assertEquals(8, layout.size)
    }

    @Test
    fun aClassFieldIsJustAReference() {
        val layout = layoutOf(
            """
            class Payload
            {
                public long first;
                public long second;
            }

            struct Holder
            {
                Payload payload;
            }
            """,
            "Holder",
        )
        assertEquals(8, field(layout, "payload").size)
        assertEquals(8, layout.size)
        assertFalse(layout.isBlittable)
    }

    @Test
    fun anUnknownTypeMakesTheLayoutApproximate() {
        val layout = layoutOf(
            """
            struct Unknown
            {
                MissingType missing;
                int value;
            }
            """,
            "Unknown",
        )
        assertEquals(LayoutConfidence.APPROXIMATE, layout.confidence)
        assertEquals(NodeKind.UNRESOLVED, field(layout, "missing").kind)
        assertTrue(layout.notes.any { note -> note.contains("could not be resolved") })
    }

    @Test
    fun aClassIsLaidOutByTheRuntime() {
        val layout = layoutOf(
            """
            class Node
            {
                byte flag;
                long value;
            }
            """,
            "Node",
        )
        assertEquals(LayoutConfidence.RUNTIME_DEFINED, layout.confidence)
    }

    @Test
    fun aPositionalRecordStructHasFieldsWithoutABody() {
        val layout = layoutOf("record struct Point(int X, int Y);", "Point")
        assertEquals(listOf("X", "Y"), fieldNames(layout))
        assertEquals(8, layout.size)
    }

    @Test
    fun unityMathematicsTypesAreKnownWithoutSources() {
        val layout = layoutOf(
            """
            struct Particle
            {
                float3 position;
                float lifetime;
                quaternion rotation;
            }
            """,
            "Particle",
        )
        assertEquals(0, field(layout, "position").offset)
        assertEquals(12, field(layout, "lifetime").offset)
        assertEquals(16, field(layout, "rotation").offset)
        assertEquals(32, layout.size)
        assertTrue(layout.isBlittable)
    }

    @Test
    fun boolAndCharAreSizedButNotBlittable() {
        val layout = layoutOf(
            """
            struct Flags
            {
                bool visible;
                char initial;
            }
            """,
            "Flags",
        )
        assertEquals(0, field(layout, "visible").offset)
        assertEquals(2, field(layout, "initial").offset)
        assertEquals(4, layout.size)
        assertFalse(layout.isBlittable)
        assertEquals(2, layout.blittableProblems.size)
    }

    @Test
    fun aFieldInitializerIsNotAMemberBody() {
        val layout = layoutOf(
            """
            struct WithInitializer
            {
                public Vector3 position = new Vector3 { x = 1 };
                public int value;
            }
            """,
            "WithInitializer",
        )
        assertEquals(listOf("position", "value"), fieldNames(layout))
        assertEquals(16, layout.size)
    }

    @Test
    fun bracesInsideStringsAndCommentsAreIgnored() {
        val layout = layoutOf(
            """
            struct Tricky
            {
                // } int commented;
                string label = "} not a brace {";
                int value;
            }
            """,
            "Tricky",
        )
        assertEquals(listOf("label", "value"), fieldNames(layout))
        assertEquals(16, layout.size)
    }

    @Test
    fun anInterfaceFieldIsOneReference() {
        // An interface reference is a plain object reference: there is no vtable pointer in the
        // field, the dispatch goes through the method table of whatever it points at.
        val layout = layoutOf(
            """
            interface IPayload { }

            struct Holder
            {
                IPayload payload;
                byte tag;
            }
            """,
            "Holder",
        )
        assertEquals(8, field(layout, "payload").size)
        assertEquals(8, field(layout, "payload").alignment)
        assertTrue(field(layout, "payload").isReference)
        assertTrue(field(layout, "payload").children.isEmpty())
        assertEquals(8, field(layout, "tag").offset)
        assertEquals(16, layout.size)
        assertFalse(layout.isBlittable)
    }

    @Test
    fun anInterfaceFieldIsFourBytesOnA32BitRuntime() {
        val layout = layoutOf(
            """
            interface IPayload { }

            struct Holder
            {
                IPayload payload;
                byte tag;
            }
            """,
            "Holder",
            LayoutTarget.X86,
        )
        assertEquals(4, field(layout, "payload").size)
        assertEquals(4, field(layout, "tag").offset)
        assertEquals(8, layout.size)
    }

    @Test
    fun stringsArraysAndObjectsAreReferencesToo() {
        val layout = layoutOf(
            """
            struct Names
            {
                string first;
                int[] numbers;
                object tag;
            }
            """,
            "Names",
        )
        assertTrue(field(layout, "first").isReference)
        assertTrue(field(layout, "numbers").isReference)
        assertTrue(field(layout, "tag").isReference)
        assertEquals(24, layout.size)
        assertFalse(layout.isBlittable)
    }

    @Test
    fun aValueFieldIsNotAReference() {
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
        assertFalse(field(layout, "x").isReference)
    }
}
