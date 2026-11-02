package com.adbify.terminal

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.adbify.usb.UsbDeviceInfo
import com.adbify.usb.UsbHostManager
import com.adbify.usb.UsbDeviceManager
import com.adbify.usb.NativeUsbBridge
import java.io.File
import java.util.zip.ZipFile

/**
 * Provisions the native toolchain shipped in `assets/lib_symlinks.properties`.
 *
 * Every payload file is packaged in `jniLibs` under a `lib<name>.so` name so that it
 * survives APK packaging and the installer gives it the executable bit. The manifest maps
 * each of those files to the *tool names* users actually type:
 *
 *     libadb.so=\
 *       bin/adb,\
 *       bin/adb-host
 *
 * Provisioning recreates those symlinks under a Termux-shaped prefix, so `usr/bin/adb` on
 * `PATH` is really `libadb.so` with an exec bit. The manifest splits into two
 * kinds of entry and both matter:
 *
 *  - executables (`bin/...`) are symlinked onto `PATH` and exec'd;
 *  - libraries (`lib/...`) are symlinked into `lib/` and published via `LD_LIBRARY_PATH`,
 *    because the payload `libadb.so` is linked against `libusb.so` and `libusb_bridge.so`,
 *    which do not exist on stock Android.
 *
 * The multi-call binaries (`libcoreutils.so`) dispatch on `basename(argv[0])`, so the
 * symlink *name* is load-bearing: it is what makes one blob answer to `ls`, `cp`, `rm`, ...
 *
 * Provisioning is idempotent and never fatal. Anything missing is skipped, so a build
 * carrying only the plain network-only `libadb.so` still gets a working `adb` on `PATH`
 * and falls back to the system shell.
 */
object NativePayload {

    private const val TAG = "NativePayload"
    private const val MANIFEST = "lib_symlinks.properties"

    /**
     * Small system-like tree: `etc/`, `share/terminfo`, `share/tabset`, `bin/mke2fs.conf`.
     */
    private const val BOOTSTRAP = "bootstrap.zip"

    /**
     * Bump when the payload changes so the next launch re-extracts it.
     *
     * [extractOnce] skips an unpack whose marker is already present, so without this a
     * rebuilt payload would never reach the device over an existing install. The stamp is
     * per-asset, which is why it lives here rather than being derived from the filename.
     */
    private const val BOOTSTRAP_STAMP = "1"

    /**
     * Bumped to 5 when the payload stopped raising on a missing libfuse, and stopped shipping
     * unusable bytecode.
     *
     * mtkclient guards its two `mfusepy` imports with `except ImportError`, but mfusepy *is*
     * installed and still fails: it resolves libfuse itself, finds none, and raises `OSError`
     * -- which is not an `ImportError`, so it escaped a plain `from mfusepy import ...` and
     * killed `mtk` during import. Both guards now also catch `OSError`, which is what the
     * surrounding stub class and the `if FUSE is not None` check were written for anyway.
     *
     * The same archive stopped carrying 143 source-adjacent `.pyc` compiled by CPython 3.11
     * while the device runs 3.14. They were silently ignored (magic mismatch): 1 MB of dead
     * weight, and a stale pre-patch copy of every file `patch-android.py` rewrites.
     *
     * Bumped to 4 for the same reason as this -- patches applied during the build are invisible
     * to an install that already unpacked the previous payload into `$PREFIX`.
     */
    private const val PYTHON_STAMP = "5"

    /** Provider basename for the bash that backs [shellPath]; also symlinked as `bin/sh`. */
    private const val BASH_PROVIDER = "libbash.so"

