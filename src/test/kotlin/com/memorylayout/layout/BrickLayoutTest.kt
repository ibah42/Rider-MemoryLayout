package com.memorylayout.layout

import com.memorylayout.layout.LayoutTestSupport.field
import com.memorylayout.layout.LayoutTestSupport.layoutOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrickLayoutTest {

    private val chunk = """
        struct Float3
        {
            float x;
            float y;
            float z;
        }

        struct Chunk
        {
            Float3 center;
            Float3 extents;
            Float3 velocity;
            Float3 rotation;
            float mass;
            int id;
            byte lod;
            Float3 offset;
            double energy;
        }
        """

    private val nothingExpanded: (LayoutNode) -> Boolean = { false }

    @Test
    fun theTestStructIsTheShapeTheseTestsAssume() {
        val layout = layoutOf(chunk, "Chunk")
        assertEquals(80, layout.size)
        assertEquals(60, field(layout, "offset").offset)
        assertEquals(72, field(layout, "energy").offset)
    }

    @Test
    fun oneRowPerCacheLine() {
        val layout = layoutOf(chunk, "Chunk")
        val rows = BrickLayout.rowsOf(layout.nodes, nothingExpanded, 64, layout.size)
        assertEquals(2, rows.size)
        assertEquals(0, rows[0].baseOffset)
        assertEquals(64, rows[1].baseOffset)
    }

    @Test
    fun aFieldReachingPastTheLineIsCutInTwo() {
        val layout = layoutOf(chunk, "Chunk")
        val rows = BrickLayout.rowsOf(layout.nodes, nothingExpanded, 64, layout.size)
        val head = rows[0].pieces.last()
        val tail = rows[1].pieces.first()
        assertEquals("offset", head.node.fieldName)
        assertEquals("offset", tail.node.fieldName)
        assertEquals(60, head.offset)
        assertEquals(4, head.size)
        assertTrue(head.continuesAfter)
        assertFalse(head.continuesBefore)
        assertEquals(64, tail.offset)
        assertEquals(8, tail.size)
        assertTrue(tail.continuesBefore)
        assertFalse(tail.continuesAfter)
        // The two halves are the same field, which is what lets one click light up both.
        assertSame(head.node, tail.node)
    }

    @Test
    fun theSpareTailOfTheLastLineIsReported() {
        val layout = layoutOf(chunk, "Chunk")
        val rows = BrickLayout.rowsOf(layout.nodes, nothingExpanded, 64, layout.size)
        assertEquals(0, rows[0].spareBytes)
        assertEquals(48, rows[1].spareBytes)
    }

    @Test
    fun unfoldingAStructReplacesOneBrickWithItsMembers() {
        val layout = layoutOf(chunk, "Chunk")
        val center = field(layout, "center")
        val collapsed = BrickLayout.visibleLeaves(layout.nodes, nothingExpanded)
        val expanded = BrickLayout.visibleLeaves(layout.nodes) { node -> node === center }
        assertEquals(10, collapsed.size)
        assertEquals(12, expanded.size)
        assertEquals("x", expanded[0].fieldName)
        assertEquals("z", expanded[2].fieldName)
        assertEquals("extents", expanded[3].fieldName)
    }

    @Test
    fun onlyTheStructThatIsUnfoldedComesApart() {
        val layout = layoutOf(chunk, "Chunk")
        val extents = field(layout, "extents")
        val leaves = BrickLayout.visibleLeaves(layout.nodes) { node -> node === extents }
        assertEquals("center", leaves[0].fieldName)
        assertEquals("x", leaves[1].fieldName)
        assertEquals(12, leaves[1].offset)
    }

    @Test
    fun paddingGetsABrickOfItsOwn() {
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
        val rows = BrickLayout.rowsOf(layout.nodes, nothingExpanded, 64, layout.size)
        assertEquals(1, rows.size)
        assertEquals(3, rows[0].pieces.size)
        assertEquals(NodeKind.PADDING, rows[0].pieces[1].node.kind)
        assertEquals(1, rows[0].pieces[1].offset)
        assertEquals(3, rows[0].pieces[1].size)
        assertEquals(56, rows[0].spareBytes)
    }

    @Test
    fun anUnresolvedFieldOwnsNoBytesAndGetsNoBrick() {
        val layout = layoutOf(
            """
            struct Holder
            {
                int before;
                Missing gap;
                int after;
            }
            """,
            "Holder",
        )
        val leaves = BrickLayout.visibleLeaves(layout.nodes, nothingExpanded)
        assertEquals(2, leaves.size)
        assertEquals("before", leaves[0].fieldName)
        assertEquals("after", leaves[1].fieldName)
    }

    @Test
    fun aByteIsTracedBackToTheFieldThatOwnsIt() {
        val layout = layoutOf(chunk, "Chunk")
        val rows = BrickLayout.rowsOf(layout.nodes, nothingExpanded, 64, layout.size)
        assertEquals("center", BrickLayout.pieceAt(rows, 0)?.node?.fieldName)
        assertEquals("offset", BrickLayout.pieceAt(rows, 66)?.node?.fieldName)
        assertEquals("energy", BrickLayout.pieceAt(rows, 79)?.node?.fieldName)
        assertNull(BrickLayout.pieceAt(rows, 80))
    }

    @Test
    fun aTypeSmallerThanOneLineStillGetsOneRow() {
        val layout = layoutOf(
            """
            struct Tiny
            {
                int value;
            }
            """,
            "Tiny",
        )
        val rows = BrickLayout.rowsOf(layout.nodes, nothingExpanded, 64, layout.size)
        assertEquals(1, rows.size)
        assertEquals(60, rows[0].spareBytes)
    }

    @Test
    fun aClassIsDrawnFromItsHeaderBecauseThatIsWhereItStarts() {
        val layout = layoutOf(
            """
            class AudioService
            {
                ILogger _logger;
            }
            """,
            "AudioService",
        )
        val start = BrickLayout.lowestOffset(layout.nodes)
        assertEquals(-8, start)
        val rows = BrickLayout.rowsOf(layout.nodes, nothingExpanded, 32, layout.size, start)
        // The allocation is 24 bytes -- header, method table pointer, one reference -- and it
        // begins at the header. Cut from the header it is one line with room to spare; cut from
        // the reference, as it used to be, it looked like two lines with 24 bytes of nothing.
        assertEquals(1, rows.size)
        assertEquals(-8, rows[0].baseOffset)
        assertEquals(8, rows[0].spareBytes)
        assertEquals(3, rows[0].pieces.size)
        assertEquals("object header", rows[0].pieces[0].node.fieldName)
        assertEquals(-8, rows[0].pieces[0].offset)
        assertEquals("type handle", rows[0].pieces[1].node.fieldName)
        assertEquals(0, rows[0].pieces[1].offset)
        assertEquals("_logger", rows[0].pieces[2].node.fieldName)
    }

    @Test
    fun aBigClassCutsItsLinesFromTheHeaderAndNotFromZero() {
        val layout = layoutOf(
            """
            class Wide
            {
                double a;
                double b;
                double c;
                double d;
                double e;
                double f;
                double g;
                double h;
            }
            """,
            "Wide",
        )
        val start = BrickLayout.lowestOffset(layout.nodes)
        assertEquals(-8, start)
        // 8 bytes of header, 8 of method table pointer, 64 of fields: 80 bytes of allocation.
        assertEquals(72, layout.size)
        val rows = BrickLayout.rowsOf(layout.nodes, nothingExpanded, 64, layout.size, start)
        assertEquals(2, rows.size)
        assertEquals(-8, rows[0].baseOffset)
        assertEquals(56, rows[1].baseOffset)
        assertEquals(48, rows[1].spareBytes)
    }

    @Test
    fun aStructStartsAtZeroAndHasNothingBeforeIt() {
        val layout = layoutOf(chunk, "Chunk")
        assertEquals(0, BrickLayout.lowestOffset(layout.nodes))
        val rows = BrickLayout.rowsOf(layout.nodes, nothingExpanded, 64, layout.size, 0)
        assertEquals(2, rows.size)
        assertEquals(0, rows[0].baseOffset)
    }

    @Test
    fun aSmallerCacheLineMakesMoreRows() {
        val layout = layoutOf(chunk, "Chunk")
        val rows = BrickLayout.rowsOf(layout.nodes, nothingExpanded, 32, layout.size)
        assertEquals(3, rows.size)
        assertEquals(listOf(0, 32, 64), rows.map { row -> row.baseOffset })
        assertEquals(16, rows[2].spareBytes)
    }
}
