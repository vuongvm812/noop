package com.noop.huawei.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The send allowlist (BLE safety contract). HuaweiRequest can only carry a HuaweiCommand, so a command
 * absent from the enum cannot be sent; this test pins the enum to the exact reviewed set and checks no
 * banned service or command crept in.
 */
class HuaweiOutboundAllowlistTest {

    private val allowed = setOf(
        0x01 to 0x01, 0x01 to 0x02, 0x01 to 0x03, 0x01 to 0x07, 0x01 to 0x08, 0x01 to 0x0E, 0x01 to 0x0F,
        0x01 to 0x13, 0x01 to 0x28, 0x01 to 0x2C, 0x01 to 0x31, 0x01 to 0x33, 0x01 to 0x37,
        // History sync (#82): FitnessData reads and the three switches, Workout reads, band → phone file
        // download over 0x0A and 0x2C (the 2C block request appears twice: encrypted and plaintext), P2P.
        0x07 to 0x0A, 0x07 to 0x0B, 0x07 to 0x0C, 0x07 to 0x0D, 0x07 to 0x17, 0x07 to 0x23, 0x07 to 0x24,
        0x17 to 0x07, 0x17 to 0x08, 0x17 to 0x0A, 0x17 to 0x15,
        // Glue (#83): the clock, workout pace reads, the optional account id.
        0x01 to 0x05, 0x17 to 0x0C, 0x1A to 0x01, 0x1A to 0x05,
        0x0A to 0x01, 0x0A to 0x02, 0x0A to 0x03, 0x0A to 0x04, 0x0A to 0x06,
        0x2C to 0x01, 0x2C to 0x03, 0x2C to 0x04, 0x2C to 0x06,
        0x34 to 0x01,
    )

    /** Factory reset; OTA; file upload; watchface; apps; ephemeris (two services). */
    private val bannedCommands = setOf(0x01 to 0x0D)
    private val bannedServices = setOf(0x09, 0x28, 0x27, 0x2A, 0x1F, 0x1C)

    @Test fun enumIsExactlyTheReviewedSet() {
        assertEquals(allowed, HuaweiCommand.entries.map { it.serviceId to it.commandId }.toSet())
        assertEquals(allowed.size + 1, HuaweiCommand.entries.size)
        assertEquals(listOf(HuaweiCommand.FILE2C_BLOCK, HuaweiCommand.FILE2C_BLOCK_PLAIN),
            HuaweiCommand.entries.filter { it.serviceId == 0x2C && it.commandId == 0x04 })
    }

    @Test fun bannedCommandsAreUnrepresentable() {
        for (c in HuaweiCommand.entries) {
            assertFalse("$c is banned", (c.serviceId to c.commandId) in bannedCommands)
            assertFalse("$c uses a banned service", c.serviceId in bannedServices)
        }
    }

