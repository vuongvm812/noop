package com.noop.garmin

// The connected-watch session: ML startup and the minimal GFDI handshake. Twin of
// Packages/GarminProtocol/Sources/GarminProtocol/GarminSession.swift, where the sequence and every reply rule
// are documented. Pure: no Android Bluetooth, no timers, no clock. Both platforms replay the same traces and
// compare the printed transcript (GarminSessionTest), so every log line here matches the Swift text exactly.

/** The clock values a reply may need, supplied by the platform on every call. */
data class GarminClock(
    val nowMs: Long,
    val unixSeconds: Long,
    val utcOffsetSeconds: Int,
    /** Unix seconds of the coming time-zone transition, null when the zone has none. */
    val nextTransitionUnix: Long? = null,
    /** Unix seconds of the transition after that one, null when none. */
    val followingTransitionUnix: Long? = null,
)

data class GarminSessionConfig(
    val host: GarminHostIdentity,
    val capabilities: Set<GarminCapability> = MINIMAL_CAPABILITIES,
    /** True only for the session that first pairs this watch with NOOP (PAIR_COMPLETE + SETUP_WIZARD_COMPLETE). */
    val firstPairing: Boolean,
    /** Send HOST_DID_ENTER_FOREGROUND / BACKGROUND (`GarminRadioPolicy.SENDS_HOST_LIFECYCLE_EVENTS`). */
    val sendsHostLifecycleEvents: Boolean,
    val clientId: Long = GarminMultiLink.DEFAULT_CLIENT_ID,
    /** From the first write until the watch's capabilities arrive. */
    val handshakeTimeoutMs: Long = 30_000,
) {
    companion object {
        /** File sync, device-initiated sync, time requests, multi-link: nothing that makes the watch send
         *  notification, weather, music or calendar traffic. Add a bit back only with the model named. */
        val MINIMAL_CAPABILITIES: Set<GarminCapability> = GarminCapability.entries.toSet()

        /** NOOP's identity in the 5024 reply: NOOP and the host platform, never Garmin Connect. */
        fun noopHost(platform: String, softwareVersion: Int): GarminHostIdentity = GarminHostIdentity(
            protocolVersion = 150, productNumber = 0xFFFF, unitNumber = 0xFFFF_FFFFL,
            softwareVersion = softwareVersion, maxPacketSize = 0xFFFF,
            bluetoothName = "NOOP", manufacturer = "NOOP", model = platform,
        )
    }
}

sealed class GarminSessionPhase(val label: String) {
    object Idle : GarminSessionPhase("idle")
    object ClosingAll : GarminSessionPhase("closing-all")
    object Registering : GarminSessionPhase("registering")
    object AwaitingDeviceInformation : GarminSessionPhase("awaiting-device-information")
    object Handshaking : GarminSessionPhase("handshaking")
    object Ready : GarminSessionPhase("ready")
    data class Failed(val reason: String) : GarminSessionPhase("failed($reason)")
}

/** One diagnostic line: [rare] lines are always logged, the rest only while a Test Centre domain is active. */
data class GarminSessionLog(val rare: Boolean, val text: String)

sealed class GarminSessionOutput {
    /** Bytes for the transport's write characteristic, in order. */
    class Write(val bytes: ByteArray) : GarminSessionOutput()
    data class Log(val line: GarminSessionLog) : GarminSessionOutput()
    data class Phase(val phase: GarminSessionPhase) : GarminSessionOutput()
    data class Battery(val percent: Int) : GarminSessionOutput()
    class Watch(val info: GarminDeviceInformation) : GarminSessionOutput()
    object PairingEventsSent : GarminSessionOutput()
    /** The watch announced new data; the platform asks `GarminRadioPolicy` and calls [GarminSession.startSync]. */
    data class SyncAnnounced(val announcement: GarminSyncAnnouncement) : GarminSessionOutput()
    /** One wanted file downloaded and checked. */
    class SyncFile(val file: GarminDownloadedFile) : GarminSessionOutput()
    /** The sync ended (completed, failed or interrupted). */
    data class SyncFinished(val summary: GarminSyncSummary) : GarminSessionOutput()
    /** A history transfer started (true) or ended (false): CONNECTION_PRIORITY_HIGH only in between. */
    data class TransferActive(val active: Boolean) : GarminSessionOutput()
}

