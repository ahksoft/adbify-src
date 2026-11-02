#!/usr/bin/env bash
#
# Builds the USB bridge implementation library for Android.
# Uses host clang to cross-compile for Android aarch64.
#
#   scripts/build_usb_bridge.sh
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

# Find sysroot - headers are host-independent
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-aarch64/sysroot"
if [[ ! -d "$SYSROOT" ]]; then
    SYSROOT="$(find "$NDK/toolchains/llvm/prebuilt" -maxdepth 3 -type d -name sysroot 2>/dev/null | head -1)"
fi
[[ -d "$SYSROOT" ]] || die "no sysroot found in NDK"

# Use host clang (system clang, not NDK's Android clang)
HOST_CLANG="$(which clang 2>/dev/null || which clang-18 2>/dev/null || which clang-17 2>/dev/null)"
[[ -n "$HOST_CLANG" ]] || die "no host clang found (tried clang, clang-18, clang-17)"

# Use host lld from rustup (as per AGENTS.md)
HOST_LLD="$(which ld.lld 2>/dev/null || find /root/.rustup/toolchains -name ld.lld -type f 2>/dev/null | head -1)"
[[ -n "$HOST_LLD" ]] || die "no host ld.lld found (install rustup or llvm)"

echo "=== Building USB Bridge Implementation ==="
echo "NDK: $NDK"
echo "Sysroot: $SYSROOT"
echo "Host clang: $HOST_CLANG"
echo "Host lld: $HOST_LLD"
echo "Source: $JNI_DIR/usb_bridge_impl.cpp"
echo ""

# Target ABI
ABIS=("arm64-v8a" "armeabi-v7a" "x86_64" "x86")

for ABI in "${ABIS[@]}"; do
    echo "Building for $ABI..."
    
    case "$ABI" in
        "arm64-v8a")
            TARGET="aarch64-none-linux-android$API"
            CLANG_FLAGS="-target aarch64-none-linux-android$API -march=armv8-a"
            ;;
        "armeabi-v7a")
            TARGET="armv7a-none-linux-androideabi$API"
            CLANG_FLAGS="-target armv7a-none-linux-androideabi$API -march=armv7-a -mfpu=vfpv3-d16 -mfloat-abi=softfp"
            ;;
        "x86_64")
            TARGET="x86_64-none-linux-android$API"
            CLANG_FLAGS="-target x86_64-none-linux-android$API -march=x86-64"
            ;;
        "x86")
            TARGET="i686-none-linux-android$API"
            CLANG_FLAGS="-target i686-none-linux-android$API -march=i686"
            ;;
    esac
    
    OUT_ABI_DIR="$OUT_DIR/$ABI"
    mkdir -p "$OUT_ABI_DIR"
    
    # Build flags
    CFLAGS="$CLANG_FLAGS -D__ANDROID_API__=$API -DANDROID -fdata-sections -ffunction-sections -funwind-tables -fstack-protector-strong -no-canonical-prefixes"
    CFLAGS="$CFLAGS -fno-addrsig -fomit-frame-pointer -fvisibility=hidden -Oz -g -Wall -Wextra -Werror"
    
    # Include paths
    INCLUDES="-I$SYSROOT/usr/include -I$SYSROOT/usr/include/$TARGET"
    
    # Library paths
    LIB_DIR="$SYSROOT/usr/lib/$TARGET/$API"
    
    # Compile
    echo "  Compiling usb_bridge_impl.cpp..."
    "$HOST_CLANG" $CFLAGS $INCLUDES -c "$JNI_DIR/usb_bridge_impl.cpp" -o "/tmp/usb_bridge_impl_$ABI.o"
    
    # Link
    echo "  Linking libusb_bridge_impl.so..."
    "$HOST_CLANG" $CFLAGS \
        -shared \
        -Wl,--gc-sections \
        -Wl,-z,nocopyreloc \
        -Wl,-soname,libusb_bridge_impl.so \
        "/tmp/usb_bridge_impl_$ABI.o" \
        -L"$LIB_DIR" \
        -llog -ldl -lc++_shared \
        -fuse-ld="$HOST_LLD" \
        -o "$OUT_ABI_DIR/libusb_bridge_impl.so"
    
    echo "  ✅ Built $OUT_ABI_DIR/libusb_bridge_impl.so"
    echo ""
done

echo "=== Build Complete ==="
echo "Libraries built in: $OUT_DIR"
echo "Check symbol exports:"
for ABI in "${ABIS[@]}"; do
    echo "  $ABI:"
    nm -D "$OUT_DIR/$ABI/libusb_bridge_impl.so" 2>/dev/null | grep -E "usb_bridge::get_devices|usb_bridge::get_fd" || echo "    (no symbols found or nm failed)"
done

echo ""
echo "Now rebuild APK:"
echo "  ./gradlew assembleDebug -PskipNdkBuild"