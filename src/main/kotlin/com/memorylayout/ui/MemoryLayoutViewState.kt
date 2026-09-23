package com.memorylayout.ui

import com.memorylayout.layout.CacheLineMath
import com.memorylayout.layout.LayoutTarget
import com.memorylayout.settings.MemoryLayoutProjectSettings
import com.memorylayout.settings.MemoryLayoutSettings
import com.intellij.openapi.project.Project
import com.memorylayout.settings.WindowBackground

/**
 * The settings every open tab shares: the target, the cache line, the column widths (those per
 * project, see [MemoryLayoutProjectSettings]).
 *
 * These are not properties of a type, they are properties of the way the reader is looking at
 * types right now. Switching to 32-bit in one tab and leaving another on 64-bit would mean two
 * tabs whose numbers cannot be compared, which is the opposite of what the tabs are for. So the
 * value lives here, every panel listens, and every change is written to the settings on the spot
 * -- "remembered forever" means the next session opens where this one left off.
 */
object MemoryLayoutViewState {

    /** What a panel is told when something it draws with has changed elsewhere. */
    interface Listener {

        fun targetChanged()

        fun cacheLineSizeChanged()

        fun backgroundChanged()

        fun byteWidthChanged()

        fun appearanceChanged()

        /**
         * @param source whoever dragged the column; that table already has the new width and must
         *   not be laid out again mid-drag.
         */
        fun columnWidthsChanged(source: Any?)
    }

    private val listeners = ArrayList<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    var target: LayoutTarget
        get() = MemoryLayoutSettings.getInstance().target
        set(value) {
            if (value == target) {
                return
            }
            MemoryLayoutSettings.getInstance().target = value
            for (listener in listeners.toList()) {
                listener.targetChanged()
            }
        }

    var cacheLineSize: Int
        get() = MemoryLayoutSettings.getInstance().cacheLineSize
        set(value) {
            val normalized = CacheLineMath.normalize(value)
            if (normalized == cacheLineSize) {
                return
            }
            MemoryLayoutSettings.getInstance().cacheLineSize = normalized
            for (listener in listeners.toList()) {
                listener.cacheLineSizeChanged()
            }
        }

    var backgroundMode: WindowBackground
        get() = MemoryLayoutSettings.getInstance().backgroundMode
        set(value) {
            if (value == backgroundMode) {
                return
            }
            MemoryLayoutSettings.getInstance().backgroundMode = value
            notifyBackgroundChanged()
        }

    var customBackgroundRgb: Int
        get() = MemoryLayoutSettings.getInstance().customBackgroundRgb
        set(value) {
            if (value == customBackgroundRgb) {
                return
            }
            MemoryLayoutSettings.getInstance().customBackgroundRgb = value
            notifyBackgroundChanged()
        }

    /** Pixels per byte in the brick view; 0 means "as wide as four characters of the font". */
    var byteWidth: Int
        get() = MemoryLayoutSettings.getInstance().byteWidth
        set(value) {
            if (value == byteWidth) {
                return
            }
            MemoryLayoutSettings.getInstance().byteWidth = value
            for (listener in listeners.toList()) {
                listener.byteWidthChanged()
            }
        }

    var selectedContrast: Int
        get() = MemoryLayoutSettings.getInstance().selectedContrast
        set(value) {
            MemoryLayoutSettings.getInstance().selectedContrast = value
            notifyAppearanceChanged()
        }

    var dimmedContrast: Int
        get() = MemoryLayoutSettings.getInstance().dimmedContrast
        set(value) {
            MemoryLayoutSettings.getInstance().dimmedContrast = value
            notifyAppearanceChanged()
        }

    var paddingContrast: Int
        get() = MemoryLayoutSettings.getInstance().paddingContrast
        set(value) {
            MemoryLayoutSettings.getInstance().paddingContrast = value
            notifyAppearanceChanged()
        }

    var labelFontSize: Int
        get() = MemoryLayoutSettings.getInstance().labelFontSize
        set(value) {
            MemoryLayoutSettings.getInstance().labelFontSize = value
            notifyAppearanceChanged()
        }

    var labelMinimumFontSize: Int
        get() = MemoryLayoutSettings.getInstance().labelMinimumFontSize
        set(value) {
            MemoryLayoutSettings.getInstance().labelMinimumFontSize = value
            notifyAppearanceChanged()
        }

    var labelMaximumLines: Int
        get() = MemoryLayoutSettings.getInstance().labelMaximumLines
        set(value) {
            MemoryLayoutSettings.getInstance().labelMaximumLines = value
            notifyAppearanceChanged()
        }

    var tableFontSize: Int
        get() = MemoryLayoutSettings.getInstance().tableFontSize
        set(value) {
            MemoryLayoutSettings.getInstance().tableFontSize = value
            notifyAppearanceChanged()
        }

    var tickFontSize: Int
        get() = MemoryLayoutSettings.getInstance().tickFontSize
        set(value) {
            MemoryLayoutSettings.getInstance().tickFontSize = value
            notifyAppearanceChanged()
        }

    /** Colours, contrast, fonts: everything the views redraw without recomputing anything. */
    fun notifyAppearanceChanged() {
        for (listener in listeners.toList()) {
            listener.appearanceChanged()
        }
    }

    /** Called by the settings page too, which writes the colour without going through here. */
    fun notifyBackgroundChanged() {
        for (listener in listeners.toList()) {
            listener.backgroundChanged()
        }
    }

    /**
     * The stored width of a column, or [NO_STORED_WIDTH] when the reader never dragged it.
     *
     * The project's own widths first; a project that has none yet starts from the widths last
     * dragged anywhere, rather than from nothing.
     */
    fun columnWidthOf(project: Project, columnId: String): Int {
        var text = MemoryLayoutProjectSettings.getInstance(project).columnWidths
        if (text.isEmpty()) {
            text = MemoryLayoutSettings.getInstance().columnWidths
        }
        for (entry in text.split(ENTRY_SEPARATOR)) {
            val separator = entry.indexOf(VALUE_SEPARATOR)
            if (separator <= 0) {
                continue
            }
            if (entry.substring(0, separator) != columnId) {
                continue
            }
            val width = entry.substring(separator + 1).toIntOrNull() ?: continue
            if (width <= 0) {
                continue
            }
            return width
        }
        return NO_STORED_WIDTH
    }

    /**
     * Stores what the reader dragged, for this project and as the default for the next one, and
     * tells every open tab. A tab of another project is told too; it reads its own project's
     * widths back and finds nothing changed.
     */
    fun rememberColumnWidths(project: Project, widths: Map<String, Int>, source: Any?) {
        val text = widths.entries.joinToString(ENTRY_SEPARATOR) { entry ->
            entry.key + VALUE_SEPARATOR + entry.value
        }
        val projectSettings = MemoryLayoutProjectSettings.getInstance(project)
        if (text == projectSettings.columnWidths) {
            return
        }
        projectSettings.columnWidths = text
        MemoryLayoutSettings.getInstance().columnWidths = text
        for (listener in listeners.toList()) {
            listener.columnWidthsChanged(source)
        }
    }

    const val NO_STORED_WIDTH = -1

    private const val ENTRY_SEPARATOR = ";"

    private const val VALUE_SEPARATOR = "="
}