/** How the watch said it has new data. */
sealed class GarminSyncAnnouncement(val label: String) {
    /** 5037 SYNCHRONIZATION: the only announcement answered with FILTER before the directory request. */
    data class Synchronization(val type: Int, val fileTypeMask: Long) :
        GarminSyncAnnouncement("synchronization type=$type mask=0x${java.lang.Long.toHexString(fileTypeMask)}")
    /** 5009 FILE_AVAILABLE for one directory entry. */
    data class FileAvailable(val entry: GarminDirectoryEntry) :
        GarminSyncAnnouncement("file available ${GarminSyncFileKey(entry).label}")
    /** FileAccess ItemAddedNotification. */
    data class ItemAdded(val count: Int) : GarminSyncAnnouncement("item added count=$count")
    /** FileAccess SyncButtonNotification. */
    object SyncButton : GarminSyncAnnouncement("sync button")

    val expectsFilter: Boolean get() = this is Synchronization
}

class GarminSession(val config: GarminSessionConfig, val transport: GarminTransportKind, mtu: Int) {

    var phase: GarminSessionPhase = GarminSessionPhase.Idle
        private set
    var watch: GarminDeviceInformation? = null
        private set
    var gfdiHandle: Int? = null
        private set
    var messagesIn = 0
        private set
    var framesDropped = 0
        private set

    private var mtu = maxOf(23, mtu)
    private var mlr: GarminMlrSession? = null
    private val decoder = GarminCobsStreamDecoder()
    private var startedAtMs: Long? = null
    private var handshakeDeadline: Long? = null
    private var foreground = true
    private var lifecycleSent: Boolean? = null
    private var pairingSent = false
    private val loggedUnknownTypes = HashSet<Int>()
    private val sync = GarminHistorySync()
    private val out = ArrayList<GarminSessionOutput>()

    /** The earliest moment [tick] has work: the handshake deadline, an MLR timer, or the sync's stall deadline. */
    val nextDeadlineMs: Long? get() = listOfNotNull(handshakeDeadline, mlr?.nextDeadlineMs, sync.deadlineMs).minOrNull()

    /** A history sync is running. */
    val isSyncing: Boolean get() = sync.isActive

    /** What an interrupted file transfer could continue from (kept by the platform across a reconnect). */
    val syncResumePoint: GarminSyncResume? get() = sync.resumePoint

    /** Start one batched, read-only history sync. Ignored (logged) before the handshake or while one runs. */
    fun startSync(request: GarminSyncRequest, clock: GarminClock): List<GarminSessionOutput> {
        if (phase != GarminSessionPhase.Ready) {
            note(true, "sync not started: session is ${phase.label}")
            return flush()
        }
        perform(sync.start(request, clock.nowMs), clock)
        return flush()
    }

    fun setMtu(mtu: Int) {
        this.mtu = maxOf(23, mtu)
        mlr?.setMtu(this.mtu)
    }

    fun start(clock: GarminClock): List<GarminSessionOutput> {
        startedAtMs = clock.nowMs
        handshakeDeadline = clock.nowMs + config.handshakeTimeoutMs
        note(true, "session start transport=${transport.label} mtu=$mtu firstPairing=${config.firstPairing}")
        if (transport == GarminTransportKind.MULTI_LINK_V2) {
            setPhase(GarminSessionPhase.ClosingAll)
            control(GarminMultiLink.ControlRequest.CloseAll)
        } else {
            setPhase(GarminSessionPhase.AwaitingDeviceInformation)
        }
        return flush()
    }

    fun setForeground(isForeground: Boolean, clock: GarminClock): List<GarminSessionOutput> {
        foreground = isForeground
        sendLifecycleIfNeeded(clock)
        return flush()
    }

