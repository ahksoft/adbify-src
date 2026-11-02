# Adbify — Agent Guide

Android app that runs a real `adb` binary inside an on-screen terminal. Java + Kotlin,
Groovy Gradle DSL, two modules.

## Gradle: `./gradlew` works in the working tree, but HEAD still holds a corrupted copy

The `gradlew` in **git HEAD** — and therefore a fresh clone or `git checkout gradlew` — has had
line breaks and spaces injected throughout it, mangling both the Apache license comment and the
actual shell (`app_path = $0`, `CLASSPATH = $APP_HOME / gradle / wrapper / gradle - wrapper.jar`).
Running it executes prose (`You: not found`, `Bad substitution`). It fails under `bash` too, and
`/bin/sh` is `dash` here. The corruption is in HEAD, not a local edit, so checkout does not fix it.

The working tree has been restored from the canonical Gradle 8.1.1 wrapper
(`raw.githubusercontent.com/gradle/gradle/v8.1.1/gradlew`), so `./gradlew` works. If it starts
failing with those errors again, restore it the same way. `gradlew.bat` is unaffected.

```bash
source ~/.android_env
./gradlew <args>
# fallback if gradlew is corrupted:
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain <args>
```

`~/.android_env` provides `JAVA_HOME` (JDK 17), `ANDROID_SDK_ROOT`, and `ANDROID_NDK_HOME`.
There is **no `local.properties`** in this repo; the env vars are the only SDK wiring.

## Build

```bash
./gradlew assembleDebug -PskipNdkBuild
```

Output lands in `app/build/outputs/apk/debug/`. ABI splits are on with `universalApk`,
so you get `app-universal-debug.apk` plus one per ABI (`arm64-v8a`, `armeabi-v7a`, `x86`,
`x86_64`).

**There are no tests.** No `test/` or `androidTest/` source sets exist. Verification is
`assembleDebug` plus checking the APK contents.

## Native toolchain: this host is aarch64 and the NDK's host tools cannot run

`ndk-build` and AGP's `llvm-strip` both need NDK *host* binaries, and neither NDK works
on an aarch64 Linux box:

| NDK | Failure |
| --- | --- |
| `25.2.9519653` | `Unknown host CPU architecture: aarch64` — no `aarch64` case in `build/tools/ndk_bin_common.sh`, so it dies during configure |
| `26.1.10909125` | Recognises the host, but `toolchains/llvm/prebuilt/linux-aarch64/bin/*` are **Android** binaries (`readelf` shows interpreter `/system/bin/linker64`) and cannot exec |

The sysroot is unaffected — it is just data. `scripts/build-native-libs.sh` therefore
drives the host `clang` (18.x) and a host `ld.lld` against the NDK sysroot, mirroring the
flags in `terminalview/build.gradle`:

```bash
./scripts/build-native-libs.sh          # -> terminalview/src/main/jniLibs/<abi>/libadbify-term.so
```

Then `-PskipNdkBuild` in `terminalview/build.gradle`:
- drops the `externalNativeBuild.ndkBuild` blocks so Gradle stops invoking `ndk-build`
- disables `strip*DebugSymbols` — `debugSymbolLevel 'NONE'` does **not** disable that task
  in AGP 8.0, and unlike `:app` a library module treats the strip failure as fatal

The script borrows `ld.lld` from the rustup toolchain because no NDK-provided one runs here.
It fails loudly if any ABI is missing or any JNI entry point is unexported — a
`libadbify-term.so` that links but lacks `Java_com_adbify_terminal_JNI_*` loads and then
crashes at `System.loadLibrary`, so verify symbols after touching `term.c`.

Without `-PskipNdkBuild` the standard `ndk-build` path is unchanged and still correct on a
normal x86_64 machine.

## Architecture