    /**
     * The CPython 3.14 + mtkclient payload, shipped as a zip in `jniLibs/arm64-v8a`.
     *
     * The interpreter is upstream's own `aarch64-linux-android` release rather than a local
     * cross-build. Two consequences matter here, and both are why this cannot simply be a
     * `$PREFIX`-shaped directory copied over:
     *
     *  * It ships **no interpreter binary at all**. Python on Android is embedded-mode only,
     *    so the release is a libprefix: `libpython3.14.so` plus the stdlib. `$PREFIX/bin/
     *    python3.14` is a three-line embedding host around `Py_BytesMain` built outside this
     *    repo, and it has to be named exactly that because CPython derives `sys.prefix` from
     *    the executable's own path.
     *  * It resolves OpenSSL by plain soname with no RPATH. `libssl_python.so` and
     *    `libcrypto_python.so` are unpacked into `$PREFIX/lib`, so they are found only because
     *    [buildEnv] puts that on `LD_LIBRARY_PATH` -- the same mechanism the payload's own
     *    `libusb.so` already depends on. Drop that and `import ssl` fails at `dlopen`, which
     *    surfaces as the first `pip` command breaking rather than at startup.
     *
     * It lives in `jniLibs` rather than in `assets` for one reason: `jniLibs` is split per
     * ABI and `assets` is not. Every `.so` in it is an Android arm64 ELF, so as an asset the
     * archive was 60 MB of dead weight in each of the x86, x86_64 and armeabi-v7a APKs --
     * 87% of those downloads, for a payload they cannot execute. As a `jniLibs` entry the
     * package manager extracts it into `nativeLibraryDir` for the device's own ABI and the
     * other splits never see it.
     *
     * The `lib*.so` name is the only naming the installer extracts with an executable bit;
     * the contents are a zip, not an ELF. That is the same trick the rest of the payload
     * already plays -- `libadb.so` is an executable, not a library -- so it is an existing
     * convention here rather than a new one.
     *
     * Its presence in `nativeLibraryDir` doubles as the ABI check, which is strictly better
     * than consulting `Build.SUPPORTED_ABIS`: the file is only there when this APK's ABI is
     * the one it was packaged for, so the two cannot disagree.
     */
    private const val PYTHON_PAYLOAD = "libpython-payload.so"

        @Volatile
    private var provisioned = false

    /** True once the payload gave us a real bash rather than the system shell. */
    var hasBash: Boolean = false
        private set

    /** USB devices detected during provisioning, exposed to the shell environment. */
    private var usbDevices: List<UsbDeviceInfo> = emptyList()
    
    /** USB device file descriptors that have been opened. */
    private var openUsbDeviceFds: Map<String, Int> = emptyMap()

    /**
     * Termux-style tree, mirroring `/data/data/com.termux/files`:
     *
     *     files/home   -> $HOME, the user's working directory
     *     files/usr    -> $PREFIX, the system-like tree owned by the terminal
     *       usr/bin    -> tool symlinks (adb, bash, ls, ...)
     *       usr/lib    -> shared libraries (libusb.so, libusb_bridge.so, ...)
     *       usr/etc    -> bash.bashrc, profile, inputrc from bootstrap.zip
     *       usr/share  -> terminfo, tabset from bootstrap.zip
     *       usr/tmp    -> $TMPDIR
     *
     * The manifest's targets (`bin/adb`, `lib/libusb.so`) are *relative*, which is why they
     * resolve against `usr/` and not `files/` — the same convention that makes Termux's
     * manifest work.
     */
    lateinit var homeDir: File
        private set

    lateinit var prefix: File
        private set

    val binDir: File get() = File(prefix, "bin")
    val libDir: File get() = File(prefix, "lib")
    val etcDir: File get() = File(prefix, "etc")
    val shareDir: File get() = File(prefix, "share")
    val tmpDir: File get() = File(prefix, "tmp")

    /** The shell to spawn: payload bash when available, else the system shell. */
    val shellPath: String
        get() = if (hasBash) File(binDir, "bash").absolutePath else SYSTEM_SHELL

