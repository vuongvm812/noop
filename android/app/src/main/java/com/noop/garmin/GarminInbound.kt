package com.noop.garmin

// MARK: - Inbound GFDI message decoding
//
// Faithful Kotlin twin of Packages/GarminProtocol/Sources/GarminProtocol/GarminInbound.swift, which lists
// every payload layout (Gadgetbridge wire facts, see ATTRIBUTION.md). Every decode returns null for a
// payload too short for its fixed fields; trailing bytes beyond the known layout are ignored.

/** One entry of the file directory (file index 0) or a FileAvailable notice. Exactly 16 bytes on the wire. */
data class GarminDirectoryEntry(
    val fileIndex: Int,
    val dataType: Int,
    val subType: Int,
    val fileNumber: Int,
    val specificFlags: Int,
    val fileFlags: Int,
    val fileSize: Long,
    /** Garmin-epoch seconds; 0 means the watch gave no date. */
    val garminTimestamp: Long,
) {
    /** Unix seconds, or null when the watch sent the 0 "no date" sentinel. */
    val unixTime: Long? get() = if (garminTimestamp == 0L) null else GarminGfdi.unixTime(garminTimestamp)

    internal fun write(w: GarminByteWriter) {
        w.u16(fileIndex); w.u8(dataType); w.u8(subType); w.u16(fileNumber)
        w.u8(specificFlags); w.u8(fileFlags); w.u32(fileSize); w.u32(garminTimestamp)
    }

    companion object {
        const val BYTE_COUNT = 16

        internal fun read(r: GarminByteReader): GarminDirectoryEntry? {
            val index = r.u16() ?: return null
            val dataType = r.u8() ?: return null
            val subType = r.u8() ?: return null
            val number = r.u16() ?: return null
            val specific = r.u8() ?: return null
            val flags = r.u8() ?: return null
            val size = r.u32() ?: return null
            val time = r.u32() ?: return null
            return GarminDirectoryEntry(index, dataType, subType, number, specific, flags, size, time)
        }

        /** Parse a downloaded directory file (file index 0): a whole number of 16-byte entries, else null. */
        fun parseDirectory(data: ByteArray): List<GarminDirectoryEntry>? {
            if (data.size % BYTE_COUNT != 0) return null
            val r = GarminByteReader(data)
            val entries = ArrayList<GarminDirectoryEntry>()
            while (r.remaining > 0) entries.add(read(r) ?: return null)
            return entries
        }
    }
}

/** The watch's 5024 DeviceInformation. */
class GarminDeviceInformation(
    val protocolVersion: Int,
    val productNumber: Int,
    val unitNumber: Long,
    /** Firmware version × 100. */
    val softwareVersion: Int,
    val maxPacketSize: Int,
    val bluetoothName: String,
    val deviceName: String,
    val deviceModel: String,
    /** Classic-Bluetooth address bytes as sent (little-endian), when the watch includes them. */
    val classicAddress: ByteArray?,
)

/** Per-origType fields that follow [origType][status] in a 5000 reply. Read only when status is ACK. */
sealed class GarminStatusDetail {
    object None : GarminStatusDetail()
    data class DownloadRequest(val downloadStatus: Int, val maxFileSize: Long) : GarminStatusDetail()
    data class FileTransferData(val transferStatus: Int, val dataOffset: Long) : GarminStatusDetail()
    data class SupportedFileTypes(val types: List<GarminSupportedFileType>) : GarminStatusDetail()
    data class Filter(val unknown: Int) : GarminStatusDetail()
    data class AuthNegotiation(val authStatus: Int, val unknown: Int, val flags: Long) : GarminStatusDetail()
    data class Protobuf(val requestId: Int, val dataOffset: Long, val chunkStatus: Int, val statusCode: Int) : GarminStatusDetail()
}

data class GarminSupportedFileType(val dataType: Int, val subType: Int, val name: String)

/** One chunk of a 5043 / 5044 protobuf message. */
class GarminProtobufChunk(val requestId: Int, val dataOffset: Long, val totalLength: Long, val data: ByteArray) {
    /** True when this chunk is the whole message (offset 0 and length equals total). */
    val isComplete: Boolean get() = dataOffset == 0L && data.size.toLong() == totalLength
}

sealed class GarminInbound {
    data class Status(val originalType: Int, val status: Int, val detail: GarminStatusDetail) : GarminInbound()
    class DeviceInformation(val info: GarminDeviceInformation) : GarminInbound()
    class Configuration(val capabilities: ByteArray) : GarminInbound()
    data class AuthNegotiation(val unknown: Int, val flags: Long) : GarminInbound()
    data class CurrentTimeRequest(val referenceId: Long) : GarminInbound()
    data class Synchronization(val type: Int, val fileTypeMask: Long) : GarminInbound()
    class FileTransferData(val flags: Int, val runningCrc: Int, val dataOffset: Long, val data: ByteArray) : GarminInbound()
    data class FileAvailable(val entry: GarminDirectoryEntry) : GarminInbound()
    class ProtobufRequest(val chunk: GarminProtobufChunk) : GarminInbound()
    class ProtobufResponse(val chunk: GarminProtobufChunk) : GarminInbound()
    /** 5023. [percent] is the watch's charge; [statusBits] and [centivolts] are kept raw (candidates). */
    data class BatteryStatus(val statusBits: Int, val centivolts: Int, val percent: Int) : GarminInbound()
    /** A valid frame of a type NOOP does not decode, or a known type whose payload was too short. */
    class Unknown(val type: Int, val payload: ByteArray) : GarminInbound()

