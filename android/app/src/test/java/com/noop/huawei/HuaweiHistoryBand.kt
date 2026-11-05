package com.noop.huawei

import com.noop.huawei.decode.HuaweiFixtures
import com.noop.huawei.protocol.HuaweiBytes
import com.noop.huawei.protocol.HuaweiCipher
import com.noop.huawei.protocol.HuaweiCounterIvSource
import com.noop.huawei.protocol.HuaweiFrame
import com.noop.huawei.protocol.HuaweiFrameAssembler
import com.noop.huawei.protocol.HuaweiFraming
import com.noop.huawei.protocol.HuaweiLinkConfig
import com.noop.huawei.protocol.HuaweiPacketCodec
import com.noop.huawei.protocol.HuaweiTlv

/**
 * A band holding scripted history, answering an already-authenticated session: it opens NOOP's encrypted
 * requests with the shared session key and answers encrypted, streams files as raw 05 blocks over 0x2C or
 * 0x0A, answers P2P dictionary requests as band pushes, and can stay silent or misreport on cue. Every
 * request it received is kept, decrypted, in [received].
 */
class HuaweiHistoryBand(val script: Script, sessionCipher: HuaweiCipher? = null) : FakeHuaweiLink.Peer {

    class StepRecord(val baseTs: Long, val minutes: List<Pair<Int, ByteArray>>)

    class DictRecord(val dictClass: Int, val startMs: Long, val endMs: Long, val valueId: Int, val value: Double)

    /** [pace] is one 17/0C reply container per pace index (`02`/`08` are filled in by the band). */
    class Workout(val number: Int, val totals: HuaweiTlv, val pages: List<HuaweiTlv>, val pace: List<HuaweiTlv> = emptyList())

    class Script(
        val steps: List<StepRecord> = emptyList(),
        val legacySleep: List<List<Pair<Int, ByteArray>>> = emptyList(),
        /** File name → contents; a name absent here answers "nothing". */
        val files: Map<String, ByteArray> = emptyMap(),
        val dictionary: List<DictRecord> = emptyList(),
        val dictionaryPageSize: Int = 1,
        val workouts: List<Workout> = emptyList(),
        val maxBlock: Int = 64,
        val burst: Int = 20,
        val noEncrypt: Boolean = false,
        /** Send 2C blocks inside an encryption envelope. */
        val encryptBlocks: Boolean = false,
        /** Never answer these (sid, cid). */
        val silentOn: Set<Pair<Int, Int>> = emptySet(),
        /** A workout count over a range longer than this reports one workout more than it lists. */
        val workoutListBreaksAbove: Long = Long.MAX_VALUE,
        /** Stop streaming a file after this many blocks (the rest never arrives). */
        val stallAfterBlocks: Int = Int.MAX_VALUE,
        /** 07/0B answers this result code instead of data for these step indexes. */
        val stepErrorOn: Set<Int> = emptySet(),
        /** 07/0C answers this result code (100004 on a Band 10) instead of a count. */
        val sleepCountError: Long? = null,
        /** The P2P ping goes unanswered, as on a band without the data dictionary service. */
        val pingUnavailable: Boolean = false,
        /** A 17/07 range containing this timestamp answers [workoutListError]. */
        val workoutListErrorAt: Long? = null,
        val workoutListError: Long = 0x186A4L,
    )

    class Received(val serviceId: Int, val commandId: Int, val tlv: HuaweiTlv, val encrypted: Boolean)

    val received = mutableListOf<Received>()

    private val key = ByteArray(16) { (it * 7 + 3).toByte() }
    /** The band's session cipher: [SimulatedHuaweiBand]'s after a real handshake, else a fixed test key. */
    private val cipher = sessionCipher ?: HuaweiCipher(key, useGcm = false, ivSource = HuaweiCounterIvSource(0x2000, SeededHuaweiRandom(5)))
    private val assembler = HuaweiFrameAssembler()
    private var fileIds = 0
    private val openFiles = HashMap<Int, ByteArray>()
    private var blocksSent = 0
    private var bandSeq = 100

    /** The NOOP-side link configuration for this band's session. */
    fun linkConfig() = HuaweiLinkConfig(cipher = HuaweiCipher(key, useGcm = false,
        ivSource = HuaweiCounterIvSource(0x10, SeededHuaweiRandom(6))))

    override suspend fun onFrames(frames: List<ByteArray>): List<ByteArray>? {
        val frame = frames.flatMap { assembler.feed(it) }.single()
        val raw = HuaweiTlv.parse(frame.body)
        val tlv = HuaweiPacketCodec.decodeTlv(frame, cipher)
        return answerDecoded(frame, tlv, HuaweiCipher.isEnvelope(raw))
    }

    /** One already reassembled and decrypted request (from [SimulatedHuaweiBand], which owns the session). */
    fun answerDecoded(frame: HuaweiFrame, tlv: HuaweiTlv, encrypted: Boolean): List<ByteArray>? {
        received.add(Received(frame.serviceId, frame.commandId, tlv, encrypted))
        if (frame.serviceId to frame.commandId in script.silentOn) return null
        return answer(frame, tlv)
    }

