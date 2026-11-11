package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Pins every [WeeklyMetric] spec and the card sections. The expected text is the Swift twin's output. */
class WeeklyMetricSpecTest {
    private val expected = """
charge|Charge||mean|higherBetter|12.0|12.0|0|true|nil|nil
effort|Effort||mean|higherBetter|12.0|12.0|0|true|nil|nil
rest|Rest||mean|higherBetter|12.0|12.0|0|true|nil|nil
rhr|Resting HR|bpm|mean|lowerBetter|4.0|4.0|0|true|nil|nil
hrv|HRV|ms|mean|higherBetter|8.0|8.0|0|true|nil|nil
steps|Steps|steps|mean|higherBetter|nil|500.0|0|true|nil|nil
activeKcal|Active calories|kcal|mean|higherBetter|nil|50.0|0|true|nil|nil
sleepDuration|Sleep duration|h|mean|neutral|nil|0.25|1|true|nil|nil
sleepEfficiency|Sleep efficiency|%|mean|higherBetter|nil|2.0|0|false|nil|nil
deepSleep|Deep sleep|min|mean|higherBetter|nil|5.0|0|true|nil|nil
remSleep|REM sleep|min|mean|higherBetter|nil|5.0|0|true|nil|nil
sleepScore|Sleep score||mean|higherBetter|nil|4.0|0|true|nil|nil
spo2|Overnight SpO2|%|mean|neutral|nil|0.5|1|false|nil|nil
respiration|Respiration|brpm|mean|neutral|nil|0.3|1|true|nil|nil
skinTempDeviation|Skin temp deviation|°C|mean|neutral|nil|0.1|1|false|nil|nil
stress|Stress||mean|lowerBetter|nil|3.0|0|true|nil|nil
bodyBatteryRecharge|Body Battery recharge||mean|higherBetter|nil|5.0|0|true|nil|nil
trainingLoad|Training load||sum|neutral|nil|25.0|0|true|nil|nil
intensityMinutes|Intensity minutes|min|sum|higherBetter|nil|15.0|0|true|150.0|nil
vo2max|VO2 max|ml/kg/min|latest|higherBetter|nil|1.0|0|false|nil|nil
lightSleep|Light sleep|min|mean|neutral|nil|5.0|0|true|nil|sleep_light_min
sleepNeed|Sleep need|min|mean|neutral|nil|5.0|0|true|nil|sleep_need_min
sleepDebt|Sleep debt|min|mean|lowerBetter|nil|10.0|0|true|nil|sleep_debt_min
sleepConsistency|Sleep consistency|%|mean|higherBetter|nil|3.0|0|false|nil|sleep_consistency
disturbances|Disturbances||mean|lowerBetter|nil|1.0|0|true|nil|disturbances
avgHr|Average heart rate|bpm|mean|neutral|nil|2.0|0|true|nil|avg_hr
maxHr|Max heart rate|bpm|mean|neutral|nil|4.0|0|true|nil|max_hr
sdnn|HRV (SDNN)|ms|mean|higherBetter|nil|4.0|0|true|nil|sdnn
totalKcal|Total calories|kcal|mean|neutral|nil|100.0|0|true|nil|energy_kcal
basalKcal|Basal calories|kcal|mean|neutral|nil|50.0|0|true|nil|basal_kcal
distance|Distance|km|mean|neutral|nil|0.5|1|true|nil|distance_km
workoutCount|Workouts||sum|neutral|nil|1.0|0|true|nil|workout_count
workoutMinutes|Workout time|min|sum|higherBetter|nil|15.0|0|true|nil|workout_duration_min
workoutKcal|Workout calories|kcal|sum|neutral|nil|100.0|0|true|nil|workout_energy_kcal
workoutMaxHr|Workout max heart rate|bpm|mean|neutral|nil|4.0|0|true|nil|workout_max_hr
workoutPower|Average power|W|mean|neutral|nil|10.0|0|true|nil|workout_avg_power_w
workoutCadence|Average cadence|rpm|mean|neutral|nil|3.0|0|true|nil|workout_avg_cadence
workoutElevation|Elevation gain|m|sum|neutral|nil|25.0|0|true|nil|workout_altitude_gain_m
aerobicTe|Aerobic training effect||mean|neutral|nil|0.3|1|false|nil|workout_aerobic_te
anaerobicTe|Anaerobic training effect||mean|neutral|nil|0.3|1|false|nil|workout_anaerobic_te
weight|Weight|kg|latest|neutral|nil|0.3|1|false|nil|weight
bodyFat|Body fat|%|latest|lowerBetter|nil|0.5|1|false|nil|body_fat
leanMass|Lean mass|kg|latest|higherBetter|nil|0.3|1|false|nil|lean_mass
bmi|BMI||latest|neutral|nil|0.2|1|false|nil|bmi
fitnessAge|Fitness Age|yrs|latest|lowerBetter|nil|1.0|0|false|nil|fitness_age
vitality|Vitality||latest|higherBetter|nil|3.0|0|false|nil|vitality
bodyAge|Body Age|yrs|latest|lowerBetter|nil|1.0|0|false|nil|body_age
vo2maxEst|VO2 max (estimated)|ml/kg/min|latest|higherBetter|nil|1.0|0|false|nil|vo2max_est
caloriesIn|Calories in|kcal|mean|neutral|nil|150.0|0|true|nil|calories_in
protein|Protein|g|mean|neutral|nil|10.0|0|true|nil|protein_g
carbs|Carbs|g|mean|neutral|nil|15.0|0|true|nil|carbs_g
fat|Fat|g|mean|neutral|nil|8.0|0|true|nil|fat_g
water|Water|ml|mean|neutral|nil|150.0|0|true|nil|hydration_ml
mood|Mood|/5|mean|higherBetter|nil|0.4|1|false|nil|mood
activity: steps,activeKcal,totalKcal,basalKcal,distance,intensityMinutes
sleep: sleepDuration,sleepEfficiency,deepSleep,remSleep,lightSleep,sleepScore,sleepNeed,sleepDebt,sleepConsistency,disturbances
overnightVitals: spo2,respiration,skinTempDeviation
stressEnergy: stress,bodyBatteryRecharge
training: trainingLoad,vo2max
heart: avgHr,maxHr,sdnn
workouts: workoutCount,workoutMinutes,workoutKcal,workoutMaxHr,workoutPower,workoutCadence,workoutElevation,aerobicTe,anaerobicTe
bodyComposition: weight,bodyFat,leanMass,bmi
fitnessScores: fitnessAge,vitality,bodyAge,vo2maxEst
nutrition: caloriesIn,protein,carbs,fat,water
mind: mood
""".trimIndent()