    /**
     * Run provisioning once per process. Returns true if the full payload was staged.
     * Safe to call from the service on every session creation.
     */
    @Synchronized
    fun provision(context: Context): Boolean {
        if (provisioned) return hasBash
        provisioned = true

        val filesDir = context.filesDir
        homeDir = File(filesDir, "home")
        prefix = File(filesDir, "usr")

        for (dir in listOf(homeDir, prefix, binDir, libDir, etcDir, shareDir, tmpDir)) {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "cannot create $dir")
                if (dir == prefix || dir == binDir) {
                    hasBash = false
                    return false
                }
            }
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val entries = readManifest(context)

        var linked = 0
        for ((provider, targets) in entries) {
            // Guard against ABIs that only ship the plain libadb.so: skip manifest rows
            // whose provider binary was not packaged, rather than leaving dangling links.
            val source = File(nativeLibDir, provider)
            if (!source.exists()) {
                Log.i(TAG, "skipping $provider -> $targets (not packaged for this ABI)")
                continue
            }
            for (target in targets) {
                if (symlink(source, File(prefix, target))) linked++
            }
        }

        // Real shared libraries with no manifest row (libpty.so,
        // libdatastore_shared_counter.so) still have to be reachable by the dynamic
        // linker, so publish anything unlisted into lib/.
        //
        // The Python payload is excluded: it is a zip, not a library, and is consumed
        // directly out of nativeLibraryDir by extractPythonPayload(). Symlinking it into
        // lib/ would only advertise a 60 MB archive as a loadable library, which is both
        // untrue and a trap for anything that later probes lib/ for a dependency.
        File(nativeLibDir).listFiles()?.forEach { lib ->
            if (lib.name.endsWith(".so") && lib.name !in entries && lib.name != PYTHON_PAYLOAD) {
                if (symlink(lib, File(libDir, lib.name))) linked++
            }
        }

        extractOnce(context, BOOTSTRAP, BOOTSTRAP_STAMP)
        extractPythonPayload(nativeLibDir)
        chmodExecutablesInBin()
        linkBashrc()

        hasBash = File(binDir, "bash").exists() || File(binDir, BASH_PROVIDER).exists()

        // Scan for USB devices and request permission to detect attached devices
        val usbHostManager = UsbHostManager.getInstance(context)
        usbHostManager.scanAndRequestPermissions()
        
        // Wait briefly for permission requests to be processed
        Thread.sleep(100)
        
        usbDevices = usbHostManager.listDevices()
        
        // Log USB device information for debugging
        usbDevices.forEachIndexed { index, device ->
            Log.i(TAG, "USB device $index: ${device.deviceName}, VID=${device.vendorId}, PID=${device.productId}")
        }
        
        // Initialize Native USB Bridge - CRITICAL for libusb_bridge.so to work
        try {
            NativeUsbBridge.isAvailable() // This triggers initialization in init block
            Log.i(TAG, "Native USB bridge initialized: ${NativeUsbBridge.isAvailable()}")
            
            // Test bridge functionality
            if (NativeUsbBridge.isAvailable() && usbDevices.isNotEmpty()) {
                val devicesJson = NativeUsbBridge.enumerateDevices()
                Log.i(TAG, "USB bridge enumerated devices: $devicesJson")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize USB bridge: ${e.message}")
        }
        
        // Open USB device connections and get file descriptors
        val usbDeviceManager = UsbDeviceManager.getInstance(context)
        val openDeviceFds = mutableMapOf<String, Int>()
        
        usbDevices.filter { device ->
            usbHostManager.hasPermission(device)
        }.forEach { device ->
            val fd = usbDeviceManager.openUsbDeviceFd(device.deviceName)
            if (fd != -1) {
                openDeviceFds[device.deviceName] = fd
                Log.i(TAG, "Opened USB device ${device.deviceName} with fd=$fd")
            }
        }
        
        // Store open FDs for later use
        this.openUsbDeviceFds = openDeviceFds
        
        Log.i(TAG, "provisioned $linked links from ${entries.size} manifest rows, bash=$hasBash, usbDevices=${usbDevices.size}, openFds=${openDeviceFds.size}")
        return hasBash
    }

