package com.noop.huawei.auth

import com.noop.huawei.protocol.HuaweiTlv

/**
 * The plaintext TLVs of the auth requests, as [HuaweiAuthMachine] sends them. Layouts are protocol facts
 * from Gadgetbridge `devices/huawei/packets/DeviceConfig.java` (AGPL-3.0, commit
 * 6e4220682feb6917a5644304d9d7c760f30b6eaf); no code is copied.
 */
internal object HuaweiAuthPackets {
    /** SecurityNegotiation 01/33. */
    fun securityNegotiation(authMode: Int, deviceId: ByteArray, phoneModel: String, encryptMethod: Int): HuaweiTlv {
        val t = HuaweiTlv().putByte(0x01, authMode)
        if (authMode == 2 || authMode == 4) t.putByte(0x02, 0x01)
        t.put(0x05, deviceId).putByte(0x03, 0x01).putByte(0x04, 0x00)
        if (authMode == 4) t.put(0x06).putString(0x07, phoneModel)
        if (encryptMethod == 1) t.putByte(0x0D, 0x01)
        return t
    }

    /** PinCode 01/2C. */
    fun pinCode(): HuaweiTlv = HuaweiTlv().put(0x01)

    /** Auth 01/13: the 32-byte challenge, then authVersion u16 + client nonce; Lite adds authAlgo. */
    fun auth(challenge: ByteArray, nonce: ByteArray, authAlgo: Int?): HuaweiTlv {
        val t = HuaweiTlv().put(0x01, challenge).put(0x02, nonce)
        if (authAlgo != null) t.putByte(0x03, authAlgo)
        return t
    }

    /** BondParams 01/0F: the bond serial and the (random) phone address string. */
    fun bondParams(serial: ByteArray, phoneAddress: ByteArray): HuaweiTlv =
        HuaweiTlv().put(0x01).put(0x03, serial).putByte(0x04, 0x02).put(0x05).put(0x07, phoneAddress).put(0x09)

    /** Bond 01/0E: the encrypted session key and its IV. */
    fun bond(serial: ByteArray, encryptedKey: ByteArray, iv: ByteArray): HuaweiTlv =
        HuaweiTlv().put(0x01).putByte(0x03, 0x00).put(0x05, serial).put(0x06, encryptedKey).put(0x07, iv)
}
