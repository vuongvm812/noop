package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.noop.garmin.GarminRealtimeCapture
import com.noop.garmin.GarminService

/**
 * Tests for [GarminRadioPolicy], the twin of the Swift `GarminRadioPolicy`
 * (Strand/BLE/GarminRadioPolicy.swift). The rule tests read on their own; [matchesSwiftOracle] pins the
 * whole decision space against stdout from the Swift implementation compiled standalone
 * (`swiftc -O Strand/BLE/GarminRadioPolicy.swift main.swift`), pasted verbatim, so the two platforms
 * cannot drift apart unnoticed.
 */
class GarminRadioPolicyTest {
    private val hr = GarminLiveChannel.HEART_RATE
    private val rr = GarminLiveChannel.RR_INTERVALS
    private val all = GarminLiveChannel.values().toSet()

    @Test
    fun noDemandMeansRadioIdle() {
        assertTrue(GarminRadioPolicy.desiredChannels(GarminRadioInputs(appInForeground = true)).isEmpty())
        assertTrue(GarminRadioPolicy.desiredChannels(GarminRadioInputs(appInForeground = false)).isEmpty())
    }

    @Test
    fun liveScreenOpensOnlyHrAndRrByDefault() {
        val inputs = GarminRadioInputs(appInForeground = true, liveScreenVisible = true)
        assertEquals(setOf(hr, rr), GarminRadioPolicy.desiredChannels(inputs))
    }

    @Test
    fun optionalChannelsNeedTheirUiOnScreen() {
        val inputs = GarminRadioInputs(
            appInForeground = true,
            liveScreenVisible = true,
            onScreenChannels = setOf(GarminLiveChannel.BODY_BATTERY),
        )
        assertEquals(setOf(hr, rr, GarminLiveChannel.BODY_BATTERY), GarminRadioPolicy.desiredChannels(inputs))
    }

    @Test
    fun spo2AndAccelerometerNeverOpenAutomatically() {
        val auto = GarminRadioInputs(
            appInForeground = true,
            liveScreenVisible = true,
            workoutActive = true,
            continuousLiveEnabled = true,
            onScreenChannels = all,
        )
        val desired = GarminRadioPolicy.desiredChannels(auto)
        assertFalse(GarminLiveChannel.SPO2 in desired)
        assertFalse(GarminLiveChannel.ACCELEROMETER in desired)
        val asked = auto.copy(explicitChannels = setOf(GarminLiveChannel.SPO2))
        assertTrue(GarminLiveChannel.SPO2 in GarminRadioPolicy.desiredChannels(asked))
    }

    @Test
    fun backgroundClosesEverythingExceptAWorkout() {
        val bg = GarminRadioInputs(
            appInForeground = false,
            liveScreenVisible = true,
            continuousLiveEnabled = true,
            onScreenChannels = all,
            explicitChannels = all,
        )
        assertTrue(GarminRadioPolicy.desiredChannels(bg).isEmpty())
        assertEquals(setOf(hr, rr), GarminRadioPolicy.desiredChannels(bg.copy(workoutActive = true)))
    }

    @Test
    fun continuousLiveStopsWhileAsleep() {
        val on = GarminRadioInputs(appInForeground = true, continuousLiveEnabled = true)
        assertEquals(setOf(hr, rr), GarminRadioPolicy.desiredChannels(on))
        assertTrue(GarminRadioPolicy.desiredChannels(on.copy(asleep = true)).isEmpty())
    }

    @Test
    fun channelsCloseThirtySecondsAfterTheViewerLeaves() {
        val linger = GarminLiveChannelLinger()
        val viewing = GarminRadioInputs(appInForeground = true, liveScreenVisible = true)
        val away = GarminRadioInputs(appInForeground = true)
        linger.update(viewing, 0.0)
        val left = linger.update(away, 100.0)
        assertEquals(setOf(hr, rr), left.channels)
        assertEquals(130.0, left.recheckAtSeconds!!, 0.0)
        assertEquals(setOf(hr, rr), linger.update(away, 129.9).channels)
        val closed = linger.update(away, 130.0)
        assertTrue(closed.channels.isEmpty())
        assertNull(closed.recheckAtSeconds)
    }

    @Test
    fun backgroundingSkipsTheLinger() {
        val linger = GarminLiveChannelLinger()
        linger.update(GarminRadioInputs(appInForeground = true, liveScreenVisible = true), 0.0)
        assertTrue(linger.update(GarminRadioInputs(appInForeground = false), 1.0).channels.isEmpty())
    }

