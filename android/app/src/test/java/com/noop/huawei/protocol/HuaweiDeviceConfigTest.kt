package com.noop.huawei.protocol

import com.noop.huawei.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.noop.huawei.protocol.GadgetbridgeHuaweiVectors as V

/**
 * Device Config packets. Expected literals reproduce Gadgetbridge's `packets/TestDeviceConfig.java`
 * (AGPL-3.0, commit 6e4220682feb6917a5644304d9d7c760f30b6eaf), used as protocol test vectors with
 * attribution (see ATTRIBUTION.md). Its encrypted vectors use an all-zero key and IV with AES-CBC.
 */
class HuaweiDeviceConfigTest {

    private val zeroCipher = HuaweiCipher(ByteArray(16), useGcm = false) { ByteArray(16) }
    private val codec = HuaweiPacketCodec(HuaweiLinkConfig(cipher = zeroCipher))

    private fun decode(wire: String): Pair<HuaweiFrame, HuaweiTlv> {
        val frame = HuaweiFraming.decodeSingle(hex(wire))
        return frame to codec.decodeTlv(frame)
    }

    @Test fun linkParamsRequest() {
        val request = HuaweiDeviceConfig.LinkParams.request()
        assertEquals(HuaweiTlv().put(1).put(2).put(3).put(4), request.tlv)
        assertArrayEquals(hex("5A000B0001010100020003000400F13B"), HuaweiPacketCodec().encode(request).single())
    }

    @Test fun linkParamsResponse() {
        val (frame, tlv) = decode("5A001B00010103020014051200016AA296E37641B10CF8AAF747055D0AA3E89F")
        assertEquals(0x01, frame.serviceId)
        assertEquals(0x01, frame.commandId)
        assertEquals(HuaweiTlv().put(0x03, hex("0014")).put(0x05, hex("00016AA296E37641B10CF8AAF747055D0AA3")), tlv)
        val lp = HuaweiDeviceConfig.LinkParams.decode(tlv)
        assertEquals(1, lp.authVersion)
        assertArrayEquals(hex("6AA296E37641B10CF8AAF747055D0AA3"), lp.serverNonce)
        assertEquals(0x14, lp.mtu)
        assertEquals(0xF4, lp.sliceSize)
        assertEquals(0, lp.deviceSupportType)
    }

    @Test fun linkParamsResponseWithoutTag5IsAnError() {
        val (_, tlv) = decode("5A0017000101011200016AA296E37641B10CF8AAF747055D0AA3DD41")
        assertThrows(HuaweiMissingTagException::class.java) { HuaweiDeviceConfig.LinkParams.decode(tlv) }
    }

    @Test fun linkParamsDecodesEveryTag() {
        val tlv = HuaweiTlv().putByte(1, 2).putShort(2, 0x80).putShort(3, 0x200).putShort(4, 10)
            .put(5, hex("0002") + ByteArray(16) { it.toByte() }).putByte(7, 4).putByte(8, 1).putByte(9, 1).putByte(0x0C, 1)
        val lp = HuaweiDeviceConfig.LinkParams.decode(tlv)
        assertEquals(HuaweiLinkParams(2, 0x80, 0x200, 10, 2, ByteArray(16) { it.toByte() }, 4, 1, 1, 1), lp)
    }

    @Test fun supportedServicesRequest() {
        val all = (1..0x35).toList()
        val request = HuaweiDeviceConfig.SupportedServices.request(all)
        assertEquals(HuaweiTlv().put(0x01, hex("0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F202122232425262728292A2B2C2D2E2F303132333435")), request.tlv)
        assertArrayEquals(hex(V.SUPPORTED_SERVICES_REQUEST), codec.encode(request).single())
    }

    @Test fun supportedServicesResponse() {
        val (_, tlv) = decode(V.SUPPORTED_SERVICES_RESPONSE)
        val flags = hex(V.SUPPORTED_SERVICES_FLAGS)
        assertEquals(HuaweiTlv().put(0x02, flags), tlv)
        val requested = (1..0x35).toList()
        val supported = HuaweiDeviceConfig.SupportedServices.decode(tlv, requested)
        assertEquals(flags.indices.filter { flags[it].toInt() == 1 }.map { it + 1 }.toSet(), supported)
        assertTrue(0x01 in supported)
        assertFalse(0x02 in supported)
    }

    @Test fun supportedServicesResponseErrors() {
        val (_, tlv) = decode(V.SUPPORTED_SERVICES_RESPONSE_BAD)
        assertThrows(HuaweiProtocolException::class.java) { HuaweiDeviceConfig.SupportedServices.decode(tlv, (1..0x35).toList()) }
        assertThrows(HuaweiProtocolException::class.java) {
            HuaweiDeviceConfig.SupportedServices.decode(HuaweiTlv().put(0x02, hex("0101")), listOf(1))
        }
    }

    private val commands = linkedMapOf(
        0x01 to hex("040708090A0D0E10111213141B1A1D21222324292A2B322E31303536372F"),
        0x02 to hex("010405060708"),
        0x04 to hex("01"),
    )

    @Test fun supportedCommandsRequest() {
        val requests = HuaweiDeviceConfig.SupportedCommands.requests(commands, 0xF4)
        assertEquals(1, requests.size)
        val expectedTlv = HuaweiTlv().put(
            0x81,
            HuaweiTlv().putByte(2, 1).put(3, commands.getValue(1)).putByte(2, 2).put(3, commands.getValue(2)).putByte(2, 4).put(3, commands.getValue(4)),
        )
        assertEquals(expectedTlv, requests[0].tlv)
        assertArrayEquals(hex(V.SUPPORTED_COMMANDS_REQUEST), codec.encode(requests[0]).single())
    }

