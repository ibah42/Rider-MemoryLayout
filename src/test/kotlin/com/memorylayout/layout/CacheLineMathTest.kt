package com.memorylayout.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheLineMathTest {

    @Test
    fun countsTheLinesARangeTouches() {
        assertEquals(1, CacheLineMath.linesTouched(0, 64, 64))
        assertEquals(2, CacheLineMath.linesTouched(1, 64, 64))
        assertEquals(1, CacheLineMath.linesTouched(60, 4, 64))
        assertEquals(2, CacheLineMath.linesTouched(60, 5, 64))
        assertEquals(0, CacheLineMath.linesTouched(0, 0, 64))
    }

    @Test
    fun straddlingNeedsTheFieldToHaveFitInTheFirstPlace() {
        // Four bytes ending one past the boundary: the classic split.
        assertTrue(CacheLineMath.straddlesBoundary(62, 4, 64))
        assertFalse(CacheLineMath.straddlesBoundary(60, 4, 64))
        // A float4x4 is larger than the line; it is big, not split.
        assertFalse(CacheLineMath.straddlesBoundary(0, 128, 64))
    }

    @Test
    fun theSameFieldSplitsOnOneMachineAndNotOnAnother() {
        // 64-byte line on x86-64, 128-byte line on Apple silicon.
        assertTrue(CacheLineMath.straddlesBoundary(62, 8, 64))
        assertFalse(CacheLineMath.straddlesBoundary(62, 8, 128))
    }

    @Test
    fun roundsATypedInValueOntoAPowerOfTwo() {
        assertEquals(64, CacheLineMath.normalize(64))
        assertEquals(64, CacheLineMath.normalize(100))
        assertEquals(128, CacheLineMath.normalize(128))
        assertEquals(CacheLineMath.MINIMUM_LINE_SIZE, CacheLineMath.normalize(0))
        assertEquals(CacheLineMath.MINIMUM_LINE_SIZE, CacheLineMath.normalize(-8))
        assertEquals(CacheLineMath.MAXIMUM_LINE_SIZE, CacheLineMath.normalize(99999))
    }

    @Test
    fun reportsWhereTheBoundariesFall() {
        assertEquals(emptyList<Int>(), CacheLineMath.boundariesWithin(40, 64))
        assertEquals(emptyList<Int>(), CacheLineMath.boundariesWithin(64, 64))
        assertEquals(listOf(64), CacheLineMath.boundariesWithin(65, 64))
        assertEquals(listOf(64, 128), CacheLineMath.boundariesWithin(130, 64))
    }

    @Test
    fun reportsTheUnusedTailOfTheLastLine() {
        assertEquals(0, CacheLineMath.wastedTailBytes(64, 64))
        assertEquals(36, CacheLineMath.wastedTailBytes(28, 64))
        assertEquals(60, CacheLineMath.wastedTailBytes(68, 64))
    }

    @Test
    fun offsetWithinLineIsWhereTheFieldStartsInIt() {
        assertEquals(0, CacheLineMath.offsetWithinLine(128, 64))
        assertEquals(4, CacheLineMath.offsetWithinLine(68, 64))
    }
}
