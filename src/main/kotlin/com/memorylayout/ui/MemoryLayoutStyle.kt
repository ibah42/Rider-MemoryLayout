package com.memorylayout.ui

import com.memorylayout.layout.BrickLayout
import com.memorylayout.layout.CacheLineMath
import com.memorylayout.layout.LayoutConfidence
import com.memorylayout.layout.LayoutNode
import com.memorylayout.layout.NodeKind
import com.memorylayout.layout.RepeatTail
import com.memorylayout.layout.TypeLayout
import com.memorylayout.settings.MemoryLayoutSettings
import com.memorylayout.settings.WindowBackground
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import java.awt.Color
import kotlin.math.roundToInt

/**
 * Every colour, every word and every default width the window uses.
 *
 * One file on purpose: the look of this thing is going to be argued with for a while, and moving a
 * colour should not mean reading the table code. Nothing here decides *what* is shown -- that is
 * the layout engine's business -- only how it reads.
 *
 * Colour carries meaning by column, not by decoration: a number is blue because it is an offset,
 * green because it is a size, teal because it is an alignment. Nothing is grey. Grey is what the
 * eye skips, and there is nothing in this table worth skipping -- the padding rows least of all,
 * since finding them is the reason the window exists.
 */
object MemoryLayoutStyle {

    // Colours. The pair is (light theme, dark theme).

    /** The offset a field starts at, in hex. */
    val hexOffsetForeground = JBColor(Color(0x18, 0x6F, 0xE0), Color(0x63, 0xA8, 0xFF))

    /** The same offset in decimal, one step quieter so the two columns do not fight. */
    val decimalOffsetForeground = JBColor(Color(0x2E, 0x86, 0xF0), Color(0x8F, 0xC4, 0xFF))

    val sizeForeground = JBColor(Color(0x0E, 0x8A, 0x3E), Color(0x5F, 0xD0, 0x7A))

    val alignmentForeground = JBColor(Color(0x0E, 0x9A, 0x8D), Color(0x3F, 0xD4, 0xC2))

    val typeForeground = JBColor(Color(0x7B, 0x3F, 0xE4), Color(0xB5, 0x8C, 0xFF))

    /** A field that holds a pointer: an interface, a class, an array, a string, a delegate. */
    val referenceForeground = JBColor(Color(0x00, 0xA0, 0xC6), Color(0x4F, 0xD8, 0xFF))

    /** Bytes the runtime owns: the vtable and monitor pointers that start every object. */
    val runtimeForeground = JBColor(Color(0x00, 0x86, 0xA8), Color(0x6F, 0xE3, 0xFF))

    val paddingForeground = JBColor(Color(0xE0, 0x7A, 0x00), Color(0xFF, 0xA8, 0x3D))

    val paddingBackground = JBColor(Color(0xFF, 0xF0, 0xD6), Color(0x3B, 0x2F, 0x1C))

    val unresolvedForeground = JBColor(Color(0xD3, 0x1C, 0x2B), Color(0xFF, 0x6B, 0x68))

    /** A field a cache line cuts in half. */
    val splitForeground = JBColor(Color(0xC2, 0x18, 0x5B), Color(0xFF, 0x5C, 0x8A))

    val autoPropertyForeground = JBColor(Color(0xD3, 0x2F, 0x6B), Color(0xFF, 0x6E, 0x9C))

    val overlapForeground = JBColor(Color(0xB0, 0x00, 0xB5), Color(0xFF, 0x6F, 0xFF))

    val blittableForeground = JBColor(Color(0x0E, 0x8A, 0x3E), Color(0x5F, 0xD0, 0x7A))

    val warningForeground = JBColor(Color(0xE0, 0x7A, 0x00), Color(0xFF, 0xA8, 0x3D))

