package com.noop.ui

import com.noop.R
import com.noop.ble.HuaweiConnectReadout
import com.noop.ble.HuaweiSource.LinkPhase
import com.noop.huawei.HuaweiCapabilities
import com.noop.huawei.protocol.HuaweiExpandCapabilities
import com.noop.huawei.protocol.HuaweiSettingRelated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiBandUiTest {

    @Test fun statusFollowsThePhase() {
        assertEquals(R.string.huawei_panel_status_ready, HuaweiBandUi.statusRes(LinkPhase.READY, syncing = false))
        assertEquals(R.string.huawei_panel_status_syncing, HuaweiBandUi.statusRes(LinkPhase.READY, syncing = true))
        assertEquals(R.string.huawei_panel_status_needs_pairing, HuaweiBandUi.statusRes(LinkPhase.NEEDS_PAIRING, syncing = false))
        assertEquals(R.string.huawei_panel_status_failed, HuaweiBandUi.statusRes(LinkPhase.FAILED, syncing = true))
    }

    @Test fun syncNowIsOfferedOnlyWhenItDoesSomething() {
        assertTrue(HuaweiBandUi.canSyncNow(LinkPhase.READY, syncing = false))
        assertFalse(HuaweiBandUi.canSyncNow(LinkPhase.READY, syncing = true))
        assertTrue(HuaweiBandUi.canSyncNow(LinkPhase.FAILED, syncing = false))
        assertFalse(HuaweiBandUi.canSyncNow(LinkPhase.NEEDS_PAIRING, syncing = false))
        assertFalse(HuaweiBandUi.canSyncNow(LinkPhase.AUTHENTICATING, syncing = false))
    }

    @Test fun countdownRunsFromThePrompt() {
        assertEquals(30, HuaweiBandUi.secondsLeft(1_000, 1_000))
        assertEquals(20, HuaweiBandUi.secondsLeft(1_000, 11_500))
        assertEquals(0, HuaweiBandUi.secondsLeft(1_000, 99_000))
        assertEquals(30, HuaweiBandUi.secondsLeft(5_000, 1_000))
    }

    @Test fun accountIdIsOptionalButMustBe17Digits() {
        assertTrue(HuaweiBandUi.accountIdAcceptable(""))
        assertTrue(HuaweiBandUi.accountIdAcceptable("12345678901234567"))
        assertFalse(HuaweiBandUi.accountIdAcceptable("123"))
    }

    @Test fun testCentreLinesShowTheCapabilityBits() {
        assertNull(HuaweiBandUi.capabilityLine(HuaweiConnectReadout()))
        val readout = HuaweiConnectReadout(capabilities = HuaweiCapabilities(
            services = setOf(0x07, 0x01),
            commands = mapOf(0x01 to setOf(0x07, 0x08)),
            expand = HuaweiExpandCapabilities(byteArrayOf(0x05, 0x00, 0x01)),
            settings = HuaweiSettingRelated(truSleepFileSync = true, rriFileSync = false, gpsFileSync = true),
        ))
        assertEquals("37=050001 31=sleep+gps", HuaweiBandUi.capabilityLine(readout))
        assertEquals("01(2) 07", HuaweiBandUi.servicesLine(readout))
    }
}
