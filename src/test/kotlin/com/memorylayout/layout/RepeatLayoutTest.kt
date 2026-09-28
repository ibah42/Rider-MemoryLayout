package com.memorylayout.layout

import com.memorylayout.layout.LayoutTestSupport.TEST_FILE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The objects whose end repeats: an array's elements and a string's characters. One allocation
 * holds the header and every element, so the layout is the fixed part plus a stride times n.
 */
class RepeatLayoutTest {

    private val source = """
        struct Point
        {
            int x;
            byte flag;
        }

        class Enemy
        {
            int health;
        }
        """

    private fun arrayOf(
        elementTypeName: String,
        elementCount: Int = RepeatTail.UNKNOWN_COUNT,
        target: LayoutTarget = LayoutTarget.X64,
    ): TypeLayout {
        val lookup = SourceTypeLookup(CodeMask.of(source), TEST_FILE_ID)
        return LayoutEngine(target, lookup).layoutOfArray(elementTypeName, LookupContext.EMPTY, elementCount)
    }

    private fun repeatOf(layout: TypeLayout): LayoutNode {
        return layout.nodes.first { node -> node.kind == NodeKind.REPEAT }
    }

    @Test
    fun anArrayIsItsHeaderThenItsElementsFrom32() {
        val layout = arrayOf("int")
        assertEquals(listOf(0, 8, 16, 24), layout.nodes.take(4).map { node -> node.offset })
        assertEquals("max_length", layout.nodes[3].fieldName)
        val repeat = repeatOf(layout)
        assertEquals(32, repeat.offset)
        assertEquals(4, repeat.repeatStride)
        assertEquals("n", repeat.repeatCountText)
        // Nothing counted yet: the size is the empty array, and the elements are a formula.
        assertEquals(32, layout.size)
        assertEquals(4, layout.repeat?.stride)
        assertFalse(layout.repeat?.isCountKnown ?: true)
    }

    @Test
    fun aThirtyTwoBitArrayStartsItsElementsAt16() {
        val layout = arrayOf("int", target = LayoutTarget.X86)
        assertEquals(16, repeatOf(layout).offset)
        assertEquals(16, layout.size)
    }

    @Test
    fun aCountedArrayIsRoundedUpToEightBytes() {
        val layout = arrayOf("int", elementCount = 3)
        assertEquals(12, repeatOf(layout).size)
        assertEquals("3", repeatOf(layout).repeatCountText)
        // 32 + 3 * 4 = 44, and the collector hands out multiples of 8.
        assertEquals(48, layout.size)
        assertEquals(4, layout.paddingBytes)
    }

    @Test
    fun aStructElementUnfoldsIntoElementZero() {
        val layout = arrayOf("Point")
        val repeat = repeatOf(layout)
        assertEquals(8, repeat.repeatStride)
        assertEquals(listOf("x", "flag"), repeat.children.filter { child -> child.kind == NodeKind.FIELD }.map { child -> child.fieldName })
        assertEquals(32, repeat.children[0].offset)
        assertEquals(36, repeat.children[1].offset)
    }

    @Test
    fun aClassElementIsAReference() {
        val layout = arrayOf("Enemy")
        val repeat = repeatOf(layout)
        assertTrue(repeat.isReference)
        assertEquals(8, repeat.repeatStride)
        assertTrue(repeat.children.isEmpty())
        assertTrue(layout.notes.any { note -> note.contains("separate allocations") })
    }

    @Test
    fun anUncountedArrayIsDrawnAsThreeElementsAndAnEllipsis() {
        val layout = arrayOf("int")
        val rows = BrickLayout.rowsOf(layout.nodes, { false }, 64, layout.size, 0)
        assertEquals(1, rows.size)
        val pieces = rows[0].pieces
        assertEquals(listOf("[0]", "[1]", "[2]", "… × n"), pieces.drop(4).map { piece -> piece.node.fieldName })
        assertEquals(listOf(32, 36, 40, 44), pieces.drop(4).map { piece -> piece.offset })
        val repeat = repeatOf(layout)
        for (piece in pieces.drop(4)) {
            assertSame(repeat, piece.node.repeatSource)
        }
        assertEquals(0, pieces[4].node.repeatCopyIndex)
        assertTrue(pieces[7].node.isRepeatEllipsis)
        assertEquals(16, rows[0].spareBytes)
    }

    @Test
    fun aLargeCountIsCutShortInsteadOfDrawingEveryElement() {
        val layout = arrayOf("int", elementCount = 1000)
        assertEquals(4032, layout.size)
        val rows = BrickLayout.rowsOf(layout.nodes, { false }, 64, layout.size, 0)
        // 256 elements from 32 end at 1056, then one "… × 744" brick: 17 lines, not 63.
        assertEquals(17, rows.size)
        val last = rows.last().pieces.last()
        assertEquals("… × 744", last.node.fieldName)
        assertEquals(1056, last.offset)
    }

    @Test
    fun anUnfoldedElementIsDrawnAsItsMembersInEveryCopy() {
        val layout = arrayOf("Point")
        val repeat = repeatOf(layout)
        val leaves = BrickLayout.visibleLeaves(layout.nodes) { node -> node === repeat }
        val xs = leaves.filter { leaf -> leaf.fieldName == "x" }
        assertEquals(listOf(32, 40, 48), xs.map { leaf -> leaf.offset })
        // Element [0] is the table's own node; the others are copies pointing back at it.
        assertNull(xs[0].repeatSource)
        assertSame(xs[0], xs[1].repeatSource)
    }
}
