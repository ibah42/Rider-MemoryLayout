package com.memorylayout.ui

import com.memorylayout.layout.BrickLayout
import com.memorylayout.layout.CacheLineMath
import com.memorylayout.layout.LayoutNode
import com.memorylayout.layout.NodeKind
import com.memorylayout.settings.MemoryLayoutSettings
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.IdentityHashMap
import javax.swing.JComponent

/**
 * The type drawn as bricks: one row is one cache line, one rectangle is a field or the part of one
 * that fits on the row.
 *
 * Painted rather than built out of components. A 4 KB `FixedString4096Bytes` is four thousand
 * bytes and sixty-four rows; that is a picture, and a picture is drawn once per repaint, not
 * assembled out of four thousand panels that each want a layout pass.
 *
 * It owns no data of its own -- it is handed the rows [BrickLayout] cut, and hands back the node
 * under the mouse. Which bytes a rectangle covers was decided in `layout/`; everything here is how
 * it looks and where it was clicked.
 */
class BrickView : JComponent() {

    private var rows: List<BrickLayout.BrickRow> = emptyList()

    private var lineSize = CacheLineMath.DEFAULT_LINE_SIZE

    private var totalSize = 0

    private val colors = IdentityHashMap<LayoutNode, Color>()

    /** Font metrics are looked up per brick per repaint; measuring them each time is not free. */
    private val metricsBySize = HashMap<Int, java.awt.FontMetrics>()

    /**
     * Everything selected, and everything under it.
     *
     * Selecting a struct has to light up the bytes of every member it contains -- that is what
     * makes "where does `transform` live" answerable when `transform` is three fields rather than
     * one -- and the table selects rows the way a file list does, several at a time, so this is a
     * set and not a node. Identity again: a member of a nested struct is the same instance in
     * both views.
     */
    private val selection = IdentityHashMap<LayoutNode, Boolean>()

    /** Called when the reader clicks a brick. The table listens and selects the same field. */
    var onNodeSelected: ((LayoutNode) -> Unit)? = null

