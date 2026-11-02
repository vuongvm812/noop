package com.noop.ble

/**
 * The Test Centre connection readout for one [GarminSource]: MTU, last handshake time, sessions started and
 * the last failure. Twin of the Swift `GarminConnectReadout` in Strand/BLE/GarminSource.swift.
 */
data class GarminConnectReadout(
    val mtu: Int? = null,
    val handshakeMs: Int? = null,
    val connects: Int = 0,
    val lastFailure: String? = null,
)
