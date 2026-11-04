package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #93: the Today Refresh button's resolver. Twin of the Swift `TodayRefreshControlTests`.
 *
 * [oracle_matchesSwiftTwin] holds the stdout of the Swift twin (`Strand/Screens/TodayRefreshControl.swift`)
 * compiled standalone (`swiftc -O TodayRefreshControl.swift main.swift`) over the WHOLE input space of every
 * function, so the two cannot drift without this failing. Regenerate it the same way if the rule changes.
 */
class TodayRefreshControlTest {

    private fun b(x: Boolean) = if (x) "1" else "0"
    private fun f(k: TodayRefreshLink) = "${b(k.linkUp)}${b(k.canSync)}${b(k.syncing)}"
    private val bools = listOf(false, true)

    private fun kotlinOracle(): String {
        val out = mutableListOf<String>()
        for (a in bools) for (g in bools) for (h in bools) for (o in bools) {
            val s = TodayRefreshControl.source(activeIsWhoop = a, garminLive = g, huaweiLive = h, ouraActive = o)
            out += "S${b(a)}${b(g)}${b(h)}${b(o)}=${s.name.lowercase()}"
        }
        for (s in TodayRefreshSource.values()) for (l in bools) for (c in bools) for (y in bools) for (r in bools) {
            val st = TodayRefreshControl.resolve(s, TodayRefreshLink(linkUp = l, canSync = c, syncing = y), rescanOffered = r)
            out += "R${s.name.lowercase()}${b(l)}${b(c)}${b(y)}${b(r)}=${st.name.lowercase()}"
        }
        for (c in bools) for (bo in bools) for (bf in bools) for (hr in bools) {
            val k = TodayRefreshControl.whoop(connected = c, bonded = bo, backfilling = bf, historyReady = hr)
            out += "W${b(c)}${b(bo)}${b(bf)}${b(hr)}=${f(k)}"
        }
        for (x in bools) for (y in bools) {
            out += "G${b(x)}${b(y)}=${f(TodayRefreshControl.garmin(linkReady = x, syncing = y))}"
            out += "H${b(x)}${b(y)}=${f(TodayRefreshControl.huawei(ready = x, syncing = y))}"
            out += "O${b(x)}${b(y)}=${f(TodayRefreshControl.oura(connecting = x, authenticated = y))}"
        }
        return out.joinToString(" ")
    }

    @Test
    fun oracle_matchesSwiftTwin() {
        val swift = listOf(
            "S0000=none S0001=oura S0010=huawei S0011=huawei S0100=garmin S0101=garmin S0110=garmin S0111=garmin",
            "S1000=whoop S1001=whoop S1010=whoop S1011=whoop S1100=whoop S1101=whoop S1110=whoop S1111=whoop",
            "Rwhoop0000=unavailable Rwhoop0001=rescan Rwhoop0010=unavailable Rwhoop0011=rescan Rwhoop0100=unavailable Rwhoop0101=rescan Rwhoop0110=unavailable Rwhoop0111=rescan",
            "Rwhoop1000=unavailable Rwhoop1001=unavailable Rwhoop1010=syncing Rwhoop1011=syncing Rwhoop1100=ready Rwhoop1101=ready Rwhoop1110=syncing Rwhoop1111=syncing",
            "Rgarmin0000=unavailable Rgarmin0001=rescan Rgarmin0010=unavailable Rgarmin0011=rescan Rgarmin0100=unavailable Rgarmin0101=rescan Rgarmin0110=unavailable Rgarmin0111=rescan",
            "Rgarmin1000=unavailable Rgarmin1001=unavailable Rgarmin1010=syncing Rgarmin1011=syncing Rgarmin1100=ready Rgarmin1101=ready Rgarmin1110=syncing Rgarmin1111=syncing",
            "Rhuawei0000=unavailable Rhuawei0001=rescan Rhuawei0010=unavailable Rhuawei0011=rescan Rhuawei0100=unavailable Rhuawei0101=rescan Rhuawei0110=unavailable Rhuawei0111=rescan",
            "Rhuawei1000=unavailable Rhuawei1001=unavailable Rhuawei1010=syncing Rhuawei1011=syncing Rhuawei1100=ready Rhuawei1101=ready Rhuawei1110=syncing Rhuawei1111=syncing",
            "Roura0000=unavailable Roura0001=rescan Roura0010=unavailable Roura0011=rescan Roura0100=unavailable Roura0101=rescan Roura0110=unavailable Roura0111=rescan",
            "Roura1000=unavailable Roura1001=unavailable Roura1010=syncing Roura1011=syncing Roura1100=ready Roura1101=ready Roura1110=syncing Roura1111=syncing",
            "Rnone0000=hidden Rnone0001=rescan Rnone0010=hidden Rnone0011=rescan Rnone0100=hidden Rnone0101=rescan Rnone0110=hidden Rnone0111=rescan",
            "Rnone1000=hidden Rnone1001=rescan Rnone1010=hidden Rnone1011=rescan Rnone1100=hidden Rnone1101=rescan Rnone1110=hidden Rnone1111=rescan",
            "W0000=000 W0001=000 W0010=001 W0011=001 W0100=000 W0101=000 W0110=001 W0111=001",
            "W1000=100 W1001=100 W1010=101 W1011=101 W1100=100 W1101=110 W1110=101 W1111=101",
            "G00=000 H00=000 O00=000 G01=001 H01=001 O01=110 G10=110 H10=110",
            "O10=101 G11=101 H11=101 O11=110",
        ).joinToString(" ")
        assertEquals(swift, kotlinOracle())
    }

