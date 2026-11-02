/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: 2026 Adbify contributors
 *
 * USB host manager: detects attached USB devices, requests permission, and
 * integrates with the native libusb bridge for non-root USB filesystem access.
 */
package com.adbify.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Manages USB device detection and permission flow.
 *
 * On Android, raw USB device access requires:
 *  1. Declaring `android.hardware.usb.host` in the manifest
 *  2. Requesting permission via `UsbManager.requestPermission()` with a PendingIntent
 *  3. Opening a `UsbDeviceConnection` to access endpoints
 *
 * This manager handles steps 2-3 and notifies registered listeners when devices
 * are attached, detached, or granted permission.
 */
class UsbHostManager private constructor(private val context: Context) {
    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as UsbManager?

    private val listeners = mutableSetOf<UsbHostListener>()
    private val connectedDevices = mutableMapOf<String, UsbDeviceInfo>()

    private val permissionIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { onDeviceAttached(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { onDeviceDetached(it) }
                }
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    device?.let {
                        if (granted) {
                            onPermissionGranted(it)
                        } else {
                            onPermissionDenied(it)
                        }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(usbReceiver, filter)
        }
    }

    /**
     * Scans for currently connected USB devices and requests permission for those
     * that haven't been granted yet.
     */
    fun scanAndRequestPermissions() {
        if (usbManager == null) return

        for (device in usbManager.deviceList.values) {
            if (!usbManager.hasPermission(device)) {
                requestPermission(device)
            } else {
                onDeviceAttached(device)
            }
        }
    }

    /**
     * Returns a list of all currently connected USB devices.
     */
    fun listDevices(): List<UsbDeviceInfo> {
        val result = mutableListOf<UsbDeviceInfo>()
        if (usbManager == null) return result

        for (device in usbManager.deviceList.values) {
            result.add(UsbDeviceInfo(device))
        }
        return result
    }

    /**
     * Returns true if the app has permission to access the given USB device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager?.hasPermission(device) ?: false
    }

    fun hasPermission(device: UsbDeviceInfo): Boolean {
        val usbManager = usbManager ?: return false
        val usbDevice = usbManager.deviceList[device.deviceName] ?: return false
        return usbManager.hasPermission(usbDevice)
    }

    /**
     * Requests permission for a USB device.
     */
    fun requestPermission(device: UsbDevice) {
        if (usbManager == null) return
        usbManager.requestPermission(device, permissionIntent)
        Log.i(TAG, "Requesting permission for device: ${device.deviceName}")
    }

    fun requestPermission(device: UsbDeviceInfo) {
        val usbManager = usbManager ?: return
        val usbDevice = usbManager.deviceList[device.deviceName] ?: return
        requestPermission(usbDevice)
    }

    /**
     * Opens a USB device connection for direct endpoint access.
     */
    fun openDevice(device: UsbDevice): UsbDeviceConnection? {
        val mgr = usbManager ?: return null
        return mgr.openDevice(device)
    }

    private fun onDeviceAttached(device: UsbDevice) {
        val info = UsbDeviceInfo(device)
        connectedDevices[device.deviceName] = info
        listeners.forEach { it.onDeviceAttached(info) }
    }

    private fun onDeviceDetached(device: UsbDevice) {
        connectedDevices.remove(device.deviceName)
        listeners.forEach { it.onDeviceDetached(device.deviceName) }
    }

    private fun onPermissionGranted(device: UsbDevice) {
        val info = connectedDevices[device.deviceName] ?: UsbDeviceInfo(device)
        listeners.forEach { it.onPermissionGranted(info) }
    }

    private fun onPermissionDenied(device: UsbDevice) {
        listeners.forEach { it.onPermissionDenied(device.deviceName) }
    }

    fun registerListener(listener: UsbHostListener) {
        listeners.add(listener)
    }

    fun unregisterListener(listener: UsbHostListener) {
        listeners.remove(listener)
    }

    fun shutdown() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // already unregistered
        }
    }

    interface UsbHostListener {
        fun onDeviceAttached(device: UsbDeviceInfo)
        fun onDeviceDetached(deviceName: String)
        fun onPermissionGranted(device: UsbDeviceInfo)
        fun onPermissionDenied(deviceName: String)
    }

    companion object {
        private const val TAG = "UsbHostManager"
        private const val ACTION_USB_PERMISSION = "com.adbify.USB_PERMISSION"

        @Volatile
        private var instance: UsbHostManager? = null

        fun getInstance(context: Context): UsbHostManager {
            return instance ?: synchronized(this) {
                instance ?: UsbHostManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
