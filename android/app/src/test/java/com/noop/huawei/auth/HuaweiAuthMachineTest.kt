package com.noop.huawei.auth

import com.noop.huawei.FakeHuaweiLink
import com.noop.huawei.SeededHuaweiRandom
import com.noop.huawei.SimulatedHuaweiBand
import com.noop.huawei.hex
import com.noop.huawei.protocol.HuaweiBytes
import com.noop.huawei.protocol.HuaweiCommand
import com.noop.huawei.protocol.HuaweiDeviceConfig
import com.noop.huawei.protocol.HuaweiFirstPairingGrant
import com.noop.huawei.protocol.HuaweiRequest
import com.noop.huawei.protocol.HuaweiTlv
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full handshakes against [SimulatedHuaweiBand], which implements the band side independently and talks
 * bytes through [FakeHuaweiLink]. A session counts as complete only when the band can open NOOP's first
 * encrypted request with its own key and NOOP can open the reply.
 */
class HuaweiAuthMachineTest {

    private val identity = HuaweiPhoneIdentity("0123456789ABCDEF0123456789ABCDEF", "FF:FF:FF:12:34:56", "NOOP")
    private val clock = { 1_700_000_000_000L }

    private fun firstPairing(band: SimulatedHuaweiBand, stored: HuaweiBondRecord? = null) = HuaweiAuthMachine.firstPairing(
        HuaweiFirstPairingGrant.forPairingWizard(), identity, band.config.bandAddress, stored, SeededHuaweiRandom(1), clock,
    )

    private fun reconnect(band: SimulatedHuaweiBand, stored: HuaweiBondRecord) =
        HuaweiAuthMachine.reconnect(identity, band.config.bandAddress, stored, SeededHuaweiRandom(2), clock)

    private fun commands(link: FakeHuaweiLink) = link.sent.map { it.command }

    private fun hiChainMessages(link: FakeHuaweiLink) = link.sent.filter { it.command == HuaweiCommand.HICHAIN }.map {
        it.tlv.byte(0x02) to JSONObject(it.tlv.string(0x01)).getInt("message")
    }

    private fun success(outcome: HuaweiAuthOutcome): HuaweiAuthResult {
        assertTrue("expected success, got $outcome", outcome is HuaweiAuthOutcome.Success)
        return (outcome as HuaweiAuthOutcome.Success).result
    }

    private fun failure(outcome: HuaweiAuthOutcome): HuaweiAuthError {
        assertTrue("expected failure, got $outcome", outcome is HuaweiAuthOutcome.Failure)
        return (outcome as HuaweiAuthOutcome.Failure).error
    }

    /** The reconnect path must never send a key-installing request. */
    private fun assertNoKeyInstall(link: FakeHuaweiLink) {
        for (r in link.sent) assertFalse("reconnect sent $r", HuaweiRequest.needsFirstPairing(r.command, r.tlv))
    }

    /** An encrypted read after auth proves both sides hold the same session key. */
    private suspend fun assertSessionWorks(link: FakeHuaweiLink, band: SimulatedHuaweiBand) {
        val asked = listOf(0x01, 0x02, 0x07, 0x16)
        val supported = HuaweiDeviceConfig.SupportedServices.decode(link.request(HuaweiDeviceConfig.SupportedServices.request(asked)), asked)
        assertEquals(setOf(0x01, 0x07, 0x16), supported)
        assertEquals("Band 10", HuaweiDeviceConfig.ProductInfo.decode(link.request(HuaweiDeviceConfig.ProductInfo.request())).productModel)
        assertEquals(77, HuaweiDeviceConfig.Battery.decode(link.request(HuaweiDeviceConfig.Battery.request())).level)
        val decrypted = band.received.last().second
        assertEquals(HuaweiTlv().put(1), decrypted)
    }

    // MARK: - Normal mode

