package com.noop.ble

import com.noop.huawei.FakeHuaweiLink
import com.noop.huawei.HuaweiCapabilities
import com.noop.huawei.HuaweiRadioPolicy
import com.noop.huawei.HuaweiSyncRunner
import com.noop.huawei.HuaweiSyncStage
import com.noop.huawei.HuaweiSyncSummary
import com.noop.huawei.SeededHuaweiRandom
import com.noop.huawei.SimulatedHuaweiBand
import com.noop.huawei.auth.HuaweiAuthMode
import com.noop.huawei.auth.HuaweiBondRecord
import com.noop.huawei.auth.HuaweiPhoneIdentity
import com.noop.huawei.protocol.HuaweiFirstPairingGrant
import com.noop.huawei.protocol.HuaweiFrameAssembler
import com.noop.huawei.protocol.HuaweiLink
import com.noop.huawei.protocol.HuaweiRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A GATT connection whose far end is a [SimulatedHuaweiBand]: slices in, reassembled replies out. */
internal class FakeHuaweiGatt(private val scope: CoroutineScope, private val band: FakeHuaweiLink.Peer) : HuaweiGatt {
    val opens = mutableListOf<String>()
    var closes = 0
        private set
    val written = mutableListOf<ByteArray>()
    var mtu = 247
    private var events: HuaweiGatt.Events? = null
    private val assembler = HuaweiFrameAssembler()
    private val slices = mutableListOf<ByteArray>()

    override fun open(address: String, events: HuaweiGatt.Events) {
        opens.add(address)
        this.events = events
        scope.launch { events.onLinkUp(mtu) }
    }

    override fun write(value: ByteArray): Boolean {
        val e = events ?: return false
        written.add(value)
        slices.add(value)
        if (assembler.feed(value).isEmpty()) return true
        val message = slices.toList()
        slices.clear()
        scope.launch {
            val reply = band.onFrames(message) ?: return@launch
            if (events === e) reply.forEach { e.onNotification(it) }
        }
        return true
    }

    override fun close() {
        closes++
        events = null
        assembler.reset()
        slices.clear()
    }

    /** The band pushes bytes unprompted. */
    fun deliver(chunks: List<ByteArray>) = chunks.forEach { events?.onNotification(it) }

    /** The connection drops (out of range, the band rebooted). */
    fun drop(reason: String = "status=8") {
        val e = events ?: return
        events = null
        e.onLinkDown(reason)
    }
}

internal class MemoryHuaweiKeys : HuaweiKeyAccess {
    val map = HashMap<String, HuaweiStoredKey>()
    override fun load(deviceId: String): HuaweiStoredKey? = map[deviceId]
    override fun save(deviceId: String, key: HuaweiStoredKey) { map[deviceId] = key }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HuaweiSourceTest {

    private val id = "huawei-C8:2A:DD:10:20:30"
    private val identity = HuaweiPhoneIdentity("00112233445566778899AABBCCDDEEFF", "FF:FF:FF:12:34:56", "NOOP")
    private val secret = ByteArray(16) { (it + 40).toByte() }

    private class Harness(
        val band: SimulatedHuaweiBand,
        val gatt: FakeHuaweiGatt,
        val keys: MemoryHuaweiKeys,
        val source: HuaweiSource,
        val lines: MutableList<String>,
        val syncs: MutableList<Pair<HuaweiCapabilities, String>>,
    )

    private fun TestScope.harness(
        config: SimulatedHuaweiBand.Config,
        stored: HuaweiStoredKey? = HuaweiStoredKey(identity, HuaweiBondRecord(HuaweiAuthMode.NORMAL, secret, null), null),
        runner: HuaweiSyncRunner? = null,
        lastAttempt: () -> Long? = { null },
        streak: () -> Int = { 0 },
    ): Harness {
        val band = SimulatedHuaweiBand(config)
        val gatt = FakeHuaweiGatt(backgroundScope, band)
        val keys = MemoryHuaweiKeys().also { k -> stored?.let { k.map[id] = it } }
        val lines = mutableListOf<String>()
        val syncs = mutableListOf<Pair<HuaweiCapabilities, String>>()
        val source = HuaweiSource(
            deviceId = id,
            gatt = gatt,
            keys = keys,
            scope = backgroundScope,
            syncRunner = runner ?: HuaweiSyncRunner { _: HuaweiLink, caps, deviceId -> syncs.add(caps to deviceId) },
            log = { lines.add(it) },
            detail = { true },
            random = SeededHuaweiRandom(7),
            nowMs = { testScheduler.currentTime + 1_700_000_000_000L },
            lastSyncAttemptAt = lastAttempt,
            emptyStreak = streak,
            utcOffsetSeconds = { 0 },
        )
        return Harness(band, gatt, keys, source, lines, syncs)
    }

    /** Background work is not covered by advanceUntilIdle, so move virtual time on explicitly. */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
    }