    /**
     * The bar drawn at the left of a row, one colour per nesting level.
     *
     * The indent alone is easy to lose once a struct is three deep and the column is narrow; the
     * bar says which level a row belongs to without counting pixels.
     */
    private val LEVEL_COLORS = arrayOf(
        JBColor(Color(0x18, 0x6F, 0xE0), Color(0x63, 0xA8, 0xFF)),
        JBColor(Color(0x0E, 0x9A, 0x8D), Color(0x3F, 0xD4, 0xC2)),
        JBColor(Color(0x7B, 0x3F, 0xE4), Color(0xB5, 0x8C, 0xFF)),
        JBColor(Color(0xE0, 0x7A, 0x00), Color(0xFF, 0xA8, 0x3D)),
        JBColor(Color(0xD3, 0x2F, 0x6B), Color(0xFF, 0x6E, 0x9C)),
    )

    fun levelColor(level: Int): Color {
        if (level <= 0) {
            return LEVEL_COLORS[0]
        }
        return LEVEL_COLORS[level % LEVEL_COLORS.size]
    }

    /**
     * The bricks. One colour per field, taken in order, so the same field keeps its colour while
     * the reader unfolds and refolds the tree around it.
     */
    private val BRICK_COLORS = arrayOf(
        JBColor(Color(0x2E, 0x86, 0xF0), Color(0x63, 0xA8, 0xFF)),
        JBColor(Color(0x0E, 0x9A, 0x8D), Color(0x3F, 0xD4, 0xC2)),
        JBColor(Color(0x7B, 0x3F, 0xE4), Color(0xB5, 0x8C, 0xFF)),
        JBColor(Color(0x0E, 0x8A, 0x3E), Color(0x5F, 0xD0, 0x7A)),
        JBColor(Color(0xD3, 0x2F, 0x6B), Color(0xFF, 0x6E, 0x9C)),
        JBColor(Color(0xC0, 0x8A, 0x00), Color(0xFF, 0xD1, 0x66)),
        JBColor(Color(0x00, 0xA0, 0xC6), Color(0x4F, 0xD8, 0xFF)),
        JBColor(Color(0x8A, 0x9A, 0x00), Color(0xC3, 0xF1, 0x4A)),
    )

    fun brickColor(index: Int): Color {
        if (index <= 0) {
            return BRICK_COLORS[0]
        }
        return BRICK_COLORS[index % BRICK_COLORS.size]
    }

    /** What a brick fades towards when it is not the one being looked at. */
    private val BRICK_GREY = JBColor(Color(0xB6, 0xB6, 0xB6), Color(0x56, 0x59, 0x5E))

    private val DARK_LABEL: Color = Color(0x14, 0x14, 0x14)

    private val LIGHT_LABEL: Color = Color(0xF0, 0xF1, 0xF2)

    /**
     * How much of its own colour a brick keeps.
     *
     * Full saturation everywhere was the problem: with every brick shouting, none of them is the
     * answer to "where is this field", and dark text on a saturated fill is hard work to read.
     * So the selection keeps most of its colour, everything else fades towards grey, and padding
     * fades further still -- it is the thing you want to notice, not the thing you want to read.
     */
    fun contrastFor(selected: Boolean, isPadding: Boolean): Int {
        val base: Int
        if (selected) {
            base = MemoryLayoutViewState.selectedContrast
        } else {
            base = MemoryLayoutViewState.dimmedContrast
        }
        if (!isPadding) {
            return base
        }
        return base * MemoryLayoutViewState.paddingContrast / FULL_CONTRAST
    }

    /**
     * [contrast] percent of the colour, the rest of the way to a grey that has the background
     * half mixed into it -- which is what a brick looks like when it is getting out of the way.
     */
    fun towardGrey(color: Color, contrast: Int, background: Color): Color {
        val kept = contrast.coerceIn(0, FULL_CONTRAST) / FULL_CONTRAST.toDouble()
        val grey = translucentGrey(background)
        return Color(
            mixChannel(color.red, grey.red, kept),
            mixChannel(color.green, grey.green, kept),
            mixChannel(color.blue, grey.blue, kept),
        )
    }

