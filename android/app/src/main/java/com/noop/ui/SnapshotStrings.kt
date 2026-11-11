package com.noop.ui

import androidx.annotation.StringRes
import com.noop.R
import com.noop.analytics.MetricSnapshotCategory

// Resource lookups for the metric snapshot catalog (generated from MetricSnapshotCatalog ids; a test pins that
// every catalog item and category has one).

/** The localized label of a snapshot item, by [com.noop.analytics.MetricSnapshotItem.id]. */
@StringRes
internal fun snapshotItemLabelRes(id: String): Int = when (id) {
    "recovery" -> R.string.snapshot_item_recovery
    "strain" -> R.string.snapshot_item_strain
    "hrv" -> R.string.snapshot_item_hrv
    "rhr" -> R.string.snapshot_item_rhr
    "stress" -> R.string.snapshot_item_stress
    "sleep_performance" -> R.string.snapshot_item_sleep_performance
    "sleep_total_min" -> R.string.snapshot_item_sleep_total_min
    "in_bed_min" -> R.string.snapshot_item_in_bed_min
    "sleep_efficiency" -> R.string.snapshot_item_sleep_efficiency
    "sleep_deep_min" -> R.string.snapshot_item_sleep_deep_min
    "sleep_rem_min" -> R.string.snapshot_item_sleep_rem_min
    "sleep_light_min" -> R.string.snapshot_item_sleep_light_min
    "restorative_min" -> R.string.snapshot_item_restorative_min
    "sleep_need_min" -> R.string.snapshot_item_sleep_need_min
    "sleep_debt_min" -> R.string.snapshot_item_sleep_debt_min
    "sleep_consistency" -> R.string.snapshot_item_sleep_consistency
    "hours_vs_needed_pct" -> R.string.snapshot_item_hours_vs_needed_pct
    "disturbances" -> R.string.snapshot_item_disturbances
    "avg_hr" -> R.string.snapshot_item_avg_hr
    "max_hr" -> R.string.snapshot_item_max_hr
    "resp_rate" -> R.string.snapshot_item_resp_rate
    "spo2" -> R.string.snapshot_item_spo2
    "skin_temp" -> R.string.snapshot_item_skin_temp
    "sdnn" -> R.string.snapshot_item_sdnn
    "steps" -> R.string.snapshot_item_steps
    "steps_est" -> R.string.snapshot_item_steps_est
    "distance_km" -> R.string.snapshot_item_distance_km
    "energy_kcal" -> R.string.snapshot_item_energy_kcal
    "active_kcal" -> R.string.snapshot_item_active_kcal
    "basal_kcal" -> R.string.snapshot_item_basal_kcal
    "strength_min" -> R.string.snapshot_item_strength_min
    "hr_zones13_min" -> R.string.snapshot_item_hr_zones13_min
    "hr_zones45_min" -> R.string.snapshot_item_hr_zones45_min
    "hr_zones_all_min" -> R.string.snapshot_item_hr_zones_all_min
    "workout_count" -> R.string.snapshot_item_workout_count
    "workout_duration_min" -> R.string.snapshot_item_workout_duration_min
    "workout_energy_kcal" -> R.string.snapshot_item_workout_energy_kcal
    "workout_distance_km" -> R.string.snapshot_item_workout_distance_km
    "workout_avg_hr" -> R.string.snapshot_item_workout_avg_hr
    "workout_max_hr" -> R.string.snapshot_item_workout_max_hr
    "workout_effort" -> R.string.snapshot_item_workout_effort
    "workout_zone1_min" -> R.string.snapshot_item_workout_zone1_min
    "workout_zone2_min" -> R.string.snapshot_item_workout_zone2_min
    "workout_zone3_min" -> R.string.snapshot_item_workout_zone3_min
    "workout_zone4_min" -> R.string.snapshot_item_workout_zone4_min
    "workout_zone5_min" -> R.string.snapshot_item_workout_zone5_min
    "workout_avg_power_w" -> R.string.snapshot_item_workout_avg_power_w
    "workout_avg_cadence" -> R.string.snapshot_item_workout_avg_cadence
    "workout_altitude_gain_m" -> R.string.snapshot_item_workout_altitude_gain_m
    "workout_max_altitude_m" -> R.string.snapshot_item_workout_max_altitude_m
    "workout_aerobic_te" -> R.string.snapshot_item_workout_aerobic_te
    "workout_anaerobic_te" -> R.string.snapshot_item_workout_anaerobic_te
    "weight" -> R.string.snapshot_item_weight
    "body_fat" -> R.string.snapshot_item_body_fat
    "lean_mass" -> R.string.snapshot_item_lean_mass
    "bmi" -> R.string.snapshot_item_bmi
    "fitness_age" -> R.string.snapshot_item_fitness_age
    "vitality" -> R.string.snapshot_item_vitality
    "body_age" -> R.string.snapshot_item_body_age
    "vo2max" -> R.string.snapshot_item_vo2max
    "vo2max_est" -> R.string.snapshot_item_vo2max_est
    "watch_stress" -> R.string.snapshot_item_watch_stress
    "body_battery_min" -> R.string.snapshot_item_body_battery_min
    "body_battery_max" -> R.string.snapshot_item_body_battery_max
    "body_battery_recharge" -> R.string.snapshot_item_body_battery_recharge
    "watch_sleep_score" -> R.string.snapshot_item_watch_sleep_score
    "training_load" -> R.string.snapshot_item_training_load
    "intensity_min" -> R.string.snapshot_item_intensity_min
    "watch_skin_temp_c" -> R.string.snapshot_item_watch_skin_temp_c
    "calories_in" -> R.string.snapshot_item_calories_in
    "protein_g" -> R.string.snapshot_item_protein_g
    "carbs_g" -> R.string.snapshot_item_carbs_g
    "fat_g" -> R.string.snapshot_item_fat_g
    "hydration_ml" -> R.string.snapshot_item_hydration_ml
    "mood" -> R.string.snapshot_item_mood
    "cycle_day" -> R.string.snapshot_item_cycle_day
    "sleep_awake_count" -> R.string.snapshot_item_sleep_awake_count
    "sleep_restless_moments" -> R.string.snapshot_item_sleep_restless_moments
    "sleep_stress" -> R.string.snapshot_item_sleep_stress
    "sleep_awake_min" -> R.string.snapshot_item_sleep_awake_min
    "nap_min" -> R.string.snapshot_item_nap_min
    "sleep_score_quality" -> R.string.snapshot_item_sleep_score_quality
    "sleep_score_duration" -> R.string.snapshot_item_sleep_score_duration
    "sleep_score_recovery" -> R.string.snapshot_item_sleep_score_recovery
    "sleep_score_deep" -> R.string.snapshot_item_sleep_score_deep
    "sleep_score_rem" -> R.string.snapshot_item_sleep_score_rem
    "sleep_score_restfulness" -> R.string.snapshot_item_sleep_score_restfulness
    "hr_min" -> R.string.snapshot_item_hr_min
    "hr_overnight" -> R.string.snapshot_item_hr_overnight
    "resp_overnight" -> R.string.snapshot_item_resp_overnight
    "spo2_min" -> R.string.snapshot_item_spo2_min
    "bmr_kcal" -> R.string.snapshot_item_bmr_kcal
    "floors_ascended_m" -> R.string.snapshot_item_floors_ascended_m
    "moderate_min" -> R.string.snapshot_item_moderate_min
    "vigorous_min" -> R.string.snapshot_item_vigorous_min
    "height_m" -> R.string.snapshot_item_height_m
    "ftp_w" -> R.string.snapshot_item_ftp_w
    "lactate_threshold_hr" -> R.string.snapshot_item_lactate_threshold_hr
    "race_5k_s" -> R.string.snapshot_item_race_5k_s
    "race_10k_s" -> R.string.snapshot_item_race_10k_s
    "race_half_s" -> R.string.snapshot_item_race_half_s
    "race_marathon_s" -> R.string.snapshot_item_race_marathon_s
    "stress_max" -> R.string.snapshot_item_stress_max
    "stress_low_min" -> R.string.snapshot_item_stress_low_min
    "stress_medium_min" -> R.string.snapshot_item_stress_medium_min
    "stress_high_min" -> R.string.snapshot_item_stress_high_min
    "body_battery_charged" -> R.string.snapshot_item_body_battery_charged
    "body_battery_drained" -> R.string.snapshot_item_body_battery_drained
    "sweat_loss_ml" -> R.string.snapshot_item_sweat_loss_ml
    else -> R.string.nav_daily_summary
}

