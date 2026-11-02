package com.noop.garmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The send allowlist (BLE safety contract). Every GarminOutbound case is enumerated through the exhaustive
 * `when` in oracleOutboundSamples; each encoded frame's GFDI type, and each system event's ordinal, must be
 * on the explicit lists below. Kotlin twin of GarminOutboundAllowlistTests.swift.
 */
class GarminOutboundAllowlistTest {

    private val allowedTypes = setOf(5000, 5002, 5007, 5030, 5031, 5043, 5050)
    private val allowedSystemEvents = setOf(0, 1, 3, 4, 5, 6, 7, 8, 9, 12, 13, 14, 15, 16)
    private val forbiddenTypes = setOf(5003, 5005, 5006, 5008) + (5102..5112)
    private val forbiddenSystemEvents = setOf(2, 10, 11, 17)

    @Test fun everyOutboundCaseIsOnTheAllowlist() {
        for (kind in GarminOutbound.Kind.entries) {
            val samples = oracleOutboundSamples(kind)
            assertTrue("$kind has no sample", samples.isNotEmpty())
            for (sample in samples) {
                assertEquals(kind, sample.kind)
                val frame = (GarminGfdi.decode(GarminCobs.decode(sample.frame().bytes)!!) as GarminGfdi.Result.Success).frame
                assertTrue("$kind emits type ${frame.type}", frame.type in allowedTypes)
                assertFalse(frame.type in forbiddenTypes)
                if (frame.type == 5030) {
                    val ordinal = frame.payload[0].toInt() and 0xFF
                    assertTrue("system event $ordinal", ordinal in allowedSystemEvents)
                    assertFalse(ordinal in forbiddenSystemEvents)
                }
                if (frame.type == 5043) {
                    val chunk = (GarminInbound.decode(frame) as GarminInbound.ProtobufRequest).chunk
                    val smart = GarminProtoReader.fields(chunk.data)!!
                    assertEquals(listOf(43), smart.map { it.first })
                    val fields = GarminProtoReader.fields(smart[0].second.data!!)!!.map { it.first }
                    assertTrue("FileAccess fields $fields", fields.all { it == 1 || it == 9 })
                }
            }
        }
    }

    @Test fun safeSystemEventsExcludeDestructiveOrdinals() {
        assertEquals(allowedSystemEvents, GarminSafeSystemEvent.entries.map { it.raw }.toSet())
        assertTrue(GarminSafeSystemEvent.entries.none { it.raw in forbiddenSystemEvents })
    }

    @Test fun filterAndCapabilityEnumsAreExplicit() {
        assertEquals(listOf(0, 1, 3), GarminFilterType.entries.map { it.raw })
        assertEquals(listOf(0, 3, 4, 5, 55, 71, 76, 90), GarminCapability.entries.map { it.bit })
    }
}
