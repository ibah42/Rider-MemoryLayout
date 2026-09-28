package com.memorylayout.ui

import com.memorylayout.index.ClosureSource
import com.memorylayout.index.IndexedType
import com.memorylayout.metadata.Closures
import com.memorylayout.index.ProjectTypeLookup
import com.memorylayout.index.RuntimeAssemblyService
import com.memorylayout.layout.DeclaredType
import com.memorylayout.metadata.CompositeTypeLookup
import com.memorylayout.index.TypeIndexService
import com.memorylayout.layout.LayoutEngine
import com.memorylayout.layout.LayoutNode
import com.memorylayout.layout.LayoutTarget
import com.memorylayout.layout.NodeKind
import com.memorylayout.layout.RepeatTail
import com.memorylayout.layout.TypeLayout
import com.intellij.openapi.project.Project

/** Puts the index, the lookup and the engine together. Runs off the UI thread. */
object LayoutComputer {

    /**
     * @param elementCount the reader's `n` for a `string` or an array, or
     *   [RepeatTail.UNKNOWN_COUNT] to show the sizes as formulas
     */
    fun compute(
        project: Project,
        entry: IndexedType,
        target: LayoutTarget,
        elementCount: Int = RepeatTail.UNKNOWN_COUNT,
    ): TypeLayout? {
        val index = TypeIndexService.getInstance(project)
        val runtime = RuntimeAssemblyService.getInstance(project)
        val arrayElement = entry.arrayElement
        if (arrayElement != null) {
            // No declaration to read: the runtime defines an array, the element is resolved.
            val lookup = CompositeTypeLookup(listOf(ProjectTypeLookup(index), runtime.lookup()))
            return LayoutEngine(target, lookup).layoutOfArray(arrayElement.typeName, arrayElement.context, elementCount)
        }
        val declared: DeclaredType?
        if (runtime.isRuntimeEntry(entry)) {
            declared = runtime.declaredTypeOf(entry)
        } else {
            declared = index.declaredTypeOf(entry)
        }
        if (declared == null) {
            return null
        }
        // The project answers first: its own `Entry` is the one its code means. The assemblies
        // answer for everything it does not declare -- `Guid`, `List<T>`, `Vector3`.
        val lookups = arrayListOf(ProjectTypeLookup(index), runtime.lookup())
        val closure = entry.closure
        if (closure != null) {
            // A closure may point at the enclosing scope's closure, another compiler-made class.
            lookups.add(runtime.scriptAssemblyLookup())
        }
        val layout = LayoutEngine(target, CompositeTypeLookup(lookups)).layoutOf(declared, entry.typeArguments, elementCount)
        if (closure == null) {
            return layout
        }
        return asClosure(layout, closure)
    }

    /**
     * The closure class dressed as the lambda's: fields named as the reader wrote the variables,
     * each pointing at its declaration, and the notes about when it is allocated first.
     */
    private fun asClosure(layout: TypeLayout, closure: ClosureSource): TypeLayout {
        val nodes = layout.nodes.map { node ->
            if (node.kind == NodeKind.PADDING || node.kind == NodeKind.RUNTIME) {
                node
            } else {
                val offset = closure.declarationOffsets[node.fieldName]
                if (offset == null) {
                    node.copy(fieldName = Closures.labelOf(node.fieldName))
                } else {
                    node.copy(fieldName = Closures.labelOf(node.fieldName), fileId = closure.fileUrl, declarationOffset = offset)
                }
            }
        }
        val notes = closure.notes + layout.notes.filterNot { note -> note.startsWith(METADATA_NOTE_PREFIX) }
        return layout.copy(displayName = closure.title, nodes = nodes, notes = notes)
    }

    /** The layout as plain text, for the clipboard. */
    fun asText(layout: TypeLayout): String {
        val builder = StringBuilder()
        builder.append(MemoryLayoutStyle.headerText(layout)).append('\n')
        builder.append(
            format(
                MemoryLayoutStyle.COLUMN_HEX,
                MemoryLayoutStyle.COLUMN_DECIMAL,
                MemoryLayoutStyle.COLUMN_SIZE,
                MemoryLayoutStyle.COLUMN_ALIGNMENT,
                MemoryLayoutStyle.COLUMN_TYPE,
                MemoryLayoutStyle.COLUMN_NAME,
                0,
            )
        )
        appendNodes(builder, layout.nodes, 0)
        for (note in layout.notes) {
            builder.append("note: ").append(note).append('\n')
        }
        return builder.toString()
    }

    private fun appendNodes(builder: StringBuilder, nodes: List<LayoutNode>, depth: Int) {
        for (node in nodes) {
            val name: String
            if (node.kind == NodeKind.PADDING) {
                builder.append(
                    format(
                        MemoryLayoutStyle.hexOffset(node.offset),
                        node.offset.toString(),
                        node.size.toString(),
                        "",
                        MemoryLayoutStyle.PADDING_ROW_NAME,
                        "",
                        depth,
                    )
                )
                continue
            }
            name = node.fieldName
            builder.append(
                format(
                    MemoryLayoutStyle.hexOffset(node.offset),
                    node.offset.toString(),
                    node.size.toString(),
                    node.alignment.toString(),
                    node.typeName,
                    name,
                    depth,
                )
            )
            appendNodes(builder, node.children, depth + 1)
        }
    }

    private fun format(
        hex: String,
        decimal: String,
        size: String,
        alignment: String,
        typeName: String,
        name: String,
        depth: Int,
    ): String {
        val indent = "  ".repeat(depth)
        return hex.padEnd(HEX_WIDTH) +
            decimal.padStart(NUMBER_WIDTH) +
            size.padStart(NUMBER_WIDTH) +
            alignment.padStart(NUMBER_WIDTH) +
            "  " + (indent + typeName).padEnd(TYPE_WIDTH) +
            name + "\n"
    }

    /** The metadata lookup's own note; a closure says where it came from more precisely. */
    private const val METADATA_NOTE_PREFIX = "Read from the metadata of"

    private const val HEX_WIDTH = 8

    private const val NUMBER_WIDTH = 6

    private const val TYPE_WIDTH = 28
}
