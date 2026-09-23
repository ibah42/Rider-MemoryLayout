package com.memorylayout.metadata

import com.memorylayout.layout.LayoutEngine
import com.memorylayout.layout.LayoutTarget
import com.memorylayout.layout.LookupContext
import com.memorylayout.metadata.MetadataFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Reads a real runtime `mscorlib.dll`.
 *
 * The assembly is Unity's and cannot live in this repository, so the test takes its path from
 * `MEMORY_LAYOUT_MSCORLIB` -- `<Editor>/Data/MonoBleedingEdge/lib/mono/unityjit-win32/mscorlib.dll`.
 * Without it the reader tests do nothing, and they say so on the console rather than pretending.
 */
class MetadataReaderTest {

    private fun runtime(): MetadataTypeLookup? {
        val path = System.getenv(ASSEMBLY_VARIABLE)
        if (path.isNullOrEmpty()) {
            println("MetadataReaderTest: $ASSEMBLY_VARIABLE is not set, nothing checked")
            return null
        }
        return MetadataTypeLookup(MetadataReader.read(File(path).readBytes(), "mscorlib.dll"))
    }

    private fun fieldsOf(lookup: MetadataTypeLookup, typeName: String): List<String> {
        val type = lookup.rankedCandidates(typeName, LookupContext.EMPTY).first()
        return type.fields.map { field -> field.typeName + " " + field.name }
    }

    @Test
    fun readsTheFieldsOfTheFrameworkTypes() {
        val lookup = runtime() ?: return
        assertEquals(listOf("int _stringLength", "char _firstChar"), fieldsOf(lookup, "System.String"))
        assertEquals(
            listOf("T[] _items", "int _size", "int _version", "object _syncRoot"),
            fieldsOf(lookup, "System.Collections.Generic.List<T>"),
        )
        assertEquals(listOf("ulong _dateData"), fieldsOf(lookup, "System.DateTime"))
        assertEquals(listOf("bool hasValue", "T value"), fieldsOf(lookup, "System.Nullable<T>"))
    }

    @Test
    fun laysOutTheFrameworkTypes() {
        val lookup = runtime() ?: return
        val engine = LayoutEngine(LayoutTarget.X64, lookup)
        assertEquals(16, engine.layoutOf(lookup.resolve("System.Guid", LookupContext.EMPTY)!!).size)
        assertEquals(16, engine.layoutOf(lookup.resolve("decimal", LookupContext.EMPTY)!!).size)
        assertEquals(8, engine.layoutOf(lookup.resolve("System.DateTime", LookupContext.EMPTY)!!).size)
        val dictionary = engine.layoutOf(lookup.resolve("System.Collections.Generic.Dictionary<TKey, TValue>", LookupContext.EMPTY)!!)
        assertTrue(dictionary.nodes.any { node -> node.fieldName == "_buckets" })
    }

    @Test
    fun refusesAFileThatIsNotAnAssembly() {
        try {
            MetadataReader.read(ByteArray(NOT_AN_ASSEMBLY_SIZE), "empty.dll")
            fail("an empty file was read as an assembly")
        } catch (expected: MetadataFormatException) {
            assertTrue(expected.message!!.isNotEmpty())
        }
    }

    companion object {
        private const val ASSEMBLY_VARIABLE = "MEMORY_LAYOUT_MSCORLIB"

        private const val NOT_AN_ASSEMBLY_SIZE = 256
    }
}
