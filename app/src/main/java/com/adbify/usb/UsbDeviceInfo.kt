/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: 2026 Adbify contributors
 *
 * Immutable snapshot of a connected USB device's identifying information.
 */
package com.adbify.usb

import android.hardware.usb.UsbDevice

/**
 * Lightweight, comparable snapshot of a USB device's identifying information.
 * Created from an [UsbDevice] at discovery time.
 */
data class UsbDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val productName: String?,
    val manufacturerName: String?,
    val interfaceCount: Int,
    val hasPermission: Boolean
) {
    constructor(device: UsbDevice) : this(
        deviceName = device.deviceName,
        vendorId = device.vendorId,
        productId = device.productId,
        deviceClass = device.deviceClass,
        deviceSubclass = device.deviceSubclass,
        deviceProtocol = device.deviceProtocol,
        productName = device.productName,
        manufacturerName = device.manufacturerName,
        interfaceCount = device.interfaceCount,
        hasPermission = false
    )

    /**
     * Returns a human-readable vendor:product identifier string.
     */
    fun vidPidHex(): String = String.format("%04x:%04x", vendorId, productId)

    override fun toString(): String {
        return "$deviceName (${vidPidHex()}) ${productName ?: ""}"
    }
}
