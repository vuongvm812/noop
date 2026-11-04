package com.noop.huawei.protocol

// MARK: - The send allowlist
//
// HuaweiRequest is the ONLY thing a HuaweiLink sends, and it can only be built for a HuaweiCommand. A
// command with no entry here cannot be sent. Every entry is a read, a pairing step, the session
// handshake, a band → phone file download, or one of the reversible writes listed below.
//
// Reversible writes (each can be undone on the band or by the next write): the three measurement switches
// (only ever "on"), the clock (01/05, the phone's time and UTC offset) and the user's own Huawei account id
// (1A/01, 1A/05, only the 17-digit id the user typed into the pairing wizard).
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
    /** Set the band's clock: `{01 unix seconds u32, 02 UTC offset hours|0x80 sign, minutes}`. */
    SET_TIME(0x01, 0x05, encrypted = true, sliced = true),
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

    // FitnessData (0x07): history reads and the three measurement switches.
    STEP_DATA_COUNT(0x07, 0x0A, encrypted = true, sliced = true),
    STEP_DATA(0x07, 0x0B, encrypted = true, sliced = true),
    SLEEP_DATA_COUNT(0x07, 0x0C, encrypted = true, sliced = true),
    SLEEP_DATA(0x07, 0x0D, encrypted = true, sliced = true),
    AUTO_HEART_RATE(0x07, 0x17, encrypted = true, sliced = true),
    RESTING_HEART_RATE(0x07, 0x23, encrypted = true, sliced = true),
    AUTO_SPO2(0x07, 0x24, encrypted = true, sliced = true),

    // Workout (0x17): reads.
    WORKOUT_COUNT(0x17, 0x07, encrypted = true, sliced = true),
    WORKOUT_TOTALS(0x17, 0x08, encrypted = true, sliced = true),
    WORKOUT_DATA(0x17, 0x0A, encrypted = true, sliced = true),
    WORKOUT_PACE(0x17, 0x0C, encrypted = true, sliced = true),
    WORKOUT_CAPABILITY(0x17, 0x15, encrypted = true, sliced = true),

    // File download, band → phone only ("old sync" 0x0A, "new sync" 0x2C). No upload service is listed.
    FILE0A_INIT(0x0A, 0x01, encrypted = true, sliced = true),
    FILE0A_PARAMS(0x0A, 0x02, encrypted = true, sliced = true),
    FILE0A_INFO(0x0A, 0x03, encrypted = true, sliced = true),
    FILE0A_BLOCK(0x0A, 0x04, encrypted = true, sliced = true),
    FILE0A_DONE(0x0A, 0x06, encrypted = true, sliced = true),
    FILE2C_INIT(0x2C, 0x01, encrypted = true, sliced = true),
    FILE2C_INFO(0x2C, 0x03, encrypted = true, sliced = true),
    FILE2C_BLOCK(0x2C, 0x04, encrypted = true, sliced = true),
    /** The block request when the band's 2C/03 reply says it cannot take it encrypted (tag 5). */
    FILE2C_BLOCK_PLAIN(0x2C, 0x04, encrypted = false, sliced = true),
    FILE2C_DONE(0x2C, 0x06, encrypted = true, sliced = true),

    // AccountRelated (0x1A): the optional account id, only as the 17 digits the user entered.
    ACCOUNT(0x1A, 0x01, encrypted = true, sliced = true),
    ACCOUNT_EXTENDED(0x1A, 0x05, encrypted = true, sliced = true),

    /** P2P (0x34/0x01), only between the packages in [HuaweiRequest.P2P_PACKAGES]. */
    P2P(0x34, 0x01, encrypted = true, sliced = true),
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

        /** The measurement switches; each may only be sent as `{01: on}`. */
        val SWITCHES = setOf(HuaweiCommand.AUTO_HEART_RATE, HuaweiCommand.AUTO_SPO2, HuaweiCommand.RESTING_HEART_RATE)

        /** The only P2P packages NOOP addresses: the phone-side health app, the dictionary sync module and the band's file sync. */
        val P2P_PACKAGES = setOf("com.huawei.health", "hw.unitedevice.datadictionarysync", "hw.watch.health.filesync")

        /** P2P command ids NOOP sends: 1 ping, 2 data, 3 ack. */
        val P2P_COMMANDS = setOf(1, 2, 3)

        /** Any allowlisted request that does not install a key. */
        fun of(command: HuaweiCommand, tlv: HuaweiTlv): HuaweiRequest {
            require(!needsFirstPairing(command, tlv)) { "$command with this payload needs a first-pairing grant" }
            require(command !in SWITCHES || isSwitchOn(tlv)) { "$command may only switch the measurement on" }
            require(command != HuaweiCommand.P2P || isDictionaryP2P(tlv)) { "P2P is limited to the data-dictionary sync" }
            require(command != HuaweiCommand.SET_TIME || isSetTime(tlv)) { "SET_TIME carries exactly a time and an offset" }
            require(command !in ACCOUNT_COMMANDS || isAccount(command, tlv)) { "$command carries only a 17-digit account id" }
            return HuaweiRequest(command, tlv)
        }

        /** Exactly one tag 1 holding a non-zero value of 1 or 4 bytes. */
        fun isSwitchOn(tlv: HuaweiTlv): Boolean {
            val e = tlv.entries.singleOrNull() ?: return false
            val v = e.value
            return e.tag == 0x01 && (v.size == 1 || v.size == 4) && v.any { it.toInt() != 0 }
        }

        val ACCOUNT_COMMANDS = setOf(HuaweiCommand.ACCOUNT, HuaweiCommand.ACCOUNT_EXTENDED)

        /** Exactly tag 1 (4 bytes) then tag 2 (2 bytes). */
        fun isSetTime(tlv: HuaweiTlv): Boolean {
            val e = tlv.entries
            return e.size == 2 && e[0].tag == 0x01 && e[0].value.size == 4 && e[1].tag == 0x02 && e[1].value.size == 2
        }

        /**
         * Tag 1 holding 17 ASCII digits; the extended form may add tag 3 = 1 (the band's "different account
         * pairing optimisation" flag). Never an empty or cleared account.
         */
        fun isAccount(command: HuaweiCommand, tlv: HuaweiTlv): Boolean {
            val e = tlv.entries
            val id = e.firstOrNull()?.takeIf { it.tag == 0x01 }?.value?.toString(Charsets.US_ASCII) ?: return false
            if (id.length != 17 || !id.all { it in '0'..'9' }) return false
            return when (e.size) {
                1 -> true
                2 -> command == HuaweiCommand.ACCOUNT_EXTENDED && e[1].tag == 0x03 && e[1].value.contentEquals(byteArrayOf(1))
                else -> false
            }
        }

        /** A ping, data or ack between two [P2P_PACKAGES], each tag at most once. */
        fun isDictionaryP2P(tlv: HuaweiTlv): Boolean {
            val tags = tlv.entries.map { it.tag }
            if (tags.size != tags.toSet().size) return false
            val cmd = tlv.bytesOrNull(0x01)?.singleOrNull()?.toInt() ?: return false
            val src = tlv.bytesOrNull(0x03)?.toString(Charsets.UTF_8) ?: return false
            val dst = tlv.bytesOrNull(0x04)?.toString(Charsets.UTF_8) ?: return false
            return cmd in P2P_COMMANDS && src in P2P_PACKAGES && dst in P2P_PACKAGES
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