    private fun translucentGrey(background: Color): Color {
        val grey: Color = BRICK_GREY
        return Color(
            mixChannel(grey.red, background.red, GREY_SHARE),
            mixChannel(grey.green, background.green, GREY_SHARE),
            mixChannel(grey.blue, background.blue, GREY_SHARE),
        )
    }

    /** A brick's outline: its own colour, half way to black. */
    fun borderOf(fill: Color): Color {
        return Color(
            mixChannel(fill.red, 0, BORDER_SHARE),
            mixChannel(fill.green, 0, BORDER_SHARE),
            mixChannel(fill.blue, 0, BORDER_SHARE),
        )
    }

    private fun mixChannel(from: Int, to: Int, kept: Double): Int {
        return (from * kept + to * (1 - kept)).roundToInt().coerceIn(0, MAX_CHANNEL)
    }

    /**
     * Black or white, whichever the fill can carry.
     *
     * A brick is whatever colour its field drew and whatever the contrast setting left of it, so
     * one fixed label colour is legible on some of them and not on others.
     */
    fun labelOn(fill: Color): Color {
        val luminance = (RED_WEIGHT * fill.red + GREEN_WEIGHT * fill.green + BLUE_WEIGHT * fill.blue) / MAX_CHANNEL
        if (luminance > LIGHT_FILL_THRESHOLD) {
            return DARK_LABEL
        }
        return LIGHT_LABEL
    }

    val brickBorder: Color = Color(0x00, 0x00, 0x00, 0x60)

    /** The rest of the last cache line: bytes the type pays for and does not use. */
    val spareBackground = JBColor(Color(0xE8, 0xE8, 0xE8), Color(0x2A, 0x2C, 0x2F))

    val spareForeground = JBColor(Color(0x8A, 0x8A, 0x8A), Color(0x6B, 0x6F, 0x77))

    val selectionOutline = JBColor(Color(0x10, 0x10, 0x10), Color(0xFF, 0xFF, 0xFF))

    val cacheLineRule = JBColor(Color(0xE0, 0x7A, 0x00), Color(0xFF, 0xA8, 0x3D))

    /**
     * What the window paints behind everything.
     *
     * [fallback] is whatever the component would have used on its own, which is what the theme
     * mode means.
     */
    fun windowBackground(fallback: Color): Color {
        return when (MemoryLayoutViewState.backgroundMode) {
            WindowBackground.THEME -> {
                fallback
            }
            WindowBackground.EDITOR -> {
                EditorColorsManager.getInstance().globalScheme.defaultBackground
            }
            WindowBackground.CUSTOM -> {
                customBackground(fallback)
            }
        }
    }

    private fun customBackground(fallback: Color): Color {
        val rgb = MemoryLayoutViewState.customBackgroundRgb
        if (rgb == MemoryLayoutSettings.NO_CUSTOM_BACKGROUND) {
            return fallback
        }
        return Color(rgb)
    }

    // Words.

    const val TOOL_WINDOW_TITLE = "Memory Layout"

    const val EMPTY_STATE_TEXT = "Right-click a type in the editor and pick Memory Layout."

    const val PADDING_ROW_NAME = "padding"

    const val TAIL_PADDING_SUFFIX = "tail"

    const val UNKNOWN_SIZE = "—"

    const val REFERENCE_MARKER = "ref"

    /** After the type of a reference: a click on the type opens what it points at. */
    const val FOLLOW_MARKER = "→"

    const val FOLLOW_TOOLTIP = "Click the type to open the layout of what this points at"

    const val STRIDE_MARKER = "stride"

    const val COUNT_LABEL = "n ="

    const val COUNT_DESCRIPTION =
        "How many elements this string or array holds. Empty keeps every size a formula in n."

    const val NOT_FOUND_TEXT = "Nothing named %s in the sources or the referenced assemblies"

    const val SPLIT_MARKER = "split"

    const val AUTO_PROPERTY_MARKER = "auto"

