/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: 2026 Adbify contributors
 *
 * JNI implementation for NativeUsbBridge that connects to libusb_bridge.so.
 *
 * This file implements the JNI functions declared in NativeUsbBridge.kt
 * and calls into the C++ bridge functions exported by libusb_bridge.so.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "UsbBridgeJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declarations of the C++ bridge functions
// These are implemented in libusb_bridge.so
extern "C" {
    // Returns a vector of device names
    std::vector<std::string> usb_bridge_get_devices();
    
    // Returns a file descriptor for a device, or -1 on error
    int usb_bridge_get_fd(const std::string& device_name);
    
    // Returns serial number for a device
    std::string usb_bridge_get_serial(const std::string& device_name);
    
    // Cleanup/shutdown
    void usb_bridge_shutdown();
}

// Global state
static bool g_initialized = false;
static JavaVM* g_jvm = nullptr;

// Convert std::vector<std::string> to Java String[]
jobjectArray stringVectorToJavaArray(JNIEnv* env, const std::vector<std::string>& strings) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) {
        LOGE("Failed to find String class");
        return nullptr;
    }
    
    jobjectArray array = env->NewObjectArray(strings.size(), stringClass, nullptr);
    if (!array) {
        LOGE("Failed to create String array");
        return nullptr;
    }
    
    for (size_t i = 0; i < strings.size(); i++) {
        jstring str = env->NewStringUTF(strings[i].c_str());
        env->SetObjectArrayElement(array, i, str);
        env->DeleteLocalRef(str);
    }
    
    return array;
}

// Convert Java String to std::string
std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    
    const char* cstr = env->GetStringUTFChars(jstr, nullptr);
    if (!cstr) return "";
    
    std::string result(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    return result;
}

// JNI: Java_com_adbify_usb_NativeUsbBridge_init
extern "C" JNIEXPORT void JNICALL
Java_com_adbify_usb_NativeUsbBridge_init(JNIEnv* env, jobject /* this */) {
    LOGI("NativeUsbBridge.init() called");
    
    if (g_initialized) {
        LOGI("Already initialized");
        return;
    }
    
    // Store JVM reference
    if (env->GetJavaVM(&g_jvm) != JNI_OK) {
        LOGE("Failed to get Java VM");
        return;
    }
    
    g_initialized = true;
    LOGI("NativeUsbBridge initialized successfully");
}

// JNI: Java_com_adbify_usb_NativeUsbBridge_shutdown
extern "C" JNIEXPORT void JNICALL
Java_com_adbify_usb_NativeUsbBridge_shutdown(JNIEnv* /* env */, jobject /* this */) {
    LOGI("NativeUsbBridge.shutdown() called");
    
    if (!g_initialized) {
        LOGI("Not initialized");
        return;
    }
    
    // Call bridge shutdown if available
    try {
        usb_bridge_shutdown();
    } catch (...) {
        LOGE("Exception in bridge shutdown");
    }
    
    g_initialized = false;
    LOGI("NativeUsbBridge shutdown complete");
}

