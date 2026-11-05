package com.noop.ui

import com.noop.analytics.ActivityCost
import com.noop.analytics.BehaviorEffect
import com.noop.analytics.DoseResponse
import com.noop.analytics.DosedBehavior
import com.noop.analytics.EffectRanker
import com.noop.analytics.RankedEffect
import com.noop.analytics.ScoreConfidence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The view-layer renderer must read exactly like the (parity-pinned) engine sentences in English.
 * A plain JVM test has no Android resources, so the English text is read straight from
 * `res/values/strings.xml` and resolved through the generated `R` ids by reflection.
 */
class InsightsSentencesTest {

    private object EnglishFromXml : InsightsText {
        private val strings = HashMap<String, String>()
        private val plurals = HashMap<String, Map<String, String>>()
        private val stringNames = HashMap<Int, String>()
        private val pluralNames = HashMap<Int, String>()

        init {
            val file = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
                .map(::File).first { it.exists() }
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                when (n.nodeName) {
                    "string" -> strings[n.attributes.getNamedItem("name").nodeValue] = unescape(n.textContent)
                    "plurals" -> {
                        val items = HashMap<String, String>()
                        val kids = n.childNodes
                        for (j in 0 until kids.length) {
                            val k = kids.item(j)
                            if (k.nodeName == "item") {
                                items[k.attributes.getNamedItem("quantity").nodeValue] = unescape(k.textContent)
                            }
                        }
                        plurals[n.attributes.getNamedItem("name").nodeValue] = items
                    }
                }
            }
            for (f in Class.forName("com.noop.R\$string").fields) stringNames[f.getInt(null)] = f.name
            for (f in Class.forName("com.noop.R\$plurals").fields) pluralNames[f.getInt(null)] = f.name
        }

        private fun unescape(s: String): String =
            Regex("\\\\(.)").replace(s) { if (it.groupValues[1] == "n") "\n" else it.groupValues[1] }

        override fun string(id: Int, vararg args: Any): String =
            String.format(Locale.US, strings.getValue(stringNames.getValue(id)), *args)

        override fun plural(id: Int, count: Int, vararg args: Any): String {
            val items = plurals.getValue(pluralNames.getValue(id))
            return String.format(Locale.US, items[if (count == 1) "one" else "other"] ?: items.getValue("other"), *args)
        }
    }

    private fun effect(delta: Double, pct: Double?, withMean: Double = 61.0, without: Double = 69.0) =
        BehaviorEffect(
            behavior = "Alcohol", outcome = "Charge", meanWith = withMean, meanWithout = without,
            delta = delta, pctChange = pct, nWith = 140, nWithout = 498,
            cohensD = 0.4, pApprox = 0.01, significant = true,
        )

    @Test
    fun effectMatchesEngine() {
        val cases = listOf(
            effect(-8.0, -11.6),
            effect(8.0, 12.4, 69.0, 61.0),
            effect(-2.46, null),
            effect(3.04, null),
            effect(0.0, 0.0, 65.0, 65.0),
        )
        for (e in cases) assertEquals(EffectRanker.sentence(e), InsightsSentences.effect(e, EnglishFromXml))
    }

    @Test
    fun activityCostMatchesEngine() {
        val cases = listOf(0.4 to null, -0.9 to 2, 4.2 to 3, 1.0 to 1, 6.0 to null, -3.0 to 1, -2.6 to null)
        for ((delta, days) in cases) {
            val c = ActivityCost("Running", delta, 60.0, 64.0, days, 7, ScoreConfidence.SOLID)
            assertEquals(c.sentence(), InsightsSentences.activityCost(c, EnglishFromXml))
        }
    }

    @Test
    fun rankedMatchesEngine() {
        for (lag in listOf(0, 1, 2, 3)) {
            val r = RankedEffect("Alcohol", "Charge", lag, effect(-8.0, -11.6), ScoreConfidence.BUILDING)
            assertEquals(r.sentence(), InsightsSentences.ranked(r, EnglishFromXml))
        }
    }

    @Test
    fun doseMatchesEngine() {
        fun resp(perUnit: Double, prior: Boolean, contradicts: Boolean) = DoseResponse(
            behavior = DosedBehavior.ALCOHOL, outcome = "Charge", perUnit = perUnit, userSlope = null,
            priorSlope = -3.0, weight = 0.5, nUser = 6, priorDominated = prior, contradictsPrior = contradicts,
            confidence = ScoreConfidence.BUILDING, curve = emptyList(),
        )
        val cases = listOf(
            Triple(-3.0, true, false), Triple(2.5, true, false), Triple(-4.26, false, false),
            Triple(1.0, false, false), Triple(0.0, false, false), Triple(3.0, false, true),
        )
        for ((u, p, c) in cases) {
            val r = resp(u, p, c)
            assertEquals(r.sentence(), InsightsSentences.dose(r, EnglishFromXml))
        }
    }
}
