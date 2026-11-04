package com.noop.huawei.auth

import com.noop.huawei.protocol.HuaweiHex
import com.noop.huawei.protocol.HuaweiLinkConfig
import com.noop.huawei.protocol.HuaweiLinkParams
import com.noop.huawei.protocol.HuaweiRandom

/**
 * How the band authenticates, from LinkParams deviceSupportType (tag 7) and, for 1..4, the
 * SecurityNegotiation authType: 0 normal; 1/3 HiChain; 2 HiChain Lite; 4 HiChain3 (HiChain with
 * 16-byte random IVs and GCM).
 */
enum class HuaweiAuthMode {
    NORMAL,
    HICHAIN,
    HICHAIN_LITE,
    HICHAIN3;

    val isHiChainFamily: Boolean get() = this == HICHAIN || this == HICHAIN3
}

/** Which request the machine was waiting on when something went wrong. */
enum class HuaweiAuthStage {
    LINK_PARAMS,
    SECURITY_NEGOTIATION,
    PIN_CODE,
    HICHAIN_AUTHENTICATE,
    HICHAIN_BIND,
    AUTH,
    BOND_PARAMS,
    BOND,
}

/**
 * NOOP's identity toward one band. Generated once at first pairing and persisted with the bond: the band
 * remembers it. [androidId] is a random hex id (the HiChain auth id and SecurityNegotiation device id);
 * [phoneAddress] is a random `FF:FF:FF:xx:xx:xx` address whose last six hex digits are the bond serial.
 * Neither is the phone's real identifier.
 */
data class HuaweiPhoneIdentity(val androidId: String, val phoneAddress: String, val phoneModel: String) {
    init {
        require(phoneAddress.replace(":", "").length >= 12) { "phone address must hold 12 hex digits" }
    }

    val authId: ByteArray get() = androidId.toByteArray(Charsets.UTF_8)

    val addressBytes: ByteArray get() = phoneAddress.toByteArray(Charsets.UTF_8)

    val serial: ByteArray get() = phoneAddress.replace(":", "").substring(6, 12).toByteArray(Charsets.UTF_8)

    companion object {
        /** [phoneModel] is sent to HiChain3 bands only; NOOP passes a neutral label, not the device model. */
        fun generate(random: HuaweiRandom = HuaweiRandom.secure, phoneModel: String = "NOOP"): HuaweiPhoneIdentity {
            val tail = random.nextBytes(3).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            return HuaweiPhoneIdentity(HuaweiHex.encode(random.nextBytes(16)), "FF:FF:FF:$tail", phoneModel)
        }
    }
}

/**
 * What a successful pairing leaves to persist; a reconnect needs it back. [secret] is the 16-byte session
 * key for normal and Lite bands, or the HiChain auth token the band issued during operation 1.
 */
class HuaweiBondRecord(val mode: HuaweiAuthMode, secret: ByteArray, pinCode: ByteArray?) {
    private val secretBytes = secret.copyOf()
    private val pinBytes = pinCode?.copyOf()

    val secret: ByteArray get() = secretBytes.copyOf()
    val pinCode: ByteArray? get() = pinBytes?.copyOf()

    override fun equals(other: Any?): Boolean = other is HuaweiBondRecord && other.mode == mode &&
        other.secretBytes.contentEquals(secretBytes) && other.pinBytes.contentEquals(pinBytes)

    override fun hashCode(): Int = (mode.hashCode() * 31 + secretBytes.contentHashCode()) * 31 + (pinBytes?.contentHashCode() ?: 0)

    override fun toString(): String = "HuaweiBondRecord($mode, secret=${secretBytes.size} bytes, pin=${pinBytes != null})"
}

/** A finished authentication: the transport state to install and the bond to persist. */
class HuaweiAuthResult(
    val mode: HuaweiAuthMode,
    val linkParams: HuaweiLinkParams,
    val linkConfig: HuaweiLinkConfig,
    val bond: HuaweiBondRecord,
    /** True when this session installed a key on the band (Bond sent, or HiChain operation 1 run). */
    val installedKey: Boolean,
)

sealed class HuaweiAuthError {
    /** The band answered with a 0x7F result other than 0x000186A0. */
    data class DeviceError(val stage: HuaweiAuthStage, val code: Long) : HuaweiAuthError()

    /** The band's proof (Auth digest, HiChain token, return-code MAC or GCM tag) did not verify. */
    data class MacMismatch(val stage: HuaweiAuthStage) : HuaweiAuthError()

    data class Timeout(val stage: HuaweiAuthStage) : HuaweiAuthError()

    /** deviceSupportType or SecurityNegotiation authType is not one NOOP knows. */
    data class UnknownMode(val deviceSupportType: Int, val authType: Int?) : HuaweiAuthError()

    /** A reconnect cannot proceed without installing a key: the user must pair again from the wizard. */
    data class NeedsFirstPairing(val reason: String) : HuaweiAuthError()

    /** A HiChain JSON payload carried a non-zero errorCode. */
    data class HiChainError(val stage: HuaweiAuthStage, val errorCode: Int) : HuaweiAuthError()

    /** A malformed or unexpected message. */
    data class Protocol(val stage: HuaweiAuthStage?, val message: String) : HuaweiAuthError()
}

sealed interface HuaweiAuthOutcome {
    data class Success(val result: HuaweiAuthResult) : HuaweiAuthOutcome
    data class Failure(val error: HuaweiAuthError) : HuaweiAuthOutcome
}
