package com.noop.huawei.protocol

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
    )

    /** Factory reset; OTA; file upload; watchface; apps; ephemeris (two services). */
    private val bannedCommands = setOf(0x01 to 0x0D)
    private val bannedServices = setOf(0x09, 0x28, 0x27, 0x2A, 0x1F, 0x1C)

    @Test fun enumIsExactlyTheReviewedSet() {
        assertEquals(allowed, HuaweiCommand.entries.map { it.serviceId to it.commandId }.toSet())
        assertEquals(allowed.size, HuaweiCommand.entries.size)
    }

    @Test fun bannedCommandsAreUnrepresentable() {
        for (c in HuaweiCommand.entries) {
            assertFalse("$c is banned", (c.serviceId to c.commandId) in bannedCommands)
            assertFalse("$c uses a banned service", c.serviceId in bannedServices)
            assertEquals("only Device Config is allowlisted", 0x01, c.serviceId)
        }
    }

    @Test fun authStepsArePlaintextAndReadsAreEncrypted() {
        val plaintext = setOf(
            HuaweiCommand.LINK_PARAMS, HuaweiCommand.SECURITY_NEGOTIATION, HuaweiCommand.PIN_CODE, HuaweiCommand.HICHAIN,
            HuaweiCommand.AUTH, HuaweiCommand.BOND_PARAMS, HuaweiCommand.BOND,
        )
        for (c in HuaweiCommand.entries) assertEquals(c.toString(), c !in plaintext, c.encrypted)
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
