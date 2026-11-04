package com.noop.ble

import com.noop.huawei.SeededHuaweiRandom
import com.noop.huawei.protocol.HuaweiCipher
import com.noop.huawei.protocol.HuaweiCommand
import com.noop.huawei.protocol.HuaweiCounterIvSource
import com.noop.huawei.protocol.HuaweiFrameAssembler
import com.noop.huawei.protocol.HuaweiFraming
import com.noop.huawei.protocol.HuaweiLinkConfig
import com.noop.huawei.protocol.HuaweiPacketCodec
import com.noop.huawei.protocol.HuaweiRawPush
import com.noop.huawei.protocol.HuaweiRequest
import com.noop.huawei.protocol.HuaweiTlv
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The GATT link's history-sync paths: raw file blocks reach [HuaweiGattLink.rawPushes], opened, and [HuaweiGattLink.send] writes without waiting. */
class HuaweiGattLinkRawTest {

    private val key = ByteArray(16) { it.toByte() }
    private fun cipher(seed: Long) = HuaweiCipher(key, useGcm = false, ivSource = HuaweiCounterIvSource(seed, SeededHuaweiRandom(seed)))

    @Test
    fun plainAndEncryptedBlocksArriveAsRawPushes() = runBlocking {
        val link = HuaweiGattLink(write = { true })
        link.configure(HuaweiLinkConfig(cipher = cipher(1)))
        val plain = byteArrayOf(7, 0, 0, 0, 0, 0, 1, 2, 3)
        val secret = byteArrayOf(7, 0, 0, 0, 3, 0, 4, 5)
        val got = async(start = CoroutineStart.UNDISPATCHED) { link.rawPushes.take(3).toList() }
        HuaweiFraming.encode(0x2C, 0x05, plain, sliced = true).forEach(link::onNotification)
        HuaweiFraming.encode(0x2C, 0x05, cipher(9).seal(secret).serialize(), sliced = true).forEach(link::onNotification)
        HuaweiFraming.encode(0x0A, 0x05, byteArrayOf(0, 42), sliced = true).forEach(link::onNotification)
        val pushes: List<HuaweiRawPush> = got.await()
        assertArrayEquals(plain, pushes[0].body)
        assertArrayEquals(secret, pushes[1].body)
        assertEquals(0x0A to 0x05, pushes[2].serviceId to pushes[2].commandId)
        assertArrayEquals(byteArrayOf(0, 42), pushes[2].body)
    }

    @Test
    fun sendWritesTheRequestWithoutWaitingForAReply() = runBlocking {
        val written = mutableListOf<ByteArray>()
        val link = HuaweiGattLink(write = { written.add(it) })
        link.configure(HuaweiLinkConfig(cipher = cipher(2)))
        link.send(HuaweiRequest.of(HuaweiCommand.FILE2C_DONE, HuaweiTlv().putByte(0x01, 3).putByte(0x02, 1)))
        val frame = written.flatMap { HuaweiFrameAssembler().feed(it) }.single()
        assertEquals(0x2C to 0x06, frame.serviceId to frame.commandId)
        assertEquals(3, HuaweiPacketCodec.decodeTlv(frame, cipher(3)).byte(0x01))
    }
}