    companion object {
        /** Decode a verified GFDI frame. Never fails: an undecodable payload becomes [Unknown]. */
        fun decode(frame: GarminGfdi.Frame): GarminInbound = decodeKnown(frame) ?: Unknown(frame.type, frame.payload)

        private fun decodeKnown(frame: GarminGfdi.Frame): GarminInbound? {
            val r = GarminByteReader(frame.payload)
            return when (GarminMessageType.fromRaw(frame.type)) {
                GarminMessageType.RESPONSE -> {
                    val orig = r.u16() ?: return null
                    val status = r.u8() ?: return null
                    val detail = if (status == GarminStatus.ACK.raw) statusDetail(orig, r) else GarminStatusDetail.None
                    Status(orig, status, detail)
                }
                GarminMessageType.DEVICE_INFORMATION -> {
                    val protocolVersion = r.u16() ?: return null
                    val product = r.u16() ?: return null
                    val unit = r.u32() ?: return null
                    val software = r.u16() ?: return null
                    val maxPacket = r.u16() ?: return null
                    val btName = r.string8() ?: return null
                    val name = r.string8() ?: return null
                    val model = r.string8() ?: return null
                    var classic: ByteArray? = null
                    if (r.u8() == 1 && r.take(6) != null) classic = r.take(6)
                    DeviceInformation(
                        GarminDeviceInformation(protocolVersion, product, unit, software, maxPacket, btName, name, model, classic),
                    )
                }
                GarminMessageType.CONFIGURATION -> {
                    val count = r.u8() ?: return null
                    Configuration(r.take(count) ?: return null)
                }
                GarminMessageType.AUTH_NEGOTIATION -> {
                    val unknown = r.u8() ?: return null
                    AuthNegotiation(unknown, r.u32() ?: return null)
                }
                GarminMessageType.CURRENT_TIME_REQUEST -> CurrentTimeRequest(r.u32() ?: return null)
                GarminMessageType.BATTERY_STATUS -> {
                    val status = r.u8() ?: return null
                    val volts = r.u8() ?: return null
                    BatteryStatus(status, volts, r.u8() ?: return null)
                }
                GarminMessageType.SYNCHRONIZATION -> {
                    val type = r.u8() ?: return null
                    when (r.u8() ?: return null) {
                        4 -> Synchronization(type, r.u32() ?: return null)
                        8 -> Synchronization(type, r.u64() ?: return null)
                        else -> null
                    }
                }
                GarminMessageType.FILE_TRANSFER_DATA -> {
                    val flags = r.u8() ?: return null
                    val crc = r.u16() ?: return null
                    val offset = r.u32() ?: return null
                    FileTransferData(flags, crc, offset, r.rest())
                }
                GarminMessageType.FILE_AVAILABLE -> FileAvailable(GarminDirectoryEntry.read(r) ?: return null)
                GarminMessageType.PROTOBUF_REQUEST, GarminMessageType.PROTOBUF_RESPONSE -> {
                    val id = r.u16() ?: return null
                    val offset = r.u32() ?: return null
                    val total = r.u32() ?: return null
                    val length = r.u32() ?: return null
                    if (length > Int.MAX_VALUE) return null
                    val chunk = GarminProtobufChunk(id, offset, total, r.take(length.toInt()) ?: return null)
                    if (frame.type == GarminMessageType.PROTOBUF_REQUEST.raw) ProtobufRequest(chunk) else ProtobufResponse(chunk)
                }
                else -> null
            }
        }

        private fun statusDetail(orig: Int, r: GarminByteReader): GarminStatusDetail {
            val none = GarminStatusDetail.None
            return when (GarminMessageType.fromRaw(orig)) {
                GarminMessageType.DOWNLOAD_REQUEST -> {
                    val s = r.u8() ?: return none
                    GarminStatusDetail.DownloadRequest(s, r.u32() ?: return none)
                }
                GarminMessageType.FILE_TRANSFER_DATA -> {
                    val s = r.u8() ?: return none
                    GarminStatusDetail.FileTransferData(s, r.u32() ?: return none)
                }
                GarminMessageType.SUPPORTED_FILE_TYPES_REQUEST -> {
                    val count = r.u8() ?: return none
                    val types = ArrayList<GarminSupportedFileType>()
                    repeat(count) {
                        val dt = r.u8() ?: return none
                        val st = r.u8() ?: return none
                        types.add(GarminSupportedFileType(dt, st, r.string8() ?: return none))
                    }
                    GarminStatusDetail.SupportedFileTypes(types)
                }
                GarminMessageType.FILTER -> GarminStatusDetail.Filter(r.u8() ?: return none)
                GarminMessageType.AUTH_NEGOTIATION -> {
                    val s = r.u8() ?: return none
                    val unknown = r.u8() ?: return none
                    GarminStatusDetail.AuthNegotiation(s, unknown, r.u32() ?: return none)
                }
                GarminMessageType.PROTOBUF_REQUEST, GarminMessageType.PROTOBUF_RESPONSE -> {
                    val id = r.u16() ?: return none
                    val offset = r.u32() ?: return none
                    val chunk = r.u8() ?: return none
                    GarminStatusDetail.Protobuf(id, offset, chunk, r.u8() ?: return none)
                }
                else -> none
            }
        }
    }
}

/** Decodes a capability bitmap from 5050 into the set of bit indices. */
object GarminCapabilityBitmap {
    fun indices(bitmap: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        for ((byteIndex, b) in bitmap.withIndex()) {
            for (bit in 0 until 8) if ((b.toInt() and (1 shl bit)) != 0) out.add(byteIndex * 8 + bit)
        }
        return out
    }
}