    private fun reply(sid: Int, cid: Int, tlv: HuaweiTlv): List<ByteArray> =
        HuaweiFraming.encode(sid, cid, cipher.seal(tlv).serialize(), sliced = true)

    private fun ok() = HuaweiTlv().putInt(0x7F, HuaweiTlv.RESULT_OK.toInt())

    private fun answer(frame: HuaweiFrame, t: HuaweiTlv): List<ByteArray>? {
        val sid = frame.serviceId
        val cid = frame.commandId
        return when (sid to cid) {
            0x07 to 0x17, 0x07 to 0x23, 0x07 to 0x24 -> reply(sid, cid, ok())
            0x07 to 0x0A -> reply(sid, cid, HuaweiTlv().put(0x81, HuaweiTlv().putShort(0x02, script.steps.size)))
            0x07 to 0x0B -> {
                val i = t.tlv(0x81).u16(0x02)
                if (i in script.stepErrorOn) return reply(sid, cid, HuaweiTlv().putInt(0x7F, 0x186A4))
                val r = script.steps[i]
                val c = HuaweiTlv().putShort(0x02, i).putInt(0x03, r.baseTs.toInt())
                for ((off, data) in r.minutes) c.put(0x84, HuaweiTlv().putByte(0x05, off).put(0x06, data))
                reply(sid, cid, HuaweiTlv().put(0x81, c))
            }
            0x07 to 0x0C -> if (script.sleepCountError != null) reply(sid, cid, HuaweiTlv().putInt(0x7F, script.sleepCountError.toInt()))
                else reply(sid, cid, HuaweiTlv().put(0x81, HuaweiTlv().putShort(0x02, script.legacySleep.size)))
            0x07 to 0x0D -> {
                val i = t.tlv(0x81).u16(0x02)
                val c = HuaweiTlv().putShort(0x02, i)
                for ((type, value) in script.legacySleep[i]) c.put(0x83, HuaweiTlv().putByte(0x04, type).put(0x05, value))
                reply(sid, cid, HuaweiTlv().put(0x81, c))
            }
            0x2C to 0x01 -> {
                val name = t.string(0x01)
                val bytes = script.files[name]
                val out = HuaweiTlv().putString(0x01, name).put(0x02, t.bytes(0x02))
                if (bytes == null) return reply(sid, cid, out.putByte(0x03, 0).putInt(0x04, 0))
                val id = ++fileIds
                openFiles[id] = bytes
                reply(sid, cid, out.putByte(0x03, id).putInt(0x04, bytes.size))
            }
            0x2C to 0x03 -> reply(sid, cid, HuaweiTlv().put(0x01, t.bytes(0x01)).putByte(0x02, 1)
                .putShort(0x03, script.burst).putInt(0x04, script.maxBlock).putBoolean(0x05, script.noEncrypt))
            0x2C to 0x04 -> {
                val id = t.byte(0x01)
                val offset = t.int(0x02)
                val size = t.int(0x03)
                if (blocksSent++ >= script.stallAfterBlocks) return emptyList()
                val file = openFiles.getValue(id)
                file.copyOfRange(offset, offset + size).toList().chunked(script.burst).flatMapIndexed { k, part ->
                    val body = byteArrayOf(id.toByte()) + HuaweiBytes.u32((offset + k * script.burst).toLong()) + byteArrayOf(0) + part.toByteArray()
                    val wire = if (script.encryptBlocks) cipher.seal(body).serialize() else body
                    HuaweiFraming.encode(0x2C, 0x05, wire, sliced = true)
                }
            }
            0x2C to 0x06, 0x0A to 0x06 -> null
            0x0A to 0x01 -> reply(sid, cid, HuaweiTlv().putString(0x01, script.files.keys.joinToString(";")))
            0x0A to 0x02 -> reply(sid, cid, HuaweiTlv().putShort(0x04, script.maxBlock).putShort(0x05, 1))
            0x0A to 0x03 -> reply(sid, cid, HuaweiTlv().putInt(0x02, script.files[t.string(0x01)]?.size ?: 0))
            0x0A to 0x04 -> {
                val file = script.files.getValue(t.string(0x01))
                val offset = t.int(0x02)
                val size = t.int(0x03)
                if (blocksSent++ >= script.stallAfterBlocks) return emptyList()
                file.copyOfRange(offset, offset + size).toList().chunked(script.burst).flatMapIndexed { k, part ->
                    HuaweiFraming.encode(0x0A, 0x05, byteArrayOf(k.toByte()) + part.toByteArray(), sliced = true)
                }
            }
            0x34 to 0x01 -> p2p(t)
            0x17 to 0x15 -> reply(sid, cid, HuaweiTlv().putByte(0x01, 0x02))
            0x17 to 0x07 -> {
                val c = t.tlv(0x81)
                val start = c.u32(0x03)
                val end = c.u32(0x04)
                if (script.workoutListErrorAt?.let { it in start..end } == true) {
                    return reply(sid, cid, HuaweiTlv().putInt(0x7F, script.workoutListError.toInt()))
                }
                val list = script.workouts.filter { w -> w.totals.u32(0x04) in start..end }
                val out = HuaweiTlv().putShort(0x02, list.size + if (end - start > script.workoutListBreaksAbove) 1 else 0)
                for (w in list) out.put(0x85, HuaweiTlv().putShort(0x06, w.number).putShort(0x07, w.pages.size).putShort(0x08, w.pace.size))
                reply(sid, cid, HuaweiTlv().put(0x81, out))
            }
            0x17 to 0x08 -> {
                val n = t.tlv(0x81).u16(0x02)
                reply(sid, cid, HuaweiTlv().put(0x81, script.workouts.single { it.number == n }.totals))
            }
            0x17 to 0x0A -> {
                val c = t.tlv(0x81)
                val w = script.workouts.single { it.number == c.u16(0x02) }
                reply(sid, cid, HuaweiTlv().put(0x81, w.pages[c.u16(0x03)]))
            }
            0x17 to 0x0C -> {
                val c = t.tlv(0x81)
                val w = script.workouts.single { it.number == c.u16(0x02) }
                val index = c.u16(0x08)
                val body = HuaweiTlv().putShort(0x02, w.number).putShort(0x08, index)
                for (e in w.pace[index].entries) body.put(e.tag, e.value)
                reply(sid, cid, HuaweiTlv().put(0x81, body))
            }
            else -> error("the band was sent %02X/%02X".format(sid, cid))
        }
    }