/** The localized heading of a snapshot category. */
@StringRes
internal fun MetricSnapshotCategory.labelRes(): Int = when (this) {
    MetricSnapshotCategory.RECOVERY_STRAIN -> R.string.snapshot_category_recovery_strain
    MetricSnapshotCategory.SLEEP -> R.string.snapshot_category_sleep
    MetricSnapshotCategory.HEART -> R.string.snapshot_category_heart
    MetricSnapshotCategory.ACTIVITY -> R.string.snapshot_category_activity
    MetricSnapshotCategory.WORKOUTS -> R.string.snapshot_category_workouts
    MetricSnapshotCategory.BODY -> R.string.snapshot_category_body_composition
    MetricSnapshotCategory.FITNESS -> R.string.snapshot_category_fitness_scores
    MetricSnapshotCategory.WATCH -> R.string.snapshot_category_watch
    MetricSnapshotCategory.NUTRITION -> R.string.snapshot_category_nutrition
    MetricSnapshotCategory.HYDRATION -> R.string.snapshot_category_hydration
    MetricSnapshotCategory.MIND -> R.string.snapshot_category_mood_journal
    MetricSnapshotCategory.CYCLE -> R.string.snapshot_category_cycle
    MetricSnapshotCategory.LABS -> R.string.snapshot_category_lab_markers
}
