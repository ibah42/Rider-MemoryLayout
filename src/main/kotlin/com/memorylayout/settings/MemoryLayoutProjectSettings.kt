package com.memorylayout.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * What the reader set for this project rather than for every project: the column widths.
 *
 * Kept in the workspace file, next to the window layout and the open editors, because a column
 * width is a habit of looking at one codebase -- its type names are long or short -- and not a
 * fact about the code worth committing.
 */
@Service(Service.Level.PROJECT)
@State(name = "MemoryLayoutProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class MemoryLayoutProjectSettings : PersistentStateComponent<MemoryLayoutProjectSettings.Config> {

    class Config {
        /**
         * As `hex=40;dec=36;size=30;align=34;type=180`. The name column is never stored.
         *
         * The 3 drops what earlier versions held: widths stored while the table's own layout could
         * still put Swing's default of 75 back, so mostly 75s nobody chose.
         */
        var columnWidths4: String = ""
    }

    private var config = Config()

    override fun getState(): Config {
        return config
    }

    override fun loadState(state: Config) {
        config = state
    }

    var columnWidths: String
        get() = config.columnWidths4
        set(value) {
            config.columnWidths4 = value
        }

    companion object {
        fun getInstance(project: Project): MemoryLayoutProjectSettings {
            return project.service()
        }
    }
}
