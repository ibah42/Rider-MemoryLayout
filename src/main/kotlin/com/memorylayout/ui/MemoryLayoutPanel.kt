package com.memorylayout.ui

import com.memorylayout.index.IndexedType
import com.memorylayout.layout.BrickLayout
import com.memorylayout.layout.CacheLineMath
import com.memorylayout.layout.LayoutNode
import com.memorylayout.layout.LayoutTarget
import com.memorylayout.layout.TypeLayout
import com.memorylayout.settings.MemoryLayoutSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.IdentityHashMap
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JSplitPane
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.TreePath

/**
 * One tab: the header line, the toolbar, the table and the bricks for a single type.
 *
 * The two views are one picture of one tree. The table decides what is unfolded, the bricks follow
 * it, and a click on either selects in both -- they agree because they are handed the same
 * [LayoutNode] instances and compare them by identity, not by name or offset.
 *
 * They scroll apart on purpose: a 64-byte line at four characters a byte is wider than any panel,
 * so the bricks need their own horizontal scrolling, and the offsets stay put in a row header
 * rather than scrolling off to the left with everything else.
 *
 * What it knows how to do is recompute itself -- from the index, on a pooled thread -- and draw
 * what came back. Target, cache line, byte width and background live in [MemoryLayoutViewState],
 * shared by every open tab.
 */
