package com.noop.garmin

// Kotlin re-run of Packages/GarminProtocol/Tests/GarminProtocolTests/GarminSyncOracleCases.swift: the same
// scripted fake watch, the same history-sync traces, printed the same way. GarminHistorySyncTest compares
// garminSyncOracleLines() against the Swift standalone stdout. Keep the two files case-for-case.

/** A scripted Garmin watch (see the Swift twin for what each switch models). */
internal class OracleFakeWatch {
    val gfdiHandle = 0x65
    val pipeHandle = 0x66
    var directory: List<GarminDirectoryEntry> = emptyList()
    var files: MutableMap<Int, ByteArray> = HashMap()
    var chunkSize = 64
    var corruptChunks: MutableSet<Int> = HashSet()
    var cutAfterChunks: Int? = null
    var refuseContinue = false
    var restartOnContinue = false
    var compressedFiles: MutableMap<Int, ByteArray> = HashMap()
    var compressedPieceSize = 60
    var refuseDirectory = false
    var silent = false
    var itemPages: List<List<GarminItemReference>> = emptyList()
    var pipeFiles: MutableMap<Int, ByteArray> = HashMap()
    var pullHandles: MutableMap<Long, Int> = HashMap()
    val seen = ArrayList<String>()
    val seenTypes = HashSet<Int>()
    private val decoder = GarminCobsStreamDecoder()
    private var data = ByteArray(0)
    private var chunksSent = 0
    private var pageIndex = 0

    fun directoryBytes(): ByteArray {
        val w = GarminByteWriter()
        for (e in directory) e.write(w)
        return w.bytes
    }

    private fun gfdi(type: Int, payload: ByteArray): ByteArray = bytes(gfdiHandle) + GarminCobs.encode(oracleFrame(type, payload))

    private fun status(original: Int, rest: ByteArray = ByteArray(0)): ByteArray = gfdi(5000, oracleLE16(original) + bytes(0) + rest)

    fun receive(write: ByteArray): List<ByteArray> {
        if (write.isEmpty()) return emptyList()
        val handle = write[0].toInt() and 0xFF
        if (handle == 0) return control(write)
        if (handle == pipeHandle) return pipe(write)
        if (handle != gfdiHandle) return emptyList()
        val out = ArrayList<ByteArray>()
        for (message in decoder.feed(write.copyOfRange(1, write.size))) {
            val r = GarminGfdi.decode(message)
            if (r !is GarminGfdi.Result.Success) { seen.add("bad frame"); continue }
            seenTypes.add(r.frame.type)
            out += gfdiMessage(r.frame)
        }
        return out
    }

    private fun u(b: Byte) = b.toInt() and 0xFF

    private fun control(w: ByteArray): List<ByteArray> {
        val type = u(w[1])
        val service = u(w[10]) or (u(w[11]) shl 8)
        return when {
            type == 5 -> { seen.add("ml close-all"); listOf(oracleControl(6, oracleLE16(0) + bytes(0))) }
            type == 0 && service == 1 -> {
                seen.add("ml register gfdi")
                val info = oracleFr165DeviceInformation()
                listOf(oracleControl(1, oracleLE16(1) + bytes(0, gfdiHandle, 0)), gfdi(5024, info.copyOfRange(4, info.size - 2)))
            }
            type == 0 -> { seen.add("ml register service=$service"); listOf(oracleControl(1, oracleLE16(service) + bytes(0, pipeHandle, 0))) }
            type == 2 -> { seen.add("ml close handle service=$service handle=${u(w[12])}"); emptyList() }
            else -> { seen.add("ml type $type"); emptyList() }
        }
    }

    private fun pipe(w: ByteArray): List<ByteArray> {
        val transfer = u(w[3]) or (u(w[4]) shl 8)
        seen.add("pipe start transfer=$transfer bytes=${garminHex(w)}")
        val stream = pipeFiles[transfer] ?: return emptyList()
        val out = arrayListOf(bytes(pipeHandle, 0, 0, 0, transfer and 0xFF, transfer shr 8))
        var i = 0
        while (i < stream.size) {
            out.add(bytes(pipeHandle) + stream.copyOfRange(i, minOf(i + 40, stream.size)))
            i += 40
        }
        out.add(oracleControl(3, oracleLE16(GarminService.FILE_TRANSFER_2.raw) + bytes(pipeHandle, 0)))
        return out
    }