    @Test fun normalFirstPairingBondsAndEncrypts() = runTest {
        for (version in listOf(1, 2, 3)) {
            val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.normal().copy(authVersion = version))
            val link = FakeHuaweiLink(band)
            val result = success(HuaweiAuthenticator.run(link, firstPairing(band)))
            assertEquals(HuaweiAuthMode.NORMAL, result.mode)
            assertEquals(
                listOf(HuaweiCommand.LINK_PARAMS, HuaweiCommand.AUTH, HuaweiCommand.BOND_PARAMS, HuaweiCommand.BOND),
                commands(link),
            )
            assertTrue(result.installedKey)
            assertArrayEquals(band.bondedKey, result.bond.secret)
            assertEquals(16, result.bond.secret.size)
            assertFalse(result.linkConfig.cipher!!.useGcm)
            assertSessionWorks(link, band)
            // Bond IV carries seed+1; the first encrypted packet seed+2.
            assertArrayEquals(HuaweiBytes.u32(0x1001), band.receivedIvs[0].copyOfRange(12, 16))
            assertArrayEquals(HuaweiBytes.u32(0x1002), band.receivedIvs[1].copyOfRange(12, 16))
        }
    }

    @Test fun normalFirstPairingWithGcm() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.normal().copy(encryptMethod = 1))
        val link = FakeHuaweiLink(band)
        val result = success(HuaweiAuthenticator.run(link, firstPairing(band)))
        assertTrue(result.linkConfig.cipher!!.useGcm)
        assertSessionWorks(link, band)
    }

    @Test fun normalReconnectReusesTheBondWithoutBonding() = runTest {
        val secret = ByteArray(16) { (it + 40).toByte() }
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.normal().copy(bondStatus = 1, knownSecret = secret))
        val link = FakeHuaweiLink(band)
        val result = success(HuaweiAuthenticator.run(link, reconnect(band, HuaweiBondRecord(HuaweiAuthMode.NORMAL, secret, null))))
        assertEquals(listOf(HuaweiCommand.LINK_PARAMS, HuaweiCommand.AUTH, HuaweiCommand.BOND_PARAMS), commands(link))
        assertFalse(result.installedKey)
        assertNoKeyInstall(link)
        assertSessionWorks(link, band)
    }

    @Test fun normalReconnectThatWouldNeedABondStops() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.normal().copy(bondStatus = 0))
        val link = FakeHuaweiLink(band)
        val error = failure(HuaweiAuthenticator.run(link, reconnect(band, HuaweiBondRecord(HuaweiAuthMode.NORMAL, ByteArray(16), null))))
        assertTrue(error is HuaweiAuthError.NeedsFirstPairing)
        assertNoKeyInstall(link)
        assertFalse(HuaweiCommand.BOND in commands(link))
    }

    @Test fun reconnectWithoutAMatchingBondNeedsFirstPairing() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.normal())
        val link = FakeHuaweiLink(band)
        val error = failure(HuaweiAuthenticator.run(link, reconnect(band, HuaweiBondRecord(HuaweiAuthMode.HICHAIN, ByteArray(32), null))))
        assertTrue(error is HuaweiAuthError.NeedsFirstPairing)
        assertEquals(listOf(HuaweiCommand.LINK_PARAMS), commands(link))
    }

    // MARK: - HiChain Lite

    @Test fun liteFirstPairingFetchesPinAndBondsWithFirstKey() = runTest {
        for (algo in listOf(0, 1)) {
            val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.lite().copy(authAlgo = algo))
            val link = FakeHuaweiLink(band)
            val result = success(HuaweiAuthenticator.run(link, firstPairing(band)))
            assertEquals(HuaweiAuthMode.HICHAIN_LITE, result.mode)
            assertEquals(
                listOf(
                    HuaweiCommand.LINK_PARAMS, HuaweiCommand.SECURITY_NEGOTIATION, HuaweiCommand.PIN_CODE,
                    HuaweiCommand.AUTH, HuaweiCommand.BOND_PARAMS, HuaweiCommand.BOND,
                ),
                commands(link),
            )
            assertEquals(algo, link.sent.single { it.command == HuaweiCommand.AUTH }.tlv.byte(0x03))
            assertArrayEquals(band.config.pin, result.bond.pinCode)
            assertArrayEquals(band.bondedKey, result.bond.secret)
            assertSessionWorks(link, band)
        }
    }

    @Test fun liteReconnectUsesStoredPinAndSkipsBond() = runTest {
        val secret = ByteArray(16) { 9 }
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.lite().copy(knownSecret = secret))
        val link = FakeHuaweiLink(band)
        val stored = HuaweiBondRecord(HuaweiAuthMode.HICHAIN_LITE, secret, band.config.pin)
        success(HuaweiAuthenticator.run(link, reconnect(band, stored)))
        assertEquals(
            listOf(HuaweiCommand.LINK_PARAMS, HuaweiCommand.SECURITY_NEGOTIATION, HuaweiCommand.AUTH, HuaweiCommand.BOND_PARAMS),
            commands(link),
        )
        assertNoKeyInstall(link)
        assertSessionWorks(link, band)
    }

    // MARK: - HiChain

    @Test fun hiChainFirstPairingRunsOperation1ThenOperation2() = runTest {
        // A small slice size makes the JSON requests and replies travel in several slices each way.
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.hiChain().copy(sliceSize = 0x40))
        val link = FakeHuaweiLink(band)
        val machine = firstPairing(band)
        val result = success(HuaweiAuthenticator.run(link, machine))
        assertEquals(HuaweiAuthMode.HICHAIN, result.mode)
        assertEquals(
            listOf(HuaweiCommand.LINK_PARAMS, HuaweiCommand.SECURITY_NEGOTIATION, HuaweiCommand.PIN_CODE) + List(7) { HuaweiCommand.HICHAIN },
            commands(link),
        )
        assertEquals(listOf(1 to 1, 1 to 2, 1 to 3, 1 to 4, 2 to 0x11, 2 to 0x12, 2 to 0x13), hiChainMessages(link))
        assertTrue(link.sentFrames.any { it.size > 1 })
        assertTrue(result.installedKey)
        assertArrayEquals(band.authToken, result.bond.secret)
        assertEquals(32, band.packetKey!!.size)
        assertEquals(0x40, link.currentConfig.sliceSize)
        assertSessionWorks(link, band)
    }

    @Test fun hiChainOperation1WaitsThirtySecondsForTheUser() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.hiChain())
        val link = FakeHuaweiLink(band)
        val m = firstPairing(band)
        val steps = mutableListOf<HuaweiAuthMachine.Step.Send>()
        var step = m.start()
        while (step is HuaweiAuthMachine.Step.Send) {
            steps.add(step)
            link.configure(m.linkConfig)
            step = m.onResponse(link.request(step.request))
        }
        success((step as HuaweiAuthMachine.Step.Done).outcome)
        val op1 = steps.filter { it.request.command == HuaweiCommand.HICHAIN && it.request.tlv.byte(2) == 1 }
        val op2 = steps.filter { it.request.command == HuaweiCommand.HICHAIN && it.request.tlv.byte(2) == 2 }
        assertEquals(4, op1.size)
        assertTrue(op1.all { it.timeoutMillis == HuaweiAuthMachine.FIRST_PAIR_TIMEOUT_MS })
        assertEquals(30_000L, HuaweiAuthMachine.FIRST_PAIR_TIMEOUT_MS)
        assertEquals(3, op2.size)
        assertTrue(op2.all { it.timeoutMillis == HuaweiAuthMachine.DEFAULT_TIMEOUT_MS })
    }

    @Test fun hiChainReconnectIsOperation2Only() = runTest {
        val token = ByteArray(32) { (0xA0 + it).toByte() }
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.hiChain().copy(knownSecret = token))
        val link = FakeHuaweiLink(band)
        val result = success(HuaweiAuthenticator.run(link, reconnect(band, HuaweiBondRecord(HuaweiAuthMode.HICHAIN, token, null))))
        assertEquals(
            listOf(HuaweiCommand.LINK_PARAMS, HuaweiCommand.SECURITY_NEGOTIATION) + List(3) { HuaweiCommand.HICHAIN },
            commands(link),
        )
        assertEquals(listOf(2 to 0x11, 2 to 0x12, 2 to 0x13), hiChainMessages(link))
        assertFalse(result.installedKey)
        assertNoKeyInstall(link)
        assertArrayEquals(token, result.bond.secret)
        assertSessionWorks(link, band)
    }

    @Test fun hiChainFirstPairingThenReconnectAgainstTheSameBand() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.hiChain())
        val first = success(HuaweiAuthenticator.run(FakeHuaweiLink(band), firstPairing(band)))
        val firstKey = band.packetKey!!.copyOf()
        val link = FakeHuaweiLink(band)
        success(HuaweiAuthenticator.run(link, reconnect(band, first.bond)))
        assertFalse(firstKey.contentEquals(band.packetKey))
        assertSessionWorks(link, band)
    }

    @Test fun hiChain3UsesGcmRandomIvAndSendsThePhoneModel() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.hiChain3())
        val link = FakeHuaweiLink(band)
        val result = success(HuaweiAuthenticator.run(link, firstPairing(band)))
        assertEquals(HuaweiAuthMode.HICHAIN3, result.mode)
        assertTrue(result.linkConfig.cipher!!.useGcm)
        val secNeg = link.sent.single { it.command == HuaweiCommand.SECURITY_NEGOTIATION }.tlv
        assertEquals(4, secNeg.byte(0x01))
        assertEquals("NOOP", secNeg.string(0x07))
        assertSessionWorks(link, band)
        // 16 random IV bytes, not a counter.
        val ivs = band.receivedIvs
        assertFalse(ivs[0].copyOfRange(12, 16).contentEquals(HuaweiBytes.u32(1)))
    }

    // MARK: - Error branches

    @Test fun resultCodeErrorStopsTheHandshake() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.normal().copy(errorCodes = mapOf((0x01 to 0x13) to 0x000186A1L)))
        val error = failure(HuaweiAuthenticator.run(FakeHuaweiLink(band), firstPairing(band)))
        assertEquals(HuaweiAuthError.DeviceError(HuaweiAuthStage.AUTH, 0x000186A1L), error)
    }

    @Test fun authDigestMismatch() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.normal().copy(corruptAuthResponse = true))
        val link = FakeHuaweiLink(band)
        assertEquals(HuaweiAuthError.MacMismatch(HuaweiAuthStage.AUTH), failure(HuaweiAuthenticator.run(link, firstPairing(band))))
        assertFalse(HuaweiCommand.BOND in commands(link))
    }

    @Test fun hiChainTokenMismatch() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.hiChain().copy(corruptHiChainToken = true))
        val error = failure(HuaweiAuthenticator.run(FakeHuaweiLink(band), firstPairing(band)))
        assertEquals(HuaweiAuthError.MacMismatch(HuaweiAuthStage.HICHAIN_AUTHENTICATE), error)
    }

    @Test fun hiChainReturnCodeMacMismatch() = runTest {
        val token = ByteArray(32) { 3 }
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.hiChain().copy(knownSecret = token, corruptReturnCodeMac = true))
        val error = failure(HuaweiAuthenticator.run(FakeHuaweiLink(band), reconnect(band, HuaweiBondRecord(HuaweiAuthMode.HICHAIN, token, null))))
        assertEquals(HuaweiAuthError.MacMismatch(HuaweiAuthStage.HICHAIN_BIND), error)
    }

    @Test fun hiChainReconnectWithAStaleTokenFails() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.hiChain().copy(knownSecret = ByteArray(32) { 1 }))
        val error = failure(
            HuaweiAuthenticator.run(FakeHuaweiLink(band), reconnect(band, HuaweiBondRecord(HuaweiAuthMode.HICHAIN, ByteArray(32) { 2 }, null))),
        )
        assertEquals(HuaweiAuthError.MacMismatch(HuaweiAuthStage.HICHAIN_BIND), error)
    }

    @Test fun hiChainErrorCode() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.hiChain().copy(hiChainErrorCode = 0x2003))
        val error = failure(HuaweiAuthenticator.run(FakeHuaweiLink(band), firstPairing(band)))
        assertEquals(HuaweiAuthError.HiChainError(HuaweiAuthStage.HICHAIN_AUTHENTICATE, 0x2003), error)
    }

    @Test fun hiChainUserNeverConfirmsTimesOutAfterThirtySeconds() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.hiChain().copy(silentOn = setOf(0x01 to 0x28)))
        val start = currentTime
        val error = failure(HuaweiAuthenticator.run(FakeHuaweiLink(band), firstPairing(band)))
        assertEquals(HuaweiAuthError.Timeout(HuaweiAuthStage.HICHAIN_AUTHENTICATE), error)
        assertEquals(30_000L, currentTime - start)
    }

    @Test fun linkParamsTimeout() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.normal().copy(silentOn = setOf(0x01 to 0x01)))
        val start = currentTime
        assertEquals(HuaweiAuthError.Timeout(HuaweiAuthStage.LINK_PARAMS), failure(HuaweiAuthenticator.run(FakeHuaweiLink(band), firstPairing(band))))
        assertEquals(5_000L, currentTime - start)
    }

    @Test fun unknownDeviceSupportType() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.normal().copy(deviceSupportType = 7))
        val link = FakeHuaweiLink(band)
        assertEquals(HuaweiAuthError.UnknownMode(7, null), failure(HuaweiAuthenticator.run(link, firstPairing(band))))
        assertEquals(listOf(HuaweiCommand.LINK_PARAMS), commands(link))
    }

    @Test fun unknownNegotiatedAuthType() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config(deviceSupportType = 1, secNegTag2 = 9))
        assertEquals(HuaweiAuthError.UnknownMode(1, 9), failure(HuaweiAuthenticator.run(FakeHuaweiLink(band), firstPairing(band))))
    }

    @Test fun corruptFrameFromTheBandIsAProtocolError() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.normal().copy(badCrcOn = setOf(0x01 to 0x0F)))
        val error = failure(HuaweiAuthenticator.run(FakeHuaweiLink(band), firstPairing(band)))
        assertTrue(error is HuaweiAuthError.Protocol)
        assertEquals(HuaweiAuthStage.BOND_PARAMS, (error as HuaweiAuthError.Protocol).stage)
    }

    @Test fun honorPakeFramingIsRejected() = runTest {
        val link = FakeHuaweiLink.scripted { frame, _ ->
            when (frame.commandId) {
                0x01 -> HuaweiTlv().put(5, hex("0001") + ByteArray(16)).putByte(7, 1)
                0x33 -> HuaweiTlv().putByte(1, 1)
                0x2C -> HuaweiTlv().put(1, HuaweiAuthCrypto.encryptPinCode(1, 0, "1".toByteArray(), ByteArray(16))).put(2, ByteArray(16))
                0x28 -> HuaweiTlv().putByte(1, 2).putString(2, "{}")
                else -> null
            }
        }
        val error = failure(HuaweiAuthenticator.run(link, HuaweiAuthMachine.firstPairing(
            HuaweiFirstPairingGrant.forPairingWizard(), identity, "AA:BB:CC:DD:EE:FF", null, SeededHuaweiRandom(5), clock,
        )))
        assertTrue(error is HuaweiAuthError.Protocol)
    }

    @Test fun malformedLinkParamsIsAProtocolError() = runTest {
        val link = FakeHuaweiLink.scripted { _, _ -> HuaweiTlv().putByte(7, 0) }
        val m = HuaweiAuthMachine.firstPairing(HuaweiFirstPairingGrant.forPairingWizard(), identity, "AA:BB:CC:DD:EE:FF")
        val error = failure(HuaweiAuthenticator.run(link, m))
        assertEquals(HuaweiAuthStage.LINK_PARAMS, (error as HuaweiAuthError.Protocol).stage)
    }

    // MARK: - Machine contract and pushes

    @Test fun machineContract() {
        val m = HuaweiAuthMachine.reconnect(identity, "AA:BB:CC:DD:EE:FF", HuaweiBondRecord(HuaweiAuthMode.NORMAL, ByteArray(16), null))
        assertFalse(m.isFirstPairing)
        assertThrows(IllegalStateException::class.java) { m.onResponse(HuaweiTlv()) }
        assertNull(m.awaiting)
        val first = m.start()
        assertEquals(HuaweiAuthStage.LINK_PARAMS, m.awaiting)
        assertEquals(HuaweiCommand.LINK_PARAMS, (first as HuaweiAuthMachine.Step.Send).request.command)
        assertThrows(IllegalStateException::class.java) { m.start() }
        assertTrue(m.onTimeout() is HuaweiAuthMachine.Step.Done)
        assertNull(m.awaiting)
    }

    @Test fun batteryPushArrivesOnThePushFlow() = runTest {
        val band = SimulatedHuaweiBand(SimulatedHuaweiBand.Config.normal())
        val link = FakeHuaweiLink(band)
        success(HuaweiAuthenticator.run(link, firstPairing(band)))
        link.deliverFromBand(band.batteryPush(42))
        val push = link.pushes.first()
        assertEquals(0x01 to 0x27, push.serviceId to push.commandId)
        assertEquals(42, HuaweiDeviceConfig.Battery.decode(push.tlv).level)
        assertNotNull(link.currentConfig.cipher)
    }
}
