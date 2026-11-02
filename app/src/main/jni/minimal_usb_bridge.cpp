/*
 * Minimal USB bridge implementation with C-compatible interface
 * This avoids C++ ABI issues by using C linkage
 */

#include <cstring>
#include <cstdlib>
#include <android/log.h>

#define LOG_TAG "MinimalUsbBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// C-compatible structures to avoid C++ ABI issues
struct usb_device_list {
    int count;
    const char* devices[10];  // Max 10 devices
};

struct usb_device_list g_device_list = {0};

// Initialize from environment
__attribute__((constructor)) static void init_bridge() {
    LOGI("Minimal USB Bridge initializing");
    
    const char* count_str = getenv("ADB_USB_DEVICE_COUNT");
    if (count_str) {
        g_device_list.count = atoi(count_str);
        LOGI("Found %d USB devices in environment", g_device_list.count);
        
        for (int i = 0; i < g_device_list.count && i < 10; i++) {
            char env_name[64];
            snprintf(env_name, sizeof(env_name), "ADB_USB_DEVICE_%d_NAME", i);
            const char* device_name = getenv(env_name);
            if (device_name) {
                // Store pointer (environment strings remain valid)
                g_device_list.devices[i] = device_name;
                LOGI("Device[%d]: %s", i, device_name);
            }
        }
    } else {
        LOGI("No USB devices in environment");
    }
}

// C-compatible functions that match what libusb expects
// We'll use simple C linkage to avoid C++ ABI issues

#ifdef __cplusplus
extern "C" {
#endif

// Simple C-compatible version - returns pointer to device list
void* usb_bridge_get_devices_simple() {
    LOGI("usb_bridge_get_devices_simple() called");
    return &g_device_list;
}

// Get file descriptor for device
int usb_bridge_get_fd_simple(const char* device_name) {
    LOGI("usb_bridge_get_fd_simple(%s) called", device_name ? device_name : "null");
    
    if (!device_name) {
        LOGE("Null device name");
        return -1;
    }
    
    // Check if device is in our list
    for (int i = 0; i < g_device_list.count; i++) {
        if (g_device_list.devices[i] && strcmp(g_device_list.devices[i], device_name) == 0) {
            LOGI("Found device %s, returning dummy fd", device_name);
            return 1234;  // Dummy FD for testing
        }
    }
    
    LOGE("Device not found: %s", device_name);
    return -1;
}

// Get serial for device
const char* usb_bridge_get_serial_simple(const char* device_name) {
    LOGI("usb_bridge_get_serial_simple(%s) called", device_name ? device_name : "null");
    
    static char serial_buffer[64];
    if (device_name) {
        snprintf(serial_buffer, sizeof(serial_buffer), "SERIAL_%s", device_name);
        return serial_buffer;
    }
    
    return "UNKNOWN";
}

#ifdef __cplusplus
}
#endif

// Also provide the C++ mangled names that the original library expects
// We'll use simple implementations that call our C functions

// This is the tricky part - we need to match the exact C++ mangling
// Let's check what the actual mangled names are and create simple stubs

// For testing, let's also create JNI function
JNIEXPORT void JNICALL
Java_com_adbify_usb_NativeUsbBridge_initBridgeJNI(JNIEnv* env, jclass clazz) {
    LOGI("Minimal bridge JNI init called");
}

// Note: The actual usb_bridge::get_devices() C++ function expects std::vector<std::string>
// This is complex to implement with C linkage. We might need a different approach.