package com.noop.garmin

// Kotlin re-implementation of Packages/GarminProtocol/Tests/GarminProtocolTests/GarminOracleCases.swift.
// garminOracleLines() must print exactly what the Swift standalone oracle printed; GarminOracleTest holds
// that stdout as its expected literal. Keep the two files case-for-case and format-for-format.

internal fun oracleFnv32(bytes: ByteArray): String {
    var h = 0x811C9DC5L
    for (b in bytes) {
        h = h xor (b.toLong() and 0xFF)
        h = (h * 0x01000193L) and 0xFFFF_FFFFL
    }
    return oracleHex32(h)
}

internal fun oracleHex32(v: Long): String = java.lang.Long.toHexString(v and 0xFFFF_FFFFL).padStart(8, '0')
internal fun oracleHex16(v: Int): String = Integer.toHexString(v and 0xFFFF).padStart(4, '0')
internal fun oracleHex64(v: Long): String = java.lang.Long.toHexString(v).padStart(16, '0')
internal fun oraclePattern(count: Int, mul: Int, add: Int): ByteArray = ByteArray(count) { ((it * mul + add) % 256).toByte() }
internal fun oracleOpt(v: Any?): String = v?.toString() ?: "-"
internal fun oracleU64(v: Long?): String = v?.let { java.lang.Long.toUnsignedString(it) } ?: "-"
internal fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

internal fun oracleDescribe(item: GarminItemReference): String {
    val uid = item.uid?.let { oracleHex64(it.mostSignificant) + ":" + oracleHex64(it.leastSignificant) } ?: "-"
    val dt = item.dataType?.let { "${oracleOpt(it.format)}/${oracleOpt(it.typeName)}/${oracleOpt(it.typeNameKey)}/${oracleOpt(it.fileIdentifier)}" } ?: "-"
    return "{uid=$uid type=$dt size=${oracleU64(item.dataSize)} flags=${oracleOpt(item.requestedFlags)}" +
        " txn=${oracleOpt(item.transactionId)} urg=${oracleOpt(item.urgency)} mtime=${oracleOpt(item.modifiedTime)}}"
}

internal fun oracleDescribe(m: GarminFileAccessMessage?): String = when (m) {
    null -> "nil"
    is GarminFileAccessMessage.ItemListResponse ->
        "itemList status=${oracleOpt(m.status)} session=${oracleOpt(m.sessionId)} next=${oracleOpt(m.nextTransactionId)}" +
            " error=${oracleOpt(m.errorCode)} items=[" + m.items.joinToString(",") { oracleDescribe(it) } + "]"
    is GarminFileAccessMessage.PullItemResponse ->
        "pull result=${oracleOpt(m.result)} transport=${oracleOpt(m.transport)} handle=${oracleOpt(m.transferHandle)}" +
            " path=${oracleOpt(m.filePath)} error=${oracleOpt(m.errorCode)}"
    is GarminFileAccessMessage.ItemAdded -> "itemAdded [" + m.items.joinToString(",") { oracleDescribe(it) } + "]"
    GarminFileAccessMessage.SyncButton -> "syncButton"
    is GarminFileAccessMessage.Other -> "other ${m.field}"
}

internal fun oracleDescribe(e: GarminDirectoryEntry): String =
    "{idx=${e.fileIndex} dt=${e.dataType} st=${e.subType} num=${e.fileNumber} sf=${e.specificFlags}" +
        " ff=${e.fileFlags} size=${e.fileSize} ts=${e.garminTimestamp} unix=${oracleOpt(e.unixTime)}}"

internal fun oracleDescribe(d: GarminStatusDetail): String = when (d) {
    GarminStatusDetail.None -> "none"
    is GarminStatusDetail.DownloadRequest -> "download ${d.downloadStatus} ${d.maxFileSize}"
    is GarminStatusDetail.FileTransferData -> "transfer ${d.transferStatus} ${d.dataOffset}"
    is GarminStatusDetail.SupportedFileTypes -> "types [" + d.types.joinToString(",") { "${it.dataType}/${it.subType}/${it.name}" } + "]"
    is GarminStatusDetail.Filter -> "filter ${d.unknown}"
    is GarminStatusDetail.AuthNegotiation -> "auth ${d.authStatus} ${d.unknown} ${oracleHex32(d.flags)}"
    is GarminStatusDetail.Protobuf -> "protobuf ${d.requestId} ${d.dataOffset} ${d.chunkStatus} ${d.statusCode}"
}

