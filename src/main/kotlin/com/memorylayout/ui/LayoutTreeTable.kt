package com.memorylayout.ui

import com.memorylayout.layout.CacheLineMath
import com.memorylayout.layout.LayoutNode
import com.memorylayout.layout.NodeKind
import com.memorylayout.layout.TypeLayout
import com.memorylayout.settings.MemoryLayoutSettings
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.IdentityHashMap
import java.util.TreeSet
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableColumn
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * The first row of the table: the type itself, as a total.
 *
 * Its size and alignment sit in the columns the fields' do, so the whole reads as a sum; the name
 * column says where the type is declared, and a click on the row goes to the declaration. The
 * padding and the cache lines stay in the header line above the table.
 *
 * @param location `WorldChunk.cs:14`, or the assembly for a type with no source
 * @param isNavigable whether a click has anywhere to go
 */
class SummaryRow(
    val typeName: String,

    /** `224`, or `22 + 2·n` for a string or an array whose length is not given. */
    val sizeText: String,
    val alignment: Int,
    val location: String,
    val tooltip: String?,
    val isNavigable: Boolean,
)

/**
 * Turns a layout into the table on screen.
 *
 * The hierarchy lives in the `name` column: what nests is the path to a member -- `position.x` --
 * and a name is what a reader follows down. The numbers keep the left edge to themselves and read
 * as four columns of numbers, which is the other half of why this is readable at all. It works
 * because [TreeColumnInfo] reports `TreeTableModel` as its column class, and [TreeTable] draws the
 * tree in whichever column says that, wherever it sits.
 *
 * Depth is said twice: by the indent the tree already draws, and by a bar in the level's colour at
 * the left of the row. One of the two survives a narrow column, a long type name or a struct three
 * levels deep, which is more than can be said for the indent on its own.
 */
object LayoutTreeTable {

    /** Column order. The tree is the last of them. */
    private val COLUMN_IDS = arrayOf("hex", "dec", "size", "align", "type", "name")

    const val TREE_COLUMN_INDEX = 5

    private const val COLUMN_INDEX_HEX = 0

    private const val COLUMN_INDEX_DECIMAL = 1

    private const val COLUMN_INDEX_SIZE = 2

    private const val COLUMN_INDEX_ALIGNMENT = 3

    private const val COLUMN_INDEX_TYPE = 4

    /** The model index of the type column, where a click on a reference follows it. */
    const val TYPE_COLUMN_INDEX = COLUMN_INDEX_TYPE

    /** Room for the sort arrow and the cell inset, on top of the text the column has to hold. */
    private const val COLUMN_SLACK = 4

    /**
     * A numeric column starts between these two, in characters: four is `-0x8`, five is the
     * `align` title, eight is an offset past a megabyte. Wider than that is air nobody reads.
     */
    private const val MINIMUM_NUMBER_CHARACTERS = 4

    private const val MAXIMUM_NUMBER_CHARACTERS = 8

    /**
     * How much air a numeric column gets on top of its text, as a factor. The offsets get more:
     * `0x` and a hex digit run read as one smudge when they touch the column edge.
     */
    private const val HEX_AIR_FACTOR = 1.7

    private const val NUMBER_AIR_FACTOR = 1.5

    /**
     * The type column starts as wide as this type's longest type name, and never narrower than
     * this: `NativeArray< BlockResourceData >` is where Unity code usually lands.
     */
    private const val MINIMUM_TYPE_CHARACTERS = 32

    /** However far a column is dragged in, it keeps this much, so its edge can still be grabbed. */
    private const val MINIMUM_DRAGGED_CHARACTERS = 2

    /** The name column is what is left, but never less than this. */
    private const val MINIMUM_NAME_CHARACTERS = 8

    private const val ROW_PADDING = 4

    /** Room the name column leaves for the level bar and the markers after the name. */
    private const val MARKER_ROOM = "      "

    private val COLUMN_TITLES = arrayOf(
        MemoryLayoutStyle.COLUMN_HEX,
        MemoryLayoutStyle.COLUMN_DECIMAL,
        MemoryLayoutStyle.COLUMN_SIZE,
        MemoryLayoutStyle.COLUMN_ALIGNMENT,
        MemoryLayoutStyle.COLUMN_TYPE,
        MemoryLayoutStyle.COLUMN_NAME,
    )

