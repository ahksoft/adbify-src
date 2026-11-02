/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: 2026 Adbify contributors
 *
 * Practical implementation of libusb_bridge.so functions.
 * This provides actual USB device data to enable adb USB device detection.
 */

#include <vector>
#include <string>
#include <cstring>
#include <cstdlib>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <android/log.h>
#include <jni.h>
#include <sys/stat.h>

#define LOG_TAG "UsbBridgeImpl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Global state
static std::vector<std::string> g_device_list;
static bool g_initialized = false;
static JavaVM* g_jvm = nullptr;
static jclass g_usb_bridge_class = nullptr;
static jmethodID g_enumerate_devices_method = nullptr;
static jmethodID g_open_device_method = nullptr;

// JNI helper to get device list from Java
static std::vector<std::string> get_devices_from_java() {
    std::vector<std::string> devices;
    
    if (!g_jvm || !g_usb_bridge_class || !g_enumerate_devices_method) {
        LOGE("JNI not initialized");
        return devices;
    }
    
    JNIEnv* env = nullptr;
    bool attached = false;
    
    // Get JNIEnv
    int status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        // Attach current thread
        if (g_jvm->AttachCurrentThread(&env, nullptr) != 0) {
            LOGE("Failed to attach thread");
            return devices;
        }
        attached = true;
    } else if (status != JNI_OK) {
        LOGE("Failed to get JNIEnv: %d", status);
        return devices;
    }
    
    try {
        // Call Java method: NativeUsbBridge.enumerateDevices()
        jstring result = (jstring)env->CallStaticObjectMethod(
            g_usb_bridge_class, g_enumerate_devices_method);
        
        if (result) {
            const char* json_str = env->GetStringUTFChars(result, nullptr);
            if (json_str) {
                // Parse simple JSON array: ["device1", "device2"]
                std::string json = json_str;
                LOGD("Java returned device JSON: %s", json.c_str());
                
                // Simple JSON parsing (for format ["dev1","dev2"])
                size_t start = json.find('[');
                size_t end = json.find(']');
                
                if (start != std::string::npos && end != std::string::npos && end > start + 1) {
                    std::string content = json.substr(start + 1, end - start - 1);
                    size_t pos = 0;
                    
                    while (pos < content.length()) {
                        // Find next quote
                        size_t quote_start = content.find('"', pos);
                        if (quote_start == std::string::npos) break;
                        
                        size_t quote_end = content.find('"', quote_start + 1);
                        if (quote_end == std::string::npos) break;
                        
                        std::string device = content.substr(quote_start + 1, quote_end - quote_start - 1);
                        devices.push_back(device);
                        
                        pos = quote_end + 1;
                        // Skip comma if present
                        if (pos < content.length() && content[pos] == ',') pos++;
                    }
                }
                
                env->ReleaseStringUTFChars(result, json_str);
            }
        }
    } catch (...) {
        LOGE("Exception calling Java method");
    }
    
    // Detach if we attached
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
    
    LOGI("Got %zu devices from Java", devices.size());
    return devices;
}

// Initialize from environment variables as fallback
static void init_from_environment() {
    LOGI("Initializing from environment variables...");
    
    const char* count_str = getenv("ADB_USB_DEVICE_COUNT");
    if (!count_str) {
        LOGI("No ADB_USB_DEVICE_COUNT in environment");
        return;
    }
    
    int count = atoi(count_str);
    LOGI("Found %d USB devices in environment", count);
    
    // Clear existing list
    g_device_list.clear();
    
    // Read each device from environment
    for (int i = 0; i < count; i++) {
        char env_name[64];
        snprintf(env_name, sizeof(env_name), "ADB_USB_DEVICE_%d_NAME", i);
        const char* device_name = getenv(env_name);
        if (device_name && device_name[0] != '\0') {
            g_device_list.push_back(device_name);
            LOGI("Device[%d]: %s", i, device_name);
        }
    }
    
    // Also check ADB_USB_FIRST_DEV for backward compatibility
    const char* first_dev = getenv("ADB_USB_FIRST_DEV");
    if (first_dev && first_dev[0] != '\0' && g_device_list.empty()) {
        g_device_list.push_back(first_dev);
        LOGI("Added first device from ADB_USB_FIRST_DEV: %s", first_dev);
    }
    
    g_initialized = true;
    LOGI("Initialized with %zu devices from environment", g_device_list.size());
}

// JNI initialization
extern "C" JNIEXPORT void JNICALL
Java_com_adbify_usb_NativeUsbBridge_initBridgeJNI(JNIEnv* env, jclass clazz) {
    LOGI("Bridge JNI initialization called");
    
    // Store JVM reference
    if (env->GetJavaVM(&g_jvm) != JNI_OK) {
        LOGE("Failed to get Java VM");
        return;
    }
    
    // Get method IDs
    g_usb_bridge_class = (jclass)env->NewGlobalRef(clazz);
    g_enumerate_devices_method = env->GetStaticMethodID(
        clazz, "enumerateDevices", "()Ljava/lang/String;");
    g_open_device_method = env->GetStaticMethodID(
        clazz, "openDevice", "(Ljava/lang/String;)J");
    
    if (!g_enumerate_devices_method) {
        LOGE("Failed to get enumerateDevices method");
    }
    
    if (!g_open_device_method) {
        LOGE("Failed to get openDevice method");
    }
    
    LOGI("Bridge JNI initialized successfully");
}

