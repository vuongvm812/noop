package com.noop.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live source for a Huawei Band 10 until its protocol driver lands. It never touches the radio: a
 * scan or connect only logs that the band is not yet connectable, and [needsPairing] stays true, so the
 * coordinator holds the band as the active device without running a strap profile against it. Swift
 * twin: the coordinator's `UnavailableSource` ("not available on this platform").
 */
class HuaweiPlaceholderSource(
    private val deviceId: String,
    private val log: (String) -> Unit = {},
) : LiveHrSource {

    private val _needsPairing = MutableStateFlow(true)

    /** Always true: NOOP cannot pair this band yet. */
    val needsPairing: StateFlow<Boolean> = _needsPairing.asStateFlow()

    /** False until the Huawei driver replaces this placeholder. */
    val isConnectable: Boolean get() = false

    override fun scan() = log(line())

    override fun connect(address: String) = log(line())

    override fun stop() = Unit

    private fun line() = "$NOT_CONNECTABLE (device $deviceId)"

    companion object {
        /** The strap-log line for every scan / connect attempt. */
        const val NOT_CONNECTABLE = "huawei: needs pairing, not yet connectable"
    }
}
