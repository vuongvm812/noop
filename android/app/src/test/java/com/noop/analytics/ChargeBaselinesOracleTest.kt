package com.noop.analytics

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cross-platform oracle for the per-day Charge baselines (#53): a deterministic spread of imported / own /
 * seed histories with device switches and recalibration epochs, each resolved for one scored day through
 * [ChargeBaselines.resolveDay]. The SAME generator runs in the Swift `ChargeBaselinesTests.oracleLines`; the
 * literal below is that test's stdout (`PRINT_ORACLE=1 swift test --filter ChargeBaselinesTests`), pasted
 * verbatim, so either side drifting fails here.
 */
class ChargeBaselinesOracleTest {

    private fun dayKey(epochDay: Int): String = LocalDate.ofEpochDay(epochDay.toLong()).toString()

    private fun oracleLines(): List<String> {
        var s = 53L
        fun next(): Int {
            s = s * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return (s ushr 33).toInt()
        }
        fun f6(x: Double): String = String.format(Locale.ROOT, "%.6f", x)
        val lines = ArrayList<String>()
        for (c in 0 until 60) {
            val scored = 20_089 + next() % 400
            fun nights(count: Int, endBack: Int, nilEvery: Int, gen: () -> Double): List<Pair<String, Double?>> =
                (0 until count).map { i ->
                    val v: Double? = if (next() % nilEvery == 0) null else gen()
                    dayKey(scored - endBack - (count - 1 - i)) to v
                }
            val nImp = next() % 90
            val impEnd = next() % 70
            val nOwn = next() % 70
            val ownEnd = next() % 5
            val nSeed = next() % 60
            val seedEnd = next() % 80
            val impHrv = nights(nImp, impEnd, 9) { 30 + (next() % 90).toDouble() }
            val impRhr = nights(nImp, impEnd, 9) { (45 + next() % 20).toDouble() }
            val impResp = nights(nImp, impEnd, 9) { 12 + (next() % 60).toDouble() / 10 }
            val ownHrv = nights(nOwn, ownEnd, 7) { 20 + (next() % 1200).toDouble() / 10 }
            val ownRhr = nights(nOwn, ownEnd, 7) { (42 + next() % 25).toDouble() }
            val ownResp = nights(nOwn, ownEnd, 7) { 11 + (next() % 80).toDouble() / 10 }
            val ownSkin = nights(nOwn, ownEnd, 5) { 32 + (next() % 40).toDouble() / 10 }
            val seedHrv = nights(nSeed, seedEnd, 6) { 25 + (next() % 100).toDouble() }
            val seedRhr = nights(nSeed, seedEnd, 6) { (44 + next() % 22).toDouble() }
            val seedResp = nights(nSeed, seedEnd, 6) { 12 + (next() % 50).toDouble() / 10 }
            val imported = ChargeBaselines.Nights(impHrv, impRhr, impResp)
            val own = ChargeBaselines.Nights(ownHrv, ownRhr, ownResp, ownSkin)
            val seed = ChargeBaselines.Nights(seedHrv, seedRhr, seedResp)
            // Own nights switch brand `switchBack` days before the scored day (0 = never).
            val brands = listOf("my-whoop", "garmin-AAA", "oura-import")
            val newBrand = brands[next() % 3]
            val switchBack = next() % 30
            val ownSources = ownHrv.map { n ->
                val back = scored - Baselines.isoEpochDay(n.first)!!
                n.first to (
                    if (switchBack > 0 && back < switchBack) newBrand
                    else if (next() % 6 == 0) null else "my-whoop"
                    )
            }
            val seedSources = seedHrv.map { ChargeBaselines.SourceDay(it.first, "garmin-import") }
            val sources = ChargeBaselines.sourceDays(
                ownSources, "my-whoop", impHrv.map { it.first }, "my-whoop", seedSources,
            )
            val hrvRecal = if (next() % 6 == 0) (scored - next() % 70).toDouble() * 86_400 else 0.0
            val recRecal = if (next() % 6 == 0) (scored - next() % 70).toDouble() * 86_400 else 0.0
            val r = ChargeBaselines.resolveDay(dayKey(scored), imported, own, seed, sources, hrvRecal, recRecal)
            if (r == null) {
                lines.add("c=$c nil")
                continue
            }
            fun ep(e: Double): String = if (e > 0) dayKey((e / 86_400).toInt()) else "-"
            fun st(name: String, h: ChargeBaselines.History, b: BaselineState): String =
                "$name=${if (h.seededByImport) "s" else "o"}${h.ownValidNights}+${h.importedNights}/" +
                    "n${b.nValid}/${b.status.raw}/${f6(b.baseline)}/${f6(b.spread)}/${b.nightsSinceUpdate}"
            lines.add(
                "c=$c day=${r.scoredDay} ep=${ep(r.epochs.hrv)},${ep(r.epochs.restingHR)}," +
                    "${ep(r.epochs.resp)},${ep(r.epochs.skin)} " +
                    st("hrv", r.hrvHistory, r.hrv) + " " + st("rhr", r.restingHRHistory, r.restingHR) + " " +
                    st("resp", r.respHistory, r.resp) + " " + st("skin", r.skinHistory, r.skin),
            )
        }
        return lines
    }

    @Test fun oracleSpread() {
        assertEquals(oracleLiteral, oracleLines().joinToString("\n"))
    }

    private val oracleLiteral = """
c=0 day=2025-06-25 ep=2025-06-22,2025-06-22,2025-06-22,- hrv=s3+24/n3/calibrating/64.710175/0.610071/0 rhr=s3+25/n3/calibrating/52.000000/4.788508/0 resp=s3+26/n3/calibrating/13.566667/1.132418/0 skin=o29+0/n29/trusted/34.103448/0.867331/0
c=1 day=2025-05-12 ep=2025-04-22,2025-04-22,2025-04-22,- hrv=s13+23/n13/provisional/64.517798/0.448642/3 rhr=o15+0/n15/trusted/52.466667/5.495178/3 resp=s13+22/n13/provisional/14.284615/1.783038/3 skin=o26+0/n26/trusted/34.146154/0.933515/3
c=2 day=2025-02-13 ep=2025-02-10,2025-02-10,2025-02-10,- hrv=s0+7/n1/calibrating/65.000000/0.039904/2 rhr=s0+4/n0/calibrating/75.000000/1.596169/0 resp=s0+7/n1/calibrating/16.900000/0.399042/2 skin=o34+0/n34/trusted/33.941176/0.768545/3
c=3 day=2026-01-10 ep=2026-01-02,2026-01-02,2026-01-02,- hrv=s4+34/n5/provisional/63.661994/0.482593/3 rhr=s5+36/n5/provisional/51.600000/4.570733/3 resp=s5+37/n5/provisional/14.720000/2.180831/3 skin=o4+0/n4/provisional/33.725000/0.717907/4
c=4 day=2025-10-16 ep=2025-09-20,2025-09-20,2025-09-20,- hrv=o23+0/n23/trusted/63.799867/0.443995/1 rhr=o19+0/n19/trusted/52.842105/5.201264/2 resp=o23+0/n23/trusted/15.552174/2.099001/1 skin=o50+0/n50/trusted/33.942000/0.964072/1
c=5 day=2025-10-27 ep=2025-10-23,2025-10-23,2025-10-23,2025-09-05 hrv=s3+38/n3/calibrating/100.579262/0.077611/1 rhr=s4+39/n4/provisional/48.250000/3.983767/0 resp=s4+39/n4/provisional/13.925000/2.373212/0 skin=o3+0/n3/calibrating/35.166667/0.945430/0
c=6 day=2025-11-10 ep=-,-,2025-11-01,- hrv=o25+0/n25/trusted/82.738563/0.323457/0 rhr=o27+0/n27/trusted/54.777778/5.819973/0 resp=s8+2/n8/provisional/15.112500/1.423468/1 skin=o30+0/n30/trusted/33.686667/1.004213/0
c=7 day=2025-01-18 ep=-,-,2024-12-28,- hrv=o25+0/n25/trusted/54.168697/0.438866/0 rhr=o23+0/n23/trusted/53.826087/4.657649/0 resp=o21+0/n21/trusted/14.076190/1.754680/0 skin=o23+0/n23/trusted/34.021739/0.934430/0
c=8 day=2025-10-29 ep=2025-08-04,2025-08-04,2025-08-04,- hrv=s11+39/n47/trusted/67.102613/0.375481/4 rhr=s11+39/n48/trusted/55.354167/5.120804/3 resp=s13+39/n50/trusted/14.990000/1.516939/3 skin=o11+0/n11/provisional/33.609091/0.947978/3
c=9 day=2025-06-10 ep=2025-06-02,2025-06-02,2025-06-02,- hrv=s8+36/n8/provisional/65.948399/0.532264/0 rhr=s5+36/n5/provisional/54.800000/6.897778/0 resp=s8+36/n8/provisional/14.375000/1.854458/0 skin=o16+0/n16/trusted/34.025000/1.085121/1
c=10 day=2025-08-19 ep=2025-07-27,2025-07-27,2025-07-27,- hrv=o19+0/n19/trusted/61.528465/0.363861/0 rhr=o17+0/n17/trusted/54.705882/4.933983/0 resp=o19+0/n19/trusted/15.763158/2.167470/0 skin=o18+0/n18/trusted/34.150000/1.021772/1
c=11 day=2026-01-24 ep=2026-01-21,2026-01-21,2026-01-21,2026-01-15 hrv=s0+60/n3/calibrating/54.761502/0.443509/0 rhr=s0+60/n3/calibrating/53.000000/4.146969/0 resp=s0+60/n3/calibrating/13.433333/0.799414/0 skin=o5+0/n5/provisional/33.460000/1.098055/3
c=12 day=2025-12-24 ep=2025-11-17,2025-10-28,2025-10-28,- hrv=o28+0/n28/trusted/90.592075/0.292399/3 rhr=o46+0/n46/trusted/53.173913/5.689035/2 resp=o42+0/n42/trusted/14.323810/1.665686/2 skin=o50+0/n50/trusted/33.742000/0.801188/2
c=13 day=2025-12-19 ep=2025-12-08,2025-12-08,2025-12-08,2025-10-20 hrv=s10+13/n10/provisional/59.927374/0.520296/1 rhr=s9+13/n9/provisional/53.888889/4.861843/1 resp=s9+13/n9/provisional/15.744444/1.717788/1 skin=o6+0/n6/provisional/34.233333/1.113512/1
c=14 day=2025-05-03 ep=2025-04-20,2025-04-20,2025-04-20,- hrv=s11+42/n11/provisional/60.650612/0.423067/1 rhr=s10+43/n10/provisional/56.000000/3.686195/1 resp=s11+44/n12/provisional/13.908333/1.871816/1 skin=o8+0/n8/provisional/33.800000/1.075830/1
c=15 day=2025-10-16 ep=2025-08-31,2025-08-19,2025-08-19,- hrv=s3+54/n39/trusted/65.874196/0.354552/0 rhr=s2+54/n54/trusted/54.648148/5.273782/1 resp=s4+53/n54/trusted/15.205556/1.239243/0 skin=o4+0/n4/provisional/34.225000/0.801734/0
c=16 day=2025-10-27 ep=2025-10-23,2025-10-23,2025-10-23,- hrv=s3+25/n3/calibrating/35.491138/0.649749/1 rhr=s2+25/n2/calibrating/51.000000/7.900634/1 resp=s3+25/n3/calibrating/14.033333/1.341004/1 skin=o3+0/n3/calibrating/34.033333/0.719753/1
c=17 day=2025-02-01 ep=2025-01-13,2025-01-13,2025-01-13,2024-11-27 hrv=o16+0/n16/trusted/76.685549/0.333527/0 rhr=o19+0/n19/trusted/53.578947/5.181173/0 resp=o17+0/n17/trusted/15.070588/1.699275/0 skin=o29+0/n29/trusted/33.793103/0.932411/0
c=18 day=2025-03-07 ep=2025-01-01,2024-12-16,2024-12-16,- hrv=o0+0/n0/calibrating/127.500000/0.039904/0 rhr=o1+0/n1/calibrating/54.000000/1.596169/0 resp=o1+0/n1/calibrating/17.600000/0.399042/0 skin=o1+0/n1/calibrating/35.300000/0.239425/0
c=19 day=2025-11-27 ep=2025-11-02,2025-11-02,2025-11-02,- hrv=o20+0/n20/trusted/68.149220/0.432925/0 rhr=o21+0/n21/trusted/51.095238/6.009025/0 resp=o23+0/n23/trusted/14.195652/1.935922/0 skin=o19+0/n19/trusted/34.336842/0.813981/1
c=20 day=2025-01-02 ep=2025-01-01,2025-01-01,2025-01-01,- hrv=s0+17/n1/calibrating/70.000000/0.039904/0 rhr=s0+17/n1/calibrating/45.000000/1.596169/0 resp=s0+17/n1/calibrating/17.700000/0.399042/0 skin=o33+0/n33/trusted/33.412121/0.980044/1
c=21 day=2025-06-01 ep=2025-05-17,2025-05-17,2025-05-17,- hrv=s12+15/n12/provisional/76.120921/0.463305/3 rhr=s13+15/n13/provisional/56.000000/5.333849/2 resp=s12+15/n12/provisional/14.091667/1.489375/2 skin=o30+0/n30/trusted/34.080000/0.878868/2
c=22 day=2025-03-15 ep=-,-,-,- hrv=s9+20/n28/trusted/64.123739/0.346792/2 rhr=s10+20/n30/trusted/55.933333/4.360893/2 resp=s10+20/n28/trusted/14.932143/1.437382/2 skin=o8+0/n8/provisional/34.675000/0.942136/2
c=23 day=2025-04-29 ep=2025-04-05,2025-04-26,2025-04-26,2025-04-26 hrv=o20+0/n20/trusted/67.756766/0.433569/0 rhr=s3+29/n3/calibrating/48.333333/2.303872/0 resp=s3+23/n3/calibrating/14.066667/1.948918/0 skin=o3+0/n3/calibrating/34.800000/1.055767/0
c=24 day=2025-03-28 ep=2025-03-07,-,2025-03-19,- hrv=o17+0/n17/trusted/65.391949/0.402066/1 rhr=o28+0/n28/trusted/53.250000/6.263914/0 resp=s9+23/n9/provisional/14.177778/1.773732/0 skin=o25+0/n25/trusted/33.716000/0.711475/1
c=25 day=2025-05-29 ep=2025-05-05,-,-,- hrv=o18+0/n18/trusted/67.635775/0.408726/0 rhr=o45+0/n45/trusted/53.800000/5.590737/0 resp=o43+0/n43/trusted/15.123256/1.944854/2 skin=o33+0/n33/trusted/34.069697/0.718687/0
c=26 day=2025-03-03 ep=2025-02-27,2025-02-27,2025-02-27,- hrv=s4+32/n4/provisional/76.260765/0.457770/0 rhr=s3+32/n3/calibrating/59.333333/2.438189/0 resp=s4+32/n4/provisional/16.925000/2.679136/0 skin=o3+0/n3/calibrating/34.200000/0.761324/0
c=27 day=2025-01-28 ep=2025-01-13,2025-01-13,2025-01-13,- hrv=s11+19/n11/provisional/88.352941/0.304763/2 rhr=s11+18/n11/provisional/55.545455/4.924429/2 resp=s12+22/n12/provisional/15.225000/2.213058/2 skin=o49+0/n49/trusted/33.967347/0.838525/2
c=28 day=2025-05-03 ep=2025-04-17,2025-04-22,2025-04-22,2025-04-22 hrv=s13+44/n13/provisional/71.976955/0.384524/1 rhr=s9+44/n9/provisional/54.000000/4.371289/1 resp=s8+44/n8/provisional/14.862500/1.829854/0 skin=o9+0/n9/provisional/34.500000/0.920396/1
c=29 day=2025-03-13 ep=-,-,2025-02-23,- hrv=o20+0/n20/trusted/62.968824/0.442137/1 rhr=o18+0/n18/trusted/49.000000/4.004482/2 resp=s12+43/n12/provisional/15.566667/1.282967/1 skin=o15+0/n15/trusted/34.326667/0.781650/1
c=30 day=2025-11-21 ep=-,-,-,- hrv=o49+0/n49/trusted/61.813697/0.492695/3 rhr=o50+0/n50/trusted/54.660000/5.854962/4 resp=o50+0/n50/trusted/15.254000/1.946852/3 skin=o41+0/n41/trusted/33.992683/0.837493/3
c=31 day=2025-02-23 ep=2025-01-19,2025-01-19,2025-01-19,- hrv=o30+0/n30/trusted/65.637938/0.460819/1 rhr=o33+0/n33/trusted/54.333333/5.920995/0 resp=o35+0/n35/trusted/14.720000/1.775816/0 skin=o33+0/n33/trusted/33.715152/0.923445/0
c=32 day=2025-07-09 ep=2025-05-24,2025-05-24,2025-05-24,- hrv=o20+0/n20/trusted/79.757351/0.323384/1 rhr=o26+0/n26/trusted/54.230769/5.676206/0 resp=o23+0/n23/trusted/15.139130/1.982655/0 skin=o21+0/n21/trusted/33.980952/0.785462/0
c=33 day=2025-08-20 ep=2025-08-08,2025-08-08,2025-08-08,- hrv=s9+8/n9/provisional/75.014152/0.358872/0 rhr=s11+8/n11/provisional/55.727273/6.894000/0 resp=s12+8/n12/provisional/14.950000/2.037710/0 skin=o15+0/n15/trusted/34.420000/0.731581/0
c=34 day=2025-03-20 ep=2025-03-13,2025-03-13,2025-03-13,- hrv=o4+0/n4/provisional/67.452347/0.492438/3 rhr=o4+0/n4/provisional/51.500000/8.203854/3 resp=o3+0/n3/calibrating/16.066667/2.008469/3 skin=o4+0/n4/provisional/35.125000/0.301270/3
c=35 day=2025-03-15 ep=2025-02-03,-,-,- hrv=o35+0/n35/trusted/73.982853/0.367779/2 rhr=o44+0/n44/trusted/54.068182/6.390237/2 resp=o47+0/n47/trusted/15.176596/1.926734/2 skin=o46+0/n46/trusted/34.080435/0.880327/2
c=36 day=2025-01-04 ep=-,-,2024-12-09,- hrv=o24+0/n24/trusted/81.914811/0.369669/0 rhr=o20+0/n20/trusted/56.250000/5.374806/0 resp=o22+0/n22/trusted/14.018182/1.910748/0 skin=o22+0/n22/trusted/33.563636/0.907802/0
c=37 day=2025-08-19 ep=-,-,2025-08-10,- hrv=o52+0/n52/trusted/71.126549/0.417679/2 rhr=o51+0/n51/trusted/55.450980/5.718016/2 resp=s7+24/n7/provisional/14.528571/1.941667/2 skin=o47+0/n47/trusted/33.678723/0.908881/2
c=38 day=2025-10-27 ep=-,-,-,- hrv=o47+0/n47/trusted/72.211121/0.419972/1 rhr=o49+0/n49/trusted/52.816327/5.850368/1 resp=o46+0/n46/trusted/15.058696/1.706365/2 skin=o38+0/n38/trusted/34.305263/0.951561/1
c=39 day=2025-10-11 ep=2025-09-29,2025-09-29,2025-09-29,- hrv=s9+11/n9/provisional/84.305327/0.281489/2 rhr=s8+10/n8/provisional/56.875000/4.338643/3 resp=s8+12/n8/provisional/14.875000/2.097590/2 skin=o14+0/n14/trusted/33.814286/0.873056/2
c=40 day=2025-01-07 ep=-,-,-,- hrv=o49+0/n49/trusted/77.238528/0.353540/0 rhr=o55+0/n55/trusted/55.745455/5.327694/0 resp=o53+0/n53/trusted/15.111321/1.820967/0 skin=o50+0/n50/trusted/33.752000/0.986220/1
c=41 day=2025-01-16 ep=2024-12-24,2024-12-24,2024-12-24,- hrv=o18+0/n18/trusted/76.830262/0.329820/1 rhr=o20+0/n20/trusted/53.000000/5.955455/1 resp=o22+0/n22/trusted/14.309091/1.843082/1 skin=o19+0/n19/trusted/33.894737/0.819727/1
c=42 day=2025-11-30 ep=-,-,-,- hrv=s8+51/n48/trusted/63.857371/0.315722/2 rhr=s6+51/n50/trusted/53.140000/4.204589/2 resp=s7+51/n46/trusted/15.067391/1.421702/2 skin=o7+0/n7/provisional/34.114286/1.130944/2
c=43 day=2025-03-04 ep=2025-01-15,2025-01-15,2025-01-15,- hrv=o40+0/n40/trusted/70.211762/0.426627/0 rhr=o36+0/n36/trusted/54.611111/5.657088/0 resp=o40+0/n40/trusted/15.182500/1.565891/0 skin=o36+0/n36/trusted/33.683333/0.868096/2
c=44 day=2025-05-06 ep=2025-04-01,2025-04-01,2025-04-01,- hrv=s0+48/n23/trusted/66.439064/0.403454/9 rhr=s0+47/n22/trusted/54.590909/5.527422/8 resp=s0+46/n21/trusted/14.442857/1.080092/9 skin=o0+0/n0/calibrating/31.000000/0.239425/0
c=45 day=2025-02-12 ep=2025-01-18,2025-01-18,2025-01-18,- hrv=o19+0/n19/trusted/72.959927/0.473858/1 rhr=o21+0/n21/trusted/55.333333/5.645191/1 resp=o22+0/n22/trusted/15.063636/2.011117/1 skin=o34+0/n34/trusted/34.211765/0.943849/1
c=46 day=2025-04-13 ep=2025-04-06,2025-04-06,2025-04-06,- hrv=s6+34/n6/provisional/59.624617/0.511912/1 rhr=s7+32/n7/provisional/55.142857/5.962153/0 resp=s6+31/n6/provisional/14.266667/1.540669/0 skin=o7+0/n7/provisional/34.257143/1.082435/0
c=47 day=2025-01-20 ep=2024-12-31,2024-12-31,2024-12-31,- hrv=o18+0/n18/trusted/71.871883/0.422464/0 rhr=o18+0/n18/trusted/57.000000/4.858416/0 resp=o19+0/n19/trusted/14.089474/1.526338/0 skin=o46+0/n46/trusted/34.165217/1.034466/1
c=48 day=2025-06-21 ep=2025-06-01,2025-06-01,2025-06-01,- hrv=o17+0/n17/trusted/85.824842/0.419174/0 rhr=o17+0/n17/trusted/55.352941/5.607511/0 resp=o17+0/n17/trusted/14.758824/1.908753/0 skin=o34+0/n34/trusted/33.755882/0.769818/0
c=49 day=2025-10-06 ep=2025-09-23,2025-09-23,2025-09-23,- hrv=s12+58/n13/provisional/68.894606/0.217642/0 rhr=s11+58/n12/provisional/55.416667/6.361584/0 resp=s11+58/n12/provisional/15.525000/1.410981/0 skin=o22+0/n22/trusted/33.954545/0.915184/0
c=50 day=2026-01-17 ep=-,-,-,- hrv=o14+0/n14/trusted/91.200431/0.310582/3 rhr=s12+11/n20/trusted/53.550000/5.532171/2 resp=o16+0/n16/trusted/15.950000/1.645423/2 skin=o15+0/n15/trusted/33.693333/0.892524/2
c=51 day=2025-08-08 ep=2025-07-28,2025-07-28,2025-07-28,2025-07-02 hrv=s8+25/n10/provisional/76.757948/0.264994/1 rhr=s9+24/n9/provisional/51.666667/4.316302/1 resp=s10+25/n10/provisional/14.950000/1.651626/1 skin=o8+0/n8/provisional/33.725000/0.972550/1
c=52 day=2025-09-24 ep=2025-09-16,2025-08-27,2025-08-27,- hrv=s4+20/n4/provisional/65.856400/0.604877/3 rhr=o22+0/n22/trusted/52.090909/5.429149/1 resp=o23+0/n23/trusted/15.365217/2.048283/2 skin=o24+0/n24/trusted/33.895833/0.832553/3
c=53 day=2025-04-16 ep=-,-,2025-04-07,- hrv=o25+0/n25/trusted/67.892979/0.467671/0 rhr=o23+0/n23/trusted/57.173913/5.511735/0 resp=s8+7/n8/provisional/14.675000/2.489187/1 skin=o21+0/n21/trusted/34.180952/0.885701/0
c=54 day=2025-10-10 ep=-,-,-,- hrv=o25+0/n25/trusted/70.542391/0.358816/0 rhr=o28+0/n28/trusted/55.892857/5.898149/0 resp=o23+0/n23/trusted/15.430435/1.944570/0 skin=o23+0/n23/trusted/34.095652/1.042654/1
c=55 day=2025-06-07 ep=2025-03-26,2025-03-26,2025-03-26,- hrv=o25+0/n25/trusted/63.029677/0.516939/4 rhr=o25+0/n25/trusted/51.320000/5.935410/3 resp=o21+0/n21/trusted/15.500000/2.025154/4 skin=o30+0/n30/trusted/33.570000/0.801147/3
c=56 day=2025-03-23 ep=2025-01-20,2025-01-20,2025-01-20,- hrv=s13+24/n34/trusted/77.982521/0.314244/0 rhr=s10+24/n32/trusted/54.937500/4.813917/1 resp=s13+24/n37/trusted/15.162162/1.497617/0 skin=o10+0/n10/provisional/33.370000/0.913101/0
c=57 day=2025-08-16 ep=2025-06-09,2025-06-09,2025-06-09,- hrv=s3+35/n37/trusted/66.810601/0.344030/0 rhr=s3+35/n33/trusted/55.303030/4.681248/0 resp=s3+35/n34/trusted/14.432353/1.297288/0 skin=o3+0/n3/calibrating/33.833333/0.599007/0
c=58 day=2025-11-26 ep=2025-11-15,-,2025-10-31,- hrv=s8+26/n8/provisional/52.544329/0.322643/2 rhr=o26+0/n26/trusted/54.576923/5.260936/2 resp=o20+0/n20/trusted/14.745000/1.838145/2 skin=o24+0/n24/trusted/33.691667/0.914485/2
c=59 day=2026-01-20 ep=2026-01-19,2026-01-19,2026-01-19,- hrv=s1+4/n1/calibrating/51.100000/0.039904/0 rhr=s1+6/n1/calibrating/65.000000/1.596169/0 resp=s1+7/n1/calibrating/12.600000/0.399042/0 skin=o7+0/n7/provisional/33.857143/1.052602/0
    """.trimIndent()
}
