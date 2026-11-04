package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure brand catalog: recognition, ordering, and the byte-parity facts (sourceKind / idPrefix /
 * capability / tier). Faithful twin of the Swift DeviceBrandCatalogTests — the SAME cases must hold so the
 * two platforms recognise a device identically.
 */
class DeviceBrandCatalogTest {

    @Test
    fun recognisesEachBrandFromAdvertisedName() {
        val cases = listOf(
            "Amazfit Helio Ring" to "Amazfit",
            "Zepp E" to "Amazfit",
            "Mi Smart Band 8" to "Mi Band",
            "Xiaomi Band 9" to "Mi Band",
            "Garmin Forerunner 265" to "Garmin",
            "fēnix 7" to "Garmin",            // diacritic-folded to "fenix"
            "HRM-Pro" to "Garmin",
            "HUAWEI Band 10-A1B" to "Huawei",
            "Oura Ring" to "Oura",
            "Polar H10" to "Polar",
            "Wahoo TICKR" to "Wahoo",
            "COOSPO HW9" to "Coospo",
            "Scosche Rhythm+" to "Scosche",
            "Magene H64" to "Magene",
        )
        for ((name, expected) in cases) {
            assertEquals(name, expected, DeviceBrandCatalog.specForAdvertisedName(name)?.brand)
        }
    }

    @Test
    fun unknownNameIsNull() {
        assertNull(DeviceBrandCatalog.specForAdvertisedName("Acme HR 3000"))
        assertNull(DeviceBrandCatalog.specForAdvertisedName(""))
    }

    /** Mi Band (a Huami sub-brand) must win over the broader Amazfit family. */
    @Test
    fun miBandWinsOverAmazfit() {
        assertEquals("Mi Band", DeviceBrandCatalog.specForAdvertisedName("Mi Band")?.brand)
        assertEquals("Mi Band", DeviceBrandCatalog.specForAdvertisedName("Smart Band 10")?.brand)
    }

    @Test
    fun routingAndTierFacts() {
        fun spec(brand: String): DeviceBrandSpec =
            requireNotNull(DeviceBrandCatalog.specForBrand(brand)) { "missing catalog row for $brand" }

        assertEquals(SourceKind.huami, spec("Amazfit").sourceKind)
        assertEquals(SourceKind.huami, spec("Mi Band").sourceKind)
        assertEquals(SourceKind.oura, spec("Oura").sourceKind)
        assertEquals(SourceKind.garmin, spec("Garmin").sourceKind)
        assertEquals("garmin", SourceKind.garmin.name)   // stable cross-platform store encoding
        assertEquals(SourceKind.liveBLE, spec("Polar").sourceKind)
        assertEquals(SourceKind.huawei, spec("Huawei").sourceKind)
        assertEquals("huawei", SourceKind.huawei.name)   // stable cross-platform store encoding
        assertEquals("huawei", spec("Huawei").idPrefix)
        assertFalse(spec("Huawei").canStreamLiveHR)
        assertFalse(spec("Huawei").isExperimentalTier)

        assertEquals("huami", spec("Amazfit").idPrefix)
        assertEquals("oura", spec("Oura").idPrefix)
        assertEquals("garmin", spec("Garmin").idPrefix)
        assertEquals("strap", spec("Polar").idPrefix)

        assertFalse(spec("Oura").canStreamLiveHR)
        assertTrue(spec("Amazfit").canStreamLiveHR)
        assertTrue(spec("Garmin").canStreamLiveHR)
        assertTrue(spec("Polar").canStreamLiveHR)

        // Amazfit / Mi Band / Oura are the opt-in experimental tier; Garmin (#43) and generic straps are not.
        for (b in listOf("Amazfit", "Mi Band", "Oura")) assertTrue(b, spec(b).isExperimentalTier)
        for (b in listOf("Garmin", "Polar", "Wahoo", "Coospo", "Scosche", "Magene")) assertFalse(b, spec(b).isExperimentalTier)
    }

    @Test
    fun brandStringsUnique() {
        val brands = DeviceBrandCatalog.all.map { it.brand }
        assertEquals(brands.size, brands.toSet().size)
    }

    /** `isOura` resolves a registry deviceId to its brand by the "<idPrefix>-" prefix, true ONLY for the Oura
     *  ring — what lets the sleep surfaces name an Oura night's provenance "Oura". Twin of Swift
     *  `DeviceBrandCatalogTests.testIsOuraByRegistryIdPrefix` (SAME cases). */
    @Test
    fun isOuraByRegistryIdPrefix() {
        assertTrue(DeviceBrandCatalog.isOura("oura-5C4C0BF8-2DF6-1B3A-18D0-3DF0B3590148"))
        assertTrue(DeviceBrandCatalog.isOura("oura-anything"))
        assertFalse(DeviceBrandCatalog.isOura("whoop-1234"))
        assertFalse(DeviceBrandCatalog.isOura("garmin-1234"))
        assertFalse(DeviceBrandCatalog.isOura("strap-1234"))
        assertFalse(DeviceBrandCatalog.isOura("huami-1234"))
        assertFalse(DeviceBrandCatalog.isOura("some-noop"))
        assertFalse(DeviceBrandCatalog.isOura(""))
        assertFalse(DeviceBrandCatalog.isOura("oura"))
        assertFalse(DeviceBrandCatalog.isOura("neuraloura-1"))
    }

    /** The watch resolver: a paired Garmin or Huawei watch id resolves to its brand row; an export import, a
     *  computed sibling and every other brand do not. Twin of Swift `testWatchSpecForDeviceId` (SAME cases). */
    @Test
    fun watchSpecForDeviceId() {
        assertEquals("Garmin", DeviceBrandCatalog.watchSpecForDeviceId("garmin-AA:BB")?.brand)
        assertEquals("Huawei", DeviceBrandCatalog.watchSpecForDeviceId("huawei-AA:BB:CC:00:11:22")?.brand)
        for (id in listOf("garmin-import", "huawei-import", "garmin-x-noop", "huawei-x-noop", "garmin", "huawei",
                "my-whoop", "oura-1", "huami-1", "strap-1", "")) {
            assertNull(id, DeviceBrandCatalog.watchSpecForDeviceId(id))
        }
        assertEquals("Huawei", DeviceBrandCatalog.watchSpecForSource("huawei-x-noop")?.brand)
        assertNull(DeviceBrandCatalog.watchSpecForSource("huawei-import"))
    }
}
