package com.memorylayout.layout

/**
 * How a byte range sits against the cache lines of a machine.
 *
 * Pure arithmetic, and deliberately in `layout/` rather than next to the table: which fields a
 * cache line cuts in half is a fact about the type, not a decision about how to draw it. The
 * wording and the colours stay in the UI.
 *
 * The sizes that actually exist in the wild, which is what the presets offer:
 *
 * | machine | line |
 * |---|---|
 * | x86-64 desktop and console -- Intel, AMD, PS5, Xbox Series | 64 B |
 * | ARM64 -- Cortex-A, Snapdragon, most Android, Switch | 64 B |
 * | Apple silicon -- M-series and A-series, so every iPhone, iPad and Mac | 128 B |
 * | older 32-bit ARM -- Cortex-A7 and its relatives | 32 B |
 *
 * 64 is the default because it is what every desktop and console target uses. The reason to look
 * at 128 is Apple: a struct that fits one line on a PC straddles none on an M-series chip but two
 * arrays of it interleave differently, and false sharing between threads is decided by the *larger*
 * line of the platforms being shipped to.
 */
object CacheLineMath {

    const val DEFAULT_LINE_SIZE = 64

    const val MINIMUM_LINE_SIZE = 8

    const val MAXIMUM_LINE_SIZE = 4096

    /** The sizes offered as buttons, smallest first. Anything else is typed in. */
    val PRESET_LINE_SIZES = intArrayOf(32, 64, 128)

    /**
     * The value brought into range and onto a power of two.
     *
     * Every cache line on every architecture worth naming is a power of two, so a typed-in 100 is
     * a mistake rather than an exotic machine; rounding down keeps the picture pessimistic, which
     * is the safe direction when the question is "does this field get split".
     */
    fun normalize(lineSize: Int): Int {
        if (lineSize <= MINIMUM_LINE_SIZE) {
            return MINIMUM_LINE_SIZE
        }
        if (lineSize >= MAXIMUM_LINE_SIZE) {
            return MAXIMUM_LINE_SIZE
        }
        var value = MINIMUM_LINE_SIZE
        while (value * 2 <= lineSize) {
            value *= 2
        }
        return value
    }

    /** How many lines the range `[offset, offset + size)` touches. An empty range touches none. */
    fun linesTouched(offset: Int, size: Int, lineSize: Int): Int {
        if (size <= 0 || lineSize <= 0) {
            return 0
        }
        val firstLine = offset / lineSize
        val lastLine = (offset + size - 1) / lineSize
        return lastLine - firstLine + 1
    }

    /**
     * The field is cut by a line boundary: part of it is fetched with one line and part with
     * another. Only true for a field that could have fit in one line -- a `float4x4` is larger
     * than the line itself and is not a straddle, it is simply big.
     */
    fun straddlesBoundary(offset: Int, size: Int, lineSize: Int): Boolean {
        if (size > lineSize) {
            return false
        }
        return linesTouched(offset, size, lineSize) > 1
    }

    /** The line-relative offset a range starts at. */
    fun offsetWithinLine(offset: Int, lineSize: Int): Int {
        if (lineSize <= 0) {
            return offset
        }
        return offset % lineSize
    }

    /**
     * Where the line boundaries fall inside a type of this size, assuming the type itself starts
     * on a line. Empty when the type fits in one line.
     */
    fun boundariesWithin(size: Int, lineSize: Int): List<Int> {
        if (lineSize <= 0 || size <= lineSize) {
            return emptyList()
        }
        val boundaries = ArrayList<Int>()
        var boundary = lineSize
        while (boundary < size) {
            boundaries.add(boundary)
            boundary += lineSize
        }
        return boundaries
    }

    /** How much of the last line the type leaves unused, when it starts on a line. */
    fun wastedTailBytes(size: Int, lineSize: Int): Int {
        if (size <= 0 || lineSize <= 0) {
            return 0
        }
        val remainder = size % lineSize
        if (remainder == 0) {
            return 0
        }
        return lineSize - remainder
    }
}
