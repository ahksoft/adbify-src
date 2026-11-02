/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: 2026 Adbify contributors
 *
 * JNI bridge to native libusb for non-root USB filesystem access.
 *
 * The native libraries (libusb.so, libusb_bridge.so) are shipped in jniLibs
 * and are placed on LD_LIBRARY_PATH during payload provisioning. This bridge
 * exposes the subset of libusb functions needed for ADB communication:
 *
 *  - libusb_init / libusb_exit
 *  - libusb_get_device_list / libusb_free_device_list
 *  - libusb_get_device_descriptor
 *  - libusb_open / libusb_close
 *  - libusb_claim_interface / libusb_release_interface
 *  - libusb_bulk_transfer (for ADB protocol AMessage/AData)
 *
 * The bridge uses the helper service pattern from green-green-avk's patch,
 * which provides a stable ABI between the app's UsbDeviceConnection and
 * the native libusb backend.
 */
package com.adbify.usb

import android.util.Log

/**
 * Native bridge for direct USB device access via libusb.
 *
 * This class loads the native `libusb_bridge` library, which wraps libusb
 * and interfaces with the app's UsbDeviceConnection to provide non-root
 * USB filesystem access. The bridge is used by `adb` for low-level USB
 * communication with devices that require it.
 */
object NativeUsbBridge {
    private const val TAG = "NativeUsbBridge"

    private const val LIB_USB = "usb"
    private const val LIB_USB_BRIDGE = "usb_bridge"

    private var initialized = false

    init {
        try {
            // Load libusb first (required by the bridge)
            System.loadLibrary(LIB_USB)
            
            // Load the USB bridge library (now contains our working implementation)
            System.loadLibrary(LIB_USB_BRIDGE)
            
            // Initialize the bridge
            init()
            initialized = true
            Log.i(TAG, "Native USB bridge initialized successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Failed to load native USB bridge: ${e.message}")
        }
    }

    /**
     * Initializes the native libusb context.
     * Must be called before any other native method.
     */
    @JvmStatic
    private external fun init()

    /**
     * Cleans up the native libusb context.
     */
    @JvmStatic
    external fun shutdown()

    /**
     * Returns a JSON array of currently connected USB devices.
     * Each entry contains: deviceName, vendorId, productId, deviceClass,
     * productName, manufacturerName
     */
    @JvmStatic
    external fun enumerateDevices(): String?

    /**
     * Opens a USB device by name and returns a handle (file descriptor).
     * Returns -1 on failure.
     */
    @JvmStatic
    external fun openDevice(deviceName: String): Long

    /**
     * Closes a previously opened USB device handle.
     */
    @JvmStatic
    external fun closeDevice(handle: Long)

    /**
     * Claims a USB interface on the opened device.
     * Returns 0 on success, negative on error.
     */
    @JvmStatic
    external fun claimInterface(handle: Long, interfaceNumber: Int): Int

    /**
     * Releases a claimed USB interface.
     */
    @JvmStatic
    external fun releaseInterface(handle: Long, interfaceNumber: Int): Int

    /**
     * Performs a bulk USB transfer.
     *
     * @param handle Device handle from [openDevice]
     * @param endpoint Endpoint address (with direction bit)
     * @param data Buffer to read from or write to
     * @param length Number of bytes to transfer
     * @param timeoutMs Timeout in milliseconds
     * @return Number of bytes transferred, or negative error code
     */
    @JvmStatic
    external fun bulkTransfer(
        handle: Long,
        endpoint: Int,
        data: ByteArray,
        length: Int,
        timeoutMs: Int
    ): Int

    /**
     * Gets the device descriptor for a given device.
     * Returns a JSON string with descriptor fields, or null on error.
     */
    @JvmStatic
    external fun getDeviceDescriptor(deviceName: String): String?

    /**
     * Returns true if the native USB bridge was successfully loaded and initialized.
     */
    fun isAvailable(): Boolean = initialized

    /**
     * Initializes the JNI connection between the bridge implementation and Java.
     * Called from the bridge implementation library.
     */
    @JvmStatic
    private external fun initBridgeJNI()
}
