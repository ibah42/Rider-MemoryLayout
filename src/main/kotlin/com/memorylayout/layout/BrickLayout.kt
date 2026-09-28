package com.memorylayout.layout

/**
 * Cuts a layout into the rectangles the brick view draws.
 *
 * One row is one cache line, and a field that reaches past the end of a line is cut there: the
 * halves are two pieces of the same field, drawn on two rows with the join marked. That is the
 * whole reason this is not a list of fields -- a field is one thing, but it can be two or three
 * rectangles on screen.
 *
 * Geometry, not decoration: which bytes a rectangle covers is a fact about the type and is tested
 * as one. What colour it is painted and what is written on it stays in the UI.
 *
 * A piece carries the [LayoutNode] itself, and the views compare nodes by identity. Nothing else
 * would do: an offset is not unique under `LayoutKind.Explicit`, a name is not unique across
 * nested structs, and an index path breaks the moment the table stops showing padding rows. The
 * table and the bricks are drawn from the same [TypeLayout], so the same instances reach both.
 */
object BrickLayout {

    /** Copies drawn after element `[0]` while the count is unknown, before the `… × n`. */
    private const val UNCOUNTED_COPIES = 2

    /** A known count is drawn element by element up to this many; the rest is one `… × rest`. */
    private const val MAX_DRAWN_ELEMENTS = 256

    /**
     * One rectangle: a field, or the part of a field that fits on one row.
     *
     * @param continuesBefore this piece starts mid-field, because the previous row ended inside it
     * @param continuesAfter the field goes on past the end of this row
     */
    data class BrickPiece(
        val node: LayoutNode,
        val offset: Int,
        val size: Int,
        val continuesBefore: Boolean,
        val continuesAfter: Boolean,
    ) {
        val endOffset: Int
            get() = offset + size
    }

    /**
     * One cache line's worth of the type. [spareBytes] is the tail of the last line it does not
     * use.
     */
    data class BrickRow(
        val baseOffset: Int,
        val pieces: List<BrickPiece>,
        val spareBytes: Int,
    )

    /**
     * The nodes the bricks are drawn from: the leaves of the tree as it is currently unfolded.
     *
     * A collapsed struct is one brick; unfold it in the table and it becomes its members, which is
     * what makes the two views read as one picture rather than two pictures of the same bytes.
     */
    fun visibleLeaves(
        nodes: List<LayoutNode>,
        isExpanded: (LayoutNode) -> Boolean,
    ): List<LayoutNode> {
        val leaves = ArrayList<LayoutNode>()
        collectLeaves(nodes, isExpanded, leaves)
        return leaves
    }

    private fun collectLeaves(
        nodes: List<LayoutNode>,
        isExpanded: (LayoutNode) -> Boolean,
        output: MutableList<LayoutNode>,
    ) {
        for (node in nodes) {
            if (node.kind == NodeKind.REPEAT && node.repeatStride > 0) {
                val cutShort = addElementCopies(node, isExpanded, output)
                if (cutShort) {
                    // Only the tail padding can follow, thousands of bytes past the `… × rest`.
                    return
                }
                continue
            }
            if (node.children.isNotEmpty() && isExpanded(node)) {
                collectLeaves(node.children, isExpanded, output)
                continue
            }
            if (node.size <= 0) {
                // An unresolved field owns no bytes; there is no rectangle to draw for it.
                continue
            }
            output.add(node)
        }
    }

    /**
     * The elements of a [NodeKind.REPEAT], one brick each: `[0]`, then faded `[1]`, `[2]` and a
     * `… × n` while the count is unknown, or the counted elements up to [MAX_DRAWN_ELEMENTS] and a
     * `… × rest` for the others. An unfolded element is drawn as its members, in every copy.
     *
     * Every brick is a copy pointing back at the table's node through [LayoutNode.repeatSource],
     * so a click on `[7]` selects the row, and the row lights up all of its copies.
     *
     * @return true when the elements were cut short by a `… × rest`
     */
    private fun addElementCopies(
        node: LayoutNode,
        isExpanded: (LayoutNode) -> Boolean,
        output: MutableList<LayoutNode>,
    ): Boolean {
        val stride = node.repeatStride
        val knownCount = node.repeatCountText.toIntOrNull()
        val drawn: Int
        val ellipsisText: String?
        if (knownCount == null) {
            drawn = 1 + UNCOUNTED_COPIES
            ellipsisText = "… × " + node.repeatCountText
        } else {
            drawn = minOf(knownCount, MAX_DRAWN_ELEMENTS)
            if (knownCount > drawn) {
                ellipsisText = "… × " + (knownCount - drawn)
            } else {
                ellipsisText = null
            }
        }
        val unfolded = node.children.isNotEmpty() && isExpanded(node)
        val members: List<LayoutNode>
        if (unfolded) {
            members = visibleLeaves(node.children, isExpanded)
        } else {
            members = emptyList()
        }
        for (index in 0 until drawn) {
            val shift = index * stride
            if (unfolded) {
                for (member in members) {
                    if (index == 0) {
                        // Element [0] is what the table shows unfolded: its own nodes, not copies.
                        output.add(member)
                        continue
                    }
                    output.add(
                        member.copy(
                            offset = member.offset + shift,
                            children = emptyList(),
                            repeatSource = member.repeatSource ?: member,
                            repeatCopyIndex = index,
                        )
                    )
                }
                continue
            }
            output.add(
                node.copy(
                    offset = node.offset + shift,
                    size = stride,
                    fieldName = "[$index]",
                    children = emptyList(),
                    repeatSource = node,
                    repeatCopyIndex = index,
                )
            )
        }
        if (ellipsisText == null) {
            return false
        }
        output.add(
            node.copy(
                offset = node.offset + drawn * stride,
                size = stride,
                fieldName = ellipsisText,
                children = emptyList(),
                repeatSource = node,
                repeatCopyIndex = drawn,
                isRepeatEllipsis = true,
            )
        )
        return true
    }