    fun tick(clock: GarminClock): List<GarminSessionOutput> {
        perform(sync.tick(clock.nowMs), clock)
        val deadline = handshakeDeadline
        if (deadline != null && clock.nowMs >= deadline) {
            handshakeDeadline = null
            fail("handshake timed out in ${phase.label} after ${clock.nowMs - (startedAtMs ?: clock.nowMs)} ms")
        }
        mlr?.let { m -> for (p in m.tick(clock.nowMs)) out.add(GarminSessionOutput.Write(p)) }
        return flush()
    }

    fun receive(notification: ByteArray, clock: GarminClock): List<GarminSessionOutput> {
        if (phase is GarminSessionPhase.Failed) return flush()
        if (transport != GarminTransportKind.MULTI_LINK_V2) {
            feed(notification, clock)
            return flush()
        }
        val handle = gfdiHandle
        val reliable = if (mlr != null && handle != null) setOf(handle) else emptySet()
        when (val route = GarminMultiLink.route(notification, reliable)) {
            is GarminMultiLink.Route.Empty -> Unit
            is GarminMultiLink.Route.Control -> handleControl(route.packet, clock)
            is GarminMultiLink.Route.Reliable -> mlr?.let { m ->
                val received = m.receive(route.packet, clock.nowMs)
                for (p in received.outbound) out.add(GarminSessionOutput.Write(p))
                received.data?.let { feed(it, clock) }
            }
            is GarminMultiLink.Route.Plain ->
                if (route.handle == gfdiHandle) feed(route.payload, clock)
                else if (routeSync(sync.handlePipeData(route.handle, route.payload, clock.nowMs), clock)) Unit
                else note(false, "ignored ${route.payload.size} bytes on unregistered handle ${hex8(route.handle)}")
        }
        return flush()
    }

    // ML control

    private fun handleControl(packet: ByteArray, clock: GarminClock) {
        val response = GarminMultiLink.parseControl(packet, config.clientId)
        if (response == null) {
            note(false, "ignored control frame ${garminHex(packet)}")
            return
        }
        when (response) {
            is GarminMultiLink.ControlResponse.AllClosed -> {
                if (phase == GarminSessionPhase.ClosingAll) {
                    note(false, "all handles closed")
                } else {
                    note(true, "watch closed all handles; re-registering GFDI")
                    dropGfdi(clock)
                }
                setPhase(GarminSessionPhase.Registering)
                control(GarminMultiLink.ControlRequest.Register(GarminService.GFDI, false))
            }
            is GarminMultiLink.ControlResponse.HandleClosed -> {
                if (routeSync(sync.handleClosed(response.service, response.handle, clock.nowMs), clock)) {
                    note(false, "file-transfer handle ${hex8(response.handle)} closed status=${response.status}")
                } else if (response.handle == gfdiHandle && response.service == GarminService.GFDI.raw) {
                    note(true, "watch closed GFDI handle ${hex8(response.handle)} status=${response.status}; re-registering")
                    dropGfdi(clock)
                    setPhase(GarminSessionPhase.Registering)
                    control(GarminMultiLink.ControlRequest.Register(GarminService.GFDI, false))
                } else {
                    note(false, "handle ${hex8(response.handle)} service=${response.service} closed status=${response.status}")
                }
            }
            is GarminMultiLink.ControlResponse.Registered -> {
                if (routeSync(sync.handleRegistered(response.service, response.status, response.handle, clock.nowMs), clock)) return
                if (response.service != GarminService.GFDI.raw || phase != GarminSessionPhase.Registering) {
                    note(false, "registration for service ${response.service} status=${response.status} ignored")
                    return
                }
                if (response.status != 0) {
                    note(true, "GFDI registration refused status=${response.status}")
                    fail("registration refused status=${response.status}")
                    return
                }
                gfdiHandle = response.handle
                if (response.reliable) mlr = GarminMlrSession(response.handle, mtu)
                note(true, "GFDI registered handle=${hex8(response.handle)} reliable=${response.reliable} status=0")
                setPhase(GarminSessionPhase.AwaitingDeviceInformation)
            }
        }
    }

