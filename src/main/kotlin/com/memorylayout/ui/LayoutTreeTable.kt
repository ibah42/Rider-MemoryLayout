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
import java.awt.Graphics
import java.util.IdentityHashMap
import java.util.TreeSet
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.event.ChangeEvent
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

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

    /** Room for the sort arrow and the cell inset, on top of the text the column has to hold. */
    private const val COLUMN_SLACK = 4

    /**
     * Digits a numeric column holds before it has to grow: sizes and alignments are almost always
     * under a thousand, and an offset column wider than its numbers is just air.
     */
    private const val MINIMUM_NUMBER_CHARACTERS = 3

    /** The type column never starts narrower than this, however short this type's names are. */
    private const val MINIMUM_TYPE_CHARACTERS = 20

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

    fun build(project: Project, layout: TypeLayout, showPadding: Boolean, cacheLineSize: Int): TreeTable {
        val root = DefaultMutableTreeNode()
        addNodes(root, layout.nodes, showPadding)
        val model = ListTreeTableModelOnColumns(root, columns())
        val table = object : TreeTable(model) {
            override fun doLayout() {
                if (!layoutColumns(this)) {
                    super.doLayout()
                }
            }
        }
        table.setRootVisible(false)
        table.tree.isRootVisible = false
        table.tree.showsRootHandles = true
        table.tree.cellRenderer = NameCellRenderer(cacheLineSize)
        table.rowSelectionAllowed = true
        // Anything but the last column keeps the width it was given; the name column takes the
        // slack. With the default mode a drag steals from the neighbour and no width ever sticks.
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        applyRenderers(table, cacheLineSize)
        val binding = WidthBinding(project, table, widestTexts(layout, showPadding))
        bindings[table] = binding
        binding.applyStoredWidths()
        binding.attach()
        return table
    }

    /**
     * Every column but the last at exactly its preferred width, the name column taking whatever is
     * left. Returns false during a drag, which the table's own layout handles.
     *
     * `JTable` does not do this by itself, whatever its resize mode: `AUTO_RESIZE_LAST_COLUMN`
     * only governs a drag, and every other layout -- a new tab, a resized window, a scrollbar
     * appearing -- spreads the spare width over all the columns in proportion. That is what kept
     * making the numeric columns wide again, and why a width dragged in one tab never showed in
     * another: it was set as a preference and then spread away.
     */
    private fun layoutColumns(table: TreeTable): Boolean {
        if (table.tableHeader?.resizingColumn != null) {
            return false
        }
        val columnModel = table.columnModel
        val columnCount = columnModel.columnCount
        if (columnCount == 0) {
            return true
        }
        var used = 0
        for (index in 0 until columnCount - 1) {
            val column = columnModel.getColumn(index)
            column.width = column.preferredWidth
            used += column.preferredWidth
        }
        val last = columnModel.getColumn(columnCount - 1)
        val minimum = table.getFontMetrics(table.font).charWidth('0') * MINIMUM_NAME_CHARACTERS
        last.width = maxOf(table.width - used, minimum)
        return true
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
    private fun widestTexts(layout: TypeLayout, showPadding: Boolean): Array<String> {
        val widest = Array(COLUMN_IDS.size) { "" }
        keepWidest(widest, COLUMN_INDEX_SIZE, layout.size.toString())
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
            TextColumn(MemoryLayoutStyle.COLUMN_HEX) { node -> MemoryLayoutStyle.hexOffset(node.offset) },
            TextColumn(MemoryLayoutStyle.COLUMN_DECIMAL) { node -> node.offset.toString() },
            TextColumn(MemoryLayoutStyle.COLUMN_SIZE) { node -> sizeText(node) },
            TextColumn(MemoryLayoutStyle.COLUMN_ALIGNMENT) { node -> alignmentText(node) },
            TextColumn(MemoryLayoutStyle.COLUMN_TYPE) { node -> typeText(node) },
            TreeColumnInfo(MemoryLayoutStyle.COLUMN_NAME),
        )
    }

    private fun sizeText(node: LayoutNode): String {
        if (node.kind == NodeKind.UNRESOLVED) {
            return MemoryLayoutStyle.UNKNOWN_SIZE
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
        if (node.isReference) {
            return node.typeName + "  " + MemoryLayoutStyle.REFERENCE_MARKER
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

    fun nodeAt(table: TreeTable, row: Int): LayoutNode? {
        val path: TreePath = table.tree.getPathForRow(row) ?: return null
        val treeNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return treeNode.userObject as? LayoutNode
    }

    private class TextColumn(
        name: String,
        private val textOf: (LayoutNode) -> String,
    ) : ColumnInfo<DefaultMutableTreeNode, String>(name) {

        override fun valueOf(item: DefaultMutableTreeNode): String {
            val node = item.userObject as? LayoutNode ?: return ""
            return textOf(node)
        }
    }

    /**
     * Keeps one table's column widths in step with every other open tab.
     *
     * The width is written on every pixel of a drag rather than when the mouse comes up: there is
     * no "drag finished" event on a column model, and the other tabs following along live is what
     * makes it obvious the setting is shared.
     */
    private class WidthBinding(
        private val project: Project,
        private val table: TreeTable,
        private val widest: Array<String>,
    ) : TableColumnModelListener {

        private var applying = false

        fun attach() {
            table.columnModel.addColumnModelListener(this)
        }

        fun applyStoredWidths() {
            applying = true
            // The name column is not set: it takes what the others leave, see layoutColumns.
            for (index in 0 until minOf(table.columnModel.columnCount, COLUMN_IDS.size) - 1) {
                val column = table.columnModel.getColumn(index)
                val needed = neededWidth(index)
                val stored = MemoryLayoutViewState.columnWidthOf(project, COLUMN_IDS[index])
                if (stored == MemoryLayoutViewState.NO_STORED_WIDTH) {
                    column.preferredWidth = needed
                    continue
                }
                if (index == COLUMN_INDEX_TYPE) {
                    // A type name that does not fit is cut with an ellipsis the reader can see;
                    // the dragged width stands.
                    column.preferredWidth = stored
                    continue
                }
                // A clipped number looks like a different number, so a numeric column grows past
                // a dragged width when this type's numbers need it -- and only then.
                column.preferredWidth = maxOf(stored, needed)
            }
            // A preferred width is only a wish until the table lays itself out again -- but a
            // table that has not been shown yet has no width to distribute, and laying it out now
            // would squash the last column to its minimum before anyone sees it.
            if (table.width > 0) {
                table.doLayout()
            }
            applying = false
        }

        /**
         * What the column has to be: its widest text or its header, whichever is wider, plus air.
         *
         * The widest text is measured, not counted: the table's font is the IDE's, digits in it
         * are narrower than its average character, and a count multiplied by an average width
         * pays for space no digit ever occupies.
         */
        private fun neededWidth(index: Int): Int {
            val metrics = table.getFontMetrics(table.font)
            val header = metrics.stringWidth(COLUMN_TITLES[index])
            val content = metrics.stringWidth(widest[index])
            val floor: Int
            if (index == COLUMN_INDEX_TYPE) {
                floor = metrics.charWidth('0') * MINIMUM_TYPE_CHARACTERS
            } else {
                floor = metrics.charWidth('0') * MINIMUM_NUMBER_CHARACTERS
            }
            var width = maxOf(header, content, floor) + JBUI.scale(COLUMN_SLACK)
            if (index == COLUMN_INDEX_ALIGNMENT || index == COLUMN_INDEX_TYPE) {
                // Room for the rule between the numbers and the words, and the air around it.
                width += metrics.charWidth('0') * MemoryLayoutStyle.SEPARATOR_CHARACTERS
            }
            return width
        }

        override fun columnMarginChanged(event: ChangeEvent) {
            if (applying) {
                return
            }
            // Only a drag counts. The table fires this from its own layout as well -- resizing the
            // window, showing a scrollbar -- and storing those meant every install ended up with
            // a set of widths nobody chose, overriding the measured ones for good.
            if (table.tableHeader?.resizingColumn == null) {
                return
            }
            val widths = LinkedHashMap<String, Int>()
            // A drag sets a column's width but not its preference, and the next layout -- ours,
            // which lays columns out by preference -- would put the column straight back. So the
            // preference follows the drag. The name column is left out: its width is the
            // window's, not a choice.
            applying = true
            for (index in 0 until minOf(table.columnModel.columnCount, COLUMN_IDS.size) - 1) {
                val column = table.columnModel.getColumn(index)
                column.preferredWidth = column.width
                widths[COLUMN_IDS[index]] = column.width
            }
            applying = false
            MemoryLayoutViewState.rememberColumnWidths(project, widths, table)
        }

        override fun columnAdded(event: TableColumnModelEvent) {
        }

        override fun columnRemoved(event: TableColumnModelEvent) {
        }

        override fun columnMoved(event: TableColumnModelEvent) {
        }

        override fun columnSelectionChanged(event: ListSelectionEvent) {
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

        private fun appendField(node: LayoutNode) {
            append(node.fieldName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
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
            if (isSelected) {
                return component
            }
            val treeTable = table as? TreeTable
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
