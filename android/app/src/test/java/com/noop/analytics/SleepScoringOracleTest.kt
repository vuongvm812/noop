package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #55 byte-parity oracle for the sleep-scoring funnel and the Rest composite.
 *
 * EXPECTED is the verbatim stdout of the standalone Swift oracle (scratchpad `oracle/Sources/Oracle/
 * main.swift`, linking the StrandAnalytics package), with every Double printed as its IEEE-754 bit
 * pattern so the comparison is on bits, not formatting. [lines] regenerates the same lines from the
 * Kotlin twins with identical generators; the Swift side pins the same literals in
 * `SleepScoringOracleTests.swift`.
 *
 * The `rest.old` line records the finding this fixes: the pre-#55 Kotlin RestScorer (0–100 terms, ×100
 * roundToInt) against the pre-#55 Swift composite (0–1 terms, ×10000 `.rounded()`) over the grid.
 */
class SleepScoringOracleTest {

    private fun h(x: Double): String = java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(x))
    private fun ho(x: Double?): String = x?.let { h(it) } ?: "nil"

    private class Fnv {
        var v: Long = -0x340d631b7bdddcdbL   // 0xcbf29ce484222325
        fun add(x: Double) {
            var b = java.lang.Double.doubleToRawLongBits(x)
            repeat(8) {
                v = v xor (b and 0xff)
                v *= 0x100000001b3L
                b = b ushr 8
            }
        }
    }

    private class Lcg(var s: Long) {
        fun next(): Long { s = (s * 1_103_515_245L + 12_345L) and 0x7fff_ffffL; return s }
        fun unit(): Double = next().toDouble() / 2_147_483_648.0
    }

    /** Pre-#55 Swift arithmetic (transliterated; the Swift oracle uses the identical function). */
    private fun oldSwift(tst: Double, eff: Double, restor: Double, need: Double, cons: Double?, deep: Double): Double {
        fun c(x: Double) = maxOf(0.0, minOf(1.0, x))
        val dur = c(tst / (maxOf(need, 0.1) * 3600)); val e = c(eff)
        val df = 0.5 + 0.5 * c((deep / tst) / 0.13)
        val r = c((restor / tst) / 0.50) * df
        val w = 0.50 * dur + 0.20 * e + 0.20 * r + 0.10 * c(cons ?: 0.5)
        return RestScorer.swiftRounded(w * 10000.0) / 100.0
    }

    /** Pre-#55 Kotlin RestScorer.rest arithmetic, verbatim. */
    private fun oldKotlin(tst: Double, eff: Double, deepS: Double, remS: Double, need: Double, cons: Double?): Double {
        val needH = maxOf(need, 0.1)
        val dur = minOf(100.0, tst / 3600.0 / needH * 100.0)
        val e = (eff * 100.0).coerceIn(0.0, 100.0)
        val share = (deepS + remS) / tst
        val ad = ((deepS / tst) / 0.13).coerceIn(0.0, 1.0)
        val df = 0.5 + (1.0 - 0.5) * ad
        val r = minOf(100.0, share / 0.50 * 100.0) * df
        val cs = ((cons ?: 0.5) * 100.0).coerceIn(0.0, 100.0)
        val w = 0.50 * dur + 0.20 * e + 0.20 * r + 0.10 * cs
        return Math.round(w * 100.0) / 100.0
    }

    private fun lines(): List<String> {
        val out = ArrayList<String>()
        // ---- 1. Rest rounding grid
        val needs = listOf(7.0, 8.0, 8.75)
        val conses = listOf<Double?>(null, 0.0, 0.37, 0.81, 1.0)
        val ages = listOf<Int?>(null, 16, 65)
        var oldTotal = 0; var oldDiff = 0
        val fnv = Fnv(); var newCount = 0
        for (i in 0 until 40) {
            val tst = (180 + 14 * i).toDouble() * 60.0
            for (j in 0 until 9) {
                val eff = 0.60 + 0.05 * j.toDouble()
                for (k in 0 until 8) {
                    val deep = tst * (0.02 * k.toDouble())
                    for (l in 0 until 8) {
                        val rem = tst * (0.05 + 0.03 * l.toDouble())
                        for (need in needs) for (cons in conses) {
                            val a = oldSwift(tst, eff, deep + rem, need, cons, deep)
                            val b = oldKotlin(tst, eff, deep, rem, need, cons)
                            oldTotal += 1; if (a != b) oldDiff += 1
                            for (age in ages) {
                                val v = RestScorer.composite(
                                    tstSeconds = tst, efficiency = eff, restorativeSeconds = deep + rem,
                                    needHours = need, consistency = cons,
                                    deepSeconds = if (k == 0) null else deep, age = age,
                                )
                                fnv.add(v); newCount += 1
                            }
                        }
                    }
                }
            }
        }
        out.add("rest.old total=$oldTotal diff=$oldDiff")
        out.add("rest.new count=$newCount fnv=${java.lang.Long.toUnsignedString(fnv.v, 16)}")
        out.add("rest.lit1 ${h(RestScorer.composite(25200.0, 0.9, 9000.0, 8.25, 0.73, 3600.0, 35))}")
        out.add("rest.lit2 ${h(RestScorer.composite(21600.0, 0.9, 5400.0, 7.0, null, null, 70))}")
        out.add("rest.lit3 ${h(RestScorer.composite(30000.0, 0.97, 14000.0, 9.5, 0.95, 200.0, 15))}")
        for (a in listOf<Int?>(null, 0, 5, 12, 13, 17, 18, 59, 60, 90)) {
            out.add("floor age=${a ?: "nil"} ${h(RestScorer.populationNeedFloorHours(a))} " +
                "restor=${h(RestScorer.restorativeTarget(a))} deep=${h(RestScorer.deepShareTarget(a))}")
        }

        // ---- 2. DayOrdinal
        val lo = DayOrdinal.ordinal("1900-01-01")!!
        val hi = DayOrdinal.ordinal("2100-12-31")!!
        var roundTrip = true
        for (o in lo..hi) if (DayOrdinal.ordinal(DayOrdinal.key(o)) != o) roundTrip = false
        out.add("ord lo=$lo hi=$hi roundTrip=$roundTrip")
        for (k in listOf("1970-01-01", "2000-02-29", "2024-03-10", "2026-11-03", "1969-12-31", "2026-13-01", "2026-1-01", "abc")) {
            out.add("ord $k ${DayOrdinal.ordinal(k) ?: "nil"}")
        }

        // ---- 3. Sleep Regularity
        fun regLines(name: String, ws: List<SleepRegularity.Window>, days: List<String>) {
            val r = SleepRegularity.results(ws, days)
            for (d in days) {
                val x = r[d]
                if (x != null) out.add("reg $name $d score=${h(x.score)} sri=${ho(x.sri)} m=${x.method.raw} n=${x.samples}")
                else out.add("reg $name $d none")
            }
        }
        val base = DayOrdinal.ordinal("2026-03-01")!!
        fun night(wakeOrd: Int, onsetHour: Double, hours: Double, off: Int): SleepRegularity.Window {
            val start = (wakeOrd - 1).toLong() * 86_400L + (onsetHour * 3600).toLong() - off
            return SleepRegularity.Window(start, start + (hours * 3600).toLong(), off.toLong())
        }
        val probe = listOf(3, 8, 15, 29, 30, 40).map { DayOrdinal.key(base + it) }
        regLines("regular", (0 until 35).map { night(base + it, 23.0, 8.0, 0) }, probe)
        regLines("swing", (0 until 35).map { night(base + it, if (it % 2 == 0) 21.0 else 26.0, 7.5, 3600) }, probe)
        regLines("sparse", (0 until 35).filter { it % 2 == 0 }
            .map { night(base + it, 22.5 + (it % 3).toDouble() * 0.5, 7.0, -18000) }, probe)
        regLines("dst", (0 until 35).map { night(base + it, 23.25, 7.75, if (it < 17) 3600 else 7200) }, probe)
        val g = Lcg(42)
        val jit = ArrayList<SleepRegularity.Window>()
        for (i in 0 until 40) {
            if (!(g.unit() > 0.15)) continue
            val onset = 21.5 + g.unit() * 4.0
            val len = 5.5 + g.unit() * 3.5
            jit.add(night(base + i, onset, len, -25200))
            if (g.unit() > 0.8) {
                val s = (base + i).toLong() * 86_400L + 14 * 3600 + 25200
                jit.add(SleepRegularity.Window(s, s + 2400, -25200))
            }
        }
        regLines("jitter", jit, probe)
        regLines("short", (0 until 35).map { night(base + it, 23.0, 2.5, 0) }, probe)

        // ---- 4. Sleep need funnel
        val nights = ArrayList<SleepNeed.Night>()
        val g2 = Lcg(7)
        for (i in 0 until 60) {
            val r = g2.unit()
            if (r < 0.1) continue
            val main: Double? = if (r < 0.15) null else 300 + g2.unit() * 260
            val nap = if (g2.unit() > 0.85) 20 + g2.unit() * 40 else 0.0
            val strain: Double? = if (g2.unit() > 0.2) g2.unit() * 100 else null
            nights.add(SleepNeed.Night(DayOrdinal.key(base + i), main, nap, strain))
        }
        val needDays = listOf(0, 1, 6, 7, 8, 20, 45, 59, 60, 75).map { DayOrdinal.key(base + it) }
        for (age in listOf<Int?>(null, 30, 15)) {
            val c = SleepNeed.contexts(nights, needDays, age)
            for (d in needDays) {
                val x = c[d]
                if (x == null) { out.add("need age=${age ?: "nil"} $d none"); continue }
                val after = SleepNeed.debtAfter(x, 380.0, 25.0)
                out.add("need age=${age ?: "nil"} $d base=${h(x.baseHours)} strain=${h(x.strainHours)} " +
                    "debt=${h(x.debtMin)} total=${h(x.totalHours)} after=${h(after)}")
            }
        }
        out.add("need empty " + SleepNeed.contexts(emptyList(), listOf("2026-03-05", "bad"), 10)
            .map { "${it.key}:${h(it.value.baseHours)}" }.sorted().joinToString(","))
        for (s in listOf<Double?>(null, -5.0, 0.0, 49.9, 50.0, 62.5, 99.0, 100.0, 140.0, Double.NaN)) {
            out.add("strainHours ${ho(s)} ${h(SleepNeed.strainHours(s))}")
        }
        val joined = SleepScoring.contexts(nights, jit, needDays, 64)
        for (d in needDays) {
            val x = joined[d] ?: continue
            out.add("ctx $d need=${h(x.rest.needHours)} cons=${ho(x.rest.consistency)} age=${x.rest.age}")
        }

        // ---- 5. SleepDebt ledger / series
        val series = ArrayList<Pair<String, Double?>>()
        val g3 = Lcg(99)
        for (i in 0 until 30) {
            val r = g3.unit()
            if (r < 0.12) continue
            series.add(DayOrdinal.key(base + i) to (if (r < 0.2) null else 280 + g3.unit() * 260))
        }
        for ((need, win) in listOf(8.0 to 14, 7.25 to 7, 9.0 to 1)) {
            val l = SleepDebt.ledger(series, need, win)
            out.add("ledger need=${h(need)} win=$win bal=${h(l.balanceMin)} n=${l.nightCount} " +
                "days=${l.nights.joinToString(",") { it.day }} deltas=${l.nights.joinToString(",") { h(it.deltaMin) }}")
        }
        val ser = SleepDebt.debtSeries(series, 8.0, mapOf(DayOrdinal.key(base + 10) to 42.5))
        out.add("series " + ser.joinToString(",") { "${it.first}=${h(it.second)}" })

        // ---- 6. Nap minutes
        fun b(s: Long, e: Long, eff: Double?) = SleepScoring.Block(s, e, eff)
        out.add("nap a ${h(SleepScoring.napMinutes(listOf(b(0, 28800, 0.9), b(40000, 42400, 0.8), b(50000, 51800, null))))}")
        out.add("nap b ${h(SleepScoring.napMinutes(listOf(b(0, 3600, 1.2), b(5000, 8600, 0.5))))}")
        out.add("nap c ${h(SleepScoring.napMinutes(listOf(b(10, 5, 0.9))))}")
        out.add("nap d ${h(SleepScoring.napMinutes(listOf(b(0, 1000, 0.9), b(2000, 3000, 0.7))))}")
        return out
    }

    @Test fun kotlinTwinMatchesSwiftOracleBitForBit() {
        assertEquals(EXPECTED.trim().lines(), lines())
    }

    private companion object {
        const val EXPECTED = """
rest.old total=345600 diff=577
rest.new count=1036800 fnv=711469746a192d2a
rest.lit1 40553147ae147ae1
rest.lit2 40536eb851eb851f
rest.lit3 405558f5c28f5c29
floor age=nil 401c000000000000 restor=3fd999999999999a deep=3fc0a3d70a3d70a4
floor age=0 401c000000000000 restor=3fd999999999999a deep=3fc0a3d70a3d70a4
floor age=5 4022000000000000 restor=3fdccccccccccccd deep=3fc0a3d70a3d70a4
floor age=12 4022000000000000 restor=3fdccccccccccccd deep=3fc0a3d70a3d70a4
floor age=13 4020000000000000 restor=3fdccccccccccccd deep=3fc0a3d70a3d70a4
floor age=17 4020000000000000 restor=3fdccccccccccccd deep=3fc0a3d70a3d70a4
floor age=18 401c000000000000 restor=3fd999999999999a deep=3fc0a3d70a3d70a4
floor age=59 401c000000000000 restor=3fd999999999999a deep=3fc0a3d70a3d70a4
floor age=60 401c000000000000 restor=3fd999999999999a deep=3fb999999999999a
floor age=90 401c000000000000 restor=3fd999999999999a deep=3fb999999999999a
ord lo=-25567 hi=47846 roundTrip=true
ord 1970-01-01 0
ord 2000-02-29 11016
ord 2024-03-10 19792
ord 2026-11-03 20760
ord 1969-12-31 -1
ord 2026-13-01 nil
ord 2026-1-01 nil
ord abc nil
reg regular 2026-03-04 score=3ff0000000000000 sri=nil m=midsleepSD n=3
reg regular 2026-03-09 score=3ff0000000000000 sri=4059000000000000 m=sri n=7
reg regular 2026-03-16 score=3ff0000000000000 sri=4059000000000000 m=sri n=14
reg regular 2026-03-30 score=3ff0000000000000 sri=4059000000000000 m=sri n=27
reg regular 2026-03-31 score=3ff0000000000000 sri=4059000000000000 m=sri n=27
reg regular 2026-04-10 score=3ff0000000000000 sri=4059000000000000 m=sri n=22
reg swing 2026-03-04 score=0 sri=nil m=midsleepSD n=3
reg swing 2026-03-09 score=3fc5555555555557 sri=4030aaaaaaaaaaac m=sri n=7
reg swing 2026-03-16 score=3fc5555555555557 sri=4030aaaaaaaaaaac m=sri n=14
reg swing 2026-03-30 score=3fc5555555555557 sri=4030aaaaaaaaaaac m=sri n=27
reg swing 2026-03-31 score=3fc5555555555557 sri=4030aaaaaaaaaaac m=sri n=27
reg swing 2026-04-10 score=3fc5555555555557 sri=4030aaaaaaaaaaac m=sri n=22
reg sparse 2026-03-04 none
reg sparse 2026-03-09 score=3fe95de35b00a433 sri=nil m=midsleepSD n=4
reg sparse 2026-03-16 score=3fe9126145e9ecd5 sri=nil m=midsleepSD n=8
reg sparse 2026-03-30 score=3fe99c79dab771c4 sri=nil m=midsleepSD n=14
reg sparse 2026-03-31 score=3fe99c79dab771c4 sri=nil m=midsleepSD n=14
reg sparse 2026-04-10 score=3fe977d0a3fcf4f0 sri=nil m=midsleepSD n=12
reg dst 2026-03-04 score=3ff0000000000000 sri=nil m=midsleepSD n=3
reg dst 2026-03-09 score=3ff0000000000000 sri=4059000000000000 m=sri n=7
reg dst 2026-03-16 score=3ff0000000000000 sri=4059000000000000 m=sri n=14
reg dst 2026-03-30 score=3ff0000000000000 sri=4059000000000000 m=sri n=27
reg dst 2026-03-31 score=3ff0000000000000 sri=4059000000000000 m=sri n=27
reg dst 2026-04-10 score=3ff0000000000000 sri=4059000000000000 m=sri n=22
reg jitter 2026-03-04 score=3fdf2736ceef1f36 sri=nil m=midsleepSD n=3
reg jitter 2026-03-09 score=3fe8684bda12f685 sri=4053117b425ed098 m=sri n=6
reg jitter 2026-03-16 score=3fe74fa4fa4fa4fb sri=40523638e38e38e4 m=sri n=10
reg jitter 2026-03-30 score=3fe5e38e38e38e38 sri=405119c71c71c71c m=sri n=20
reg jitter 2026-03-31 score=3fe6693e93e93e94 sri=40518238e38e38e4 m=sri n=20
reg jitter 2026-04-10 score=3fe4a57eb50295fb sri=4050214afd6a052c m=sri n=22
reg short 2026-03-04 none
reg short 2026-03-09 none
reg short 2026-03-16 none
reg short 2026-03-30 none
reg short 2026-03-31 none
reg short 2026-04-10 none
need age=nil 2026-03-01 base=4020000000000000 strain=0 debt=0 total=4020000000000000 after=4044a00000000000
need age=nil 2026-03-02 base=4020000000000000 strain=3fdf89c850000000 debt=404c1a41ab0d0001 total=4022dbec3cd63333 after=40561b2e7af39334
need age=nil 2026-03-07 base=4020000000000000 strain=3fddcbf7ed000000 debt=404ada882f460001 total=4022b8ad17e42222 after=405589ca028d0ccd
need age=nil 2026-03-08 base=4020000000000000 strain=0 debt=403c5e0efb043334 total=4020f21190e68a3d after=404c6d10eb6df47b
need age=nil 2026-03-09 base=4020000000000000 strain=3fc113df00000000 debt=4057c047c4ad7a3e total=40236f03b85b65a2 after=405879ef5879033d
need age=nil 2026-03-21 base=4020a9a90af1aaaa strain=0 debt=0 total=4020a9a90af1aaaa after=404a17b29a49bffc
need age=nil 2026-04-15 base=402064beede8aaaa strain=0 debt=4050482bb4d1d00a total=402290807d7c1bbc after=4054e412059ff26b
need age=nil 2026-04-29 base=40207151da805555 strain=0 debt=4065a0646e9d65c4 total=402635b0e6ee929a after=4061f6bcdc4c0e5d
need age=nil 2026-04-30 base=40207151da805555 strain=0 debt=4057ca081346bcbf total=40239d52ee235d6e after=405938f61651e166
need age=nil 2026-05-15 base=40207151da805555 strain=0 debt=0 total=40207151da805555 after=404846e34aa2bffc
need age=30 2026-03-01 base=4020000000000000 strain=0 debt=0 total=4020000000000000 after=4044a00000000000
need age=30 2026-03-02 base=4020000000000000 strain=3fdf89c850000000 debt=404c1a41ab0d0001 total=4022dbec3cd63333 after=40561b2e7af39334
need age=30 2026-03-07 base=4020000000000000 strain=3fddcbf7ed000000 debt=404ada882f460001 total=4022b8ad17e42222 after=405589ca028d0ccd
need age=30 2026-03-08 base=4020000000000000 strain=0 debt=403c5e0efb043334 total=4020f21190e68a3d after=404c6d10eb6df47b
need age=30 2026-03-09 base=4020000000000000 strain=3fc113df00000000 debt=4057c047c4ad7a3e total=40236f03b85b65a2 after=405879ef5879033d
need age=30 2026-03-21 base=4020a9a90af1aaaa strain=0 debt=0 total=4020a9a90af1aaaa after=404a17b29a49bffc
need age=30 2026-04-15 base=402064beede8aaaa strain=0 debt=4050482bb4d1d00a total=402290807d7c1bbc after=4054e412059ff26b
need age=30 2026-04-29 base=40207151da805555 strain=0 debt=4065a0646e9d65c4 total=402635b0e6ee929a after=4061f6bcdc4c0e5d
need age=30 2026-04-30 base=40207151da805555 strain=0 debt=4057ca081346bcbf total=40239d52ee235d6e after=405938f61651e166
need age=30 2026-05-15 base=40207151da805555 strain=0 debt=0 total=40207151da805555 after=404846e34aa2bffc
need age=15 2026-03-01 base=4020000000000000 strain=0 debt=0 total=4020000000000000 after=4044a00000000000
need age=15 2026-03-02 base=4020000000000000 strain=3fdf89c850000000 debt=404c1a41ab0d0001 total=4022dbec3cd63333 after=40561b2e7af39334
need age=15 2026-03-07 base=4020000000000000 strain=3fddcbf7ed000000 debt=404ada882f460001 total=4022b8ad17e42222 after=405589ca028d0ccd
need age=15 2026-03-08 base=4020000000000000 strain=0 debt=403c5e0efb043334 total=4020f21190e68a3d after=404c6d10eb6df47b
need age=15 2026-03-09 base=4020000000000000 strain=3fc113df00000000 debt=4057c047c4ad7a3e total=40236f03b85b65a2 after=405879ef5879033d
need age=15 2026-03-21 base=4020a9a90af1aaaa strain=0 debt=0 total=4020a9a90af1aaaa after=404a17b29a49bffc
need age=15 2026-04-15 base=402064beede8aaaa strain=0 debt=4050482bb4d1d00a total=402290807d7c1bbc after=4054e412059ff26b
need age=15 2026-04-29 base=40207151da805555 strain=0 debt=4065a0646e9d65c4 total=402635b0e6ee929a after=4061f6bcdc4c0e5d
need age=15 2026-04-30 base=40207151da805555 strain=0 debt=4057ca081346bcbf total=40239d52ee235d6e after=405938f61651e166
need age=15 2026-05-15 base=40207151da805555 strain=0 debt=0 total=40207151da805555 after=404846e34aa2bffc
need empty 2026-03-05:4022000000000000
strainHours nil 0
strainHours c014000000000000 0
strainHours 0 0
strainHours 4048f33333333333 0
strainHours 4049000000000000 0
strainHours 404f400000000000 3fc0000000000000
strainHours 4058c00000000000 3fdf5c28f5c28f5c
strainHours 4059000000000000 3fe0000000000000
strainHours 4061800000000000 3fe0000000000000
strainHours 7ff8000000000000 0
ctx 2026-03-01 need=4020000000000000 cons=nil age=64
ctx 2026-03-02 need=4022dbec3cd63333 cons=nil age=64
ctx 2026-03-07 need=4022b8ad17e42222 cons=3fe7777777777778 age=64
ctx 2026-03-08 need=4020f21190e68a3d cons=3fe8684bda12f685 age=64
ctx 2026-03-09 need=40236f03b85b65a2 cons=3fe8684bda12f685 age=64
ctx 2026-03-21 need=4020a9a90af1aaaa cons=3fe679e79e79e79f age=64
ctx 2026-04-15 need=402290807d7c1bbc cons=3fe40b60b60b60b6 age=64
ctx 2026-04-29 need=402635b0e6ee929a cons=3fe3955555555557 age=64
ctx 2026-04-30 need=40239d52ee235d6e cons=3fe3e79e79e79e7b age=64
ctx 2026-05-15 need=40207151da805555 cons=nil age=64
ledger need=4020000000000000 win=14 bal=c045266666666666 n=10 days=2026-03-17,2026-03-18,2026-03-21,2026-03-22,2026-03-24,2026-03-25,2026-03-26,2026-03-27,2026-03-29,2026-03-30 deltas=c0165bb3a8000000,c068e51c49420000,4044739e2f280000,4047e7163f600000,c022d288ca900000,4024bb05aad00000,4045a0f3e3cc0000,c0552d2800ae0000,404c5abbf8700000,c0533986710c0000
ledger need=401d000000000000 win=7 bal=c031800000000000 n=6 days=2026-03-24,2026-03-25,2026-03-26,2026-03-27,2026-03-29,2026-03-30 deltas=4041cb5dcd5c0000,404baec16ab40000,40561079f1e60000,c043da50015c0000,40596d5dfc380000,c03fe619c4300000
ledger need=4022000000000000 win=1 bal=c052d33333333333 n=1 days=2026-03-30 deltas=c0611cc338860000
series 2026-03-01=405499999999999a,2026-03-02=4046666666666666,2026-03-03=4054266666666666,2026-03-04=4059333333333333,2026-03-05=4061366666666666,2026-03-06=4056933333333333,2026-03-07=405ed9999999999a,2026-03-08=4056600000000000,2026-03-09=4054533333333333,2026-03-10=405799999999999a,2026-03-11=4045400000000000,2026-03-12=0,2026-03-13=404df33333333333,2026-03-14=403c4ccccccccccd,2026-03-15=4046cccccccccccd,2026-03-16=4042cccccccccccd,2026-03-17=4037cccccccccccd,2026-03-18=405ea66666666666,2026-03-21=0,2026-03-22=0,2026-03-24=0,2026-03-25=0,2026-03-26=0,2026-03-27=40474ccccccccccd,2026-03-29=0,2026-03-30=4045266666666666
nap a 404d800000000000
nap b 403e000000000000
nap c 0
nap d 4027555555555555
"""
    }
}