    /**
     * Environment for [TerminalSession], following Termux's shape.
     *
     * Ordering matters: `$PREFIX/bin` and `$PREFIX/lib` are prepended so the packaged tools
     * win over anything on the system path. `LD_LIBRARY_PATH` carries [libDir] only, not
     * `nativeLibraryDir`: the manifest already publishes every library the payload
     * executables link against, and adding the install directory would expose unrelated
     * libraries to the exec'd tools.
     */
    fun buildEnv(): Array<String> {
        val systemPath = System.getenv("PATH").orEmpty()
        return buildList {
            add("TERM=screen")
            add("HOME=${homeDir.absolutePath}")
            add("PREFIX=${prefix.absolutePath}")
            add("TMPDIR=${tmpDir.absolutePath}")
            add("ADB=${File(binDir, "adb").absolutePath}")
            add("PATH=${binDir.absolutePath}:$systemPath")
            add("LD_LIBRARY_PATH=${libDir.absolutePath}")
            // Stock Android ships no terminfo database; without these bash and the renderer fall
            // back to hardcoded defaults and output is wrong on non-vt100 terminals.
            add("TERMINFO=${File(shareDir, "terminfo").absolutePath}")
            add("TERMINFO_DIRS=${File(shareDir, "terminfo").absolutePath}")
            add("INPUTRC=${File(etcDir, "inputrc").absolutePath}")
            // bash only sources BASH_ENV when non-interactive and ~/.bashrc when it is; the
            // bootstrap ships etc/bash.bashrc, so cover both. See linkBashrc().
            add("BASH_ENV=${File(homeDir, ".bashrc").absolutePath}")
            add("ENV=${File(etcDir, "profile").absolutePath}")
            add("EXTERNAL_STORAGE=/sdcard")
            add("PS1=\\u@\\h:\\w\\$ ")
            // USB: expose device count and device information so the payload adb can find
            // USB devices without root. The native libusb bridge handles the actual
            // UsbDeviceConnection -> file descriptor handoff.
            add("ADB_USB_DEVICE_COUNT=${usbDevices.size}")
            
            // Pass USB device information to adb
            usbDevices.forEachIndexed { index, device ->
                add("ADB_USB_DEVICE_${index}_NAME=${device.deviceName}")
                add("ADB_USB_DEVICE_${index}_VID=${device.vendorId}")
                add("ADB_USB_DEVICE_${index}_PID=${device.productId}")
                add("ADB_USB_DEVICE_${index}_VIDPID=${device.vidPidHex()}")
            }
            
            // First device environment variable for backward compatibility
            if (usbDevices.isNotEmpty()) {
                add("ADB_USB_FIRST_DEV=${usbDevices[0].deviceName}")
                add("ADB_USB_FIRST_VID=${usbDevices[0].vendorId}")
                add("ADB_USB_FIRST_PID=${usbDevices[0].productId}")
            }
            
            add("ANDROID_RAPID_FORWARD_START")
            add("ANDROID_USB_FS_PATH=/dev/bus/usb")
            add("LIBUSB_DEBUG=3")
            add("LIBUSB_OPTION_WEAK_AUTHORITY=1")
        }.toTypedArray()
    }

    /** Absolute path of a provisioned tool, or null when this build does not ship it. */
    fun tool(name: String): String? {
        val f = File(binDir, name)
        return if (f.exists()) f.absolutePath else null
    }

