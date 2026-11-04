package com.noop.ble

import android.content.Context
import android.content.SharedPreferences
import com.noop.data.SecurePrefs
import com.noop.huawei.auth.HuaweiAuthMode
import com.noop.huawei.auth.HuaweiBondRecord
import com.noop.huawei.auth.HuaweiPhoneIdentity
import com.noop.huawei.protocol.HuaweiHex
import com.noop.huawei.protocol.HuaweiRandom

/**
 * Everything NOOP keeps for one paired Huawei band. [identity] is the random androidId / `FF:FF:FF:xx:xx:xx`
 * address NOOP presents to that band (never the phone's own identifiers); [bond] is the session key or
 * HiChain auth token from the last successful pairing, null until one completes; [accountId] is the
 * optional 17-digit Huawei account id the user typed in the wizard.
 */
data class HuaweiStoredKey(
    val identity: HuaweiPhoneIdentity,
    val bond: HuaweiBondRecord?,
    val accountId: String?,
)

/** The key-store surface the source needs, so it runs on the JVM against an in-memory double. */
interface HuaweiKeyAccess {
    fun load(deviceId: String): HuaweiStoredKey?
    fun save(deviceId: String, key: HuaweiStoredKey)
}

/**
 * At-rest-encrypted storage for Huawei band credentials, keyed by registry device id. Production opens the
 * file through [SecurePrefs] (Keystore-backed `EncryptedSharedPreferences`, the [OuraInstallKeyStore]
 * pattern); tests pass any [SharedPreferences].
 *
 * The registry keeps it in step with the device list: [move] when a device id is re-keyed, [delete] when a
 * device is forgotten, so a removed band never leaves a usable key behind.
 */
class HuaweiKeyStore(private val prefs: SharedPreferences) : HuaweiKeyAccess {

    override fun load(deviceId: String): HuaweiStoredKey? {
        val androidId = prefs.getString(k(deviceId, ANDROID_ID), null) ?: return null
        val address = prefs.getString(k(deviceId, PHONE_ADDRESS), null) ?: return null
        val identity = runCatching { HuaweiPhoneIdentity(androidId, address, PHONE_MODEL) }.getOrNull() ?: return null
        return HuaweiStoredKey(identity, loadBond(deviceId), prefs.getString(k(deviceId, ACCOUNT_ID), null))
    }

    override fun save(deviceId: String, key: HuaweiStoredKey) {
        val e = prefs.edit()
            .putString(k(deviceId, ANDROID_ID), key.identity.androidId)
            .putString(k(deviceId, PHONE_ADDRESS), key.identity.phoneAddress)
        val bond = key.bond
        if (bond == null) {
            e.remove(k(deviceId, MODE)).remove(k(deviceId, SECRET)).remove(k(deviceId, PIN))
        } else {
            e.putString(k(deviceId, MODE), bond.mode.name).putString(k(deviceId, SECRET), HuaweiHex.encode(bond.secret))
            val pin = bond.pinCode
            if (pin == null) e.remove(k(deviceId, PIN)) else e.putString(k(deviceId, PIN), HuaweiHex.encode(pin))
        }
        val account = key.accountId
        if (account == null) e.remove(k(deviceId, ACCOUNT_ID)) else e.putString(k(deviceId, ACCOUNT_ID), account)
        e.apply()
    }

    /** The stored record, or a fresh identity (no bond yet) persisted now so every later attempt reuses it. */
    fun loadOrCreate(deviceId: String, random: HuaweiRandom = HuaweiRandom.secure): HuaweiStoredKey =
        load(deviceId) ?: HuaweiStoredKey(HuaweiPhoneIdentity.generate(random, PHONE_MODEL), null, null).also { save(deviceId, it) }

    /** Store (or clear, with null) the optional account id. A value that is not 17 digits is refused. */
    fun setAccountId(deviceId: String, accountId: String?, random: HuaweiRandom = HuaweiRandom.secure): Boolean {
        val clean = accountId?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null && !isValidAccountId(clean)) return false
        save(deviceId, loadOrCreate(deviceId, random).copy(accountId = clean))
        return true
    }

    /** Re-key: the band keeps the identity it paired with, so the record follows the device to [to]. */
    fun move(from: String, to: String) {
        if (from == to) return
        val key = load(from) ?: return
        save(to, key)
        delete(from)
    }

    fun delete(deviceId: String) {
        val e = prefs.edit()
        FIELDS.forEach { e.remove(k(deviceId, it)) }
        e.apply()
    }

    private fun loadBond(deviceId: String): HuaweiBondRecord? {
        val mode = prefs.getString(k(deviceId, MODE), null)?.let { m -> HuaweiAuthMode.entries.firstOrNull { it.name == m } } ?: return null
        val secret = prefs.getString(k(deviceId, SECRET), null)?.let { runCatching { HuaweiHex.decode(it) }.getOrNull() } ?: return null
        if (secret.size != 16 && secret.size != 32) return null
        val pin = prefs.getString(k(deviceId, PIN), null)?.let { runCatching { HuaweiHex.decode(it) }.getOrNull() }
        return HuaweiBondRecord(mode, secret, pin)
    }

    private fun k(deviceId: String, field: String) = "$field.$deviceId"

    companion object {
        private const val FILE_NAME = "noop_huawei_secure_prefs"
        private const val ANDROID_ID = "androidId"
        private const val PHONE_ADDRESS = "phoneAddress"
        private const val MODE = "bondMode"
        private const val SECRET = "bondSecret"
        private const val PIN = "bondPin"
        private const val ACCOUNT_ID = "accountId"
        private val FIELDS = listOf(ANDROID_ID, PHONE_ADDRESS, MODE, SECRET, PIN, ACCOUNT_ID)

        /** The neutral label sent to HiChain3 bands in place of the phone's model. */
        const val PHONE_MODEL = "NOOP"

        /** Huawei account ids are 17 decimal digits. */
        fun isValidAccountId(value: String): Boolean = value.length == 17 && value.all { it in '0'..'9' }

        fun of(ctx: Context): HuaweiKeyStore = HuaweiKeyStore(SecurePrefs.of(ctx.applicationContext, FILE_NAME))

    }
}