    private const val LEVEL_BAR_WIDTH = 3

    private const val LEVEL_BAR_GAP = 4

    private const val LEVEL_BAR_HEIGHT = 12

    private val bindings = java.util.WeakHashMap<TreeTable, WidthBinding>()

    fun build(
        project: Project,
        layout: TypeLayout,
        summary: SummaryRow,
        showPadding: Boolean,
        cacheLineSize: Int,
    ): TreeTable {
        val root = DefaultMutableTreeNode()
        root.add(DefaultMutableTreeNode(summary))
        addNodes(root, layout.nodes, showPadding)
        val model = ListTreeTableModelOnColumns(root, columns())
        val table = TreeTable(model)
        table.setRootVisible(false)
        table.tree.isRootVisible = false
        table.tree.showsRootHandles = true
        table.tree.cellRenderer = NameCellRenderer(cacheLineSize)
        table.rowSelectionAllowed = true
        // Every column but the name column is locked at its width (see WidthBinding), so whatever
        // the table's own layout has to give or take can only land on the name column.
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        applyRenderers(table, cacheLineSize)
        val binding = WidthBinding(project, table, widestTexts(layout, summary, showPadding))
        bindings[table] = binding
        binding.applyStoredWidths()
        binding.attach()
        return table
    }

    /** Lays the columns out at the widths the reader last dragged them to, in every open tab. */
    fun applyStoredWidths(table: TreeTable) {
        bindings[table]?.applyStoredWidths()
    }

    /**
     * How many characters each column has to hold for *this* type.
     *
     * Measured rather than guessed: a struct whose largest offset is 28 wants three characters of
     * offset column, and one that runs to 4096 wants six. A fixed width is wrong in both
     * directions at once -- pointless air on the small type, a clipped number on the big one --
     * and the reader should not have to drag a column to read a number the window already knows
     * the width of.
     */
    private fun widestTexts(layout: TypeLayout, summary: SummaryRow, showPadding: Boolean): Array<String> {
        val widest = Array(COLUMN_IDS.size) { "" }
        keepWidest(widest, COLUMN_INDEX_SIZE, summary.sizeText)
        keepWidest(widest, COLUMN_INDEX_TYPE, summary.typeName)
        measureInto(widest, layout.nodes, showPadding)
        for (index in widest.indices) {
            val limit = MemoryLayoutStyle.COLUMN_CHARACTER_LIMITS[index]
            if (limit > 0 && widest[index].length > limit) {
                widest[index] = widest[index].substring(0, limit)
            }
        }
        return widest
    }

    private fun measureInto(widest: Array<String>, nodes: List<LayoutNode>, showPadding: Boolean) {
        for (node in nodes) {
            if (node.kind == NodeKind.PADDING && !showPadding) {
                continue
            }
            keepWidest(widest, COLUMN_INDEX_HEX, MemoryLayoutStyle.hexOffset(node.offset))
            keepWidest(widest, COLUMN_INDEX_DECIMAL, node.offset.toString())
            keepWidest(widest, COLUMN_INDEX_SIZE, sizeText(node))
            keepWidest(widest, COLUMN_INDEX_ALIGNMENT, alignmentText(node))
            keepWidest(widest, COLUMN_INDEX_TYPE, typeText(node))
            keepWidest(widest, TREE_COLUMN_INDEX, node.fieldName + MARKER_ROOM)
            measureInto(widest, node.children, showPadding)
        }
    }

    private fun keepWidest(widest: Array<String>, index: Int, candidate: String) {
        if (candidate.length > widest[index].length) {
            widest[index] = candidate
        }
    }

