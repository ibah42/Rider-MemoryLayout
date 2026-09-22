package com.memorylayout.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/** The caret-to-name reading the action depends on; there is no PSI to ask for it. */
class SourceTextTest {

    @Test
    fun readsTheIdentifierTheCaretSitsIn() {
        val text = "public Vertex vertex;"
        assertEquals("Vertex", SourceText.identifierAround(text, 7))
        assertEquals("Vertex", SourceText.identifierAround(text, 10))
    }

    @Test
    fun readsTheIdentifierTheCaretSitsRightAfter() {
        val text = "public Vertex vertex;"
        assertEquals("Vertex", SourceText.identifierAround(text, 13))
    }

    @Test
    fun readsNothingFromWhitespaceOrPunctuation() {
        val text = "a + b;"
        assertEquals("", SourceText.identifierAround(text, 2))
        assertEquals("", SourceText.identifierAround(text, 6))
    }

    @Test
    fun aNumberIsNotAnIdentifier() {
        assertEquals("", SourceText.identifierAround("size = 128;", 8))
    }

    @Test
    fun readsAQualifiedNamesLastSegment() {
        val text = "Game.Data.Chunk chunk;"
        assertEquals("Chunk", SourceText.identifierAround(text, 11))
        assertEquals("Data", SourceText.identifierAround(text, 6))
    }
}
