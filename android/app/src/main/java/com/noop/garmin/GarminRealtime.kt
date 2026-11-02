package com.noop.garmin

// Realtime multi-link channel payloads. Twin of
// Packages/GarminProtocol/Sources/GarminProtocol/GarminRealtime.swift, where the wire layouts are documented.
// Unknown sentinels decode to null, never to a number; the heart-rate type byte, the bytes after the resting
// value and the u32 after each beat interval are raw candidates for the log and never feed a value.

/** One decoded realtime notification. */
sealed class GarminRealtimeReading {
    /** [bpm] null when the watch sends 0; [resting] null when 0 or absent. [type] / [trailing] are candidates. */
    class HeartRate(val bpm: Int?, val resting: Int?, val type: Int, val trailing: ByteArray) : GarminRealtimeReading() {
        override fun equals(other: Any?): Boolean = other is HeartRate && other.bpm == bpm && other.resting == resting &&
            other.type == type && other.trailing.contentEquals(trailing)
        override fun hashCode(): Int = ((bpm ?: -1) * 31 + (resting ?: -1)) * 31 + type
    }
    /** One beat interval in ms, null for 0. [unknown] is the trailing u32 candidate, null when absent. */
    data class RrInterval(val ms: Int?, val unknown: Long?) : GarminRealtimeReading()
    data class Steps(val total: Long, val goal: Long) : GarminRealtimeReading()
    data class Floors(val ascended: Int, val descended: Int, val ascendTarget: Int) : GarminRealtimeReading()
    /** [percent] null for the unknown codes and anything outside 1..100; [garminTime] null whenever it is. */
    data class Spo2(val percent: Int?, val garminTime: Long?) : GarminRealtimeReading()
    /** 0..100, null for 0x7F and anything above 100. */
    data class BodyBattery(val value: Int?) : GarminRealtimeReading()
    /** Breaths per minute, null for zero or negative. */
    data class Respiration(val breathsPerMinute: Int?) : GarminRealtimeReading()
}

object GarminRealtime {

    /** The realtime services this layer can decode and therefore may open. */
    val DECODABLE_SERVICES: Set<GarminService> = setOf(
        GarminService.REALTIME_HEART_RATE, GarminService.REALTIME_STEPS, GarminService.REALTIME_FLOORS,
        GarminService.REALTIME_HRV, GarminService.REALTIME_SPO2, GarminService.REALTIME_BODY_BATTERY,
        GarminService.REALTIME_RESPIRATION,
    )

    /** Decode one payload (handle byte stripped). Null = too short, or a service this layer does not decode. */
    fun decode(service: GarminService, payload: ByteArray): GarminRealtimeReading? {
        val r = GarminByteReader(payload)
        return when (service) {
            GarminService.REALTIME_HEART_RATE -> {
                val type = r.u8() ?: return null
                val bpm = r.u8() ?: return null
                val resting = r.u8() ?: 0
                GarminRealtimeReading.HeartRate(bpm.takeIf { it != 0 }, resting.takeIf { it != 0 }, type, r.rest())
            }
            GarminService.REALTIME_HRV -> {
                val rr = r.u16() ?: return null
                GarminRealtimeReading.RrInterval(rr.takeIf { it != 0 }, r.u32())
            }
            GarminService.REALTIME_STEPS -> {
                val total = r.u32() ?: return null
                val goal = r.u32() ?: return null
                GarminRealtimeReading.Steps(total, goal)
            }
            GarminService.REALTIME_FLOORS -> {
                val up = r.u16() ?: return null
                val down = r.u16() ?: return null
                val target = r.u16() ?: return null
                GarminRealtimeReading.Floors(up, down, target)
            }
            GarminService.REALTIME_SPO2 -> {
                val raw = r.u8() ?: return null
                val percent = raw.toByte().toInt()
                if (percent !in 1..100) GarminRealtimeReading.Spo2(null, null)
                else GarminRealtimeReading.Spo2(percent, r.u32())
            }
            GarminService.REALTIME_BODY_BATTERY -> {
                val raw = r.u8() ?: return null
                GarminRealtimeReading.BodyBattery(raw.takeIf { it <= 100 })
            }
            GarminService.REALTIME_RESPIRATION -> {
                val raw = r.u8() ?: return null
                GarminRealtimeReading.Respiration(raw.toByte().toInt().takeIf { it > 0 })
            }
            else -> null
        }
    }

    /** One log / oracle line for a reading. Same text on both platforms. */
    fun describe(reading: GarminRealtimeReading): String {
        fun opt(v: Any?): String = v?.toString() ?: "-"
        return when (reading) {
            is GarminRealtimeReading.HeartRate ->
                "hr bpm=${opt(reading.bpm)} resting=${opt(reading.resting)} type=${reading.type} trailing=${garminHex(reading.trailing)}"
            is GarminRealtimeReading.RrInterval -> "rr ms=${opt(reading.ms)} unknown=${opt(reading.unknown)}"
            is GarminRealtimeReading.Steps -> "steps total=${reading.total} goal=${reading.goal}"
            is GarminRealtimeReading.Floors -> "floors up=${reading.ascended} down=${reading.descended} target=${reading.ascendTarget}"
            is GarminRealtimeReading.Spo2 -> "spo2 pct=${opt(reading.percent)} time=${opt(reading.garminTime)}"
            is GarminRealtimeReading.BodyBattery -> "body-battery ${opt(reading.value)}"
            is GarminRealtimeReading.Respiration -> "respiration ${opt(reading.breathsPerMinute)}"
        }
    }
}