    private fun dropGfdi(clock: GarminClock) {
        perform(sync.interrupt("GFDI handle closed by the watch"), clock)
        gfdiHandle = null
        mlr = null
        decoder.reset()
        lifecycleSent = null
        startedAtMs = clock.nowMs
        handshakeDeadline = clock.nowMs + config.handshakeTimeoutMs
    }

    // GFDI

    private fun feed(bytes: ByteArray, clock: GarminClock) {
        val droppedBefore = decoder.droppedFrames
        val messages = decoder.feed(bytes)
        if (decoder.droppedFrames > droppedBefore) {
            framesDropped += decoder.droppedFrames - droppedBefore
            note(true, "dropped ${decoder.droppedFrames - droppedBefore} malformed COBS frame(s)")
        }
        for (message in messages) {
            when (val result = GarminGfdi.decode(message)) {
                is GarminGfdi.Result.Failure -> {
                    framesDropped += 1
                    note(true, "dropped GFDI frame: ${describe(result.error)}")
                }
                is GarminGfdi.Result.Success -> {
                    messagesIn += 1
                    handle(GarminInbound.decode(result.frame), result.frame.type, clock)
                }
            }
        }
    }

    private fun handle(message: GarminInbound, type: Int, clock: GarminClock) {
        when (message) {
            is GarminInbound.DeviceInformation -> {
                val info = message.info
                if (watch == null) {
                    note(true, "watch ${info.bluetoothName} model=${info.deviceModel} product=${info.productNumber} " +
                        "fw=${firmware(info.softwareVersion)} protocol=${info.protocolVersion} maxPacket=${info.maxPacketSize}")
                    out.add(GarminSessionOutput.Watch(info))
                } else {
                    note(false, "5024 again (the watch re-sends until answered)")
                }
                watch = info
                send(GarminOutbound.Status(type, GarminStatus.ACK), clock)
                send(GarminOutbound.DeviceInformationReply(config.host, if (info.protocolVersion / 100 == 1) 1 else 0), clock)
                if (phase == GarminSessionPhase.AwaitingDeviceInformation) setPhase(GarminSessionPhase.Handshaking)
            }
            is GarminInbound.Configuration -> {
                val bits = GarminCapabilityBitmap.indices(message.capabilities)
                note(true, "watch capabilities: ${bits.size} bits; NOOP advertises " +
                    config.capabilities.map { it.bit }.sorted().joinToString(","))
                send(GarminOutbound.Status(type, GarminStatus.ACK), clock)
                send(GarminOutbound.Configuration(config.capabilities), clock)
                if (phase != GarminSessionPhase.Ready) completeHandshake(clock)
            }
            is GarminInbound.AuthNegotiation -> {
                note(false, "5101 auth negotiation echoed unknown=${message.unknown} flags=${message.flags}")
                send(GarminOutbound.AuthNegotiationReply(message.unknown, message.flags), clock)
            }
            is GarminInbound.CurrentTimeRequest -> {
                note(false, "5052 time request ref=${message.referenceId} -> unix=${clock.unixSeconds} offset=${clock.utcOffsetSeconds}")
                send(GarminOutbound.CurrentTimeReply(
                    referenceId = message.referenceId,
                    garminTime = GarminGfdi.garminTime(clock.unixSeconds),
                    utcOffsetSeconds = clock.utcOffsetSeconds,
                    followingTransition = clock.followingTransitionUnix?.let { GarminGfdi.garminTime(it) } ?: 0L,
                    nextTransition = clock.nextTransitionUnix?.let { GarminGfdi.garminTime(it) } ?: 0L,
                ), clock)
            }
            is GarminInbound.BatteryStatus -> {
                note(false, "5023 battery ${message.percent}%")
                send(GarminOutbound.Status(type, GarminStatus.ACK), clock)
                if (message.percent <= 100) out.add(GarminSessionOutput.Battery(message.percent))
            }
            is GarminInbound.Synchronization -> {
                note(false, "$type watch has new data")
                send(GarminOutbound.Status(type, GarminStatus.ACK), clock)
                out.add(GarminSessionOutput.SyncAnnounced(GarminSyncAnnouncement.Synchronization(message.type, message.fileTypeMask)))
            }
            is GarminInbound.FileAvailable -> {
                note(false, "$type watch has new data")
                send(GarminOutbound.Status(type, GarminStatus.ACK), clock)
                out.add(GarminSessionOutput.SyncAnnounced(GarminSyncAnnouncement.FileAvailable(message.entry)))
            }
            is GarminInbound.CompressedFileData -> {
                if (routeSync(sync.handleCompressedChunk(message.sequence, message.flags, message.checkpoint,
                        message.checkpointCrc, message.data, clock.nowMs), clock)) return
                if (loggedUnknownTypes.add(type)) note(true, "unsolicited compressed file data ($type)")
                send(GarminOutbound.Status(type, GarminStatus.UNSUPPORTED), clock)
            }
            is GarminInbound.FileTransferData -> {
                if (routeSync(sync.handleChunk(message.flags, message.runningCrc, message.dataOffset, message.data,
                        clock.nowMs), clock)) return
                if (loggedUnknownTypes.add(type)) note(true, "unsolicited file data ($type)")
                send(GarminOutbound.Status(type, GarminStatus.UNSUPPORTED), clock)
            }
            is GarminInbound.ProtobufRequest -> {
                val chunk = message.chunk
                val announcement = if (chunk.isComplete) fileAccessAnnouncement(chunk.data) else null
                if (announcement != null) {
                    // A FileAccess notice from the watch: keep it (no reply payload is due) and announce it.
                    note(false, "watch FileAccess ${announcement.label} id=${chunk.requestId}")
                    send(GarminOutbound.ProtobufStatus(GarminProtobufDirection.REQUEST, chunk.requestId, chunk.dataOffset,
                        discard = false, code = 0), clock)
                    out.add(GarminSessionOutput.SyncAnnounced(announcement))
                    return
                }
                // Other FileAccess notices (a Forerunner 165 sends one after every pull) are declined like
                // any other request; only the per-connect detail log names them.
                note(firstFieldNumber(chunk.data) != GarminFileAccessRequest.SMART_FILE_ACCESS_FIELD,
                    "refused watch request ${smartServiceName(chunk)} id=${chunk.requestId}")
                send(GarminOutbound.ProtobufStatus(GarminProtobufDirection.REQUEST, chunk.requestId, chunk.dataOffset,
                    discard = true, code = UNKNOWN_REQUEST_ID), clock)
            }
            is GarminInbound.ProtobufResponse -> {
                val chunk = message.chunk
                if (routeSync(sync.handleChunkedResponse(chunk, clock.nowMs), clock)) return
                note(false, "discarded unsolicited protobuf response id=${chunk.requestId}")
                send(GarminOutbound.ProtobufStatus(GarminProtobufDirection.RESPONSE, chunk.requestId, chunk.dataOffset,
                    discard = true, code = UNKNOWN_REQUEST_ID), clock)
            }
            is GarminInbound.Status ->
                if (routeSync(sync.handleStatus(message.originalType, message.status, message.detail, clock.nowMs), clock)) Unit
                else if (message.status == GarminStatus.ACK.raw) note(false, "watch acked ${message.originalType}")
                else note(true, "watch answered ${message.originalType} with status ${message.status}")
            is GarminInbound.Unknown -> {
                if (loggedUnknownTypes.add(type)) {
                    note(true, REFUSED_TYPE_NAMES[type]?.let { "refused $it ($type)" } ?: "unsupported message $type")
                }
                send(GarminOutbound.Status(type, GarminStatus.UNSUPPORTED), clock)
            }
        }
    }

