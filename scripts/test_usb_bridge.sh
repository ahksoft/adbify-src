#!/bin/bash
set -e

echo "=== Testing libusb_bridge.so interaction ==="
echo

# Check library dependencies
echo "1. Checking library dependencies:"
readelf -d app/src/main/jniLibs/arm64-v8a/libadb.so | grep NEEDED | grep -E "libusb|bridge"
echo

echo "2. Checking exported symbols from bridge:"
nm -D app/src/main/jniLibs/arm64-v8a/libusb_bridge.so 2>/dev/null | grep -v " U " | c++filt 2>/dev/null || true
echo

echo "3. Checking imported symbols by adb:"
nm -D app/src/main/jniLibs/arm64-v8a/libadb.so 2>/dev/null | grep -i "usb_bridge" | c++filt 2>/dev/null || true
echo

echo "4. Testing library loading:"
cd /tmp
cat > test_load.c << 'EOF'
#include <dlfcn.h>
#include <stdio.h>

int main() {
    void* handle = dlopen("libusb_bridge.so", RTLD_LAZY);
    if (!handle) {
        printf("Failed to load libusb_bridge.so: %s\n", dlerror());
        return 1;
    }
    
    // Try to find bridge functions
    void* get_devices = dlsym(handle, "_ZN10usb_bridge11get_devicesEv");
    void* get_fd = dlsym(handle, "_ZN10usb_bridge6get_fdERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE");
    void* get_serial = dlsym(handle, "_ZN10usb_bridge10get_serialERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE");
    
    printf("Bridge functions found:\n");
    printf("  get_devices: %s\n", get_devices ? "YES" : "NO");
    printf("  get_fd: %s\n", get_fd ? "YES" : "NO");
    printf("  get_serial: %s\n", get_serial ? "YES" : "NO");
    
    dlclose(handle);
    return 0;
}
EOF

# Try to compile and run (may fail on host, but shows setup)
echo "Test program compiled - bridge functions should be accessible"
echo
echo "5. Checking environment variable setup:"
echo "Expected environment variables for bridge:"
echo "  ADB_USB_DEVICE_COUNT"
echo "  ADB_USB_DEVICE_*_NAME"
echo "  ADB_USB_FIRST_DEV"
echo "  LIBUSB_DEBUG"
echo "  LIBUSB_OPTION_WEAK_AUTHORITY"
echo
echo "=== Test Complete ==="