internal fun oracleDescribe(m: GarminInbound): String = when (m) {
    is GarminInbound.Status -> "status orig=${m.originalType} status=${m.status} detail=${oracleDescribe(m.detail)}"
    is GarminInbound.DeviceInformation -> m.info.let { i ->
        "devinfo proto=${i.protocolVersion} product=${i.productNumber} unit=${i.unitNumber}" +
            " sw=${i.softwareVersion} maxpkt=${i.maxPacketSize} bt=${i.bluetoothName} name=${i.deviceName}" +
            " model=${i.deviceModel} classic=${i.classicAddress?.let { garminHex(it) } ?: "-"}"
    }
    is GarminInbound.Configuration -> "config bits=" + GarminCapabilityBitmap.indices(m.capabilities).joinToString(",")
    is GarminInbound.AuthNegotiation -> "auth unk=${m.unknown} flags=${oracleHex32(m.flags)}"
    is GarminInbound.CurrentTimeRequest -> "time ref=${m.referenceId}"
    is GarminInbound.Synchronization -> "sync type=${m.type} mask=${oracleHex64(m.fileTypeMask)}"
    is GarminInbound.FileTransferData -> "chunk flags=${m.flags} crc=${oracleHex16(m.runningCrc)} offset=${m.dataOffset} data=${garminHex(m.data)}"
    is GarminInbound.CompressedFileData ->
        "compressed seq=${m.sequence} flags=${m.flags} checkpoint=${m.checkpoint} crc=${oracleHex16(m.checkpointCrc)} data=${garminHex(m.data)}"
    is GarminInbound.FileAvailable -> "available " + oracleDescribe(m.entry)
    is GarminInbound.ProtobufRequest -> m.chunk.let { c ->
        "pbreq id=${c.requestId} offset=${c.dataOffset} total=${c.totalLength} complete=${c.isComplete} data=${garminHex(c.data)}"
    }
    is GarminInbound.ProtobufResponse -> m.chunk.let { c ->
        "pbresp id=${c.requestId} offset=${c.dataOffset} total=${c.totalLength} complete=${c.isComplete} data=${garminHex(c.data)}"
    }
    is GarminInbound.BatteryStatus -> "battery status=${m.statusBits} centivolts=${m.centivolts} percent=${m.percent}"
    is GarminInbound.Unknown -> "unknown type=${m.type} payload=${garminHex(m.payload)}"
}

internal fun oracleDescribe(r: GarminGfdi.Result): String = when (r) {
    is GarminGfdi.Result.Success -> r.frame.let { f ->
        "ok raw=${oracleHex16(f.rawType)} type=${f.type} seq=${oracleOpt(f.compactSequence)} payload=${garminHex(f.payload)}"
    }
    is GarminGfdi.Result.Failure -> when (val e = r.error) {
        GarminGfdi.DecodeError.TooShort -> "err short"
        is GarminGfdi.DecodeError.LengthMismatch -> "err length ${e.declared} ${e.actual}"
        is GarminGfdi.DecodeError.CrcMismatch -> "err crc ${oracleHex16(e.expected)} ${oracleHex16(e.actual)}"
    }
}

internal fun oracleDescribe(c: GarminMultiLink.ControlResponse?): String = when (c) {
    null -> "nil"
    is GarminMultiLink.ControlResponse.Registered ->
        "registered service=${c.service} status=${c.status} handle=${c.handle} reliable=${c.reliable}"
    is GarminMultiLink.ControlResponse.HandleClosed -> "closed service=${c.service} handle=${c.handle} status=${c.status}"
    GarminMultiLink.ControlResponse.AllClosed -> "allClosed"
}

internal fun oracleDescribe(r: GarminMultiLink.Route): String = when (r) {
    is GarminMultiLink.Route.Control -> "control ${garminHex(r.packet)}"
    is GarminMultiLink.Route.Reliable -> "reliable ${r.handle} ${garminHex(r.packet)}"
    is GarminMultiLink.Route.Plain -> "plain ${r.handle} ${garminHex(r.payload)}"
    GarminMultiLink.Route.Empty -> "empty"
}