    private fun completeHandshake(clock: GarminClock) {
        send(GarminOutbound.SystemEvent(GarminSafeSystemEvent.SYNC_READY, 0), clock)
        if (config.firstPairing && !pairingSent) {
            send(GarminOutbound.SystemEvent(GarminSafeSystemEvent.PAIR_COMPLETE, 0), clock)
            send(GarminOutbound.SystemEvent(GarminSafeSystemEvent.SETUP_WIZARD_COMPLETE, 0), clock)
            pairingSent = true
            out.add(GarminSessionOutput.PairingEventsSent)
        }
        handshakeDeadline = null
        setPhase(GarminSessionPhase.Ready)
        note(true, "handshake complete in ${clock.nowMs - (startedAtMs ?: clock.nowMs)} ms")
        sendLifecycleIfNeeded(clock)
    }

    private fun sendLifecycleIfNeeded(clock: GarminClock) {
        if (!config.sendsHostLifecycleEvents || phase != GarminSessionPhase.Ready || lifecycleSent == foreground) return
        lifecycleSent = foreground
        val event = if (foreground) GarminSafeSystemEvent.HOST_DID_ENTER_FOREGROUND else GarminSafeSystemEvent.HOST_DID_ENTER_BACKGROUND
        send(GarminOutbound.SystemEvent(event, 0), clock)
    }

