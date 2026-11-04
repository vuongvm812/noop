package com.noop.huawei.decode

/**
 * Values of the P2P data-dictionary sync reply. The class and value ids, the value tag and the ranges are
 * protocol facts from Gadgetbridge (`HuaweiP2PDataDictionarySyncService.java`,
 * `p2p/dictionarysync/HuaweiDictionarySyncHRV.java`, `HuaweiDictionarySyncSkinTemperature.java`,
 * `HuaweiDictTypes.java`, AGPL-3.0), re-derived here; see ATTRIBUTION.md. Input is the record list wave 2
 * lifts out of the reply's TLV. Kotlin-only; Swift twin deferred (fork).
 */
object HuaweiDictionaryDecoder {

    const val CLASS_HRV = 500_044
    const val VALUE_HRV_RMSSD = 500_044_831
    const val CLASS_SKIN_TEMPERATURE = 400_012
    const val VALUE_SKIN_TEMPERATURE = 400_012_430

    /** The tag that carries a numeric value (an 8-byte big-endian double). */
    const val TAG_NUMBER = 0x0A

    /** One value of a dictionary record: [valueId] (tag 0x09), the tag that held it, and its raw bytes. */
    class Value(val valueId: Int, val tag: Int, val bytes: ByteArray)

    /** One dictionary record as the reply carries it. Times are unix MILLISECONDS; 0 when absent. */
    class Record(val dictClass: Int, val startMs: Long, val endMs: Long, val modifiedMs: Long, val values: List<Value>)

    enum class Kind { HRV_RMSSD_MS, SKIN_TEMP_C }

    /** One decoded value, stamped at the record start in unix seconds. */
    data class Sample(val ts: Long, val kind: Kind, val value: Double)

    private val HRV_RANGE = 0.0..200.0
    private val SKIN_TEMP_RANGE = 20.0..42.0

    /**
     * The HRV RMSSD (ms, 0-200, a zero dropped as unmeasured) and wrist skin temperature (degrees C,
     * 20-42) values of [records]. Other classes and value ids, values under another tag, values that are
     * not 8 bytes, and records without a start time are dropped.
     */
    fun samples(records: List<Record>): List<Sample> {
        val out = ArrayList<Sample>()
        for (rec in records) {
            if (rec.startMs <= 0) continue
            val (valueId, kind, range) = when (rec.dictClass) {
                CLASS_HRV -> Triple(VALUE_HRV_RMSSD, Kind.HRV_RMSSD_MS, HRV_RANGE)
                CLASS_SKIN_TEMPERATURE -> Triple(VALUE_SKIN_TEMPERATURE, Kind.SKIN_TEMP_C, SKIN_TEMP_RANGE)
                else -> continue
            }
            for (v in rec.values) {
                if (v.tag != TAG_NUMBER || v.valueId != valueId) continue
                val d = number(v.bytes) ?: continue
                if (d !in range || (kind == Kind.HRV_RMSSD_MS && d == 0.0)) continue
                out.add(Sample(Math.floorDiv(rec.startMs, 1000L), kind, d))
            }
        }
        return out
    }

    /** An 8-byte big-endian IEEE double, or null for any other length or a non-finite value. */
    fun number(b: ByteArray): Double? {
        if (b.size != 8) return null
        return java.lang.Double.longBitsToDouble(HuaweiByteReader.beUnsigned(b)!!).takeIf { it.isFinite() }
    }
}