- `app/` — application. `applicationId ahk.unitool` (renamed from `com.rv882.adbify`) but
  **`namespace com.adbify`** and every source package is still `com.adbify`. That mismatch is
  intentional, so don't "fix" it casually: renaming the namespace would move every source
  file, change the R class, and invalidate the JNI symbol names in `terminalview`. Only
  `applicationId` is the installed package.

  Renaming `applicationId` is a one-line change and everything downstream follows
  automatically, because nothing hardcodes the package: `NativePayload` derives `filesDir`
  and `nativeLibraryDir` from `Context`, and the `FileProvider` authority is
  `${applicationId}.fileProvider`. Verified in the built APK: the authorities become
  `ahk.unitool.fileProvider`, `ahk.unitool.mobileadsinitprovider` and
  `ahk.unitool.androidx-startup` with no code change.

  **Changing `applicationId` changes the app's data directory**, so it is a different app on
  device: `files/home` and `files/usr` (see below) start empty and the payload is
  re-provisioned on next launch. There is no migration from a previously installed
  `com.rv882.adbify`.
- `terminalview/` — a fork of Jack Palevich's terminal-view (the Termux one), namespaced
  `com.adbify.terminal`. Emulator, renderer, PTY session management, plus `jni/term.c`
  (fork/exec on `/dev/ptmx`, winsize, `waitpid`). `JNI.LIB_NAME = "adbify-term"`.

### How `adb` gets into the app

This is the core trick and is not obvious from filenames. There is no adb source here —
only prebuilt binaries committed under `app/src/main/jniLibs/<abi>/`, each named
`lib<tool>.so` so the APK installer gives it an executable bit.

`NativePayload.provision()` (called from `TerminalService.getOrCreateTerminalSession()`)
reads `assets/lib_symlinks.properties` — a continuation-style manifest mapping each provider
binary to the tool names it should answer to:

```
libadb.so=\
  bin/adb
libcoreutils.so=\
  bin/ls,\
  bin/cp,\
  ... 25 entries
```

It recreates those symlinks and returns the env for the shell. Three details
are easy to get wrong:

- **`lib/` links and `LD_LIBRARY_PATH` are load-bearing, not tidiness.** The arm64 `libadb.so`
  is a USB-capable build linked against `libusb.so` + `libusb_bridge.so`, which do not exist on
  stock Android. Without them on `LD_LIBRARY_PATH` the exec'd `adb` fails to load.
- **The symlink *name* is the dispatcher for multi-call binaries.** `libcoreutils.so` is one
  blob answering to 25 names because GNU coreutils dispatches on `basename(argv[0])`. Do not
  "clean up" those 25 links to one link named `coreutils`.
- **`bin/sh` → `libbash.so`, and the shell is no longer `/system/bin/sh`.** `NativePayload`
  spawns payload bash where available and falls back to the system shell otherwise. bash needs
  `TERMINFO`/`INPUTRC`/`ENV` pointed into `usr/etc` and `usr/share`, because Android ships no
  terminfo database.

### `files/` layout is Termux-shaped: `home` + `usr`

`NativePayload` builds the same two-directory split Termux uses, and the manifest's *relative*
targets are what force it — `bin/adb` and `lib/libusb.so` can only mean something against a
prefix, not against `files/` itself:

```
files/home   -> $HOME, the user's working directory (stays empty until they use it)
files/usr    -> $PREFIX, the system-like tree owned by the terminal
  usr/bin    -> 35 tool symlinks (adb, bash, sh, ls, cp, fastboot, qdl, heimdall, ...)
                 (13 manifest rows -> 39 links total: 35 in bin/, 4 in lib/)
  usr/lib    -> shared libraries (libusb.so, libusb_bridge.so, libpty.so, ...)
  usr/etc    -> bash.bashrc, profile, inputrc from bootstrap.zip
  usr/share  -> terminfo, tabset from bootstrap.zip
  usr/tmp    -> $TMPDIR
```

So **`usr/bin` on `PATH` resolves the manifest's `bin/…` targets, and `usr/lib` is
`LD_LIBRARY_PATH`.** `bootstrap.zip` is unpacked over `usr/`, never `home/` — it is a
system-like tree, not user data, and `home/` must stay clean so `ls` there looks empty.

