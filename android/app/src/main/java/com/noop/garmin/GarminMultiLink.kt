package com.noop.garmin

// MARK: - Multi-link (ML) transport: control frames, plain data framing, inbound routing
//
// Faithful Kotlin twin of Packages/GarminProtocol/Sources/GarminProtocol/GarminMultiLink.swift. Wire facts
// (Gadgetbridge, see ATTRIBUTION.md): byte 0 of every ML packet is a handle. Handle 0x00 carries 13-byte
// control frames [0x00][type u8][clientId u64][service u16][param u8] (REGISTER 0/1, CLOSE_HANDLE 2/3,
// CLOSE_ALL 5/6; REGISTER param 0 = plain, 2 = reliable; CLOSE_HANDLE param = handle). Other handles carry
// `[handle][payload…]`. Plain handles can also have bit 7 set (Gadgetbridge #5476), so only handles
// registered as reliable are routed to MLR.

/** The logical ML services NOOP knows by number. */
enum class GarminService(val raw: Int) {
    GFDI(1),
    REGISTRATION(4),
    REALTIME_HEART_RATE(6),
    REALTIME_STEPS(7),
    REALTIME_CALORIES(8),
    REALTIME_FLOORS(9),
    REALTIME_INTENSITY(10),
    REALTIME_HRV(12),
    REALTIME_STRESS(13),
    REALTIME_ACCELEROMETER(16),
    REALTIME_SPO2(19),
    REALTIME_BODY_BATTERY(20),
    REALTIME_RESPIRATION(21),
    FILE_TRANSFER_2(0x2018),
    FILE_TRANSFER_4(0x4018),
    FILE_TRANSFER_6(0x6018),
    FILE_TRANSFER_A(0xA018),
    FILE_TRANSFER_C(0xC018),
    FILE_TRANSFER_E(0xE018);

    /** The realtime multi-link channels (the services that carry `[handle][payload]` live data). */
    val isRealtime: Boolean
        get() = when (this) {
            REALTIME_HEART_RATE, REALTIME_STEPS, REALTIME_CALORIES, REALTIME_FLOORS, REALTIME_INTENSITY,
            REALTIME_HRV, REALTIME_STRESS, REALTIME_ACCELEROMETER, REALTIME_SPO2, REALTIME_BODY_BATTERY,
            REALTIME_RESPIRATION -> true
            GFDI, REGISTRATION, FILE_TRANSFER_2, FILE_TRANSFER_4, FILE_TRANSFER_6, FILE_TRANSFER_A,
            FILE_TRANSFER_C, FILE_TRANSFER_E -> false
        }

    companion object {
        fun fromRaw(raw: Int): GarminService? = entries.firstOrNull { it.raw == raw }
    }
}

object GarminMultiLink {

    /**
     * The client id NOOP puts in every control frame; responses with any other id are ignored. Gadgetbridge
     * uses 2, the only value observed working. HARDWARE-GATED: whether it matters for coexisting with Garmin
     * Connect is an open question for the GarminSource issue.
     */
    const val DEFAULT_CLIENT_ID: Long = 2L

    /** The handle reserved for control frames. */
    const val CONTROL_HANDLE: Int = 0x00

    const val TYPE_REGISTER_REQUEST = 0
    const val TYPE_REGISTER_RESPONSE = 1
    const val TYPE_CLOSE_HANDLE_REQUEST = 2
    const val TYPE_CLOSE_HANDLE_RESPONSE = 3
    const val TYPE_CLOSE_ALL_REQUEST = 5
    const val TYPE_CLOSE_ALL_RESPONSE = 6

    /** The ML control requests NOOP may send. There is no raw-bytes case. */
    sealed class ControlRequest {
        data class Register(val service: GarminService, val reliable: Boolean) : ControlRequest()
        data class CloseHandle(val service: GarminService, val handle: Int) : ControlRequest()
        object CloseAll : ControlRequest()