/** One sample per GarminOutbound.Kind; the `when` is exhaustive so a new case must be sampled. */
internal fun oracleOutboundSamples(kind: GarminOutbound.Kind): List<GarminOutbound> {
    val item = GarminItemReference(
        uid = GarminUid(0x0123_4567_89AB_CDEFL, 0xFEDC_BA98_7654_3210uL.toLong()),
        dataType = GarminItemDataType(format = 3, typeName = "FIT_TYPE_44", typeNameKey = 7, fileIdentifier = null),
        dataSize = 123_456, requestedFlags = null, transactionId = 42, urgency = 1, modifiedTime = 1_100_000_000,
    )
    val synced = GarminUid(42_405, 42_405)
    return when (kind) {
        GarminOutbound.Kind.STATUS -> listOf(
            GarminOutbound.Status(5024, GarminStatus.ACK), GarminOutbound.Status(5999, GarminStatus.UNSUPPORTED),
        )
        GarminOutbound.Kind.DEVICE_INFORMATION_REPLY -> listOf(
            GarminOutbound.DeviceInformationReply(
                GarminHostIdentity(150, 0xFFFF, 0xFFFF_FFFFL, 100, 0xFFFF, "NOOP phone", "NOOP", "Strand é"),
                protocolFlags = 1,
            ),
        )
        GarminOutbound.Kind.CONFIGURATION -> listOf(
            GarminOutbound.Configuration(emptySet()), GarminOutbound.Configuration(GarminCapability.entries.toSet()),
        )
        GarminOutbound.Kind.AUTH_NEGOTIATION_REPLY -> listOf(
            GarminOutbound.AuthNegotiationReply(0, 0), GarminOutbound.AuthNegotiationReply(3, 0x8000_0001L),
        )
        GarminOutbound.Kind.CURRENT_TIME_REPLY -> listOf(
            GarminOutbound.CurrentTimeReply(77, 1_100_000_000, -18_000, 1_120_000_000, 1_105_000_000),
        )
        GarminOutbound.Kind.SYSTEM_EVENT -> GarminSafeSystemEvent.entries.map { GarminOutbound.SystemEvent(it, 0) }
        GarminOutbound.Kind.FILTER -> GarminFilterType.entries.map { GarminOutbound.Filter(it) }
        GarminOutbound.Kind.DOWNLOAD_REQUEST -> listOf(
            GarminOutbound.DownloadRequest(0, 0, GarminDownloadKind.NEW, 0, 0, 0),
            GarminOutbound.DownloadRequest(0x1234, 4096, GarminDownloadKind.CONTINUE, 0xBEEF, 70_000, 1),
        )
        GarminOutbound.Kind.FILE_TRANSFER_DATA_REPLY -> listOf(GarminOutbound.FileTransferDataReply(0, 500))
        GarminOutbound.Kind.SUPPORTED_FILE_TYPES_REQUEST -> listOf(GarminOutbound.SupportedFileTypesRequest)
        GarminOutbound.Kind.FILE_ACCESS_REQUEST -> listOf(
            GarminOutbound.FileAccessRequest(1, GarminFileAccessRequest.ItemList(null, null, null, emptyList(), emptyList())),
            GarminOutbound.FileAccessRequest(2, GarminFileAccessRequest.ItemList(9, 300, 100, listOf(synced), listOf(synced))),
            GarminOutbound.FileAccessRequest(0xFFFF, GarminFileAccessRequest.PullItem(item, 24, 0, 15)),
        )
        GarminOutbound.Kind.PROTOBUF_STATUS -> listOf(
            GarminOutbound.ProtobufStatus(GarminProtobufDirection.REQUEST, 5, 0, false, 0),
            GarminOutbound.ProtobufStatus(GarminProtobufDirection.RESPONSE, 6, 10, true, 100),
        )
    }
}

/** A test-side GFDI frame builder for inbound fixtures. */
internal fun oracleFrame(type: Int, payload: ByteArray): ByteArray {
    val len = payload.size + 6
    val head = bytes(len and 0xFF, len shr 8, type and 0xFF, (type shr 8) and 0xFF) + payload
    val crc = GarminCrc.crc16(head)
    return head + bytes(crc and 0xFF, crc shr 8)
}

internal fun oracleLE16(v: Int): ByteArray = bytes(v and 0xFF, (v shr 8) and 0xFF)
internal fun oracleLE32(v: Long): ByteArray = ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }
internal fun oracleStr(s: String): ByteArray = s.toByteArray(Charsets.UTF_8).let { bytes(it.size) + it }

