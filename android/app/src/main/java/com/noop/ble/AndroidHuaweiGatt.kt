package com.noop.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * The Android radio under [HuaweiSource]: `connectGatt(TRANSPORT_LE)`, MTU request, service FE86, FE02
 * notifications on, FE01 writes queued in order. Every callback is posted to the main looper.
 *
 * Never bonds: there is no `createBond` here and no reaction to the OS bond state. If the band's GATT
 * needed an encrypted link Android would raise its own prompt; a refusal shows up as a failed subscribe
 * and is reported as that, nothing more.
 */
@SuppressLint("MissingPermission")
class AndroidHuaweiGatt(context: Context, private val log: (String) -> Unit = {}) : HuaweiGatt {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var events: HuaweiGatt.Events? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var mtu = DEFAULT_MTU

    override fun open(address: String, events: HuaweiGatt.Events) = onMain {
        closeNow()
        this.events = events
        val a = adapter
        if (a == null || !a.isEnabled) return@onMain fail("Bluetooth is off")
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return@onMain fail("invalid band address")
        gatt = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, callback)
            }
        }.getOrNull()
        if (gatt == null) fail("connectGatt failed")
    }

    override fun write(value: ByteArray): Boolean {
        if (gatt == null || writeChar == null) return false
        onMain {
            queue.addLast(value)
            drain()
        }
        return true
    }

    override fun close() = onMain { closeNow() }

    private fun closeNow() {
        events = null
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        writeChar = null
        notifyChar = null
        queue.clear()
        writeInFlight = false
        mtu = DEFAULT_MTU
    }

    private fun fail(reason: String) {
        val e = events
        closeNow()
        e?.onLinkDown(reason)
    }

    private fun drain() {
        if (writeInFlight) return
        val g = gatt ?: return
        val c = writeChar ?: return
        val next = queue.removeFirstOrNull() ?: return
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        writeInFlight = true
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, next, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { c.writeType = type; c.value = next; g.writeCharacteristic(c) }
        }
        if (!started) {
            // The stack is busy: keep the slice at the head and retry, so slices never reorder.
            writeInFlight = false
            queue.addFirst(next)
            handler.postDelayed({ drain() }, WRITE_RETRY_MS)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = onMain {
            if (g !== gatt) return@onMain
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> fail("disconnected (status=$status)")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) = onMain {
            if (g !== gatt) return@onMain
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else DEFAULT_MTU
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onMain {
            if (g !== gatt) return@onMain
            val service = g.getService(SERVICE) ?: return@onMain fail("service FE86 not found")
            writeChar = service.getCharacteristic(WRITE)
            notifyChar = service.getCharacteristic(NOTIFY)
            val n = notifyChar ?: return@onMain fail("notify characteristic FE02 not found")
            if (writeChar == null) return@onMain fail("write characteristic FE01 not found")
            g.setCharacteristicNotification(n, true)
            val cccd = n.getDescriptor(CCCD) ?: return@onMain fail("FE02 has no CCCD")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = onMain {
            if (g !== gatt || descriptor.uuid != CCCD) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) return@onMain fail("enabling FE02 notifications failed (status=$status)")
            log("Huawei: notifications on (MTU $mtu)")
            events?.onLinkUp(mtu)
        }

        @Deprecated("API < 33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            received(g, characteristic, value.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            received(g, characteristic, value.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = onMain {
            if (g !== gatt) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) log("Huawei: write failed (status=$status)")
            writeInFlight = false
            drain()
        }
    }

    private fun received(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = onMain {
        if (g !== gatt || characteristic.uuid != NOTIFY) return@onMain
        events?.onNotification(value)
    }

    private fun onMain(block: () -> Unit) {
        handler.post { runCatching(block).onFailure { log("Huawei: GATT callback error (${it.javaClass.simpleName}: ${it.message})") } }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("0000fe86-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val REQUESTED_MTU = 512
        private const val DEFAULT_MTU = 23
        private const val WRITE_RETRY_MS = 20L
    }
}
