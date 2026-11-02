package com.noop.garmin

// Live readings -> NOOP's live state and stored rows. Twin of
// Packages/GarminProtocol/Sources/GarminProtocol/GarminLiveIntake.swift, where the rule is documented:
// plausible heart rate only, beat intervals passed through to the shared R-R intake unfiltered, Body Battery
// / respiration / SpO2 to the snapshot plus at most one deviceMetricSample row per kind per wall-clock
// minute, steps and floors to the snapshot only. Pinned by the realtime oracle (GarminRealtimeTest).

/** One deviceMetricSample row to write. [kind] is the stored text (DeviceMetricKind wire value). */
data class GarminLiveMetricWrite(val kind: String, val ts: Long, val value: Double)

/** The latest live values a Garmin watch reported. Null = not reported, or its channel is closed. */
data class GarminLiveSnapshot(
    val restingHeartRate: Int? = null,
    val bodyBattery: Int? = null,
    val respirationBpm: Int? = null,
    val spo2Pct: Int? = null,
    val steps: Long? = null,
    val stepGoal: Long? = null,
    val floorsAscended: Int? = null,
    val floorsDescended: Int? = null,
    val floorsTarget: Int? = null,
)

data class GarminLiveIntakeEffect(
    /** A readable heart rate to publish and store. */
    val heartRate: Int? = null,
    /** Beat intervals (ms) to publish and store. */
    val rr: List<Int> = emptyList(),
    val writes: List<GarminLiveMetricWrite> = emptyList(),
    val snapshotChanged: Boolean = false,
    /** The live heart rate must be blanked (its channel closed). */
    val clearHeartRate: Boolean = false,
)

class GarminLiveIntake {

    var snapshot = GarminLiveSnapshot()
        private set
    private val lastWrittenMinute = HashMap<String, Long>()

    fun ingest(reading: GarminRealtimeReading, unixSeconds: Long): GarminLiveIntakeEffect {
        val before = snapshot
        var heartRate: Int? = null
        var rr: List<Int> = emptyList()
        val writes = mutableListOf<GarminLiveMetricWrite>()
        when (reading) {
            is GarminRealtimeReading.HeartRate -> {
                heartRate = reading.bpm?.takeIf { it in PLAUSIBLE_HEART_RATE }
                snapshot = snapshot.copy(restingHeartRate = reading.resting?.takeIf { it in PLAUSIBLE_HEART_RATE })
            }
            is GarminRealtimeReading.RrInterval -> reading.ms?.let { rr = listOf(it) }
            is GarminRealtimeReading.Steps -> snapshot = snapshot.copy(steps = reading.total, stepGoal = reading.goal)
            is GarminRealtimeReading.Floors -> snapshot = snapshot.copy(
                floorsAscended = reading.ascended, floorsDescended = reading.descended, floorsTarget = reading.ascendTarget,
            )
            is GarminRealtimeReading.BodyBattery -> {
                snapshot = snapshot.copy(bodyBattery = reading.value)
                reading.value?.let { write(KIND_BODY_BATTERY, it, unixSeconds, writes) }
            }
            is GarminRealtimeReading.Respiration -> {
                val plausible = reading.breathsPerMinute?.takeIf { it in PLAUSIBLE_RESPIRATION }
                snapshot = snapshot.copy(respirationBpm = plausible)
                plausible?.let { write(KIND_RESPIRATION, it, unixSeconds, writes) }
            }
            is GarminRealtimeReading.Spo2 -> {
                snapshot = snapshot.copy(spo2Pct = reading.percent)
                reading.percent?.let { write(KIND_SPO2, it, unixSeconds, writes) }
            }
        }
        return GarminLiveIntakeEffect(heartRate, rr, writes, snapshot != before, false)
    }

    /** A channel closed: its values leave the snapshot. The throttle keeps its memory. */
    fun channelClosed(service: GarminService): GarminLiveIntakeEffect {
        val before = snapshot
        var clear = false
        snapshot = when (service) {
            GarminService.REALTIME_HEART_RATE -> { clear = true; snapshot.copy(restingHeartRate = null) }
            GarminService.REALTIME_STEPS -> snapshot.copy(steps = null, stepGoal = null)
            GarminService.REALTIME_FLOORS -> snapshot.copy(floorsAscended = null, floorsDescended = null, floorsTarget = null)
            GarminService.REALTIME_BODY_BATTERY -> snapshot.copy(bodyBattery = null)
            GarminService.REALTIME_RESPIRATION -> snapshot.copy(respirationBpm = null)
            GarminService.REALTIME_SPO2 -> snapshot.copy(spo2Pct = null)
            else -> snapshot
        }
        return GarminLiveIntakeEffect(snapshotChanged = snapshot != before, clearHeartRate = clear)
    }

    /** The link went down: every channel is closed. */
    fun linkDown(): GarminLiveIntakeEffect {
        val changed = snapshot != GarminLiveSnapshot()
        snapshot = GarminLiveSnapshot()
        return GarminLiveIntakeEffect(snapshotChanged = changed, clearHeartRate = true)
    }

    private fun write(kind: String, value: Int, ts: Long, writes: MutableList<GarminLiveMetricWrite>) {
        val minute = Math.floorDiv(ts, METRIC_WRITE_INTERVAL_SECONDS.toLong())
        if (lastWrittenMinute[kind] == minute) return
        lastWrittenMinute[kind] = minute
        writes += GarminLiveMetricWrite(kind, ts, value.toDouble())
    }

    companion object {
        const val METRIC_SOURCE = "garmin_live"
        const val METRIC_WRITE_INTERVAL_SECONDS = 60
        val PLAUSIBLE_HEART_RATE = 30..220
        /** Breaths per minute a person can have; 127 (the i8 maximum) and the like are not shown or stored. */
        val PLAUSIBLE_RESPIRATION = 4..60

        const val KIND_BODY_BATTERY = "body_battery"
        const val KIND_RESPIRATION = "respiration_bpm"
        const val KIND_SPO2 = "spo2_pct"
    }
}