    @Test
    fun explicitSpo2ClosesImmediately() {
        val linger = GarminLiveChannelLinger()
        val asking = GarminRadioInputs(
            appInForeground = true,
            liveScreenVisible = true,
            explicitChannels = setOf(GarminLiveChannel.SPO2),
        )
        linger.update(asking, 0.0)
        val done = linger.update(asking.copy(explicitChannels = emptySet()), 5.0)
        assertEquals(setOf(hr, rr), done.channels)
    }

    @Test
    fun syncFloorAndEmptyBackoff() {
        val p = GarminSyncTrigger.PERIODIC
        assertFalse(GarminRadioPolicy.shouldSync(p, 10_000.0, 10_000.0 - 899))
        assertTrue(GarminRadioPolicy.shouldSync(p, 10_000.0, 10_000.0 - 900))
        // 3 empty syncs double the floor; the cap is 1 h.
        assertFalse(GarminRadioPolicy.shouldSync(p, 10_000.0, 10_000.0 - 1799, emptyStreak = 3))
        assertTrue(GarminRadioPolicy.shouldSync(p, 10_000.0, 10_000.0 - 1800, emptyStreak = 3))
        assertFalse(GarminRadioPolicy.shouldSync(p, 10_000.0, 10_000.0 - 3599, emptyStreak = 50))
        assertTrue(GarminRadioPolicy.shouldSync(p, 10_000.0, 10_000.0 - 3600, emptyStreak = 50))
        // A non-empty sync resets the streak to 0, so the base floor applies again.
        assertTrue(GarminRadioPolicy.shouldSync(p, 10_000.0, 10_000.0 - 900, emptyStreak = 0))
        // The user is never delayed.
        assertTrue(GarminRadioPolicy.shouldSync(GarminSyncTrigger.MANUAL, 1.0, 1.0, emptyStreak = 50))
    }

    @Test
    fun connectionRules() {
        assertEquals(GarminConnectionPriority.HIGH, GarminRadioPolicy.connectionPriority(GarminLinkActivity.FILE_TRANSFER))
        assertEquals(GarminConnectionPriority.LOW_POWER, GarminRadioPolicy.connectionPriority(GarminLinkActivity.IDLE))
        assertEquals(GarminDiscoveryAction.NONE, GarminRadioPolicy.discoveryAction(false, false))
        assertEquals(GarminDiscoveryAction.CONNECT_BY_IDENTIFIER, GarminRadioPolicy.discoveryAction(false, true))
        assertEquals(515, GarminRadioPolicy.REQUESTED_MTU)
        assertNull(GarminRadioPolicy.MEASURED_LIVE_COST_PERCENT_PER_HOUR)
    }

    @Test
    fun matchesSwiftOracle() {
        assertEquals(SWIFT_ORACLE.trimEnd(), oracleOutput().joinToString("\n"))
    }

