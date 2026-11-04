package com.noop.ble

import android.content.SharedPreferences
import com.noop.huawei.SeededHuaweiRandom
import com.noop.huawei.auth.HuaweiAuthMode
import com.noop.huawei.auth.HuaweiBondRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiKeyStoreTest {

    private val prefs = MapPrefs()
    private val store = HuaweiKeyStore(prefs)

    @Test fun storesAndLoadsAKeyWithItsIdentity() {
        val created = store.loadOrCreate("huawei-A", SeededHuaweiRandom(1))
        assertTrue(Regex("[0-9A-F]{32}").matches(created.identity.androidId))
        assertTrue(Regex("FF:FF:FF:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}").matches(created.identity.phoneAddress))
        assertNull(created.bond)
        val key = ByteArray(16) { it.toByte() }
        store.save("huawei-A", created.copy(bond = HuaweiBondRecord(HuaweiAuthMode.NORMAL, key, byteArrayOf(1, 2, 3))))
        val back = store.load("huawei-A")!!
        assertEquals(created.identity, back.identity)
        assertEquals(HuaweiAuthMode.NORMAL, back.bond!!.mode)
        assertArrayEquals(key, back.bond!!.secret)
        assertArrayEquals(byteArrayOf(1, 2, 3), back.bond!!.pinCode)
        // A second loadOrCreate keeps the identity the band already knows.
        assertEquals(created.identity, store.loadOrCreate("huawei-A", SeededHuaweiRandom(2)).identity)
    }

    @Test fun storesA32ByteHiChainToken() {
        val base = store.loadOrCreate("huawei-B", SeededHuaweiRandom(1))
        val token = ByteArray(32) { (it * 7).toByte() }
        store.save("huawei-B", base.copy(bond = HuaweiBondRecord(HuaweiAuthMode.HICHAIN3, token, null)))
        assertArrayEquals(token, store.load("huawei-B")!!.bond!!.secret)
        assertNull(store.load("huawei-B")!!.bond!!.pinCode)
    }

    @Test fun malformedSecretReadsAsNoBond() {
        store.loadOrCreate("huawei-C", SeededHuaweiRandom(1))
        prefs.edit().putString("bondMode.huawei-C", "NORMAL").putString("bondSecret.huawei-C", "ABCD").apply()
        assertNotNull(store.load("huawei-C"))
        assertNull(store.load("huawei-C")!!.bond)
    }

    @Test fun accountIdMustBe17Digits() {
        assertTrue(store.setAccountId("huawei-D", "12345678901234567", SeededHuaweiRandom(1)))
        assertEquals("12345678901234567", store.load("huawei-D")!!.accountId)
        assertFalse(store.setAccountId("huawei-D", "1234", SeededHuaweiRandom(1)))
        assertFalse(store.setAccountId("huawei-D", "1234567890123456a", SeededHuaweiRandom(1)))
        assertEquals("12345678901234567", store.load("huawei-D")!!.accountId)
        assertTrue(store.setAccountId("huawei-D", "  ", SeededHuaweiRandom(1)))
        assertNull(store.load("huawei-D")!!.accountId)
    }

    @Test fun reKeyMovesEverythingAndLeavesNothingBehind() {
        val base = store.loadOrCreate("huawei-old", SeededHuaweiRandom(1))
        store.save("huawei-old", base.copy(bond = HuaweiBondRecord(HuaweiAuthMode.HICHAIN_LITE, ByteArray(16) { 9 }, null), accountId = "12345678901234567"))
        store.move("huawei-old", "huawei-new")
        assertNull(store.load("huawei-old"))
        val moved = store.load("huawei-new")!!
        assertEquals(base.identity, moved.identity)
        assertEquals(HuaweiAuthMode.HICHAIN_LITE, moved.bond!!.mode)
        assertEquals("12345678901234567", moved.accountId)
        assertTrue(prefs.map.keys.none { it.endsWith(".huawei-old") })
    }

    @Test fun deleteRemovesEveryField() {
        val base = store.loadOrCreate("huawei-E", SeededHuaweiRandom(1))
        store.save("huawei-E", base.copy(bond = HuaweiBondRecord(HuaweiAuthMode.NORMAL, ByteArray(16), byteArrayOf(5)), accountId = "12345678901234567"))
        store.loadOrCreate("huawei-F", SeededHuaweiRandom(2))
        store.delete("huawei-E")
        assertNull(store.load("huawei-E"))
        assertTrue(prefs.map.keys.none { it.endsWith(".huawei-E") })
        assertNotNull("other bands untouched", store.load("huawei-F"))
    }

    /** In-memory [SharedPreferences] (the encrypted file is Android-only). */
    private class MapPrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = map[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private val removes = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes.add(key) }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                removes.forEach { map.remove(it) }
                map.putAll(puts)
            }
        }
    }
}