    @Test fun theSwitchesCanOnlyBeTurnedOn() {
        for (c in HuaweiRequest.SWITCHES) {
            assertEquals(c, HuaweiRequest.of(c, HuaweiTlv().putBoolean(1, true)).command)
            assertEquals(c, HuaweiRequest.of(c, HuaweiTlv().putInt(1, 1)).command)
            assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.of(c, HuaweiTlv().putBoolean(1, false)) }
            assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.of(c, HuaweiTlv().putInt(1, 0)) }
            assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.of(c, HuaweiTlv().putBoolean(1, true).putByte(2, 5)) }
            assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.of(c, HuaweiTlv()) }
        }
    }

    @Test fun setTimeCarriesOnlyATimeAndAnOffset() {
        val ok = HuaweiDeviceConfig.SetTime.request(1_700_000_000L, 3_600)
        assertEquals(HuaweiCommand.SET_TIME, ok.command)
        assertEquals(1_700_000_000L, ok.tlv.u32(0x01))
        assertArrayEquals(byteArrayOf(1, 0), ok.tlv.bytes(0x02))
        // West of UTC: hours + 0x80, minutes as Java's signed remainder (-3:30 -> 0x83, -30).
        assertArrayEquals(byteArrayOf(0x83.toByte(), (-30).toByte()), HuaweiDeviceConfig.SetTime.request(0, -12_600).tlv.bytes(0x02))
        assertArrayEquals(byteArrayOf(5, 30), HuaweiDeviceConfig.SetTime.request(0, 19_800).tlv.bytes(0x02))
        assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.of(HuaweiCommand.SET_TIME, HuaweiTlv().putInt(1, 5)) }
        assertThrows(IllegalArgumentException::class.java) {
            HuaweiRequest.of(HuaweiCommand.SET_TIME, HuaweiTlv().putInt(1, 5).putShort(2, 0).putByte(3, 1))
        }
    }

    @Test fun accountCarriesOnlyASeventeenDigitId() {
        val id = "12345678901234567"
        assertEquals(HuaweiCommand.ACCOUNT, HuaweiDeviceConfig.Account.request(id, extended = false, pairingOptimisation = true).command)
        val ext = HuaweiDeviceConfig.Account.request(id, extended = true, pairingOptimisation = true)
        assertEquals(HuaweiCommand.ACCOUNT_EXTENDED, ext.command)
        assertEquals(id, ext.tlv.string(0x01))
        assertEquals(1, ext.tlv.byte(0x03))
        assertFalse(HuaweiDeviceConfig.Account.request(id, extended = true, pairingOptimisation = false).tlv.contains(0x03))
        for (bad in listOf("", "1234", "1234567890123456X", "123456789012345678")) {
            for (c in listOf(HuaweiCommand.ACCOUNT, HuaweiCommand.ACCOUNT_EXTENDED)) {
                assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.of(c, HuaweiTlv().putString(1, bad)) }
            }
        }
        // Clearing the account (an empty tag 1, or a lone 00) is never sent.
        assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.of(HuaweiCommand.ACCOUNT, HuaweiTlv().put(1)) }
        assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.of(HuaweiCommand.ACCOUNT_EXTENDED, HuaweiTlv().putByte(1, 0)) }
        assertThrows(IllegalArgumentException::class.java) {
            HuaweiRequest.of(HuaweiCommand.ACCOUNT, HuaweiTlv().putString(1, id).putByte(3, 1))
        }
    }

    @Test fun p2pIsLimitedToTheDictionarySync() {
        fun p2p(cmd: Int, src: String, dst: String) =
            HuaweiTlv().putByte(1, cmd).putShort(2, 1).putString(3, src).putString(4, dst)
        val ok = p2p(2, "hw.unitedevice.datadictionarysync", "hw.watch.health.filesync")
        assertEquals(HuaweiCommand.P2P, HuaweiRequest.of(HuaweiCommand.P2P, ok).command)
        // Another service on the band (a file push, a watchface), an unknown command, or a smuggled duplicate tag.
        assertThrows(IllegalArgumentException::class.java) {
            HuaweiRequest.of(HuaweiCommand.P2P, p2p(2, "hw.unitedevice.datadictionarysync", "hw.watch.watchface"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HuaweiRequest.of(HuaweiCommand.P2P, p2p(4, "com.huawei.health", "hw.watch.health.filesync"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HuaweiRequest.of(HuaweiCommand.P2P, ok.copy().putString(4, "hw.watch.watchface"))
        }
    }

    @Test fun authStepsArePlaintextAndReadsAreEncrypted() {
        val plaintext = setOf(
            HuaweiCommand.LINK_PARAMS, HuaweiCommand.SECURITY_NEGOTIATION, HuaweiCommand.PIN_CODE, HuaweiCommand.HICHAIN,
            HuaweiCommand.AUTH, HuaweiCommand.BOND_PARAMS, HuaweiCommand.BOND,
        )
        for (c in HuaweiCommand.entries) {
            if (c == HuaweiCommand.FILE2C_BLOCK_PLAIN) continue
            assertEquals(c.toString(), c !in plaintext, c.encrypted)
        }
        assertFalse("sent plaintext only when the band's 2C/03 reply asks for it", HuaweiCommand.FILE2C_BLOCK_PLAIN.encrypted)
        assertFalse(HuaweiCommand.LINK_PARAMS.sliced)
    }

    @Test fun keyInstallingRequestsNeedTheFirstPairingGrant() {
        val bond = HuaweiTlv().put(1)
        assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.of(HuaweiCommand.BOND, bond) }
        val op1 = HuaweiTlv().putString(1, "{}").putByte(2, 1)
        val op2 = HuaweiTlv().putString(1, "{}").putByte(2, 2)
        assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.of(HuaweiCommand.HICHAIN, op1) }
        assertEquals(HuaweiCommand.HICHAIN, HuaweiRequest.of(HuaweiCommand.HICHAIN, op2).command)

        val grant = HuaweiFirstPairingGrant.forPairingWizard()
        assertEquals(HuaweiCommand.BOND, HuaweiRequest.firstPairing(HuaweiCommand.BOND, bond, grant).command)
        assertEquals(HuaweiCommand.HICHAIN, HuaweiRequest.firstPairing(HuaweiCommand.HICHAIN, op1, grant).command)
        assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.firstPairing(HuaweiCommand.HICHAIN, op2, grant) }
        assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.firstPairing(HuaweiCommand.AUTH, bond, grant) }
        // A duplicate or empty operation tag is treated as operation 1.
        val sneaky = HuaweiTlv().putString(1, "{}").putByte(2, 2).putByte(2, 1)
        assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.of(HuaweiCommand.HICHAIN, sneaky) }
        assertThrows(IllegalArgumentException::class.java) { HuaweiRequest.of(HuaweiCommand.HICHAIN, HuaweiTlv().put(2)) }
    }

    @Test fun requestPayloadCannotBeMutatedAfterTheCheck() {
        val tlv = HuaweiTlv().putString(1, "{}").putByte(2, 2)
        val request = HuaweiRequest.of(HuaweiCommand.HICHAIN, tlv)
        tlv.remove(2)
        tlv.putByte(2, 1)
        request.tlv.putByte(2, 1)
        assertEquals(2, request.tlv.byte(2))
        assertTrue(HuaweiRequest.needsFirstPairing(HuaweiCommand.HICHAIN, tlv))
    }
}