    private fun gfdiMessage(f: GarminGfdi.Frame): List<ByteArray> {
        val r = GarminByteReader(f.payload)
        when (f.type) {
            5000 -> {
                val original = r.u16() ?: 0
                val replyStatus = r.u8()
                if (original == 5024 && f.payload.size > 3) return listOf(gfdi(5050, bytes(8, 0xFF, 0x01, 0, 0, 0, 0, 0, 0x80)))
                if (original == 5004 && replyStatus == 0) {
                    val transferStatus = r.u8() ?: 0xFF
                    val next = (r.u32() ?: 0L).toInt()
                    seen.add("rx chunk reply status=$transferStatus next=$next")
                    return nextChunk(next)
                }
                return emptyList()
            }
            5050 -> { seen.add("rx configuration"); return listOf(status(5050)) }
            5030 -> { seen.add("rx system event ${u(f.payload[0])}"); return listOf(status(5030)) }
            5007 -> { seen.add("rx filter ${u(f.payload[0])}"); return listOf(status(5007, bytes(0))) }
            5002 -> {
                val index = r.u16()!!
                val offset = r.u32()!!.toInt()
                val kind = r.u8()!!
                val seed = r.u16()!!
                val size = r.u32()!!
                val flags = r.u8()!!
                seen.add("rx download idx=$index offset=$offset kind=$kind seed=$seed size=$size flags=$flags")
                if (silent) return emptyList()
                val body = if (index == 0) directoryBytes() else files[index]
                if (body == null || (index == 0 && refuseDirectory)) return listOf(status(5002, bytes(1) + oracleLE32(0)))
                if (kind == 0) {
                    if (refuseContinue) return listOf(status(5002, bytes(4) + oracleLE32(0)))
                    if (offset + size > body.size) return listOf(status(5002, bytes(6) + oracleLE32(0)))
                    if (offset > body.size || GarminCrc.crc16(body.copyOfRange(0, offset)) != seed) {
                        return listOf(status(5002, bytes(5) + oracleLE32(0)))
                    }
                }
                val stream = compressedFiles[index]
                if (flags and 1 != 0 && kind == 1 && stream != null) {
                    data = ByteArray(0)
                    return listOf(status(5002, bytes(0) + oracleLE32(body.size.toLong()))) + compressedPieces(stream, body)
                }
                val start = if (kind == 0 && !restartOnContinue) offset else 0
                data = body
                return listOf(status(5002, bytes(0) + oracleLE32((body.size - if (kind == 0) offset else 0).toLong()))) + nextChunk(start)
            }
            5043 -> return fileAccess(f.payload)
            else -> { seen.add("rx ${f.type}"); return emptyList() }
        }
    }

    private fun compressedPieces(stream: ByteArray, plain: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        var seq = 0
        var checkpoint = 0
        while (i < stream.size) {
            val cut = cutAfterChunks
            if (cut != null && chunksSent >= cut) { seen.add("link cut at piece $seq"); break }
            chunksSent += 1
            val end = minOf(i + compressedPieceSize, stream.size)
            val last = end == stream.size
            if (last) checkpoint = plain.size
            val header = bytes(seq, if (last) 0x02 else 0) + oracleLE32(checkpoint.toLong()) +
                oracleLE16(GarminCrc.crc16(plain.copyOfRange(0, checkpoint)))
            out.add(gfdi(5054, header + stream.copyOfRange(i, end)))
            if (!last) checkpoint = (GarminInflate.zlibPrefix(stream.copyOfRange(0, end)).size / 64) * 64
            i = end
            seq += 1
        }
        return out
    }