    private val bonded = SimulatedHuaweiBand.Config.normal().copy(bondStatus = 1, knownSecret = secret)

    private fun keyInstalls(band: SimulatedHuaweiBand): List<Int> = band.received
        .filter { (f, tlv) -> f.serviceId == 0x01 && (f.commandId == 0x0E || (f.commandId == 0x28 && runCatching { tlv.byte(0x02) }.getOrNull() == 1)) }
        .map { it.first.commandId }

    @Test fun reconnectAuthReachesReadyWithBatteryProductAndCapabilities() = runTest {
        val h = harness(bonded)
        h.source.connect(h.band.config.bandAddress)
        settle()
        assertEquals(HuaweiSource.LinkPhase.READY, h.source.linkPhase.value)
        assertEquals(77, h.source.battery.value)
        assertEquals("Band 10", h.source.product.value?.productModel)
        assertEquals("10.0.0.120", h.source.product.value?.softwareVersion)
        val readout = h.source.readout.value
        assertEquals(HuaweiAuthMode.NORMAL, readout.authMode)
        assertEquals("AES-CBC", readout.cipher)
        assertEquals(0xF4, readout.sliceSize)
        assertEquals(1, readout.authVersion)
        val caps = h.source.capabilities
        assertEquals(setOf(0x01, 0x07, 0x0A), caps.services)
        assertArrayEquals(byteArrayOf(0x05, 0x00, 0x01), caps.expand?.raw)
        assertTrue(caps.settings!!.truSleepFileSync && caps.settings!!.rriFileSync && caps.settings!!.gpsFileSync)
        // The sync engine got the link once, for this device.
        assertEquals(listOf(id), h.syncs.map { it.second })
        assertNotNull(h.source.lastSyncAt.value)
        assertTrue("reconnect must not install a key", keyInstalls(h.band).isEmpty())
        // Auth, then product info, the clock (01/05), battery, then the capability reads.
        val after = h.band.received.map { it.first.commandId }.dropWhile { it != 0x07 }
        assertEquals(listOf(0x07, 0x05, 0x08, 0x02, 0x03), after.take(5))
    }

    @Test fun batteryPushUpdatesTheLevel() = runTest {
        val h = harness(bonded)
        h.source.connect(h.band.config.bandAddress)
        settle()
        h.gatt.deliver(h.band.batteryPush(55))
        settle()
        assertEquals(55, h.source.battery.value)
    }

    @Test fun smallSliceSizeSplitsWritesAndStillReachesReady() = runTest {
        val h = harness(bonded.copy(sliceSize = 0x40))
        h.source.connect(h.band.config.bandAddress)
        settle()
        assertEquals(HuaweiSource.LinkPhase.READY, h.source.linkPhase.value)
        assertEquals(0x40, h.source.readout.value.sliceSize)
        assertTrue("every slice fits the slice size", h.gatt.written.all { it.size <= 0x40 })
        assertTrue("some request needed more than one slice", h.gatt.written.size > h.band.received.size)
    }

    @Test fun missingKeyGoesToNeedsPairingWithoutTouchingTheRadio() = runTest {
        val h = harness(bonded, stored = null)
        h.source.connect(h.band.config.bandAddress)
        settle()
        assertEquals(HuaweiSource.LinkPhase.NEEDS_PAIRING, h.source.linkPhase.value)
        assertTrue(h.gatt.opens.isEmpty())
        assertTrue(h.lines.any { "needs pairing" in it })
    }

    @Test fun identityWithoutABondAlsoNeedsPairing() = runTest {
        val h = harness(bonded, stored = HuaweiStoredKey(identity, null, "12345678901234567"))
        h.source.connect(h.band.config.bandAddress)
        settle()
        assertEquals(HuaweiSource.LinkPhase.NEEDS_PAIRING, h.source.linkPhase.value)
        assertTrue(h.gatt.opens.isEmpty())
    }

