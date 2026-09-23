package com.memorylayout.layout

/**
 * The plumbing every layout test needs: mask the source, resolve names inside it, lay one type out.
 *
 * Kept in its own file so a test reads as the C# it is about plus the assertion, and nothing else.
 */
object LayoutTestSupport {

    const val TEST_FILE_ID = "Test.cs"

    fun layoutOf(source: String, typeName: String, target: LayoutTarget = LayoutTarget.X64): TypeLayout {
        val masked = CodeMask.of(source)
        val lookup = SourceTypeLookup(masked, TEST_FILE_ID)
        val declaration = lookup.allDeclarations().firstOrNull { candidate -> candidate.name == typeName }
            ?: throw AssertionError("no type named $typeName in the test source")
        return LayoutEngine(target, lookup).layoutOf(lookup.declaredTypeOf(declaration))
    }

    /** The node of the field with this name, at any depth. */
    fun field(layout: TypeLayout, fieldName: String): LayoutNode {
        return findField(layout.nodes, fieldName)
            ?: throw AssertionError("no field named $fieldName in ${layout.displayName}")
    }

    private fun findField(nodes: List<LayoutNode>, fieldName: String): LayoutNode? {
        for (node in nodes) {
            if (node.kind != NodeKind.PADDING && node.fieldName == fieldName) {
                return node
            }
            val inChildren = findField(node.children, fieldName)
            if (inChildren != null) {
                return inChildren
            }
        }
        return null
    }

    /** Every padding run of the top level, as `offset:size` pairs, in order. */
    fun topLevelPadding(layout: TypeLayout): List<Pair<Int, Int>> {
        return layout.nodes
            .filter { node -> node.kind == NodeKind.PADDING }
            .map { node -> Pair(node.offset, node.size) }
    }

    /** The top-level field names in layout order, padding excluded. */
    fun fieldNames(layout: TypeLayout): List<String> {
        return layout.nodes
            .filter { node -> node.kind != NodeKind.PADDING }
            .map { node -> node.fieldName }
    }
}
