#!/usr/bin/env bash
#
# Builds the working USB bridge implementation in C
# Uses the same approach as build-native-libs.sh
#

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="${REPO_ROOT}/app/src/main/jni"
OUT_DIR="${REPO_ROOT}/app/src/main/jniLibs"

NDK_VERSION="${NDK_VERSION:-26.1.10909125}"
API=21

die() { echo "error: $*" >&2; exit 1; }

# Find NDK
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK_HOME:-}}}"
if [[ -z "$NDK" && -d "${ANDROID_SDK_ROOT:-/root/android-sdk}/ndk/${NDK_VERSION}" ]]; then
    NDK="${ANDROID_SDK_ROOT:-/root/android-sdk}/ndk/${NDK_VERSION}"
fi
[[ -d "$NDK" ]] || die "no Android NDK found. Set ANDROID_NDK_HOME to ndk ${NDK_VERSION}."

# Find sysroot
PRE="$NDK/toolchains/llvm/prebuilt/linux-aarch64"
if [[ ! -d "$PRE/sysroot" ]]; then
    PRE="$(dirname "$(find "$NDK/toolchains/llvm/prebuilt" -maxdepth 2 -type d -name sysroot 2>/dev/null | head -1)")"
fi
[[ -d "$PRE/sysroot" ]] || die "no NDK sysroot under $NDK/toolchains/llvm/prebuilt/*/sysroot"

# Check for host clang
command -v clang >/dev/null || die "host clang not found on PATH"

# Find working lld
LLD="${LLD:-$(command -v ld.lld || true)}"
if [[ -z "$LLD" ]]; then
    LLD="$(find "${HOME}/.rustup/toolchains" -name ld.lld -type f 2>/dev/null | head -1 || true)"
fi
[[ -n "$LLD" && -x "$LLD" ]] || die "no usable lld. Set LLD=/path/to/ld.lld."
"$LLD" --version >/dev/null 2>&1 || die "$LLD cannot execute on this host."

# Resource dir for compiler-rt
RESOURCE_DIR="$PRE/lib/clang/$(ls "$PRE/lib/clang" 2>/dev/null | sort -V | tail -1)"
[[ -d "$RESOURCE_DIR" ]] || die "no clang resource dir under $PRE/lib/clang"

echo "=== Building Working USB Bridge (C implementation) ==="
echo "NDK:         $NDK"
echo "sysroot:     $PRE/sysroot"
echo "clang:       $(clang --version | head -1)"
echo "lld:         $LLD"
echo "API level:   $API"
echo ""

# Build for each ABI
build_abi() {
    local abi="$1" triple="$2"
    local outdir="$OUT_DIR/$abi"
    
    mkdir -p "$outdir"
    
    echo "  Building $abi (target: ${triple}${API})..."
    
    # C flags (simple C, not C++)
    CFLAGS=(-std=c11 -Wall -Wextra -Werror -Os -fPIC -DANDROID -DLOG_TAG=\"WorkingUsbBridge\")
    CFLAGS+=(-ffunction-sections -fdata-sections -fno-stack-protector)
    
    # Compile
    clang \
        --target="${triple}${API}" \
        --sysroot="$PRE/sysroot" \
        -resource-dir="$RESOURCE_DIR" \
        "${CFLAGS[@]}" \
        -I"$JNI_DIR" \
        -c "$JNI_DIR/working_usb_bridge.c" \
        -o "/tmp/working_bridge_${abi}.o" || die "compile failed for $abi"
    
    # Link
    clang \
        --target="${triple}${API}" \
        --sysroot="$PRE/sysroot" \
        --ld-path="$LLD" \
        -resource-dir="$RESOURCE_DIR" \
        --rtlib=compiler-rt \
        -shared \
        -fPIC \
        "${CFLAGS[@]}" \
        -Wl,--gc-sections \
        -Wl,-soname,libworking_usb_bridge.so \
        "/tmp/working_bridge_${abi}.o" \
        -L"$PRE/sysroot/usr/lib/${triple}/$API" \
        -llog -ldl \
        -o "$outdir/libworking_usb_bridge.so" || die "link failed for $abi"
    
    echo "    ✅ Built: $outdir/libworking_usb_bridge.so"
    
    # Check symbols
    echo -n "    Symbols: "
    nm -D "$outdir/libworking_usb_bridge.so" 2>/dev/null | grep -c "_ZN10usb_bridge" || echo "0"
}

echo "Building libworking_usb_bridge.so for 4 ABIs into $OUT_DIR"
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
echo "Checking key symbols in ARM64 build:"
nm -D "$OUT_DIR/arm64-v8a/libworking_usb_bridge.so" 2>/dev/null | grep "_ZN10usb_bridge" || echo "  (No usb_bridge symbols found)"
echo ""
echo "=== Next Steps ==="
echo "1. Update NativeUsbBridge.kt to load this library"
echo "2. Rebuild APK: ./gradlew assembleDebug -PskipNdkBuild"
echo "3. Install and test"