    init {
        isOpaque = true
        // Registers the component with the tooltip manager; the text itself comes from the event.
        toolTipText = ""
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                val piece = pieceAt(event.point) ?: return
                // Through select(), not by setting the field: select() is what builds the set of
                // highlighted nodes, and it returns early when the node has not changed. Setting
                // the field here first made it return early every time, so a click moved nothing.
                val node = identityOf(piece.node)
                select(node)
                onNodeSelected?.invoke(node)
            }
        })
    }

    fun show(rows: List<BrickLayout.BrickRow>, lineSize: Int, totalSize: Int) {
        this.rows = rows
        this.lineSize = lineSize
        this.totalSize = totalSize
        // A recompute hands over fresh nodes, so a selection held by identity means nothing now.
        selection.clear()
        assignColors()
        revalidate()
        repaint()
    }

    fun select(node: LayoutNode?) {
        if (node == null) {
            select(emptyList())
            return
        }
        select(listOf(node))
    }

    fun select(nodes: List<LayoutNode>) {
        val wanted = IdentityHashMap<LayoutNode, Boolean>()
        for (node in nodes) {
            markSelected(node, wanted)
        }
        if (sameAsCurrent(wanted)) {
            return
        }
        selection.clear()
        selection.putAll(wanted)
        scrollToSelection()
        repaint()
    }

    private fun sameAsCurrent(wanted: IdentityHashMap<LayoutNode, Boolean>): Boolean {
        if (wanted.size != selection.size) {
            return false
        }
        return wanted.keys.all { node ->
            selection.containsKey(node)
        }
    }

    private fun markSelected(node: LayoutNode, into: IdentityHashMap<LayoutNode, Boolean>) {
        into[node] = true
        for (child in node.children) {
            markSelected(child, into)
        }
    }

    /**
     * Nothing is lit until something is selected.
     *
     * A view that opens with every brick at full colour has already answered a question nobody
     * asked; the picture starts quiet and the reader's click is what turns a part of it on.
     */
    private fun isHighlighted(node: LayoutNode): Boolean {
        return selection.containsKey(identityOf(node))
    }

    /** The table's node a brick stands for: itself, or the row an element copy was made from. */
    private fun identityOf(node: LayoutNode): LayoutNode {
        return node.repeatSource ?: node
    }

    /** One colour per field, in the order the fields are laid out. */
    private fun assignColors() {
        colors.clear()
        var index = 0
        for (row in rows) {
            for (piece in row.pieces) {
                val node = identityOf(piece.node)
                if (colors.containsKey(node)) {
                    continue
                }
                colors[node] = MemoryLayoutStyle.brickColor(index)
                index++
            }
        }
    }

    fun byteWidth(): Int {
        val stored = MemoryLayoutViewState.byteWidth
        if (stored > 0) {
            return stored
        }
        return maxOf(
            MemoryLayoutSettings.MINIMUM_BYTE_WIDTH,
            getFontMetrics(labelFont(MemoryLayoutViewState.labelFontSize)).charWidth('0') *
                CHARACTERS_PER_BYTE,
        )
    }

    fun rowHeight(): Int {
        return JBUI.scale(BRICK_HEIGHT + TICK_HEIGHT + ROW_GAP)
    }

    /**
     * The margin the drawing keeps from the edge of the scrolling area: four characters of the
     * ruler's own font on every side.
     *
     * Four, not a couple of pixels, because the number that closes a cache line is drawn past the
     * last brick and has to fit there -- `128` was landing on top of `124` and then being cut off
     * by the edge. Measured in characters so it stays right when the font size changes.
     */
    fun edgeInset(): Int {
        return getFontMetrics(tickFont()).charWidth('0') * EDGE_CHARACTERS
    }

    fun rowCount(): Int {
        return rows.size
    }

    fun baseOffsetOf(rowIndex: Int): Int {
        return rows[rowIndex].baseOffset
    }

    /**
     * What the ruler under the bricks counts from: the start of the type, not the reference.
     *
     * A class begins at its object header, eight bytes before the reference the offsets are
     * measured from, so the picture would otherwise open with -8 and -4 -- negative numbers for
     * the first bytes of the thing being drawn, which is nobody's idea of a ruler. Shifting the
     * whole scale by that much puts byte zero where the object starts. The table keeps the real
     * offsets; it is the one answering "where is this field", and a debugger reports the same.
     */
    private fun displayShift(): Int {
        val first = rows.firstOrNull() ?: return 0
        return -minOf(0, first.baseOffset)
    }

    /** The offset the gutter prints for a row, on the ruler's own scale. */
    fun displayedOffsetOf(rowIndex: Int): Int {
        return rows[rowIndex].baseOffset + displayShift()
    }

    /** Which cache line of the object this row is, counted from where the object really starts. */
    fun rowLabelOf(rowIndex: Int): String {
        val first = rows.first().baseOffset
        val line = (rows[rowIndex].baseOffset - first) / lineSize
        return MemoryLayoutStyle.CACHE_LINE_ROW_PREFIX + line
    }

    override fun getPreferredSize(): Dimension {
        if (rows.isEmpty()) {
            return Dimension(0, 0)
        }
        val inset = edgeInset()
        // The right margin also has to hold the number that closes the line, and it is drawn a
        // few pixels past the last brick.
        return Dimension(
            inset * 2 + lineSize * byteWidth() + JBUI.scale(LINE_END_GAP),
            inset * 2 + rows.size * rowHeight(),
        )
    }

    private fun labelFont(size: Int): Font {
        return Font(Font.MONOSPACED, Font.BOLD, JBUI.scale(size))
    }

    private fun tickFont(): Font {
        return Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(MemoryLayoutViewState.tickFontSize))
    }

    private fun pieceAt(point: Point): BrickLayout.BrickPiece? {
        val offset = byteAt(point)
        if (offset < 0) {
            return null
        }
        return BrickLayout.pieceAt(rows, offset)
    }

    private fun byteAt(point: Point): Int {
        val inset = edgeInset()
        val x = point.x - inset
        val y = point.y - inset
        if (x < 0 || y < 0) {
            return -1
        }
        val rowIndex = y / rowHeight()
        if (rowIndex >= rows.size) {
            return -1
        }
        val byteInRow = x / byteWidth()
        if (byteInRow >= lineSize) {
            return -1
        }
        return rows[rowIndex].baseOffset + byteInRow
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val piece = pieceAt(event.point) ?: return null
        return MemoryLayoutStyle.brickTooltip(piece, lineSize)
    }

    /**
     * Brings the selected field into view, and only when it is not already there.
     *
     * The scroll pane animates, so scrolling to a row that is under the mouse already makes a
     * click feel like it took a moment to land -- the click was instant, the view just slid
     * somewhere for no reason.
     */
    private fun scrollToSelection() {
        if (selection.isEmpty()) {
            return
        }
        val height = rowHeight()
        for (index in rows.indices) {
            for (piece in rows[index].pieces) {
                if (!selection.containsKey(identityOf(piece.node))) {
                    continue
                }
                val target = Rectangle(0, edgeInset() + index * height, maxOf(width, 1), height)
                if (!visibleRect.contains(target)) {
                    scrollRectToVisible(target)
                }
                return
            }
        }
    }

    override fun paintComponent(graphics: Graphics) {
        val canvas = graphics.create() as Graphics2D
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.color = background
            canvas.fillRect(0, 0, width, height)
            canvas.translate(edgeInset(), edgeInset())
            val byteWidth = byteWidth()
            val rowHeightPixels = rowHeight()
            for (index in rows.indices) {
                paintRow(canvas, rows[index], index * rowHeightPixels, byteWidth)
            }
        } finally {
            canvas.dispose()
        }
    }

    private fun paintRow(canvas: Graphics2D, row: BrickLayout.BrickRow, top: Int, byteWidth: Int) {
        val brickHeight = JBUI.scale(BRICK_HEIGHT)
        if (row.baseOffset > 0) {
            canvas.color = MemoryLayoutStyle.cacheLineRule
            canvas.fillRect(0, top - JBUI.scale(RULE_OFFSET), lineSize * byteWidth, JBUI.scale(RULE_HEIGHT))
        }
        paintSpare(canvas, row, top, byteWidth, brickHeight)
        paintTicks(canvas, row, top + brickHeight, byteWidth)
        for (piece in row.pieces) {
            paintPiece(canvas, row, piece, top, byteWidth, brickHeight)
        }
    }

    private fun paintSpare(
        canvas: Graphics2D,
        row: BrickLayout.BrickRow,
        top: Int,
        byteWidth: Int,
        brickHeight: Int,
    ) {
        if (row.spareBytes <= 0) {
            return
        }
        val left = (lineSize - row.spareBytes) * byteWidth
        val width = row.spareBytes * byteWidth
        canvas.color = MemoryLayoutStyle.spareBackground
        canvas.fillRect(left, top, width, brickHeight)
        canvas.color = MemoryLayoutStyle.spareForeground
        canvas.font = tickFont()
        val text = row.spareBytes.toString() + " B " + MemoryLayoutStyle.SPARE_TEXT
        val textWidth = canvas.fontMetrics.stringWidth(text)
        if (textWidth + JBUI.scale(LABEL_MARGIN) > width) {
            return
        }
        canvas.drawString(text, left + (width - textWidth) / 2, top + brickHeight / 2 + canvas.fontMetrics.ascent / 2)
    }

    private fun paintTicks(canvas: Graphics2D, row: BrickLayout.BrickRow, top: Int, byteWidth: Int) {
        canvas.font = tickFont()
        canvas.color = MemoryLayoutStyle.decimalOffsetForeground
        var step = TICK_STEP
        while (step * byteWidth < JBUI.scale(MINIMUM_TICK_GAP) && step < lineSize) {
            step *= 2
        }
        val baseline = top + JBUI.scale(TICK_HEIGHT) - JBUI.scale(TICK_TEXT_BASELINE)
        val shift = displayShift()
        var byteIndex = 0
        while (byteIndex < lineSize) {
            val x = byteIndex * byteWidth
            canvas.drawLine(x, top, x, top + JBUI.scale(TICK_MARK_HEIGHT))
            canvas.drawString(
                (row.baseOffset + byteIndex + shift).toString(),
                x + JBUI.scale(TICK_TEXT_INSET),
                baseline,
            )
            byteIndex += step
        }
        paintLineEnd(canvas, row, top, byteWidth, baseline)
    }

    /**
     * The offset one past the end of the line, in the margin past the last brick.
     *
     * The ticks say where the line starts and step through it; without the number at the far end
     * the reader has to multiply to find out where it stops, which is the one number they were
     * looking at the picture to avoid working out. It goes outside the bricks rather than inside,
     * or it lands on the last tick's label.
     */
    private fun paintLineEnd(
        canvas: Graphics2D,
        row: BrickLayout.BrickRow,
        top: Int,
        byteWidth: Int,
        baseline: Int,
    ) {
        val end = row.baseOffset + lineSize + displayShift()
        val text = end.toString()
        val right = lineSize * byteWidth
        canvas.color = MemoryLayoutStyle.cacheLineRule
        canvas.drawLine(right - 1, top, right - 1, top + JBUI.scale(TICK_MARK_HEIGHT))
        canvas.drawString(text, right + JBUI.scale(LINE_END_GAP), baseline)
    }

    private fun paintPiece(
        canvas: Graphics2D,
        row: BrickLayout.BrickRow,
        piece: BrickLayout.BrickPiece,
        top: Int,
        byteWidth: Int,
        brickHeight: Int,
    ) {
        val left = (piece.offset - row.baseOffset) * byteWidth
        val width = piece.size * byteWidth
        val arc = JBUI.scale(BRICK_ARC)
        val isPadding = piece.node.kind == NodeKind.PADDING
        val fill = fillOf(piece, isPadding)
        canvas.color = fill
        canvas.fillRoundRect(left, top, width, brickHeight, arc, arc)
        // A cut field keeps a square edge where it was cut, so the join reads as a join.
        if (piece.continuesBefore) {
            canvas.fillRect(left, top, arc, brickHeight)
        }
        if (piece.continuesAfter) {
            canvas.fillRect(left + width - arc, top, arc, brickHeight)
        }
        if (isPadding) {
            paintHatching(canvas, piece, left, top, width, brickHeight)
        }
        paintBorder(canvas, piece, fill, left, top, width, brickHeight, arc)
        paintLabel(canvas, piece, fill, left, top, width, brickHeight, isPadding)
    }

    private fun fillOf(piece: BrickLayout.BrickPiece, isPadding: Boolean): Color {
        var contrast = MemoryLayoutStyle.contrastFor(isHighlighted(piece.node), isPadding)
        if (piece.node.repeatCopyIndex > 0) {
            // Element [1] onwards are the same bytes again: drawn fainter, so [0] reads as the
            // element and the rest as its repetition.
            contrast = contrast * REPEAT_COPY_CONTRAST / FULL_PERCENT
        }
        return MemoryLayoutStyle.towardGrey(baseColorOf(piece, isPadding), contrast, background)
    }

    private fun baseColorOf(piece: BrickLayout.BrickPiece, isPadding: Boolean): Color {
        if (isPadding) {
            return MemoryLayoutStyle.paddingBackground
        }
        if (piece.node.kind == NodeKind.UNRESOLVED) {
            return MemoryLayoutStyle.unresolvedForeground
        }
        if (piece.node.kind == NodeKind.RUNTIME) {
            return MemoryLayoutStyle.runtimeForeground
        }
        return colors[identityOf(piece.node)] ?: MemoryLayoutStyle.brickColor(0)
    }

    private fun paintHatching(
        canvas: Graphics2D,
        piece: BrickLayout.BrickPiece,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ) {
        val previousClip = canvas.clip
        canvas.clipRect(left, top, width, height)
        canvas.color = MemoryLayoutStyle.towardGrey(
            MemoryLayoutStyle.paddingForeground,
            MemoryLayoutStyle.contrastFor(isHighlighted(piece.node), true),
            background,
        )
        val step = JBUI.scale(HATCH_STEP)
        var x = left - height
        while (x < left + width) {
            canvas.drawLine(x, top + height, x + height, top)
            x += step
        }
        canvas.clip = previousClip
    }

    private fun paintBorder(
        canvas: Graphics2D,
        piece: BrickLayout.BrickPiece,
        fill: Color,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        arc: Int,
    ) {
        val selected = selection.containsKey(identityOf(piece.node))
        val split = CacheLineMath.straddlesBoundary(piece.node.offset, piece.node.size, lineSize)
        if (selected) {
            canvas.color = MemoryLayoutStyle.selectionOutline
            canvas.drawRoundRect(left, top, width - 1, height - 1, arc, arc)
            canvas.drawRoundRect(left + 1, top + 1, width - 3, height - 3, arc, arc)
            return
        }
        if (split) {
            canvas.color = MemoryLayoutStyle.splitForeground
            canvas.drawRoundRect(left, top, width - 1, height - 1, arc, arc)
            return
        }
        canvas.color = MemoryLayoutStyle.borderOf(fill)
        canvas.drawRoundRect(left, top, width - 1, height - 1, arc, arc)
    }

    private fun paintLabel(
        canvas: Graphics2D,
        piece: BrickLayout.BrickPiece,
        fill: Color,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        isPadding: Boolean,
    ) {
        canvas.color = MemoryLayoutStyle.labelOn(fill)
        val text: String
        if (isPadding) {
            text = MemoryLayoutStyle.PADDING_ROW_NAME
        } else {
            text = piece.node.fieldName
        }
        drawName(canvas, text, left, top, width, height)
        if (piece.continuesAfter) {
            canvas.font = labelFont(MemoryLayoutViewState.labelMinimumFontSize)
            canvas.drawString(
                CONTINUES_MARK,
                left + width - JBUI.scale(MARK_INSET),
                top + height - JBUI.scale(MARK_BASELINE),
            )
        }
    }

    /**
     * Puts a name on a brick: full size if it fits, then a size or two smaller, then wrapped onto
     * a second and a third line, and only when none of that works, cut from the end.
     *
     * Bigger beats fewer lines: every size is tried on one line before two lines are considered,
     * so a name only wraps once shrinking has stopped helping. How far it may shrink and how many
     * lines are allowed are settings -- the right answer depends on the byte width, and that is a
     * slider.
     */
    private fun drawName(canvas: Graphics2D, text: String, left: Int, top: Int, width: Int, height: Int) {
        val available = width - JBUI.scale(LABEL_MARGIN)
        if (available <= 0 || text.isEmpty()) {
            return
        }
        val base = MemoryLayoutViewState.labelFontSize
        val minimum = minOf(MemoryLayoutViewState.labelMinimumFontSize, base)
        val maximumLines = MemoryLayoutViewState.labelMaximumLines
            .coerceIn(1, MemoryLayoutSettings.MAXIMUM_LABEL_LINES)
        var size = base
        while (size >= minimum) {
            val metrics = metricsFor(size)
            for (lineCount in 1..maximumLines) {
                val parts = splitInto(text, lineCount)
                if (parts.size != lineCount) {
                    continue
                }
                if (!fits(metrics, parts, available, height)) {
                    continue
                }
                drawLines(canvas, metrics, size, parts, left, top, width, height)
                return
            }
            size--
        }
        val metrics = metricsFor(minimum)
        val shown = fitted(metrics, text, available)
        if (shown.isEmpty()) {
            return
        }
        drawLines(canvas, metrics, minimum, listOf(shown), left, top, width, height)
    }

    private fun fits(
        metrics: java.awt.FontMetrics,
        parts: List<String>,
        available: Int,
        height: Int,
    ): Boolean {
        if (parts.size * metrics.height > height - JBUI.scale(LINE_MARGIN)) {
            return false
        }
        return parts.all { part ->
            metrics.stringWidth(part) <= available
        }
    }

    private fun drawLines(
        canvas: Graphics2D,
        metrics: java.awt.FontMetrics,
        size: Int,
        parts: List<String>,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ) {
        canvas.font = labelFont(size)
        val block = parts.size * metrics.height
        var baseline = top + (height - block) / 2 + metrics.ascent
        for (part in parts) {
            canvas.drawString(part, left + (width - metrics.stringWidth(part)) / 2, baseline)
            baseline += metrics.height
        }
    }

    /**
     * The name in [parts] pieces, cut at word boundaries where there is one near the right place.
     *
     * `worldPosition` in two becomes `world` and `Position` rather than `worldPo` and `sition`;
     * a name with no boundaries is cut evenly. Empty when the name is too short to divide, which
     * is the caller's signal to try a smaller font instead.
     */
    private fun splitInto(text: String, parts: Int): List<String> {
        if (parts <= 1) {
            return listOf(text)
        }
        if (text.length < parts * MINIMUM_PART_LENGTH) {
            return emptyList()
        }
        val cuts = cutsFor(text, parts)
        val pieces = ArrayList<String>()
        var start = 0
        for (cut in cuts) {
            pieces.add(text.substring(start, cut))
            start = cut
        }
        pieces.add(text.substring(start))
        return pieces
    }

    private fun cutsFor(text: String, parts: Int): List<Int> {
        val boundaries = boundariesOf(text)
        val tolerance = maxOf(1, text.length / parts / 2)
        val cuts = ArrayList<Int>()
        for (index in 1 until parts) {
            val target = text.length * index / parts
            var cut = target
            for (boundary in boundaries) {
                if (Math.abs(boundary - target) < Math.abs(cut - target)) {
                    cut = boundary
                }
            }
            if (Math.abs(cut - target) > tolerance) {
                cut = target
            }
            val previous = cuts.lastOrNull() ?: 0
            if (cut <= previous || cut >= text.length) {
                return evenCuts(text, parts)
            }
            cuts.add(cut)
        }
        return cuts
    }

    private fun evenCuts(text: String, parts: Int): List<Int> {
        val cuts = ArrayList<Int>()
        for (index in 1 until parts) {
            cuts.add(text.length * index / parts)
        }
        return cuts
    }

    /** Where a new word starts: a capital after a lower-case letter, or anything after a `_`. */
    private fun boundariesOf(text: String): List<Int> {
        val boundaries = ArrayList<Int>()
        for (index in 1 until text.length) {
            val previous = text[index - 1]
            val current = text[index]
            if (current.isUpperCase() && previous.isLowerCase()) {
                boundaries.add(index)
                continue
            }
            if (previous == '_' && current != '_') {
                boundaries.add(index)
            }
        }
        return boundaries
    }

    private fun metricsFor(size: Int): java.awt.FontMetrics {
        val cached = metricsBySize[size]
        if (cached != null) {
            return cached
        }
        val metrics = getFontMetrics(labelFont(size))
        metricsBySize[size] = metrics
        return metrics
    }

    /**
     * As much of the name as fits, cut from the end.
     *
     * `velocity` in four bytes of room becomes `velo`, which still tells the reader which field
     * they are looking at; an empty brick tells them nothing and they have to hover to find out.
     */
    private fun fitted(metrics: java.awt.FontMetrics, text: String, available: Int): String {
        if (available <= 0) {
            return ""
        }
        if (metrics.stringWidth(text) <= available) {
            return text
        }
        var length = text.length
        while (length > 0 && metrics.stringWidth(text.substring(0, length)) > available) {
            length--
        }
        return text.substring(0, length)
    }

    private companion object {
        /** How much of its contrast an element copy after [0] keeps, as a percentage. */
        const val REPEAT_COPY_CONTRAST = 45

        const val FULL_PERCENT = 100

        const val BRICK_HEIGHT = 30

        const val TICK_HEIGHT = 14

        const val ROW_GAP = 14

        const val BRICK_ARC = 6

        const val LABEL_MARGIN = 8

        const val HATCH_STEP = 7

        const val TICK_STEP = 4

        const val MINIMUM_TICK_GAP = 34

        const val TICK_MARK_HEIGHT = 3

        const val TICK_TEXT_INSET = 2

        const val TICK_TEXT_BASELINE = 2

        const val RULE_HEIGHT = 2

        const val RULE_OFFSET = 8

        const val MARK_INSET = 7

        const val MARK_BASELINE = 3

        const val CHARACTERS_PER_BYTE = 4

        const val EDGE_CHARACTERS = 4

        const val LINE_END_GAP = 3

        const val CONTINUES_MARK = "▸"

        const val LINE_MARGIN = 4

        const val MINIMUM_PART_LENGTH = 2
    }
}