    @Test fun bandThatNeedsAFreshBondStopsAtNeedsPairingAndNeverRetries() = runTest {
        val h = harness(SimulatedHuaweiBand.Config.normal().copy(bondStatus = 0))
        h.source.connect(h.band.config.bandAddress)
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        assertEquals(HuaweiSource.LinkPhase.NEEDS_PAIRING, h.source.linkPhase.value)
        assertEquals(1, h.gatt.opens.size)
        assertTrue(keyInstalls(h.band).isEmpty())
    }

    @Test fun firstPairingRunsOnlyWithTheWizardGrantAndOnlyOnce() = runTest {
        val h = harness(SimulatedHuaweiBand.Config.normal().copy(bondStatus = 0), stored = null)
        h.source.beginFirstPairing(HuaweiFirstPairingGrant.forPairingWizard())
        h.source.connect(h.band.config.bandAddress)
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(HuaweiSource.LinkPhase.READY, h.source.linkPhase.value)
        assertEquals(listOf(0x0E), keyInstalls(h.band))
        val saved = h.keys.map[id]
        assertNotNull(saved)
        assertArrayEquals(h.band.bondedKey, saved!!.bond!!.secret)
        assertTrue(saved.identity.phoneAddress.startsWith("FF:FF:FF:"))
        assertEquals(32, saved.identity.androidId.length)
        // The link drops; the reconnect runs without the grant. This band still reports "not bonded", so the
        // reconnect stops at NEEDS_PAIRING rather than pairing again on its own.
        h.gatt.drop()
        runCurrent()
        advanceTimeBy(ReconnectBackoff.BASE_DELAY_MS + 1_000)
        assertEquals(2, h.gatt.opens.size)
        assertEquals(HuaweiSource.LinkPhase.NEEDS_PAIRING, h.source.linkPhase.value)
        assertEquals("exactly one Bond, from the wizard", listOf(0x0E), keyInstalls(h.band))
    }

    @Test fun hiChain3FirstPairingInstallsAGcmSession() = runTest {
        val h = harness(SimulatedHuaweiBand.Config.hiChain3(), stored = null)
        h.source.beginFirstPairing(HuaweiFirstPairingGrant.forPairingWizard())
        h.source.connect(h.band.config.bandAddress)
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(HuaweiSource.LinkPhase.READY, h.source.linkPhase.value)
        assertEquals(HuaweiAuthMode.HICHAIN3, h.source.readout.value.authMode)
        assertEquals("AES-GCM", h.source.readout.value.cipher)
        assertEquals(HuaweiAuthMode.HICHAIN3, h.keys.map[id]?.bond?.mode)
        assertArrayEquals(h.band.authToken, h.keys.map[id]?.bond?.secret)
    }

    @Test fun storedHiChainTokenReconnectsWithoutOperationOne() = runTest {
        val token = ByteArray(32) { (it * 3).toByte() }
        val h = harness(
            SimulatedHuaweiBand.Config.hiChain().copy(knownSecret = token),
            stored = HuaweiStoredKey(identity, HuaweiBondRecord(HuaweiAuthMode.HICHAIN, token, null), null),
        )
        h.source.connect(h.band.config.bandAddress)
        settle()
        assertEquals(HuaweiSource.LinkPhase.READY, h.source.linkPhase.value)
        assertTrue(keyInstalls(h.band).isEmpty())
    }

    @Test fun timeoutDuringAuthBacksOffAndReconnects() = runTest {
        val h = harness(bonded.copy(silentOn = setOf(0x01 to 0x01)))
        h.source.connect(h.band.config.bandAddress)
        runCurrent()
        assertEquals(HuaweiSource.LinkPhase.AUTHENTICATING, h.source.linkPhase.value)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(HuaweiSource.LinkPhase.DISCONNECTED, h.source.linkPhase.value)
        assertTrue(h.lines.any { "no reply at LINK_PARAMS" in it })
        assertEquals(1, h.gatt.opens.size)
        advanceTimeBy(ReconnectBackoff.BASE_DELAY_MS + 1)
        runCurrent()
        assertEquals(2, h.gatt.opens.size)
        // Second failure waits twice as long.
        advanceTimeBy(5_001 + ReconnectBackoff.BASE_DELAY_MS + 1)
        runCurrent()
        assertEquals(2, h.gatt.opens.size)
        advanceTimeBy(ReconnectBackoff.BASE_DELAY_MS)
        runCurrent()
        assertEquals(3, h.gatt.opens.size)
    }