    /**
     * How far the bricks go. The type's size, except where elements repeat: then as far as the
     * copies drawn for them, which is past the size while the count is unknown and short of it
     * when a large count is cut to `… × rest`.
     */
    private fun drawnSizeOf(leaves: List<LayoutNode>, totalSize: Int): Int {
        if (leaves.none { leaf -> leaf.repeatSource != null }) {
            return totalSize
        }
        return leaves.maxOf { leaf -> leaf.endOffset }
    }

    /**
     * The lowest offset anything in this layout occupies, which is 0 for a struct and -8 for a
     * class on x64: the object header lives before the reference the offsets are measured from.
     */
    fun lowestOffset(nodes: List<LayoutNode>): Int {
        var lowest = 0
        for (node in nodes) {
            if (node.offset < lowest) {
                lowest = node.offset
            }
            val inChildren = lowestOffset(node.children)
            if (inChildren < lowest) {
                lowest = inChildren
            }
        }
        return lowest
    }

    /**
     * @param startOffset where the type really begins, normally [lowestOffset]. The lines are cut
     *   from there, not from zero.
     *
     * That distinction is the whole of it for a class. Zero is where the *reference* points, which
     * is eight bytes into the allocation -- the object header is what the allocator put down
     * first, and what the allocation is aligned on. Cutting the lines from zero drew a grid that
     * does not exist in memory and made a 48-byte object look like it straddles two lines. Cutting
     * them from the header is not exact either, because an allocation is aligned to eight bytes
     * and not to a cache line, so an object may begin anywhere within one; but it is the picture
     * that does not lie about the object's own footprint, and "does this fit in a line" is
     * answerable from it.
     *
     * The offsets stay as the rest of the plugin reports them: measured from the reference, so
     * `health` at 12 sits under the tick that reads 12, and the first row simply starts at -8.
     */
    fun rowsOf(
        nodes: List<LayoutNode>,
        isExpanded: (LayoutNode) -> Boolean,
        lineSize: Int,
        totalSize: Int,
        startOffset: Int = 0,
    ): List<BrickRow> {
        if (lineSize <= 0 || totalSize <= 0) {
            return emptyList()
        }
        val leaves = visibleLeaves(nodes, isExpanded)
        val drawnSize = drawnSizeOf(leaves, totalSize)
        val rows = ArrayList<BrickRow>()
        var base = minOf(startOffset, 0)
        while (base < drawnSize) {
            val rowEnd = base + lineSize
            val pieces = ArrayList<BrickPiece>()
            for (leaf in leaves) {
                val start = maxOf(leaf.offset, base)
                val end = minOf(leaf.endOffset, rowEnd)
                if (end <= start) {
                    continue
                }
                pieces.add(
                    BrickPiece(
                        node = leaf,
                        offset = start,
                        size = end - start,
                        continuesBefore = start > leaf.offset,
                        continuesAfter = end < leaf.endOffset,
                    )
                )
            }
            rows.add(
                BrickRow(
                    baseOffset = base,
                    pieces = pieces,
                    spareBytes = maxOf(0, rowEnd - maxOf(drawnSize, base)),
                )
            )
            base = rowEnd
        }
        return rows
    }

    /** The piece covering this byte, or null when the byte belongs to nobody. */
    fun pieceAt(rows: List<BrickRow>, offset: Int): BrickPiece? {
        for (row in rows) {
            for (piece in row.pieces) {
                if (offset >= piece.offset && offset < piece.endOffset) {
                    return piece
                }
            }
        }
        return null
    }
}
