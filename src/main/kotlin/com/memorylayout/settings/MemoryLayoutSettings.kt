package com.memorylayout.settings

import com.memorylayout.layout.CacheLineMath
import com.memorylayout.layout.LayoutTarget
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Where the window takes its background from.
 *
 * [EDITOR] is the one worth having: the table sits next to the editor all day, and a panel a
 * shade off the code it is about reads as a different application.
 */
enum class WindowBackground {
    THEME,
    EDITOR,
    CUSTOM,
}

/** Which folders the type index walks. A Unity project keeps its own sources in two of them. */
enum class IndexScope {
    ASSETS_ONLY,
    ASSETS_AND_PACKAGES,
    WHOLE_PROJECT,
}

/**
 * Everything the user can turn: the target the numbers are for, what the index covers, and what
 * the window shows.
 *
 * Stored application-wide rather than per project: these are reading habits, not project facts.
 */
@Service(Service.Level.APP)
@State(name = "MemoryLayoutSettings", storages = [Storage("memory-layout.xml")])
class MemoryLayoutSettings : PersistentStateComponent<MemoryLayoutSettings.Config> {

    /** A plain class with defaults, which is what the XML serializer can read back. */
    class Config {
        var targetName: String = LayoutTarget.X64.name
        var indexScopeName: String = IndexScope.ASSETS_AND_PACKAGES.name
        var showPaddingRows: Boolean = true
        var markAutoProperties: Boolean = true
        var automaticExpandDepth: Int = 0
        var openEachTypeInItsOwnTab: Boolean = true
        var focusWindowOnOpen: Boolean = true
        var cacheLineSize: Int = CacheLineMath.DEFAULT_LINE_SIZE
        var backgroundModeName: String = WindowBackground.THEME.name
        var customBackgroundRgb: Int = NO_CUSTOM_BACKGROUND

        /** Pixels per byte in the brick view. [DERIVED_BYTE_WIDTH] means "four characters wide". */
        var byteWidth: Int = DERIVED_BYTE_WIDTH

        /** How much colour a brick keeps, as a percentage. The rest of the way is plain grey. */
        var selectedContrast: Int = DEFAULT_SELECTED_CONTRAST
        var dimmedContrast: Int = DEFAULT_DIMMED_CONTRAST
        var paddingContrast: Int = DEFAULT_PADDING_CONTRAST

        /** The table's own font. [THEME_FONT_SIZE] leaves whatever the IDE theme uses. */
        var tableFontSize: Int = THEME_FONT_SIZE

        /** The offsets down the side of the bricks and the ticks along the bottom of a row. */
        var tickFontSize: Int = DEFAULT_TICK_FONT_SIZE

        /** How a name is fitted onto a brick before it has to be cut. */
        var labelFontSize: Int = DEFAULT_LABEL_FONT_SIZE
        var labelMinimumFontSize: Int = DEFAULT_LABEL_MINIMUM_FONT_SIZE
        var labelMaximumLines: Int = DEFAULT_LABEL_MAXIMUM_LINES

        /**
         * The table's column widths, as `hex=52;dec=44`.
         *
         * A string rather than a map because this is written on every pixel of a column drag and
         * read back by a serializer whose map support is not worth depending on for six numbers.
         *
         * This one is only the default for a project that has none of its own yet; see
         * [MemoryLayoutProjectSettings].
         *
         * The name carries a 3. Version 2 was fed widths the table's own layout had stretched --
         * every non-dragged layout spread the spare width over all six columns -- so what it holds
         * is a set of bloated numbers nobody chose, and reading it back would bring them back.
         */
        var columnWidths3: String = ""
    }

    private var config = Config()

    override fun getState(): Config {
        return config
    }

    override fun loadState(state: Config) {
        config = state
    }

    var target: LayoutTarget
        get() {
            return LayoutTarget.entries.firstOrNull { entry -> entry.name == config.targetName } ?: LayoutTarget.X64
        }
        set(value) {
            config.targetName = value.name
        }

    var indexScope: IndexScope
        get() {
            return IndexScope.entries.firstOrNull { entry -> entry.name == config.indexScopeName }
                ?: IndexScope.ASSETS_AND_PACKAGES
        }
        set(value) {
            config.indexScopeName = value.name
        }

    var showPaddingRows: Boolean
        get() = config.showPaddingRows
        set(value) {
            config.showPaddingRows = value
        }

