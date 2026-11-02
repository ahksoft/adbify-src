LOCAL_PATH := $(call my-dir)

# First, declare the prebuilt libusb_bridge.so
include $(CLEAR_VARS)
LOCAL_MODULE := usb_bridge_prebuilt
LOCAL_SRC_FILES := ../../jniLibs/$(TARGET_ARCH_ABI)/libusb_bridge.so
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)
include $(PREBUILT_SHARED_LIBRARY)

# Build USB bridge implementation
include $(CLEAR_VARS)

LOCAL_MODULE := usb_bridge_impl
LOCAL_SRC_FILES := usb_bridge_impl.cpp

# Use the same C++ standard as the original bridge
LOCAL_CPPFLAGS := -std=c++17 -frtti -fexceptions
LOCAL_LDLIBS := -llog -ldl
LOCAL_CFLAGS := -DLOG_TAG=\"UsbBridgeImpl\"

# Link against the prebuilt libusb_bridge.so
LOCAL_SHARED_LIBRARIES := usb_bridge_prebuilt

include $(BUILD_SHARED_LIBRARY)