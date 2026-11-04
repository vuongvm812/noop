package com.noop.huawei.auth

import com.noop.huawei.protocol.HuaweiBytes
import com.noop.huawei.protocol.HuaweiCipher
import com.noop.huawei.protocol.HuaweiCommand
import com.noop.huawei.protocol.HuaweiCrypto
import com.noop.huawei.protocol.HuaweiCryptoException
import com.noop.huawei.protocol.HuaweiDeviceConfig
import com.noop.huawei.protocol.HuaweiFirstPairingGrant
import com.noop.huawei.protocol.HuaweiFraming
import com.noop.huawei.protocol.HuaweiHex
import com.noop.huawei.protocol.HuaweiIvSource
import com.noop.huawei.protocol.HuaweiLink
import com.noop.huawei.protocol.HuaweiLinkConfig
import com.noop.huawei.protocol.HuaweiLinkParams
import com.noop.huawei.protocol.HuaweiProtocolException
import com.noop.huawei.protocol.HuaweiRandom
import com.noop.huawei.protocol.HuaweiRequest
import com.noop.huawei.protocol.HuaweiTlv
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

// The Huawei pairing / reconnect handshake as a pure state machine.
//
// Protocol facts (re-derived, no code copied) from Gadgetbridge `service/devices/huawei/
// HuaweiSupportProvider.java` (initializeDevice … initializeDeviceNormalMode), `requests/Get*Request.java`
// and `devices/huawei/packets/DeviceConfig.java` (AGPL-3.0, commit 6e4220682feb6917a5644304d9d7c760f30b6eaf).
// Every message is service 0x01 and plaintext:
//
//   LinkParams 01/01 → deviceSupportType 0 → normal:  Auth 01/13 → BondParams 01/0F → [Bond 01/0E]
//                    → 1..4 → SecurityNegotiation 01/33 → authType 0x186A0 or 5/6 → HiChain:
//                                 first pair: PinCode 01/2C → HiChain 01/28 op1 (4 steps) → op2 (bind)
//                                 reconnect:  HiChain op2 only
//                             → authType 1/2 → HiChain Lite: [PinCode] → Auth → BondParams → [Bond]
//
// The band's mode is decided by the band, so all four modes are implemented. Bond and HiChain op1 need a
// HuaweiFirstPairingGrant, which only firstPairing() holds; a reconnect that would need one fails with
// NeedsFirstPairing instead. Randomness and the clock (HiChain request id) are injected.

