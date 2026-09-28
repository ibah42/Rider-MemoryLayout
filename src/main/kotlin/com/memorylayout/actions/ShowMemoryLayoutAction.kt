package com.memorylayout.actions

import com.memorylayout.index.IndexedType
import com.memorylayout.index.ProjectTypeLookup
import com.memorylayout.index.RuntimeAssemblyService
import com.memorylayout.index.ClosureSource
import com.memorylayout.index.TypeCandidates
import com.memorylayout.layout.Lambdas
import com.memorylayout.layout.LayoutEngine
import com.memorylayout.layout.TypeLookup
import com.memorylayout.metadata.Closures
import com.memorylayout.ui.MemoryLayoutViewState
import com.memorylayout.layout.CodeMask
import com.memorylayout.layout.VariableType
import com.memorylayout.layout.VariableTypes
import com.memorylayout.metadata.CompositeTypeLookup
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
        event.presentation.isEnabledAndVisible = identifierUnderCaret(event) != null || isOnArrow(event)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val identifier = identifierUnderCaret(event) ?: ""
        val caretOffset = editor.caretModel.offset
        // Read on the UI thread, where the document may be read; everything else runs behind.
        val source = editor.document.immutableCharSequence.toString()
        val fileStamp = file?.timeStamp ?: 0L
        val index = TypeIndexService.getInstance(project)
        index.buildIfNeeded {
            val runtime = RuntimeAssemblyService.getInstance(project)
            val lookup = CompositeTypeLookup(listOf(ProjectTypeLookup(index), runtime.lookup()))
            val masked = CodeMask.of(source)
            if (Lambdas.isAt(masked, caretOffset)) {
                val closure = closureOf(project, masked, source, caretOffset, file?.url ?: "", fileStamp, lookup)
                ApplicationManager.getApplication().invokeLater {
                    present(project, editor, closure.first, closure.second)
                }
                return@buildIfNeeded
            }
            if (identifier.isEmpty()) {
                return@buildIfNeeded
            }
            val variable = VariableTypes.at(masked, source, caretOffset, file?.url ?: "", lookup)
            val candidates: List<IndexedType>
            val emptyMessage: String
            when (variable) {
                is VariableType.Declared -> {
                    candidates = TypeCandidates.of(project, variable.typeName, variable.context)
                    emptyMessage = "${variable.variableName} is a ${variable.typeName}, which is not in the " +
                        "sources or the referenced assemblies"
                }
                is VariableType.Unknown -> {
                    candidates = emptyList()
                    emptyMessage = variable.reason
                }
                null -> {
                    candidates = TypeCandidates.of(project, identifier, contextAt(index, file, caretOffset))
                    emptyMessage = "No type named $identifier in the sources or the referenced assemblies"
                }
            }
            ApplicationManager.getApplication().invokeLater {
                present(project, editor, emptyMessage, candidates)
            }
        }
    }

    /**
     * The closure of the lambda at the caret, as a message to show or an entry to open.
     *
     * Only the compiled assembly knows the closure class -- its fields, which lambdas share it --
     * so the lambda is read from the source to know what to look for, and the class is taken from
     * `Library/ScriptAssemblies`. A lambda with nothing to capture has no closure at all, and that
     * is said rather than shown.
     */
    private fun closureOf(
        project: Project,
        masked: String,
        source: String,
        caretOffset: Int,
        fileUrl: String,
        fileStamp: Long,
        lookup: TypeLookup,
    ): Pair<String, List<IndexedType>> {
        val site = Lambdas.at(masked, source, caretOffset, fileUrl, lookup)
            ?: return Pair("The caret is not on the => of a lambda or the delegate of an anonymous method", emptyList())
        val runtime = RuntimeAssemblyService.getInstance(project)
        val delegateCost = delegateCostNote(runtime)
        if (site.captured.isEmpty()) {
            if (site.usesInstance) {
                return Pair(
                    "This lambda captures only `this`: the compiler makes it a method of " +
                        "${site.containerQualifiedName} and allocates no closure. $delegateCost",
                    emptyList(),
                )
            }
            return Pair(
                "This lambda captures nothing: its delegate is created once, cached in a static field of " +
                    "the compiler's `<>c` class, and never allocated again.",
                emptyList(),
            )
        }
        val names = site.captured.map { variable -> variable.name }
        val compiled = runtime.findClosure(site.containerQualifiedName, site.memberName, names)
            ?: return Pair(
                "This lambda captures ${names.joinToString(", ")}, but Library/ScriptAssemblies has no closure " +
                    "class for ${site.containerQualifiedName}.${site.memberName}. Has Unity compiled this code?",
                emptyList(),
            )
        val offsets = HashMap<String, Int>()
        for (variable in site.captured) {
            offsets[variable.name] = variable.declarationOffset
        }
        offsets[Closures.THIS_FIELD_NAME] = site.containerNameOffset
        val closureSource = ClosureSource(
            fileUrl = fileUrl,
            lambdaOffset = site.start,
            title = "closure · ${site.memberName}",
            declarationOffsets = offsets,
            notes = closureNotes(site.memberName, compiled, fileStamp, delegateCost),
        )
        return Pair("", listOf(runtime.entryOfClosure(compiled, closureSource)))
    }

    private fun closureNotes(
        memberName: String,
        compiled: RuntimeAssemblyService.CompiledClosure,
        fileStamp: Long,
        delegateCost: String,
    ): List<String> {
        val match = compiled.match
        val assemblyName = match.type.assemblyName
        val notes = ArrayList<String>()
        notes.add(
            "Closure of a lambda in $memberName: the compiler's ${match.type.rawName}, read from $assemblyName " +
                "as Unity last compiled it"
        )
        if (compiled.assemblyModified in 1 until fileStamp) {
            notes.add("$assemblyName is older than this file: this is the last compiled version -- let Unity recompile")
        }
        notes.add(
            "Allocated when the scope declaring ${match.matchedNames.joinToString(", ")} is entered, not when " +
                "the lambda runs, and shared by every lambda of that scope"
        )
        if (match.missingNames.isNotEmpty()) {
            notes.add(
                "Not in this class: ${match.missingNames.joinToString(", ")} -- captured from an enclosing " +
                    "scope, reached through the outer closure field, or newer than the compiled assembly"
            )
        }
        notes.add(delegateCost)
        return notes
    }

    /** What one delegate object costs: `MulticastDelegate` as the runtime's own assembly declares it. */
    private fun delegateCostNote(runtime: RuntimeAssemblyService): String {
        val target = MemoryLayoutViewState.target
        val delegateType = runtime.lookup().resolve(DELEGATE_TYPE, LookupContext.EMPTY)
            ?: return "Turning it into a delegate allocates a delegate object as well."
        val layout = LayoutEngine(target, runtime.lookup()).layoutOf(delegateType)
        // The layout starts at the object header, so its size is the whole allocation.
        val allocation = layout.size
        return "Every time it is turned into a delegate, a delegate object is allocated too: $allocation B on ${target.name}."
    }

    /** Cheap enough for [update]: the caret beside `=>` or on `delegate`, checked without parsing. */
    private fun isOnArrow(event: AnActionEvent): Boolean {
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return false
        val text = editor.document.immutableCharSequence
        val offset = editor.caretModel.offset
        for (candidate in (offset - ARROW_LENGTH)..offset) {
            if (candidate >= 0 && candidate + ARROW_LENGTH <= text.length && text.subSequence(candidate, candidate + ARROW_LENGTH).toString() == "=>") {
                return true
            }
        }
        return false
    }

    private fun present(project: Project, editor: Editor, emptyMessage: String, candidates: List<IndexedType>) {
        if (candidates.isEmpty()) {
            JBPopupFactory.getInstance()
                .createMessage(emptyMessage)
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

    companion object {
        private const val DELEGATE_TYPE = "System.MulticastDelegate"

        private const val ARROW_LENGTH = 2
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
