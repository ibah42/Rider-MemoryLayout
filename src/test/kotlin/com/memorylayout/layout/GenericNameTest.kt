package com.memorylayout.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class GenericNameTest {

    @Test
    fun countsTheArgumentsOfAWrittenName() {
        assertEquals(0, GenericName.arityOf("Chunk"))
        assertEquals(1, GenericName.arityOf("Box<int>"))
        assertEquals(2, GenericName.arityOf("Dictionary<int, string>"))
    }

    @Test
    fun onlyTheCommasOutsideEveryAngleBracketSeparateArguments() {
        assertEquals(
            listOf("int", "List<int, float>"),
            GenericName.argumentsOf("Dictionary<int, List<int, float>>"),
        )
        assertEquals(1, GenericName.arityOf("Box<Pair<int, byte>>"))
    }

    @Test
    fun replacesWholeIdentifiersAndLeavesTheRestAlone() {
        val bound = mapOf("T" to "int")
        assertEquals("int", GenericName.substitute("T", bound))
        assertEquals("int[]", GenericName.substitute("T[]", bound))
        assertEquals("Box<int>", GenericName.substitute("Box<T>", bound))
        assertEquals("int?", GenericName.substitute("T?", bound))
        // Transform starts with T and is not it.
        assertEquals("Transform", GenericName.substitute("Transform", bound))
    }

    @Test
    fun anUnboundParameterIsLeftAsWritten() {
        assertEquals("U", GenericName.substitute("U", mapOf("T" to "int")))
    }

    @Test
    fun bindsParametersToArgumentsInOrder() {
        assertEquals(
            mapOf("TFirst" to "int", "TSecond" to "byte"),
            GenericName.bind(listOf("TFirst", "TSecond"), listOf("int", "byte")),
        )
    }
}