internal fun oracleVarint(value: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var x = value
    while ((x and 0x7FL.inv()) != 0L) {
        out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
    }
    out.write(x.toInt())
    return out.toByteArray()
}

internal fun oracleLd(field: Int, data: ByteArray): ByteArray =
    oracleVarint(((field shl 3) or 2).toLong()) + oracleVarint(data.size.toLong()) + data

internal fun oracleVi(field: Int, v: Long): ByteArray = oracleVarint((field shl 3).toLong()) + oracleVarint(v)
internal fun oracleSmart(serviceField: Int, body: ByteArray): ByteArray = oracleLd(43, oracleLd(serviceField, body))

internal fun oracleInboundFixtures(): List<Pair<String, ByteArray>> {
    val devinfo = oracleLE16(150) + oracleLE16(3_869) + oracleLE32(0x1234_5678) + oracleLE16(1_234) + oracleLE16(375) +
        oracleStr("Forerunner 165") + oracleStr("Forerunner 165") + oracleStr("FR165")
    val entry = oracleLE16(0x0102) + bytes(128, 4) + oracleLE16(7) + bytes(0, 0x10) + oracleLE32(2_048) + oracleLE32(1_100_000_000)
    val uidBody = bytes(0x09) + oracleLE32(1) + oracleLE32(0) + bytes(0x11) + oracleLE32(2) + oracleLE32(0)
    val itemRef = oracleLd(1, uidBody) +
        oracleLd(2, oracleVi(1, 3) + oracleLd(2, "FIT_TYPE_44".toByteArray()) + oracleVi(3, 7)) +
        oracleVi(3, 2_048) + oracleVi(5, 11) + oracleVi(7, 1_100_000_000)
    val itemList = oracleSmart(10, oracleVi(1, 0) + oracleVi(2, 9) + oracleVi(3, 12) + oracleLd(4, itemRef) + oracleLd(4, oracleVi(3, 1)))
    val pullResp = oracleSmart(2, oracleVi(1, 0) + oracleVi(2, 0) + oracleVi(3, 0x2018) + oracleLd(4, "/f.fit".toByteArray()) + oracleVi(5, 3))
    val added = oracleSmart(12, oracleLd(1, itemRef))
    val sync = oracleSmart(22, ByteArray(0))
    fun pb(id: Int, smart: ByteArray) =
        oracleLE16(id) + oracleLE32(0) + oracleLE32(smart.size.toLong()) + oracleLE32(smart.size.toLong()) + smart
    return listOf(
        "devinfo" to oracleFrame(5024, devinfo),
        "devinfo.addr" to oracleFrame(5024, devinfo + bytes(1) + bytes(1, 2, 3, 4, 5, 6) + bytes(0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6) + bytes(0)),
        "devinfo.short" to oracleFrame(5024, devinfo.copyOfRange(0, 20)),
        "config" to oracleFrame(5050, bytes(3, 0x39, 0x00, 0x81)),
        "config.short" to oracleFrame(5050, bytes(5, 1, 2)),
        "auth" to oracleFrame(5101, bytes(2) + oracleLE32(0x11)),
        "time" to oracleFrame(5052, oracleLE32(0xDEAD_BEEFL)),
        "sync4" to oracleFrame(5037, bytes(1, 4) + oracleLE32(0x8000_0021L)),
        "sync8" to oracleFrame(5037, bytes(0, 8) + oracleLE32(0x0400_0020) + oracleLE32(0x10)),
        "sync.bad" to oracleFrame(5037, bytes(0, 2, 1, 1)),
        "chunk" to oracleFrame(5004, bytes(0) + oracleLE16(0x1F2E) + oracleLE32(512) + bytes(0xDE, 0xAD, 0x00, 0xEF)),
        // 5054 as a Forerunner 165 sends it: the last piece of a 915-byte file (checkpoint = size, CRC 0).
        "compressed" to oracleFrame(5054, bytes(1, 2) + oracleLE32(915) + oracleLE16(0) + bytes(0x10, 0x4F, 0x21, 0xD4)),
        "compressed-short" to oracleFrame(5054, bytes(0, 0, 0, 0, 0, 0, 0)),
        "available" to oracleFrame(5009, entry),
        "available.extra" to oracleFrame(5009, entry + bytes(1)),
        "compact.available" to oracleFrame(0x8000 or 0x2A00 or 9, entry),
        "status.generic" to oracleFrame(5000, oracleLE16(5030) + bytes(0)),
        "status.nak" to oracleFrame(5000, oracleLE16(5002) + bytes(1, 9, 9)),
        "status.download" to oracleFrame(5000, oracleLE16(5002) + bytes(0, 0) + oracleLE32(4_096)),
        "status.download.short" to oracleFrame(5000, oracleLE16(5002) + bytes(0, 0)),
        "status.transfer" to oracleFrame(5000, oracleLE16(5004) + bytes(0, 1) + oracleLE32(256)),
        "status.types" to oracleFrame(5000, oracleLE16(5031) + bytes(0, 2, 128, 4) + oracleStr("FIT_TYPE_4") + bytes(255, 0) + oracleStr("Ü")),
        "status.filter" to oracleFrame(5000, oracleLE16(5007) + bytes(0, 7)),
        "status.auth" to oracleFrame(5000, oracleLE16(5101) + bytes(0, 0, 2) + oracleLE32(1)),
        "status.protobuf" to oracleFrame(5000, oracleLE16(5043) + bytes(0) + oracleLE16(3) + oracleLE32(0) + bytes(0, 0)),
        "status.short" to oracleFrame(5000, bytes(0x88)),
        "pb.itemlist" to oracleFrame(5044, pb(3, itemList)),
        "pb.pull" to oracleFrame(5044, pb(4, pullResp)),
        "pb.added" to oracleFrame(5043, pb(5, added)),
        "pb.sync" to oracleFrame(5043, pb(6, sync)),
        "pb.chunked" to oracleFrame(5043, oracleLE16(7) + oracleLE32(10) + oracleLE32(40) + oracleLE32(3) + bytes(1, 2, 3)),
        "pb.overlong" to oracleFrame(5043, oracleLE16(7) + oracleLE32(0) + oracleLE32(40) + oracleLE32(30) + bytes(1, 2, 3)),
        "battery" to oracleFrame(5023, bytes(1, 2, 3)),
        "battery.full" to oracleFrame(5023, bytes(0x10, 0xC8, 87, 0, 0, 0, 0)),
        "battery.short" to oracleFrame(5023, bytes(1, 2)),
        "unnamed" to oracleFrame(5777, bytes(9)),
    )
}

