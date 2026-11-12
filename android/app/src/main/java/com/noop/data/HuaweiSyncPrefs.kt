package com.noop.data

import android.content.Context
import android.content.SharedPreferences
import com.noop.huawei.HuaweiDataType
import com.noop.huawei.HuaweiStepCarry
import com.noop.huawei.HuaweiSyncCursor

/**
 * Huawei history-sync state in the `noop_huawei_sync` SharedPreferences, every key prefixed by the band's
 * device id (`<deviceId>|…`), so a registry re-key or data wipe moves or drops one band's state alone
 * ([moveDeviceState], [dropDeviceState], wired as [DeviceRegistry]'s hooks). Kotlin-only; Swift twin deferred (fork).
 */
class HuaweiSyncPrefs(private val prefs: SharedPreferences) : HuaweiSyncCursor {

    private fun key(deviceId: String, name: String) = "$deviceId|$name"

    private fun long(deviceId: String, name: String): Long? =
        key(deviceId, name).let { if (prefs.contains(it)) prefs.getLong(it, 0) else null }

    private fun putLong(deviceId: String, name: String, v: Long) = prefs.edit().putLong(key(deviceId, name), v).apply()

    override fun lastSync(deviceId: String, type: HuaweiDataType): Long? = long(deviceId, "last.${type.key}")
    override fun setLastSync(deviceId: String, type: HuaweiDataType, ts: Long) = putLong(deviceId, "last.${type.key}", ts)
    override fun lastWorkoutEnd(deviceId: String): Long? = long(deviceId, "workoutEnd")
    override fun setLastWorkoutEnd(deviceId: String, ts: Long) = putLong(deviceId, "workoutEnd", ts)
    override fun dictionaryCursorMs(deviceId: String, dictClass: Int): Long? = long(deviceId, "dict.$dictClass")
    override fun setDictionaryCursorMs(deviceId: String, dictClass: Int, ms: Long) = putLong(deviceId, "dict.$dictClass", ms)
    override fun switchesEnabled(deviceId: String): Boolean = prefs.getBoolean(key(deviceId, "switches"), false)
    override fun setSwitchesEnabled(deviceId: String) = prefs.edit().putBoolean(key(deviceId, "switches"), true).apply()
    override fun lastSyncAt(deviceId: String): Long? = long(deviceId, "syncAt")
    override fun emptyStreak(deviceId: String): Int = prefs.getInt(key(deviceId, "emptyStreak"), 0)

    override fun dictUnavailableUntil(deviceId: String): Long? = long(deviceId, "dictUnavailableUntil")
    override fun setDictUnavailableUntil(deviceId: String, ts: Long) = putLong(deviceId, "dictUnavailableUntil", ts)

    override fun stepCarry(deviceId: String): HuaweiStepCarry? = HuaweiStepCarry.decode(prefs.getString(key(deviceId, "stepCarry"), null))
    override fun setStepCarry(deviceId: String, carry: HuaweiStepCarry) =
        prefs.edit().putString(key(deviceId, "stepCarry"), carry.encode()).apply()
    override fun lastNightEnd(deviceId: String, type: HuaweiDataType): Long? = long(deviceId, "nightEnd.${type.key}")
    override fun setLastNightEnd(deviceId: String, type: HuaweiDataType, ts: Long) = putLong(deviceId, "nightEnd.${type.key}", ts)

    override fun recordSyncOutcome(deviceId: String, at: Long, empty: Boolean) {
        prefs.edit()
            .putLong(key(deviceId, "syncAt"), at)
            .putInt(key(deviceId, "emptyStreak"), if (empty) emptyStreak(deviceId) + 1 else 0)
            .apply()
    }

    /** When a sync of [deviceId] last finished normally (the device panel's "Last sync"); null before the first. */
    fun completedAt(deviceId: String): Long? = long(deviceId, "completedAt")

    fun setCompletedAt(deviceId: String, at: Long) = putLong(deviceId, "completedAt", at)

    /** Moves every key of [from] onto [to] (replacing [to]'s), so a re-keyed band keeps its watermarks. */
    fun moveDeviceState(from: String, to: String) {
        if (from == to) return
        val moved = prefs.all.filterKeys { it.startsWith("$from|") }
        if (moved.isEmpty()) return
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$to|") }.forEach { edit.remove(it) }
        for ((k, v) in moved) {
            val nk = to + k.removePrefix(from)
            when (v) {
                is Long -> edit.putLong(nk, v)
                is Int -> edit.putInt(nk, v)
                is Boolean -> edit.putBoolean(nk, v)
                is String -> edit.putString(nk, v)
            }
            edit.remove(k)
        }
        edit.apply()
    }

    /** Drops every key of [id], so a forgotten or wiped band starts again from the first backfill. */
    fun dropDeviceState(id: String) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$id|") }.forEach { edit.remove(it) }
        edit.apply()
    }

    companion object {
        const val PREFS_NAME = "noop_huawei_sync"

        fun of(context: Context) = HuaweiSyncPrefs(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