class HuaweiAuthMachine private constructor(
    private val grant: HuaweiFirstPairingGrant?,
    private val identity: HuaweiPhoneIdentity,
    private val bandAddress: String,
    private val stored: HuaweiBondRecord?,
    private val random: HuaweiRandom,
    private val clock: () -> Long,
) {
    sealed interface Step {
        data class Send(val request: HuaweiRequest, val timeoutMillis: Long) : Step
        data class Done(val outcome: HuaweiAuthOutcome) : Step
    }

    /** The transport state the driver must apply before the next send: slice size from LinkParams, cipher once done. */
    var linkConfig: HuaweiLinkConfig = HuaweiLinkConfig()
        private set

    val isFirstPairing: Boolean get() = grant != null

    /** The request a reply is awaited for, or null before start and after the end. */
    var awaiting: HuaweiAuthStage? = null
        private set

    private var started = false

    private lateinit var link: HuaweiLinkParams
    private var mode: HuaweiAuthMode? = null

    /** The value sent as SecurityNegotiation tag 1 and used by the digest: 0 normal, 2 HiChain/Lite, 4 HiChain3. */
    private var authMode = 0

    // Normal / Lite state.
    private var sessionSecret: ByteArray? = null
    private var pinCode: ByteArray? = if (grant == null) stored?.pinCode else null
    private var doubleNonce: ByteArray? = null
    private var firstKey: ByteArray? = null
    private var ivSource: HuaweiIvSource? = null

    // HiChain state.
    private var hiChain: HiChainOperation? = null
    private var authToken: ByteArray? = null
    private var ranOperation1 = false

    fun start(): Step {
        check(!started) { "machine already started" }
        started = true
        return send(HuaweiAuthStage.LINK_PARAMS, HuaweiDeviceConfig.LinkParams.request())
    }

    fun onResponse(tlv: HuaweiTlv): Step {
        val stage = checkNotNull(awaiting) { "no request is awaiting a reply" }
        awaiting = null
        return try {
            val code = tlv.resultCode()
            if (code != null && code != HuaweiTlv.RESULT_OK) return fail(HuaweiAuthError.DeviceError(stage, code))
            handle(stage, tlv)
        } catch (e: HuaweiCryptoException) {
            fail(HuaweiAuthError.Protocol(stage, e.message ?: "crypto failure"))
        } catch (e: HuaweiProtocolException) {
            fail(HuaweiAuthError.Protocol(stage, e.message ?: "protocol error"))
        } catch (e: JSONException) {
            fail(HuaweiAuthError.Protocol(stage, "bad HiChain JSON: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            fail(HuaweiAuthError.Protocol(stage, e.message ?: "bad value"))
        }
    }

    fun onTimeout(): Step {
        val stage = checkNotNull(awaiting) { "no request is awaiting a reply" }
        awaiting = null
        return fail(HuaweiAuthError.Timeout(stage))
    }

    fun onLinkError(error: Throwable): Step {
        val stage = awaiting
        awaiting = null
        return fail(HuaweiAuthError.Protocol(stage, error.message ?: error.javaClass.simpleName))
    }

    private fun handle(stage: HuaweiAuthStage, tlv: HuaweiTlv): Step = when (stage) {
        HuaweiAuthStage.LINK_PARAMS -> onLinkParams(tlv)
        HuaweiAuthStage.SECURITY_NEGOTIATION -> onSecurityNegotiation(tlv)
        HuaweiAuthStage.PIN_CODE -> onPinCode(tlv)
        HuaweiAuthStage.AUTH -> onAuth(tlv)
        HuaweiAuthStage.BOND_PARAMS -> onBondParams(tlv)
        HuaweiAuthStage.BOND -> finishSessionKey(installedKey = true)
        HuaweiAuthStage.HICHAIN_AUTHENTICATE, HuaweiAuthStage.HICHAIN_BIND -> onHiChain(tlv)
    }

    // MARK: - LinkParams and mode selection

    private fun onLinkParams(tlv: HuaweiTlv): Step {
        link = HuaweiDeviceConfig.LinkParams.decode(tlv)
        if (link.sliceSize < HuaweiFraming.MIN_SLICE_SIZE) {
            return fail(HuaweiAuthError.Protocol(HuaweiAuthStage.LINK_PARAMS, "slice size ${link.sliceSize}"))
        }
        linkConfig = linkConfig.copy(sliceSize = link.sliceSize)
        return when (link.deviceSupportType) {
            0 -> beginDigestAuth(HuaweiAuthMode.NORMAL)
            1, 2, 3, 4 -> {
                authMode = if (link.deviceSupportType == 4) 4 else 2
                send(HuaweiAuthStage.SECURITY_NEGOTIATION, securityNegotiationRequest())
            }
            else -> fail(HuaweiAuthError.UnknownMode(link.deviceSupportType, null))
        }
    }

    private fun securityNegotiationRequest(): HuaweiRequest = HuaweiRequest.of(
        HuaweiCommand.SECURITY_NEGOTIATION,
        HuaweiAuthPackets.securityNegotiation(authMode, identity.authId, identity.phoneModel, link.encryptMethod),
    )

    private fun onSecurityNegotiation(tlv: HuaweiTlv): Step {
        val authType = negotiatedAuthType(tlv)
        return when {
            authType == AUTH_TYPE_HICHAIN || isHiChain3(authType) ->
                beginHiChain(if (link.deviceSupportType == 4) HuaweiAuthMode.HICHAIN3 else HuaweiAuthMode.HICHAIN)
            authType == 1 || authType == 2 -> beginDigestAuth(HuaweiAuthMode.HICHAIN_LITE)
            else -> fail(HuaweiAuthError.UnknownMode(link.deviceSupportType, authType))
        }
    }

    /** Tag 1 == 1 means HiChain; tag 1 == 4 marks HiChain3, whose tag 2 is XORed with 4. */
    private fun negotiatedAuthType(tlv: HuaweiTlv): Int {
        var authType = -1
        var hiChain3Mask = -1
        if (tlv.contains(0x01)) {
            if (tlv.byte(0x01) == 0x01) authType = AUTH_TYPE_HICHAIN
            if (tlv.byte(0x01) == 0x04) hiChain3Mask = 4
        }
        if (tlv.contains(0x02)) {
            authType = tlv.byte(0x02)
            if (hiChain3Mask != -1) authType = authType xor hiChain3Mask
        }
        if (tlv.contains(HuaweiTlv.TAG_RESULT)) authType = tlv.byte(HuaweiTlv.TAG_RESULT)
        return authType
    }

    private fun isHiChain3(authType: Int): Boolean = (authType xor 0x01) == 0x04 || (authType xor 0x02) == 0x04

    // MARK: - Normal and HiChain Lite

    private fun beginDigestAuth(m: HuaweiAuthMode): Step {
        mode = m
        val reuse = stored?.takeIf { it.mode == m }?.secret?.takeIf { it.size == 16 }
        sessionSecret = when {
            reuse != null -> reuse
            isFirstPairing -> random.nextBytes(16)
            else -> return fail(HuaweiAuthError.NeedsFirstPairing("no stored $m bond"))
        }
        return if (m == HuaweiAuthMode.HICHAIN_LITE && pinCode == null && link.authVersion != 0x02) {
            send(HuaweiAuthStage.PIN_CODE, pinCodeRequest())
        } else {
            sendAuth()
        }
    }

    private fun pinCodeRequest(): HuaweiRequest = HuaweiRequest.of(HuaweiCommand.PIN_CODE, HuaweiAuthPackets.pinCode())

    private fun onPinCode(tlv: HuaweiTlv): Step {
        pinCode = HuaweiAuthCrypto.decryptPinCode(link.authVersion, link.encryptMethod, tlv.bytes(0x01), tlv.bytes(0x02))
        return if (mode == HuaweiAuthMode.HICHAIN_LITE) sendAuth() else beginOperation(1)
    }

    private fun digestKey(): ByteArray? = when {
        authMode != HuaweiAuthCrypto.AUTH_MODE_LITE -> null
        link.authVersion == 0x02 -> sessionSecret
        else -> pinCode
    }

    private fun sendAuth(): Step {
        val clientNonce = random.nextBytes(16)
        val nonce = link.serverNonce + clientNonce
        doubleNonce = nonce
        val digest = HuaweiAuthCrypto.digestChallenge(link.authVersion, link.authAlgo, authMode, digestKey(), nonce)
        firstKey = digest.copyOfRange(32, 48)
        val t = HuaweiAuthPackets.auth(
            digest.copyOfRange(0, 32),
            HuaweiBytes.u16(link.authVersion) + clientNonce,
            if (authMode == HuaweiAuthCrypto.AUTH_MODE_LITE) link.authAlgo else null,
        )
        return send(HuaweiAuthStage.AUTH, HuaweiRequest.of(HuaweiCommand.AUTH, t))
    }

    private fun onAuth(tlv: HuaweiTlv): Step {
        val expected = HuaweiAuthCrypto.digestResponse(link.authVersion, link.authAlgo, authMode, digestKey(), checkNotNull(doubleNonce))
            .copyOfRange(0, 32)
        if (!MessageDigest.isEqual(expected, tlv.bytes(0x01))) return fail(HuaweiAuthError.MacMismatch(HuaweiAuthStage.AUTH))
        val t = HuaweiAuthPackets.bondParams(identity.serial, identity.addressBytes)
        return send(HuaweiAuthStage.BOND_PARAMS, HuaweiRequest.of(HuaweiCommand.BOND_PARAMS, t))
    }

    private fun onBondParams(tlv: HuaweiTlv): Step {
        val status = tlv.byte(0x01)
        ivSource = HuaweiCipher.ivSourceFor(link.deviceSupportType, tlv.u32(0x09), random)
        val lite = mode == HuaweiAuthMode.HICHAIN_LITE
        // Normal bands report status 1 when already bonded; Lite bands are bonded every pairing.
        val bondWanted = lite || status != 0x01
        val g = grant
        return when {
            !bondWanted -> finishSessionKey(installedKey = false)
            g != null -> send(HuaweiAuthStage.BOND, bondRequest(g))
            lite -> finishSessionKey(installedKey = false)
            else -> fail(HuaweiAuthError.NeedsFirstPairing("band reports no bond (BondParams status $status)"))
        }
    }

    private fun bondRequest(g: HuaweiFirstPairingGrant): HuaweiRequest {
        val iv = checkNotNull(ivSource).nextIv()
        val encryptionKey = if (authMode == HuaweiAuthCrypto.AUTH_MODE_LITE) {
            checkNotNull(firstKey)
        } else {
            HuaweiAuthCrypto.bondEncryptionKey(link.authVersion, bandAddress)
        }
        val key = HuaweiAuthCrypto.encryptBondingKey(link.encryptMethod, checkNotNull(sessionSecret), encryptionKey, iv)
        return HuaweiRequest.firstPairing(HuaweiCommand.BOND, HuaweiAuthPackets.bond(identity.serial, key, iv), g)
    }

    private fun finishSessionKey(installedKey: Boolean): Step {
        val secret = checkNotNull(sessionSecret)
        val m = checkNotNull(mode)
        val source = ivSource ?: HuaweiCipher.ivSourceFor(link.deviceSupportType, 0, random)
        val cipher = HuaweiCipher(secret, HuaweiCipher.usesGcm(link.encryptMethod, link.deviceSupportType), source)
        return succeed(m, cipher, HuaweiBondRecord(m, secret, pinCode), installedKey)
    }

    // MARK: - HiChain

    private fun beginHiChain(m: HuaweiAuthMode): Step {
        mode = m
        if (isFirstPairing) return send(HuaweiAuthStage.PIN_CODE, pinCodeRequest())
        val token = stored?.takeIf { it.mode.isHiChainFamily }?.secret
            ?: return fail(HuaweiAuthError.NeedsFirstPairing("no stored HiChain bond"))
        authToken = token
        return beginOperation(2)
    }

    /** One HiChain operation: 1 authenticates with the PIN and yields the auth token, 2 binds and yields the packet key. */
    private inner class HiChainOperation(val operation: Int, val pskKey: ByteArray) {
        val requestId: Long = clock()
        val seed: ByteArray = random.nextBytes(32)
        val randSelf: ByteArray = random.nextBytes(16)
        var expectedStep = 1
        var psk: ByteArray = ByteArray(0)
        var randPeer: ByteArray = ByteArray(0)
        var authIdPeer: ByteArray = ByteArray(0)
        var sessionKey: ByteArray = ByteArray(0)
        var challenge: ByteArray = ByteArray(0)

        val stage: HuaweiAuthStage get() = if (operation == 1) HuaweiAuthStage.HICHAIN_AUTHENTICATE else HuaweiAuthStage.HICHAIN_BIND
    }

    private fun beginOperation(operation: Int): Step {
        if (operation == 1) ranOperation1 = true
        val key = HuaweiHiChainCrypto.pskKey(pinCode, authToken, operation)
        val op = HiChainOperation(operation, key)
        hiChain = op
        val payload = linkedMapOf<String, Any>(
            "isoSalt" to HuaweiHex.encode(op.randSelf),
            "peerAuthId" to HuaweiHex.encode(identity.authId),
            "operationCode" to operation,
            "seed" to HuaweiHex.encode(op.seed),
            "peerUserType" to 0,
        )
        if (operation == 2) {
            payload["pkgName"] = HuaweiVendorConstants.HICHAIN_PACKAGE_BIND
            payload["serviceType"] = HuaweiVendorConstants.GROUP_ID
            payload["keyLength"] = 0x20
        }
        return sendHiChain(op, messageStep = 1, payload)
    }

    private fun onHiChain(tlv: HuaweiTlv): Step {
        val op = checkNotNull(hiChain)
        if (!tlv.contains(0x04)) {
            return fail(HuaweiAuthError.Protocol(op.stage, "HiChain reply without tag 4 (Honor PAKE framing is not supported)"))
        }
        val payload: JSONObject?
        val step: Int
        if (tlv.byte(0x04) == 0x00) {
            payload = JSONObject(tlv.string(0x01)).getJSONObject("payload")
            if (payload.has("errorCode")) {
                val code = payload.getInt("errorCode")
                if (code != 0) return fail(HuaweiAuthError.HiChainError(op.stage, code))
            }
            step = when {
                payload.has("isoSalt") -> 1
                payload.has("returnCodeMac") -> 2
                payload.has("encAuthToken") -> 3
                else -> return fail(HuaweiAuthError.Protocol(op.stage, "HiChain reply with no known step"))
            }
        } else {
            payload = null
            step = 4
        }
        if (step != op.expectedStep) {
            return fail(HuaweiAuthError.Protocol(op.stage, "HiChain step $step, expected ${op.expectedStep}"))
        }
        return when (step) {
            1 -> onHiChainStep1(op, checkNotNull(payload))
            2 -> onHiChainStep2(op, checkNotNull(payload))
            3 -> onHiChainStep3(op, checkNotNull(payload))
            else -> onHiChainStep4(op)
        }
    }

    private fun onHiChainStep1(op: HiChainOperation, payload: JSONObject): Step {
        op.authIdPeer = HuaweiHex.decode(payload.getString("peerAuthId"))
        op.randPeer = HuaweiHex.decode(payload.getString("isoSalt"))
        val peerToken = HuaweiHex.decode(payload.getString("token"))
        op.psk = HuaweiHiChainCrypto.psk(op.pskKey, op.seed)
        val check = HuaweiHiChainCrypto.peerToken(op.psk, op.randSelf, op.randPeer, identity.authId, op.authIdPeer)
        if (!MessageDigest.isEqual(check, peerToken)) return fail(HuaweiAuthError.MacMismatch(op.stage))
        val token = HuaweiHiChainCrypto.selfToken(op.psk, op.randSelf, op.randPeer, identity.authId, op.authIdPeer)
        op.expectedStep = 2
        return sendHiChain(
            op,
            messageStep = 2,
            linkedMapOf("peerAuthId" to HuaweiHex.encode(identity.authId), "token" to HuaweiHex.encode(token)),
        )
    }

    private fun onHiChainStep2(op: HiChainOperation, payload: JSONObject): Step {
        val mac = HuaweiHex.decode(payload.getString("returnCodeMac"))
        if (!MessageDigest.isEqual(HuaweiHiChainCrypto.returnCodeMac(op.psk), mac)) return fail(HuaweiAuthError.MacMismatch(op.stage))
        op.sessionKey = HuaweiHiChainCrypto.sessionKey(op.psk, op.randSelf, op.randPeer)
        if (op.operation == 1) {
            val nonce = random.nextBytes(12)
            op.challenge = random.nextBytes(16)
            val aad = HuaweiVendorConstants.AAD_ISO_EXCHANGE.toByteArray(Charsets.UTF_8)
            val encData = HuaweiCrypto.aesGcmEncrypt(op.sessionKey, nonce, op.challenge, aad)
            op.expectedStep = 3
            return sendHiChain(op, messageStep = 3, linkedMapOf("nonce" to HuaweiHex.encode(nonce), "encData" to HuaweiHex.encode(encData)))
        }
        // Operation 2 has no exchange step: the result goes out under message 3.
        return sendResult(op, messageStep = 3)
    }

    private fun onHiChainStep3(op: HiChainOperation, payload: JSONObject): Step {
        val nonce = HuaweiHex.decode(payload.getString("nonce"))
        val enc = HuaweiHex.decode(payload.getString("encAuthToken"))
        authToken = try {
            HuaweiCrypto.aesGcmDecrypt(op.sessionKey, nonce, enc, op.challenge)
        } catch (e: HuaweiCryptoException) {
            return fail(HuaweiAuthError.MacMismatch(op.stage))
        }
        return sendResult(op, messageStep = 4)
    }

    private fun sendResult(op: HiChainOperation, messageStep: Int): Step {
        val nonce = random.nextBytes(12)
        val aad = HuaweiVendorConstants.AAD_ISO_RESULT.toByteArray(Charsets.UTF_8)
        val encResult = HuaweiCrypto.aesGcmEncrypt(op.sessionKey, nonce, ByteArray(4), aad)
        op.expectedStep = 4
        return sendHiChain(
            op,
            messageStep,
            linkedMapOf("nonce" to HuaweiHex.encode(nonce), "encResult" to HuaweiHex.encode(encResult), "operationCode" to op.operation),
        )
    }

    private fun onHiChainStep4(op: HiChainOperation): Step {
        if (op.operation == 1) return beginOperation(2)
        val key = HuaweiHiChainCrypto.returnKey(op.sessionKey, op.randSelf, op.randPeer)
        val m = checkNotNull(mode)
        val cipher = HuaweiCipher(
            key,
            HuaweiCipher.usesGcm(link.encryptMethod, link.deviceSupportType),
            HuaweiCipher.ivSourceFor(link.deviceSupportType, 0, random),
        )
        return succeed(m, cipher, HuaweiBondRecord(m, checkNotNull(authToken), null), installedKey = ranOperation1)
    }

    private fun sendHiChain(op: HiChainOperation, messageStep: Int, fields: LinkedHashMap<String, Any>): Step {
        val payload = linkedMapOf<String, Any>(
            "version" to linkedMapOf<String, Any>(
                "minVersion" to HuaweiVendorConstants.HICHAIN_MIN_VERSION,
                "currentVersion" to HuaweiVendorConstants.HICHAIN_CURRENT_VERSION,
            ),
        )
        payload.putAll(fields)
        val value = linkedMapOf<String, Any>(
            "authForm" to 0,
            "payload" to payload,
            "groupAndModuleVersion" to HuaweiVendorConstants.HICHAIN_GROUP_MODULE_VERSION,
            "message" to if (op.operation == 2) messageStep or 0x10 else messageStep,
        )
        if (op.operation == 1) {
            value["requestId"] = op.requestId.toString()
            value["groupId"] = HuaweiVendorConstants.GROUP_ID
            value["groupName"] = HuaweiVendorConstants.HICHAIN_GROUP_NAME
            value["groupOp"] = 2
            value["groupType"] = 256
            value["peerDeviceId"] = identity.androidId
            value["connDeviceId"] = identity.androidId
            value["appId"] = HuaweiVendorConstants.HICHAIN_APP_ID
            value["ownerName"] = ""
        } else if (messageStep <= 2) {
            value["isDeviceLevel"] = false
        }
        val t = HuaweiTlv()
            .putString(0x01, HuaweiJson.write(value))
            .putByte(0x02, op.operation)
            .putLong(0x03, op.requestId)
        val request = if (op.operation == 1) {
            HuaweiRequest.firstPairing(HuaweiCommand.HICHAIN, t, checkNotNull(grant) { "HiChain operation 1 without a grant" })
        } else {
            HuaweiRequest.of(HuaweiCommand.HICHAIN, t)
        }
        return send(op.stage, request, if (op.operation == 1) FIRST_PAIR_TIMEOUT_MS else DEFAULT_TIMEOUT_MS)
    }

    // MARK: - Plumbing

    private fun send(stage: HuaweiAuthStage, request: HuaweiRequest, timeout: Long = DEFAULT_TIMEOUT_MS): Step {
        awaiting = stage
        return Step.Send(request, timeout)
    }

    private fun succeed(m: HuaweiAuthMode, cipher: HuaweiCipher, bond: HuaweiBondRecord, installedKey: Boolean): Step {
        linkConfig = linkConfig.copy(cipher = cipher)
        return Step.Done(HuaweiAuthOutcome.Success(HuaweiAuthResult(m, link, linkConfig, bond, installedKey)))
    }

    private fun fail(error: HuaweiAuthError): Step {
        awaiting = null
        return Step.Done(HuaweiAuthOutcome.Failure(error))
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L

        /** HiChain operation 1 waits for the user to confirm the pairing on the band. */
        const val FIRST_PAIR_TIMEOUT_MS = 30_000L

        /** SecurityNegotiation authType that selects HiChain. */
        const val AUTH_TYPE_HICHAIN = 0x0186A0

        /**
         * The first-pairing entry point (the wizard). May send Bond or HiChain operation 1. [bandAddress]
         * is the band's Bluetooth address as Android reports it; it keys the normal-mode bond.
         */
        fun firstPairing(
            grant: HuaweiFirstPairingGrant,
            identity: HuaweiPhoneIdentity,
            bandAddress: String,
            stored: HuaweiBondRecord? = null,
            random: HuaweiRandom = HuaweiRandom.secure,
            clock: () -> Long = System::currentTimeMillis,
        ): HuaweiAuthMachine = HuaweiAuthMachine(grant, identity, bandAddress, stored, random, clock)

        /** Every later connection. Never sends Bond or HiChain operation 1. */
        fun reconnect(
            identity: HuaweiPhoneIdentity,
            bandAddress: String,
            stored: HuaweiBondRecord,
            random: HuaweiRandom = HuaweiRandom.secure,
            clock: () -> Long = System::currentTimeMillis,
        ): HuaweiAuthMachine = HuaweiAuthMachine(null, identity, bandAddress, stored, random, clock)
    }
}

/** Runs a [HuaweiAuthMachine] over a [HuaweiLink], applying each step's timeout. */
object HuaweiAuthenticator {
    suspend fun run(link: HuaweiLink, machine: HuaweiAuthMachine): HuaweiAuthOutcome {
        var step = machine.start()
        while (true) {
            when (val s = step) {
                is HuaweiAuthMachine.Step.Done -> {
                    val outcome = s.outcome
                    if (outcome is HuaweiAuthOutcome.Success) link.configure(outcome.result.linkConfig)
                    return outcome
                }
                is HuaweiAuthMachine.Step.Send -> {
                    link.configure(machine.linkConfig)
                    step = try {
                        val reply = withTimeoutOrNull(s.timeoutMillis) { link.request(s.request) }
                        if (reply == null) machine.onTimeout() else machine.onResponse(reply)
                    } catch (e: HuaweiProtocolException) {
                        machine.onLinkError(e)
                    }
                }
            }
        }
    }
}

/** Insertion-ordered JSON writer for the HiChain payloads (String, Int, Long, Boolean, nested maps). */
internal object HuaweiJson {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(sb, value)
            is Boolean, is Int, is Long -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    append(sb, v)
                }
                sb.append('}')
            }
            else -> throw IllegalArgumentException("unsupported JSON value ${value.javaClass.simpleName}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
