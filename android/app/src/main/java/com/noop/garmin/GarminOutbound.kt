package com.noop.garmin

// MARK: - The send allowlist
//
// Faithful Kotlin twin of Packages/GarminProtocol/Sources/GarminProtocol/GarminOutbound.swift.
// GarminOutbound is the ONLY way to produce GFDI bytes for the watch: the frame builder below is private to
// this file, and the transport (GarminMultiLink.plainPackets, GarminMlrSession.send) accepts only a
// GarminOutboundFrame, whose constructor is internal and called only here. A message with no case cannot
// be sent.
//
// Deliberately ABSENT, and not to be added (BLE safety contract, docs/CONTRIBUTING.md):
//   - system events FACTORY_RESET (2), DEVICE_SOFTWARE_UPDATE (10), DEVICE_DISCONNECT (11), TRIGGER_REBOOT (17);
//   - DELETE_FILE 5006, SET_FILE_FLAG 5008, UPLOAD_REQUEST 5003, CREATE_FILE 5005;
//   - FileAccess Delete / ModifyFlags; the key-exchange ids 5102…5112;
//   - any case taking a caller-supplied type id or raw payload.
// GarminOutboundAllowlistTest (and the Swift twin) enumerate every case and hold this list.

/** A COBS-delimited GFDI message ready for the multi-link transport. Only GarminOutbound makes one. */
class GarminOutboundFrame internal constructor(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is GarminOutboundFrame && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

/** The system events NOOP may send. Raw values are wire ordinals; 2, 10, 11 and 17 have no entry. */
enum class GarminSafeSystemEvent(val raw: Int) {
    SYNC_COMPLETE(0),
    SYNC_FAIL(1),
    PAIR_START(3),
    PAIR_COMPLETE(4),
    PAIR_FAIL(5),
    HOST_DID_ENTER_FOREGROUND(6),
    HOST_DID_ENTER_BACKGROUND(7),
    SYNC_READY(8),
    NEW_DOWNLOAD_AVAILABLE(9),
    TUTORIAL_COMPLETE(12),
    SETUP_WIZARD_START(13),
    SETUP_WIZARD_COMPLETE(14),
    SETUP_WIZARD_SKIPPED(15),
    TIME_UPDATED(16),
}

/** 5007 filter selector sent before a legacy directory download. */
enum class GarminFilterType(val raw: Int) {
    ALL_FILES(0),
    DEFAULT(1),
    ONLY_NEW(3),
}

/** 5002 request type. */
enum class GarminDownloadKind(val raw: Int) {
    CONTINUE(0),
    NEW(1),
}

/**
 * Capabilities NOOP may advertise in 5050, by bit index. Kept small on purpose: a capability the watch
 * thinks the phone has makes it send traffic the phone must answer. Add one back only with the model named.
 */
enum class GarminCapability(val bit: Int) {
    CONNECT_MOBILE_FIT_LINK(0),
    SYNC(3),
    DEVICE_INITIATES_SYNC(4),
    HOST_INITIATED_SYNC_REQUESTS(5),
    GARMIN_DEVICE_INFO_FILE_TYPE(55),
    CURRENT_TIME_REQUEST_SUPPORT(71),
    MULTI_LINK_SERVICE(76),
    SYNC_2(90);

    companion object {
        /** 129 known capability bits → 17 bytes. */
        const val BITMAP_BYTE_COUNT = 17

        fun bitmap(capabilities: Set<GarminCapability>): ByteArray {
            val out = ByteArray(BITMAP_BYTE_COUNT)
            for (c in capabilities) out[c.bit / 8] = (out[c.bit / 8].toInt() or (1 shl (c.bit % 8))).toByte()
            return out
        }
    }
}

/** What the phone tells the watch about itself in the 5024 reply. NOOP identifies as itself. */
data class GarminHostIdentity(
    val protocolVersion: Int,
    val productNumber: Int,
    val unitNumber: Long,
    val softwareVersion: Int,
    val maxPacketSize: Int,
    val bluetoothName: String,
    val manufacturer: String,
    val model: String,
)

/** Which protobuf message a [GarminOutbound.ProtobufStatus] answers. */
enum class GarminProtobufDirection(internal val messageType: GarminMessageType) {
    REQUEST(GarminMessageType.PROTOBUF_REQUEST),
    RESPONSE(GarminMessageType.PROTOBUF_RESPONSE),
}

sealed class GarminOutbound {

    /** One tag per case, for the allowlist test's exhaustive enumeration. [label] matches the Swift case name. */
    enum class Kind(val label: String) {
        STATUS("status"),
        DEVICE_INFORMATION_REPLY("deviceInformationReply"),
        CONFIGURATION("configuration"),
        AUTH_NEGOTIATION_REPLY("authNegotiationReply"),
        CURRENT_TIME_REPLY("currentTimeReply"),
        SYSTEM_EVENT("systemEvent"),
        FILTER("filter"),
        DOWNLOAD_REQUEST("downloadRequest"),
        FILE_TRANSFER_DATA_REPLY("fileTransferDataReply"),
        SUPPORTED_FILE_TYPES_REQUEST("supportedFileTypesRequest"),
        FILE_ACCESS_REQUEST("fileAccessRequest"),
        PROTOBUF_STATUS("protobufStatus"),
    }

    abstract val kind: Kind

    /** 5000 generic reply to an inbound message (ACK, or UNSUPPORTED for something NOOP does not handle). */
    data class Status(val of: Int, val status: GarminStatus) : GarminOutbound() {
        override val kind get() = Kind.STATUS
    }

    /** 5000 reply to the watch's 5024. [protocolFlags] is 1 when the watch's protocol version is 1xx. */
    data class DeviceInformationReply(val host: GarminHostIdentity, val protocolFlags: Int) : GarminOutbound() {
        override val kind get() = Kind.DEVICE_INFORMATION_REPLY
    }

    /** 5050 NOOP's capability bitmap. */
    data class Configuration(val capabilities: Set<GarminCapability>) : GarminOutbound() {
        override val kind get() = Kind.CONFIGURATION
    }

    /** 5000 reply to 5101, echoing the watch's values with the "guess OK" result (no encryption). */
    data class AuthNegotiationReply(val unknown: Int, val flags: Long) : GarminOutbound() {
        override val kind get() = Kind.AUTH_NEGOTIATION_REPLY
    }

    /**
     * 5000 reply to 5052: Garmin time, UTC offset in seconds, then the Garmin times of the zone transition
     * after the coming one ([followingTransition]) and of the coming one ([nextTransition]), in that order.
     */
    data class CurrentTimeReply(
        val referenceId: Long,
        val garminTime: Long,
        val utcOffsetSeconds: Int,
        val followingTransition: Long,
        val nextTransition: Long,
    ) : GarminOutbound() {
        override val kind get() = Kind.CURRENT_TIME_REPLY
    }

    /** 5030 with a one-byte value (0 for every event NOOP sends today). */
    data class SystemEvent(val event: GarminSafeSystemEvent, val value: Int) : GarminOutbound() {
        override val kind get() = Kind.SYSTEM_EVENT
    }

    /** 5007. */
    data class Filter(val type: GarminFilterType) : GarminOutbound() {
        override val kind get() = Kind.FILTER
    }

    /** 5002 read a file by index (index 0 is the directory). */
    data class DownloadRequest(
        val fileIndex: Int,
        val dataOffset: Long,
        val downloadKind: GarminDownloadKind,
        val crcSeed: Int,
        val dataSize: Long,
        val flags: Int,
    ) : GarminOutbound() {
        override val kind get() = Kind.DOWNLOAD_REQUEST
    }

    /** 5000 reply to a 5004 chunk: transfer status (0 OK) and the next offset expected. */
    data class FileTransferDataReply(val transferStatus: Int, val nextOffset: Long) : GarminOutbound() {
        override val kind get() = Kind.FILE_TRANSFER_DATA_REPLY
    }

    /** 5031 ask which file types the watch supports. */
    object SupportedFileTypesRequest : GarminOutbound() {
        override val kind get() = Kind.SUPPORTED_FILE_TYPES_REQUEST
    }

    /** 5043 a read-only FileAccess request, sent as one chunk. */
    data class FileAccessRequest(val requestId: Int, val request: GarminFileAccessRequest) : GarminOutbound() {
        override val kind get() = Kind.FILE_ACCESS_REQUEST
    }

    /** 5000 reply to an inbound 5043/5044 chunk; discard = true with a non-zero code declines it. */
    data class ProtobufStatus(
        val of: GarminProtobufDirection,
        val requestId: Int,
        val dataOffset: Long,
        val discard: Boolean,
        val code: Int,
    ) : GarminOutbound() {
        override val kind get() = Kind.PROTOBUF_STATUS
    }

    /** The GFDI message (len, type, payload, CRC), before COBS. */
    fun message(): ByteArray {
        val (type, payload) = typeAndPayload()
        return encodeGfdi(type.raw, payload)
    }

    /** The COBS-delimited frame for the transport. */
    fun frame(): GarminOutboundFrame = GarminOutboundFrame(GarminCobs.encode(message()))

    private fun typeAndPayload(): Pair<GarminMessageType, ByteArray> {
        val w = GarminByteWriter()
        val ack = GarminStatus.ACK.raw
        val messageType = when (this) {
            is Status -> {
                w.u16(of); w.u8(status.raw)
                GarminMessageType.RESPONSE
            }
            is DeviceInformationReply -> {
                w.u16(GarminMessageType.DEVICE_INFORMATION.raw); w.u8(ack)
                w.u16(host.protocolVersion); w.u16(host.productNumber); w.u32(host.unitNumber)
                w.u16(host.softwareVersion); w.u16(host.maxPacketSize)
                w.string8(host.bluetoothName); w.string8(host.manufacturer); w.string8(host.model)
                w.u8(protocolFlags)
                GarminMessageType.RESPONSE
            }
            is Configuration -> {
                val bitmap = GarminCapability.bitmap(capabilities)
                w.u8(bitmap.size); w.append(bitmap)
                GarminMessageType.CONFIGURATION
            }
            is AuthNegotiationReply -> {
                w.u16(GarminMessageType.AUTH_NEGOTIATION.raw); w.u8(ack)
                w.u8(0) // auth result: "guess OK"
                w.u8(unknown); w.u32(flags)
                GarminMessageType.RESPONSE
            }
            is CurrentTimeReply -> {
                w.u16(GarminMessageType.CURRENT_TIME_REQUEST.raw); w.u8(ack)
                w.u32(referenceId); w.u32(garminTime); w.u32(utcOffsetSeconds.toLong() and 0xFFFF_FFFFL)
                w.u32(followingTransition); w.u32(nextTransition)
                GarminMessageType.RESPONSE
            }
            is SystemEvent -> {
                w.u8(event.raw); w.u8(value)
                GarminMessageType.SYSTEM_EVENT
            }
            is Filter -> {
                w.u8(type.raw)
                GarminMessageType.FILTER
            }
            is DownloadRequest -> {
                w.u16(fileIndex); w.u32(dataOffset); w.u8(downloadKind.raw); w.u16(crcSeed); w.u32(dataSize); w.u8(flags)
                GarminMessageType.DOWNLOAD_REQUEST
            }
            is FileTransferDataReply -> {
                w.u16(GarminMessageType.FILE_TRANSFER_DATA.raw); w.u8(ack)
                w.u8(transferStatus); w.u32(nextOffset)
                GarminMessageType.RESPONSE
            }
            SupportedFileTypesRequest -> GarminMessageType.SUPPORTED_FILE_TYPES_REQUEST
            is FileAccessRequest -> {
                val smart = request.encodeSmart()
                w.u16(requestId); w.u32(0); w.u32(smart.size.toLong()); w.u32(smart.size.toLong()); w.append(smart)
                GarminMessageType.PROTOBUF_REQUEST
            }
            is ProtobufStatus -> {
                w.u16(of.messageType.raw); w.u8(ack)
                w.u16(requestId); w.u32(dataOffset); w.u8(if (discard) 1 else 0); w.u8(code)
                GarminMessageType.RESPONSE
            }
        }
        return messageType to w.bytes
    }
}

/** Build a framed GFDI message. Private to this file: outbound traffic is built only through GarminOutbound. */
private fun encodeGfdi(type: Int, payload: ByteArray): ByteArray {
    val w = GarminByteWriter()
    w.u16((payload.size + GarminGfdi.MINIMUM_LENGTH) and 0xFFFF)
    w.u16(type)
    w.append(payload)
    val crc = GarminCrc.crc16(w.bytes)
    w.u16(crc)
    return w.bytes
}
