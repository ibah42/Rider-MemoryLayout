package com.memorylayout.settings

import com.memorylayout.layout.CacheLineMath
import com.memorylayout.layout.LayoutTarget
import com.memorylayout.ui.MemoryLayoutStyle
import com.memorylayout.ui.MemoryLayoutViewState
import com.intellij.ui.ColorPanel
import java.awt.Color
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/**
 * The settings page.
 *
 * Deliberately small: what the numbers are for, what the index covers, and what the table shows.
 * Anything that is a property of the code rather than of the reader belongs in the source, not
 * here.
 */
class MemoryLayoutConfigurable : BoundConfigurable(DISPLAY_NAME) {

    override fun createPanel(): DialogPanel {
        val settings = MemoryLayoutSettings.getInstance()
        return panel {
            group("Numbers") {
                buttonsGroup {
                    row("Target:") {
                        radioButton("64-bit", LayoutTarget.X64)
                        radioButton("32-bit", LayoutTarget.X86)
                    }
                    // Through the view state rather than the settings: an open tab has to follow
                    // this, and the settings service has no way to tell it.
                }.bind(
                    SettingProperty({ settings.target }, { value -> MemoryLayoutViewState.target = value }),
                    LayoutTarget::class.java,
                )
                row("Cache line:") {
                    spinner(CacheLineMath.MINIMUM_LINE_SIZE..CacheLineMath.MAXIMUM_LINE_SIZE)
                        .bindIntValue(MemoryLayoutViewState::cacheLineSize)
                }
            }
            group(MemoryLayoutStyle.BACKGROUND_GROUP) {
                buttonsGroup {
                    row("Window:") {
                        radioButton(MemoryLayoutStyle.BACKGROUND_THEME_TEXT, WindowBackground.THEME)
                        radioButton(MemoryLayoutStyle.BACKGROUND_EDITOR_TEXT, WindowBackground.EDITOR)
                        radioButton(MemoryLayoutStyle.BACKGROUND_CUSTOM_TEXT, WindowBackground.CUSTOM)
                    }
                }.bind(
                    SettingProperty(
                        { settings.backgroundMode },
                        { value -> MemoryLayoutViewState.backgroundMode = value },
                    ),
                    WindowBackground::class.java,
                )
                row("Colour:") {
                    cell(colorPanel)
                        .onIsModified {
                            chosenRgb() != settings.customBackgroundRgb
                        }
                        .onApply {
                            settings.customBackgroundRgb = chosenRgb()
                            MemoryLayoutViewState.notifyBackgroundChanged()
                        }
                        .onReset {
                            showStoredColour(settings)
                        }
                }
            }
            group(MemoryLayoutStyle.BRICKS_GROUP) {
                row(MemoryLayoutStyle.SELECTED_CONTRAST_LABEL) {
                    spinner(MemoryLayoutSettings.MINIMUM_CONTRAST..MemoryLayoutSettings.MAXIMUM_CONTRAST)
                        .bindIntValue(MemoryLayoutViewState::selectedContrast)
                }.comment(MemoryLayoutStyle.CONTRAST_DESCRIPTION)
                row(MemoryLayoutStyle.DIMMED_CONTRAST_LABEL) {
                    spinner(MemoryLayoutSettings.MINIMUM_CONTRAST..MemoryLayoutSettings.MAXIMUM_CONTRAST)
                        .bindIntValue(MemoryLayoutViewState::dimmedContrast)
                }
                row(MemoryLayoutStyle.PADDING_CONTRAST_LABEL) {
                    spinner(MemoryLayoutSettings.MINIMUM_CONTRAST..MemoryLayoutSettings.MAXIMUM_CONTRAST)
                        .bindIntValue(MemoryLayoutViewState::paddingContrast)
                }
            }
            group(MemoryLayoutStyle.LABELS_GROUP) {
                row(MemoryLayoutStyle.LABEL_SIZE_LABEL) {
                    spinner(
                        MemoryLayoutSettings.MINIMUM_LABEL_FONT_SIZE..MemoryLayoutSettings.MAXIMUM_LABEL_FONT_SIZE
                    ).bindIntValue(MemoryLayoutViewState::labelFontSize)
                }
                row(MemoryLayoutStyle.LABEL_MINIMUM_SIZE_LABEL) {
                    spinner(
                        MemoryLayoutSettings.MINIMUM_LABEL_FONT_SIZE..MemoryLayoutSettings.MAXIMUM_LABEL_FONT_SIZE
                    ).bindIntValue(MemoryLayoutViewState::labelMinimumFontSize)
                }
                row(MemoryLayoutStyle.TICK_SIZE_LABEL) {
                    spinner(
                        MemoryLayoutSettings.MINIMUM_LABEL_FONT_SIZE..MemoryLayoutSettings.MAXIMUM_LABEL_FONT_SIZE
                    ).bindIntValue(MemoryLayoutViewState::tickFontSize)
                }
                row(MemoryLayoutStyle.LABEL_LINES_LABEL) {
                    spinner(1..MemoryLayoutSettings.MAXIMUM_LABEL_LINES)
                        .bindIntValue(MemoryLayoutViewState::labelMaximumLines)
                }.comment(MemoryLayoutStyle.LABEL_LINES_DESCRIPTION)
            }
            group("Index") {
                buttonsGroup {
                    row("Walk:") {
                        radioButton("Assets", IndexScope.ASSETS_ONLY)
                        radioButton("Assets and Packages", IndexScope.ASSETS_AND_PACKAGES)
                        radioButton("The whole project", IndexScope.WHOLE_PROJECT)
                    }
                }.bind(SettingProperty({ settings.indexScope }, { value -> settings.indexScope = value }), IndexScope::class.java)
            }
            group("Table") {
                row {
                    checkBox("Show padding rows").bindSelected(settings::showPaddingRows)
                }
                row {
                    checkBox("Mark auto-property backing fields").bindSelected(settings::markAutoProperties)
                }
                row(MemoryLayoutStyle.TABLE_FONT_LABEL) {
                    spinner(0..MemoryLayoutSettings.MAXIMUM_LABEL_FONT_SIZE)
                        .bindIntValue(MemoryLayoutViewState::tableFontSize)
                }.comment(MemoryLayoutStyle.TABLE_FONT_DESCRIPTION)
                row("Expand nested types to depth:") {
                    spinner(MINIMUM_DEPTH..MAXIMUM_DEPTH).bindIntValue(settings::automaticExpandDepth)
                }
            }
            group("Window") {
                row {
                    checkBox("Open each type in its own tab").bindSelected(settings::openEachTypeInItsOwnTab)
                }
                row {
                    checkBox("Focus the window when it opens").bindSelected(settings::focusWindowOnOpen)
                }
            }
        }
    }

    /**
     * The DSL's own property adapters are inline functions, and the platform's bytecode is built
     * for a newer JVM target than this plugin is -- inlining one fails the build. This does the
     * same job without being inlined.
     */
    private class SettingProperty<T>(
        private val getter: () -> T,
        private val setter: (T) -> Unit,
    ) : MutableProperty<T> {

        override fun get(): T {
            return getter()
        }

        override fun set(value: T) {
            setter(value)
        }
    }

    private val colorPanel = ColorPanel()

    private fun chosenRgb(): Int {
        val chosen = colorPanel.selectedColor ?: return MemoryLayoutSettings.NO_CUSTOM_BACKGROUND
        return chosen.rgb
    }

    private fun showStoredColour(settings: MemoryLayoutSettings) {
        if (settings.customBackgroundRgb == MemoryLayoutSettings.NO_CUSTOM_BACKGROUND) {
            colorPanel.selectedColor = null
            return
        }
        colorPanel.selectedColor = Color(settings.customBackgroundRgb, false)
    }

    private companion object {
        const val DISPLAY_NAME = "Memory Layout"

        const val MINIMUM_DEPTH = 0

        const val MAXIMUM_DEPTH = 8
    }
}
