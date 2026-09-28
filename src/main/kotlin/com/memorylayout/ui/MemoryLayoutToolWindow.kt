package com.memorylayout.ui

import com.memorylayout.index.IndexedType
import com.memorylayout.settings.MemoryLayoutSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/** What the window shows before anything has been asked of it. */
class EmptyStatePanel : JPanel(BorderLayout()) {

    init {
        val label = JBLabel(MemoryLayoutStyle.EMPTY_STATE_TEXT, SwingConstants.CENTER)
        label.border = JBUI.Borders.empty(EMPTY_STATE_PADDING)
        label.isEnabled = false
        add(label, BorderLayout.CENTER)
    }

    private companion object {
        const val EMPTY_STATE_PADDING = 12
    }
}

/** Creates the window with its placeholder; the real tabs arrive when a type is asked about. */
class MemoryLayoutToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        // The window is filled lazily, on first show -- and the first show is usually the one
        // `open` asks for after it has already added the type's tab. A placeholder added now would
        // sit next to that tab as a second, empty "Memory Layout".
        if (contentManager.contentCount == 0) {
            contentManager.addContent(createPlaceholder())
        }
        contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (contentManager.contentCount == 0) {
                    contentManager.addContent(createPlaceholder())
                }
            }
        })
    }

    private fun createPlaceholder(): Content {
        val content = ContentFactory.getInstance()
            .createContent(EmptyStatePanel(), MemoryLayoutStyle.TOOL_WINDOW_TITLE, false)
        content.isCloseable = false
        return content
    }
}

/** Opens and reuses the tabs. One tab is one type. */
@Service(Service.Level.PROJECT)
class MemoryLayoutToolWindowService(private val project: Project) {

    fun open(entry: IndexedType) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        val contentManager = toolWindow.contentManager
        val settings = MemoryLayoutSettings.getInstance()
        val existing = findTabOf(contentManager, entry)
        if (existing != null) {
            (existing.component as? MemoryLayoutPanel)?.recompute()
            contentManager.setSelectedContent(existing)
            reveal(toolWindow, settings.focusWindowOnOpen)
            return
        }
        val panel = MemoryLayoutPanel(project, entry)
        val content = ContentFactory.getInstance().createContent(panel, entry.displayName, false)
        content.isCloseable = true
        content.setDisposer(panel)
        content.description = entry.qualifiedName
        if (!settings.openEachTypeInItsOwnTab) {
            replaceSelected(contentManager)
        }
        removePlaceholder(contentManager)
        contentManager.addContent(content)
        contentManager.setSelectedContent(content)
        reveal(toolWindow, settings.focusWindowOnOpen)
    }

    private fun reveal(toolWindow: ToolWindow, focus: Boolean) {
        if (focus) {
            toolWindow.activate(null)
            return
        }
        toolWindow.show(null)
    }

    private fun findTabOf(contentManager: ContentManager, entry: IndexedType): Content? {
        return contentManager.contents.firstOrNull { content ->
            (content.component as? MemoryLayoutPanel)?.tabKey == entry.tabKey
        }
    }

    private fun replaceSelected(contentManager: ContentManager) {
        val selected = contentManager.selectedContent ?: return
        if (selected.component !is MemoryLayoutPanel) {
            return
        }
        contentManager.removeContent(selected, true)
    }

    private fun removePlaceholder(contentManager: ContentManager) {
        val placeholder = contentManager.contents.firstOrNull { content -> content.component is EmptyStatePanel }
            ?: return
        contentManager.removeContent(placeholder, true)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Memory Layout"

        fun getInstance(project: Project): MemoryLayoutToolWindowService {
            return project.service()
        }
    }
}