    private fun columns(): Array<ColumnInfo<*, *>> {
        return arrayOf(
            TextColumn(
                MemoryLayoutStyle.COLUMN_HEX,
                { node -> MemoryLayoutStyle.hexOffset(node.offset) },
                { _ -> "" },
            ),
            TextColumn(
                MemoryLayoutStyle.COLUMN_DECIMAL,
                { node -> node.offset.toString() },
                { _ -> "" },
            ),
            TextColumn(
                MemoryLayoutStyle.COLUMN_SIZE,
                { node -> sizeText(node) },
                { summary -> summary.sizeText },
            ),
            TextColumn(
                MemoryLayoutStyle.COLUMN_ALIGNMENT,
                { node -> alignmentText(node) },
                { summary -> summary.alignment.toString() },
            ),
            TextColumn(
                MemoryLayoutStyle.COLUMN_TYPE,
                { node -> typeText(node) },
                { summary -> summary.typeName },
            ),
            TreeColumnInfo(MemoryLayoutStyle.COLUMN_NAME),
        )
    }

    private fun sizeText(node: LayoutNode): String {
        if (node.kind == NodeKind.UNRESOLVED) {
            return MemoryLayoutStyle.UNKNOWN_SIZE
        }
        if (node.kind == NodeKind.REPEAT) {
            return MemoryLayoutStyle.repeatSizeText(node)
        }
        return node.size.toString()
    }

    private fun alignmentText(node: LayoutNode): String {
        if (node.kind == NodeKind.PADDING || node.kind == NodeKind.UNRESOLVED) {
            return MemoryLayoutStyle.UNKNOWN_SIZE
        }
        return node.alignment.toString()
    }

    private fun typeText(node: LayoutNode): String {
        if (node.kind == NodeKind.PADDING) {
            return MemoryLayoutStyle.UNKNOWN_SIZE
        }
        var text = node.typeName
        if (node.kind == NodeKind.REPEAT) {
            text = MemoryLayoutStyle.repeatTypeText(node)
        }
        if (node.isReference) {
            return text + "  " + MemoryLayoutStyle.REFERENCE_MARKER + " " + MemoryLayoutStyle.FOLLOW_MARKER
        }
        if (node.kind == NodeKind.REPEAT) {
            return text
        }
        if (node.kind == NodeKind.UNRESOLVED) {
            return node.typeName + "  ?"
        }
        return node.typeName
    }

    private fun addNodes(parent: DefaultMutableTreeNode, nodes: List<LayoutNode>, showPadding: Boolean) {
        for (node in nodes) {
            if (node.kind == NodeKind.PADDING && !showPadding) {
                continue
            }
            val treeNode = DefaultMutableTreeNode(node)
            parent.add(treeNode)
            addNodes(treeNode, node.children, showPadding)
        }
    }

    private fun applyRenderers(table: TreeTable, cacheLineSize: Int) {
        for (index in 0 until table.columnModel.columnCount) {
            if (index == TREE_COLUMN_INDEX) {
                continue
            }
            val alignment: Int
            if (index == COLUMN_INDEX_TYPE) {
                alignment = SwingConstants.LEFT
            } else {
                alignment = SwingConstants.RIGHT
            }
            table.columnModel.getColumn(index).cellRenderer = ValueCellRenderer(alignment, index, cacheLineSize)
        }
    }

    fun expandAll(table: TreeTable) {
        var row = 0
        while (row < table.tree.rowCount) {
            table.tree.expandRow(row)
            row++
        }
    }

    fun collapseAll(table: TreeTable) {
        var row = table.tree.rowCount - 1
        while (row >= 0) {
            table.tree.collapseRow(row)
            row--
        }
    }

    /** Expands the first [depth] levels and nothing below them. */
    fun expandTo(table: TreeTable, depth: Int) {
        if (depth <= 0) {
            return
        }
        var row = 0
        while (row < table.tree.rowCount) {
            val path = table.tree.getPathForRow(row)
            if (path != null && path.pathCount <= depth + 1) {
                table.tree.expandRow(row)
            }
            row++
        }
    }

    /**
     * Every node in the table, with the path that reaches it.
     *
     * Built once per rebuild and keyed by identity: the table may be hiding padding rows, so a
     * node's position in the tree is not its position in the layout, and only the instance itself
     * is the same thing in both views.
     */
    fun pathsByNode(table: TreeTable): IdentityHashMap<LayoutNode, TreePath> {
        val paths = IdentityHashMap<LayoutNode, TreePath>()
        val root = table.tree.model.root as? DefaultMutableTreeNode ?: return paths
        collectPaths(root, TreePath(root), paths)
        return paths
    }