`PREFIX` is exported alongside `HOME`/`PATH`, matching Termux so profile scripts and
user-installed tooling can rely on it. `$HOME/.bashrc` is a symlink to `usr/etc/bash.bashrc`,
since interactive bash reads the former and the payload ships the latter.

`MainActivity` never runs these tools — it only binds the service and forwards pasted text
into the emulator.

### The payload is arm64-only, and that is deliberate

Only `arm64-v8a` gets the full 15-binary toolchain (USB adb, fastboot, qdl, heimdall,
mke2fs/make_f2fs, bash, coreutils). The other three ABI splits keep only the original
network-only `libadb.so`. `NativePayload` skips manifest rows whose provider binary is not
packaged, so those builds still get a working `adb` on `PATH` and the system shell. **Do not
copy the arm64 blobs into the other ABI dirs** — the USB adb needs `libusb.so`, and without it
`libadb.so` will not load.

This also means **ABI splits are load-bearing**: only per-ABI APKs keep the payload contained.
Current debug builds are arm64 ~62 MB, the other three splits ~10–11 MB each, universal ~69 MB —
the ~45 MB Python payload is in one split only (see below).

## The `mtk` Python payload (`libpython-payload.so`)

mtkclient + CPython 3.14 ship as a **zip in `jniLibs/arm64-v8a/` named
`libpython-payload.so`**. On first launch `NativePayload` expands it over `$PREFIX`,
giving `$PREFIX/bin/mtk`. Build and verify it with the pipeline in `/root/UniTool-dev/mba`
(`build-all.sh`, then `verify.sh`) — it lives outside this repo, since it needs a separate
cross toolchain.

The interpreter is **upstream's own `aarch64-linux-android` release**, pinned by version and
sha256 in `mba/deps/python-release.txt`, not a local cross-build. That was a deliberate change
and the difference is not cosmetic: the old cross-build had no OpenSSL, so `import ssl` failed
and pip could only install from a local path, plus no libffi (bionic exports no ffi symbols, so
libffi had to be cross-built for `_ctypes`) and 82 MB of stdlib against upstream's 39 MB pruned.
Upstream ships `libssl_python.so` + `libcrypto_python.so`, links libffi into `_ctypes`
statically, and is smaller — so `build-libffi.sh` is gone from the pipeline and `bin/pip` can
reach PyPI.

Two consequences for anything touching the payload:

- **The release ships no interpreter binary.** Python on Android is embedded-mode only, so
  upstream's package is a libprefix: `libpython3.14.so` plus stdlib. `$PREFIX/bin/python3.14`
  is a three-line embedding host around `Py_BytesMain` built by `mba/build-python.sh`, and it
  **must** be named exactly that — CPython derives `sys.prefix` from the executable's own path
  via the `<prefix>/lib/pythonX.X` landmark, so the name is load-bearing, not cosmetic.
- **OpenSSL is resolved by plain soname with no RPATH**, so `libssl_python.so` /
  `libcrypto_python.so` are unpacked into `$PREFIX/lib` and found only because `buildEnv()`
  puts that on `LD_LIBRARY_PATH` — the same mechanism the payload's `libusb.so` already needs.