    private fun nextChunk(offset: Int): List<ByteArray> {
        if (offset >= data.size) return emptyList()
        val cut = cutAfterChunks
        if (cut != null && chunksSent >= cut) { seen.add("link cut at offset $offset"); return emptyList() }
        val end = minOf(offset + chunkSize, data.size)
        val chunk = data.copyOfRange(offset, end)
        var crc = GarminCrc.crc16(chunk, GarminCrc.crc16(data.copyOfRange(0, offset)))
        if (corruptChunks.remove(chunksSent)) crc = crc xor 0xFFFF
        chunksSent += 1
        return listOf(gfdi(5004, bytes(0) + oracleLE16(crc) + oracleLE32(offset.toLong()) + chunk))
    }

    private fun fileAccess(payload: ByteArray): List<ByteArray> {
        val r = GarminByteReader(payload)
        val id = r.u16()!!
        r.u32(); r.u32(); r.u32()
        val smart = r.rest()
        val ack = status(5043, oracleLE16(id) + oracleLE32(0) + bytes(0, 0))
        val fields = GarminProtoReader.fields(smart) ?: return listOf(ack)
        val service = fields.firstOrNull()?.second?.data ?: return listOf(ack)
        val inner = GarminProtoReader.fields(service) ?: return listOf(ack)
        val field = inner.firstOrNull()?.first ?: return listOf(ack)
        val body = GarminProtoWriter()
        if (field == 9) {
            val page = if (pageIndex < itemPages.size) itemPages[pageIndex] else emptyList()
            seen.add("rx item list page=$pageIndex")
            pageIndex += 1
            body.uint(1, 0)
            if (pageIndex < itemPages.size) body.uint(2, 7)
            for (item in page) body.lengthDelimited(4, item.encode())
            val wrap = GarminProtoWriter(); wrap.lengthDelimited(10, body.bytes)
            val smartOut = GarminProtoWriter(); smartOut.lengthDelimited(43, wrap.bytes)
            val all = smartOut.bytes
            val half = all.size / 2
            val first = all.copyOfRange(0, half)
            val second = all.copyOfRange(half, all.size)
            return listOf(ack,
                gfdi(5044, oracleLE16(id) + oracleLE32(0) + oracleLE32(all.size.toLong()) + oracleLE32(first.size.toLong()) + first),
                gfdi(5044, oracleLE16(id) + oracleLE32(half.toLong()) + oracleLE32(all.size.toLong()) + oracleLE32(second.size.toLong()) + second))
        }
        if (field == 1) {
            val pull = inner.first().second.data
            val pf = pull?.let { GarminProtoReader.fields(it) }
            val itemBytes = pf?.firstOrNull { it.first == 1 }?.second?.data
            val item = itemBytes?.let { GarminItemReference.decode(it) }
            val uid = item?.uid
            if (uid != null) {
                val handle = pullHandles[uid.leastSignificant]
                seen.add("rx pull item uid=${oracleHex64(uid.leastSignificant)} -> ${handle?.toString() ?: "refused"}")
                if (handle != null) {
                    body.uint(1, 0); body.uint(2, 0); body.uint(3, handle.toLong())
                } else {
                    body.uint(1, 2)
                }
                val wrap = GarminProtoWriter(); wrap.lengthDelimited(2, body.bytes)
                val smartOut = GarminProtoWriter(); smartOut.lengthDelimited(43, wrap.bytes)
                val all = smartOut.bytes
                return listOf(ack, gfdi(5044, oracleLE16(id) + oracleLE32(0) + oracleLE32(all.size.toLong()) + oracleLE32(all.size.toLong()) + all))
            }
        }
        seen.add("rx file access field $field")
        return listOf(ack)
    }
}

internal fun oracleWithCrc(body: ByteArray): ByteArray {
    val crc = GarminCrc.crc16(body)
    return body + bytes(crc and 0xFF, crc shr 8)
}

internal fun oracleFileBody(seed: Int, count: Int): ByteArray =
    oracleWithCrc(ByteArray(count - 2) { (it * 31 + seed * 7 + (it shr 4)).toByte() })

