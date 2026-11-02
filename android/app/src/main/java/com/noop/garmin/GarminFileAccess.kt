package com.noop.garmin

// MARK: - Protobuf wire codec + the FileAccess subset carried in 5043 / 5044
//
// Faithful Kotlin twin of Packages/GarminProtocol/Sources/GarminProtocol/GarminFileAccess.swift. A
// 5043/5044 payload is a `Smart` protobuf; field 43 is the FileAccess `Service` wrapper, of which NOOP uses
// 1 PullItemDataRequest, 2 PullItemDataResponse, 9 ItemListRequest, 10 ItemListResponse,
// 12 ItemAddedNotification and 22 SyncButtonNotification. Delete (13/14) and ModifyFlags (15/16) are
// deliberately unrepresentable. Hand-written, fields emitted in ascending number order, so both platforms
// produce identical bytes. uint64 values ride in a Long carrying the same 64 bits.

internal class GarminProtoWriter {
    private val out = java.io.ByteArrayOutputStream()
    val bytes: ByteArray get() = out.toByteArray()

    fun varint(value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    fun uint(field: Int, value: Long) {
        tag(field, 0); varint(value)
    }

    fun fixed64(field: Int, value: Long) {
        tag(field, 1)
        for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    fun lengthDelimited(field: Int, data: ByteArray) {
        tag(field, 2); varint(data.size.toLong()); out.write(data, 0, data.size)
    }

    fun string(field: Int, value: String) = lengthDelimited(field, value.toByteArray(Charsets.UTF_8))
}

/** One decoded protobuf field. Groups (wire types 3/4) are rejected. */
internal sealed class GarminProtoValue {
    class Varint(val value: Long) : GarminProtoValue()
    class Fixed64(val value: Long) : GarminProtoValue()
    class Bytes(val value: ByteArray) : GarminProtoValue()
    class Fixed32(val value: Long) : GarminProtoValue()

    val uint: Long? get() = (this as? Varint)?.value
    val u32: Long? get() = uint?.let { it and 0xFFFF_FFFFL }
    val fixed: Long? get() = (this as? Fixed64)?.value
    val data: ByteArray? get() = (this as? Bytes)?.value
    val text: String? get() = data?.let { String(it, Charsets.UTF_8) }

    /** A zigzag-encoded sint32. */
    val sint32: Int?
        get() {
            val n = uint?.toInt() ?: return null
            return (n ushr 1) xor -(n and 1)
        }
}

internal object GarminProtoReader {
    /** Split a message into (field, value) pairs in wire order, or null if malformed. */
    fun fields(data: ByteArray): List<Pair<Int, GarminProtoValue>>? {
        val out = ArrayList<Pair<Int, GarminProtoValue>>()
        var i = 0
        fun varint(): Long? {
            var result = 0L
            var shift = 0
            while (i < data.size && shift < 64) {
                val b = data[i].toInt() and 0xFF
                i++
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return null
        }
        while (i < data.size) {
            val key = varint() ?: return null
            val field = key ushr 3
            if (field <= 0L || field > Int.MAX_VALUE) return null
            when ((key and 0x07).toInt()) {
                0 -> out.add(field.toInt() to GarminProtoValue.Varint(varint() ?: return null))
                1 -> {
                    if (i + 8 > data.size) return null
                    var v = 0L
                    for (k in 0 until 8) v = v or ((data[i + k].toLong() and 0xFF) shl (8 * k))
                    i += 8
                    out.add(field.toInt() to GarminProtoValue.Fixed64(v))
                }
                2 -> {
                    val n = varint() ?: return null
                    if (n < 0 || n > (data.size - i).toLong()) return null
                    out.add(field.toInt() to GarminProtoValue.Bytes(data.copyOfRange(i, i + n.toInt())))
                    i += n.toInt()
                }
                5 -> {
                    if (i + 4 > data.size) return null
                    var v = 0L
                    for (k in 0 until 4) v = v or ((data[i + k].toLong() and 0xFF) shl (8 * k))
                    i += 4
                    out.add(field.toInt() to GarminProtoValue.Fixed32(v))
                }
                else -> return null
            }
        }
        return out
    }
}

/** A 128-bit item or flag id, carried as two fixed64 halves. */
data class GarminUid(val mostSignificant: Long, val leastSignificant: Long) {
    internal fun encode(): ByteArray {
        val w = GarminProtoWriter()
        w.fixed64(1, mostSignificant)
        w.fixed64(2, leastSignificant)
        return w.bytes
    }

    internal companion object {
        fun decode(data: ByteArray): GarminUid? {
            val fields = GarminProtoReader.fields(data) ?: return null
            var most = 0L
            var least = 0L
            for ((f, v) in fields) {
                if (f == 1) v.fixed?.let { most = it }
                if (f == 2) v.fixed?.let { least = it }
            }
            return GarminUid(most, least)
        }
    }
}

/** FileAccess `DataType`: how the watch names an item's kind. Only the fields NOOP reads are kept. */
data class GarminItemDataType(
    val format: Long? = null,
    val typeName: String? = null,
    val typeNameKey: Long? = null,
    val fileIdentifier: String? = null,
) {
    internal fun encode(): ByteArray {
        val w = GarminProtoWriter()
        format?.let { w.uint(1, it) }
        typeName?.let { w.string(2, it) }
        typeNameKey?.let { w.uint(3, it) }
        fileIdentifier?.let { w.string(4, it) }
        return w.bytes
    }

    internal companion object {
        fun decode(data: ByteArray): GarminItemDataType? {
            val fields = GarminProtoReader.fields(data) ?: return null
            var t = GarminItemDataType()
            for ((f, v) in fields) {
                t = when (f) {
                    1 -> t.copy(format = v.u32 ?: t.format)
                    2 -> t.copy(typeName = v.text ?: t.typeName)
                    3 -> t.copy(typeNameKey = v.u32 ?: t.typeNameKey)
                    4 -> t.copy(fileIdentifier = v.text ?: t.fileIdentifier)
                    else -> t
                }
            }
            return t
        }
    }
}

/** FileAccess `ItemReference`: one file on the watch. A pull request echoes it back. */
data class GarminItemReference(
    val uid: GarminUid? = null,
    val dataType: GarminItemDataType? = null,
    val dataSize: Long? = null,
    val requestedFlags: Long? = null,
    val transactionId: Long? = null,
    val urgency: Long? = null,
    val modifiedTime: Long? = null,
) {
    internal fun encode(): ByteArray {
        val w = GarminProtoWriter()
        uid?.let { w.lengthDelimited(1, it.encode()) }
        dataType?.let { w.lengthDelimited(2, it.encode()) }
        dataSize?.let { w.uint(3, it) }
        requestedFlags?.let { w.uint(4, it) }
        transactionId?.let { w.uint(5, it) }
        urgency?.let { w.uint(6, it) }
        modifiedTime?.let { w.uint(7, it) }
        return w.bytes
    }

    internal companion object {
        fun decode(data: ByteArray): GarminItemReference? {
            val fields = GarminProtoReader.fields(data) ?: return null
            var r = GarminItemReference()
            for ((f, v) in fields) {
                r = when (f) {
                    1 -> v.data?.let { r.copy(uid = GarminUid.decode(it) ?: return null) } ?: r
                    2 -> v.data?.let { r.copy(dataType = GarminItemDataType.decode(it) ?: return null) } ?: r
                    3 -> r.copy(dataSize = v.uint ?: r.dataSize)
                    4 -> r.copy(requestedFlags = v.u32 ?: r.requestedFlags)
                    5 -> r.copy(transactionId = v.u32 ?: r.transactionId)
                    6 -> r.copy(urgency = v.u32 ?: r.urgency)
                    7 -> r.copy(modifiedTime = v.u32 ?: r.modifiedTime)
                    else -> r
                }
            }
            return r
        }
    }
}

/** The FileAccess requests NOOP may send. Read-only by construction: listing and pulling only. */
sealed class GarminFileAccessRequest {
    /** `ItemListRequest`; excludeAllFlags / getFlags filter by item flag ids. */
    data class ItemList(
        val sessionId: Long?,
        val transactionId: Long?,
        val maxItemCount: Long?,
        val excludeAllFlags: List<GarminUid>,
        val getFlags: List<GarminUid>,
    ) : GarminFileAccessRequest()

    /** `PullItemDataRequest` over the multi-link pipe transport (0). */
    data class PullItem(
        val item: GarminItemReference,
        val priority: Long?,
        val offset: Long?,
        val compressionWindow: Long?,
    ) : GarminFileAccessRequest()

    /** The complete `Smart` protobuf bytes for a 5043 payload. */
    internal fun encodeSmart(): ByteArray {
        val inner = GarminProtoWriter()
        val service = GarminProtoWriter()
        when (this) {
            is ItemList -> {
                sessionId?.let { inner.uint(1, it) }
                transactionId?.let { inner.uint(2, it) }
                maxItemCount?.let { inner.uint(3, it) }
                for (flag in excludeAllFlags) inner.lengthDelimited(4, flag.encode())
                for (flag in getFlags) inner.lengthDelimited(5, flag.encode())
                service.lengthDelimited(9, inner.bytes)
            }
            is PullItem -> {
                inner.lengthDelimited(1, item.encode())
                priority?.let { inner.uint(2, it) }
                inner.uint(3, MULTILINK_TRANSPORT)
                offset?.let { inner.uint(4, it) }
                compressionWindow?.let { inner.uint(5, it) }
                service.lengthDelimited(1, inner.bytes)
            }
        }
        val smart = GarminProtoWriter()
        smart.lengthDelimited(SMART_FILE_ACCESS_FIELD, service.bytes)
        return smart.bytes
    }

    internal companion object {
        const val SMART_FILE_ACCESS_FIELD = 43
        const val MULTILINK_TRANSPORT = 0L
    }
}

/** The FileAccess messages NOOP reads from the watch. */
sealed class GarminFileAccessMessage {
    data class ItemListResponse(
        val status: Long?,
        val sessionId: Long?,
        val nextTransactionId: Long?,
        val items: List<GarminItemReference>,
        val errorCode: Long?,
    ) : GarminFileAccessMessage()

    data class PullItemResponse(
        val result: Long?,
        val transport: Long?,
        val transferHandle: Long?,
        val filePath: String?,
        val errorCode: Int?,
    ) : GarminFileAccessMessage()

    data class ItemAdded(val items: List<GarminItemReference>) : GarminFileAccessMessage()
    object SyncButton : GarminFileAccessMessage()
    data class Other(val field: Int) : GarminFileAccessMessage()

    companion object {
        /**
         * Decode a `Smart` protobuf. Null when malformed or carrying no FileAccess field (some other Smart
         * service, e.g. an HTTP proxy request, which the caller answers as unsupported).
         */
        fun decodeSmart(smart: ByteArray): GarminFileAccessMessage? {
            val fields = GarminProtoReader.fields(smart) ?: return null
            val service = fields.lastOrNull { it.first == GarminFileAccessRequest.SMART_FILE_ACCESS_FIELD }?.second?.data
                ?: return null
            val (field, value) = GarminProtoReader.fields(service)?.firstOrNull() ?: return null
            val body = value.data ?: return null
            val inner = GarminProtoReader.fields(body) ?: return null
            return when (field) {
                10 -> {
                    var status: Long? = null
                    var session: Long? = null
                    var next: Long? = null
                    var error: Long? = null
                    val items = ArrayList<GarminItemReference>()
                    for ((f, v) in inner) {
                        when (f) {
                            1 -> status = v.u32 ?: status
                            2 -> session = v.u32 ?: session
                            3 -> next = v.u32 ?: next
                            4 -> items.add(GarminItemReference.decode(v.data ?: return null) ?: return null)
                            5 -> error = v.u32 ?: error
                        }
                    }
                    ItemListResponse(status, session, next, items, error)
                }
                2 -> {
                    var result: Long? = null
                    var transport: Long? = null
                    var handle: Long? = null
                    var path: String? = null
                    var error: Int? = null
                    for ((f, v) in inner) {
                        when (f) {
                            1 -> result = v.u32 ?: result
                            2 -> transport = v.u32 ?: transport
                            3 -> handle = v.u32 ?: handle
                            4 -> path = v.text ?: path
                            5 -> error = v.sint32 ?: error
                        }
                    }
                    PullItemResponse(result, transport, handle, path, error)
                }
                12 -> {
                    val items = ArrayList<GarminItemReference>()
                    for ((f, v) in inner) {
                        if (f == 1) items.add(GarminItemReference.decode(v.data ?: return null) ?: return null)
                    }
                    ItemAdded(items)
                }
                22 -> SyncButton
                else -> Other(field)
            }
        }
    }
}
