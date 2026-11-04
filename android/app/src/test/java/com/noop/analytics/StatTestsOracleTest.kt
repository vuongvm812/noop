package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Oracle parity for [StatTests], [CorrelationEngine] and [IllnessInputs] (#56).
 *
 * EXPECTED is the verbatim stdout of the Swift twins compiled standalone:
 *   swiftc -O StatTests.swift CorrelationEngine.swift IllnessInputs.swift Baselines.swift VitalBands.swift \
 *     IllnessSignalEngine.swift IllnessDistance.swift main.swift -o oracle && ./oracle
 * over the same cases built below (main.swift mirrors [cases] line for line). Values are compared to
 * within 1e-12 relative, the last-ulp slack between Darwin libm and the JVM's exp/log; booleans and
 * nil-ness exactly.
 */
class StatTestsOracleTest {

    private val expected = listOf(
        "T|0.0|1.0|1.0",
        "T|0.0|2.0|1.0",
        "T|0.0|3.0|1.0",
        "T|0.0|4.5|1.0",
        "T|0.0|5.0|1.0",
        "T|0.0|8.0|1.0",
        "T|0.0|10.0|1.0",
        "T|0.0|17.3|1.0",
        "T|0.0|30.0|1.0",
        "T|0.0|100.0|1.0",
        "T|0.1|1.0|0.9365489651388929",
        "T|0.1|2.0|0.929465438414141",
        "T|0.1|3.0|0.9266523488008067",
        "T|0.1|4.5|0.9246440676306857",
        "T|0.1|5.0|0.9242301411546615",
        "T|0.1|8.0|0.9228049094305968",
        "T|0.1|10.0|0.9223207185644094",
        "T|0.1|17.3|0.9214940417051136",
        "T|0.1|30.0|0.9210096117902701",
        "T|0.1|100.0|0.9205445310958446",
        "T|0.5|1.0|0.7048327646991335",
        "T|0.5|2.0|0.6666666666666661",
        "T|0.5|3.0|0.6514479648481509",
        "T|0.5|4.5|0.6405495020740463",
        "T|0.5|5.0|0.6382988716409288",
        "T|0.5|8.0|0.6305360755569762",
        "T|0.5|10.0|0.6278936057429718",
        "T|0.5|17.3|0.6233751441095081",
        "T|0.5|30.0|0.6207230048851257",
        "T|0.5|100.0|0.6181735658308886",
        "T|1.0|1.0|0.5",
        "T|1.0|2.0|0.42264973081037327",
        "T|1.0|3.0|0.3910022189557706",
        "T|1.0|4.5|0.3680050838801876",
        "T|1.0|5.0|0.36321746764912344",
        "T|1.0|8.0|0.3465935070873335",
        "T|1.0|10.0|0.34089313230205776",
        "T|1.0|17.3|0.33109323476265473",
        "T|1.0|30.0|0.3253086154260264",
        "T|1.0|100.0|0.31972415578412106",
        "T|1.5|1.0|0.3743340836219976",
        "T|1.5|2.0|0.2723931248910016",
        "T|1.5|3.0|0.23058386524482313",
        "T|1.5|4.5|0.20021908565615384",
        "T|1.5|5.0|0.1939036802424735",
        "T|1.5|8.0|0.17200329195191122",
        "T|1.5|10.0|0.16450732644543842",
        "T|1.5|17.3|0.15164301391337842",
        "T|1.5|30.0|0.1440659291286418",
        "T|1.5|100.0|0.1367650581246862",
        "T|2.0|1.0|0.29516723530086664",
        "T|2.0|2.0|0.1835034190722742",
        "T|2.0|3.0|0.13932596855884322",
        "T|2.0|4.5|0.10825790718112524",
        "T|2.0|5.0|0.10193947882985843",
        "T|2.0|8.0|0.08051623795726287",
        "T|2.0|10.0|0.07338803477074059",
        "T|2.0|17.3|0.0614521867661846",
        "T|2.0|30.0|0.05462504496298348",
        "T|2.0|100.0|0.048212178731133815",
        "T|2.309401076758503|1.0|0.26014693829300617",
        "T|2.309401076758503|2.0|0.1471971345775586",
        "T|2.309401076758503|3.0|0.10408803866182786",
        "T|2.309401076758503|4.5|0.07477548052586158",
        "T|2.309401076758503|5.0|0.06895875762604618",
        "T|2.309401076758503|8.0|0.049735563119402075",
        "T|2.309401076758503|10.0|0.043551787773895706",
        "T|2.309401076758503|17.3|0.03351170758922692",
        "T|2.309401076758503|30.0|0.027986881504645175",
        "T|2.309401076758503|100.0|0.02297794366320516",
        "T|2.5|1.0|0.2422378831816868",
        "T|2.5|2.0|0.129611720221511",
        "T|2.5|3.0|0.08770664700806557",
        "T|2.5|4.5|0.05990568650220066",
        "T|2.5|5.0|0.05449009934237622",
        "T|2.5|8.0|0.03694203771362413",
        "T|2.5|10.0|0.031446844236608935",
        "T|2.5|17.3|0.022747500175801016",
        "T|2.5|30.0|0.018115649068066828",
        "T|2.5|100.0|0.014045789124077229",
        "T|3.0|1.0|0.20483276469913345",
        "T|3.0|2.0|0.09546596626670929",
        "T|3.0|3.0|0.05766888562243738",
        "T|3.0|4.5|0.03438086788875971",
        "T|3.0|5.0|0.030099247897462555",
        "T|3.0|8.0|0.01707168123378266",
        "T|3.0|10.0|0.0133436550225696",
        "T|3.0|17.3|0.007938582973502598",
        "T|3.0|30.0|0.0053899640656519775",
        "T|3.0|100.0|0.0034079153433294786",
        "T|4.0|1.0|0.15595826075473865",
        "T|4.0|2.0|0.05719095841793675",
        "T|4.0|3.0|0.028008456010146152",
        "T|4.0|4.5|0.012764489465624074",
        "T|4.0|5.0|0.010323415480831447",
        "T|4.0|8.0|0.003949772803445327",
        "T|4.0|10.0|0.0025183326247367023",
        "T|4.0|17.3|0.0008993515025510284",
        "T|4.0|30.0|0.0003818456360837587",
        "T|4.0|100.0|0.0001215236443007619",
        "T|6.0|1.0|0.10513691342250696",
        "T|6.0|2.0|0.026671473215424815",
        "T|6.0|3.0|0.00927271489228467",
        "T|6.0|4.5|0.002641475805551587",
        "T|6.0|5.0|0.0018461382895940126",
        "T|6.0|8.0|0.00032339322188514947",
        "T|6.0|10.0|0.00013210886035478595",
        "T|6.0|17.3|1.331976004196804e-05",
        "T|6.0|30.0|1.3942768767204808e-06",
        "T|6.0|100.0|3.1724915028028834e-08",
        "T|10.0|1.0|0.06345103486110716",
        "T|10.0|2.0|0.009852457023325708",
        "T|10.0|3.0|0.00212839905841415",
        "T|10.0|4.5|0.0003052805756833964",
        "T|10.0|5.0|0.00017094757574296352",
        "T|10.0|8.0|8.488181527628494e-06",
        "T|10.0|10.0|1.5895531755964144e-06",
        "T|10.0|17.3|1.3096233575540198e-08",
        "T|10.0|30.0|4.575251408229631e-11",
        "T|10.0|100.0|9.90168898459414e-17",
        "T|-2.0|1.0|0.29516723530086664",
        "T|-2.0|2.0|0.1835034190722742",
        "T|-2.0|3.0|0.13932596855884322",
        "T|-2.0|4.5|0.10825790718112524",
        "T|-2.0|5.0|0.10193947882985843",
        "T|-2.0|8.0|0.08051623795726287",
        "T|-2.0|10.0|0.07338803477074059",
        "T|-2.0|17.3|0.0614521867661846",
        "T|-2.0|30.0|0.05462504496298348",
        "T|-2.0|100.0|0.048212178731133815",
        "R|0.8|5.0|0.10408803866182782",
        "R|0.3|10.0|0.3996914687499994",
        "R|-0.45|12.0|0.14213639115730317",
        "R|0.6|30.0|0.00045705523940507963",
        "R|0.1|200.0|0.1588697048944786",
        "R|0.99|10.0|4.3227184375000325e-08",
        "R|0.5|7.4|0.23337045165064607",
        "W|0.13170214241769185",
        "W|0.44241050338691934",
        "W|0.013750277061023846",
        "W|1.0",
        "BH|0.06,0.06,0.06,0.24000000000000005,0.06,0.5",
        "RK|4.0,1.5,5.0,1.5,6.5,9.0,3.0,8.0,6.5",
        "AC|0.2819755230066934",
        "NE|8.950819672131148|14.0",
        "CP|0.978405257800127|11.937476535652019|3.948105480713038e-08|1.2323073907077045|-33.06822191727113",
        "CS|0.9768982809108381|11.732249730007485|7.632664220389641e-08",
        "IL|stable|0.02312904374450122:true:true|0.14878463436564482:true:true|0.009267875991643238:true:true|0.7946316609003877:true:true|true|false",
        "IL|ill|3.015946282371796:true:true|3.181506102841303:true:true|2.403521666893482:true:true|2.2577867553403985:true:true|true|false",
        "IL|mixed-dev|0.26446009025541284:true:true|-0.0836356410765013:true:true|0.11961021817293231:true:true|0.7819883434164066:true:false|true|false",
        "IL|mixed-abs|0.26446009025541284:true:true|0.23559819771041013:true:true|0.11961021817293231:true:true|2.698781096215045:true:true|true|false",
        "IL|hard|0.02312904374450122:true:true|0.14878463436564482:true:true|0.009267875991643238:true:true|0.3955893624167484:true:true|true|true",
        "IL|short|0.0:false:false|0.0:false:false|0.0:false:false|0.0:false:false|false|false",
        "IL|empty|nil|nil|nil|nil|false|false",
        "CTX|true|true|true|false|false|true",
        "CTX|false|false|false|true|true|true",
        "PJ|home|false|false",
        "PJ|east|true|true",
        "PJ|west2|true|true",
        "PJ|edge179|false|false",
        "PJ|edge180|true|true",
        "PJ|thin|false|false",
        "PJ|seven|true|true",
        "PJ|rotating|false|false",
        "PJ|spread|true|true",
        "SM|1969-12-31=1350.0;2026-01-02=240.0;2026-01-04=180.0",
    )

    private fun close(label: String, want: String, got: Double) {
        if (want == "nil") throw AssertionError("$label: expected nil, got $got")
        val w = want.toDouble()
        val tol = maxOf(1e-300, kotlin.math.abs(w) * 1e-12)
        assertEquals(label, w, got, tol)
    }

    private fun night(i: Int, rhr: Double?, hrv: Double?, resp: Double?, skin: Double?, strain: Double?) =
        IllnessInputs.Night(String.format("2026-01-%02d", i + 1), rhr, hrv, resp, skin, strain)

    private fun show(s: IllnessInputs.Signal?): String =
        if (s == null) "nil" else "${s.reading.zIllnessward}:${s.reading.present}:${s.trusted}"

    /** The Kotlin side of every oracle line, in the oracle's order. */
    private fun cases(): List<String> {
        val out = ArrayList<String>()
        val ts = listOf(0.0, 0.1, 0.5, 1.0, 1.5, 2.0, 2.309401076758503, 2.5, 3.0, 4.0, 6.0, 10.0, -2.0)
        val dfs = listOf(1.0, 2.0, 3.0, 4.5, 5.0, 8.0, 10.0, 17.3, 30.0, 100.0)
        for (t in ts) for (df in dfs) out.add("T|$t|$df|${StatTests.studentTTwoSidedP(t, df)}")
        for ((r, n) in listOf(0.8 to 5.0, 0.3 to 10.0, -0.45 to 12.0, 0.6 to 30.0, 0.1 to 200.0, 0.99 to 10.0, 0.5 to 7.4)) {
            out.add("R|$r|$n|${StatTests.correlationP(r, n)}")
        }
        out.add("W|${StatTests.welchP(61.0, 90.0, 6, 69.0, 110.0, 12)}")
        out.add("W|${StatTests.welchP(50.0, 4.0, 5, 52.0, 25.0, 5)}")
        out.add("W|${StatTests.welchP(10.0, 0.0, 1, 12.0, 3.0, 8)}")
        out.add("W|${StatTests.welchP(3.0, 1.0, 20, 3.0, 1.0, 20)}")
        out.add("BH|" + StatTests.benjaminiHochberg(listOf(0.01, 0.04, 0.03, 0.2, 0.04, 0.5)).joinToString(","))
        out.add("RK|" + StatTests.averageRanks(listOf(3.0, 1.0, 4.0, 1.0, 5.0, 9.0, 2.0, 6.0, 5.0)).joinToString(","))
        val series = listOf(61.0, 64.0, 70.0, 66.0, 59.0, 62.0, 75.0, 71.0, 68.0, 60.0, 58.0, 66.0, 72.0, 69.0)
        out.add("AC|${StatTests.lag1Autocorrelation(series)}")
        out.add("NE|${StatTests.effectiveN(14, 0.4, 0.55)}|${StatTests.effectiveN(14, -0.4, 0.55)}")
        val ys = listOf(40.0, 45.0, 52.0, 47.0, 39.0, 44.0, 60.0, 55.0, 50.0, 42.0, 41.0, 48.0, 58.0, 51.0)
        val xy = series.zip(ys)
        val p = CorrelationEngine.pearson(xy)!!
        out.add("CP|${p.r}|${p.nEffective}|${p.pApprox}|${p.slope}|${p.intercept}")
        val sp = CorrelationEngine.spearman(xy)!!
        out.add("CS|${sp.r}|${sp.nEffective}|${sp.pApprox}")

        fun dump(label: String, nights: List<IllnessInputs.Night>) {
            val b = IllnessInputs.build(nights)
            out.add("IL|$label|${show(b.restingHR)}|${show(b.hrv)}|${show(b.respiration)}|${show(b.skinTemp)}|${b.baselineTrusted}|${b.hardWorkout}")
        }
        val wob = listOf(0.05, -0.05, 0.0, 0.04, -0.04)
        val a = MutableList(30) { i ->
            night(i, 52.0 + i % 3, 60.0 + (i % 4) * 2, 14.5 + (i % 2) * 0.2, wob[i % 5], 40.0 + (i % 5) * 3)
        }
        a[28] = night(28, 53.0, 62.0, 14.6, 0.3, 42.0)
        a[29] = night(29, 53.0, 62.0, 14.6, 0.3, 42.0)
        dump("stable", a)
        val bn = a.toMutableList()
        bn[28] = night(28, 60.0, 44.0, 16.0, 0.8, 30.0)
        bn[29] = night(29, 61.0, 42.0, 16.2, 0.9, 30.0)
        dump("ill", bn)
        val c = a.toMutableList()
        for (i in 0 until 20) c[i] = night(i, 52.0, 61.0, 14.5, 33.0 + (i % 3) * 0.1, 45.0)
        dump("mixed-dev", c)
        val c2 = c.toMutableList()
        c2[29] = night(29, 53.0, 60.0, 14.6, 34.2, 40.0)
        c2[28] = night(28, 53.0, 60.0, 14.6, 34.0, 40.0)
        dump("mixed-abs", c2)
        val d = a.toMutableList()
        d[28] = night(28, 53.0, 62.0, 14.6, 0.0, 95.0)
        dump("hard", d)
        dump("short", a.take(5))
        dump("empty", emptyList())
        val ctx = IllnessInputs.context(
            listOf("  Did you drink   any alcohol?\n", "Did you take a pill?", "Did you feel stressed?", "Did you use a sauna?"),
            IllnessInputs.build(a),
        )
        out.add("CTX|${ctx.alcohol}|${ctx.stress}|${ctx.sauna}|${ctx.hardOrLateWorkout}|${ctx.alreadyUnwell}|${ctx.baselineTrusted}")
        val ctx2 = IllnessInputs.context(
            listOf("Did you drink water?", "Feeling still?", "Did you feel sick or ill?"), IllnessInputs.build(d),
        )
        out.add("CTX|${ctx2.alcohol}|${ctx2.stress}|${ctx2.sauna}|${ctx2.hardOrLateWorkout}|${ctx2.alreadyUnwell}|${ctx2.baselineTrusted}")

        fun withMid(n: List<IllnessInputs.Night>, mid: (Int) -> Double?) =
            n.mapIndexed { i, x -> x.copy(sleepMidpointUtcMin = mid(i)) }
        val jit = listOf(1410.0, 1430.0, 10.0, 30.0, 1420.0, 20.0, 0.0)
        val pj: List<Pair<String, (Int) -> Double?>> = listOf(
            "home" to { i -> jit[i % 7] },
            "east" to { i -> if (i >= 29) 1080.0 else jit[i % 7] },
            "west2" to { i -> if (i == 28) 240.0 else if (i == 29) null else jit[i % 7] },
            "edge179" to { i -> if (i >= 28) 179.0 else 0.0 },
            "edge180" to { i -> if (i >= 28) 180.0 else 0.0 },
            "thin" to { i -> if (i < 21) null else if (i >= 28) 600.0 else 180.0 },
            "seven" to { i -> if (i < 20) null else if (i >= 28) 600.0 else 180.0 },
            "rotating" to { i -> if (i >= 28) 600.0 else ((i % 4) * 360).toDouble() },
            "spread" to { i -> if (i >= 28) 900.0 else ((i % 3) * 200).toDouble() },
        )
        for ((label, f) in pj) {
            val b = IllnessInputs.build(withMid(a, f))
            out.add("PJ|$label|${b.travelPhaseJump}|${IllnessInputs.context(emptyList(), b).travelPhaseJump}")
        }
        val mids = IllnessInputs.sleepMidpoints(listOf(
            IllnessInputs.SleepBlock("2026-01-02", 1_767_312_000L, 1_767_340_800L),
            IllnessInputs.SleepBlock("2026-01-02", 1_767_358_800L, 1_767_362_400L),
            IllnessInputs.SleepBlock("2026-01-02", 1_767_300_000L, 1_767_328_800L),
            IllnessInputs.SleepBlock("2026-01-03", 1_767_391_200L, 1_767_391_200L),
            IllnessInputs.SleepBlock("2026-01-04", 1_767_481_200L, 1_767_510_001L),
            IllnessInputs.SleepBlock("1969-12-31", -7_200L, -3_599L),
        ))
        out.add("SM|" + mids.keys.sorted().joinToString(";") { "$it=${mids.getValue(it)}" })
        return out
    }

    @Test
    fun matchesSwiftOracle() {
        val got = cases()
        assertEquals("oracle row count", expected.size, got.size)
        for (i in expected.indices) {
            val w = expected[i].split("|")
            val g = got[i].split("|")
            assertEquals("row $i shape: ${expected[i]} vs ${got[i]}", w.size, g.size)
            for (k in w.indices) {
                val label = "row $i field $k (${expected[i]})"
                when {
                    w[k].contains(",") -> {
                        val ws = w[k].split(","); val gs = g[k].split(",")
                        assertEquals(label, ws.size, gs.size)
                        for (j in ws.indices) close(label, ws[j], gs[j].toDouble())
                    }
                    w[k].contains(":") -> {
                        val ws = w[k].split(":"); val gs = g[k].split(":")
                        close(label, ws[0], gs[0].toDouble())
                        assertEquals(label, ws.drop(1), gs.drop(1))
                    }
                    w[k].toDoubleOrNull() != null && k > 0 -> close(label, w[k], g[k].toDouble())
                    else -> assertEquals(label, w[k], g[k])
                }
            }
        }
    }

    @Test
    fun smallSampleCorrelationIsNotSignificant() {
        val p = StatTests.correlationP(0.8, 5.0)
        assertEquals(0.1041, p, 1e-4)
    }

    @Test
    fun belowMinPairsOffersNoInference() {
        val c = CorrelationEngine.pearson(listOf(1.0 to 2.0, 2.0 to 4.0, 3.0 to 5.0, 4.0 to 9.0, 5.0 to 9.0))!!
        assertEquals(1.0, c.pApprox, 0.0)
    }
}