        /** The 13-byte control frame, written as-is to the ML write characteristic. */
        fun encode(clientId: Long = DEFAULT_CLIENT_ID): ByteArray {
            val w = GarminByteWriter()
            w.u8(CONTROL_HANDLE)
            when (this) {
                is Register -> {
                    w.u8(TYPE_REGISTER_REQUEST); w.u64(clientId); w.u16(service.raw); w.u8(if (reliable) 2 else 0)
                }
                is CloseHandle -> {
                    w.u8(TYPE_CLOSE_HANDLE_REQUEST); w.u64(clientId); w.u16(service.raw); w.u8(handle)
                }
                CloseAll -> {
                    w.u8(TYPE_CLOSE_ALL_REQUEST); w.u64(clientId); w.u16(0); w.u8(0)
                }
            }
            return w.bytes
        }
    }

    /** A control response from the watch. `service` stays raw so an unknown number is still reported. */
    sealed class ControlResponse {
        /** status 0 is success; handle and reliable are meaningful only then. */
        data class Registered(val service: Int, val status: Int, val handle: Int, val reliable: Boolean) : ControlResponse()
        data class HandleClosed(val service: Int, val handle: Int, val status: Int) : ControlResponse()
        object AllClosed : ControlResponse()
    }

    /** Parse a control packet. Null for a short packet, an echoed request, an unknown type or a foreign client. */
    fun parseControl(packet: ByteArray, clientId: Long = DEFAULT_CLIENT_ID): ControlResponse? {
        val r = GarminByteReader(packet)
        if (r.u8() != CONTROL_HANDLE) return null
        val type = r.u8() ?: return null
        val client = r.u64() ?: return null
        if (client != clientId) return null
        return when (type) {
            TYPE_REGISTER_RESPONSE -> {
                val service = r.u16() ?: return null
                val status = r.u8() ?: return null
                val handle = r.u8() ?: 0
                val reliable = (r.u8() ?: 0) != 0
                ControlResponse.Registered(service, status, handle, reliable)
            }
            TYPE_CLOSE_HANDLE_RESPONSE -> {
                val service = r.u16() ?: return null
                val handle = r.u8() ?: return null
                val status = r.u8() ?: return null
                ControlResponse.HandleClosed(service, handle, status)
            }
            TYPE_CLOSE_ALL_RESPONSE -> ControlResponse.AllClosed
            else -> null
        }
    }

    /** Payload bytes per plain-ML write: MTU − 3 (ATT header) − 1 (handle byte). */
    fun plainPayloadSize(mtu: Int): Int = maxOf(1, mtu - 4)

    /** Split an outbound frame into plain-ML writes `[handle][≤ MTU−4 bytes]`. */
    fun plainPackets(frame: GarminOutboundFrame, handle: Int, mtu: Int): List<ByteArray> {
        val chunk = plainPayloadSize(mtu)
        val bytes = frame.bytes
        val packets = ArrayList<ByteArray>()
        var start = 0
        while (start < bytes.size) {
            val end = minOf(start + chunk, bytes.size)
            packets.add(byteArrayOf(handle.toByte()) + bytes.copyOfRange(start, end))
            start = end
        }
        return packets
    }

    /** Where an inbound ML notification goes. */
    sealed class Route {
        class Control(val packet: ByteArray) : Route()
        /** An MLR packet for the registered reliable handle [handle]; pass it whole to that session. */
        class Reliable(val handle: Int, val packet: ByteArray) : Route()
        /** Plain data for [handle], handle byte stripped. */
        class Plain(val handle: Int, val payload: ByteArray) : Route()
        object Empty : Route()
    }

    /** Route a notification; [reliableHandles] are the handles registered with reliable = true. */
    fun route(notification: ByteArray, reliableHandles: Set<Int>): Route {
        if (notification.isEmpty()) return Route.Empty
        val first = notification[0].toInt() and 0xFF
        if ((first and 0x80) != 0) {
            val packed = (first and 0x70) shr 4
            val handle = reliableHandles.sorted().firstOrNull { (it and 0x07) == packed }
            if (handle != null) return Route.Reliable(handle, notification)
        }
        if (first == CONTROL_HANDLE) return Route.Control(notification)
        return Route.Plain(first, notification.copyOfRange(1, notification.size))
    }
}