    // Mirrors the Swift oracle's main.swift step for step.
    private fun oracleOutput(): List<String> {
        val out = mutableListOf<String>()
        fun fmt(s: Set<GarminLiveChannel>) =
            if (s.isEmpty()) "-" else s.map { it.serviceId }.sorted().joinToString(",")
        fun camel(e: Enum<*>) = e.name.lowercase().split('_').mapIndexed { i, w ->
            if (i == 0) w else w.replaceFirstChar { it.uppercase() }
        }.joinToString("")
        for (mask in 0 until 32) {
            fun b(i: Int) = mask and (1 shl i) != 0
            val inputs = GarminRadioInputs(b(0), b(1), b(2), b(3), b(4), all, all)
            out += "desired $mask ${fmt(GarminRadioPolicy.desiredChannels(inputs))}"
        }
        val triggers = listOf(
            "periodic" to GarminSyncTrigger.PERIODIC, "connect" to GarminSyncTrigger.CONNECT,
            "foreground" to GarminSyncTrigger.FOREGROUND, "manual" to GarminSyncTrigger.MANUAL,
            "watch" to GarminSyncTrigger.WATCH,
        )
        val elapsed = listOf(0.0, 89.0, 90.0, 179.0, 180.0, 359.0, 360.0, 899.0, 900.0, 1799.0, 1800.0, 3599.0, 3600.0, 7200.0)
        for ((n, t) in triggers) {
            val row = StringBuilder()
            for (streak in listOf(0, 2, 3, 4, 5, 9)) {
                for (e in elapsed) row.append(if (GarminRadioPolicy.shouldSync(t, 10_000.0, 10_000.0 - e, streak)) "1" else "0")
                row.append("|")
            }
            out += "sync $n $row"
        }
        for (s in 0..8) out += "backoff $s ${GarminRadioPolicy.syncBackoff(s)}"
        val l = GarminLiveChannelLinger()
        fun step(label: String, i: GarminRadioInputs, t: Double) {
            val h = l.update(i, t)
            out += "linger $label t=${t.toLong()} hold=${fmt(h.channels)} recheck=${h.recheckAtSeconds?.toLong() ?: "-"}"
        }
        val fg = GarminRadioInputs(appInForeground = true)
        step("open", fg.copy(liveScreenVisible = true, onScreenChannels = setOf(GarminLiveChannel.BODY_BATTERY), explicitChannels = setOf(GarminLiveChannel.SPO2)), 0.0)
        step("bbOff", fg.copy(liveScreenVisible = true), 10.0)
        step("leave", fg, 20.0)
        step("t39", fg, 39.0)
        step("t40", fg, 40.0)
        step("t49", fg, 49.0)
        step("t50", fg, 50.0)
        step("reopen", fg.copy(liveScreenVisible = true), 60.0)
        step("bg", GarminRadioInputs(appInForeground = false), 61.0)
        step("workoutBg", GarminRadioInputs(appInForeground = false, workoutActive = true, onScreenChannels = setOf(GarminLiveChannel.RESPIRATION), explicitChannels = setOf(GarminLiveChannel.SPO2)), 70.0)
        step("workoutFg", fg.copy(workoutActive = true, onScreenChannels = setOf(GarminLiveChannel.RESPIRATION)), 80.0)
        step("workoutEnd", fg, 90.0)
        step("t120", fg, 120.0)
        for (a in GarminLinkActivity.values()) out += "priority ${camel(a)} ${camel(GarminRadioPolicy.connectionPriority(a))}"
        for (f in listOf(false, true)) for (s in listOf(false, true)) {
            out += "discovery fg=$f stored=$s ${camel(GarminRadioPolicy.discoveryAction(f, s))}"
        }
        for (m in GarminConnectionMode.values()) for (h in listOf(false, true)) {
            out += "disconnect ${camel(m)} live=$h ${GarminRadioPolicy.shouldDisconnectAfterSync(m, h)}"
        }
        val tm = GarminRadioTelemetry()
        out += tm.summary(0.0)
        tm.linkUp(100.0); tm.channelOpened(hr, 110.0); tm.channelOpened(rr, 110.0)
        tm.recordBytes(inbound = 4096, outbound = 120); tm.channelClosed(rr, 140.9)
        out += tm.summary(150.5)
        tm.syncSessionCompleted(); tm.linkDown(200.0); tm.linkUp(300.0); tm.channelOpened(rr, 300.0)
        tm.channelOpened(rr, 305.0); tm.recordBytes(outbound = 7); tm.syncSessionCompleted()
        out += tm.summary(330.0)
        tm.linkDown(360.0)
        out += tm.summary(9999.0)
        return out
    }

    /**
     * Each policy channel is the protocol's ML service of the same meaning. Guards the 8 / 10 / 13 mix-up
     * (calories 8, intensity 10, stress 13; Garmin 6/10) and any future renumbering on one side only.
     */
    @Test
    fun channelNumbersMatchTheProtocolServices() {
        val pairs = listOf(
            GarminLiveChannel.HEART_RATE to GarminService.REALTIME_HEART_RATE,
            GarminLiveChannel.STEPS to GarminService.REALTIME_STEPS,
            GarminLiveChannel.CALORIES to GarminService.REALTIME_CALORIES,
            GarminLiveChannel.FLOORS to GarminService.REALTIME_FLOORS,
            GarminLiveChannel.INTENSITY to GarminService.REALTIME_INTENSITY,
            GarminLiveChannel.RR_INTERVALS to GarminService.REALTIME_HRV,
            GarminLiveChannel.STRESS to GarminService.REALTIME_STRESS,
            GarminLiveChannel.ACCELEROMETER to GarminService.REALTIME_ACCELEROMETER,
            GarminLiveChannel.SPO2 to GarminService.REALTIME_SPO2,
            GarminLiveChannel.BODY_BATTERY to GarminService.REALTIME_BODY_BATTERY,
            GarminLiveChannel.RESPIRATION to GarminService.REALTIME_RESPIRATION,
        )
        assertEquals(GarminLiveChannel.entries.size, pairs.size)
        for ((channel, service) in pairs) {
            assertEquals(channel.name, service.raw, channel.serviceId)
            assertTrue(service.name, service.isRealtime)
        }
    }

