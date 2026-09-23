package com.memorylayout.metadata

/**
 * The table stream (II.24.2.6): row counts and a way to read any column of any row.
 *
 * The width of every column depends on the file -- an index into a heap or a table is two bytes
 * when it fits and four when it does not -- so every table has to be measured, even the dozens a
 * layout never reads, just to find where the next one starts.
 */
class MetadataTables(private val image: MetadataImage, streamOffset: Int) {

    /** One column: how wide it is, decided once the row counts are known. */
    private sealed class Column {
        class Fixed(val size: Int) : Column()
        object StringIndex : Column()
        object GuidIndex : Column()
        object BlobIndex : Column()
        class TableIndex(val table: Int) : Column()
        class CodedIndex(val tables: List<Int>, val tagBits: Int) : Column()
    }

    private val rowCounts = IntArray(TABLE_COUNT)

    private val tableOffsets = IntArray(TABLE_COUNT)

    private val rowSizes = IntArray(TABLE_COUNT)

    private val columnOffsets = arrayOfNulls<IntArray>(TABLE_COUNT)

    private val columnWidths = arrayOfNulls<IntArray>(TABLE_COUNT)

    private val heapSizes: Int

    init {
        heapSizes = image.byteAt(streamOffset + HEAP_SIZES_OFFSET)
        val validMask = image.longAt(streamOffset + VALID_MASK_OFFSET)
        var position = streamOffset + ROW_COUNTS_OFFSET
        for (table in 0 until TABLE_COUNT) {
            if ((validMask ushr table) and 1L == 1L) {
                rowCounts[table] = image.intAt(position)
                position += Int.SIZE_BYTES
            }
        }
        if ((validMask ushr TABLE_COUNT) != 0L) {
            // Tables past GenericParamConstraint belong to portable PDBs, never to an assembly.
            throw MetadataFormatException("unknown metadata tables")
        }
        if (heapSizes and EXTRA_DATA_FLAG != 0) {
            position += Int.SIZE_BYTES
        }
        for (table in 0 until TABLE_COUNT) {
            val columns = SCHEMA[table]
            val widths = IntArray(columns.size)
            val offsets = IntArray(columns.size)
            var rowSize = 0
            for (columnIndex in columns.indices) {
                offsets[columnIndex] = rowSize
                widths[columnIndex] = widthOf(columns[columnIndex])
                rowSize += widths[columnIndex]
            }
            columnWidths[table] = widths
            columnOffsets[table] = offsets
            rowSizes[table] = rowSize
            tableOffsets[table] = position
            position += rowSize * rowCounts[table]
        }
    }

    fun rowCount(table: Int): Int {
        return rowCounts[table]
    }

    /** A column of a row, rows counted from 1 as every index in the metadata counts them. */
    fun value(table: Int, row: Int, column: Int): Int {
        val offset = tableOffsets[table] + (row - 1) * rowSizes[table] + columnOffsets[table]!![column]
        if (columnWidths[table]!![column] == SHORT_WIDTH) {
            return image.unsignedShortAt(offset)
        }
        return image.intAt(offset)
    }

    private fun widthOf(column: Column): Int {
        return when (column) {
            is Column.Fixed -> {
                column.size
            }
            is Column.StringIndex -> {
                heapIndexWidth(STRINGS_HEAP_FLAG)
            }
            is Column.GuidIndex -> {
                heapIndexWidth(GUID_HEAP_FLAG)
            }
            is Column.BlobIndex -> {
                heapIndexWidth(BLOB_HEAP_FLAG)
            }
            is Column.TableIndex -> {
                indexWidth(rowCounts[column.table], 0)
            }
            is Column.CodedIndex -> {
                var largest = 0
                for (table in column.tables) {
                    if (table != UNUSED_TAG) {
                        largest = maxOf(largest, rowCounts[table])
                    }
                }
                indexWidth(largest, column.tagBits)
            }
        }
    }

    private fun heapIndexWidth(flag: Int): Int {
        if (heapSizes and flag != 0) {
            return LONG_WIDTH
        }
        return SHORT_WIDTH
    }

    /** Two bytes while the row number still fits beside the tag bits (II.24.2.6). */
    private fun indexWidth(rows: Int, tagBits: Int): Int {
        if (rows < (1 shl (SHORT_BITS - tagBits))) {
            return SHORT_WIDTH
        }
        return LONG_WIDTH
    }