    private fun collectPaths(
        treeNode: DefaultMutableTreeNode,
        path: TreePath,
        paths: IdentityHashMap<LayoutNode, TreePath>,
    ) {
        for (index in 0 until treeNode.childCount) {
            val child = treeNode.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            val childPath = path.pathByAddingChild(child)
            val node = child.userObject as? LayoutNode
            if (node != null) {
                paths[node] = childPath
            }
            collectPaths(child, childPath, paths)
        }
    }

    fun isExpanded(table: TreeTable, paths: IdentityHashMap<LayoutNode, TreePath>, node: LayoutNode): Boolean {
        val path = paths[node] ?: return false
        return table.tree.isExpanded(path)
    }

    /**
     * The rows the selection should cover, given the rows the reader picked.
     *
     * Selecting a struct means selecting its members -- they are what it is made of, and the
     * bricks light up that way, so the table has to agree. So every picked row grows over its own
     * unfolded subtree, and it does so whether the reader picked one row, a shift range or a
     * handful of separate ones. One rule for all three is the only way the result stays
     * predictable: a range that grew at one end and not at the other is what reads as crooked.
     *
     * Growing is all this does. A row nobody picked, and nobody's subtree reaches, stays out.
     */
    fun subtreeSelection(table: TreeTable, rows: IntArray): IntArray {
        val covered = TreeSet<Int>()
        for (row in rows) {
            if (row < 0) {
                continue
            }
            covered.add(row)
            val path = table.tree.getPathForRow(row) ?: continue
            var next = row + 1
            while (next < table.tree.rowCount) {
                val candidate = table.tree.getPathForRow(next)
                if (candidate == null || !path.isDescendant(candidate)) {
                    break
                }
                covered.add(next)
                next++
            }
        }
        return covered.toIntArray()
    }

    /**
     * Holds these rows selected, as the runs of consecutive rows they are made of.
     *
     * The anchor goes back where the reader left it. It is the end a shift-click extends from, so
     * leaving it at the top of whatever subtree the previous click grew into is what sent the
     * next shift-click over rows nobody asked for.
     */
    fun applySelection(table: TreeTable, rows: IntArray) {
        val selection = table.selectionModel
        val anchor = selection.anchorSelectionIndex
        selection.clearSelection()
        var start = 0
        while (start < rows.size) {
            var end = start
            while (end + 1 < rows.size && rows[end + 1] == rows[end] + 1) {
                end++
            }
            selection.addSelectionInterval(rows[start], rows[end])
            start = end + 1
        }
        if (anchor >= 0) {
            selection.anchorSelectionIndex = anchor
        }
    }

    /** Selects the row of this node, unfolding whatever is hiding it. */
    fun selectNode(table: TreeTable, paths: IdentityHashMap<LayoutNode, TreePath>, node: LayoutNode) {
        val path = paths[node] ?: return
        val ancestors = ArrayList<TreePath>()
        var ancestor = path.parentPath
        while (ancestor != null) {
            ancestors.add(ancestor)
            ancestor = ancestor.parentPath
        }
        for (index in ancestors.indices.reversed()) {
            table.tree.expandPath(ancestors[index])
        }
        val row = table.tree.getRowForPath(path)
        if (row < 0) {
            return
        }
        table.setRowSelectionInterval(row, row)
        // Only when it is not on screen already: the scroll pane animates, and an animation
        // nobody needed is what makes a click feel slow.
        val cell = table.getCellRect(row, 0, true)
        if (!table.visibleRect.contains(cell)) {
            table.scrollRectToVisible(cell)
        }
    }

    /** The background the reader asked for, on the table and on the tree that draws one column. */
    fun applyBackground(table: TreeTable, background: java.awt.Color) {
        table.background = background
        table.tree.background = background
    }

    /** The nodes of several selected rows, the way a file list selects several files. */
    fun nodesAt(table: TreeTable, rows: IntArray): List<LayoutNode> {
        val nodes = ArrayList<LayoutNode>()
        for (row in rows) {
            val node = nodeAt(table, row)
            if (node != null) {
                nodes.add(node)
            }
        }
        return nodes
    }

