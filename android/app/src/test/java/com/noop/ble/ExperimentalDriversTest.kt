package com.noop.ble

import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the DETERMINISTIC pieces of the experimental clean-room BLE drivers: the Huami custom HR parse,
 * the brand recogniser, and the SourceKind routing for the Huami tier. The live BLE I/O itself can't be
 * unit-tested on the JVM (android.bluetooth needs a real radio + a real band), so these tests cover the
 * pure logic the drivers stand on. Faithful Kotlin twin of StrandTests/ExperimentalDriversTests.swift,
 * mirroring the [StandardHeartRate] / [FitnessMachine] test discipline.
 */
class ExperimentalDriversTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    // MARK: - Huami custom HR parse

    @Test
    fun huamiTwoByteStatusHr() {
        // [status, hr] — byte 0 is a status/flags byte, byte 1 the bpm.
        assertEquals(72, HuamiHeartRate.parse(bytes(0x00, 72)))
        assertEquals(58, HuamiHeartRate.parse(bytes(0x10, 58)))   // non-zero status ignored
    }

    @Test
    fun huamiOneByteHr() {
        assertEquals(65, HuamiHeartRate.parse(bytes(65)))
    }

    @Test
    fun huamiNoReadingSentinelsAreNull() {
        // 0 = "no reading" → null (we show "—", never a fake 0). 255 = off-wrist / no-contact → null.
        assertNull(HuamiHeartRate.parse(bytes(0x00, 0)))
        assertNull(HuamiHeartRate.parse(bytes(0)))
        assertNull(HuamiHeartRate.parse(bytes(0x00, 255)))
        assertNull(HuamiHeartRate.parse(bytes(255)))
    }

    @Test
    fun huamiEmptyIsNull() {
        assertNull(HuamiHeartRate.parse(ByteArray(0)))
    }

    @Test
    fun huamiHighValueParses() {
        // The caller's 30..220 gate (not the parser) drops out-of-physiology values.
        assertEquals(200, HuamiHeartRate.parse(bytes(0x00, 200)))
    }

    /// The Swift and Kotlin parsers must agree byte-for-byte on the same fixture (cross-platform parity).
    @Test
    fun huamiCrossPlatformParity() {
        // Same fixtures asserted in the Swift twin.
        assertEquals(72, HuamiHeartRate.parse(bytes(0x00, 72)))
        assertEquals(65, HuamiHeartRate.parse(bytes(65)))
        assertNull(HuamiHeartRate.parse(bytes(0x00, 255)))
    }

    // MARK: - Brand recognition

    @Test
    fun recogniseAmazfitFamily() {
        assertEquals(ExperimentalBrand.AMAZFIT, ExperimentalBrand.recognise("Amazfit GTR 4"))
        assertEquals(ExperimentalBrand.AMAZFIT, ExperimentalBrand.recognise("Amazfit Helio Ring"))
        assertEquals(ExperimentalBrand.AMAZFIT, ExperimentalBrand.recognise("Zepp E"))
    }

    @Test
    fun recogniseMiBand() {
        assertEquals(ExperimentalBrand.MI_BAND, ExperimentalBrand.recognise("Mi Band 7"))
        assertEquals(ExperimentalBrand.MI_BAND, ExperimentalBrand.recognise("Xiaomi Smart Band 8"))
    }

    @Test
    fun recogniseGarmin() {
        // Same discovery inputs and routing assertions as ExperimentalDriversTests.swift.
        for (name in listOf("Garmin Instinct 2", "Garmin Forerunner 265", "FORERUNNER 265",
                            "fenix 7", "fēnix 7", "vívoactive 5", "Venu 3", "Instinct 2", "epix Pro", "vívosmart 5", "HRM-Pro")) {
            val brand = ExperimentalBrand.recognise(name)
            assertEquals(name, ExperimentalBrand.GARMIN, brand)
            assertEquals(name, SourceKind.garmin, brand?.sourceKind)   // GFDI pairing; Broadcast HR rows stay liveBLE
            assertEquals(name, "garmin", brand?.idPrefix)
            assertEquals(name, true, brand?.canStreamLiveHR)
        }
    }

    @Test
    fun garminRecognitionDoesNotCaptureOtherBrands() {
        assertEquals(ExperimentalBrand.AMAZFIT, ExperimentalBrand.recognise("Amazfit GTS"))
        for (name in listOf("Polar H10", "Wahoo TICKR", "Some Random Speaker", "", "   ")) {
            assertNull(name, ExperimentalBrand.recognise(name))
        }
    }

    @Test
    fun recogniseOura() {
        assertEquals(ExperimentalBrand.OURA, ExperimentalBrand.recognise("Oura Ring"))
    }

    @Test
    fun recogniseHuawei() {
        for (name in listOf("HUAWEI Band 10-A1B", "Huawei Band 10")) {
            val brand = ExperimentalBrand.recognise(name)
            assertEquals(name, ExperimentalBrand.HUAWEI, brand)
            assertEquals(name, SourceKind.huawei, brand?.sourceKind)
            assertEquals(name, "huawei", brand?.idPrefix)
            assertEquals(name, false, brand?.canStreamLiveHR)   // no live HR stream in NOOP yet
        }
        // Not every Huawei name is the Band 10, and no other brand claims it.
        assertNull(ExperimentalBrand.recognise("HUAWEI WATCH GT 4"))
    }

    @Test
    fun unknownNameIsNull() {
        // A Polar HR strap is the GENERIC path, not this experimental tier.
        assertNull(ExperimentalBrand.recognise("Polar H10"))
        assertNull(ExperimentalBrand.recognise(""))
        assertNull(ExperimentalBrand.recognise("Some Random Speaker"))
    }

    @Test
    fun onlyOuraCannotStreamLive() {
        assertFalse(ExperimentalBrand.OURA.canStreamLiveHR)
        assertTrue(ExperimentalBrand.AMAZFIT.canStreamLiveHR)
        assertTrue(ExperimentalBrand.MI_BAND.canStreamLiveHR)
        assertTrue(ExperimentalBrand.GARMIN.canStreamLiveHR)
    }

    // MARK: - Garmin is the standard path, not a proprietary one

    @Test
    fun garminBroadcastGuidanceExists() {
        assertTrue(GarminBroadcast.broadcastHint.isNotEmpty())
    }

    // MARK: - SourceKind routing

    private fun row(id: String, brand: String, sourceKind: SourceKind) = PairedDeviceRow(
        id = id, brand = brand, model = "x", nickname = null, peripheralId = "AA",
        sourceKind = sourceKind.name, capabilities = "hr", status = "paired", addedAt = 0, lastSeenAt = 0,
    )

    /// A `huami` device is NOT a WHOOP, so the SourceCoordinator routes it to a non-WHOOP source — the
    /// WHOOP path is never stolen by an Amazfit / Mi Band.
    @Test
    fun huamiSourceKindIsNotWhoop() {
        val huami = row("huami-1", "Amazfit", SourceKind.huami)
        assertFalse(SourceCoordinator.isWhoop(huami))
        assertFalse(SourceCoordinator.isWhoop("huami-1", listOf(huami)))
        // The enum name is stable for the cross-platform store encoding.
        assertEquals("huami", SourceKind.huami.name)
    }

    /// A Garmin row is a plain `liveBLE` device (standard broadcast HR), branded "Garmin", non-WHOOP.
    @Test
    fun garminSourceKindIsLiveBleNonWhoop() {
        val garmin = row("garmin-1", "Garmin", SourceKind.liveBLE)
        assertFalse(SourceCoordinator.isWhoop(garmin))
        assertFalse(SourceCoordinator.isWhoop("garmin-1", listOf(garmin)))
    }

    /// A watch paired over GFDI carries the `garmin` kind (GarminSource); a Broadcast-HR row keeps `liveBLE`.
    @Test
    fun garminGfdiRowIsGarminKindNonWhoop() {
        val watch = row("garmin-2", "Garmin", SourceKind.garmin)
        assertFalse(SourceCoordinator.isWhoop(watch))
        assertFalse(SourceCoordinator.isWhoop("garmin-2", listOf(watch)))
        assertEquals(SourceKind.garmin, ExperimentalBrand.GARMIN.sourceKind)
    }

    /** A Huawei Band 10 row is non-WHOOP; the coordinator routes it to its own HuaweiSource. */
    @Test
    fun huaweiRowIsHuaweiKindNonWhoop() {
        val band = row("huawei-1", "Huawei", SourceKind.huawei)
        assertFalse(SourceCoordinator.isWhoop(band))
        assertFalse(SourceCoordinator.isWhoop("huawei-1", listOf(band)))
        assertEquals("huawei", SourceKind.huawei.name)
    }
}