    private fun p2p(t: HuaweiTlv): List<ByteArray>? {
        val seq = t.u16(0x02)
        return when (t.byte(0x01)) {
            1 -> if (script.pingUnavailable) null else reply(0x34, 0x01, HuaweiTlv().putByte(0x01, 3).putShort(0x02, seq)
                .putString(0x03, HuaweiHistorySync.PKG_BAND_FILESYNC).putString(0x04, HuaweiHistorySync.PKG_HEALTH)
                .putInt(0x08, HuaweiHistorySync.P2P_AVAILABLE))
            2 -> {
                val req = HuaweiTlv.parse(t.bytes(0x07), 1)
                val cls = req.unsigned(0x02).toInt()
                val from = req.unsigned(0x05)
                val page = script.dictionary.filter { it.dictClass == cls && it.startMs >= from }
                    .sortedBy { it.startMs }.take(script.dictionaryPageSize)
                val block = HuaweiTlv()
                for (r in page) {
                    val value = HuaweiTlv().putInt(0x09, r.valueId).put(0x0A, HuaweiBytes.u64(java.lang.Double.doubleToLongBits(r.value)))
                    block.put(0x84, HuaweiTlv().putLong(0x05, r.startMs).putLong(0x06, r.endMs)
                        .put(0x87, HuaweiTlv().put(0x88, value)))
                }
                val body = HuaweiTlv().putByte(0x01, 1).putInt(0x02, cls)
                if (page.isNotEmpty()) body.put(0x83, block)
                reply(0x34, 0x01, HuaweiTlv().putByte(0x01, 2).putShort(0x02, ++bandSeq)
                    .putString(0x03, HuaweiHistorySync.PKG_BAND_FILESYNC).putString(0x04, HuaweiHistorySync.PKG_DICTIONARY)
                    .put(0x07, byteArrayOf(1) + body.serialize()))
            }
            else -> null
        }
    }

    companion object {
        /** One step-data minute: steps, kcal, distance, HR, and optionally SpO2 / resting HR. */
        fun minute(steps: Int? = null, kcal: Int? = null, distanceM: Int? = null, hr: Int? = null, spo2: Int? = null, rhr: Int? = null): ByteArray {
            var b1 = 0
            if (steps != null) b1 = b1 or 0x02
            if (kcal != null) b1 = b1 or 0x04
            if (distanceM != null) b1 = b1 or 0x08
            if (hr != null) b1 = b1 or 0x40
            var b2 = 0
            if (spo2 != null) b2 = b2 or 0x01
            if (rhr != null) b2 = b2 or 0x02
            if (b2 != 0) b1 = b1 or 0x80
            val out = java.io.ByteArrayOutputStream()
            out.write(b1)
            if (b2 != 0) out.write(b2)
            for (v in listOfNotNull(steps, kcal, distanceM)) out.write(HuaweiBytes.u16(v))
            hr?.let { out.write(it) }
            spo2?.let { out.write(it) }
            rhr?.let { out.write(it) }
            return out.toByteArray()
        }

        /** A version-3 `rrisqi_data.bin` with one entry per (start, end, score). */
        fun stressFile(vararg entries: Triple<Long, Long, Int>): ByteArray {
            val f = HuaweiFixtures.be().i64(0).i16(0).i16(3).padTo(48)
            for ((s, e, score) in entries) {
                f.i32(s).i32(e).i32(1).u8(score).u8(1).u8(0).u8(0).u8(1).u8(1)
                repeat(12) { f.f32(0f) }
            }
            return f.build()
        }
    }
}
