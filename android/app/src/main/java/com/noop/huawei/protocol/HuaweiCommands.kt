package com.noop.huawei.protocol

// MARK: - The send allowlist
//
// HuaweiRequest is the ONLY thing a HuaweiLink sends, and it can only be built for a HuaweiCommand. A
// command with no entry here cannot be sent. Every entry is in Device Config (service 0x01) and is a
// read, a pairing step, or the session handshake.
//
// Deliberately ABSENT, and not to be added (BLE safety contract, docs/CONTRIBUTING.md):
//   - factory reset 01/0D;
//   - the OTA service 0x09 (firmware update);
//   - file upload service 0x28, watchface service 0x27, app service 0x2A;
//   - ephemeris uploads (services 0x1F and 0x1C);
//   - debug / raw packets: there is no case taking a caller-supplied service or command id.
// HuaweiOutboundAllowlistTest enumerates every entry and holds this list.
//
// First pairing: Bond (01/0E) and HiChain operation 1 (01/28 with tag 2 = 1) install a new key on the
// band. They are built only with a HuaweiFirstPairingGrant, which the pairing wizard obtains through an
// explicitly named entry point. The reconnect path never holds one.

/** The commands NOOP may send. [encrypted]/[sliced] are how each is framed on the wire. */
enum class HuaweiCommand(val serviceId: Int, val commandId: Int, val encrypted: Boolean, val sliced: Boolean) {
    LINK_PARAMS(0x01, 0x01, encrypted = false, sliced = false),
    SUPPORTED_SERVICES(0x01, 0x02, encrypted = true, sliced = true),
    SUPPORTED_COMMANDS(0x01, 0x03, encrypted = true, sliced = true),
    PRODUCT_INFO(0x01, 0x07, encrypted = true, sliced = true),
    BATTERY_LEVEL(0x01, 0x08, encrypted = true, sliced = true),
    BOND(0x01, 0x0E, encrypted = false, sliced = true),
    BOND_PARAMS(0x01, 0x0F, encrypted = false, sliced = true),
    AUTH(0x01, 0x13, encrypted = false, sliced = true),
    HICHAIN(0x01, 0x28, encrypted = false, sliced = true),
    PIN_CODE(0x01, 0x2C, encrypted = false, sliced = true),
    SETTING_RELATED(0x01, 0x31, encrypted = true, sliced = true),
    SECURITY_NEGOTIATION(0x01, 0x33, encrypted = false, sliced = true),
    EXPAND_CAPABILITIES(0x01, 0x37, encrypted = true, sliced = true),
}

/** Inbound-only message ids (pushes the band sends unprompted). */
object HuaweiInbound {
    const val SERVICE_DEVICE_CONFIG = 0x01
    const val BATTERY_LEVEL_CHANGED = 0x27
}

/**
 * Permission to send the commands that install a key on the band (Bond, HiChain operation 1). Only the
 * first-pairing wizard asks for one; reconnects never do.
 */
class HuaweiFirstPairingGrant private constructor() {
    companion object {
        /** The explicit first-pairing entry point. Call only from the pairing wizard, after the user asked to pair. */
        fun forPairingWizard(): HuaweiFirstPairingGrant = HuaweiFirstPairingGrant()
    }
}

/** One outbound message: an allowlisted command and its plaintext TLV. */
class HuaweiRequest private constructor(val command: HuaweiCommand, tlv: HuaweiTlv) {
    private val body = tlv.copy()

    val tlv: HuaweiTlv get() = body.copy()

    val serviceId: Int get() = command.serviceId
    val commandId: Int get() = command.commandId
    val encrypted: Boolean get() = command.encrypted

    override fun toString(): String = "HuaweiRequest($command, $body)"

    companion object {
        /** HiChain operation code tag and the value that starts a first-pairing bind. */
        const val HICHAIN_TAG_OPERATION = 0x02
        const val HICHAIN_OP_AUTHENTICATE = 1
        const val HICHAIN_OP_BIND = 2

        /** Any allowlisted request that does not install a key. */
        fun of(command: HuaweiCommand, tlv: HuaweiTlv): HuaweiRequest {
            require(!needsFirstPairing(command, tlv)) { "$command with this payload needs a first-pairing grant" }
            return HuaweiRequest(command, tlv)
        }

        /** A key-installing request. The grant is the proof that the user started a first pairing. */
        @Suppress("UNUSED_PARAMETER")
        fun firstPairing(command: HuaweiCommand, tlv: HuaweiTlv, grant: HuaweiFirstPairingGrant): HuaweiRequest {
            require(needsFirstPairing(command, tlv)) { "$command does not need a first-pairing grant" }
            return HuaweiRequest(command, tlv)
        }

        fun needsFirstPairing(command: HuaweiCommand, tlv: HuaweiTlv): Boolean = when (command) {
            HuaweiCommand.BOND -> true
            // Any operation tag that is not plainly operation 2 counts, so a duplicate or empty tag cannot slip through.
            HuaweiCommand.HICHAIN -> tlv.entries.any {
                it.tag == HICHAIN_TAG_OPERATION && it.value.firstOrNull()?.toInt() != HICHAIN_OP_BIND
            }
            else -> false
        }
    }
}