    @Test fun supportedCommandsRequestSplitsBySliceBudget() {
        val many = (1..12).associateWith { ByteArray(20) { i -> (i + 1).toByte() } }
        val requests = HuaweiDeviceConfig.SupportedCommands.requests(LinkedHashMap(many), 0xF4)
        assertTrue(requests.size > 1)
        val services = requests.flatMap { r -> r.tlv.tlv(0x81).entries.filter { it.tag == 2 }.map { it.value[0].toInt() } }
        assertEquals((1..12).toList(), services)
        for (r in requests) assertEquals(1, HuaweiPacketCodec(HuaweiLinkConfig(cipher = zeroCipher)).encode(r).size)
    }

    @Test fun supportedCommandsResponse() {
        val (_, tlv) = decode(V.SUPPORTED_COMMANDS_RESPONSE)
        val body = hex(V.SUPPORTED_COMMANDS_BODY)
        assertEquals(HuaweiTlv().put(0x81, body), tlv)
        val decoded = HuaweiDeviceConfig.SupportedCommands.decode(tlv, commands)
        assertEquals(listOf(1, 2, 4), decoded.keys.toList())
        assertEquals(setOf(0x04, 0x07, 0x08, 0x09, 0x0A, 0x0D, 0x0E, 0x11, 0x12, 0x13, 0x1B, 0x1D, 0x21, 0x22), decoded[1])
        assertEquals(setOf(0x01, 0x04, 0x05, 0x06, 0x08), decoded[2])
        assertEquals(setOf(0x01), decoded[4])
    }

    @Test fun supportedCommandsResponseErrors() {
        for (wire in listOf(V.SUPPORTED_COMMANDS_RESPONSE_BAD_02, V.SUPPORTED_COMMANDS_RESPONSE_BAD_04)) {
            val (_, tlv) = decode(wire)
            assertThrows(HuaweiProtocolException::class.java) { HuaweiDeviceConfig.SupportedCommands.decode(tlv, commands) }
        }
    }

    @Test fun productInfoRequest() {
        val expected = HuaweiTlv()
        for (tag in hex("010207090A1112161A1D1E1F20212223")) expected.put(tag.toInt())
        assertEquals(expected, HuaweiDeviceConfig.ProductInfo.request().tlv)
        assertEquals(1, codec.encode(HuaweiDeviceConfig.ProductInfo.request()).size)
    }

    @Test fun productInfoResponse() {
        val (_, tlv) = decode(V.PRODUCT_INFO_RESPONSE)
        assertEquals(
            HuaweiTlv().put(0x03, hex("4E41")).put(0x07, hex("312E302E31302E3738"))
                .put(0x0A, hex("4372697573") + ByteArray(15)),
            tlv,
        )
        val info = HuaweiDeviceConfig.ProductInfo.decode(tlv)
        assertEquals("1.0.10.78", info.softwareVersion)
        assertEquals("Crius", info.productModel)
        assertEquals("NA", info.hardwareVersion)
        assertNull(info.serialNumber)
    }

    @Test fun productInfoResponseMissingRequiredTags() {
        for (wire in listOf(V.PRODUCT_INFO_RESPONSE_BAD_07, V.PRODUCT_INFO_RESPONSE_BAD_0A)) {
            val (_, tlv) = decode(wire)
            assertThrows(HuaweiMissingTagException::class.java) { HuaweiDeviceConfig.ProductInfo.decode(tlv) }
        }
    }

    @Test fun batteryExpandCapabilitiesAndSettingRelated() {
        assertEquals(HuaweiTlv().put(1), HuaweiDeviceConfig.Battery.request().tlv)
        assertEquals(HuaweiBattery(64, null, null), HuaweiDeviceConfig.Battery.decode(HuaweiTlv().putByte(1, 64)))
        assertEquals(
            HuaweiBattery(64, listOf(60, 70), listOf(1)),
            HuaweiDeviceConfig.Battery.decode(HuaweiTlv().putByte(1, 64).put(2, hex("3C46")).put(3, hex("01"))),
        )

        val caps = HuaweiDeviceConfig.ExpandCapabilities.decode(HuaweiTlv().put(1, hex("0580")))
        assertTrue(caps.has(0))
        assertFalse(caps.has(1))
        assertTrue(caps.has(2))
        assertTrue(caps.has(15))
        assertFalse(caps.has(16))
        assertFalse(caps.has(-1))
        assertFalse(HuaweiDeviceConfig.ExpandCapabilities.decode(HuaweiTlv()).has(0))

        assertEquals(HuaweiTlv().put(1).put(2).put(3).put(4).put(5).put(6), HuaweiDeviceConfig.SettingRelated.request().tlv)
        assertEquals(HuaweiSettingRelated(true, true, true), HuaweiDeviceConfig.SettingRelated.decode(HuaweiTlv().putByte(2, 0x0E)))
        assertEquals(HuaweiSettingRelated(true, false, false), HuaweiDeviceConfig.SettingRelated.decode(HuaweiTlv().putByte(2, 0x02)))
        assertEquals(HuaweiSettingRelated(false, true, true), HuaweiDeviceConfig.SettingRelated.decode(HuaweiTlv().putByte(2, 0x0C)))
        assertEquals(HuaweiSettingRelated(false, false, false), HuaweiDeviceConfig.SettingRelated.decode(HuaweiTlv()))
    }
}