    @Test fun disconnectMidAuthReconnects() = runTest {
        val h = harness(bonded.copy(silentOn = setOf(0x01 to 0x13)))
        h.source.connect(h.band.config.bandAddress)
        runCurrent()
        assertEquals(HuaweiSource.LinkPhase.AUTHENTICATING, h.source.linkPhase.value)
        h.gatt.drop()
        runCurrent()
        assertEquals(HuaweiSource.LinkPhase.DISCONNECTED, h.source.linkPhase.value)
        assertTrue(h.lines.any { "link lost during authentication" in it })
        advanceTimeBy(ReconnectBackoff.BASE_DELAY_MS + 1)
        runCurrent()
        assertEquals(2, h.gatt.opens.size)
    }

    @Test fun disconnectDuringFirstPairingFailsWithoutRetrying() = runTest {
        val h = harness(SimulatedHuaweiBand.Config.normal().copy(bondStatus = 0, silentOn = setOf(0x01 to 0x0F)), stored = null)
        h.source.beginFirstPairing(HuaweiFirstPairingGrant.forPairingWizard())
        h.source.connect(h.band.config.bandAddress)
        runCurrent()
        assertTrue(h.source.confirmingOnBand.value)
        h.gatt.drop()
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        assertEquals(HuaweiSource.LinkPhase.FAILED, h.source.linkPhase.value)
        assertFalse(h.source.confirmingOnBand.value)
        assertEquals(1, h.gatt.opens.size)
        assertNull("nothing bonded", h.keys.map[id]?.bond)
    }

    @Test fun grantIsSpentEvenWhenThePairingFails() = runTest {
        val h = harness(SimulatedHuaweiBand.Config.normal().copy(bondStatus = 0, silentOn = setOf(0x01 to 0x01)), stored = null)
        h.source.beginFirstPairing(HuaweiFirstPairingGrant.forPairingWizard())
        h.source.connect(h.band.config.bandAddress)
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(HuaweiSource.LinkPhase.FAILED, h.source.linkPhase.value)
        // "Sync now" reconnects, but with no grant and no bond it can only report NEEDS_PAIRING.
        h.source.syncNow()
        runCurrent()
        assertEquals(HuaweiSource.LinkPhase.NEEDS_PAIRING, h.source.linkPhase.value)
        assertEquals(1, h.gatt.opens.size)
        assertTrue(keyInstalls(h.band).isEmpty())
    }

    @Test fun refusedKeyStopsAtFailedUntilTheUserActs() = runTest {
        val h = harness(bonded.copy(corruptAuthResponse = true))
        h.source.connect(h.band.config.bandAddress)
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        assertEquals(HuaweiSource.LinkPhase.FAILED, h.source.linkPhase.value)
        assertEquals(1, h.gatt.opens.size)
        assertTrue(h.lines.any { "authentication failed: band proof did not verify at AUTH" in it })
        assertEquals("band proof did not verify at AUTH", h.source.readout.value.lastFailure)
        h.source.syncNow()
        runCurrent()
        assertEquals(2, h.gatt.opens.size)
    }

    @Test fun badCrcReplyIsDroppedAndTheReadTimesOut() = runTest {
        val h = harness(bonded.copy(badCrcOn = setOf(0x01 to 0x08)))
        h.source.connect(h.band.config.bandAddress)
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(HuaweiSource.LinkPhase.READY, h.source.linkPhase.value)
        assertNull(h.source.battery.value)
        assertTrue(h.lines.any { "dropped inbound bytes" in it })
    }

    @Test fun refusedCapabilityReadsStillReachReady() = runTest {
        val h = harness(bonded.copy(errorCodes = mapOf((0x01 to 0x02) to 0x00019001L, (0x01 to 0x37) to 0x00019001L)))
        h.source.connect(h.band.config.bandAddress)
        settle()
        assertEquals(HuaweiSource.LinkPhase.READY, h.source.linkPhase.value)
        assertTrue(h.source.capabilities.services.isEmpty())
        assertNull(h.source.capabilities.expand)
    }