    /** The Test Centre capture opens HR + 8 / 10 / 13 only through the policy, and nothing in the background. */
    @Test
    fun captureChannelsAreAllowedByThePolicyOnlyInTheForeground() {
        val capture = GarminRealtimeCapture.captureChannels.map { it.raw }.toSet()
        val on = GarminRadioInputs(
            appInForeground = true, liveScreenVisible = true,
            onScreenChannels = setOf(GarminLiveChannel.CALORIES, GarminLiveChannel.INTENSITY, GarminLiveChannel.STRESS),
        )
        val allowed = GarminRadioPolicy.desiredChannels(on).map { it.serviceId }.toSet()
        assertTrue(allowed.containsAll(capture))
        assertTrue(GarminRadioPolicy.desiredChannels(on.copy(appInForeground = false)).isEmpty())
    }

    private companion object {
        // Swift oracle stdout, verbatim.
        val SWIFT_ORACLE = """
            desired 0 -
            desired 1 16,19
            desired 2 -
            desired 3 6,7,8,9,10,12,13,16,19,20,21
            desired 4 6,12
            desired 5 6,7,8,9,10,12,13,16,19,20,21
            desired 6 6,12
            desired 7 6,7,8,9,10,12,13,16,19,20,21
            desired 8 -
            desired 9 6,7,8,9,10,12,13,16,19,20,21
            desired 10 -
            desired 11 6,7,8,9,10,12,13,16,19,20,21
            desired 12 6,12
            desired 13 6,7,8,9,10,12,13,16,19,20,21
            desired 14 6,12
            desired 15 6,7,8,9,10,12,13,16,19,20,21
            desired 16 -
            desired 17 16,19
            desired 18 -
            desired 19 6,7,8,9,10,12,13,16,19,20,21
            desired 20 6,12
            desired 21 6,7,8,9,10,12,13,16,19,20,21
            desired 22 6,12
            desired 23 6,7,8,9,10,12,13,16,19,20,21
            desired 24 -
            desired 25 16,19
            desired 26 -
            desired 27 6,7,8,9,10,12,13,16,19,20,21
            desired 28 6,12
            desired 29 6,7,8,9,10,12,13,16,19,20,21
            desired 30 6,12
            desired 31 6,7,8,9,10,12,13,16,19,20,21
            sync periodic 00000000111111|00000000111111|00000000001111|00000000000011|00000000000011|00000000000011|
            sync connect 00111111111111|00111111111111|00111111111111|00111111111111|00111111111111|00111111111111|
            sync foreground 00111111111111|00111111111111|00111111111111|00111111111111|00111111111111|00111111111111|
            sync manual 11111111111111|11111111111111|11111111111111|11111111111111|11111111111111|11111111111111|
            sync watch 00111111111111|00111111111111|00001111111111|00000011111111|00000011111111|00000011111111|
            backoff 0 1.0
            backoff 1 1.0
            backoff 2 1.0
            backoff 3 2.0
            backoff 4 4.0
            backoff 5 4.0
            backoff 6 4.0
            backoff 7 4.0
            backoff 8 4.0
            linger open t=0 hold=6,12,19,20 recheck=-
            linger bbOff t=10 hold=6,12,20 recheck=40
            linger leave t=20 hold=6,12,20 recheck=40
            linger t39 t=39 hold=6,12,20 recheck=40
            linger t40 t=40 hold=6,12 recheck=50
            linger t49 t=49 hold=6,12 recheck=50
            linger t50 t=50 hold=- recheck=-
            linger reopen t=60 hold=6,12 recheck=-
            linger bg t=61 hold=- recheck=-
            linger workoutBg t=70 hold=6,12 recheck=-
            linger workoutFg t=80 hold=6,12,21 recheck=-
            linger workoutEnd t=90 hold=6,12,21 recheck=120
            linger t120 t=120 hold=- recheck=-
            priority idle lowPower
            priority live balanced
            priority fileTransfer high
            discovery fg=false stored=false none
            discovery fg=false stored=true connectByIdentifier
            discovery fg=true stored=false scan
            discovery fg=true stored=true connectByIdentifier
            disconnect persistentIdle live=false false
            disconnect persistentIdle live=true false
            disconnect connectOnSync live=false true
            disconnect connectOnSync live=true false
            garmin radio: connected=0s bytes_in=0 bytes_out=0 syncs=0 handles=none
            garmin radio: connected=50s bytes_in=4096 bytes_out=120 syncs=0 handles=6:1x/40s,12:1x/30s
            garmin radio: connected=130s bytes_in=4096 bytes_out=127 syncs=2 handles=6:1x/90s,12:2x/60s
            garmin radio: connected=160s bytes_in=4096 bytes_out=127 syncs=2 handles=6:1x/90s,12:2x/90s
        """.trimIndent()
    }
}