    // History sync plumbing

    /** Perform a routed sync result; true when the input belonged to the sync. */
    private fun routeSync(routed: Pair<Boolean, List<GarminSyncAction>>, clock: GarminClock): Boolean {
        perform(routed.second, clock)
        return routed.first
    }

    private fun perform(actions: List<GarminSyncAction>, clock: GarminClock?) {
        for (action in actions) {
            when (action) {
                is GarminSyncAction.Send -> clock?.let { send(action.message, it) }
                is GarminSyncAction.Control -> control(action.request)
                is GarminSyncAction.Pipe -> out.add(GarminSessionOutput.Write(action.request.packet(action.mlHandle)))
                is GarminSyncAction.Log -> note(action.rare, action.text)
                is GarminSyncAction.File -> out.add(GarminSessionOutput.SyncFile(action.file))
                is GarminSyncAction.Finished -> out.add(GarminSessionOutput.SyncFinished(action.summary))
                is GarminSyncAction.TransferActive -> out.add(GarminSessionOutput.TransferActive(action.active))
            }
        }
    }

    // Output plumbing

    private fun send(message: GarminOutbound, clock: GarminClock) {
        val frame = message.frame()
        note(false, "-> ${describe(message)}")
        when (transport) {
            GarminTransportKind.MULTI_LINK_V2 -> {
                val handle = gfdiHandle
                if (handle == null) {
                    note(true, "no GFDI handle; ${describe(message)} not sent")
                    return
                }
                val m = mlr
                val packets = m?.send(frame, clock.nowMs) ?: GarminMultiLink.plainPackets(frame, handle, mtu)
                for (p in packets) out.add(GarminSessionOutput.Write(p))
            }
            GarminTransportKind.V1, GarminTransportKind.V0 -> {
                val size = GarminMultiLink.plainPayloadSize(mtu)
                var start = 0
                while (start < frame.bytes.size) {
                    val end = minOf(start + size, frame.bytes.size)
                    out.add(GarminSessionOutput.Write(frame.bytes.copyOfRange(start, end)))
                    start = end
                }
            }
        }
    }

    private fun control(request: GarminMultiLink.ControlRequest) {
        note(false, "-> ML ${describe(request)}")
        out.add(GarminSessionOutput.Write(request.encode(config.clientId)))
    }

    private fun fail(reason: String) {
        perform(sync.interrupt("session failed"), null)
        handshakeDeadline = null
        note(true, "session failed: $reason")
        setPhase(GarminSessionPhase.Failed(reason))
    }

    private fun setPhase(p: GarminSessionPhase) {
        if (p == phase) return
        phase = p
        out.add(GarminSessionOutput.Phase(p))
    }

    private fun note(rare: Boolean, text: String) {
        out.add(GarminSessionOutput.Log(GarminSessionLog(rare, text)))
    }

    private fun flush(): List<GarminSessionOutput> {
        val copy = out.toList()
        out.clear()
        return copy
    }

    private fun firmware(v: Int): String {
        val minor = v % 100
        return "${v / 100}.${if (minor < 10) "0" else ""}$minor"
    }