    private fun camel(s: String): String {
        val p = s.lowercase().split('_')
        return p[0] + p.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    @Test fun specsAndSectionsMatchSwift() {
        val lines = ArrayList<String>()
        for (m in WeeklyMetric.values()) {
            val s = m.spec
            lines.add(
                "${m.key}|${s.label}|${s.unit}|${s.rollup.name.lowercase()}|${camel(s.direction.name)}|" +
                    "${s.fixedSpread ?: "nil"}|${s.spreadFloor}|${s.decimals}|${s.percentChange}|" +
                    "${s.weeklyTarget ?: "nil"}|${m.snapshotId ?: "nil"}",
            )
        }
        for (sec in WeeklyDigestSection.values()) lines.add("${sec.key}: ${sec.metrics.joinToString(",") { it.key }}")
        assertEquals(expected, lines.joinToString("\n"))
    }

    @Test fun snapshotIdsExistAndFeedTheSeries() {
        for (m in WeeklyMetric.values()) {
            val id = m.snapshotId ?: continue
            assertNotNull(id, MetricSnapshotCatalog.allItems().firstOrNull { it.id == id })
        }
        val series = WeeklyDigestInputs.snapshotSeries(
            mapOf("body_fat" to mapOf("2026-06-01" to 18.5), "weight" to mapOf("2026-06-01" to 80.0)),
        )
        assertEquals(18.5, series[WeeklyMetric.BODY_FAT]!!["2026-06-01"]!!, 0.0)
        assertEquals(80.0, series[WeeklyMetric.WEIGHT]!!["2026-06-01"]!!, 0.0)
        assertEquals(null, series[WeeklyMetric.LEAN_MASS])
    }
}
