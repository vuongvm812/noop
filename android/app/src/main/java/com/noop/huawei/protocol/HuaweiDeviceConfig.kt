package com.noop.huawei.protocol

// Device Config (service 0x01) capability and information packets.
//
// Protocol facts (re-derived, no code copied) from Gadgetbridge `devices/huawei/packets/DeviceConfig.java`
// (AGPL-3.0, commit 6e4220682feb6917a5644304d9d7c760f30b6eaf). Every request here is a read; responses
// arrive decrypted (the transport opens the envelope before these decoders run).

/** LinkParams 01/01 response: the first, plaintext message of every connection. */
data class HuaweiLinkParams(
    val protocolVersion: Int,
    val sliceSize: Int,
    val mtu: Int,
    val interval: Int,
    val authVersion: Int,
    val serverNonce: ByteArray,
    val deviceSupportType: Int,
    val authAlgo: Int,
    val bondState: Int,
    val encryptMethod: Int,
) {
    override fun equals(other: Any?): Boolean = other is HuaweiLinkParams &&
        protocolVersion == other.protocolVersion && sliceSize == other.sliceSize && mtu == other.mtu &&
        interval == other.interval && authVersion == other.authVersion && serverNonce.contentEquals(other.serverNonce) &&
        deviceSupportType == other.deviceSupportType && authAlgo == other.authAlgo && bondState == other.bondState &&
        encryptMethod == other.encryptMethod

    override fun hashCode(): Int = listOf(protocolVersion, sliceSize, mtu, interval, authVersion,
        serverNonce.contentHashCode(), deviceSupportType, authAlgo, bondState, encryptMethod).hashCode()
}

/** ProductInfo 01/07 response fields NOOP reads. */
data class HuaweiProductInfo(
    val hardwareVersion: String?,
    val softwareVersion: String,
    val serialNumber: String?,
    val productModel: String,
    val deviceName: String?,
)

/** Battery 01/08 response and the 01/27 push, which shares its layout. */
data class HuaweiBattery(val level: Int, val multiLevel: List<Int>?, val status: List<Int>?)

/** ExpandCapabilities 01/37: a bitmap where bit n is `bytes[n / 8] & (1 << (n % 8))`. */
class HuaweiExpandCapabilities(bytes: ByteArray) {
    private val bits = bytes.copyOf()

    val raw: ByteArray get() = bits.copyOf()

    fun has(bit: Int): Boolean {
        if (bit < 0 || bit / 8 >= bits.size) return false
        return (bits[bit / 8].toInt() and (1 shl (bit % 8))) != 0
    }

    override fun equals(other: Any?): Boolean = other is HuaweiExpandCapabilities && other.bits.contentEquals(bits)
    override fun hashCode(): Int = bits.contentHashCode()
}

/** SettingRelated 01/31 tag 2 file-sync bits: &2 sleep (TruSleep), &4 RRI, &8 GPS. */
data class HuaweiSettingRelated(val truSleepFileSync: Boolean, val rriFileSync: Boolean, val gpsFileSync: Boolean)

object HuaweiDeviceConfig {

    object LinkParams {
        private const val DEFAULT_MTU = 0x14

        fun request(): HuaweiRequest =
            HuaweiRequest.of(HuaweiCommand.LINK_PARAMS, HuaweiTlv().put(0x01).put(0x02).put(0x03).put(0x04))

        /** Tag 5 (authVersion u16 + 16-byte server nonce) is required; the rest default as the band omits them. */
        fun decode(tlv: HuaweiTlv): HuaweiLinkParams {
            val auth = tlv.bytes(0x05)
            if (auth.size < 18) throw HuaweiProtocolException("LinkParams tag 5 has ${auth.size} bytes, expected 18")
            return HuaweiLinkParams(
                protocolVersion = if (tlv.contains(0x01)) tlv.byte(0x01) else 0,
                sliceSize = if (tlv.contains(0x02)) tlv.unsigned(0x02).toInt() else HuaweiFraming.DEFAULT_SLICE_SIZE,
                mtu = if (tlv.contains(0x03)) tlv.unsigned(0x03).toInt() else DEFAULT_MTU,
                interval = if (tlv.contains(0x04)) tlv.unsigned(0x04).toInt() else 0,
                authVersion = HuaweiBytes.readUnsigned(auth, 0, 2).toInt(),
                serverNonce = auth.copyOfRange(2, 18),
                deviceSupportType = if (tlv.contains(0x07)) tlv.byte(0x07) else 0,
                authAlgo = if (tlv.contains(0x08)) tlv.byte(0x08) else 0,
                bondState = if (tlv.contains(0x09)) tlv.byte(0x09) else 0,
                encryptMethod = if (tlv.contains(0x0C)) tlv.byte(0x0C) else 0,
            )
        }
    }

