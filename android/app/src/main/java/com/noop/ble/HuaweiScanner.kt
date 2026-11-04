package com.noop.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pairing wizard's discovery-only scan for a Huawei Band 10: lists bands advertising service FE86 or
 * a "HUAWEI Band 10" name. Never connects and persists nothing; the source owns the connection once the
 * band is registered.
 */
@SuppressLint("MissingPermission")
class HuaweiScanner(context: Context, private val log: (String) -> Unit = {}) {

    data class DiscoveredBand(val address: String, val name: String, val rssi: Int)

    private val appContext = context.applicationContext
    private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private val _discovered = MutableStateFlow<List<DiscoveredBand>>(emptyList())
    val discovered: StateFlow<List<DiscoveredBand>> = _discovered.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    fun scan() {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
            log("Huawei: scan deferred until Bluetooth is on")
            return
        }
        _discovered.value = emptyList()
        _scanning.value = true
        log("Huawei: scanning for a Huawei Band 10 (service FE86 or its name)")
        runCatching {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        }.onFailure { _scanning.value = false }
    }

    fun stopScan() {
        _scanning.value = false
        val a = adapter ?: return
        if (a.isEnabled) runCatching { a.bluetoothLeScanner?.stopScan(callback) }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val record = result.scanRecord
            val name = record?.deviceName ?: runCatching { device.name }.getOrNull() ?: ""
            val advertisesService = record?.serviceUuids?.contains(SERVICE) == true
            if (!isHuaweiBand(name, advertisesService)) return
            handler.post {
                if (_discovered.value.none { it.address == device.address }) {
                    _discovered.value = _discovered.value + DiscoveredBand(device.address, name.ifBlank { "HUAWEI Band 10" }, result.rssi)
                    log("Huawei: found ${if (name.isBlank()) "a band" else logSafeDeviceName(name)} rssi=${result.rssi}")
                }
            }
        }
    }

    companion object {
        private val SERVICE = ParcelUuid(AndroidHuaweiGatt.SERVICE)

        /** The wizard's filter: the FE86 service in the advertisement, or a recognised Band 10 name. */
        fun isHuaweiBand(name: String, advertisesService: Boolean): Boolean =
            advertisesService || ExperimentalBrand.recognise(name) == ExperimentalBrand.HUAWEI
    }
}