    /**
     * Parses the manifest into provider -> target list.
     *
     * The format is Java properties with line continuations:
     *
     *     key=\
     *       target,\
     *       target
     *
     * A backslash joins physical lines and a trailing comma separates targets, so the parse
     * strips the continuation first and then splits on the first `=`.
     */
    private fun readManifest(context: Context): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var key: String? = null
        try {
            context.assets.open(MANIFEST).bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim().removeSuffix("\\").trim().removeSuffix(",").trim()
                    if (line.isEmpty()) continue
                    val eq = line.indexOf('=')
                    if (eq >= 0) {
                        key = line.substring(0, eq).trim()
                        out.getOrPut(key!!) { mutableListOf() }
                        line.substring(eq + 1).trim().takeIf { it.isNotEmpty() }?.let {
                            out[key]!!.add(it)
                        }
                    } else if (key != null) {
                        // Continuation target for the row we are already inside.
                        out[key]!!.add(line)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "no readable $MANIFEST; adb-only payload assumed", e)
        }
        return out
    }

    /**
     * Unpacks asset [name] over [prefix], at most once per [stamp].
     *
     * The archive is copied to a real file first because [ZipFile] needs seekable input and
     * an asset stream is not seekable. It is deleted afterwards, so the only copy kept is
     * the unpacked tree.
     *
     * The marker is what makes this "at most once". `provision()` runs on every terminal
     * session start, and the staged copy is deleted once unpacked, so without a marker the
     * copy would be redone and re-extracted on each launch. That is merely wasteful for the
     * handful of files in [BOOTSTRAP], and catastrophic for the Python payload, whose
     * archive is far larger and is unpacked by [extractPythonPayload] under the same rule.
     *
     * The marker is written only after the last entry is written, so an unpack killed
     * midway leaves it absent and the next start retries. A partially written tree is
     * therefore possible but is never mistaken for a complete one -- the alternative, an
     * optimistic marker, silently ships a half-written tree that fails much later and
     * somewhere unrelated.
     */
    private fun extractOnce(context: Context, name: String, stamp: String) {
        val marker = File(prefix, ".$name.$stamp.done")
        if (marker.exists()) return
        val zip = File(prefix, name)
        try {
            context.assets.open(name).use { input ->
                zip.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "no $name in assets", e)
            return
        }
        if (unpackArchive(zip, name)) {
            try {
                // Best effort: failing to record the marker only costs a re-extract next time.
                marker.createNewFile()
            } catch (e: Exception) {
                Log.w(TAG, "could not write marker for $name", e)
            }
        }
        zip.delete()
    }

    /**
     * Unpacks [PYTHON_PAYLOAD] from [nativeLibDir] over [prefix], at most once per [stamp].
     *
     * Reads straight out of the install directory instead of staging a copy in the prefix
     * first, unlike the asset path in [extractOnce]: the package manager already put the
     * archive there, so a second copy would mean writing 60 MB twice on first launch and
     * holding 60 MB more on disk for the life of the install.
     */
    private fun extractPythonPayload(nativeLibDir: String) {
        val marker = File(prefix, ".$PYTHON_PAYLOAD.$PYTHON_STAMP.done")
        if (marker.exists()) return
        val payload = File(nativeLibDir, PYTHON_PAYLOAD)
        if (!payload.exists()) {
            // Not packaged for this ABI, so this install cannot run the payload anyway.
            Log.i(TAG, "no $PYTHON_PAYLOAD for this ABI; skipping Python payload")
            return
        }
        if (!unpackArchive(payload, PYTHON_PAYLOAD)) return
        // The archive lives in the install directory, so there is nothing of ours to clean up.
        try {
            marker.createNewFile()
        } catch (e: Exception) {
            Log.w(TAG, "could not write marker for $PYTHON_PAYLOAD", e)
        }
    }

    /**
     * Expands zip [archive] over [prefix]. Returns true only if every entry was written,
     * which is what lets the caller decide whether to record a completion marker: a
     * half-written tree must be retried, not remembered as done.
     */
    private fun unpackArchive(zip: File, name: String): Boolean {
        var unpacked = false
        try {
            // Unpack over $PREFIX, not $HOME: these are system-like trees ($PREFIX/etc,
            // $PREFIX/share/terminfo) and, for the payload, the interpreter, its shared
            // libraries and site-packages under $PREFIX/lib.
            ZipFile(zip).use { zf ->
                for (entry in zf.entries()) {
                    val out = File(prefix, entry.name)
                    // Refuse to write outside $PREFIX via a crafted "../" entry name.
                    if (!out.canonicalPath.startsWith(prefix.canonicalPath)) {
                        Log.w(TAG, "skipping $name entry outside prefix: ${entry.name}")
                        continue
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
            }
            unpacked = true
        } catch (e: Exception) {
            Log.w(TAG, "failed to unpack $name", e)
        }
        return unpacked
    }

    /**
     * Give every regular file in [binDir] the executable bit.
     *
     * `ZipEntry` carries no Unix mode bits on Android, so [extractOnce] writes every entry
     * through `File.outputStream`, which lands at 0644. That is correct for the data files
     * the archives are mostly made of, and fatal for the ones that land in `bin/`: the
     * `python3.11` interpreter and the `mtk` launcher would install cleanly and then fail
     * with EACCES the first time anyone typed `mtk`, which reads like a broken payload
     * rather than a lost permission bit.
     *
     * "Is a regular file in `$PREFIX/bin`" is the right test because that directory is on
     * `$PATH` by construction, so everything in it is meant to be runnable. It means no
     * second manifest of which archive entries are programs has to be kept in sync.
     *
     * Symlinks are skipped deliberately: `chmod` follows them, so a link would reach out of
     * the prefix and change the mode of the extracted provider in the install directory,
     * which is not this app's file to re-permission.
     */
    private fun chmodExecutablesInBin() {
        binDir.listFiles()?.forEach { f ->
            try {
                if (OsConstants.S_ISREG(Os.lstat(f.absolutePath).st_mode)) {
                    // 0x1ED is 0755. Neither `0755` nor `0o755` is usable here: Kotlin has no
                    // octal literal syntax, so `0o755` does not lex, and a bare `0755`
                    // silently means decimal 755 -- rwxr--r--, which no file on $PATH
                    // should get.
                    Os.chmod(f.absolutePath, 0x1ED)
                }
            } catch (e: Exception) {
                Log.w(TAG, "chmod ${f.name} failed", e)
            }
        }
    }

    /**
     * The archives ship `etc/bash.bashrc`, but an interactive bash reads `$HOME/.bashrc`.
     * Link the two so the payload's rc is actually sourced.
     *
     * The source is read from [etcDir], not from `homeDir`: every archive entry is unpacked
     * over `prefix`, so the file lands in `$PREFIX/etc/`. Looking for it under `$HOME/etc/`
     * finds nothing, [symlink] returns false, and `$HOME/.bashrc` is never created -- which
     * left `BASH_ENV` pointing at a path that did not exist.
     */
    private fun linkBashrc() {
        val source = File(etcDir, "bash.bashrc")
        if (source.exists()) symlink(source, File(homeDir, ".bashrc"))
    }

    /**
     * Point [target] at [source], replacing any existing link. Returns true if the link
     * points at [source] afterwards.
     *
     * Presence is probed with [Os.readlink] rather than [File.exists] on purpose: a link
     * left over from an install where its provider binary is missing is dangling, and
     * `File.exists` follows links so it would report false and the stale link would survive
     * [Os.symlink] with EEXIST. `readlink` also throws for a plain file, which is the right
     * answer too -- such a path gets replaced.
     */
    private fun symlink(source: File, target: File): Boolean {
        if (!source.exists()) return false
        try {
            target.parentFile?.mkdirs()
            val current = try {
                Os.readlink(target.absolutePath)
            } catch (e: Exception) {
                null // absent, or not a symlink
            }
            if (current != null) {
                if (current == source.absolutePath) return true
                target.delete()
            }
            Os.symlink(source.absolutePath, target.absolutePath)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "symlink ${target.name} -> ${source.name} failed", e)
            return false
        }
    }

    private const val SYSTEM_SHELL = "/system/bin/sh"
}