    companion object {
        const val MODULE = 0x00
        const val TYPE_REF = 0x01
        const val TYPE_DEF = 0x02
        const val FIELD_POINTER = 0x03
        const val FIELD = 0x04
        const val METHOD_POINTER = 0x05
        const val METHOD_DEF = 0x06
        const val PARAM_POINTER = 0x07
        const val PARAM = 0x08
        const val INTERFACE_IMPL = 0x09
        const val MEMBER_REF = 0x0A
        const val CONSTANT = 0x0B
        const val CUSTOM_ATTRIBUTE = 0x0C
        const val FIELD_MARSHAL = 0x0D
        const val DECL_SECURITY = 0x0E
        const val CLASS_LAYOUT = 0x0F
        const val FIELD_LAYOUT = 0x10
        const val STANDALONE_SIG = 0x11
        const val EVENT_MAP = 0x12
        const val EVENT_POINTER = 0x13
        const val EVENT = 0x14
        const val PROPERTY_MAP = 0x15
        const val PROPERTY_POINTER = 0x16
        const val PROPERTY = 0x17
        const val METHOD_SEMANTICS = 0x18
        const val METHOD_IMPL = 0x19
        const val MODULE_REF = 0x1A
        const val TYPE_SPEC = 0x1B
        const val IMPL_MAP = 0x1C
        const val FIELD_RVA = 0x1D
        const val ENC_LOG = 0x1E
        const val ENC_MAP = 0x1F
        const val ASSEMBLY = 0x20
        const val ASSEMBLY_PROCESSOR = 0x21
        const val ASSEMBLY_OS = 0x22
        const val ASSEMBLY_REF = 0x23
        const val ASSEMBLY_REF_PROCESSOR = 0x24
        const val ASSEMBLY_REF_OS = 0x25
        const val FILE = 0x26
        const val EXPORTED_TYPE = 0x27
        const val MANIFEST_RESOURCE = 0x28
        const val NESTED_CLASS = 0x29
        const val GENERIC_PARAM = 0x2A
        const val METHOD_SPEC = 0x2B
        const val GENERIC_PARAM_CONSTRAINT = 0x2C

        /** Tables 0 through GenericParamConstraint: everything an assembly can contain. */
        const val TABLE_COUNT = 0x2D

        /** A tag value no table stands behind, as in CustomAttributeType's unused slots. */
        const val UNUSED_TAG = -1

        private const val HEAP_SIZES_OFFSET = 6

        private const val VALID_MASK_OFFSET = 8

        private const val ROW_COUNTS_OFFSET = 24

        private const val STRINGS_HEAP_FLAG = 0x01

        private const val GUID_HEAP_FLAG = 0x02

        private const val BLOB_HEAP_FLAG = 0x04

        /** Set in the uncompressed `#-` stream: four more bytes follow the row counts. */
        private const val EXTRA_DATA_FLAG = 0x40

        private const val SHORT_WIDTH = 2

        private const val LONG_WIDTH = 4

        private const val SHORT_BITS = 16

        private const val BYTE_WIDTH = 1

        /** The coded indexes of II.24.2.6, each as the tables its tag selects and the tag's width. */
        val TYPE_DEF_OR_REF = listOf(TYPE_DEF, TYPE_REF, TYPE_SPEC)
        const val TYPE_DEF_OR_REF_BITS = 2
        val RESOLUTION_SCOPE = listOf(MODULE, MODULE_REF, ASSEMBLY_REF, TYPE_REF)
        const val RESOLUTION_SCOPE_BITS = 2
        val TYPE_OR_METHOD_DEF = listOf(TYPE_DEF, METHOD_DEF)
        const val TYPE_OR_METHOD_DEF_BITS = 1

        private val HAS_CONSTANT = listOf(FIELD, PARAM, PROPERTY)
        private val HAS_CUSTOM_ATTRIBUTE = listOf(
            METHOD_DEF, FIELD, TYPE_REF, TYPE_DEF, PARAM, INTERFACE_IMPL, MEMBER_REF, MODULE,
            DECL_SECURITY, PROPERTY, EVENT, STANDALONE_SIG, MODULE_REF, TYPE_SPEC, ASSEMBLY,
            ASSEMBLY_REF, FILE, EXPORTED_TYPE, MANIFEST_RESOURCE, GENERIC_PARAM,
            GENERIC_PARAM_CONSTRAINT, METHOD_SPEC,
        )
        private val HAS_FIELD_MARSHAL = listOf(FIELD, PARAM)
        private val HAS_DECL_SECURITY = listOf(TYPE_DEF, METHOD_DEF, ASSEMBLY)
        private val MEMBER_REF_PARENT = listOf(TYPE_DEF, TYPE_REF, MODULE_REF, METHOD_DEF, TYPE_SPEC)
        private val HAS_SEMANTICS = listOf(EVENT, PROPERTY)
        private val METHOD_DEF_OR_REF = listOf(METHOD_DEF, MEMBER_REF)
        private val MEMBER_FORWARDED = listOf(FIELD, METHOD_DEF)
        private val IMPLEMENTATION = listOf(FILE, ASSEMBLY_REF, EXPORTED_TYPE)
        private val CUSTOM_ATTRIBUTE_TYPE = listOf(UNUSED_TAG, UNUSED_TAG, METHOD_DEF, MEMBER_REF, UNUSED_TAG)

        private val U8 = Column.Fixed(BYTE_WIDTH)
        private val U16 = Column.Fixed(SHORT_WIDTH)
        private val U32 = Column.Fixed(LONG_WIDTH)
        private val S = Column.StringIndex
        private val G = Column.GuidIndex
        private val B = Column.BlobIndex

        private fun index(table: Int): Column {
            return Column.TableIndex(table)
        }

        private fun coded(tables: List<Int>, tagBits: Int): Column {
            return Column.CodedIndex(tables, tagBits)
        }

        /** Column indexes of the tables this reader actually looks into. */
        const val TYPE_REF_SCOPE = 0
        const val TYPE_REF_NAME = 1
        const val TYPE_REF_NAMESPACE = 2
        const val TYPE_DEF_FLAGS = 0
        const val TYPE_DEF_NAME = 1
        const val TYPE_DEF_NAMESPACE = 2
        const val TYPE_DEF_EXTENDS = 3
        const val TYPE_DEF_FIELD_LIST = 4
        const val FIELD_FLAGS = 0
        const val FIELD_NAME = 1
        const val FIELD_SIGNATURE = 2
        const val CLASS_LAYOUT_PACKING = 0
        const val CLASS_LAYOUT_SIZE = 1
        const val CLASS_LAYOUT_PARENT = 2
        const val FIELD_LAYOUT_OFFSET = 0
        const val FIELD_LAYOUT_FIELD = 1
        const val TYPE_SPEC_SIGNATURE = 0
        const val NESTED_CLASS_NESTED = 0
        const val NESTED_CLASS_ENCLOSING = 1
        const val GENERIC_PARAM_NUMBER = 0
        const val GENERIC_PARAM_OWNER = 2
        const val GENERIC_PARAM_NAME = 3
        const val ASSEMBLY_NAME = 7
        const val TYPE_DEF_METHOD_LIST = 5
        const val METHOD_FLAGS = 2
        const val METHOD_NAME = 3
        const val METHOD_SIGNATURE = 4
        const val PROPERTY_MAP_PARENT = 0
        const val PROPERTY_MAP_LIST = 1
        const val PROPERTY_NAME = 1
        const val PROPERTY_SIGNATURE = 2

        /** II.22, table by table, in table-number order. */
        private val SCHEMA: List<List<Column>> = listOf(
            listOf(U16, S, G, G, G),
            listOf(coded(RESOLUTION_SCOPE, RESOLUTION_SCOPE_BITS), S, S),
            listOf(U32, S, S, coded(TYPE_DEF_OR_REF, TYPE_DEF_OR_REF_BITS), index(FIELD), index(METHOD_DEF)),
            listOf(index(FIELD)),
            listOf(U16, S, B),
            listOf(index(METHOD_DEF)),
            listOf(U32, U16, U16, S, B, index(PARAM)),
            listOf(index(PARAM)),
            listOf(U16, U16, S),
            listOf(index(TYPE_DEF), coded(TYPE_DEF_OR_REF, TYPE_DEF_OR_REF_BITS)),
            listOf(coded(MEMBER_REF_PARENT, 3), S, B),
            listOf(U8, U8, coded(HAS_CONSTANT, 2), B),
            listOf(coded(HAS_CUSTOM_ATTRIBUTE, 5), coded(CUSTOM_ATTRIBUTE_TYPE, 3), B),
            listOf(coded(HAS_FIELD_MARSHAL, 1), B),
            listOf(U16, coded(HAS_DECL_SECURITY, 2), B),
            listOf(U16, U32, index(TYPE_DEF)),
            listOf(U32, index(FIELD)),
            listOf(B),
            listOf(index(TYPE_DEF), index(EVENT)),
            listOf(index(EVENT)),
            listOf(U16, S, coded(TYPE_DEF_OR_REF, TYPE_DEF_OR_REF_BITS)),
            listOf(index(TYPE_DEF), index(PROPERTY)),
            listOf(index(PROPERTY)),
            listOf(U16, S, B),
            listOf(U16, index(METHOD_DEF), coded(HAS_SEMANTICS, 1)),
            listOf(index(TYPE_DEF), coded(METHOD_DEF_OR_REF, 1), coded(METHOD_DEF_OR_REF, 1)),
            listOf(S),
            listOf(B),
            listOf(U16, coded(MEMBER_FORWARDED, 1), S, index(MODULE_REF)),
            listOf(U32, index(FIELD)),
            listOf(U32, U32),
            listOf(U32),
            listOf(U32, U16, U16, U16, U16, U32, B, S, S),
            listOf(U32),
            listOf(U32, U32, U32),
            listOf(U16, U16, U16, U16, U32, B, S, S, B),
            listOf(U32, index(ASSEMBLY_REF)),
            listOf(U32, U32, U32, index(ASSEMBLY_REF)),
            listOf(U32, S, B),
            listOf(U32, U32, S, S, coded(IMPLEMENTATION, 2)),
            listOf(U32, U32, S, coded(IMPLEMENTATION, 2)),
            listOf(index(TYPE_DEF), index(TYPE_DEF)),
            listOf(U16, U16, coded(TYPE_OR_METHOD_DEF, TYPE_OR_METHOD_DEF_BITS), S),
            listOf(coded(METHOD_DEF_OR_REF, 1), B),
            listOf(index(GENERIC_PARAM), coded(TYPE_DEF_OR_REF, TYPE_DEF_OR_REF_BITS)),
        )
    }
}