/**
 * The offsets down the left of the brick view, kept out of the scrolling area so they stay put
 * when a 64-byte line is wider than the panel.
 */
class BrickGutter(private val view: BrickView) : JComponent() {

    init {
        isOpaque = true
    }

    override fun getPreferredSize(): Dimension {
        return Dimension(JBUI.scale(GUTTER_WIDTH), view.preferredSize.height)
    }

    override fun paintComponent(graphics: Graphics) {
        val canvas = graphics.create() as Graphics2D
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.color = background
            canvas.fillRect(0, 0, width, height)
            canvas.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(MemoryLayoutViewState.tickFontSize))
            val rowHeight = view.rowHeight()
            for (index in 0 until view.rowCount()) {
                val top = view.edgeInset() + index * rowHeight
                canvas.color = MemoryLayoutStyle.hexOffsetForeground
                canvas.drawString(
                    MemoryLayoutStyle.hexOffset(view.displayedOffsetOf(index)),
                    JBUI.scale(TEXT_INSET),
                    top + JBUI.scale(FIRST_BASELINE),
                )
                canvas.color = MemoryLayoutStyle.cacheLineRule
                canvas.drawString(
                    view.rowLabelOf(index),
                    JBUI.scale(TEXT_INSET),
                    top + JBUI.scale(SECOND_BASELINE),
                )
            }
        } finally {
            canvas.dispose()
        }
    }

    private companion object {
        const val GUTTER_WIDTH = 62

        const val TEXT_INSET = 6

        const val FIRST_BASELINE = 14

        const val SECOND_BASELINE = 27
    }
}
