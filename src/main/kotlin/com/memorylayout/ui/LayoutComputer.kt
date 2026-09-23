package com.memorylayout.ui

import com.memorylayout.index.IndexedType
import com.memorylayout.index.ProjectTypeLookup
import com.memorylayout.index.RuntimeAssemblyService
import com.memorylayout.layout.DeclaredType
import com.memorylayout.metadata.CompositeTypeLookup
import com.memorylayout.index.TypeIndexService
import com.memorylayout.layout.LayoutEngine
import com.memorylayout.layout.LayoutNode
import com.memorylayout.layout.LayoutTarget
import com.memorylayout.layout.NodeKind
import com.memorylayout.layout.TypeLayout
import com.intellij.openapi.project.Project

/** Puts the index, the lookup and the engine together. Runs off the UI thread. */
object LayoutComputer {

    fun compute(project: Project, entry: IndexedType, target: LayoutTarget): TypeLayout? {
        val index = TypeIndexService.getInstance(project)
        val runtime = RuntimeAssemblyService.getInstance(project)
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
        val lookup = CompositeTypeLookup(listOf(ProjectTypeLookup(index), runtime.lookup()))
        return LayoutEngine(target, lookup).layoutOf(declared, entry.typeArguments)
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

    private const val HEX_WIDTH = 8

    private const val NUMBER_WIDTH = 6

    private const val TYPE_WIDTH = 28
}