    /** The WHOOP leg must offer Refresh exactly when pull-to-sync is enabled: one gate, two controls. */
    @Test
    fun whoopCanSync_isThePullToSyncGate() {
        for (c in bools) for (bo in bools) for (bf in bools) for (hr in bools) {
            assertEquals(
                todayPullToSyncEnabled(c, bo, bf, hr),
                TodayRefreshControl.whoop(c, bo, bf, hr).canSync,
            )
        }
    }

    @Test
    fun whoopDisconnected_keepsTheRescanDisc() {
        val link = TodayRefreshControl.whoop(connected = false, bonded = false, backfilling = false, historyReady = false)
        assertEquals(TodayRefreshState.RESCAN, TodayRefreshControl.resolve(TodayRefreshSource.WHOOP, link, rescanOffered = true))
    }

    @Test
    fun whoopBackfilling_spins() {
        val link = TodayRefreshControl.whoop(connected = true, bonded = true, backfilling = true, historyReady = true)
        assertEquals(TodayRefreshState.SYNCING, TodayRefreshControl.resolve(TodayRefreshSource.WHOOP, link, rescanOffered = false))
    }

    /** A 5/MG whose handshake has not run: connected and "bonded", but `syncNow` would decline. */
    @Test
    fun whoopNotHistoryReady_isGreyedNotTappable() {
        val link = TodayRefreshControl.whoop(connected = true, bonded = true, backfilling = false, historyReady = false)
        assertEquals(TodayRefreshState.UNAVAILABLE, TodayRefreshControl.resolve(TodayRefreshSource.WHOOP, link, rescanOffered = false))
    }

    @Test
    fun garminReady_isTappable_andSyncingSpins() {
        assertEquals(
            TodayRefreshState.READY,
            TodayRefreshControl.resolve(TodayRefreshSource.GARMIN, TodayRefreshControl.garmin(linkReady = true, syncing = false), false),
        )
        assertEquals(
            TodayRefreshState.SYNCING,
            TodayRefreshControl.resolve(TodayRefreshSource.GARMIN, TodayRefreshControl.garmin(linkReady = true, syncing = true), false),
        )
    }

    @Test
    fun huaweiNotReady_withoutRescan_isGreyed() {
        assertEquals(
            TodayRefreshState.UNAVAILABLE,
            TodayRefreshControl.resolve(TodayRefreshSource.HUAWEI, TodayRefreshControl.huawei(ready = false, syncing = false), false),
        )
    }

    @Test
    fun ouraReconnecting_spins() {
        assertEquals(
            TodayRefreshState.SYNCING,
            TodayRefreshControl.resolve(TodayRefreshSource.OURA, TodayRefreshControl.oura(connecting = true, authenticated = false), false),
        )
    }

    /** A generic HR strap: nothing to sync, so the slot stays the balancing spacer it was. */
    @Test
    fun noSyncableSource_isHidden() {
        assertEquals(
            TodayRefreshState.HIDDEN,
            TodayRefreshControl.resolve(TodayRefreshSource.NONE, TodayRefreshLink(true, true, false), rescanOffered = false),
        )
    }

    @Test
    fun source_whoopFirst_thenGarmin_thenHuawei_thenOura() {
        assertEquals(TodayRefreshSource.WHOOP, TodayRefreshControl.source(true, true, true, true))
        assertEquals(TodayRefreshSource.GARMIN, TodayRefreshControl.source(false, true, true, true))
        assertEquals(TodayRefreshSource.HUAWEI, TodayRefreshControl.source(false, false, true, true))
        assertEquals(TodayRefreshSource.OURA, TodayRefreshControl.source(false, false, false, true))
        assertEquals(TodayRefreshSource.NONE, TodayRefreshControl.source(false, false, false, false))
    }
}