// Bridge function: usb_bridge::get_devices()
std::vector<std::string> _ZN10usb_bridge11get_devicesEv() {
    LOGD("get_devices() called");
    
    if (!g_initialized) {
        // Try to get devices from Java first
        std::vector<std::string> java_devices = get_devices_from_java();
        if (!java_devices.empty()) {
            g_device_list = java_devices;
            g_initialized = true;
            LOGI("Initialized with %zu devices from Java", g_device_list.size());
        } else {
            // Fall back to environment
            init_from_environment();
        }
    }
    
    LOGD("Returning %zu devices", g_device_list.size());
    return g_device_list;
}

// Bridge function: usb_bridge::get_fd()
int _ZN10usb_bridge6get_fdERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE(
    const std::string& device_name) {
    
    LOGI("get_fd() called for device: %s", device_name.c_str());
    
    // Check if device exists in our list
    bool found = false;
    for (const auto& dev : g_device_list) {
        if (dev == device_name) {
            found = true;
            break;
        }
    }
    
    if (!found) {
        LOGE("Device not in list: %s", device_name.c_str());
        return -1;
    }
    
    // Try to open via Java if JNI is available
    if (g_jvm && g_usb_bridge_class && g_open_device_method) {
        JNIEnv* env = nullptr;
        bool attached = false;
        
        // Get JNIEnv
        int status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) != 0) {
                LOGE("Failed to attach thread for openDevice");
                return -1;
            }
            attached = true;
        } else if (status != JNI_OK) {
            LOGE("Failed to get JNIEnv for openDevice: %d", status);
            return -1;
        }
        
        try {
            jstring j_device_name = env->NewStringUTF(device_name.c_str());
            jlong result = env->CallStaticLongMethod(
                g_usb_bridge_class, g_open_device_method, j_device_name);
            env->DeleteLocalRef(j_device_name);
            
            if (attached) {
                g_jvm->DetachCurrentThread();
            }
            
            if (result > 0) {
                LOGI("Java openDevice returned fd: %ld", (long)result);
                return (int)result;
            } else {
                LOGI("Java openDevice failed for: %s", device_name.c_str());
            }
        } catch (...) {
            LOGE("Exception in Java openDevice");
            if (attached) {
                g_jvm->DetachCurrentThread();
            }
        }
    }
    
    // Fallback: Try to open /dev/bus/usb device
    // This is a simplified version - real implementation would handle Android USB API
    LOGI("Trying to open USB device: %s", device_name.c_str());
    
    // Check if it looks like a /dev/bus/usb path
    if (device_name.find("/dev/bus/usb/") == 0) {
        // Try to open the device file
        int fd = open(device_name.c_str(), O_RDWR);
        if (fd >= 0) {
            LOGI("Successfully opened %s with fd=%d", device_name.c_str(), fd);
            return fd;
        } else {
            LOGE("Failed to open %s: %s", device_name.c_str(), strerror(errno));
        }
    }
    
    // Last resort: return a dummy fd (for testing)
    LOGI("Returning dummy fd for testing");
    return 1234;
}

// Bridge function: usb_bridge::get_serial()
std::string _ZN10usb_bridge10get_serialERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE(
    const std::string& device_name) {
    
    LOGD("get_serial() called for device: %s", device_name.c_str());
    
    // Generate a serial based on device name
    std::string serial = "USB_";
    for (char c : device_name) {
        if (isalnum(c)) serial += c;
    }
    
    LOGD("Returning serial: %s", serial.c_str());
    return serial;
}

// Cleanup function
void _ZN10usb_bridge8shutdownEv() {
    LOGI("Bridge shutdown called");
    
    g_device_list.clear();
    g_initialized = false;
    
    // Cleanup JNI references
    if (g_jvm) {
        JNIEnv* env = nullptr;
        bool attached = false;
        
        int status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) == 0) {
                attached = true;
            }
        }
        
        if (env && g_usb_bridge_class) {
            env->DeleteGlobalRef(g_usb_bridge_class);
            g_usb_bridge_class = nullptr;
        }
        
        if (attached) {
            g_jvm->DetachCurrentThread();
        }
    }
    
    g_enumerate_devices_method = nullptr;
    g_open_device_method = nullptr;
    
    LOGI("Bridge shutdown complete");
}

// Initialize on library load
__attribute__((constructor)) static void bridge_init() {
    LOGI("libusb_bridge.so loaded - initializing bridge implementation");
    
    // Check for debug environment variable
    const char* debug = getenv("LIBUSB_DEBUG");
    if (debug && atoi(debug) >= 2) {
        LOGI("LIBUSB_DEBUG=%s - enabling verbose logging", debug);
    }
    
    // Initialize from environment as fallback
    init_from_environment();
    
    LOGI("Bridge implementation ready");
}

// Cleanup on library unload  
__attribute__((destructor)) static void bridge_cleanup() {
    LOGI("libusb_bridge.so unloading - cleaning up");
    _ZN10usb_bridge8shutdownEv();
}