    const val OVERLAP_MARKER = "overlaps"

    const val COLUMN_HEX = "hex"

    const val COLUMN_DECIMAL = "dec"

    const val COLUMN_SIZE = "size"

    const val COLUMN_ALIGNMENT = "align"

    const val COLUMN_TYPE = "type"

    const val COLUMN_NAME = "name"

    /**
     * The widest a column is allowed to ask for, in characters.
     *
     * The numeric columns are measured against the type actually on screen, so they need no
     * ceiling -- an offset column is as wide as the largest offset and not one character more.
     * The two text columns do need one: a fully qualified generic name would take the window.
     */
    val COLUMN_CHARACTER_LIMITS = intArrayOf(0, 0, 0, 0, 64, 24)

    /** The line between the numbers and the words, and the air on either side of it. */
    val columnSeparator = JBColor(Color(0x20, 0x20, 0x20), Color(0x00, 0x00, 0x00))

    /** Characters of air each of the two columns beside the rule gives up to it. */
    const val SEPARATOR_CHARACTERS = 1

    const val EXPAND_ALL_TEXT = "Expand All"

    const val COLLAPSE_ALL_TEXT = "Collapse All"

    const val REFRESH_TEXT = "Recompute"

    const val COPY_TEXT = "Copy as Text"

    const val TARGET_TEXT = "Target"

    const val TARGET_64_TEXT = "x64"

    const val TARGET_32_TEXT = "x32"

    const val TARGET_64_DESCRIPTION = "Lay the type out for a 64-bit runtime: references are 8 bytes"

    const val TARGET_32_DESCRIPTION = "Lay the type out for a 32-bit runtime: references are 4 bytes"

    const val CACHE_LINE_LABEL = "cache line"

    const val CACHE_LINE_DESCRIPTION =
        "The cache line to measure against. 64 on x86-64 and ARM64, 128 on Apple silicon, 32 on older ARM."

    const val BYTE_WIDTH_LABEL = "bricks scale"

    const val BYTE_WIDTH_DESCRIPTION = "How wide one byte is drawn in the brick view"

    const val SPARE_TEXT = "spare"

    const val CACHE_LINE_ROW_PREFIX = "line "

    const val BACKGROUND_GROUP = "Background"

    const val BRICKS_GROUP = "Bricks"

    const val SELECTED_CONTRAST_LABEL = "Selected:"

    const val DIMMED_CONTRAST_LABEL = "Everything else:"

    const val PADDING_CONTRAST_LABEL = "Padding:"

    const val LABELS_GROUP = "Brick labels"

    const val LABEL_SIZE_LABEL = "Name size:"

    const val TICK_SIZE_LABEL = "Offsets and ticks:"

    const val TABLE_FONT_LABEL = "Font size:"

    const val TABLE_FONT_DESCRIPTION = "0 leaves the font the IDE theme uses."

    const val LABEL_MINIMUM_SIZE_LABEL = "Shrink to at least:"

    const val LABEL_LINES_LABEL = "Wrap onto at most:"

    const val LABEL_LINES_DESCRIPTION =
        "A name that will not fit is shrunk first, then wrapped, and only then cut from the end."

    const val TARGET_LABEL = "target"

    const val CONTRAST_DESCRIPTION =
        "How much of its own colour a brick keeps, as a percentage. The rest of the way is grey."

    const val BACKGROUND_THEME_TEXT = "Follow the IDE theme"

    const val BACKGROUND_EDITOR_TEXT = "Same as the editor"

    const val BACKGROUND_CUSTOM_TEXT = "Pick one"

    const val AMBIGUOUS_TITLE = "Several types with this name"

    const val PARTIAL_CHOOSER_TITLE = "Declared in several files"

    const val DEFINITION_TOOLTIP = "Click to go to the declaration"


