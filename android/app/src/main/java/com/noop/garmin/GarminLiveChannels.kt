package com.noop.garmin

// Realtime channel handles: register to start, close the handle to stop. Twin of
// Packages/GarminProtocol/Sources/GarminProtocol/GarminLiveChannels.swift, where the state machine is
// documented. Every log line matches the Swift text, and closes/registrations go out in service order, so
// the session oracle compares the two platforms frame for frame.

internal sealed class GarminLiveChannelEvent {
    data class Control(val request: GarminMultiLink.ControlRequest) : GarminLiveChannelEvent()
    data class Log(val rare: Boolean, val text: String) : GarminLiveChannelEvent()
    data class Opened(val service: GarminService, val handle: Int) : GarminLiveChannelEvent()
    data class Closed(val service: GarminService) : GarminLiveChannelEvent()
    data class Reading(val service: GarminService, val reading: GarminRealtimeReading) : GarminLiveChannelEvent()
    /** A payload on an undecoded capture channel, handle byte stripped. */
    class Raw(val service: GarminService, val payload: ByteArray) : GarminLiveChannelEvent()
    /** The watch refused a registration (status != 0). */
    data class Refused(val service: GarminService, val status: Int) : GarminLiveChannelEvent()
}

internal class GarminLiveChannelTable {

    sealed class State {
        object Registering : State()
        data class Open(val handle: Int) : State()
        data class Closing(val handle: Int) : State()
    }

    var desired: Set<GarminService> = emptySet()
        private set
    /** What the caller asked for, before filtering; re-filtered when capture is switched. */
    private var requested: Set<GarminService> = emptySet()
    var capture = false
        private set
    val states = HashMap<GarminService, State>()
    private val refused = HashSet<GarminService>()
    private val loggedUnsupported = HashSet<GarminService>()
    private val candidates = HashMap<String, MutableSet<String>>()

    val openServices: Set<GarminService> get() = states.filterValues { it is State.Open }.keys.toSet()

    fun setDesired(services: Set<GarminService>, active: Boolean): List<GarminLiveChannelEvent> {
        requested = services
        return refilter(active)
    }

    /** Forget every refusal, so the next [apply] asks again (a new GFDI session after `busy`). */
    fun clearRefusals() {
        refused.clear()
    }

    /** Allow (true) or forbid the undecoded capture channels. Forbidding closes any that are open. */
    fun setCapture(on: Boolean, active: Boolean): List<GarminLiveChannelEvent> {
        capture = on
        return refilter(active)
    }

    private fun refilter(active: Boolean): List<GarminLiveChannelEvent> {
        val events = mutableListOf<GarminLiveChannelEvent>()
        val wanted = HashSet<GarminService>()
        for (service in requested.sortedBy { it.raw }) {
            val undecoded = service in GarminRealtimeCapture.undecodedChannels
            if (service in GarminRealtime.DECODABLE_SERVICES || (capture && undecoded)) {
                wanted += service
            } else if (loggedUnsupported.add(service)) {
                events += GarminLiveChannelEvent.Log(
                    true,
                    if (undecoded) "live channel ${service.raw} is undecoded; opens only in capture mode"
                    else "live channel ${service.raw} is not supported; not opened",
                )
            }
        }
        refused.removeAll(desired - wanted)
        desired = wanted
        if (active) events += apply()
        return events
    }

    fun apply(): List<GarminLiveChannelEvent> {
        val events = mutableListOf<GarminLiveChannelEvent>()
        for (service in states.keys.sortedBy { it.raw }) {
            if (service in desired) continue
            val state = states[service]
            if (state is State.Open) {
                states[service] = State.Closing(state.handle)
                events += GarminLiveChannelEvent.Log(false, "live channel ${service.raw} closing handle ${hex8(state.handle)}")
                events += GarminLiveChannelEvent.Control(GarminMultiLink.ControlRequest.CloseHandle(service, state.handle))
            }
        }
        for (service in desired.sortedBy { it.raw }) {
            if (states[service] != null || service in refused) continue
            states[service] = State.Registering
            events += GarminLiveChannelEvent.Log(false, "live channel ${service.raw} registering")
            events += GarminLiveChannelEvent.Control(GarminMultiLink.ControlRequest.Register(service, false))
        }
        return events
    }

