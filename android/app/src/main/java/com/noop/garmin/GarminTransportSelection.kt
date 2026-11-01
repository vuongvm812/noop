package com.noop.garmin

// Which GATT transport a watch offers. Twin of Packages/GarminProtocol/.../GarminTransportSelection.swift;
// the wire facts and the V2 → V1 → V0 → unsupported order are documented there (Gadgetbridge, ATTRIBUTION.md).

enum class GarminTransportKind(val label: String) {
    MULTI_LINK_V2("V2 multi-link"),
    V1("V1"),
    V0("V0"),
}

/** One discovered GATT service and its characteristic UUIDs, in the platform's discovery order. */
data class GarminGattService(val uuid: String, val characteristics: List<String>)

/** The characteristic pair a session runs on. UUIDs are lowercase, full 128-bit form. */
data class GarminTransport(val kind: GarminTransportKind, val service: String, val notify: String, val write: String)

object GarminTransportSelection {
    const val MULTI_LINK_SERVICE = "6a4e2800-667b-11e3-949a-0800200c9a66"
    const val V1_SERVICE = "6a4e2401-667b-11e3-949a-0800200c9a66"
    const val V1_WRITE = "6a4e4c80-667b-11e3-949a-0800200c9a66"
    const val V1_NOTIFY = "6a4ecd28-667b-11e3-949a-0800200c9a66"
    const val V0_SERVICE = "9b012401-bc30-ce9a-e111-0f67e491abde"
    const val V0_WRITE = "df334c80-e6a7-d082-274d-78fc66f85e16"
    const val V0_NOTIFY = "4acbcd28-7425-868e-f447-915c8f00d0cb"

    /** V2 notify characteristics in preference order; [MULTI_LINK_WRITE] at the same index is the pair. */
    val MULTI_LINK_NOTIFY: List<String> = (0..4).map { "6a4e281$it-667b-11e3-949a-0800200c9a66" }
    val MULTI_LINK_WRITE: List<String> = (0..4).map { "6a4e282$it-667b-11e3-949a-0800200c9a66" }

    val SERVICES_OF_INTEREST: List<String> = listOf(MULTI_LINK_SERVICE, V1_SERVICE, V0_SERVICE)

    /** Expand a 16/32-bit short UUID to the Bluetooth base form and lowercase everything. */
    fun normalize(uuid: String): String {
        val lower = uuid.lowercase()
        return when (lower.length) {
            4 -> "0000$lower-0000-1000-8000-00805f9b34fb"
            8 -> "$lower-0000-1000-8000-00805f9b34fb"
            else -> lower
        }
    }

    /** V2 if any pair is present, else V1, else V0, else null (unsupported). */
    fun select(services: List<GarminGattService>): GarminTransport? {
        fun chars(service: String): Set<String>? =
            services.firstOrNull { normalize(it.uuid) == service }?.characteristics?.map(::normalize)?.toSet()
        chars(MULTI_LINK_SERVICE)?.let { ml ->
            for (i in MULTI_LINK_NOTIFY.indices) {
                if (MULTI_LINK_NOTIFY[i] in ml && MULTI_LINK_WRITE[i] in ml) {
                    return GarminTransport(GarminTransportKind.MULTI_LINK_V2, MULTI_LINK_SERVICE, MULTI_LINK_NOTIFY[i], MULTI_LINK_WRITE[i])
                }
            }
        }
        chars(V1_SERVICE)?.let { if (V1_NOTIFY in it && V1_WRITE in it) return GarminTransport(GarminTransportKind.V1, V1_SERVICE, V1_NOTIFY, V1_WRITE) }
        chars(V0_SERVICE)?.let { if (V0_NOTIFY in it && V0_WRITE in it) return GarminTransport(GarminTransportKind.V0, V0_SERVICE, V0_NOTIFY, V0_WRITE) }
        return null
    }

    /** One line describing what was found, for the always-on "unsupported watch protocol" diagnostic. */
    fun describe(services: List<GarminGattService>): String = services.joinToString(" ") { s ->
        normalize(s.uuid).take(8) + "[" + s.characteristics.joinToString(",") { normalize(it).take(8) } + "]"
    }
}

object GarminAdvertisement {
    /** Bluetooth SIG company identifier assigned to Garmin (0x0087). */
    const val COMPANY_ID: Int = 0x0087

    /** [manufacturerData] includes the leading little-endian company id, as CoreBluetooth reports it. */
    fun isGarmin(manufacturerData: ByteArray?): Boolean {
        val d = manufacturerData ?: return false
        if (d.size < 2) return false
        return ((d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)) == COMPANY_ID
    }
}