    fun headerText(layout: TypeLayout): String {
        val parts = ArrayList<String>()
        parts.add(layout.displayName)
        parts.add("${sizeText(layout)} B")
        parts.add("align ${layout.alignment}")
        parts.add(paddingText(layout))
        parts.add(confidenceText(layout))
        return parts.filter { part -> part.isNotEmpty() }.joinToString("  ·  ")
    }

    /** The header with the cache-line sentence appended; the tab shows this one. */
    fun headerText(layout: TypeLayout, cacheLineSize: Int): String {
        return headerText(layout) + "  ·  " + cacheLineText(layout, cacheLineSize)
    }

    /**
     * What the type costs in cache lines, assuming it starts on one -- which is what an array of
     * it gives you for the first element and for every element whose size divides the line.
     */
    fun cacheLineText(layout: TypeLayout, cacheLineSize: Int): String {
        val repeat = layout.repeat
        if (repeat != null && !repeat.isCountKnown) {
            return repeatLineText(repeat, cacheLineSize)
        }
        // From where the object really starts: a class's allocation begins at its header, eight
        // bytes before the reference the offsets are measured from.
        val span = layout.size - BrickLayout.lowestOffset(layout.nodes)
        val lines = CacheLineMath.linesTouched(0, span, cacheLineSize)
        val spare = CacheLineMath.wastedTailBytes(span, cacheLineSize)
        val head: String
        if (lines <= 1) {
            head = "fits one $cacheLineSize B line"
        } else {
            head = "$lines lines of $cacheLineSize B"
        }
        if (spare == 0) {
            return head
        }
        return "$head, $spare B spare"
    }

    /**
     * How the elements fall on lines while their number is unknown: how many share the first line
     * with the header, and how many fit in each line after it.
     */
    private fun repeatLineText(repeat: RepeatTail, cacheLineSize: Int): String {
        if (repeat.stride <= 0) {
            return ""
        }
        if (repeat.stride > cacheLineSize) {
            return "each element spans ${(repeat.stride + cacheLineSize - 1) / cacheLineSize} $cacheLineSize B lines"
        }
        val firstLineRoom = cacheLineSize - repeat.offset % cacheLineSize
        val inFirstLine = firstLineRoom / repeat.stride
        val perLine = cacheLineSize / repeat.stride
        return "$inFirstLine element(s) share the first $cacheLineSize B line with the header, $perLine per line after"
    }

    /** `224`, or `22 + 2·n` while the elements of a `string` or an array are not counted. */
    fun sizeText(layout: TypeLayout): String {
        val repeat = layout.repeat
        if (repeat == null || repeat.isCountKnown) {
            return layout.size.toString()
        }
        return "${layout.size} + ${repeat.stride}·${RepeatTail.COUNT_SYMBOL}"
    }

    /** What a repeating row costs: `4·n`, `2·(n + 1)`, or the number once the count is known. */
    fun repeatSizeText(node: LayoutNode): String {
        val count = node.repeatCountText
        if (count.toIntOrNull() != null) {
            return node.size.toString()
        }
        if (count.contains(' ')) {
            return "${node.repeatStride}·($count)"
        }
        return "${node.repeatStride}·$count"
    }

    /** `int × n`, `char × (n + 1)`, `Vector3 × 100`. */
    fun repeatTypeText(node: LayoutNode): String {
        val count = node.repeatCountText
        if (count.contains(' ')) {
            return "${node.typeName} × ($count)"
        }
        return "${node.typeName} × $count"
    }

    fun paddingText(layout: TypeLayout): String {
        if (layout.paddingBytes == 0) {
            return "no padding"
        }
        val percent = (layout.paddingShare * PERCENT).roundToInt()
        return "${layout.paddingBytes} B padding ($percent%)"
    }

    fun confidenceText(layout: TypeLayout): String {
        return when (layout.confidence) {
            LayoutConfidence.EXACT -> {
                blittableText(layout)
            }
            LayoutConfidence.APPROXIMATE -> {
                "approximate"
            }
            LayoutConfidence.RUNTIME_DEFINED -> {
                "runtime-defined"
            }
        }
    }