    private fun hex8(v: Int): String = "0x" + Integer.toHexString(v and 0xFF).padStart(2, '0')

    companion object {
        /** Protobuf status code for a declined request (UNKNOWN_REQUEST_ID with chunk status DISCARDED). */
        const val UNKNOWN_REQUEST_ID = 100

        /** GFDI ids that are internet-proxy or phone-feature requests NOOP declines, named for the log. */
        val REFUSED_TYPE_NAMES: Map<Int, String> = mapOf(
            5014 to "weather request",
            5016 to "live-track request",
            5019 to "ephemeris (AGPS) request",
            5028 to "ephemeris EPO (AGPS) request",
            5034 to "notification control",
            5039 to "find-my-phone request",
            5041 to "music control",
        )

        private val SMART_SERVICE_NAMES = mapOf(
            2 to "Connect IQ HTTP", 7 to "data transfer", 8 to "device status",
            13 to "core (HTTP proxy: weather / AGPS / OAuth)", 43 to "file access",
        )

        fun smartServiceName(chunk: GarminProtobufChunk): String {
            if (chunk.dataOffset != 0L) return "(continuation chunk)"
            val field = firstFieldNumber(chunk.data) ?: return "(continuation chunk)"
            return "${SMART_SERVICE_NAMES[field] ?: "service"} (Smart field $field)"
        }

        /** The watch-initiated FileAccess notices that announce new data, else null. */
        fun fileAccessAnnouncement(smart: ByteArray): GarminSyncAnnouncement? =
            when (val m = GarminFileAccessMessage.decodeSmart(smart)) {
                is GarminFileAccessMessage.ItemAdded -> GarminSyncAnnouncement.ItemAdded(m.items.size)
                is GarminFileAccessMessage.SyncButton -> GarminSyncAnnouncement.SyncButton
                else -> null
            }

        internal fun firstFieldNumber(data: ByteArray): Int? {
            var value = 0
            var shift = 0
            for (b in data.take(5)) {
                val byte = b.toInt() and 0xFF
                value = value or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return value shr 3
                shift += 7
            }
            return null
        }

        fun describe(m: GarminOutbound): String = when (m) {
            is GarminOutbound.Status -> "status ${m.of} ${m.status.name.lowercase()}"
            is GarminOutbound.DeviceInformationReply -> "device information reply"
            is GarminOutbound.Configuration -> "configuration ${m.capabilities.size} bits"
            is GarminOutbound.AuthNegotiationReply -> "auth negotiation reply"
            is GarminOutbound.CurrentTimeReply -> "current time reply"
            is GarminOutbound.SystemEvent -> "system event ${m.event.raw}"
            is GarminOutbound.Filter -> "filter"
            is GarminOutbound.DownloadRequest -> "download request"
            is GarminOutbound.FileTransferDataReply -> "file transfer reply"
            is GarminOutbound.SupportedFileTypesRequest -> "supported file types request"
            is GarminOutbound.FileAccessRequest -> "file access request"
            is GarminOutbound.ProtobufStatus ->
                "protobuf status ${if (m.of == GarminProtobufDirection.REQUEST) "request" else "response"} id=${m.requestId} discard=${m.discard} code=${m.code}"
        }

        fun describe(r: GarminMultiLink.ControlRequest): String = when (r) {
            is GarminMultiLink.ControlRequest.Register -> "register service=${r.service.raw} reliable=${r.reliable}"
            is GarminMultiLink.ControlRequest.CloseHandle -> "close handle service=${r.service.raw} handle=${r.handle}"
            is GarminMultiLink.ControlRequest.CloseAll -> "close all"
        }

        fun describe(e: GarminGfdi.DecodeError): String = when (e) {
            is GarminGfdi.DecodeError.TooShort -> "too short"
            is GarminGfdi.DecodeError.LengthMismatch -> "length ${e.declared} != ${e.actual}"
            is GarminGfdi.DecodeError.CrcMismatch -> "crc ${e.expected} != ${e.actual}"
        }
    }
}