internal fun oracleEntry(index: Int, subType: Int, number: Int, size: Int, ts: Long, dataType: Int = 128): GarminDirectoryEntry =
    GarminDirectoryEntry(index, dataType, subType, number, 0, 0, size.toLong(), ts)

internal fun oracleHexBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

internal fun oracleZlibStream(): ByteArray = oracleHexBytes(
    "78da6350f5ca9fb2f31eb37144eda2e36f04cdd2ba375cfd25ef923973df630edda0f239673e8a5927b4ae3aff5fc5336ff28ebb4c46e1350b8fbd16304ded5a7fe5a79c73c68cbd8fd87502cb669ffe206a15dfb2f2dc3f658fdc49dbef301a86552f38fa8adf24a573dde51fb24ee9d3f73c64d30e289d75eabd88655cf38ab37f95dc73266ebbcd60105a35ffc84b3ee3e48eb597becb38a64ddbfd8055cbbf64e6c977c216b14dcbcffc5174cb9eb0f5d67ffd90ca79875ff01a25b5afb9f84dda2175eaaefb2c9a7ec5334ebc15328f695c76fab7826b56ff969bfff4822be61e7ace6398d8b6fac25729fb1490df347c8ba683fc16ddb0f414c86f7d9b6ffc05f9ede0336e0390dfbe48da2583fca6ee53380de4b7a8fa2527417eebdd74fd0fc86f079e72e983fca6e7e619a2509e9a9393975a5cac9059929aab4063a1177e00bc77bf0b",
)

internal fun oracleZlibPlain(): ByteArray =
    oracleWithCrc(ByteArray(300) { (it * 37 + (it shr 3) * 11).toByte() } + ".FIT wellness item ".repeat(6).toByteArray(Charsets.UTF_8))

internal fun oracleSyncDescribe(o: GarminSessionOutput): String? = when (o) {
    is GarminSessionOutput.Write -> {
        val b = o.bytes
        if (b.isNotEmpty() && b[0].toInt() == 0) "w ml ${garminHex(b)}"
        else {
            val body = GarminCobs.decode(b.copyOfRange(1, b.size))
            val frame = body?.let { (GarminGfdi.decode(it) as? GarminGfdi.Result.Success)?.frame }
            if (frame == null) "w raw ${garminHex(b)}"
            else {
                val p = if (frame.payload.size <= 40) garminHex(frame.payload) else "len=${frame.payload.size} fnv=${oracleFnv32(frame.payload)}"
                "w ${frame.type} $p"
            }
        }
    }
    is GarminSessionOutput.Log -> if (o.line.rare) "log ${o.line.text}" else null
    is GarminSessionOutput.SyncFile -> "file ${o.file.key.label} n=${o.file.bytes.size} fnv=${oracleFnv32(o.file.bytes)} " +
        "resumed=${oracleOpt(o.file.resumedFrom)} type=${oracleOpt(o.file.typeName)} fit=${oracleOpt(o.file.fitType?.wireName)}"
    is GarminSessionOutput.SyncFinished -> "finished ${o.summary.line} empty=${o.summary.isEmpty}"
    is GarminSessionOutput.SyncAnnounced -> "announced ${o.announcement.label} filter=${o.announcement.expectsFilter}"
    is GarminSessionOutput.TransferActive -> "transfer ${o.active}"
    is GarminSessionOutput.Phase -> "phase ${o.phase.label}"
    is GarminSessionOutput.Battery, is GarminSessionOutput.Watch, is GarminSessionOutput.PairingEventsSent -> null
}

internal sealed class OracleSyncStep {
    object Connect : OracleSyncStep()
    class Sync(val request: GarminSyncRequest) : OracleSyncStep()
    class WatchSends(val bytes: ByteArray) : OracleSyncStep()
    class Tick(val ms: Long) : OracleSyncStep()
    object DropLink : OracleSyncStep()
}

internal fun oracleNewSyncSession(): GarminSession = GarminSession(
    GarminSessionConfig(oracleSessionHost(), firstPairing = false, sendsHostLifecycleEvents = false),
    GarminTransportKind.MULTI_LINK_V2, 150)

