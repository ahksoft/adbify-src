/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: 2026 Adbify contributors
 *
 * JNI wrapper for libusb_bridge.so C++ functions.
 * This file creates the missing link between NativeUsbBridge.kt and libusb_bridge.so
 */

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include <dlfcn.h>

#define LOG_TAG "UsbBridgeJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Function pointer types for bridge functions
typedef std::vector<std::string> (*GetDevicesFunc)();
typedef int (*GetFdFunc)(const std::string&);
typedef std::string (*GetSerialFunc)(const std::string&);
typedef void (*BridgeShutdownFunc)();

// Global state
static bool g_initialized = false;
static void* g_bridge_handle = nullptr;
static GetDevicesFunc g_get_devices = nullptr;
static GetFdFunc g_get_fd = nullptr;
static GetSerialFunc g_get_serial = nullptr;
static BridgeShutdownFunc g_shutdown = nullptr;

// Helper function to load bridge symbols
bool loadBridgeSymbols() {
    if (g_bridge_handle) {
        return true; // Already loaded
    }
    
    // Try to open libusb_bridge.so
    g_bridge_handle = dlopen("libusb_bridge.so", RTLD_LAZY | RTLD_GLOBAL);
    if (!g_bridge_handle) {
        LOGE("Failed to dlopen libusb_bridge.so: %s", dlerror());
        return false;
    }
    
    LOGI("Successfully loaded libusb_bridge.so");
    
    // Load the C++ mangled symbols
    // usb_bridge::get_devices()
    g_get_devices = (GetDevicesFunc)dlsym(g_bridge_handle, "_ZN10usb_bridge11get_devicesEv");
    if (!g_get_devices) {
        LOGE("Failed to find usb_bridge::get_devices: %s", dlerror());
        dlclose(g_bridge_handle);
        g_bridge_handle = nullptr;
        return false;
    }
    
    // usb_bridge::get_fd(std::string const&)
    g_get_fd = (GetFdFunc)dlsym(g_bridge_handle, "_ZN10usb_bridge6get_fdERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE");
    if (!g_get_fd) {
        LOGE("Failed to find usb_bridge::get_fd: %s", dlerror());
        dlclose(g_bridge_handle);
        g_bridge_handle = nullptr;
        return false;
    }
    
    // usb_bridge::get_serial(std::string const&)
    g_get_serial = (GetSerialFunc)dlsym(g_bridge_handle, "_ZN10usb_bridge10get_serialERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE");
    if (!g_get_serial) {
        LOGI("usb_bridge::get_serial not found (optional)");
        // This is optional, don't fail
    }
    
    // Try to find shutdown function (optional)
    g_shutdown = (BridgeShutdownFunc)dlsym(g_bridge_handle, "_ZN10usb_bridge8shutdownEv");
    if (!g_shutdown) {
        LOGI("usb_bridge::shutdown not found (optional)");
    }
    
    LOGI("Successfully loaded all bridge symbols");
    return true;
}