    fun blittableText(layout: TypeLayout): String {
        if (layout.isBlittable) {
            return "blittable"
        }
        return "not blittable: " + layout.blittableProblems.first()
    }

    /** The name a padding row carries, size included -- it is the whole content of the row. */
    fun paddingRowText(node: LayoutNode): String {
        return "$PADDING_ROW_NAME  ${node.size} B"
    }

    /** The tooltip of a row: everything that did not fit in the columns. */
    fun rowTooltip(node: LayoutNode, cacheLineSize: Int): String? {
        val lines = ArrayList<String>()
        when (node.kind) {
            NodeKind.PADDING -> {
                lines.add("${node.size} byte(s) of alignment padding")
            }
            NodeKind.UNRESOLVED -> {
                lines.add("The type ${node.typeName} was not found in the project index")
            }
            NodeKind.REPEAT -> {
                lines.add(
                    "${repeatTypeText(node)}: the elements follow one another to the end of the object, " +
                        "${node.repeatStride} B apart, in the same allocation. Unfold the row for element [0]."
                )
                if (node.isReference) {
                    lines.add("Each element is a reference. $FOLLOW_TOOLTIP.")
                }
            }
            else -> {
                if (node.isReference) {
                    lines.add(FOLLOW_TOOLTIP)
                    lines.add(
                        "${node.typeName} is held as a reference: this field is one ${node.size}-byte pointer. " +
                            "The object it points at lives on the heap and the runtime lays it out, " +
                            "so there is nothing here to expand."
                    )
                }
            }
        }
        if (CacheLineMath.straddlesBoundary(node.offset, node.size, cacheLineSize)) {
            val boundary = node.offset + cacheLineSize - CacheLineMath.offsetWithinLine(node.offset, cacheLineSize)
            lines.add(
                "A $cacheLineSize B cache line ends at byte $boundary, inside this field: " +
                    "reading it touches two lines."
            )
        }
        if (lines.isEmpty()) {
            return null
        }
        return lines.joinToString("\n")
    }

    /** What a brick says when the mouse rests on it; a cut field says both of its halves. */
    fun brickTooltip(piece: BrickLayout.BrickPiece, cacheLineSize: Int): String {
        val node = piece.node
        val lines = ArrayList<String>()
        if (node.kind == NodeKind.PADDING) {
            lines.add("${node.size} byte(s) of alignment padding at ${node.offset}")
        } else {
            lines.add("${node.typeName}  ${node.fieldName}")
            lines.add("bytes ${node.offset}..${node.endOffset - 1}, ${node.size} B, align ${node.alignment}")
        }
        if (piece.continuesBefore || piece.continuesAfter) {
            lines.add("drawn in ${CacheLineMath.linesTouched(node.offset, node.size, cacheLineSize)} pieces, one per line")
        }
        val rest = rowTooltip(node, cacheLineSize)
        if (rest != null) {
            lines.add(rest)
        }
        return lines.joinToString("\n")
    }

    /** The object header of a class sits before the reference, so this has to sign the number. */
    fun hexOffset(offset: Int): String {
        if (offset < 0) {
            return "-0x" + (-offset).toString(HEX_RADIX).uppercase().padStart(HEX_DIGITS, '0')
        }
        return "0x" + offset.toString(HEX_RADIX).uppercase().padStart(HEX_DIGITS, '0')
    }

    private const val HEX_RADIX = 16

    private const val HEX_DIGITS = 2

    private const val PERCENT = 100.0

    private const val FULL_CONTRAST = 100

    private const val MAX_CHANNEL = 255

    private const val RED_WEIGHT = 0.2126

    private const val GREEN_WEIGHT = 0.7152

    private const val BLUE_WEIGHT = 0.0722

    private const val LIGHT_FILL_THRESHOLD = 0.55

    /** How much of the grey survives when the background is mixed into it. */
    private const val GREY_SHARE = 0.5

    private const val BORDER_SHARE = 0.5
}
