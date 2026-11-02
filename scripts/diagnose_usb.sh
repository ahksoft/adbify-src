#!/bin/bash
set -e

echo "=== COMPREHENSIVE USB CONNECTION DIAGNOSIS ==="
echo "Time: $(date)"
echo

cd /root/SimpleUsbTerminal/Adbify

# 1. Check APK build status
echo "1. APK BUILD STATUS:"
if [ -f "app/build/outputs/apk/debug/app-arm64-v8a-debug.apk" ]; then
    apk_size=$(stat -c%s "app/build/outputs/apk/debug/app-arm64-v8a-debug.apk")
    echo "✅ APK exists: $((apk_size/1024/1024)) MB"
    
    # Check if APK contains the libraries
    echo "   Checking libraries in APK:"
    unzip -l "app/build/outputs/apk/debug/app-arm64-v8a-debug.apk" | grep -E "libadb\.so|libusb\.so|libusb_bridge\.so" | head -5
else
    echo "❌ APK not built!"
    exit 1
fi
echo

# 2. Check NativeUsbBridge integration
echo "2. NATIVE USB BRIDGE INTEGRATION:"
grep -n "NativeUsbBridge" app/src/main/java/com/adbify/terminal/NativePayload.kt
if [ $? -eq 0 ]; then
    echo "✅ NativeUsbBridge referenced in NativePayload"
else
    echo "❌ NativeUsbBridge NOT referenced!"
fi
echo

# 3. Check the actual initialization code
echo "3. INITIALIZATION CODE IN NativePayload:"
grep -A5 -B2 "NativeUsbBridge" app/src/main/java/com/adbify/terminal/NativePayload.kt
echo

# 4. Check environment variable setup
echo "4. ENVIRONMENT VARIABLE SETUP:"
echo "   Checking buildEnv() function:"
grep -A15 "fun buildEnv" app/src/main/java/com/adbify/terminal/NativePayload.kt | grep -E "ADB|LIBUSB"
echo

# 5. Check library dependencies
echo "5. LIBRARY DEPENDENCY CHAIN:"
echo "   libadb.so ->"
readelf -d app/src/main/jniLibs/arm64-v8a/libadb.so 2>/dev/null | grep NEEDED
echo
echo "   libusb.so ->"
readelf -d app/src/main/jniLibs/arm64-v8a/libusb.so 2>/dev/null | grep NEEDED
echo
echo "   libusb_bridge.so ->"
readelf -d app/src/main/jniLibs/arm64-v8a/libusb_bridge.so 2>/dev/null | grep NEEDED
echo

# 6. Check symbol resolution
echo "6. SYMBOL RESOLUTION STATUS:"
echo "   What libadb.so expects from bridge:"
nm -D app/src/main/jniLibs/arm64-v8a/libadb.so 2>/dev/null | grep "usb_bridge" | wc -l | xargs echo "   - Bridge symbols needed:"
echo

# 7. Check JNI native methods
echo "7. JNI NATIVE METHOD DECLARATIONS:"
echo "   In NativeUsbBridge.kt:"
grep -n "external fun" app/src/main/java/com/adbify/usb/NativeUsbBridge.kt
echo

# 8. Check for JNI implementation files
echo "8. JNI IMPLEMENTATION FILES:"
find . -name "*.cpp" -o -name "*.c" | xargs grep -l "usb_bridge\|NativeUsbBridge" 2>/dev/null || echo "   No JNI C/C++ files found matching bridge"
echo

# 9. Check AndroidManifest USB permissions
echo "9. ANDROID MANIFEST USB PERMISSIONS:"
grep -n "usb\|USB" app/src/main/AndroidManifest.xml | head -10
echo

# 10. Check device_filter.xml
echo "10. USB DEVICE FILTER:"
if [ -f "app/src/main/res/xml/device_filter.xml" ]; then
    echo "✅ device_filter.xml exists"
    echo "   Contains $(grep -c "usb-device" app/src/main/res/xml/device_filter.xml) device filters"
else
    echo "❌ device_filter.xml missing!"
fi
echo

echo "=== POTENTIAL ISSUES ==="
echo
echo "Based on analysis, here are potential issues:"
echo
echo "1. 🚨 MISSING JNI IMPLEMENTATION"
echo "   - NativeUsbBridge declares native methods but no .cpp/.c file implements them"
echo "   - The bridge functions (usb_bridge::get_devices, etc.) need actual JNI code"
echo
echo "2. 🚨 LIBRARY LOADING ORDER"
echo "   - libusb.so loads before bridge is initialized"
echo "   - Bridge JNI context might not be ready when libusb calls it"
echo
echo "3. 🚨 ENVIRONMENT VARIABLE TIMING"
echo "   - Environment vars set in buildEnv(), but bridge might read them earlier"
echo
echo "4. 🚨 PERMISSION TIMING"
echo "   - USB permissions requested, but bridge might try to open devices before granted"
echo
echo "=== RECOMMENDED FIXES ==="
echo
echo "1. Find or create JNI implementation for NativeUsbBridge"
echo "2. Ensure bridge initialization happens BEFORE libusb loads"
echo "3. Add delay/retry logic for USB permission grants"
echo "4. Add debug logging to trace actual bridge calls"