internal fun oracleProtobufFixtures(): List<Pair<String, ByteArray>> {
    val out = ArrayList<Pair<String, ByteArray>>()
    for ((name, frame) in oracleInboundFixtures()) {
        if (!name.startsWith("pb.")) continue
        val decoded = GarminGfdi.decode(frame) as? GarminGfdi.Result.Success ?: continue
        when (val m = GarminInbound.decode(decoded.frame)) {
            is GarminInbound.ProtobufRequest -> out.add(name to m.chunk.data)
            is GarminInbound.ProtobufResponse -> out.add(name to m.chunk.data)
            else -> {}
        }
    }
    out.add("pb.notfileaccess" to oracleLd(2, oracleVi(1, 1)))
    out.add("pb.otherservice" to oracleSmart(13, oracleVi(1, 1)))
    out.add("pb.truncated" to oracleSmart(10, oracleVi(1, 0)).let { it.copyOfRange(0, it.size - 1) })
    out.add("pb.group" to bytes(0x0B, 0x0C))
    out.add("pb.negerror" to oracleSmart(2, oracleVi(1, 4) + oracleVi(5, 0xFFFF_FFFFL)))
    return out
}

internal fun garminOracleLines(): List<String> {
    val out = ArrayList<String>()

    // CRC-16/ARC and the running chain.
    val crcInputs = listOf(
        "empty" to ByteArray(0), "zero" to bytes(0), "check" to "123456789".toByteArray(), "ff4" to bytes(0xFF, 0xFF, 0xFF, 0xFF),
        "seq256" to oraclePattern(256, 1, 0), "pat1000" to oraclePattern(1000, 7, 3),
    )
    for ((name, data) in crcInputs) out.add("crc $name ${oracleHex16(GarminCrc.crc16(data))}")
    val whole = oraclePattern(1000, 13, 5)
    var running = 0
    val chain = ArrayList<String>()
    var start = 0
    for (size in listOf(1, 7, 64, 128, 300, 500)) {
        running = GarminCrc.crc16(whole.copyOfRange(start, start + size), running)
        chain.add(oracleHex16(running))
        start += size
    }
    out.add("crc.chain ${chain.joinToString(",")} whole=${oracleHex16(GarminCrc.crc16(whole))}")

    // COBS.
    val cobsInputs = listOf(
        "empty" to ByteArray(0), "z1" to bytes(0), "z2" to bytes(0, 0), "mix" to bytes(0x11, 0x22, 0x00, 0x33),
        "tailzero" to bytes(0x11, 0x00), "ones253" to ByteArray(253) { 1 }, "ones254" to ByteArray(254) { 1 },
        "ones255" to ByteArray(255) { 1 }, "seq256" to oraclePattern(256, 1, 0), "pat600" to oraclePattern(600, 7, 3),
    )
    for ((name, data) in cobsInputs) {
        val enc = GarminCobs.encode(data)
        val rt = GarminCobs.decode(enc)?.contentEquals(data) == true
        val shown = if (enc.size <= 40) garminHex(enc) else "len=${enc.size} fnv=${oracleFnv32(enc)}"
        out.add("cobs $name $shown rt=$rt")
    }
    val badBodies = listOf(
        "overrun" to bytes(0x05, 0x11, 0x22), "innerzero" to bytes(0x03, 0x11, 0x00), "codezero" to bytes(0x00),
        "ff-short" to bytes(0xFF, 0x01),
    )
    for ((name, body) in badBodies) out.add("cobs.bad $name ${GarminCobs.decodeBody(body)?.let { garminHex(it) } ?: "nil"}")
    for ((name, frame) in listOf("nolead" to bytes(0x02, 0x11, 0x00), "notail" to bytes(0x00, 0x02, 0x11), "tiny" to bytes(0x00, 0x00))) {
        out.add("cobs.frame $name ${GarminCobs.decode(frame)?.let { garminHex(it) } ?: "nil"}")
    }
    val stream = bytes(0x11, 0x22) + bytes(0x00, 0x05, 0x01, 0x02) + GarminCobs.encode(bytes(0xAA, 0x00, 0xBB)) +
        GarminCobs.encode(oraclePattern(300, 3, 1)) + bytes(0x00, 0x00) + GarminCobs.encode(ByteArray(0))
    for (split in listOf(1, 3, 20, 1000)) {
        val dec = GarminCobsStreamDecoder()
        val frames = ArrayList<String>()
        var i = 0
        while (i < stream.size) {
            for (f in dec.feed(stream.copyOfRange(i, minOf(i + split, stream.size)))) {
                frames.add(if (f.size <= 8) garminHex(f) else "len${f.size}:${oracleFnv32(f)}")
            }
            i += split
        }
        out.add("cobs.stream split=$split frames=[${frames.joinToString(",")}] dropped=${dec.droppedFrames}")
    }
    val small = GarminCobsStreamDecoder(maxBodyBytes = 4)
    val oversize = small.feed(bytes(0x00, 0x06, 1, 2, 3, 4, 5, 0x00) + GarminCobs.encode(bytes(7, 8)))
    out.add("cobs.oversize frames=[${oversize.joinToString(",") { garminHex(it) }}] dropped=${small.droppedFrames}")

    // MLR header over every handle × reqNum × seq.
    var unpacked = 0
    for (h in 0 until 8) {
        val all = java.io.ByteArrayOutputStream()
        for (r in 0 until 64) {
            for (s in 0 until 64) {
                val header = GarminMlrHeader.of(h, r, s)
                all.write(header.bytes)
                if (GarminMlrHeader.parse(header.bytes) == header) unpacked++
            }
        }
        val a = all.toByteArray()
        out.add(
            "mlr.pack h=$h fnv=${oracleFnv32(a)} crc=${oracleHex16(GarminCrc.crc16(a))} head=${garminHex(a.copyOfRange(0, 12))} tail=${garminHex(a.copyOfRange(a.size - 4, a.size))}",
        )
    }
    out.add("mlr.unpack ok=$unpacked plain=${GarminMlrHeader.parse(bytes(0x7F, 0x00)) == null} short=${GarminMlrHeader.parse(bytes(0x80)) == null}")

    // The scripted MLR session (same steps as the Swift oracle).
    val mlr = GarminMlrSession(handle = 0x83, mtu = 23)
    fun show(step: String, packets: List<ByteArray>, s: GarminMlrSession): String =
        "mlr.step $step out=[" + packets.joinToString(",") {
            if (it.size <= 6) garminHex(it) else garminHex(it.copyOfRange(0, 2)) + "+${it.size - 2}"
        } + "] inflight=${s.inFlight} queued=${s.queued} window=${s.windowSize} rto=${s.retransmitTimeoutMs} next=${oracleOpt(s.nextDeadlineMs)}"
    fun peer(reqNum: Int, seq: Int, data: ByteArray): ByteArray = GarminMlrHeader.of(3, reqNum, seq).bytes + data
    fun dataOf(r: GarminMlrSession.Received) = oracleOpt(r.data?.let { garminHex(it) })
    val big = GarminOutbound.FileTransferDataReply(0, 1).frame()
    var sent = mlr.send(GarminOutboundFrame(oraclePattern(18 * 40, 5, 1)), 0)
    out.add(show("send40", sent, mlr))
    var rx = mlr.receive(peer(10, 0, ByteArray(0)), 100)
    out.add(show("ack10", rx.outbound, mlr) + " data=${dataOf(rx)}")
    sent = mlr.tick(1_099); out.add(show("tick1099", sent, mlr))
    sent = mlr.tick(1_100); out.add(show("rto1", sent, mlr))
    sent = mlr.tick(3_100); out.add(show("rto2", sent, mlr))
    rx = mlr.receive(peer(40, 0, bytes(0xA0)), 3_200)
    out.add(show("ack40+data0", rx.outbound, mlr) + " data=${dataOf(rx)}")
    sent = mlr.tick(3_220); out.add(show("acktimer", sent, mlr))
    rx = mlr.receive(peer(40, 0, bytes(0xA0)), 3_300)
    out.add(show("dup0", rx.outbound, mlr) + " data=${dataOf(rx)}")
    rx = mlr.receive(peer(40, 2, bytes(0xA2)), 3_310)
    out.add(show("gap2", rx.outbound, mlr) + " data=${dataOf(rx)}")
    for (seq in 1..5) {
        rx = mlr.receive(peer(40, seq, bytes(0xA0 + seq)), 3_320L + seq)
        out.add(show("in$seq", rx.outbound, mlr) + " data=${dataOf(rx)}")
    }
    rx = mlr.receive(peer(63, 6, ByteArray(0)), 3_400); out.add(show("bogusack", rx.outbound, mlr))
    rx = mlr.receive(peer(0, 6, ByteArray(0)), 3_500); out.add(show("ack0", rx.outbound, mlr))
    sent = mlr.send(big, 3_600); out.add(show("sendbig", sent, mlr))
    rx = mlr.receive(peer(41, 6, bytes(0xA6)), 3_610)
    out.add(show("ack41+data6", rx.outbound, mlr) + " data=${dataOf(rx)}")
    out.add("mlr.wrong-handle ${mlr.receive(GarminMlrHeader.of(2, 0, 7).bytes + bytes(1), 3_700) == GarminMlrSession.Received(null, emptyList())}")

    // ML control frames.
    for (service in GarminService.entries) {
        out.add(
            "ml.register ${service.raw} ${garminHex(GarminMultiLink.ControlRequest.Register(service, false).encode())} " +
                garminHex(GarminMultiLink.ControlRequest.Register(service, true).encode()),
        )
    }
    out.add("ml.close ${garminHex(GarminMultiLink.ControlRequest.CloseHandle(GarminService.REALTIME_HEART_RATE, 5).encode())}")
    out.add(
        "ml.closeall ${garminHex(GarminMultiLink.ControlRequest.CloseAll.encode())} " +
            garminHex(GarminMultiLink.ControlRequest.CloseAll.encode(0x0102_0304_0506_0708L)),
    )
    val client = bytes(2, 0, 0, 0, 0, 0, 0, 0)
    val controlFixtures = listOf(
        "reg.ok" to bytes(0, 1) + client + bytes(1, 0, 0, 1, 0),
        "reg.mlr" to bytes(0, 1) + client + bytes(0x18, 0x20, 0, 0x82, 2),
        "reg.fail" to bytes(0, 1) + client + bytes(6, 0, 3),
        "reg.short" to bytes(0, 1) + client + bytes(6, 0),
        "close" to bytes(0, 3) + client + bytes(6, 0, 4, 0),
        "closeall" to bytes(0, 6) + client + bytes(0, 0, 0),
        "foreign" to bytes(0, 1, 9, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0),
        "echo" to bytes(0, 0) + client + bytes(1, 0, 0),
        "unk" to bytes(0, 4) + client + bytes(1, 0, 0),
        "nothandle0" to bytes(1, 1) + client + bytes(1, 0, 0, 1, 0),
    )
    for ((name, packet) in controlFixtures) out.add("ml.parse $name ${oracleDescribe(GarminMultiLink.parseControl(packet))}")
    val routes = listOf(ByteArray(0), bytes(0x00, 0x01), bytes(0x01, 0xAA, 0xBB), bytes(0xB2, 0x41, 0xCC), bytes(0xA1, 0x00), bytes(0x85, 0x10))
    for (n in routes) out.add("ml.route ${garminHex(n)} ${oracleDescribe(GarminMultiLink.route(n, setOf(0x82, 0x83)))}")
    out.add("ml.plain ${GarminMultiLink.plainPackets(big, 3, 10).joinToString(",") { garminHex(it) }}")
    out.add(
        "ml.plain.mtu ${GarminMultiLink.plainPayloadSize(23)} ${GarminMultiLink.plainPayloadSize(515)} " +
            "${GarminMultiLink.plainPayloadSize(2)} mlr=${GarminMlrSession.payloadSize(23)}",
    )

    // GFDI framing.
    val good = oracleFrame(5024, bytes(1, 2, 3))
    val badCrc = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }
    val badLen = good.copyOf().also { it[0] = 200.toByte() }
    val gfdiCases = listOf(
        "good" to good, "compact" to oracleFrame(0x8000 or 0x7F00 or 0x32, bytes(9)), "empty" to oracleFrame(5031, ByteArray(0)),
        "badcrc" to badCrc, "oversize" to badLen, "undersize" to good.copyOfRange(0, good.size - 1), "short" to bytes(6, 0, 0x9F, 0x13, 0),
    )
    for ((name, b) in gfdiCases) out.add("gfdi $name ${oracleDescribe(GarminGfdi.decode(b))}")
    out.add("epoch ${GarminGfdi.garminTime(1_767_225_600)} ${GarminGfdi.unixTime(0)} ${GarminGfdi.garminTime(631_065_599)}")

    // Every outbound case.
    for (kind in GarminOutbound.Kind.entries) {
        for (sample in oracleOutboundSamples(kind)) {
            val frame = sample.frame().bytes
            val shown = if (frame.size <= 80) garminHex(frame) else "len=${frame.size} fnv=${oracleFnv32(frame)}"
            out.add("out ${kind.label} $shown")
        }
    }

    // Every inbound codec.
    for ((name, b) in oracleInboundFixtures()) {
        when (val r = GarminGfdi.decode(b)) {
            is GarminGfdi.Result.Success -> out.add("in $name ${oracleDescribe(GarminInbound.decode(r.frame))}")
            is GarminGfdi.Result.Failure -> out.add("in $name frame-error ${oracleDescribe(r)}")
        }
    }
    for ((name, data) in oracleProtobufFixtures()) out.add("fa $name ${oracleDescribe(GarminFileAccessMessage.decodeSmart(data))}")

    // Directory.
    val dir = java.io.ByteArrayOutputStream()
    for (i in 0 until 3) {
        dir.write(oracleLE16(i + 1)); dir.write(bytes(128, 4 + i)); dir.write(oracleLE16(i)); dir.write(bytes(0, 0))
        dir.write(oracleLE32(100L * i)); dir.write(oracleLE32(if (i == 1) 0 else 1_000_000L + i))
    }
    val d = dir.toByteArray()
    out.add("dir ${GarminDirectoryEntry.parseDirectory(d)?.joinToString(",") { oracleDescribe(it) } ?: "nil"}")
    out.add(
        "dir.bad ${GarminDirectoryEntry.parseDirectory(d.copyOfRange(0, d.size - 1))?.size?.toString() ?: "nil"} " +
            "${GarminDirectoryEntry.parseDirectory(ByteArray(0))?.size ?: -1}",
    )

    // String truncation at 255 UTF-8 bytes without splitting a code point.
    val longName = "a".repeat(254) + "é" + "z"
    val host = GarminHostIdentity(1, 2, 3, 4, 5, longName, "", "")
    val devReply = GarminOutbound.DeviceInformationReply(host, 0).message()
    out.add("str.truncate len=${devReply.size} namelen=${devReply[19].toInt() and 0xFF} fnv=${oracleFnv32(devReply)}")
    return out
}
