package com.memorylayout.metadata

/** The file is not a .NET assembly, or not one this reader understands. */
class MetadataFormatException(message: String) : Exception(message)

/**
 * The metadata of a .NET assembly, found inside its PE file: the heaps and the table stream.
 *
 * Only what a layout needs is read -- no IL, no resources, no signatures of methods. The format is
 * ECMA-335 partition II; the section numbers in the comments below point into it.
 */
class MetadataImage private constructor(private val bytes: ByteArray) {

    private var stringsHeapOffset = 0

    private var blobHeapOffset = 0

    private var tableStreamOffset = -1

    lateinit var tables: MetadataTables
        private set

    fun byteAt(offset: Int): Int {
        return bytes[offset].toInt() and BYTE_MASK
    }

    fun unsignedShortAt(offset: Int): Int {
        return byteAt(offset) or (byteAt(offset + 1) shl BITS_PER_BYTE)
    }

    /** A 32-bit value; every one this reader looks at (offsets, row counts, sizes) fits an Int. */
    fun intAt(offset: Int): Int {
        return unsignedShortAt(offset) or (unsignedShortAt(offset + 2) shl BITS_PER_SHORT)
    }

    fun longAt(offset: Int): Long {
        val low = intAt(offset).toLong() and INT_MASK
        val high = intAt(offset + INT_SIZE).toLong() and INT_MASK
        return low or (high shl BITS_PER_INT)
    }

    /** An entry of the `#Strings` heap: UTF-8, zero-terminated. */
    fun string(index: Int): String {
        val start = stringsHeapOffset + index
        var end = start
        while (bytes[end].toInt() != 0) {
            end++
        }
        return String(bytes, start, end - start, Charsets.UTF_8)
    }

    /** An entry of the `#Blob` heap, without its length prefix (II.24.2.4). */
    fun blob(index: Int): ByteArray {
        val start = blobHeapOffset + index
        val length = CompressedInteger.read(bytes, start)
        val dataStart = start + CompressedInteger.sizeAt(bytes, start)
        return bytes.copyOfRange(dataStart, dataStart + length)
    }

    private fun parse() {
        val metadataRoot = locateMetadataRoot()
        if (intAt(metadataRoot) != METADATA_SIGNATURE) {
            throw MetadataFormatException("no metadata signature")
        }
        val versionLength = intAt(metadataRoot + METADATA_VERSION_LENGTH_OFFSET)
        var position = metadataRoot + METADATA_VERSION_OFFSET + versionLength
        val streamCount = unsignedShortAt(position + STREAM_COUNT_OFFSET)
        position += STREAM_HEADERS_OFFSET
        for (streamIndex in 0 until streamCount) {
            val offset = intAt(position)
            position += STREAM_HEADER_FIXED_SIZE
            var nameEnd = position
            while (bytes[nameEnd].toInt() != 0) {
                nameEnd++
            }
            val name = String(bytes, position, nameEnd - position, Charsets.US_ASCII)
            // The name is zero-terminated and then padded to the next four-byte boundary; the
            // terminator counts, so an eight-letter name like `#Strings` takes twelve bytes.
            val afterTerminator = nameEnd + 1
            position = (afterTerminator + STREAM_NAME_ALIGNMENT) and STREAM_NAME_ALIGNMENT.inv()
            when (name) {
                "#Strings" -> {
                    stringsHeapOffset = metadataRoot + offset
                }
                "#Blob" -> {
                    blobHeapOffset = metadataRoot + offset
                }
                "#~", "#-" -> {
                    tableStreamOffset = metadataRoot + offset
                }
                else -> {
                    // #GUID and #US hold nothing a layout needs.
                }
            }
        }
        if (tableStreamOffset < 0) {
            throw MetadataFormatException("no table stream")
        }
        tables = MetadataTables(this, tableStreamOffset)
    }

    /** From the DOS header to the PE header, its CLI data directory, and the metadata it points at. */
    private fun locateMetadataRoot(): Int {
        if (unsignedShortAt(0) != DOS_SIGNATURE) {
            throw MetadataFormatException("not a PE file")
        }
        val peHeader = intAt(PE_HEADER_POINTER_OFFSET)
        if (intAt(peHeader) != PE_SIGNATURE) {
            throw MetadataFormatException("no PE signature")
        }
        val sectionCount = unsignedShortAt(peHeader + SECTION_COUNT_OFFSET)
        val optionalHeaderSize = unsignedShortAt(peHeader + OPTIONAL_HEADER_SIZE_OFFSET)
        val optionalHeader = peHeader + OPTIONAL_HEADER_OFFSET
        val dataDirectories: Int
        if (unsignedShortAt(optionalHeader) == PE32_MAGIC) {
            dataDirectories = optionalHeader + PE32_DATA_DIRECTORIES_OFFSET
        } else {
            dataDirectories = optionalHeader + PE32_PLUS_DATA_DIRECTORIES_OFFSET
        }
        val sectionTable = optionalHeader + optionalHeaderSize
        val cliHeaderRva = intAt(dataDirectories + CLI_DIRECTORY_INDEX * DATA_DIRECTORY_SIZE)
        if (cliHeaderRva == 0) {
            throw MetadataFormatException("a native binary, not an assembly")
        }
        val cliHeader = fileOffsetOf(cliHeaderRva, sectionTable, sectionCount)
        val metadataRva = intAt(cliHeader + CLI_METADATA_RVA_OFFSET)
        return fileOffsetOf(metadataRva, sectionTable, sectionCount)
    }

