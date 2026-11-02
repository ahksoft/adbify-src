#!/usr/bin/env bash
#
# Cross-compiles terminalview/src/main/jni/term.c into libadbify-term.so and drops it
# into the terminalview jniLibs tree.
#
#   scripts/build-native-libs.sh
#
# The normal build path is Gradle's `externalNativeBuild.ndkBuild` in
# terminalview/build.gradle. This script is the escape hatch for machines where
# ndk-build cannot run at all -- in particular an aarch64 Linux host, where the NDK
# ships Android binaries as its host tools:
#
#   * NDK 25.x  -> "Unknown host CPU architecture: aarch64" (build/tools/ndk_bin_common.sh
#                  has no aarch64 case), so ndk-build bails during configure.
#   * NDK 26.x  -> recognises aarch64, but prebuilt/linux-aarch64/bin/{make,clang} have
#                  interpreter /system/bin/linker64, so they cannot exec on the host.
#
# The NDK *sysroot* is just data and is unaffected, so this script drives a host clang
# and a host lld against it. Stage the result with `-PskipNdkBuild`:
#
#   ./gradlew assembleDebug -PskipNdkBuild
#
# Flags mirror cFlags in terminalview/build.gradle so both paths produce the same code.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="${REPO_ROOT}/terminalview"
SRC="${MODULE_DIR}/src/main/jni/term.c"
OUT_ROOT="${1:-${MODULE_DIR}/src/main/jniLibs}"

NDK_VERSION="${NDK_VERSION:-26.1.10909125}"
API=24 # keep in sync with rootProject.ext.minSdk

die() { echo "error: $*" >&2; exit 1; }

# ANDROID_NDK_HOME is set by ~/.android_env and this repo's docs; Android Studio uses
# ANDROID_NDK_ROOT. Accept either, then fall back to a versioned subdirectory.
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK_HOME:-}}}"
if [[ -z "$NDK" && -d "${ANDROID_SDK_ROOT:-/root/android-sdk}/ndk/${NDK_VERSION}" ]]; then
    NDK="${ANDROID_SDK_ROOT:-/root/android-sdk}/ndk/${NDK_VERSION}"
fi
[[ -d "$NDK" ]] || die "no Android NDK found. Set ANDROID_NDK_HOME to ndk ${NDK_VERSION}."

# Host prebuilt dir. NDK 26 names it linux-aarch64 on this machine; fall back to any
# single prebuilt dir, since the sysroot headers/libs are host-independent.
PRE="$NDK/toolchains/llvm/prebuilt/linux-aarch64"
if [[ ! -d "$PRE/sysroot" ]]; then
    PRE="$(dirname "$(find "$NDK/toolchains/llvm/prebuilt" -maxdepth 2 -type d -name sysroot 2>/dev/null | head -1)")"
fi
[[ -d "$PRE/sysroot" ]] || die "no NDK sysroot under $NDK/toolchains/llvm/prebuilt/*/sysroot"

command -v clang >/dev/null || die "host clang not found on PATH"

# ndk-build's own linkers are unusable on an aarch64 host, so borrow any working lld.
# rustup ships one that runs natively; otherwise use whatever ld.lld is on PATH.
LLD="${LLD:-$(command -v ld.lld || true)}"
if [[ -z "$LLD" ]]; then
    LLD="$(find "${HOME}/.rustup/toolchains" -name ld.lld -type f 2>/dev/null | head -1 || true)"
fi
[[ -n "$LLD" && -x "$LLD" ]] || die "no usable lld. Set LLD=/path/to/ld.lld."
"$LLD" --version >/dev/null 2>&1 || die "$LLD cannot execute on this host."

# The NDK's compiler-rt builtins are the only -lgcc replacement available; tell clang to
# look in the NDK resource dir rather than its own.
RESOURCE_DIR="$PRE/lib/clang/$(ls "$PRE/lib/clang" 2>/dev/null | sort -V | tail -1)"

CFLAGS=(-std=c11 -Wall -Wextra -Werror -Os -fno-stack-protector -ffunction-sections -fdata-sections)

# abi : clang target triple prefix
build_abi() {
    local abi="$1" triple="$2"
    local outdir="${OUT_ROOT}/${abi}"
    mkdir -p "$outdir"

    echo "  ${abi} -> ${outdir}/libadbify-term.so"
    clang \
        --target="${triple}${API}" \
        --sysroot="$PRE/sysroot" \
        --ld-path="$LLD" \
        -resource-dir="$RESOURCE_DIR" \
        --rtlib=compiler-rt \
        -shared -fPIC \
        "${CFLAGS[@]}" \
        -Wl,--gc-sections \
        -o "${outdir}/libadbify-term.so" \
        "$SRC"
}

echo "NDK:    $NDK (${NDK_VERSION})"
echo "sysroot:$PRE/sysroot"
echo "clang:  $(clang --version | head -1)"
echo "lld:    $LLD"
echo "Building libadbify-term.so for 4 ABIs into $OUT_ROOT"

build_abi arm64-v8a   aarch64-linux-android
build_abi armeabi-v7a armv7a-linux-androideabi
build_abi x86_64      x86_64-linux-android
build_abi x86         i686-linux-android

# A library that links but exports nothing loadable would ship an app that starts and
# then fails at System.loadLibrary. Verify every entry point JNI.java declares.
readonly REQUIRED_SYMBOLS=(
    Java_com_adbify_terminal_JNI_createSubprocess
    Java_com_adbify_terminal_JNI_setPtyWindowSize
    Java_com_adbify_terminal_JNI_waitFor
    Java_com_adbify_terminal_JNI_close
)

rc=0
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
    so="${OUT_ROOT}/${abi}/libadbify-term.so"
    if [[ ! -f "$so" ]]; then
        echo "  MISSING: $so" >&2
        rc=1
        continue
    fi
    for sym in "${REQUIRED_SYMBOLS[@]}"; do
        if ! nm -D --defined-only "$so" | grep -q " ${sym}\$"; then
            echo "  MISSING SYMBOL $sym in $so" >&2
            rc=1
        fi
    done
done

if [[ $rc -ne 0 ]]; then
    die "verification failed -- do not ship these libraries."
fi

echo "OK: all 4 ABIs built and every JNI entry point is exported."