    @Test fun stopPreventsReconnect() = runTest {
        val h = harness(bonded)
        h.source.connect(h.band.config.bandAddress)
        settle()
        h.source.stop()
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        assertEquals(HuaweiSource.LinkPhase.DISCONNECTED, h.source.linkPhase.value)
        assertEquals(1, h.gatt.opens.size)
    }

    @Test fun syncNowRunsAnotherSyncWhenReady() = runTest {
        val h = harness(bonded)
        h.source.connect(h.band.config.bandAddress)
        settle()
        h.source.syncNow()
        settle()
        assertEquals(2, h.syncs.size)
    }

    @Test fun failingSyncIsLoggedAndKeepsTheLink() = runTest {
        val h = harness(bonded, runner = HuaweiSyncRunner { _, _, _ -> error("boom") })
        h.source.connect(h.band.config.bandAddress)
        settle()
        assertEquals(HuaweiSource.LinkPhase.READY, h.source.linkPhase.value)
        assertNull(h.source.lastSyncAt.value)
        assertTrue(h.lines.any { "sync (CONNECT) failed" in it })
        assertEquals("IllegalStateException: boom", h.source.lastSyncError.value)
        assertNull(h.source.syncStage.value)
    }

    @Test fun foregroundSyncWaitsForTheEventFloorAndManualDoesNot() = runTest {
        val h = harness(bonded)
        h.source.connect(h.band.config.bandAddress)
        settle()
        assertEquals(1, h.syncs.size)
        h.source.onForeground()
        settle()
        assertEquals("held inside the 90 s event floor", 1, h.syncs.size)
        h.source.syncNow()
        settle()
        assertEquals("a manual sync always runs", 2, h.syncs.size)
        advanceTimeBy(HuaweiRadioPolicy.EVENT_FLOOR_SECONDS * 1000)
        h.source.onForeground()
        settle()
        assertEquals(3, h.syncs.size)
    }

    @Test fun periodicSyncRunsAtTheFloorWhileReadyAndStopsOnDisconnect() = runTest {
        val h = harness(bonded)
        h.source.connect(h.band.config.bandAddress)
        settle()
        advanceTimeBy(HuaweiRadioPolicy.PERIODIC_FLOOR_SECONDS * 1000)
        runCurrent()
        assertEquals(2, h.syncs.size)
        h.source.stop()
        settle()
        advanceTimeBy(HuaweiRadioPolicy.PERIODIC_FLOOR_SECONDS * 3000)
        runCurrent()
        assertEquals(2, h.syncs.size)
    }

    @Test fun anEmptyStreakStretchesThePeriodicFloor() = runTest {
        // The empty streak comes from the sync prefs; 3 empty syncs in a row double the periodic floor.
        val h = harness(bonded, streak = { 3 })
        h.source.connect(h.band.config.bandAddress)
        settle()
        advanceTimeBy(HuaweiRadioPolicy.PERIODIC_FLOOR_SECONDS * 1000)
        runCurrent()
        assertEquals("the backed-off floor is 30 min", 1, h.syncs.size)
        advanceTimeBy(HuaweiRadioPolicy.PERIODIC_FLOOR_SECONDS * 1000)
        runCurrent()
        assertEquals(2, h.syncs.size)
    }

    @Test fun progressStagesAndPartialFailuresReachThePanel() = runTest {
        val stages = mutableListOf<HuaweiSyncStage>()
        val runner = object : HuaweiSyncRunner {
            override suspend fun sync(link: HuaweiLink, capabilities: HuaweiCapabilities, deviceId: String) = Unit
            override suspend fun run(link: HuaweiLink, capabilities: HuaweiCapabilities, deviceId: String,
                                     progress: (HuaweiSyncStage) -> Unit): HuaweiSyncSummary {
                progress(HuaweiSyncStage.STEPS)
                stages.add(HuaweiSyncStage.STEPS)
                return HuaweiSyncSummary(stored = true, counts = mapOf("minutes" to 1), failures = listOf("stress: no reply"))
            }
        }
        val h = harness(bonded, runner = runner)
        h.source.connect(h.band.config.bandAddress)
        settle()
        assertEquals(listOf(HuaweiSyncStage.STEPS), stages)
        assertNotNull("a partial sync still counts as synced", h.source.lastSyncAt.value)
        assertEquals("stress: no reply", h.source.lastSyncError.value)
        assertNull(h.source.syncStage.value)
    }