internal fun oracleRunSync(
    name: String, watch: OracleFakeWatch, steps: List<OracleSyncStep>, existing: GarminSession? = null,
): Pair<List<String>, GarminSession> {
    val session = existing ?: oracleNewSyncSession()
    val out = mutableListOf("trace $name")
    var now = 0L
    var linkUp = true
    val inbox = ArrayDeque<ByteArray>()
    fun clock() = GarminClock(now, 1_793_546_986L + now / 1000, 25_200)
    fun absorb(outputs: List<GarminSessionOutput>) {
        for (o in outputs) {
            oracleSyncDescribe(o)?.let { out.add("@$now $it") }
            if (o is GarminSessionOutput.Write && linkUp) inbox.addAll(watch.receive(o.bytes))
        }
    }
    fun pump() {
        var guardCount = 0
        while (linkUp && inbox.isNotEmpty() && guardCount < 10_000) {
            guardCount += 1
            now += 1
            absorb(session.receive(inbox.removeFirst(), clock()))
        }
    }
    for (step in steps) {
        when (step) {
            is OracleSyncStep.Connect -> absorb(session.start(clock()))
            is OracleSyncStep.Sync -> absorb(session.startSync(step.request, clock()))
            is OracleSyncStep.WatchSends -> { now += 1; absorb(session.receive(step.bytes, clock())) }
            is OracleSyncStep.Tick -> { now += step.ms; absorb(session.tick(clock())) }
            is OracleSyncStep.DropLink -> { linkUp = false; inbox.clear() }
        }
        pump()
    }
    out.add("watch saw " + watch.seen.joinToString(" | "))
    out.add("watch types " + watch.seenTypes.sorted().joinToString(","))
    out.add("end syncing=${session.isSyncing} resume=${session.syncResumePoint?.let { "${it.key.label}@${it.offset} crc=${it.runningCrc}" } ?: "-"} " +
        "deadline=${oracleOpt(session.nextDeadlineMs)}")
    return out to session
}

internal const val ORACLE_RECENT_TS = 1_161_900_000L
internal const val ORACLE_NOT_BEFORE = 1_792_000_000L

internal fun oracleStandardWatch(): OracleFakeWatch = OracleFakeWatch().apply {
    directory = listOf(
        oracleEntry(1, 32, 1, 150, ORACLE_RECENT_TS),
        oracleEntry(2, 49, 2, 70, ORACLE_RECENT_TS + 60),
        oracleEntry(3, 44, 3, 40, ORACLE_RECENT_TS + 120),
        oracleEntry(4, 3, 4, 90, ORACLE_RECENT_TS),
        oracleEntry(5, 32, 5, 64, 1_000_000),
        oracleEntry(6, 255, 6, 30, ORACLE_RECENT_TS, dataType = 8),
        oracleEntry(7, 68, 7, 0, ORACLE_RECENT_TS),
    )
    files = hashMapOf(1 to oracleFileBody(1, 150), 2 to oracleFileBody(2, 70), 3 to oracleFileBody(3, 40),
        4 to oracleFileBody(4, 90), 5 to oracleFileBody(5, 64))
}

internal fun oracleEntryBytes(e: GarminDirectoryEntry): ByteArray = GarminByteWriter().also { e.write(it) }.bytes

/** Swift's description of a [GarminSyncFlow], as the Swift oracle prints it. */
internal fun oracleDescribe(f: GarminSyncFlow): String = when (f) {
    is GarminSyncFlow.Legacy -> "legacy"
    is GarminSyncFlow.FileAccess -> "fileAccess(legacyFlush: ${f.legacyFlush})"
}

internal val ORACLE_SYNC_MONITOR = GarminItemReference(uid = GarminUid(0x4567_0000_0000_0001L, 0xA1),
    dataType = GarminItemDataType(format = 3, typeName = "FIT_TYPE_32", typeNameKey = 32), dataSize = 416, modifiedTime = ORACLE_RECENT_TS)

