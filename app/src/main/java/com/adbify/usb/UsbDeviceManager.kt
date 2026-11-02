/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: 2026 Adbify contributors
 *
 * USB device manager: handles USB device connections and provides file descriptors
 * to native code for libusb access.
 */

package com.adbify.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.system.Os
import android.util.Log
import java.lang.reflect.Method

/**
 * Manages USB device connections and provides file descriptors for native USB access.
 *
 * This class bridges Android's UsbDeviceConnection to native file descriptors
 * that can be used by libusb via libusb_wrap_sys_device().
 */
class UsbDeviceManager(private val context: Context) {
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val connections = mutableMapOf<String, UsbDeviceConnection>()
    
    companion object {
        private const val TAG = "UsbDeviceManager"
        
        @Volatile
        private var instance: UsbDeviceManager? = null
        
        fun getInstance(context: Context): UsbDeviceManager {
            return instance ?: synchronized(this) {
                instance ?: UsbDeviceManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /**
     * Opens a USB device connection by device name and returns the file descriptor.
     * Returns -1 if the connection could not be opened or if file descriptor access fails.
     */
    fun openUsbDeviceFd(deviceName: String): Int {
        // Check if we already have a connection for this device
        val existingConnection = connections[deviceName]
        if (existingConnection != null) {
            val fd = getFileDescriptor(existingConnection)
            if (fd != -1) {
                Log.i(TAG, "Reusing existing connection for device: $deviceName, fd=$fd")
                return fd
            }
        }
        
        // Find the USB device by name
        val usbDevice = usbManager.deviceList[deviceName]
        if (usbDevice == null) {
            Log.e(TAG, "USB device not found: $deviceName")
            return -1
        }
        
        // Check permission
        if (!usbManager.hasPermission(usbDevice)) {
            Log.e(TAG, "No permission to access USB device: $deviceName")
            return -1
        }
        
        // Open new connection
        val connection = usbManager.openDevice(usbDevice)
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device: $deviceName")
            return -1
        }
        
        connections[deviceName] = connection
        
        // Get file descriptor
        val fd = getFileDescriptor(connection)
        if (fd == -1) {
            Log.e(TAG, "Failed to get file descriptor for device: $deviceName")
            connections.remove(deviceName)
            connection.close()
            return -1
        }
        
        Log.i(TAG, "Opened USB device: $deviceName, fd=$fd")
        return fd
    }
    
    /**
     * Opens a USB device connection by UsbDevice object and returns the file descriptor.
     */
    fun openUsbDeviceFd(device: UsbDevice): Int {
        return openUsbDeviceFd(device.deviceName)
    }
    
    /**
     * Closes a USB device connection by device name.
     */
    fun closeUsbDevice(deviceName: String) {
        val connection = connections.remove(deviceName)
        connection?.close()
        Log.i(TAG, "Closed USB device: $deviceName")
    }
    
    /**
     * Closes all USB device connections.
     */
    fun closeAll() {
        connections.values.forEach { it.close() }
        connections.clear()
        Log.i(TAG, "Closed all USB device connections")
    }
    
    /**
     * Gets the file descriptor from a UsbDeviceConnection.
     * Uses reflection to access the protected file descriptor field.
     */
    private fun getFileDescriptor(connection: UsbDeviceConnection): Int {
        try {
            // Try to get file descriptor via reflection
            val field = connection.javaClass.getDeclaredField("mFileDescriptor")
            field.isAccessible = true
            val fileDescriptor = field.get(connection) as java.io.FileDescriptor
            
            // Get the integer file descriptor from FileDescriptor object
            val fdField = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
            fdField.isAccessible = true
            return fdField.getInt(fileDescriptor)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file descriptor via reflection: ${e.message}")
            
            // Fallback: try to get via getFileDescriptor() method if available (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val method: Method = connection.javaClass.getMethod("getFileDescriptor")
                    val fileDescriptor = method.invoke(connection) as java.io.FileDescriptor
                    
                    // Get the integer file descriptor from FileDescriptor object
                    val fdField = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                    fdField.isAccessible = true
                    return fdField.getInt(fileDescriptor)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to get file descriptor via getFileDescriptor(): ${e2.message}")
                    return -1
                }
            } else {
                return -1
            }
        }
    }
    
    /**
     * Returns a list of currently open USB device file descriptors.
     */
    fun getOpenDeviceFds(): Map<String, Int> {
        return connections.mapValues { (_, connection) ->
            getFileDescriptor(connection)
        }.filterValues { it != -1 }
    }
    
    /**
     * Checks if a USB device is currently open.
     */
    fun isDeviceOpen(deviceName: String): Boolean {
        return connections.containsKey(deviceName) && getFileDescriptor(connections[deviceName]!!) != -1
    }
}