// JNI: Java_com_adbify_usb_NativeUsbBridge_enumerateDevices
extern "C" JNIEXPORT jstring JNICALL
Java_com_adbify_usb_NativeUsbBridge_enumerateDevices(JNIEnv* env, jobject /* this */) {
    LOGI("NativeUsbBridge.enumerateDevices() called");
    
    if (!g_initialized) {
        LOGE("Bridge not initialized");
        return nullptr;
    }
    
    try {
        // Get devices from bridge
        std::vector<std::string> devices = usb_bridge_get_devices();
        
        // Convert to JSON array for compatibility with existing code
        std::string json = "[";
        for (size_t i = 0; i < devices.size(); i++) {
            if (i > 0) json += ",";
            json += "\"" + devices[i] + "\"";
        }
        json += "]";
        
        LOGI("Found %zu USB devices", devices.size());
        return env->NewStringUTF(json.c_str());
        
    } catch (const std::exception& e) {
        LOGE("Exception in enumerateDevices: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("Unknown exception in enumerateDevices");
        return nullptr;
    }
}

// JNI: Java_com_adbify_usb_NativeUsbBridge_openDevice
extern "C" JNIEXPORT jlong JNICALL
Java_com_adbify_usb_NativeUsbBridge_openDevice(JNIEnv* env, jobject /* this */, jstring deviceName) {
    std::string name = jstringToString(env, deviceName);
    LOGI("NativeUsbBridge.openDevice('%s') called", name.c_str());
    
    if (!g_initialized) {
        LOGE("Bridge not initialized");
        return -1;
    }
    
    if (name.empty()) {
        LOGE("Empty device name");
        return -1;
    }
    
    try {
        // Get file descriptor from bridge
        int fd = usb_bridge_get_fd(name);
        
        if (fd < 0) {
            LOGE("Failed to open device '%s', fd=%d", name.c_str(), fd);
            return -1;
        }
        
        LOGI("Opened device '%s' with fd=%d", name.c_str(), fd);
        return static_cast<jlong>(fd);
        
    } catch (const std::exception& e) {
        LOGE("Exception in openDevice: %s", e.what());
        return -1;
    } catch (...) {
        LOGE("Unknown exception in openDevice");
        return -1;
    }
}

// JNI: Java_com_adbify_usb_NativeUsbBridge_closeDevice
extern "C" JNIEXPORT void JNICALL
Java_com_adbify_usb_NativeUsbBridge_closeDevice(JNIEnv* /* env */, jobject /* this */, jlong handle) {
    int fd = static_cast<int>(handle);
    LOGI("NativeUsbBridge.closeDevice(fd=%d) called", fd);
    
    if (fd <= 0) {
        LOGE("Invalid file descriptor: %d", fd);
        return;
    }
    
    // Close the file descriptor
    close(fd);
    LOGI("Closed device fd=%d", fd);
}

// JNI: Java_com_adbify_usb_NativeUsbBridge_claimInterface
extern "C" JNIEXPORT jint JNICALL
Java_com_adbify_usb_NativeUsbBridge_claimInterface(JNIEnv* /* env */, jobject /* this */, 
                                                   jlong handle, jint interfaceNumber) {
    int fd = static_cast<int>(handle);
    LOGI("NativeUsbBridge.claimInterface(fd=%d, interface=%d) called", fd, interfaceNumber);
    
    // On Android, interface claiming is handled by UsbDeviceConnection
    // The bridge should have already claimed the interface when opening
    // For now, just return success
    return 0;
}

// JNI: Java_com_adbify_usb_NativeUsbBridge_releaseInterface
extern "C" JNIEXPORT jint JNICALL
Java_com_adbify_usb_NativeUsbBridge_releaseInterface(JNIEnv* /* env */, jobject /* this */,
                                                     jlong handle, jint interfaceNumber) {
    int fd = static_cast<int>(handle);
    LOGI("NativeUsbBridge.releaseInterface(fd=%d, interface=%d) called", fd, interfaceNumber);
    
    // Interface release is handled when closing the device
    return 0;
}

// JNI: Java_com_adbify_usb_NativeUsbBridge_bulkTransfer
extern "C" JNIEXPORT jint JNICALL
Java_com_adbify_usb_NativeUsbBridge_bulkTransfer(JNIEnv* env, jobject /* this */,
                                                 jlong handle, jint endpoint,
                                                 jbyteArray data, jint length,
                                                 jint timeoutMs) {
    int fd = static_cast<int>(handle);
    LOGI("NativeUsbBridge.bulkTransfer(fd=%d, endpoint=0x%x, length=%d, timeout=%d) called",
         fd, endpoint, length, timeoutMs);
    
    if (fd <= 0) {
        LOGE("Invalid file descriptor: %d", fd);
        return -1;
    }
    
    if (!data) {
        LOGE("Null data buffer");
        return -1;
    }
    
    if (length <= 0) {
        LOGE("Invalid length: %d", length);
        return -1;
    }
    
    // Get data buffer
    jbyte* buffer = env->GetByteArrayElements(data, nullptr);
    if (!buffer) {
        LOGE("Failed to get byte array elements");
        return -1;
    }
    
    // For now, this is a stub implementation
    // In a real implementation, this would perform USB bulk transfer
    // using the file descriptor and Linux USB syscalls
    
    LOGI("bulkTransfer stub - would transfer %d bytes", length);
    
    // Release buffer
    env->ReleaseByteArrayElements(data, buffer, 0);
    
    // Return length to indicate success
    return length;
}

// JNI: Java_com_adbify_usb_NativeUsbBridge_getDeviceDescriptor
extern "C" JNIEXPORT jstring JNICALL
Java_com_adbify_usb_NativeUsbBridge_getDeviceDescriptor(JNIEnv* env, jobject /* this */,
                                                        jstring deviceName) {
    std::string name = jstringToString(env, deviceName);
    LOGI("NativeUsbBridge.getDeviceDescriptor('%s') called", name.c_str());
    
    if (!g_initialized) {
        LOGE("Bridge not initialized");
        return nullptr;
    }
    
    try {
        // Get serial from bridge (as a simple descriptor)
        std::string serial = usb_bridge_get_serial(name);
        
        if (serial.empty()) {
            LOGI("No serial for device '%s'", name.c_str());
            return nullptr;
        }
        
        // Return serial as a simple descriptor
        std::string descriptor = "{\"serial\": \"" + serial + "\"}";
        return env->NewStringUTF(descriptor.c_str());
        
    } catch (const std::exception& e) {
        LOGE("Exception in getDeviceDescriptor: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("Unknown exception in getDeviceDescriptor");
        return nullptr;
    }
}

// JNI_OnLoad - called when library is loaded
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    LOGI("UsbBridgeJNI JNI_OnLoad called");
    
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNIEnv");
        return JNI_ERR;
    }
    
    // Store JVM
    g_jvm = vm;
    
    LOGI("UsbBridgeJNI loaded successfully");
    return JNI_VERSION_1_6;
}

// JNI_OnUnload - called when library is unloaded
extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /* vm */, void* /* reserved */) {
    LOGI("UsbBridgeJNI JNI_OnUnload called");
    
    if (g_initialized) {
        LOGI("Shutting down bridge");
        try {
            usb_bridge_shutdown();
        } catch (...) {
            LOGE("Exception in shutdown during unload");
        }
        g_initialized = false;
    }
    
    LOGI("UsbBridgeJNI unloaded");
}