internal fun garminSyncOracleLines(): List<String> {
    val out = mutableListOf<String>()
    val synced = setOf(GarminSyncFileKey(oracleEntry(3, 44, 3, 40, ORACLE_RECENT_TS + 120)))
    val handle = 0x65
    fun ml(message: ByteArray): ByteArray = bytes(handle) + GarminCobs.encode(message)
    val connect = OracleSyncStep.Connect
    fun sync(r: GarminSyncRequest) = OracleSyncStep.Sync(r)

    // A
    val a = oracleStandardWatch()
    a.corruptChunks = hashSetOf(2)
    out += oracleRunSync("legacy-watch-initiated", a, listOf(
        connect,
        OracleSyncStep.WatchSends(ml(oracleFrame(5037, bytes(1, 4) + oracleLE32(0x0400_0028)))),
        sync(GarminSyncRequest(synced = synced, requestCompression = false, watchInitiated = true, notBeforeUnix = ORACLE_NOT_BEFORE)),
    )).first

    // B
    val b = oracleStandardWatch()
    b.cutAfterChunks = 4
    val (bLines, bSession) = oracleRunSync("legacy-cut", b, listOf(
        connect, sync(GarminSyncRequest(synced = synced, notBeforeUnix = ORACLE_NOT_BEFORE)), OracleSyncStep.DropLink,
    ))
    out += bLines
    val held = bSession.syncResumePoint
    b.cutAfterChunks = null
    out += oracleRunSync("legacy-resume", b, listOf(
        connect, sync(GarminSyncRequest(synced = synced, resume = held, notBeforeUnix = ORACLE_NOT_BEFORE)),
    )).first

    // C
    val c = oracleStandardWatch()
    c.refuseContinue = true
    val partialData = oracleFileBody(2, 70).copyOfRange(0, 20)
    val partial = GarminSyncResume(GarminSyncFileKey(c.directory[1]), partialData, GarminCrc.crc16(partialData))
    out += oracleRunSync("legacy-resume-refused", c, listOf(
        connect, sync(GarminSyncRequest(synced = synced, resume = partial, notBeforeUnix = ORACLE_NOT_BEFORE)),
    )).first

    // D
    val d = oracleStandardWatch()
    val goneData = bytes(1, 2, 3)
    val gone = GarminSyncResume(GarminSyncFileKey(oracleEntry(9, 32, 9, 100, ORACLE_RECENT_TS)), goneData, GarminCrc.crc16(goneData))
    val everything = d.directory.map { GarminSyncFileKey(it) }.toSet()
    out += oracleRunSync("legacy-nothing-new", d, listOf(
        connect, sync(GarminSyncRequest(synced = everything, resume = gone, notBeforeUnix = ORACLE_NOT_BEFORE)),
    )).first

    // E
    val e = oracleStandardWatch()
    e.refuseDirectory = true
    out += oracleRunSync("legacy-directory-refused", e, listOf(connect, sync(GarminSyncRequest()))).first
    val f = oracleStandardWatch()
    f.silent = true
    out += oracleRunSync("legacy-stall", f, listOf(connect, sync(GarminSyncRequest()), OracleSyncStep.Tick(19_999), OracleSyncStep.Tick(1))).first
    val g = oracleStandardWatch()
    g.cutAfterChunks = 3
    out += oracleRunSync("legacy-gfdi-closed", g, listOf(
        connect,
        sync(GarminSyncRequest(synced = synced, notBeforeUnix = ORACLE_NOT_BEFORE)),
        OracleSyncStep.WatchSends(oracleControl(3, oracleLE16(1) + bytes(handle, 0))),
    )).first

    // F
    val h = oracleStandardWatch()
    val monitor = ORACLE_SYNC_MONITOR
    val sleep = GarminItemReference(uid = GarminUid(0x4567_0000_0000_0002L, 0xA2),
        dataType = GarminItemDataType(typeNameKey = 32), dataSize = 99, modifiedTime = ORACLE_RECENT_TS + 5)
    val app = GarminItemReference(uid = GarminUid(1, 0xA3),
        dataType = GarminItemDataType(typeName = "IQWatchApps"), dataSize = 5_000, modifiedTime = ORACLE_RECENT_TS)
    val known = GarminItemReference(uid = GarminUid(2, 0xA4),
        dataType = GarminItemDataType(typeName = "FIT_TYPE_49", typeNameKey = 32), dataSize = 77, modifiedTime = ORACLE_RECENT_TS)
    val untyped = GarminItemReference(uid = GarminUid(3, 0xA5), dataSize = 10)
    h.itemPages = listOf(listOf(monitor, app), listOf(known, sleep, untyped))
    h.pullHandles = hashMapOf(0xA1L to 0x1234)
    h.pipeFiles = hashMapOf(0x1234 to oracleZlibStream())
    out += oracleRunSync("file-access-flush", h, listOf(
        connect,
        sync(GarminSyncRequest(flow = GarminSyncFlow.FileAccess(legacyFlush = true),
            synced = setOf(GarminSyncFileKey.fileAccess(known, "FIT_TYPE_49")!!), notBeforeUnix = ORACLE_NOT_BEFORE)),
    )).first

    // G
    val i = oracleStandardWatch()
    i.itemPages = listOf(listOf(monitor))
    i.pullHandles = hashMapOf(0xA1L to 0x0042)
    i.pipeFiles = hashMapOf(0x0042 to oracleZlibStream().let { it.copyOfRange(0, it.size - 1) })
    val added = GarminProtoWriter(); added.lengthDelimited(1, monitor.encode())
    val addedWrap = GarminProtoWriter(); addedWrap.lengthDelimited(12, added.bytes)
    val addedSmart = GarminProtoWriter(); addedSmart.lengthDelimited(43, addedWrap.bytes)
    val notice = addedSmart.bytes
    val button = GarminProtoWriter(); button.lengthDelimited(22, ByteArray(0))
    val buttonSmart = GarminProtoWriter(); buttonSmart.lengthDelimited(43, button.bytes)
    val bs = buttonSmart.bytes
    out += oracleRunSync("file-access-no-flush", i, listOf(
        connect,
        OracleSyncStep.WatchSends(ml(oracleFrame(5043, oracleLE16(900) + oracleLE32(0) + oracleLE32(notice.size.toLong()) +
            oracleLE32(notice.size.toLong()) + notice))),
        OracleSyncStep.WatchSends(ml(oracleFrame(5043, oracleLE16(901) + oracleLE32(0) + oracleLE32(bs.size.toLong()) +
            oracleLE32(bs.size.toLong()) + bs))),
        OracleSyncStep.WatchSends(ml(oracleFrame(5009, oracleEntryBytes(oracleEntry(1, 32, 1, 150, ORACLE_RECENT_TS))))),
        sync(GarminSyncRequest(flow = GarminSyncFlow.FileAccess(legacyFlush = false))),
    )).first

    // I
    fun compressedWatch(): OracleFakeWatch = OracleFakeWatch().apply {
        directory = listOf(oracleEntry(8, 32, 8, 416, ORACLE_RECENT_TS), oracleEntry(9, 49, 9, 416, ORACLE_RECENT_TS + 10))
        files = hashMapOf(8 to oracleZlibPlain(), 9 to oracleZlibPlain())
        compressedFiles = hashMapOf(8 to oracleZlibStream(), 9 to oracleZlibStream())
    }
    val k = compressedWatch()
    val bad = oracleZlibStream()
    bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0x01).toByte()
    k.compressedFiles[9] = bad
    out += oracleRunSync("compressed", k, listOf(
        connect, sync(GarminSyncRequest(requestCompression = true, notBeforeUnix = ORACLE_NOT_BEFORE)),
    )).first
    val l = compressedWatch()
    l.compressedPieceSize = 120
    l.cutAfterChunks = 3
    val (lLines, lSession) = oracleRunSync("compressed-cut", l, listOf(
        connect, sync(GarminSyncRequest(requestCompression = true, notBeforeUnix = ORACLE_NOT_BEFORE)), OracleSyncStep.DropLink,
    ))
    out += lLines
    l.restartOnContinue = true
    l.cutAfterChunks = null
    out += oracleRunSync("compressed-resume-restarted", l, listOf(
        connect, sync(GarminSyncRequest(resume = lSession.syncResumePoint, requestCompression = true, notBeforeUnix = ORACLE_NOT_BEFORE)),
    )).first
    val m = oracleStandardWatch()
    m.restartOnContinue = true
    val partialMData = oracleFileBody(1, 150).copyOfRange(0, 64)
    val partialM = GarminSyncResume(GarminSyncFileKey(m.directory[0]), partialMData, GarminCrc.crc16(partialMData))
    out += oracleRunSync("legacy-continue-restarted", m, listOf(
        connect, sync(GarminSyncRequest(synced = synced, resume = partialM, notBeforeUnix = ORACLE_NOT_BEFORE)),
    )).first

    // H
    val j = oracleStandardWatch()
    out += oracleRunSync("not-ready", j, listOf(sync(GarminSyncRequest()))).first

    val vectors = listOf(
        "stored" to "7801011100eeff4e4f4f502073746f72656420626c6f636b34440619",
        "fixed" to "78014b4c4a4e8421053f7fff0000389005f5",
        "dynamic" to "78dacbc9cc4b553050c84f5328c94855484f2ccacdcc53c8cdcfcb2cc92f5248cbcc49e5ca01a93024a8c288a00a63822a4c08aa3025a8c28ca08a51df8efa76d4b7a3bea5896f01ecb1db33",
        "bad-header" to "7901011100eeff4e4f4f502073746f72656420626c6f636b34440619",
        "bad-adler" to "7801011100eeff4e4f4f502073746f72656420626c6f636b34440618",
        "truncated" to "78dacbc9cc4b553050c84f5328c94855",
        "empty" to "",
    )
    for ((name, hex) in vectors) {
        val result = GarminInflate.zlib(oracleHexBytes(hex))
        out.add("inflate $name " + (result?.let { "n=${it.size} fnv=${oracleFnv32(it)}" } ?: "nil"))
    }
    val pipePlain = GarminInflate.zlib(oracleZlibStream())
    out.add("inflate pipe matches=${pipePlain != null && pipePlain.contentEquals(oracleZlibPlain())}")

    out.add("key fa ${GarminSyncFileKey.fileAccess(monitor, "FIT_TYPE_32")!!.label}")
    out.add("key fa-none ${oracleOpt(GarminSyncFileKey.fileAccess(untyped)?.label)}")
    out.add("key legacy ${GarminSyncFileKey(oracleEntry(0x1234, 49, 0xBEEF, 70_000, 0xFEDC_BA98L)).label}")
    out.add("wanted " + GarminFitFileType.entries.joinToString(",") { "${it.raw}=${it.wireName}" })
    for (name in listOf("FIT_TYPE_32", "FIT_TYPE_4", "FIT_TYPE_255", "FIT_TYPE_256", "FIT_TYPE_", "FIT_TYPE_-1", "FIT_TYPE_0032", "IQWatchApps", "")) {
        out.add("fit-subtype ${if (name.isEmpty()) "(empty)" else name}=${oracleOpt(GarminSyncFileKey.fitSubType(name))}")
    }
    out.add("item-time modified=${GarminSyncFileKey.itemTime(monitor)} uid=${GarminSyncFileKey.itemTime(app)} " +
        "none=${GarminSyncFileKey.itemTime(untyped)} " +
        "created=${GarminSyncFileKey.itemTime(GarminItemReference(uid = GarminUid(0x4549_F016_0000_0001L, 1)))} " +
        "max=${GarminSyncFileKey.itemTime(GarminItemReference(uid = GarminUid(-1L, 1)))}")
    for (product in listOf(4432, 3990, 0)) out.add("flow product=$product ${oracleDescribe(GarminSyncModelPolicy.flow(product))}")
    out.add("flow product=nil ${oracleDescribe(GarminSyncModelPolicy.flow(null))}")
    return out
}