    private fun fileOffsetOf(rva: Int, sectionTable: Int, sectionCount: Int): Int {
        for (sectionIndex in 0 until sectionCount) {
            val section = sectionTable + sectionIndex * SECTION_HEADER_SIZE
            val virtualSize = intAt(section + SECTION_VIRTUAL_SIZE_OFFSET)
            val virtualAddress = intAt(section + SECTION_VIRTUAL_ADDRESS_OFFSET)
            val rawSize = intAt(section + SECTION_RAW_SIZE_OFFSET)
            val rawPointer = intAt(section + SECTION_RAW_POINTER_OFFSET)
            val extent = maxOf(virtualSize, rawSize)
            if (rva >= virtualAddress && rva < virtualAddress + extent) {
                return rva - virtualAddress + rawPointer
            }
        }
        throw MetadataFormatException("address outside every section")
    }

    companion object {
        /**
         * Reads the image, or throws [MetadataFormatException]. A truncated or foreign file is
         * reported the same way rather than as an index out of bounds from somewhere inside.
         */
        fun read(bytes: ByteArray): MetadataImage {
            val image = MetadataImage(bytes)
            try {
                image.parse()
            } catch (outOfBounds: IndexOutOfBoundsException) {
                throw MetadataFormatException("truncated file")
            }
            return image
        }

        private const val BYTE_MASK = 0xFF

        private const val INT_MASK = 0xFFFFFFFFL

        private const val BITS_PER_BYTE = 8

        private const val BITS_PER_SHORT = 16

        private const val BITS_PER_INT = 32

        private const val INT_SIZE = 4

        private const val DOS_SIGNATURE = 0x5A4D

        private const val PE_HEADER_POINTER_OFFSET = 0x3C

        private const val PE_SIGNATURE = 0x00004550

        private const val SECTION_COUNT_OFFSET = 6

        private const val OPTIONAL_HEADER_SIZE_OFFSET = 20

        private const val OPTIONAL_HEADER_OFFSET = 24

        private const val PE32_MAGIC = 0x10B

        private const val PE32_DATA_DIRECTORIES_OFFSET = 96

        private const val PE32_PLUS_DATA_DIRECTORIES_OFFSET = 112

        private const val CLI_DIRECTORY_INDEX = 14

        private const val DATA_DIRECTORY_SIZE = 8

        private const val CLI_METADATA_RVA_OFFSET = 8

        private const val SECTION_HEADER_SIZE = 40

        private const val SECTION_VIRTUAL_SIZE_OFFSET = 8

        private const val SECTION_VIRTUAL_ADDRESS_OFFSET = 12

        private const val SECTION_RAW_SIZE_OFFSET = 16

        private const val SECTION_RAW_POINTER_OFFSET = 20

        private const val METADATA_SIGNATURE = 0x424A5342

        private const val METADATA_VERSION_LENGTH_OFFSET = 12

        private const val METADATA_VERSION_OFFSET = 16

        private const val STREAM_COUNT_OFFSET = 2

        private const val STREAM_HEADERS_OFFSET = 4

        private const val STREAM_HEADER_FIXED_SIZE = 8

        private const val STREAM_NAME_ALIGNMENT = 3
    }
}

/** The variable-length unsigned integers of II.23.2: one, two or four bytes, big-endian. */
object CompressedInteger {

    private const val ONE_BYTE_MARKER = 0x80

    private const val TWO_BYTE_MARKER = 0xC0

    private const val TWO_BYTE_VALUE = 0x80

    private const val TWO_BYTE_PAYLOAD = 0x3F

    private const val FOUR_BYTE_PAYLOAD = 0x1F

    private const val BYTE_MASK = 0xFF

    private const val FIRST_BYTE_SHIFT = 8

    private const val SECOND_BYTE_SHIFT = 16

    private const val THIRD_BYTE_SHIFT = 24

    private const val FOUR_BYTE_SIZE = 4

    fun read(data: ByteArray, offset: Int): Int {
        val first = data[offset].toInt() and BYTE_MASK
        if (first and ONE_BYTE_MARKER == 0) {
            return first
        }
        val second = data[offset + 1].toInt() and BYTE_MASK
        if (first and TWO_BYTE_MARKER == TWO_BYTE_VALUE) {
            return ((first and TWO_BYTE_PAYLOAD) shl FIRST_BYTE_SHIFT) or second
        }
        val third = data[offset + 2].toInt() and BYTE_MASK
        val fourth = data[offset + 3].toInt() and BYTE_MASK
        return ((first and FOUR_BYTE_PAYLOAD) shl THIRD_BYTE_SHIFT) or
            (second shl SECOND_BYTE_SHIFT) or
            (third shl FIRST_BYTE_SHIFT) or
            fourth
    }

    fun sizeAt(data: ByteArray, offset: Int): Int {
        val first = data[offset].toInt() and BYTE_MASK
        if (first and ONE_BYTE_MARKER == 0) {
            return 1
        }
        if (first and TWO_BYTE_MARKER == TWO_BYTE_VALUE) {
            return 2
        }
        return FOUR_BYTE_SIZE
    }
}
