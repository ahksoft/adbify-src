/*
 * Minimal stub implementation for USB bridge
 * Provides the required symbols for libusb_bridge.so
 */

#include <vector>
#include <string>
#include <cstring>

// Simple implementation that returns dummy data
extern "C" std::vector<std::string> _ZN10usb_bridge11get_devicesEv() {
    std::vector<std::string> devices;
    // Return a dummy device for testing
    devices.push_back("USB_Device_001");
    return devices;
}

extern "C" int _ZN10usb_bridge6get_fdERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE(
    const std::string& device_name) {
    // Return a dummy file descriptor
    return 1234;
}

extern "C" std::string _ZN10usb_bridge10get_serialERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE(
    const std::string& device_name) {
    return "USB_SERIAL_001";
}

extern "C" void _ZN10usb_bridge8shutdownEv() {
    // Nothing to cleanup
}
