package com.noop.ui

import com.noop.R
import com.noop.analytics.BalanceRead
import com.noop.analytics.WeeklyDigest
import com.noop.analytics.WeeklyDigestEngine
import com.noop.analytics.WeeklyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The localized digest renderer must reproduce the engine's English sentences exactly: the engine
 * strings are pinned by golden tests, so the renderer is checked against them, resolving the real
 * `values/strings.xml` templates (id -> name by reflection on R).
 */
class WeeklyDigestTextTest {

    private val english = object : WeeklyDigestStrings {
        private val idToName: Map<Int, String> by lazy {
            (R.string::class.java.fields + R.plurals::class.java.fields)
                .associate { (it.get(null) as Int) to it.name }
        }
        private val strings = HashMap<String, String>()
        private val plurals = HashMap<String, Map<String, String>>()

        init {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/values/strings.xml"))
            val s = doc.getElementsByTagName("string")
            for (i in 0 until s.length) {
                val e = s.item(i) as Element
                strings[e.getAttribute("name")] = unescape(e.textContent)
            }
            val p = doc.getElementsByTagName("plurals")
            for (i in 0 until p.length) {
                val e = p.item(i) as Element
                val items = e.getElementsByTagName("item")
                plurals[e.getAttribute("name")] = (0 until items.length).associate {
                    val item = items.item(it) as Element
                    item.getAttribute("quantity") to unescape(item.textContent)
                }
            }
        }

        private fun unescape(t: String) = t.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")

        override fun string(id: Int, vararg args: Any): String =
            String.format(Locale.US, strings.getValue(idToName.getValue(id)), *args)

        override fun plural(id: Int, count: Int, vararg args: Any): String {
            val forms = plurals.getValue(idToName.getValue(id))
            return String.format(Locale.US, forms.getValue(if (count == 1) "one" else "other"), *args)
        }
    }

    private fun day(n: Int) = "2026-06-%02d".format(n)

    /** The engine's spec labels differ from a few localized names; compare with the label swapped. */
    private fun assertSameEnglish(d: WeeklyDigest) {
        assertTrue(d.focalPoints.isNotEmpty())
        assertEquals(d.focalPoints.size, d.focalDetails.size)
        val rendered = WeeklyDigestText.focalLines(d, english)
        d.focalPoints.indices.forEach { assertEquals(d.focalPoints[it], rendered[it]) }
    }

    @Test fun moverUpGoodSign() {
        val charge = HashMap<String, Double>()
        for (n in 8..14) charge[day(n)] = 80.0
        for (n in 1..7) charge[day(n)] = 55.0
        assertSameEnglish(WeeklyDigestEngine.build(mapOf(WeeklyMetric.CHARGE to charge), "2026-06-13"))
    }

    @Test fun moverRestingHrWorthALook() {
        val rhr = HashMap<String, Double>()
        for (n in 8..14) rhr[day(n)] = 60.0
        for (n in 1..7) rhr[day(n)] = 52.0
        assertSameEnglish(WeeklyDigestEngine.build(mapOf(WeeklyMetric.RHR to rhr), "2026-06-13"))
    }

    @Test fun moverInPointsWhenPercentDoesNotApply() {
        val eff = HashMap<String, Double>()
        for (n in 8..14) eff[day(n)] = 91.5
        for (n in 1..7) eff[day(n)] = 88.0
        // A personal baseline (4 earlier weeks) is what lets a non-headline metric be a mover.
        listOf("2026-05-05" to 87.0, "2026-05-12" to 88.0, "2026-05-19" to 89.0, "2026-05-26" to 88.0)
            .forEach { eff[it.first] = it.second }
        assertSameEnglish(WeeklyDigestEngine.build(mapOf(WeeklyMetric.SLEEP_EFFICIENCY to eff), "2026-06-13"))
    }

