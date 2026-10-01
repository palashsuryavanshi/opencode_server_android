# Resuming this project on another machine

Everything below was verified on the machine this was last built on. Nothing in the repository
references an absolute path from that machine, and the Gradle wrapper is committed, so a fresh
checkout builds with `./gradlew` and no manual setup beyond the toolchain.

## Toolchain requirements

| Requirement | Version used | Notes |
| --- | --- | --- |
| JDK | Android Studio's bundled JBR (25.0.3) | **Use the JBR, not a system JDK 17.** The app compiles fine on 17, but `testDebugUnitTest` runs Robolectric, which requires Java 21+. On 17 the Robolectric test fails with `Unsupported class file major version` / a Java-21 requirement error. |
| Gradle | 9.7.1 | Provided by the wrapper. Do not run a system Gradle. |
| Android SDK | compileSdk 36.1, minSdk 24, build-tools 36 | `compileSdk` is `release(36) { minorApiLevel = 1 }`, so plain SDK 36 alone is not enough. |
| NDK | 27.0.12077973 installed, but the build uses 28.2.13676358 | AGP selects the NDK itself. If it is missing, AGP downloads it once given accepted SDK licences. Required because of `externalNativeBuild` — there is no non-native fallback in the build. |
| CMake | 3.22.1 | Provided by AGP. |

`local.properties` points at this machine's SDK. Android Studio rewrites it on open, or set it
manually:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

## Commands

```bash
# Everything that is normally run. Produces the debug APK and runs all 196 unit tests.
./gradlew :app:assembleDebug :app:testDebugUnitTest

# Native PTY only (all four ABIs).
./gradlew :app:externalNativeBuildDebug

# Linux userland for one ABI. Downloads ~41 MB, writes a ~66 MB tar.gz.
./gradlew :app:bootstrapUserland -PuserlandAbi=arm64-v8a
```

Useful `-P` properties for the userland task:

| Property | Default | Purpose |
| --- | --- | --- |
| `userlandAbi` | first of `userlandAbis` | Which ABI to build, e.g. `x86_64` |
| `userlandAbis` | `arm64-v8a,armeabi-v7a,x86_64,x86` | Which ABIs the release build packages |
| `userlandSeedPackages` | see `app/build.gradle.kts` | Override the seed package set |
| `userlandAllowPartial` | `false` | Accept a payload with no git/Node/npm instead of failing |

## Last verified state

- `:app:assembleDebug` — **passes**. APK is ~100 MB with the bundled aarch64 userland.
- `:app:testDebugUnitTest` — **213 tests, 0 failures**, 19 suites (was 196/17 before the installer).
- `:app:externalNativeBuildDebug` — **passes**, `libopencodepty.so` built for
  arm64-v8a, armeabi-v7a, x86, x86_64, all 7 JNI symbols exported and verified with
  `llvm-objdump -T`.
- `:app:bootstrapUserland -PuserlandAbi=arm64-v8a` — **passes**. 58 packages, 41 MB downloaded,
  5221 files and 1288 symlinks extracted, payload 66.2 MB, `bin/bash` verified present.

## What was added in the last session

| Area | Files |
| --- | --- |
| Native PTY | `app/src/main/cpp/pty_bridge.cpp`, `app/src/main/cpp/CMakeLists.txt`, JNI surface in `ForkPtyHost.kt` |
| ProGuard | JNI keep rules in `app/proguard-rules.pro` |
| Userland bootstrap | `buildSrc/` — `ArArchive.kt`, `TarReader.kt`, `TarWriter.kt`, `TermuxRepository.kt`, `BootstrapUserlandTask.kt` |
| Gradle | `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`; `externalNativeBuild` + `abiFilters` + userland task wiring in `app/build.gradle.kts` |

Roughly 50 backend source files under `app/src/main/java/com/example/backend/` and 13 test files
under `app/src/test/java/com/example/backend/` predate this session.

## Known issues, stated honestly

1. **119 archive entries are skipped during the userland build** (103 under
   `lib/node_modules/npm/node_modules/`, 16 `termux-exec`/`termux-core` development headers). The
   cause is a file/directory collision when unpacking onto a Windows checkout, where a host file
   already occupies a path the archive wants to be a directory. None of it is a runtime binary.
   On a Linux or macOS build host this should not occur, but it has only been *observed* on Windows.
2. **Symlinks are recorded, not created, on the build host.** A Windows machine cannot create them
   without developer mode, and a POSIX target like `/system/bin/sh` is not a valid Windows path.
   They are written as real symlink entries in the tar and **must be recreated on device** — see
   the outstanding item below.
3. **The app-side installer is done.** `UserlandInstaller` (`com.palash.opencodeserver.backend.userland`)
   reads `assets/userland/<arch>.tar.gz` + `<arch>.manifest`, verifies SHA-256, extracts into
   `userland/<triple>` with a canonical-path traversal guard, recreates symlinks, and verifies
   `bin/bash` (spec 113). `ProvisionedUserlandProvider` delegates to it; the legacy `.tar.zst`
   + host-tar path remains as fallback when no installer is injected. Covered by 17 unit tests
   (`UserlandArchiveTest`, `UserlandInstallerTest`), full suite 213 green.
4. **`RUNTIME.md`, `SECURITY.md` and `THIRD_PARTY_NOTICES.md` still describe the old, unbuilt
   state.** They need updating to match what now exists.
5. **`MainActivity` is not wired to `BackendFactory.create(context)`.** The frontend is still on its
   own mocks. `BACKEND_INTEGRATION.md` is the guide; it predates the userland work.
6. `usesCleartextTraffic="true"` still permits cleartext to any host. A release should narrow this
   to loopback with a network security config.
7. `debug.keystore` was generated into the project root and is gitignored. If it is missing on the
   new machine, `validateSigningDebug` fails; recreate it with:

   ```bash
   keytool -genkeypair -v -keystore debug.keystore -storepass android \
     -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
     -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   ```

## The immediate next task (previous one is done)

`UserlandInstaller` is written and tested — see known-issue 3 above. Remaining, in suggested order:

1. Regenerate the userland payload (`./gradlew :app:bootstrapUserland -PuserlandAbi=arm64-v8a`).
   `TarWriter` had three latent bugs fixed after the payload was built: decimal (not octal) mode
   fields, a zeros-based header checksum, and truncated long paths. The app-side reader tolerates
   the old payload (proven by test), and executability falls back to path heuristics — but a fresh
   payload carries correct modes/checksums, so system `tar` can also read it.
2. Refresh `RUNTIME.md`, `SECURITY.md`, `THIRD_PARTY_NOTICES.md` to describe the built state.
3. Wire `MainActivity` to `BackendFactory.create(context)` (`BACKEND_INTEGRATION.md` is the guide).
4. Narrow `usesCleartextTraffic` to loopback for release.

## Licensing, still open

The bundled userland is Termux packages under **GPL-3.0**. Redistribution obligations are recorded
in `THIRD_PARTY_NOTICES.md` and are *not* yet satisfied. A release build that ships the payload must
complete them.