    /** The table's own font, when the reader asked for one that is not the theme's. */
    fun applyFont(table: TreeTable, size: Int) {
        if (size <= 0) {
            return
        }
        val font = table.font.deriveFont(JBUI.scale(size).toFloat())
        table.font = font
        table.tree.font = font
        val height = table.getFontMetrics(font).height + JBUI.scale(ROW_PADDING)
        table.rowHeight = height
        table.tree.rowHeight = height
    }

    /** True when the point is on the type of a reference: a click there opens what it points at. */
    fun isFollowableTypeAt(table: TreeTable, point: java.awt.Point): Boolean {
        val row = table.rowAtPoint(point)
        val column = table.columnAtPoint(point)
        if (row < 0 || column < 0) {
            return false
        }
        if (table.convertColumnIndexToModel(column) != TYPE_COLUMN_INDEX) {
            return false
        }
        val node = nodeAt(table, row) ?: return false
        return node.isReference && node.repeatSource == null
    }

    /** True for the total at the top, which stands for the type rather than any of its fields. */
    fun isSummaryRow(table: TreeTable, row: Int): Boolean {
        return summaryAt(table, row) != null
    }

    private fun summaryAt(table: TreeTable, row: Int): SummaryRow? {
        val path: TreePath = table.tree.getPathForRow(row) ?: return null
        val treeNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return treeNode.userObject as? SummaryRow
    }

    fun nodeAt(table: TreeTable, row: Int): LayoutNode? {
        val path: TreePath = table.tree.getPathForRow(row) ?: return null
        val treeNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return treeNode.userObject as? LayoutNode
    }

    private class TextColumn(
        name: String,
        private val textOf: (LayoutNode) -> String,
        private val summaryTextOf: (SummaryRow) -> String,
    ) : ColumnInfo<DefaultMutableTreeNode, String>(name) {

        override fun valueOf(item: DefaultMutableTreeNode): String {
            val summary = item.userObject as? SummaryRow
            if (summary != null) {
                return summaryTextOf(summary)
            }
            val node = item.userObject as? LayoutNode ?: return ""
            return textOf(node)
        }
    }