    @Test fun moverInOwnUnitAndLatestRollup() {
        val vo2 = HashMap<String, Double>()
        vo2[day(3)] = 45.0
        listOf("2026-05-06" to 44.0, "2026-05-13" to 45.0, "2026-05-20" to 44.0, "2026-05-27" to 45.0)
            .forEach { vo2[it.first] = it.second }
        vo2[day(10)] = 47.0
        val d = WeeklyDigestEngine.build(mapOf(WeeklyMetric.VO2MAX to vo2), "2026-06-13")
        assertTrue(d.focalPoints[0], d.focalPoints[0].contains("ml/kg/min"))
        val rendered = WeeklyDigestText.focalLines(d, english)
        // Localized name is "VO₂max" where the engine's English label is "VO2 max".
        assertEquals(d.focalPoints[0].replace("VO2 max", "VO₂max"), rendered[0])
    }

    @Test fun balanceLinesMatchEngine() {
        val effort = HashMap<String, Double>(); val charge = HashMap<String, Double>()
        for (n in 8..14) { effort[day(n)] = 80.0; charge[day(n)] = 50.0 }
        val d = WeeklyDigestEngine.build(
            mapOf(WeeklyMetric.EFFORT to effort, WeeklyMetric.CHARGE to charge), "2026-06-10",
        )
        assertEquals(BalanceRead.OVERREACHING, d.balance)
        assertEquals(d.balance.sentence, WeeklyDigestText.balance(d.balance, english))
        BalanceRead.values().forEach { assertEquals(it.sentence, WeeklyDigestText.balance(it, english)) }
    }

    @Test fun steadyWeekWithRest() {
        val charge = HashMap<String, Double>(); val effort = HashMap<String, Double>(); val rest = HashMap<String, Double>()
        for (n in 1..14) { charge[day(n)] = 65.0; effort[day(n)] = 63.0; rest[day(n)] = 84.0 }
        assertSameEnglish(
            WeeklyDigestEngine.build(
                mapOf(WeeklyMetric.CHARGE to charge, WeeklyMetric.EFFORT to effort, WeeklyMetric.REST to rest),
                "2026-06-13",
            ),
        )
    }

    @Test fun steadyWeekWithoutRest() {
        val charge = HashMap<String, Double>()
        for (n in 1..14) charge[day(n)] = 65.0
        assertSameEnglish(WeeklyDigestEngine.build(mapOf(WeeklyMetric.CHARGE to charge), "2026-06-13"))
    }

    @Test fun sparseCurrentWeekPluralAndSingular() {
        val charge = HashMap<String, Double>()
        for (n in 1..7) charge[day(n)] = 70.0
        charge[day(8)] = 40.0
        assertSameEnglish(WeeklyDigestEngine.build(mapOf(WeeklyMetric.CHARGE to charge), "2026-06-08"))
        charge[day(9)] = 40.0
        assertSameEnglish(WeeklyDigestEngine.build(mapOf(WeeklyMetric.CHARGE to charge), "2026-06-09"))
    }

    @Test fun sparsePreviousWeek() {
        val charge = HashMap<String, Double>()
        charge["2026-06-06"] = 70.0
        charge["2026-06-07"] = 74.0
        for (n in 8..12) charge[day(n)] = 41.0
        assertSameEnglish(WeeklyDigestEngine.build(mapOf(WeeklyMetric.CHARGE to charge), "2026-06-12"))
    }

    @Test fun notificationBodyIsTopLineAndNullWhenEmpty() {
        val charge = HashMap<String, Double>()
        for (n in 8..14) charge[day(n)] = 80.0
        for (n in 1..7) charge[day(n)] = 55.0
        val d = WeeklyDigestEngine.build(mapOf(WeeklyMetric.CHARGE to charge), "2026-06-13")
        assertEquals(d.focalPoints.first(), WeeklyDigestText.notificationBody(d, english))
        assertEquals(null, WeeklyDigestText.notificationBody(WeeklyDigestEngine.build(emptyMap(), "2026-06-13"), english))
    }
}