    fun registered(raw: Int, status: Int, handle: Int, reliable: Boolean): List<GarminLiveChannelEvent> {
        val service = GarminService.fromRaw(raw)
        if (service == null || states[service] != State.Registering) {
            if (status != 0 || service == null) {
                return listOf(GarminLiveChannelEvent.Log(false, "registration for service $raw status=$status ignored"))
            }
            if (states[service] == null) states[service] = State.Closing(handle)
            return listOf(
                GarminLiveChannelEvent.Log(true, "unrequested handle ${hex8(handle)} for service $raw; closing it"),
                GarminLiveChannelEvent.Control(GarminMultiLink.ControlRequest.CloseHandle(service, handle)),
            )
        }
        if (status != 0) {
            states.remove(service)
            refused += service
            return listOf(
                GarminLiveChannelEvent.Log(true, "live channel $raw refused status=$status; not retried until asked again"),
                GarminLiveChannelEvent.Refused(service, status),
            )
        }
        if (reliable || service !in desired) {
            states[service] = State.Closing(handle)
            if (reliable) refused += service
            return listOf(
                GarminLiveChannelEvent.Log(
                    reliable,
                    if (reliable) "live channel $raw came back reliable; closing handle ${hex8(handle)}"
                    else "live channel $raw no longer wanted; closing handle ${hex8(handle)}",
                ),
                GarminLiveChannelEvent.Control(GarminMultiLink.ControlRequest.CloseHandle(service, handle)),
            )
        }
        states[service] = State.Open(handle)
        return listOf(
            GarminLiveChannelEvent.Log(true, "live channel $raw open handle=${hex8(handle)}"),
            GarminLiveChannelEvent.Opened(service, handle),
        )
    }

    /** The watch answered CLOSE_HANDLE. Null when the handle is not a live channel. */
    fun handleClosed(handle: Int, status: Int): List<GarminLiveChannelEvent>? {
        val entry = states.entries.firstOrNull { it.value == State.Open(handle) || it.value == State.Closing(handle) } ?: return null
        val service = entry.key
        val byWatch = entry.value == State.Open(handle)
        states.remove(service)
        val events = mutableListOf(
            GarminLiveChannelEvent.Log(
                true,
                if (byWatch) "watch closed live channel ${service.raw} handle ${hex8(handle)} status=$status"
                else "live channel ${service.raw} closed handle=${hex8(handle)} status=$status",
            ),
            GarminLiveChannelEvent.Closed(service),
        )
        if (!byWatch && service in desired) {
            states[service] = State.Registering
            events += GarminLiveChannelEvent.Control(GarminMultiLink.ControlRequest.Register(service, false))
        }
        return events
    }

    fun allClosed(): List<GarminLiveChannelEvent> {
        val events = states.keys.sortedBy { it.raw }.filter { states[it] is State.Open }.map { GarminLiveChannelEvent.Closed(it) }
        states.clear()
        return events
    }

    fun receive(handle: Int, payload: ByteArray): List<GarminLiveChannelEvent>? {
        val service = states.entries.firstOrNull { it.value == State.Open(handle) || it.value == State.Closing(handle) }?.key
            ?: return null
        if (service in GarminRealtimeCapture.undecodedChannels) return listOf(GarminLiveChannelEvent.Raw(service, payload))
        val reading = GarminRealtime.decode(service, payload)
            ?: return listOf(GarminLiveChannelEvent.Log(true, "live channel ${service.raw} payload too short: ${garminHex(payload)}"))
        val events = mutableListOf<GarminLiveChannelEvent>(GarminLiveChannelEvent.Log(false, "live ${service.raw} [${garminHex(payload)}] ${GarminRealtime.describe(reading)}"))
        when (reading) {
            is GarminRealtimeReading.HeartRate -> {
                events += candidate("hr.type", reading.type.toString(), service)
                events += candidate("hr.trailing", garminHex(reading.trailing), service)
            }
            is GarminRealtimeReading.RrInterval -> reading.unknown?.let { events += candidate("rr.unknown", it.toString(), service) }
            else -> Unit
        }
        events += GarminLiveChannelEvent.Reading(service, reading)
        return events
    }

    private fun candidate(field: String, value: String, service: GarminService): List<GarminLiveChannelEvent> {
        val seen = candidates.getOrPut(field) { HashSet() }
        if (seen.size >= CANDIDATE_LOG_CAP || !seen.add(value)) return emptyList()
        return listOf(GarminLiveChannelEvent.Log(true, "candidate $field=$value (service ${service.raw}; undecoded, not used)"))
    }

    private fun hex8(v: Int): String {
        val s = Integer.toHexString(v and 0xFF)
        return "0x" + (if (s.length < 2) "0" else "") + s
    }

    companion object {
        const val CANDIDATE_LOG_CAP = 4
    }
}
