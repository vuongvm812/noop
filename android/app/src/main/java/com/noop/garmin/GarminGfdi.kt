package com.noop.garmin

// MARK: - GFDI message frame
//
// Faithful Kotlin twin of Packages/GarminProtocol/Sources/GarminProtocol/GarminGfdi.swift. Wire facts
// (Gadgetbridge, see ATTRIBUTION.md): [len u16][type u16][payload…][crc u16], little-endian, `len`
// counting the whole message, CRC-16/ARC over everything before the CRC. Bit 15 of the type marks a
// compact id: type = (t & 0xFF) + 5000, bits 8…14 a sequence number.

object GarminGfdi {

    /** Smallest legal message: length, type, CRC. */
    const val MINIMUM_LENGTH = 6

    /** Seconds between the Unix epoch and the Garmin epoch (1989-12-31T00:00:00Z). */
    const val GARMIN_EPOCH_OFFSET = 631_065_600L

    /** Garmin time as an unsigned 32-bit value held in a Long. */
    fun garminTime(fromUnix: Long): Long = (fromUnix - GARMIN_EPOCH_OFFSET) and 0xFFFF_FFFFL

    fun unixTime(fromGarmin: Long): Long = fromGarmin + GARMIN_EPOCH_OFFSET

    /** One verified GFDI message. */
    class Frame(val rawType: Int, val type: Int, val compactSequence: Int?, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && rawType == other.rawType && type == other.type &&
                compactSequence == other.compactSequence && payload.contentEquals(other.payload)

        override fun hashCode(): Int = rawType * 31 + payload.contentHashCode()
    }

    sealed class DecodeError {
        object TooShort : DecodeError()
        data class LengthMismatch(val declared: Int, val actual: Int) : DecodeError()
        data class CrcMismatch(val expected: Int, val actual: Int) : DecodeError()
    }

    sealed class Result {
        data class Success(val frame: Frame) : Result()
        data class Failure(val error: DecodeError) : Result()
    }

    /** Verify and split one de-COBSed message. The declared length must equal the byte count exactly. */
    fun decode(message: ByteArray): Result {
        if (message.size < MINIMUM_LENGTH) return Result.Failure(DecodeError.TooShort)
        val declared = (message[0].toInt() and 0xFF) or ((message[1].toInt() and 0xFF) shl 8)
        if (declared != message.size) return Result.Failure(DecodeError.LengthMismatch(declared, message.size))
        val carried = (message[message.size - 2].toInt() and 0xFF) or ((message[message.size - 1].toInt() and 0xFF) shl 8)
        val computed = GarminCrc.crc16(message.copyOfRange(0, message.size - 2))
        if (carried != computed) return Result.Failure(DecodeError.CrcMismatch(computed, carried))
        val rawType = (message[2].toInt() and 0xFF) or ((message[3].toInt() and 0xFF) shl 8)
        val compact = (rawType and 0x8000) != 0
        return Result.Success(
            Frame(
                rawType = rawType,
                type = if (compact) (rawType and 0xFF) + 5000 else rawType,
                compactSequence = if (compact) (rawType shr 8) and 0x7F else null,
                payload = message.copyOfRange(4, message.size - 2),
            ),
        )
    }
}

/** GFDI message ids NOOP names. Ids outside this list still decode, as GarminInbound.Unknown. */
enum class GarminMessageType(val raw: Int) {
    RESPONSE(5000),
    DOWNLOAD_REQUEST(5002),
    UPLOAD_REQUEST(5003),
    FILE_TRANSFER_DATA(5004),
    CREATE_FILE(5005),
    DELETE_FILE(5006),
    FILTER(5007),
    SET_FILE_FLAG(5008),
    FILE_AVAILABLE(5009),
    BATTERY_STATUS(5023),
    DEVICE_INFORMATION(5024),
    SYSTEM_EVENT(5030),
    SUPPORTED_FILE_TYPES_REQUEST(5031),
    SYNCHRONIZATION(5037),
    PROTOBUF_REQUEST(5043),
    PROTOBUF_RESPONSE(5044),
    CONFIGURATION(5050),
    CURRENT_TIME_REQUEST(5052),
    AUTH_NEGOTIATION(5101);

    companion object {
        fun fromRaw(raw: Int): GarminMessageType? = entries.firstOrNull { it.raw == raw }
    }
}

/** The status byte of a 5000 reply. */
enum class GarminStatus(val raw: Int) {
    ACK(0),
    NAK(1),
    UNSUPPORTED(2),
    DECODE_ERROR(3),
    CRC_ERROR(4),
    LENGTH_ERROR(5),
}
