package com.memorylayout.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class NameOffsetTest {

    private fun offsetOf(source: String, name: String): Int {
        val masked = CodeMask.of(source)
        val statement = SourceText.statementsIn(masked, 0, masked.length).first()
        return SourceText.nameOffsetInStatement(masked, statement.start, name)
    }

    @Test
    fun landsOnTheNameNotTheModifiers() {
        val source = "[FieldOffset(4)] public readonly int value;"
        assertEquals(source.indexOf("value"), offsetOf(source, "value"))
    }

    @Test
    fun takesTheNameNotATypeOfTheSameName() {
        val source = "public Vector3 Vector3;"
        assertEquals(source.lastIndexOf("Vector3"), offsetOf(source, "Vector3"))
    }

    @Test
    fun findsTheSecondDeclarator() {
        val source = "public int first, second;"
        assertEquals(source.indexOf("second"), offsetOf(source, "second"))
    }

    @Test
    fun stopsAtTheInitializer() {
        val source = "private Foo target = new Foo(target);"
        assertEquals(source.indexOf("target"), offsetOf(source, "target"))
    }

    @Test
    fun findsAnAutoPropertyAndAPositionalParameter() {
        val property = "public int Count { get; set; }"
        assertEquals(property.indexOf("Count"), offsetOf(property, "Count"))
        val record = "public record struct Point(int X, int Y);"
        assertEquals(record.indexOf("Y"), offsetOf(record, "Y"))
    }

    @Test
    fun ignoresTheNameInAComment() {
        val source = "/* value */ int value;"
        assertEquals(source.lastIndexOf("value"), offsetOf(source, "value"))
    }

    @Test
    fun fallsBackToTheStatementWhenTheNameIsMissing() {
        val source = "int value;"
        assertEquals(0, offsetOf(source, "other"))
    }
}
