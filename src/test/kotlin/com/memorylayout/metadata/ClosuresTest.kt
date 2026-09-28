package com.memorylayout.metadata

import com.memorylayout.layout.DeclaredLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClosuresTest {

    private fun closure(rawName: String, containerNames: List<String>, fields: List<String>, methods: List<String>): MetadataType {
        return MetadataType(
            assemblyName = "Assembly-CSharp.dll",
            namespaceName = "Game",
            name = Identifiers.sanitize(rawName),
            containerNames = containerNames,
            genericParameters = emptyList(),
            kind = MetadataTypeKind.CLASS,
            isPublic = false,
            baseTypeName = "",
            layoutKind = DeclaredLayoutKind.AUTO,
            pack = MetadataType.UNSET,
            classSize = MetadataType.UNSET,
            fields = fields.map { name -> MetadataField(Identifiers.sanitize(name), fieldTypeOf(name)) },
            rawName = rawName,
            compilerMethodNames = methods,
        )
    }

    /** The outer closure field points at the enclosing scope's class, as the compiler types it. */
    private fun fieldTypeOf(name: String): String {
        if (name.startsWith("CS$<>8__locals")) {
            return "Game.Player." + Identifiers.sanitize("<>c__DisplayClass6_0")
        }
        return "int"
    }

    private val types = listOf(
        closure("<>c__DisplayClass5_0", listOf("Player"), listOf("factor", "<>4__this"), listOf(".ctor", "<Update>b__0")),
        closure("<>c__DisplayClass6_0", listOf("Player"), listOf("factor"), listOf(".ctor", "<Start>b__0")),
        closure("<>c__DisplayClass6_1", listOf("Player"), listOf("inner", "CS$<>8__locals1"), listOf(".ctor", "<Start>b__1")),
        closure("<>c__DisplayClass0_0", listOf("Enemy"), listOf("factor"), listOf(".ctor", "<Update>b__0")),
    )

    @Test
    fun findsTheClosureOfTheRightMember() {
        assertEquals("<>c__DisplayClass5_0", Closures.find(types, "Game.Player", "Update", listOf("factor"))!!.type.rawName)
        assertEquals("<>c__DisplayClass6_0", Closures.find(types, "Game.Player", "Start", listOf("factor"))!!.type.rawName)
        assertEquals("<>c__DisplayClass0_0", Closures.find(types, "Game.Enemy", "Update", listOf("factor"))!!.type.rawName)
    }

    @Test
    fun prefersTheScopeThatHoldsTheCaptures() {
        val match = Closures.find(types, "Game.Player", "Start", listOf("inner", "factor"))!!
        assertEquals("<>c__DisplayClass6_1", match.type.rawName)
        assertEquals(listOf("inner"), match.matchedNames)
        // `factor` is reached through the outer closure field, so nothing is missing.
        assertEquals(emptyList<String>(), match.missingNames)
    }

    @Test
    fun findsNothingForAnotherType() {
        assertNull(Closures.find(types, "Game.Boss", "Update", listOf("factor")))
    }

    @Test
    fun labelsTheCompilersFields() {
        assertEquals("this", Closures.labelOf(Identifiers.sanitize("<>4__this")))
        assertEquals("outer closure", Closures.labelOf(Identifiers.sanitize("CS$<>8__locals1")))
        assertEquals("factor", Closures.labelOf("factor"))
    }
}
