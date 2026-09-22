package com.memorylayout.actions

import com.memorylayout.index.IndexedType
import com.memorylayout.index.ProjectTypeLookup
import com.memorylayout.index.TypeIndexService
import com.memorylayout.layout.LookupContext
import com.memorylayout.layout.SourceText
import com.memorylayout.layout.TypeScanner
import com.memorylayout.ui.MemoryLayoutStyle
import com.memorylayout.ui.MemoryLayoutToolWindowService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile

/**
 * The way into the window: the type the caret sits on.
 *
 * The caret rather than the mouse even when this runs from the context menu, because the editor
 * moves the caret to the click before showing the menu. There is no PSI to ask, so the identifier
 * is read straight out of the document -- which is also why this works in a file Rider has not
 * finished analysing.
 */
class ShowMemoryLayoutAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = identifierUnderCaret(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val typeName = identifierUnderCaret(event) ?: return
        val caretOffset = editor.caretModel.offset
        val index = TypeIndexService.getInstance(project)
        index.buildIfNeeded {
            val context = contextAt(index, file, caretOffset)
            val candidates = ProjectTypeLookup(index).rankedCandidates(typeName, context)
            ApplicationManager.getApplication().invokeLater {
                present(project, editor, typeName, candidates)
            }
        }
    }

    private fun present(project: Project, editor: Editor, typeName: String, candidates: List<IndexedType>) {
        if (candidates.isEmpty()) {
            JBPopupFactory.getInstance()
                .createMessage("No type named $typeName in the index")
                .showInBestPositionFor(editor)
            return
        }
        if (candidates.size == 1) {
            MemoryLayoutToolWindowService.getInstance(project).open(candidates.first())
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(candidates)
            .setTitle(MemoryLayoutStyle.AMBIGUOUS_TITLE)
            .setRenderer(CandidateRenderer())
            .setItemChosenCallback { chosen ->
                MemoryLayoutToolWindowService.getInstance(project).open(chosen)
            }
            .createPopup()
            .showInBestPositionFor(editor)
    }

    /**
     * Where the caret is, in namespace terms: it decides which `Chunk` is meant when two
     * namespaces declare one.
     */
    private fun contextAt(index: TypeIndexService, file: VirtualFile?, caretOffset: Int): LookupContext {
        if (file == null) {
            return LookupContext.EMPTY
        }
        val masked = index.maskedSourceOf(file) ?: return LookupContext.EMPTY
        val enclosing = TypeScanner.declarationAt(TypeScanner.scan(masked), caretOffset)
            ?: return LookupContext(namespaceName = "", containerNames = emptyList(), fileId = file.url)
        return LookupContext(
            namespaceName = enclosing.namespaceName,
            containerNames = enclosing.containerNames + enclosing.name,
            fileId = file.url,
        )
    }

    private fun identifierUnderCaret(event: AnActionEvent): String? {
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
        val text = editor.document.immutableCharSequence.toString()
        val identifier = SourceText.identifierAround(text, editor.caretModel.offset)
        if (identifier.isEmpty()) {
            return null
        }
        return identifier
    }

    private class CandidateRenderer : javax.swing.DefaultListCellRenderer() {

        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            hasFocus: Boolean,
        ): java.awt.Component {
            val entry = value as? IndexedType
            val text: String
            if (entry == null) {
                text = value?.toString() ?: ""
            } else {
                text = entry.qualifiedName + "   " + entry.filePresentableName
            }
            return super.getListCellRendererComponent(list, text, index, isSelected, hasFocus)
        }
    }
}