    object SupportedServices {
        /** Asks which of [services] the band supports. */
        fun request(services: List<Int>): HuaweiRequest =
            HuaweiRequest.of(HuaweiCommand.SUPPORTED_SERVICES, HuaweiTlv().put(0x01, ByteArray(services.size) { services[it].toByte() }))

        /** Tag 2 holds one byte per requested service, 1 = supported. */
        fun decode(tlv: HuaweiTlv, requested: List<Int>): Set<Int> {
            val flags = tlv.bytes(0x02)
            if (flags.size > requested.size) {
                throw HuaweiProtocolException("SupportedServices answered ${flags.size} flags for ${requested.size} services")
            }
            return flags.indices.filter { flags[it].toInt() == 1 }.map { requested[it] and 0xFF }.toSet()
        }
    }

    object SupportedCommands {
        /**
         * One request per group of services that fits a slice: `81{ 02 service, 03 commands … }`. The size
         * budget leaves room for the frame header, the 0x81 container, the envelope tags and AES padding.
         */
        fun requests(commandsPerService: Map<Int, ByteArray>, sliceSize: Int): List<HuaweiRequest> {
            var budget = sliceSize - 32
            budget = budget - (budget % 16) - 4
            val out = mutableListOf<HuaweiRequest>()
            var current = HuaweiTlv()
            for ((service, commands) in commandsPerService) {
                val cost = commands.size + 5
                if (cost > budget) throw IllegalArgumentException("service $service has too many commands for one request")
                if (current.encodedSize() + cost > budget) {
                    out.add(HuaweiRequest.of(HuaweiCommand.SUPPORTED_COMMANDS, HuaweiTlv().put(0x81, current)))
                    current = HuaweiTlv()
                }
                current.putByte(0x02, service).put(0x03, commands)
            }
            if (!current.isEmpty) out.add(HuaweiRequest.of(HuaweiCommand.SUPPORTED_COMMANDS, HuaweiTlv().put(0x81, current)))
            return out
        }

        /**
         * `81{ 02 service, 04 bitmap … }`: bitmap byte i is 1 when the i-th command asked for that service is
         * supported. Returns service → supported command ids.
         */
        fun decode(tlv: HuaweiTlv, commandsPerService: Map<Int, ByteArray>): Map<Int, Set<Int>> {
            val out = linkedMapOf<Int, Set<Int>>()
            var service: Int? = null
            for (entry in tlv.tlv(0x81).entries) {
                when (entry.tag) {
                    0x02 -> service = entry.value.firstOrNull()?.toInt()?.and(0xFF)
                        ?: throw HuaweiProtocolException("SupportedCommands service tag is empty")
                    0x04 -> {
                        val s = service ?: throw HuaweiProtocolException("SupportedCommands bitmap before a service")
                        val asked = commandsPerService[s] ?: throw HuaweiProtocolException("SupportedCommands answered unasked service $s")
                        val bitmap = entry.value
                        if (bitmap.size > asked.size) throw HuaweiProtocolException("SupportedCommands bitmap longer than the request for $s")
                        out[s] = bitmap.indices.filter { bitmap[it].toInt() == 1 }.map { asked[it].toInt() and 0xFF }.toSet()
                    }
                    else -> throw HuaweiProtocolException("SupportedCommands unknown tag 0x%02X".format(entry.tag))
                }
            }
            if (out.isEmpty()) throw HuaweiProtocolException("SupportedCommands list is empty")
            return out
        }
    }