    /**
     * Holds one table's column widths, and keeps them in step with every other open tab.
     *
     * Every column but the name column is locked: its minimum and maximum width are both set to
     * the width it should have. `JTable` has several layout passes of its own -- a new tab, a
     * resized window, a scrollbar appearing, and above all the one it runs while a header reports
     * a resizing column, which copies the current widths back into the preferred ones -- and a
     * preferred width is only a suggestion to each of them. A locked column is not negotiable, so
     * all of them can only move the name column, which is the one meant to take the slack.
     *
     * That is what went wrong before: the widths were set as preferences, a layout pass put the
     * Swing default of 75 back, and the next drag stored those 75s as if the reader had chosen them.
     *
     * A drag unlocks the one column being dragged -- on the press, after the header has decided
     * which column that is -- and locks it again at its new width on release, which is also when
     * the width is stored and handed to the other tabs.
     */
    private class WidthBinding(
        private val project: Project,
        private val table: TreeTable,
        private val widest: Array<String>,
    ) {

        /** The column a drag has unlocked, until the mouse comes up. */
        private var draggedColumn: TableColumn? = null

        fun attach() {
            val header = table.tableHeader ?: return
            val listener = object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    startDrag(header.resizingColumn)
                }

                /**
                 * The header's own handler normally runs first and has named the column by the
                 * time the press reaches here -- but a theme change installs it again, after this
                 * one. Then the first movement of the drag is where the column becomes known.
                 */
                override fun mouseDragged(event: MouseEvent) {
                    if (draggedColumn == null) {
                        startDrag(header.resizingColumn)
                    }
                }

                override fun mouseReleased(event: MouseEvent) {
                    finishDrag()
                }
            }
            header.addMouseListener(listener)
            header.addMouseMotionListener(listener)
        }

        /** Locks every column but the name column at its stored width, or its default one. */
        fun applyStoredWidths() {
            val columnModel = table.columnModel
            val lockedCount = minOf(columnModel.columnCount, COLUMN_IDS.size) - 1
            for (index in 0 until lockedCount) {
                val column = columnModel.getColumn(index)
                if (column === draggedColumn) {
                    continue
                }
                lock(column, widthOf(index))
            }
            if (columnModel.columnCount > 0) {
                val nameColumn = columnModel.getColumn(columnModel.columnCount - 1)
                nameColumn.minWidth = table.getFontMetrics(table.font).charWidth('0') * MINIMUM_NAME_CHARACTERS
            }
            table.revalidate()
            table.repaint()
        }

        private fun widthOf(index: Int): Int {
            val stored = MemoryLayoutViewState.columnWidthOf(project, COLUMN_IDS[index])
            if (stored == MemoryLayoutViewState.NO_STORED_WIDTH) {
                return defaultWidth(index)
            }
            return maxOf(stored, minimumDraggedWidth())
        }

        private fun startDrag(column: TableColumn?) {
            if (column == null || column.modelIndex == TREE_COLUMN_INDEX) {
                return
            }
            draggedColumn = column
            column.maxWidth = Int.MAX_VALUE
            column.minWidth = minimumDraggedWidth()
        }

        private fun finishDrag() {
            val column = draggedColumn ?: return
            draggedColumn = null
            val width = maxOf(column.width, minimumDraggedWidth())
            lock(column, width)
            val widths = LinkedHashMap<String, Int>()
            val columnModel = table.columnModel
            for (index in 0 until minOf(columnModel.columnCount, COLUMN_IDS.size) - 1) {
                widths[COLUMN_IDS[index]] = columnModel.getColumn(index).width
            }
            MemoryLayoutViewState.rememberColumnWidths(project, widths, table)
        }

        /**
         * Pins a column at one width. The order matters: `TableColumn` clamps the width and the
         * preference into the current bounds on every change, so the bounds open first.
         */
        private fun lock(column: TableColumn, width: Int) {
            column.maxWidth = Int.MAX_VALUE
            column.minWidth = width
            column.maxWidth = width
            column.preferredWidth = width
            column.width = width
        }

        /**
         * Where a column starts before anyone drags it: its widest text or its title, whichever is
         * wider, within the bounds of its kind.
         *
         * The widest text is measured, not counted: the table's font is the IDE's, digits in it
         * are narrower than its average character, and a count multiplied by an average width
         * pays for space no digit ever occupies.
         */
        private fun defaultWidth(index: Int): Int {
            val metrics = table.getFontMetrics(table.font)
            val digitWidth = metrics.charWidth('0')
            val header = metrics.stringWidth(COLUMN_TITLES[index])
            val content = metrics.stringWidth(widest[index])
            var width: Int
            if (index == COLUMN_INDEX_TYPE) {
                width = maxOf(header, content, digitWidth * MINIMUM_TYPE_CHARACTERS)
            } else {
                width = maxOf(header, content, digitWidth * MINIMUM_NUMBER_CHARACTERS)
                width = minOf(width, digitWidth * MAXIMUM_NUMBER_CHARACTERS)
                val airFactor: Double
                if (index == COLUMN_INDEX_HEX) {
                    airFactor = HEX_AIR_FACTOR
                } else {
                    airFactor = NUMBER_AIR_FACTOR
                }
                width = (width * airFactor).toInt()
            }
            width += JBUI.scale(COLUMN_SLACK)
            if (index == COLUMN_INDEX_ALIGNMENT || index == COLUMN_INDEX_TYPE) {
                // Room for the rule between the numbers and the words, and the air around it.
                width += digitWidth * MemoryLayoutStyle.SEPARATOR_CHARACTERS
            }
            return width
        }

        private fun minimumDraggedWidth(): Int {
            return table.getFontMetrics(table.font).charWidth('0') * MINIMUM_DRAGGED_CHARACTERS
        }
    }

    /** The bar at the left of a row saying which nesting level it belongs to. */
    private class LevelBar(private val color: Color) : Icon {

        override fun getIconWidth(): Int {
            return JBUI.scale(LEVEL_BAR_WIDTH + LEVEL_BAR_GAP)
        }

        override fun getIconHeight(): Int {
            return JBUI.scale(LEVEL_BAR_HEIGHT)
        }

        override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
            graphics.color = color
            graphics.fillRect(x, y, JBUI.scale(LEVEL_BAR_WIDTH), JBUI.scale(LEVEL_BAR_HEIGHT))
        }
    }

    /** Draws the `name` column, which is also the one carrying the expanders and the level bars. */
    private class NameCellRenderer(private val cacheLineSize: Int) : ColoredTreeCellRenderer() {

        private val levelBars = HashMap<Int, Icon>()

        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val treeNode = value as? DefaultMutableTreeNode ?: return
            val summary = treeNode.userObject as? SummaryRow
            if (summary != null) {
                appendSummary(summary)
                return
            }
            val node = treeNode.userObject as? LayoutNode ?: return
            setIcon(levelBarOf(treeNode.level - 1))
            when (node.kind) {
                NodeKind.PADDING -> {
                    append(
                        MemoryLayoutStyle.paddingRowText(node),
                        SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_ITALIC,
                            MemoryLayoutStyle.paddingForeground,
                        ),
                    )
                }
                NodeKind.UNRESOLVED -> {
                    append(
                        node.fieldName,
                        SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_PLAIN,
                            MemoryLayoutStyle.unresolvedForeground,
                        ),
                    )
                }
                NodeKind.RUNTIME -> {
                    append(
                        node.fieldName,
                        SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_ITALIC,
                            MemoryLayoutStyle.runtimeForeground,
                        ),
                    )
                }
                else -> {
                    appendField(node)
                }
            }
            toolTipText = MemoryLayoutStyle.rowTooltip(node, cacheLineSize)
        }

        /** Where the type is declared, as a link when there is somewhere to go. */
        private fun appendSummary(summary: SummaryRow) {
            // The renderer is shared by every row; the previous one's level bar must not stay.
            setIcon(null)
            val locationAttributes: SimpleTextAttributes
            if (summary.isNavigable) {
                locationAttributes = SimpleTextAttributes.LINK_ATTRIBUTES
            } else {
                locationAttributes = SimpleTextAttributes.GRAYED_ATTRIBUTES
            }
            append(summary.location, locationAttributes)
            val tooltipLines = ArrayList<String>()
            if (summary.isNavigable) {
                tooltipLines.add(MemoryLayoutStyle.DEFINITION_TOOLTIP)
            }
            summary.tooltip?.let { notes ->
                tooltipLines.add(notes)
            }
            if (tooltipLines.isEmpty()) {
                toolTipText = null
            } else {
                toolTipText = tooltipLines.joinToString("\n")
            }
        }

        private fun appendField(node: LayoutNode) {
            append(node.fieldName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (node.kind == NodeKind.REPEAT) {
                append(
                    "  " + MemoryLayoutStyle.STRIDE_MARKER + " " + node.repeatStride,
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
            }
            if (node.isAutoProperty && MemoryLayoutSettings.getInstance().markAutoProperties) {
                append(
                    "  " + MemoryLayoutStyle.AUTO_PROPERTY_MARKER,
                    SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        MemoryLayoutStyle.autoPropertyForeground,
                    ),
                )
            }
            if (node.overlapsPrevious) {
                append(
                    "  " + MemoryLayoutStyle.OVERLAP_MARKER,
                    SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        MemoryLayoutStyle.overlapForeground,
                    ),
                )
            }
            if (CacheLineMath.straddlesBoundary(node.offset, node.size, cacheLineSize)) {
                append(
                    "  " + MemoryLayoutStyle.SPLIT_MARKER,
                    SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_BOLD,
                        MemoryLayoutStyle.splitForeground,
                    ),
                )
            }
        }

        private fun levelBarOf(level: Int): Icon {
            val existing = levelBars[level]
            if (existing != null) {
                return existing
            }
            val created = LevelBar(MemoryLayoutStyle.levelColor(level))
            levelBars[level] = created
            return created
        }
    }

    /** Draws every column that is not the tree. Colour says which kind of number it is. */
    private class ValueCellRenderer(
        private val horizontalAlignment: Int,
        private val columnIndex: Int,
        private val cacheLineSize: Int,
    ) : DefaultTableCellRenderer() {

        private var cellBorder: javax.swing.border.Border? = null

        private var borderedFontSize = -1

        /**
         * The rule between the last number and the first word.
         *
         * `align` and `type` were touching, and a column of digits running straight into a column
         * of identifiers reads as one smeared column. A line with a character of air on each side
         * is the cheapest thing that separates them, and it moves with the font rather than being
         * a fixed number of pixels that stops being right the moment the font size changes.
         */
        private fun borderFor(table: JTable): javax.swing.border.Border? {
            if (columnIndex != COLUMN_INDEX_ALIGNMENT && columnIndex != COLUMN_INDEX_TYPE) {
                return null
            }
            val size = table.font.size
            val existing = cellBorder
            if (existing != null && borderedFontSize == size) {
                return existing
            }
            val gap = table.getFontMetrics(table.font).charWidth('0')
            val border: javax.swing.border.Border
            if (columnIndex == COLUMN_INDEX_ALIGNMENT) {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 0, 1, MemoryLayoutStyle.columnSeparator),
                    BorderFactory.createEmptyBorder(0, 0, 0, gap),
                )
            } else {
                border = BorderFactory.createEmptyBorder(0, gap, 0, 0)
            }
            cellBorder = border
            borderedFontSize = size
            return border
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            setHorizontalAlignment(horizontalAlignment)
            borderFor(table)?.let { border ->
                setBorder(border)
            }
            val treeTable = table as? TreeTable
            val isSummary = treeTable != null && isSummaryRow(treeTable, row)
            // The total reads as a total: bold, the way a sum line is set under a column.
            if (isSummary) {
                font = table.font.deriveFont(Font.BOLD)
            } else {
                font = table.font
            }
            if (isSelected) {
                return component
            }
            val node: LayoutNode?
            if (treeTable != null) {
                node = nodeAt(treeTable, row)
            } else {
                node = null
            }
            background = backgroundOf(table, node)
            foreground = foregroundOf(table, node)
            return component
        }

        private fun backgroundOf(table: JTable, node: LayoutNode?): Color {
            if (node?.kind == NodeKind.PADDING) {
                return MemoryLayoutStyle.paddingBackground
            }
            return table.background
        }

        private fun foregroundOf(table: JTable, node: LayoutNode?): Color {
            if (node == null) {
                return table.foreground
            }
            if (node.kind == NodeKind.PADDING) {
                return MemoryLayoutStyle.paddingForeground
            }
            if (node.kind == NodeKind.UNRESOLVED) {
                return MemoryLayoutStyle.unresolvedForeground
            }
            if (node.kind == NodeKind.RUNTIME) {
                return MemoryLayoutStyle.runtimeForeground
            }
            if (columnIndex == COLUMN_INDEX_TYPE && node.isReference) {
                return MemoryLayoutStyle.referenceForeground
            }
            if (isCutByACacheLine(node)) {
                return MemoryLayoutStyle.splitForeground
            }
            return when (columnIndex) {
                COLUMN_INDEX_HEX -> {
                    MemoryLayoutStyle.hexOffsetForeground
                }
                COLUMN_INDEX_DECIMAL -> {
                    MemoryLayoutStyle.decimalOffsetForeground
                }
                COLUMN_INDEX_SIZE -> {
                    MemoryLayoutStyle.sizeForeground
                }
                COLUMN_INDEX_ALIGNMENT -> {
                    MemoryLayoutStyle.alignmentForeground
                }
                COLUMN_INDEX_TYPE -> {
                    MemoryLayoutStyle.typeForeground
                }
                else -> {
                    table.foreground
                }
            }
        }

        /** The offsets are what says a field is split, so they are what turns colour. */
        private fun isCutByACacheLine(node: LayoutNode): Boolean {
            if (columnIndex != COLUMN_INDEX_HEX && columnIndex != COLUMN_INDEX_DECIMAL) {
                return false
            }
            return CacheLineMath.straddlesBoundary(node.offset, node.size, cacheLineSize)
        }
    }
}