**`libpython-payload.so` is gitignored.** It needs a cross toolchain to rebuild (host
`clang`, a synthetic resource dir, rustup's `ld.lld`), so it cannot be reconstructed from
this repo anyway, and ~45 MB is an order of magnitude more than anything committed here.
A fresh clone therefore builds fine but has no `mtk` until `/root/UniTool-dev/mba/build-all.sh`
is run — `NativePayload` skips the payload when `nativeLibraryDir` has no such file, so the
app degrades to plain `adb` rather than failing.

It is in `jniLibs`, **not** `assets`, and that placement is the whole point: `jniLibs` is
split per ABI and `assets` is not. The payload is ~45 MB of arm64 ELFs, and as an asset it
was 87% of the x86/x86_64/armeabi-v7a APKs for something those devices cannot run. With it
in `jniLibs` those splits are back to ~10 MB and only arm64 carries it:

| split | with payload in `assets` | in `jniLibs` |
| --- | --- | --- |
| arm64-v8a | 75.5 MB | 62 MB |
| armeabi-v7a | 68.4 MB | **10.5 MB** |
| x86_64 | 69.1 MB | **11.2 MB** |
| x86 | 69.0 MB | **11.1 MB** |
| universal | 82.1 MB | 69 MB |

The `assets` column was measured against the earlier CPython 3.11 payload and is kept for the
ratio it demonstrates; the `jniLibs` column is the current build. The figure barely moved even
though the payload changed interpreter, because the payload *shrank* from 64 MB to ~45 MB when
the cross-build was replaced by upstream's — while gaining OpenSSL and a working pip.

The `lib*.so` name is what makes the installer extract it — same convention as the payload's
real executables (`libadb.so` is not a library either). It is excluded from the "publish
unlisted `.so` into `lib/`" loop in `NativePayload`, because it is an archive, not something
the dynamic linker loads.

Four things about this payload are easy to get wrong:

- **`ZipEntry` carries no Unix mode bits on Android.** Every unpacked file lands at 0644,
  so `bin/mtk` and `bin/python3.14` would install fine and then fail `EACCES`.
  `chmodExecutablesInBin()` fixes this by chmod'ing regular files in `bin/`, and deliberately
  skips symlinks since `chmod` follows them.
- **Unpacking is marker-guarded** (`.<name>.<stamp>.done` in `$PREFIX`). `provision()` runs
  on every session start and the staged copy is deleted after unpacking, so without a marker
  120 MB is re-extracted on every launch. Bump the `*_STAMP` constants when the payload
  changes or the new one never reaches an existing install.
- **The shebang hardcodes `/data/data/<applicationId>/files/usr`.** `package.sh` reads
  `applicationId` from `app/build.gradle` and `verify.sh` asserts it against the built APK's
  packagename, because a mismatch installs cleanly and then dies with `ENOENT` on every
  `mtk`.
- **`Crypto` and `Cryptodome` are both real trees, not one plus a symlink.** pycryptodome
  builds exactly one namespace per run, and the payload must ship both. A `Crypto ->
  Cryptodome` symlink cannot survive: `ZipEntry` gives no way to see that an entry is a
  symlink, so the unpack would write the *link text* into `site-packages/Crypto` as a 10-byte
  file and every `from Crypto...` would fail as "not a package". Hence `package.sh` resolves
  every symlink into a real copy and then **fails the build** if any survive.

### Installing packages with pip

`pip` and `pip3` are on `$PATH` and reach PyPI over HTTPS. `python -m pip` works too. Both are
the pip wheel from the interpreter's own `ensurepip/_bundled`, so the version always matches the
release rather than being whatever happened to be lying around.

```bash
pip install <package>          # from PyPI
pip install -r requirements.txt
pip install ./local-wheel.whl
```

**Do not run `pip install -r requirements.txt` from the mtkclient checkout.** It cannot work,
and one line of it actively breaks a working install. Its 15 lines break down as:

| line | requirement | what happens |
| --- | --- | --- |
| 7 | `shiboken6` | no Android wheel on PyPI; only a `PySide6` dependency. This is the line the install stops on |
| 8 | `pyside6` | no Android wheel on PyPI, ever |
| 13 | `unicorn` | never imported by mtkclient; its only wheel is `manylinux_2_17_aarch64`, i.e. glibc |
| 14 | `keystone` | **not `keystone-engine`.** It is OpenStack's identity service, and it installs a top-level `keystone/` package with an *empty* `__init__.py`, replacing the assembler binding in the payload. `Library/asmtools.py` does `from keystone import Ks`, so this turns a working `mtk` into an `ImportError` |
| 1, 2, 10 | `wheel`, `setuptools`, `flake8` | would install fine (pure-Python wheels) and are build/lint tools `mtk` never touches |

The remaining **8 are already satisfied by the payload** — `pyusb`, `pycryptodome`,
`pycryptodomex`, `colorama`, `pyserial`, `keystone-engine`, `capstone`, `mfusepy` — so the
command has nothing to accomplish except fail or break something. Just run `mtk`.

That "already satisfied" is metadata, not luck. Each shipped package carries a PEP 376
`.dist-info`, generated offline by `mba/make-distinfo.py` from the sdist `PKG-INFO` or the
`setup.py install --root` egg-info. Without it the packages are invisible to pip, so
`pip install -r` would refetch the pycryptodome sdist and try to rebuild what is already there.
`check-distinfo.py` (run from `verify.sh`) asserts every importable top-level in site-packages is
owned by a parseable `.dist-info` whose `RECORD` lists files that exist.

### Installing *other* packages

What works:

- **Pure-Python packages, wheel or sdist.** An sdist fetches its PEP 517 build backend from PyPI
  at install time and needs no compiler.
- **Local wheels and paths**, including `pip install -r req.txt` where the entries are paths.

What does not, and this is a property of Android rather than of this payload:

- **Anything with C extensions.** PyPI publishes no Android wheels, so pip either picks a
  `manylinux_*_aarch64` wheel — glibc-linked, needing `libc.so.6` and `libpthread.so.0`, neither
  of which exists on bionic — or falls back to building from source, and there is no compiler in
  the terminal. Both fail *late*: pip reports success and the failure surfaces as a `dlopen`
  error on first import. `--only-binary=:all:` converts that into an immediate, honest
  "no matching distribution" instead.
- Note that `--target` makes pip reinstall even packages that are present, so it is the wrong
  tool for checking whether something is already available; `pip list` is.
- `verify.sh` asserts there is **no `EXTERNALLY-MANAGED` marker** in the prefix. If one ever
  appeared, every `pip install` would fail with `error: externally-managed-environment` while
  `pip --version` kept working, which reads as "pip is broken" rather than as a packaging
  change.

### Verifying the Python payload

No Android binary can be exec'd on this host, so there is no runtime check. `verify.sh` is
the substitute and it asserts nine things, the two that actually catch breakage being the
import walk and the dynamic-symbol audit:

```bash
/root/UniTool-dev/mba/verify.sh    # exits non-zero on any failure
```

- the zip unpacks byte-identically to the staged tree, with no symlinks
- the shebang is under the 127-byte kernel limit and resolves to a file in the payload
- all 140 ELF files are aarch64; `PT_INTERP` is required only of `EXEC`, since a shared
  library correctly has no interpreter
- **every import reachable from `mtk_main` resolves in the packed tree** (64 internal modules)
- the runtime pieces loaded via `ctypes` are present and export their symbols
- pip's prerequisites are present: `_ssl`, the vendored CA bundle, no `EXTERNALLY-MANAGED`
  marker, and a default target inside this prefix — plus **every shipped package carries
  `.dist-info` pip can parse**, or pip would refetch and rebuild over working trees
- pyusb can find `libusb-1.0.so` (the manifest publishes it; `usblib.py` hardcodes that name)
- **both `sys.platform == 'android'` patches are present, exactly once each**, **both
  `mfusepy` guards catch `OSError`**, and **no `.pyc` ships** (see below)
- the APK carries the validated archive, and it has not leaked into other ABIs' splits

Three bugs in that checker are worth knowing about, because each made it pass while reporting
something false:

- The import walk must resolve `mod_to_path` against `PKGROOT` **with the leading
  `mtkclient` stripped**. `PKGROOT` is already `site-packages/mtkclient`; joining the full
  dotted name looks up `mtkclient/mtkclient/…`, every absolute import reports unresolved,
  and the walk visits 1 file instead of 64.
- It must distinguish **module-level** imports from deferred ones. A blanket `ast.walk`
  reports `seccfg.py`'s `from hwcrypto import …` — which is inside
  `if __name__ == "__main__":` and never runs on import — as a hard failure.
- Section 7b's occurrence count cannot be written as
  `n=$(grep -c -- "$needle" "$f" || echo 0)`. `grep -c` prints `0` *and* exits non-zero on
  no match, so `|| echo 0` appends a second line, `n` becomes `0\n0`, and the comparison dies
  with `integer expression expected` — which hides the FAIL it was meant to report.

### `sys.platform` is `'android'`, and two shipped packages assume `'linux'`

**This is the one thing that makes the payload differ from every other Python-on-Android
build, and it was found by running `mtk` on a device.** CPython's official
`aarch64-linux-android` build reports `sys.platform == 'android'`. That is correct and the
stdlib handles it, but third-party code predating Android as a CPython target guards on
`sys.platform.startswith('linux')` and falls through every branch.

Termux does not hit this because its Python is built as *linux*, so `sys.platform` is
`'linux'`. That is why the widely-cited `davidjakus83/termux-mtkclient` port needs no such
patch — it is a property of the interpreter, not of the port. Don't take that repo as evidence
that no patch is needed here; it also assumes **root**, which sidesteps the permission
question entirely.

Two gates are fatal, both found the same way — by the first two crashes of `mtk`:

| site | failure | why it is fatal |
| --- | --- | --- |
| `serial/tools/list_ports_posix.py` | `plat[:5] == 'linux'` is false, every branch skipped, module raises `ImportError` after printing "don't know how to enumerate ttys" | `mtkclient/Library/Connection/seriallib.py:11` imports it at **module** level, so merely starting `mtk` aborts |
| `mtkclient/Library/Connection/usblib.py:132` | the freebsd/linux/darwin test *and* the win32 test both fail, so `self.backend` is **never assigned** | the next line's `if self.backend is not None` raises `AttributeError`. This is the libusb path — exactly the `libusb-1.0.so` the payload ships and publishes |

`patch-android.py` fixes both, by asserted substitution rather than by an unverified `sed`.
Patches are applied **during the build** (pyserial in `build-deps.sh`, mtkclient in
`package.sh`) rather than committed into the upstream checkouts, so `mtkclient` and the
sdists stay pristine and a version bump surfaces as a build failure rather than silently
dropping the fix.

Three things about that script that are load-bearing:

- **The `old` text must be asserted, and the count checked *after* an applied-marker test.**
  For the pyserial patch `old` is a *prefix* of `new` — the Linux branch survives inside the
  replacement — so a second run matches again and inserts a **duplicate** Android branch. That
  duplicate still parses, so nothing downstream notices. Idempotence comes from a marker
  string unique to the patched state, tested before counting.
- **`package.sh` applies the mtkclient patch before generating `.dist-info`**, because `RECORD`
  stores a hash of `usblib.py` and the patch rewrites it.
- **The pyserial branch globs device nodes directly** (`/dev/ttyUSB*`, `/dev/ttyACM*`,
  `/dev/ttyS*`) rather than delegating to `list_ports_linux`, which walks sysfs/udev and
  expects a layout Android does not provide. `list_links()` still resolves
  `/dev/serial/by-id/*`, which is where Android puts stable USB serial names.

Enumeration succeeding is not the same as opening succeeding. On stock Android `ueventd`
gives `/dev/ttyUSB*` to group `usb` with mode 0660, and an app is not in that group, so `mtk`
may still fail `EACCES` even with `--port`. Expect to need that plumbing regardless.

### `mfusepy` raises `OSError`, and upstream only catches `ImportError`

The next crash after the two platform gates, and a different shape of problem: not a
platform check, but an **exception type** mismatch.

`mtkdafs.py` already handles "fuse is not installed" — it catches `ImportError`, prints
`fuse library not installed`, and substitutes a stub `MtkDaFS`. `mtk_da_handler.py:19-22`
does the same with `FUSE = None`, and the call site at `:1414` already tests
`if FUSE is not None`. The optional-fuse design is entirely intact.

It does not fire, because `mfusepy` is *installed* and still fails. At module level it calls
`find_library('fuse')`, finds nothing on Android, and raises **`OSError('Unable to find
libfuse')`** — which is not an `ImportError`. It escaped an ordinary `from mfusepy import …`
and killed `mtk` during import, despite three pieces of upstream code that were written
specifically to survive this exact situation. Both guards now catch `(ImportError, OSError)`.

Catching `OSError` is the fix rather than shipping a `libfuse`, because the feature cannot
work here anyway: FUSE needs `CAP_SYS_ADMIN` or a setuid `fusermount`, and `allow_other=True`
additionally needs `user_allow_other` in `fuse.conf`. An app gets neither. Shipping libfuse
would only move the failure from import time to the mount.

Note that `mtk fs` then becomes a **silent no-op** — that is upstream's own behaviour when
mfusepy is absent, not something the patch introduces, but it is worth knowing before
concluding the command hung.

### No `.pyc` ships, and `compileall -b` here produced unusable ones

`build-deps.sh` and `package.sh` used to run `compileall -b` with `$HOSTPY/bin/python3.11`,
writing `foo.pyc` *beside* `foo.py` — 143 of them. `$HOSTPY` is **3.11**; the interpreter on
the device is **3.14**. Every file carried magic `0xa0d0da7` (3.11's), and CPython validates
the magic and silently recompiles from source on a mismatch, so **all 143 were ignored**:
1.0 MB of compressed dead weight in the archive.

Worse than useless, though: it was a stale pre-patch copy of every file `patch-android.py`
rewrites, sitting right beside the patched source (`list_ports_posix.pyc` recorded size 4535
against the patched 5447). The *only* reason the device ran the patched code is the magic
mismatch. Valid 3.14 bytecode cannot be produced on this host at all, since that needs the
arm64 interpreter we cannot exec.

The original rationale — "so the tree still imports if the prefix is ever mounted read-only" —
does not hold either: `$PREFIX` is writable, `pip` installs into it, and `NativePayload`
unpacks into it. Both `compileall` calls are gone and `package.sh` now strips any `.pyc`
payload-wide as a backstop; `verify.sh` section 7b fails if one reappears.

Still unverified without a device:

- **That `import ssl` succeeds**, i.e. `libssl_python.so`/`libcrypto_python.so` resolve off
  `LD_LIBRARY_PATH`. They carry no RPATH, so this depends on `buildEnv()`.
- **That `ctypes.CDLL` succeeds** against libkeystone/libcapstone/libusb.
- **That the Android patches clear the crash on real hardware.** They are verified here
  only by simulating the device — forcing `sys.platform = 'android'` under a host interpreter
  turns the `ImportError` into `comports()` returning ports. That proves the branch is taken
  and the code runs, not that the phone takes it.
- That `mtk` reaches real hardware, or can open a tty at all.

Two things that the device run *did* settle, and which had been the project's largest
unknowns: **the interpreter starts**, and **`sys.prefix` relocates** to
`/data/data/ahk.unitool/files/usr` rather than the compiled-in `/usr/local`. The traceback
paths are `$PREFIX/lib/python3.14/site-packages/…`, so `getpath()` did derive the prefix from
the executable's own path. `bin/mtk`, its shebang and the stdlib all work.

### USB access still needs runtime plumbing

The manifest declares `<uses-feature android:name="android.hardware.usb.host"
android:required="false"/>` so the app is not filtered off non-USB devices. That is the
*declaration* only: there is no `UsbManager` code yet, so the USB-capable adb cannot actually
claim an interface until a permission request and a default-host-app/`/dev/bus/usb` story are
added. Treat raw USB as **not yet working on device**.

### Startup ordering

`TerminalInitProvider` is a `ContentProvider` that initialises `TerminalContextProvider`
during app start. `JNI`'s static block falls back to `ReLinker` using that context, so the
provider is load-bearing, not decoration.

## Build config quirks

- **`values/themes.xml` is generated** by the Material Theme Builder plugin from
  `materialThemeBuilder { primaryColor = "#FF8000" }` in `app/build.gradle`. Change the
  colour there, not in the XML. `values-v31/themes.xml` adds dynamic colours.
- **Release runs R8** (`minifyEnabled true` + `shrinkResources true`) but
  `app/proguard-rules.pro` is only `-dontobfuscate` and the
  `-keep class com.adbify.terminal.** { *; }` line is commented out. `-dontobfuscate`
  keeps names intact; shrinking is still active, so JNI-reachable classes are the risk
  area if you touch the terminal packages.
- `TerminalService` runs as a foreground service on notification channel `882` and kills
  the session from its notification action; `MainActivity` sets
  `AdbifyApp.adShowEnabled = false` while a file attach is in flight.

## Traps

- `app/src/main/assets/commands` is **not referenced by any code** — it is a leftover
  scratch list, not a feature. Lines 34–35 contain non-ASCII homoglyphs (`adb –help` uses
  an en-dash, `adb get-statеget-statе` uses Cyrillic `е`). Never copy commands out of it.
- **Never point a host interpreter at the staged prefix.** Running `python3 -m pip` with
  `PYTHONPATH` set to `mba/work/stage/.../site-packages` — which is the only way to test the
  payload's own pip from here — writes `__pycache__` into it. Those land *after* the zip was
  packed, so the staged tree and the validated zip stop agreeing and `verify.sh` section 1
  fails with a file-count mismatch and nothing edited. Use `PYTHONDONTWRITEBYTECODE=1`, or
  unpack `dist/python.zip` into `/tmp` and test that instead. `package.sh` strips
  `__pycache__` and any `.pyc` before packing as a backstop — and note there is deliberately
  **no** `compileall` step: the host interpreter is 3.11 and the device's is 3.14, so no
  bytecode built here is usable (see "No `.pyc` ships" above).
- Build output goes to `app/build/outputs/`. There is deliberately no committed
  `output-metadata.json` — one leaked in at `app/release/` claiming
  `com.rv882.adbify` and versionCode 36 and was deleted, since a checked-in build artifact
  can only ever be wrong.
- **`app/src/main/assets/dexopt/` is build output, not a source asset.** AGP writes the
  baseline profile (`baseline.prof`/`.profm`, ~2 KB) there on every build and `.gitignore`
  excludes it. It is regenerated, so never commit it or hand-edit it.
- `scripts/` is not in the original upstream repo — `build-native-libs.sh` is
  container-local tooling for the aarch64 host issue.
- **`new-feature/` is the undeleted drop folder the native payload was copied *from*.** It is
  untracked and redundant with `app/src/main/jniLibs/arm64-v8a/` + `app/src/main/assets/`.
  If you update the payload, copy it into both destinations — the app does not read
  `new-feature/` — and do not edit the binaries there expecting them to take effect.
- **`uniui.zip` (repo root) is an unrelated React/Vite web UI drop, untracked and referenced
  by no code.** It is not part of the Android build; ignore it and do not add it to the
  Gradle project or ship it as an asset.

## Verifying the native payload (no tests exist)

`assembleDebug` proves nothing about the toolchain, so check the two things that actually
break:

```bash
# every provider + asset shipped, per ABI
unzip -l app/build/outputs/apk/debug/app-arm64-v8a-debug.apk | grep -E '^ .*lib/|assets/'

# the payload adb's dynamic deps are all satisfiable from lib/ via LD_LIBRARY_PATH
readelf -d app/src/main/jniLibs/arm64-v8a/libadb.so | grep NEEDED
```

The second check is the important one: `libusb.so` and `libusb_bridge.so` are not on a stock
device, so a `libadb.so` whose `NEEDED` list has grown without a matching `lib/` link yields
an APK that installs fine and then fails every `adb` invocation.

The manifest parse is also the thing most likely to silently regress. Its format is
properties-with-continuations, so `key=\` then indented comma-terminated targets — a naive
split on `=` or `,` mangles it.

## Licensing

GPL-3.0-or-later. Note the parent directory `/root/UniTool-dev/UniTool-Android` is a
**separate AGPL-3.0 project** (UniTool); its `AGENTS.md` and penumbra/USB work do not
apply here, and the two must not share license assumptions.