    /**
     * Set the band's clock (01/05): `{01 unix seconds u32, 02 offset}`. The offset is two bytes, whole hours
     * (with 0x80 added when the zone is west of UTC) then minutes as Java's signed `offset / 60 % 60`, so a
     * negative half-hour zone sends its minutes byte two's-complement, as Gadgetbridge's
     * `HuaweiUtil.getTimeAndZoneId` does (AGPL-3.0, read for the layout only). Reversible: the next set, or
     * the band's own settings, replace it.
     */
    object SetTime {
        fun request(unixSeconds: Long, utcOffsetSeconds: Int): HuaweiRequest {
            val hours = if (utcOffsetSeconds < 0) -utcOffsetSeconds / 3600 + 128 else utcOffsetSeconds / 3600
            val minutes = utcOffsetSeconds / 60 % 60
            val offset = byteArrayOf(hours.toByte(), minutes.toByte())
            return HuaweiRequest.of(HuaweiCommand.SET_TIME, HuaweiTlv().putInt(0x01, unixSeconds.toInt()).put(0x02, offset))
        }
    }

    /**
     * The user's Huawei account id (AccountRelated 0x1A): 1A/05 `{01 id, [03 01]}` on a band that supports
     * 1A/05 and 1A/06, else 1A/01 `{01 id}`; tag 3 only when the band reports expand bit 0xAC. Gadgetbridge
     * `SendAccountRequest` / `SendExtendedAccountRequest` / `packets/AccountRelated` (AGPL-3.0, facts only).
     */
    object Account {
        const val EXPAND_DIFF_ACCOUNT_PAIRING = 0xAC

        fun request(accountId: String, extended: Boolean, pairingOptimisation: Boolean): HuaweiRequest {
            val t = HuaweiTlv().put(0x01, accountId.toByteArray(Charsets.US_ASCII))
            if (!extended) return HuaweiRequest.of(HuaweiCommand.ACCOUNT, t)
            if (pairingOptimisation) t.putByte(0x03, 1)
            return HuaweiRequest.of(HuaweiCommand.ACCOUNT_EXTENDED, t)
        }
    }

    object ProductInfo {
        /** The BLE tag set: hardware/software/serial/model/name and the reserved tags the band expects asked. */
        private val BLE_TAGS = intArrayOf(0x01, 0x02, 0x07, 0x09, 0x0A, 0x11, 0x12, 0x16, 0x1A, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23)

        fun request(): HuaweiRequest = HuaweiRequest.of(HuaweiCommand.PRODUCT_INFO, HuaweiTlv().also { t -> BLE_TAGS.forEach { t.put(it) } })

        /** Tags 7 (software version) and 0x0A (model, NUL/space padded) are required. */
        fun decode(tlv: HuaweiTlv): HuaweiProductInfo = HuaweiProductInfo(
            hardwareVersion = if (tlv.contains(0x03)) tlv.string(0x03) else null,
            softwareVersion = tlv.string(0x07),
            serialNumber = if (tlv.contains(0x09)) tlv.string(0x09) else null,
            productModel = tlv.string(0x0A).trim { it <= ' ' },
            deviceName = if (tlv.contains(0x11)) tlv.string(0x11) else null,
        )
    }

    object Battery {
        fun request(): HuaweiRequest = HuaweiRequest.of(HuaweiCommand.BATTERY_LEVEL, HuaweiTlv().put(0x01))

        /** Decodes both the 01/08 response and the 01/27 push. */
        fun decode(tlv: HuaweiTlv): HuaweiBattery = HuaweiBattery(
            level = tlv.byte(0x01),
            multiLevel = tlv.bytesOrNull(0x02)?.map { it.toInt() and 0xFF },
            status = tlv.bytesOrNull(0x03)?.map { it.toInt() and 0xFF },
        )
    }

    object ExpandCapabilities {
        fun request(): HuaweiRequest = HuaweiRequest.of(HuaweiCommand.EXPAND_CAPABILITIES, HuaweiTlv().put(0x01))

        fun decode(tlv: HuaweiTlv): HuaweiExpandCapabilities = HuaweiExpandCapabilities(tlv.bytesOrNull(0x01) ?: ByteArray(0))
    }

    object SettingRelated {
        fun request(): HuaweiRequest =
            HuaweiRequest.of(HuaweiCommand.SETTING_RELATED, HuaweiTlv().put(0x01).put(0x02).put(0x03).put(0x04).put(0x05).put(0x06))

        fun decode(tlv: HuaweiTlv): HuaweiSettingRelated {
            val bits = if (tlv.contains(0x02)) tlv.byte(0x02) else 0
            return HuaweiSettingRelated(
                truSleepFileSync = bits and 0x02 != 0,
                rriFileSync = bits and 0x04 != 0,
                gpsFileSync = bits and 0x08 != 0,
            )
        }
    }
}