class MemoryLayoutPanel(
    private val project: Project,
    private val entry: IndexedType,
) : SimpleToolWindowPanel(true, true), Disposable, MemoryLayoutViewState.Listener {

    private val headerLabel = JBLabel()

    private val tableHolder = JPanel(BorderLayout())

    private val brickView = BrickView()

    private val brickScroll = JBScrollPane(brickView)

    private val splitter = JSplitPane(JSplitPane.VERTICAL_SPLIT, tableHolder, brickScroll)

    private val targetControl = TargetControl()

    private val cacheLineControl = CacheLineControl()

    private val byteWidthControl = ByteWidthControl()

    private var table: TreeTable? = null

    private var layout: TypeLayout? = null

    private var nodePaths = IdentityHashMap<LayoutNode, TreePath>()

    /** The background the theme would have used, kept so "follow the theme" can go back to it. */
    private var themeBackground: Color = Color.WHITE

    /** True while this panel is the one moving the table's selection, not the reader. */
    private var changingSelection = false

    val typeQualifiedName: String
        get() = entry.qualifiedName

    init {
        headerLabel.border = JBUI.Borders.empty(HEADER_PADDING)
        brickScroll.setRowHeaderView(BrickGutter(brickView))
        brickScroll.border = JBUI.Borders.empty()
        brickView.onNodeSelected = { node ->
            selectInTable(node)
        }
        splitter.resizeWeight = TABLE_SHARE
        splitter.dividerSize = JBUI.scale(DIVIDER_SIZE)
        splitter.border = JBUI.Borders.empty()
        val content = JPanel(BorderLayout())
        content.add(headerLabel, BorderLayout.NORTH)
        content.add(splitter, BorderLayout.CENTER)
        setContent(content)
        toolbar = createToolbar()
        MemoryLayoutViewState.addListener(this)
        recompute()
    }

    private fun createToolbar(): JPanel {
        val group = DefaultActionGroup()
        group.add(simpleAction(MemoryLayoutStyle.EXPAND_ALL_TEXT, AllIcons.Actions.Expandall) {
            table?.let { current ->
                LayoutTreeTable.expandAll(current)
            }
        })
        group.add(simpleAction(MemoryLayoutStyle.COLLAPSE_ALL_TEXT, AllIcons.Actions.Collapseall) {
            table?.let { current ->
                LayoutTreeTable.collapseAll(current)
            }
        })
        group.addSeparator()
        group.add(simpleAction(MemoryLayoutStyle.REFRESH_TEXT, AllIcons.Actions.Refresh) {
            recompute()
        })
        group.add(simpleAction(MemoryLayoutStyle.COPY_TEXT, AllIcons.Actions.Copy) {
            copyToClipboard()
        })
        val actionToolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        actionToolbar.targetComponent = this
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, CONTROL_GAP, 0))
        controls.add(targetControl)
        controls.add(cacheLineControl)
        controls.add(byteWidthControl)
        val wrapper = JPanel(BorderLayout())
        wrapper.add(actionToolbar.component, BorderLayout.WEST)
        wrapper.add(controls, BorderLayout.CENTER)
        return wrapper
    }

    private fun simpleAction(text: String, icon: javax.swing.Icon, action: () -> Unit): AnAction {
        return object : AnAction(text, text, icon) {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }

            override fun actionPerformed(event: AnActionEvent) {
                action()
            }
        }
    }

    /**
     * A toolbar button that shows whether it is the one in force.
     *
     * Plain Swing rather than a `ToggleAction`: an action with no icon draws the platform's own
     * on/off diamond and the text is the small print next to it, which is backwards when the text
     * -- `x64`, `128` -- is the whole point of the button.
     */
    private inner class ChoiceButton(
        text: String,
        tooltip: String,
        private val isActive: () -> Boolean,
        private val activate: () -> Unit,
    ) : JButton(text) {

        private val plainForeground: Color = foreground

        init {
            toolTipText = tooltip
            margin = Insets(0, BUTTON_INSET, 0, BUTTON_INSET)
            isFocusable = false
            addActionListener {
                activate()
            }
        }

        fun refresh() {
            if (isActive()) {
                font = font.deriveFont(Font.BOLD)
                foreground = MemoryLayoutStyle.referenceForeground
                return
            }
            font = font.deriveFont(Font.PLAIN)
            foreground = plainForeground
        }
    }

    /** What the numbers are for. Shared by every tab, so both tabs always compare like with like. */
    private inner class TargetControl : JPanel(FlowLayout(FlowLayout.LEFT, CONTROL_GAP, 0)) {

        private val buttons = ArrayList<ChoiceButton>()

        init {
            val label = JLabel(MemoryLayoutStyle.TARGET_LABEL)
            label.toolTipText = MemoryLayoutStyle.TARGET_64_DESCRIPTION
            add(label)
            add(LayoutTarget.X64, MemoryLayoutStyle.TARGET_64_TEXT, MemoryLayoutStyle.TARGET_64_DESCRIPTION)
            add(LayoutTarget.X86, MemoryLayoutStyle.TARGET_32_TEXT, MemoryLayoutStyle.TARGET_32_DESCRIPTION)
            refresh()
        }

        private fun add(value: LayoutTarget, text: String, description: String) {
            val button = ChoiceButton(
                text,
                description,
                { MemoryLayoutViewState.target == value },
                { MemoryLayoutViewState.target = value },
            )
            buttons.add(button)
            add(button)
        }

        fun refresh() {
            for (button in buttons) {
                button.refresh()
            }
        }
    }

    /** The cache line the table and the bricks measure against: the sizes that exist, as buttons. */
    private inner class CacheLineControl : JPanel(FlowLayout(FlowLayout.LEFT, CONTROL_GAP, 0)) {

        private val buttons = ArrayList<ChoiceButton>()

        init {
            val label = JLabel(MemoryLayoutStyle.CACHE_LINE_LABEL)
            label.toolTipText = MemoryLayoutStyle.CACHE_LINE_DESCRIPTION
            add(label)
            for (preset in CacheLineMath.PRESET_LINE_SIZES) {
                add(preset)
            }
            refresh()
        }

        private fun add(size: Int) {
            val button = ChoiceButton(
                size.toString(),
                MemoryLayoutStyle.CACHE_LINE_DESCRIPTION,
                { MemoryLayoutViewState.cacheLineSize == size },
                { MemoryLayoutViewState.cacheLineSize = size },
            )
            buttons.add(button)
            add(button)
        }

        fun refresh() {
            for (button in buttons) {
                button.refresh()
            }
        }
    }

    /** How wide one byte is drawn. Four characters by default, and squeezable when a type is big. */
    private inner class ByteWidthControl : JPanel(FlowLayout(FlowLayout.LEFT, CONTROL_GAP, 0)) {

        private val slider = JSlider(
            MemoryLayoutSettings.MINIMUM_BYTE_WIDTH,
            MemoryLayoutSettings.MAXIMUM_BYTE_WIDTH,
            MemoryLayoutSettings.MINIMUM_BYTE_WIDTH,
        )

        /** True while the slider is being moved to match the view, not by the reader. */
        private var following = false

        init {
            val label = JLabel(MemoryLayoutStyle.BYTE_WIDTH_LABEL)
            label.toolTipText = MemoryLayoutStyle.BYTE_WIDTH_DESCRIPTION
            add(label)
            slider.toolTipText = MemoryLayoutStyle.BYTE_WIDTH_DESCRIPTION
            slider.isFocusable = false
            slider.preferredSize = java.awt.Dimension(JBUI.scale(SLIDER_WIDTH), slider.preferredSize.height)
            slider.addChangeListener {
                if (!following) {
                    MemoryLayoutViewState.byteWidth = slider.value
                }
            }
            add(slider)
        }

        fun showCurrentWidth() {
            val width = brickView.byteWidth()
            if (slider.value == width) {
                return
            }
            // Moving the knob to where the view already is must not pin the derived width.
            following = true
            slider.value = width
            following = false
        }
    }

    override fun targetChanged() {
        targetControl.refresh()
        recompute()
    }

    override fun cacheLineSizeChanged() {
        cacheLineControl.refresh()
        redraw()
    }

    override fun backgroundChanged() {
        applyBackground()
    }

    override fun byteWidthChanged() {
        rebuildBricks()
    }

    override fun appearanceChanged() {
        table?.let { current ->
            LayoutTreeTable.applyFont(current, MemoryLayoutViewState.tableFontSize)
            LayoutTreeTable.applyStoredWidths(current)
        }
        brickView.revalidate()
        brickView.repaint()
        brickScroll.rowHeader.view?.repaint()
    }

    override fun columnWidthsChanged(source: Any?) {
        val current = table ?: return
        if (current === source) {
            return
        }
        LayoutTreeTable.applyStoredWidths(current)
        current.repaint()
    }

    fun recompute() {
        val target = MemoryLayoutViewState.target
        ApplicationManager.getApplication().executeOnPooledThread {
            val computed = LayoutComputer.compute(project, entry, target)
            ApplicationManager.getApplication().invokeLater {
                show(computed)
            }
        }
    }

    /** Draws the layout already in hand again: what changed is how it is shown, not the numbers. */
    private fun redraw() {
        show(layout)
    }

    private fun show(computed: TypeLayout?) {
        layout = computed
        tableHolder.removeAll()
        if (computed == null) {
            headerLabel.text = "${entry.simpleName}: the declaration could not be read"
            table = null
            brickView.show(emptyList(), MemoryLayoutViewState.cacheLineSize, 0)
            tableHolder.revalidate()
            tableHolder.repaint()
            return
        }
        val cacheLineSize = MemoryLayoutViewState.cacheLineSize
        headerLabel.text = MemoryLayoutStyle.headerText(computed, cacheLineSize)
        headerLabel.toolTipText = computed.notes.joinToString("\n").ifEmpty { null }
        val settings = MemoryLayoutSettings.getInstance()
        val built = LayoutTreeTable.build(computed, settings.showPaddingRows, cacheLineSize)
        themeBackground = built.background
        built.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount < DOUBLE_CLICK) {
                    return
                }
                navigateTo(LayoutTreeTable.nodeAt(built, built.rowAtPoint(event.point)))
            }
        })
        built.tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                rebuildBricks()
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {
                rebuildBricks()
            }
        })
        built.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !changingSelection) {
                selectionChanged(built)
            }
        }
        LayoutTreeTable.applyFont(built, MemoryLayoutViewState.tableFontSize)
        // After the font, not before: the columns are measured in characters of it.
        LayoutTreeTable.applyStoredWidths(built)
        LayoutTreeTable.expandTo(built, settings.automaticExpandDepth)
        table = built
        nodePaths = LayoutTreeTable.pathsByNode(built)
        tableHolder.add(JBScrollPane(built), BorderLayout.CENTER)
        tableHolder.revalidate()
        tableHolder.repaint()
        applyBackground()
        rebuildBricks()
    }

    private fun rebuildBricks() {
        val current = layout ?: return
        val currentTable = table ?: return
        val lineSize = MemoryLayoutViewState.cacheLineSize
        val rows = BrickLayout.rowsOf(
            current.nodes,
            { node ->
                LayoutTreeTable.isExpanded(currentTable, nodePaths, node)
            },
            lineSize,
            current.size,
            BrickLayout.lowestOffset(current.nodes),
        )
        brickView.show(rows, lineSize, current.size)
        byteWidthControl.showCurrentWidth()
        brickScroll.revalidate()
        brickScroll.repaint()
    }

    /**
     * The table's selection moved: widen it over the subtree and tell the bricks.
     *
     * Widening puts the selection back into the model it came from, so the guard is what stops
     * that from being read as a second selection and going round again.
     */
    private fun selectionChanged(current: TreeTable) {
        val rows = current.selectedRows
        // One row means "this field and what it is made of"; several mean exactly those, because
        // the reader picked them one at a time the way they would pick files.
        if (rows.size == 1) {
            changingSelection = true
            LayoutTreeTable.selectSubtree(current, rows[0])
            changingSelection = false
        }
        brickView.select(LayoutTreeTable.nodesAt(current, current.selectedRows))
    }

    private fun selectInTable(node: LayoutNode) {
        val current = table ?: return
        LayoutTreeTable.selectNode(current, nodePaths, node)
    }

    private fun applyBackground() {
        val background = MemoryLayoutStyle.windowBackground(themeBackground)
        table?.let { current ->
            LayoutTreeTable.applyBackground(current, background)
        }
        brickView.background = background
        brickScroll.rowHeader.view?.background = background
        brickScroll.viewport.background = background
        brickScroll.repaint()
    }

    private fun navigateTo(node: LayoutNode?) {
        if (node == null || node.declarationOffset < 0 || node.fileId.isEmpty()) {
            return
        }
        val file = VirtualFileManager.getInstance().findFileByUrl(node.fileId) ?: return
        OpenFileDescriptor(project, file, node.declarationOffset).navigate(true)
    }

    private fun copyToClipboard() {
        val current = layout ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(LayoutComputer.asText(current)))
    }

    override fun dispose() {
        MemoryLayoutViewState.removeListener(this)
        brickView.onNodeSelected = null
        table = null
        layout = null
        nodePaths = IdentityHashMap()
    }

    companion object {
        private const val TOOLBAR_PLACE = "MemoryLayoutToolbar"

        private const val HEADER_PADDING = 6

        private const val DOUBLE_CLICK = 2

        private const val CONTROL_GAP = 4

        private const val BUTTON_INSET = 6

        private const val SLIDER_WIDTH = 96

        private const val DIVIDER_SIZE = 6

        private const val TABLE_SHARE = 0.62
    }
}
