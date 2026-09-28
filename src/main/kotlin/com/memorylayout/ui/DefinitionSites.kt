package com.memorylayout.ui

import com.memorylayout.index.IndexedType
import com.memorylayout.index.RuntimeAssemblyService
import com.memorylayout.index.TypeIndexService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.awt.RelativePoint
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

/**
 * One place a tab's type is written down: a file, the offset of the declaration, and the line a
 * reader would call it by.
 *
 * @param presentableText `WorldChunk.cs:14`, what the summary row and the chooser show
 */
data class DefinitionSite(
    val fileUrl: String,
    val offset: Int,
    val presentableText: String,
)

/**
 * Where the type of a tab is declared, and how to get there.
 *
 * Most types have one place. A partial type has one per file, and a click has to ask which of
 * them is meant rather than pick one -- the part a reader is after is usually the one holding the
 * fields they are staring at, and only they know which that is. A closure has the lambda it was
 * compiled from. A type read from an assembly has no source at all, and says which assembly.
 */
object DefinitionSites {

    /** Every declaration of the tab's type, partial parts in file order. Reads documents: not on the UI thread. */
    fun of(project: Project, entry: IndexedType): List<DefinitionSite> {
        return ReadAction.compute<List<DefinitionSite>, RuntimeException> {
            collect(project, entry)
        }
    }

    private fun collect(project: Project, entry: IndexedType): List<DefinitionSite> {
        val closure = entry.closure
        if (closure != null) {
            return listOfNotNull(siteOf(closure.fileUrl, closure.lambdaOffset))
        }
        if (RuntimeAssemblyService.getInstance(project).isRuntimeEntry(entry)) {
            return emptyList()
        }
        if (!entry.isPartial) {
            return listOfNotNull(siteOf(entry.fileUrl, entry.declarationOffset))
        }
        val parts = TypeIndexService.getInstance(project).candidates(entry.simpleName)
            .filter { candidate -> candidate == entry || candidate.isPartOfSameType(entry) }
            .sortedWith(compareBy({ candidate -> candidate.fileUrl }, { candidate -> candidate.declarationOffset }))
        val sites = ArrayList<DefinitionSite>()
        for (part in parts) {
            val site = siteOf(part.fileUrl, part.declarationOffset) ?: continue
            val alreadyListed = sites.any { listed -> listed.fileUrl == site.fileUrl && listed.offset == site.offset }
            if (!alreadyListed) {
                sites.add(site)
            }
        }
        if (sites.isEmpty()) {
            return listOfNotNull(siteOf(entry.fileUrl, entry.declarationOffset))
        }
        return sites
    }

    private fun siteOf(fileUrl: String, offset: Int): DefinitionSite? {
        if (fileUrl.isEmpty() || offset < 0) {
            return null
        }
        val file = VirtualFileManager.getInstance().findFileByUrl(fileUrl) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (document == null || offset > document.textLength) {
            return DefinitionSite(fileUrl, offset, file.name)
        }
        val line = document.getLineNumber(offset) + 1
        return DefinitionSite(fileUrl, offset, file.name + ":" + line)
    }

    /**
     * What the summary row says about where the type lives: `WorldChunk.cs:14`, the first part
     * and how many more for a partial type, the assembly for a type with no source.
     */
    fun locationText(sites: List<DefinitionSite>, entry: IndexedType): String {
        if (sites.isEmpty()) {
            return entry.filePresentableName
        }
        if (sites.size == 1) {
            return sites.first().presentableText
        }
        return sites.first().presentableText + " +" + (sites.size - 1)
    }

    /** One site: go there. Several: ask which, right where the click was. */
    fun navigate(project: Project, sites: List<DefinitionSite>, event: MouseEvent, requestFocus: Boolean) {
        if (sites.isEmpty()) {
            return
        }
        if (sites.size == 1) {
            open(project, sites.first(), requestFocus)
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(sites)
            .setTitle(MemoryLayoutStyle.PARTIAL_CHOOSER_TITLE)
            .setRenderer(SiteRenderer())
            .setItemChosenCallback { chosen ->
                open(project, chosen, true)
            }
            .createPopup()
            .show(RelativePoint(event))
    }

    private fun open(project: Project, site: DefinitionSite, requestFocus: Boolean) {
        val file = VirtualFileManager.getInstance().findFileByUrl(site.fileUrl) ?: return
        OpenFileDescriptor(project, file, site.offset).navigate(requestFocus)
    }

    private class SiteRenderer : DefaultListCellRenderer() {

        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            hasFocus: Boolean,
        ): Component {
            val site = value as? DefinitionSite
            val text: String
            if (site == null) {
                text = value?.toString() ?: ""
            } else {
                text = site.presentableText
            }
            return super.getListCellRendererComponent(list, text, index, isSelected, hasFocus)
        }
    }
}
