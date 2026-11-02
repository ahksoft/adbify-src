#!/usr/bin/env bash
#
# Builds the USB bridge implementation library using the same approach as
# build-native-libs.sh (host clang + host lld against NDK sysroot).
#
#   scripts/build_usb_bridge_native.sh
#

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="${REPO_ROOT}/app/src/main/jni"
OUT_DIR="${REPO_ROOT}/app/src/main/jniLibs"

NDK_VERSION="${NDK_VERSION:-26.1.10909125}"
API=21  # minSdkVersion

die() { echo "error: $*" >&2; exit 1; }

# Find NDK
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK_HOME:-}}}"
if [[ -z "$NDK" && -d "${ANDROID_SDK_ROOT:-/root/android-sdk}/ndk/${NDK_VERSION}" ]]; then
    NDK="${ANDROID_SDK_ROOT:-/root/android-sdk}/ndk/${NDK_VERSION}"
fi
[[ -d "$NDK" ]] || die "no Android NDK found. Set ANDROID_NDK_HOME to ndk ${NDK_VERSION}."

# Host prebuilt dir - find any sysroot (headers are host-independent)
PRE="$NDK/toolchains/llvm/prebuilt/linux-aarch64"
if [[ ! -d "$PRE/sysroot" ]]; then
    PRE="$(dirname "$(find "$NDK/toolchains/llvm/prebuilt" -maxdepth 2 -type d -name sysroot 2>/dev/null | head -1)")"
fi
[[ -d "$PRE/sysroot" ]] || die "no NDK sysroot under $NDK/toolchains/llvm/prebuilt/*/sysroot"

# Check for host clang
command -v clang >/dev/null || die "host clang not found on PATH"

# Find working lld (rustup ships one that runs natively)
LLD="${LLD:-$(command -v ld.lld || true)}"
if [[ -z "$LLD" ]]; then
    LLD="$(find "${HOME}/.rustup/toolchains" -name ld.lld -type f 2>/dev/null | head -1 || true)"
fi
[[ -n "$LLD" && -x "$LLD" ]] || die "no usable lld. Set LLD=/path/to/ld.lld."
"$LLD" --version >/dev/null 2>&1 || die "$LLD cannot execute on this host."

# NDK resource dir for compiler-rt
RESOURCE_DIR="$PRE/lib/clang/$(ls "$PRE/lib/clang" 2>/dev/null | sort -V | tail -1)"
[[ -d "$RESOURCE_DIR" ]] || die "no clang resource dir under $PRE/lib/clang"

echo "=== Building USB Bridge Native Library ==="
echo "NDK:         $NDK (${NDK_VERSION})"
echo "sysroot:     $PRE/sysroot"
echo "clang:       $(clang --version | head -1)"
echo "lld:         $LLD"
echo "resource dir:$RESOURCE_DIR"
echo "API level:   $API"
echo ""

# C++ flags for the bridge implementation
CXXFLAGS=(-std=c++17 -frtti -fexceptions -Wall -Wextra -Werror -Os -fPIC -DANDROID -DLOG_TAG=\"UsbBridgeImpl\")
CXXFLAGS+=(-fvisibility=hidden -ffunction-sections -fdata-sections -fno-stack-protector)

# Include paths
CXX_INCLUDES=(-I"$PRE/sysroot/usr/include" -I"$PRE/sysroot/usr/include/c++/v1")

# Build for each ABI
build_abi() {
    local abi="$1" triple="$2"
    local outdir="$OUT_DIR/$abi"
    
    mkdir -p "$outdir"
    
    echo "  Building $abi (target: ${triple}${API})..."
    
    # Compile
    clang++ \
        --target="${triple}${API}" \
        --sysroot="$PRE/sysroot" \
        -resource-dir="$RESOURCE_DIR" \
        "${CXX_INCLUDES[@]}" \
        "${CXXFLAGS[@]}" \
        -c "$JNI_DIR/usb_bridge_impl.cpp" \
        -o "/tmp/usb_bridge_impl_${abi}.o" || die "compile failed for $abi"
    
    # Link
    clang++ \
        --target="${triple}${API}" \
        --sysroot="$PRE/sysroot" \
        --ld-path="$LLD" \
        -resource-dir="$RESOURCE_DIR" \
        --rtlib=compiler-rt \
        -shared \
        -fPIC \
        "${CXXFLAGS[@]}" \
        -Wl,--gc-sections \
        -Wl,-soname,libusb_bridge_impl.so \
        "/tmp/usb_bridge_impl_${abi}.o" \
        -L"$PRE/sysroot/usr/lib/${triple}/$API" \
        -llog -ldl -lc++_shared \
        -o "$outdir/libusb_bridge_impl.so" || die "link failed for $abi"
    
    # Verify symbols
    echo "    ✅ Built: $outdir/libusb_bridge_impl.so"
    echo -n "    Symbols: "
    nm -D "$outdir/libusb_bridge_impl.so" 2>/dev/null | grep -c "usb_bridge::" || echo "0"
}

echo "Building libusb_bridge_impl.so for 4 ABIs into $OUT_DIR"
echo ""

# Build for each ABI
build_abi "arm64-v8a" "aarch64-linux-android"
build_abi "armeabi-v7a" "armv7a-linux-androideabi"
build_abi "x86_64" "x86_64-linux-android"
build_abi "x86" "i686-linux-android"

echo ""
echo "=== Build Complete ==="
echo "Libraries built in: $OUT_DIR"
echo ""
echo "Verifying key symbols in ARM64 build:"
nm -D "$OUT_DIR/arm64-v8a/libusb_bridge_impl.so" 2>/dev/null | grep "usb_bridge::" || echo "  (No usb_bridge symbols found)"
echo ""
echo "=== Next Steps ==="
echo "1. Rebuild APK with the new library:"
echo "   ./gradlew assembleDebug -PskipNdkBuild"
echo ""
echo "2. Install and test:"
echo "   adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
echo ""
echo "3. Check USB bridge logs:"
echo "   adb logcat | grep -E 'UsbBridge|get_devices|get_fd'"