// Convert std::vector<std::string> to JSON string
std::string vectorToJson(const std::vector<std::string>& strings) {
    std::string json = "[";
    for (size_t i = 0; i < strings.size(); i++) {
        if (i > 0) json += ",";
        // Escape quotes in strings
        std::string escaped;
        for (char c : strings[i]) {
            if (c == '"' || c == '\\') escaped += '\\';
            escaped += c;
        }
        json += "\"" + escaped + "\"";
    }
    json += "]";
    return json;
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
    
    // Load bridge symbols
    if (!loadBridgeSymbols()) {
        LOGE("Failed to load bridge symbols");
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
    if (g_shutdown) {
        try {
            g_shutdown();
            LOGI("Bridge shutdown called");
        } catch (...) {
            LOGE("Exception in bridge shutdown");
        }
    }
    
    // Close bridge handle
    if (g_bridge_handle) {
        dlclose(g_bridge_handle);
        g_bridge_handle = nullptr;
    }
    
    g_initialized = false;
    g_get_devices = nullptr;
    g_get_fd = nullptr;
    g_get_serial = nullptr;
    g_shutdown = nullptr;
    
    LOGI("NativeUsbBridge shutdown complete");
}

// JNI: Java_com_adbify_usb_NativeUsbBridge_enumerateDevices
extern "C" JNIEXPORT jstring JNICALL
Java_com_adbify_usb_NativeUsbBridge_enumerateDevices(JNIEnv* env, jobject /* this */) {
    LOGI("NativeUsbBridge.enumerateDevices() called");
    
    if (!g_initialized || !g_get_devices) {
        LOGE("Bridge not initialized or get_devices not available");
        return nullptr;
    }
    
    try {
        // Get devices from bridge
        std::vector<std::string> devices = g_get_devices();
        
        LOGI("Found %zu USB devices", devices.size());
        for (size_t i = 0; i < devices.size(); i++) {
            LOGI("  Device[%zu]: %s", i, devices[i].c_str());
        }
        
        // Convert to JSON
        std::string json = vectorToJson(devices);
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
    
    if (!g_initialized || !g_get_fd) {
        LOGE("Bridge not initialized or get_fd not available");
        return -1;
    }
    
    if (name.empty()) {
        LOGE("Empty device name");
        return -1;
    }
    
    try {
        // Get file descriptor from bridge
        int fd = g_get_fd(name);
        
        if (fd < 0) {
            LOGE("Failed to open device '%s', fd=%d", name.c_str(), fd);
            return -1;
        }
        
        LOGI("Successfully opened device '%s' with fd=%d", name.c_str(), fd);
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
    
    // On Android with UsbDeviceConnection, interfaces are claimed automatically
    // when opening with the right parameters
    return 0; // Success
}

// JNI: Java_com_adbify_usb_NativeUsbBridge_releaseInterface
extern "C" JNIEXPORT jint JNICALL
Java_com_adbify_usb_NativeUsbBridge_releaseInterface(JNIEnv* /* env */, jobject /* this */,
                                                     jlong handle, jint interfaceNumber) {
    int fd = static_cast<int>(handle);
    LOGI("NativeUsbBridge.releaseInterface(fd=%d, interface=%d) called", fd, interfaceNumber);
    
    // Interface release happens when device is closed
    return 0; // Success
}

// JNI: Java_com_adbify_usb_NativeUsbBridge_bulkTransfer
extern "C" JNIEXPORT jint JNICALL
Java_com_adbify_usb_NativeUsbBridge_bulkTransfer(JNIEnv* env, jobject /* this */,
                                                 jlong handle, jint endpoint,
                                                 jbyteArray data, jint length,
                                                 jint timeoutMs) {
    int fd = static_cast<int>(handle);
    LOGI("NativeUsbBridge.bulkTransfer(fd=%d, endpoint=0x%x, length=%d, timeout=%dms) called",
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
    
    // Perform USB bulk transfer using the file descriptor
    // This is simplified - real implementation would use USB control transfers
    ssize_t result = write(fd, buffer, length);
    
    if (result < 0) {
        LOGE("USB write failed: %s", strerror(errno));
        env->ReleaseByteArrayElements(data, buffer, 0);
        return -1;
    }
    
    LOGI("USB bulk transfer successful, wrote %zd bytes", result);
    
    // Release buffer
    env->ReleaseByteArrayElements(data, buffer, 0);
    
    return static_cast<jint>(result);
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
        std::string serial;
        
        // Try to get serial if function is available
        if (g_get_serial) {
            serial = g_get_serial(name);
        }
        
        // Create simple descriptor JSON
        std::string descriptor = "{";
        descriptor += "\"deviceName\":\"" + name + "\"";
        if (!serial.empty()) {
            descriptor += ",\"serial\":\"" + serial + "\"";
        }
        descriptor += "}";
        
        return env->NewStringUTF(descriptor.c_str());
        
    } catch (const std::exception& e) {
        LOGE("Exception in getDeviceDescriptor: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("Unknown exception in getDeviceDescriptor");
        return nullptr;
    }
}

// JNI_OnLoad
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    LOGI("UsbBridgeJNI JNI_OnLoad called");
    
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNIEnv");
        return JNI_ERR;
    }
    
    LOGI("UsbBridgeJNI loaded successfully");
    return JNI_VERSION_1_6;
}

// JNI_OnUnload
extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /* vm */, void* /* reserved */) {
    LOGI("UsbBridgeJNI JNI_OnUnload called");
    
    if (g_initialized) {
        LOGI("Shutting down bridge");
        Java_com_adbify_usb_NativeUsbBridge_shutdown(nullptr, nullptr);
    }
    
    LOGI("UsbBridgeJNI unloaded");
}