    @Test fun slicesAreCappedAtTheNegotiatedMtu() = runTest {
        val h = harness(bonded)
        h.gatt.mtu = 23
        h.source.connect(h.band.config.bandAddress)
        settle()
        assertEquals(HuaweiSource.LinkPhase.READY, h.source.linkPhase.value)
        assertEquals(20, h.source.readout.value.sliceSize)
        assertTrue("every write fits one ATT payload", h.gatt.written.all { it.size <= 20 })
        assertTrue("capability reads still go out", h.band.received.any { it.first.commandId == 0x03 })
    }

    @Test fun theAccountIdIsSentOnlyWhenTheUserEnteredOne() = runTest {
        val account = "12345678901234567"
        val config = bonded.copy(supportedServices = setOf(0x01, 0x07, 0x1A), supportedCommands = mapOf(0x1A to setOf(0x05, 0x06)))
        val h = harness(config, stored = HuaweiStoredKey(identity, HuaweiBondRecord(HuaweiAuthMode.NORMAL, secret, null), account))
        h.source.connect(h.band.config.bandAddress)
        settle()
        val sent = h.band.received.filter { it.first.serviceId == 0x1A }
        assertEquals(listOf(0x05), sent.map { it.first.commandId })
        assertEquals(account, sent.single().second.string(0x01))

        val none = harness(config)
        none.source.connect(none.band.config.bandAddress)
        settle()
        assertTrue(none.band.received.none { it.first.serviceId == 0x1A })
    }

    @Test fun theBandClockIsSetToThePhonesTime() = runTest {
        val h = harness(bonded)
        h.source.connect(h.band.config.bandAddress)
        settle()
        val set = h.band.received.single { it.first.serviceId == 0x01 && it.first.commandId == 0x05 }.second
        assertTrue(kotlin.math.abs(set.u32(0x01) - 1_700_000_000L) < 60)
        assertEquals(2, set.bytes(0x02).size)
    }

    @Test fun authModeChangeIsAlwaysLogged() = runTest {
        // A reconnect refuses a stored bond of another mode (it needs a first pairing); pairing again over that
        // record replaces it, and the change is logged even with the Test Centre off.
        val h = harness(
            SimulatedHuaweiBand.Config.normal().copy(bondStatus = 0),
            stored = HuaweiStoredKey(identity, HuaweiBondRecord(HuaweiAuthMode.HICHAIN_LITE, secret, null), null),
        )
        val quiet = mutableListOf<String>()
        val source = HuaweiSource(id, h.gatt, h.keys, backgroundScope, log = { quiet.add(it) }, detail = { false }, random = SeededHuaweiRandom(3))
        source.connect(h.band.config.bandAddress)
        settle()
        assertEquals(HuaweiSource.LinkPhase.NEEDS_PAIRING, source.linkPhase.value)
        source.beginFirstPairing(HuaweiFirstPairingGrant.forPairingWizard())
        settle()
        assertEquals(HuaweiSource.LinkPhase.READY, source.linkPhase.value)
        assertTrue(quiet.joinToString("\n"), quiet.any { "auth mode changed from HICHAIN_LITE to NORMAL" in it })
        assertEquals(identity, h.keys.map[id]?.identity)
        assertFalse("per-connect readouts stay behind the Test Centre", quiet.any { "authenticated: mode" in it })
        assertEquals(HuaweiAuthMode.NORMAL, h.keys.map[id]?.bond?.mode)
    }

    @Test fun pairingRequestsAreOneShot() {
        val grant = HuaweiFirstPairingGrant.forPairingWizard()
        HuaweiPairingRequests.arm("huawei-x", grant)
        assertTrue(HuaweiPairingRequests.take("huawei-x") === grant)
        assertNull(HuaweiPairingRequests.take("huawei-x"))
    }

    @Test fun everyRequestTheSourceSendsIsAllowlistedAndReconnectSendsNoKeyInstall() = runTest {
        val h = harness(bonded)
        h.source.connect(h.band.config.bandAddress)
        settle()
        for ((frame, tlv) in h.band.received) {
            val command = com.noop.huawei.protocol.HuaweiCommand.entries.firstOrNull { it.serviceId == frame.serviceId && it.commandId == frame.commandId }
            assertNotNull("%02X/%02X is not allowlisted".format(frame.serviceId, frame.commandId), command)
            assertFalse(HuaweiRequest.needsFirstPairing(command!!, tlv))
        }
    }
}
