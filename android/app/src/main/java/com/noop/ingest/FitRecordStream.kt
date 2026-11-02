package com.noop.ingest

/**
 * FIT record stream, shared by the activity decoder ([FitDecoder]) and the Garmin wellness decoder
 * ([GarminWellnessParser]). Validates the file header and walks the record section, handing every
 * DATA message to a consumer together with its definition. Mirrors Swift `FitRecordStream`.
 *
 * SECURITY: every read is bounds-checked against the clamped data window. A declared dataSize larger
 * than the buffer is clamped, record counts are capped, and a definition that does not fit the buffer
 * or declares a zero-size field stops the walk instead of desynchronising it.
 */
internal class FitRecordStream private constructor(val bytes: ByteArray, val start: Int, val end: Int) {

    data class FieldDef(val num: Int, val size: Int, val baseType: Int)

    /** A local message type's definition; [totalSize] includes skipped developer-field bytes. */
    data class Definition(val globalNum: Int, val fields: List<FieldDef>, val bigEndian: Boolean, val totalSize: Int)

    /** Why a walk ended. Anything other than [END] means the file was cut short or malformed. */
    enum class Stop { END, RECORD_CAP, BAD_DEFINITION, TRUNCATED_DATA, UNDEFINED_LOCAL_TYPE }

    /**
     * Calls [body] with (definition, offset, compressedTimeOffset) for every DATA message, in file
     * order. compressedTimeOffset is the 5-bit offset of a compressed-timestamp header, null otherwise.
     */
    fun walk(maxRecords: Int = MAX_RECORDS, body: (Definition, Int, Int?) -> Unit): Stop {
        val defs = HashMap<Int, Definition>()
        var i = start
        var records = 0
        while (i < end) {
            if (records >= maxRecords) return Stop.RECORD_CAP
            records++
            val header = bytes[i].toInt() and 0xFF
            i += 1
            when {
                header and 0x80 != 0 -> {
                    // Compressed-timestamp header: always DATA; local type in bits 5-6, offset in bits 0-4.
                    val def = defs[(header shr 5) and 0x03] ?: return Stop.UNDEFINED_LOCAL_TYPE
                    if (i + def.totalSize > end) return Stop.TRUNCATED_DATA
                    body(def, i, header and 0x1F)
                    i += def.totalSize
                }
                header and 0x40 != 0 -> {
                    val parsed = readDefinition(i, header and 0x20 != 0) ?: return Stop.BAD_DEFINITION
                    defs[header and 0x0F] = parsed.first
                    i = parsed.second
                }
                else -> {
                    // No definition for this local type means its length is unknown: stop safely.
                    val def = defs[header and 0x0F] ?: return Stop.UNDEFINED_LOCAL_TYPE
                    if (i + def.totalSize > end) return Stop.TRUNCATED_DATA
                    body(def, i, null)
                    i += def.totalSize
                }
            }
        }
        return Stop.END
    }

    private fun readDefinition(start: Int, hasDev: Boolean): Pair<Definition, Int>? {
        // reserved(1) + architecture(1) + globalNum(2) + numFields(1).
        if (start + 5 > end) return null
        var i = start + 1
        val bigEndian = (bytes[i].toInt() and 0xFF) == 1; i += 1
        val globalNum = if (bigEndian) ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
        else (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
        i += 2
        val numFields = bytes[i].toInt() and 0xFF; i += 1
        if (numFields > MAX_FIELDS_PER_DEF || i + numFields * 3 > end) return null
        val fields = ArrayList<FieldDef>(numFields)
        var total = 0
        repeat(numFields) {
            val size = bytes[i + 1].toInt() and 0xFF
            if (size <= 0) return null
            fields.add(FieldDef(bytes[i].toInt() and 0xFF, size, bytes[i + 2].toInt() and 0xFF))
            total += size
            i += 3
        }
        if (hasDev) {
            // Developer fields are skipped, but their bytes count toward the data record length.
            if (i >= end) return null
            val numDev = bytes[i].toInt() and 0xFF; i += 1
            if (i + numDev * 3 > end) return null
            repeat(numDev) {
                val size = bytes[i + 1].toInt() and 0xFF
                if (size <= 0) return null
                total += size
                i += 3
            }
        }
        return Definition(globalNum, fields, bigEndian, total) to i
    }

    /** An unsigned integer of [size] (1..8) bytes; null out of bounds. Sentinels are the caller's job. */
    fun uint(off: Int, size: Int, bigEndian: Boolean): Long? {
        if (off < 0 || size < 1 || size > 8 || off + size > end) return null
        var v = 0L
        if (bigEndian) for (k in 0 until size) v = (v shl 8) or (bytes[off + k].toLong() and 0xFF)
        else for (k in 0 until size) v = v or ((bytes[off + k].toLong() and 0xFF) shl (8 * k))
        return v
    }

    /** A sign-extended integer of [size] (1..8) bytes; null out of bounds. */
    fun int(off: Int, size: Int, bigEndian: Boolean): Long? {
        val u = uint(off, size, bigEndian) ?: return null
        val bits = size * 8
        if (bits >= 64) return u
        val signBit = 1L shl (bits - 1)
        return if (u and signBit != 0L) u or ((1L shl bits) - 1).inv() else u
    }

    companion object {
        /** Caps (DoS): generous ceilings that still bound work against a crafted file. */
        const val MAX_RECORDS = 2_000_000
        const val MAX_FIELDS_PER_DEF = 255

        /** Validates the header and the ".FIT" signature; null for anything that is not a FIT file. */
        fun open(bytes: ByteArray): FitRecordStream? {
            if (bytes.size < 12) return null
            val headerSize = bytes[0].toInt() and 0xFF
            if (headerSize < 12 || headerSize > bytes.size) return null
            if (!(bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte() &&
                    bytes[10] == 'I'.code.toByte() && bytes[11] == 'T'.code.toByte())) return null
            val declared = (bytes[4].toLong() and 0xFF) or ((bytes[5].toLong() and 0xFF) shl 8) or
                ((bytes[6].toLong() and 0xFF) shl 16) or ((bytes[7].toLong() and 0xFF) shl 24)
            // Clamp the data window to what is actually in the buffer.
            val dataEnd = headerSize + minOf(declared, (bytes.size - headerSize).toLong()).toInt()
            if (headerSize >= dataEnd) return null
            return FitRecordStream(bytes, headerSize, dataEnd)
        }
    }
}
