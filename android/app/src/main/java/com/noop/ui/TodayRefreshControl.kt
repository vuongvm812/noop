package com.noop.ui

/**
 * The Today Refresh button (#93): which device a tap syncs, and what the control beside the wordmark
 * shows. Pure, so the decision is pinned by JVM tests rather than read off a strap.
 *
 * Twin of Swift `TodayRefreshControl` (Strand/Screens/TodayRefreshControl.swift). The resolver is
 * oracle-checked: `TodayRefreshControlTest.oracle_matchesSwiftTwin` holds the Swift twin's stdout over
 * the whole input space.
 */
enum class TodayRefreshSource { WHOOP, GARMIN, HUAWEI, OURA, NONE }

/** What the leading wordmark slot holds. */
enum class TodayRefreshState {
    /** Nothing: the active device has no sync to offer (a generic HR strap, an Apple Watch). */
    HIDDEN,

    /** The Rescan disc (#2169), unchanged: the device is away and Today can start a connect. */
    RESCAN,

    /** Refresh, tappable. */
    READY,

    /** Refresh, spinning and not tappable: a sync (or, for a ring, a reconnect) is running. */
    SYNCING,

    /** Refresh, greyed: a device that can sync is active but cannot take a request right now. */
    UNAVAILABLE,
}

/** One source's link, reduced to the three facts the resolver reads. */
data class TodayRefreshLink(val linkUp: Boolean, val canSync: Boolean, val syncing: Boolean)

object TodayRefreshControl {

    /**
     * The device a Refresh tap reaches. WHOOP first, matching the registry's WHOOP-first default for an
     * unresolvable active row; Garmin and Huawei by their live source existing, since the coordinator
     * builds exactly one and only for the active device; Oura by the registry verdict.
     */
    fun source(
        activeIsWhoop: Boolean,
        garminLive: Boolean,
        huaweiLive: Boolean,
        ouraActive: Boolean,
    ): TodayRefreshSource = when {
        activeIsWhoop -> TodayRefreshSource.WHOOP
        garminLive -> TodayRefreshSource.GARMIN
        huaweiLive -> TodayRefreshSource.HUAWEI
        ouraActive -> TodayRefreshSource.OURA
        else -> TodayRefreshSource.NONE
    }

    /**
     * [rescanOffered] is whether the slot would hold the Rescan disc (Android: the WHOOP link is down).
     * Apple passes false: it has no Rescan disc there, so a device that is away shows a greyed Refresh.
     */
    fun resolve(
        source: TodayRefreshSource,
        link: TodayRefreshLink,
        rescanOffered: Boolean,
    ): TodayRefreshState {
        if (source != TodayRefreshSource.NONE && link.linkUp) {
            return when {
                link.syncing -> TodayRefreshState.SYNCING
                link.canSync -> TodayRefreshState.READY
                else -> TodayRefreshState.UNAVAILABLE
            }
        }
        return when {
            rescanOffered -> TodayRefreshState.RESCAN
            source == TodayRefreshSource.NONE -> TodayRefreshState.HIDDEN
            else -> TodayRefreshState.UNAVAILABLE
        }
    }

    /** WHOOP: the pull-to-sync gate, so the button is offered exactly when `syncNow()` would run. */
    fun whoop(connected: Boolean, bonded: Boolean, backfilling: Boolean, historyReady: Boolean) =
        TodayRefreshLink(
            linkUp = connected,
            canSync = todayPullToSyncEnabled(connected, bonded, backfilling, historyReady),
            syncing = backfilling,
        )

    /** Garmin: up once READY, or BUSY (another app holds GFDI; "Sync now" asks for it again). */
    fun garmin(linkReady: Boolean, syncing: Boolean) =
        TodayRefreshLink(linkUp = linkReady, canSync = linkReady && !syncing, syncing = syncing)

    /** Huawei: up once READY. */
    fun huawei(ready: Boolean, syncing: Boolean) =
        TodayRefreshLink(linkUp = ready, canSync = ready && !syncing, syncing = syncing)

    /** Oura has no history sync to request; Refresh reconnects the ring, which spins until it is
     *  authenticated again. */
    fun oura(connecting: Boolean, authenticated: Boolean) =
        TodayRefreshLink(
            linkUp = connecting || authenticated,
            canSync = authenticated,
            syncing = connecting && !authenticated,
        )
}