    /** Whether an auto-property's backing field says so in the table. It is a field either way. */
    var markAutoProperties: Boolean
        get() = config.markAutoProperties
        set(value) {
            config.markAutoProperties = value
        }

    var automaticExpandDepth: Int
        get() = config.automaticExpandDepth
        set(value) {
            config.automaticExpandDepth = value
        }

    var openEachTypeInItsOwnTab: Boolean
        get() = config.openEachTypeInItsOwnTab
        set(value) {
            config.openEachTypeInItsOwnTab = value
        }

    var focusWindowOnOpen: Boolean
        get() = config.focusWindowOnOpen
        set(value) {
            config.focusWindowOnOpen = value
        }

    /** Always a power of two in range; what the user typed is brought onto one on the way in. */
    var cacheLineSize: Int
        get() = CacheLineMath.normalize(config.cacheLineSize)
        set(value) {
            config.cacheLineSize = CacheLineMath.normalize(value)
        }

    var backgroundMode: WindowBackground
        get() {
            return WindowBackground.entries.firstOrNull { entry -> entry.name == config.backgroundModeName }
                ?: WindowBackground.THEME
        }
        set(value) {
            config.backgroundModeName = value.name
        }

    /** The chosen colour as a packed RGB, or [NO_CUSTOM_BACKGROUND] when none was ever picked. */
    var customBackgroundRgb: Int
        get() = config.customBackgroundRgb
        set(value) {
            config.customBackgroundRgb = value
        }

    var byteWidth: Int
        get() = config.byteWidth
        set(value) {
            config.byteWidth = value
        }

    /** The colour a brick keeps while it is part of the selection. */
    var selectedContrast: Int
        get() = config.selectedContrast
        set(value) {
            config.selectedContrast = value
        }

    /** The colour every other brick keeps once something is selected. */
    var dimmedContrast: Int
        get() = config.dimmedContrast
        set(value) {
            config.dimmedContrast = value
        }

    /** Applied on top of the other two, so padding is always the quietest thing on the row. */
    var paddingContrast: Int
        get() = config.paddingContrast
        set(value) {
            config.paddingContrast = value
        }

    /** 0 leaves the IDE theme's own font alone. */
    var tableFontSize: Int
        get() = config.tableFontSize
        set(value) {
            config.tableFontSize = value
        }

    var tickFontSize: Int
        get() = config.tickFontSize
        set(value) {
            config.tickFontSize = value
        }

    /** The size a brick's name is drawn at when it fits without help. */
    var labelFontSize: Int
        get() = config.labelFontSize
        set(value) {
            config.labelFontSize = value
        }

    /** How small the name may be shrunk before it is wrapped or cut instead. */
    var labelMinimumFontSize: Int
        get() = config.labelMinimumFontSize
        set(value) {
            config.labelMinimumFontSize = value
        }

    var labelMaximumLines: Int
        get() = config.labelMaximumLines
        set(value) {
            config.labelMaximumLines = value
        }

    /** The widths as stored; parsing them is the table's business, not the settings'. */
    var columnWidths: String
        get() = config.columnWidths3
        set(value) {
            config.columnWidths3 = value
        }

    companion object {
        const val NO_CUSTOM_BACKGROUND = -1

        const val DERIVED_BYTE_WIDTH = 0

        const val MINIMUM_BYTE_WIDTH = 10

        const val MAXIMUM_BYTE_WIDTH = 60

        const val MINIMUM_CONTRAST = 0

        const val MAXIMUM_CONTRAST = 100

        const val DEFAULT_SELECTED_CONTRAST = 64

        const val DEFAULT_DIMMED_CONTRAST = 20

        const val DEFAULT_PADDING_CONTRAST = 55

        const val DEFAULT_LABEL_FONT_SIZE = 11

        const val DEFAULT_LABEL_MINIMUM_FONT_SIZE = 8

        const val DEFAULT_LABEL_MAXIMUM_LINES = 2

        const val MINIMUM_LABEL_FONT_SIZE = 5

        const val MAXIMUM_LABEL_FONT_SIZE = 20

        const val MAXIMUM_LABEL_LINES = 3

        const val THEME_FONT_SIZE = 0

        const val DEFAULT_TICK_FONT_SIZE = 9

        fun getInstance(): MemoryLayoutSettings {
            return service()
        }
    }
}
