# Third-Party Notices

This file records the licence of every third-party component this project depends on, at the level of
detail needed to comply with redistribution requirements.

## Redistribution policy

Before any component is added, this file must be updated with its licence and any attribution
requirement. A component whose licence forbids the intended distribution must not be bundled.

Components are kept modular so a licence change in one does not force a rewrite of the layer above it.
The userland in particular is selected at runtime through `UserlandProvider`, so swapping it does not
touch anything else.

## Build-time dependencies

These are resolved by Gradle and are not redistributed inside the APK.

| Component | Licence | Notes |
|---|---|---|
| Android Gradle Plugin | Apache-2.0 | |
| Kotlin | Apache-2.0 | |
| Jetpack Compose / Material 3 | Apache-2.0 | |
| AndroidX (core, lifecycle, activity, room, datastore) | Apache-2.0 | |
| kotlinx.coroutines | Apache-2.0 | |
| OkHttp | Apache-2.0 | Also used for the SSE stream |
| Moshi | Apache-2.0 | JSON binding |
| Google Services / Firebase | Apache-2.0 | |
| Google Material icons | Apache-2.0 | |
| Secrets Gradle Plugin | Apache-2.0 | Reads `.env` |
| JUnit, Robolectric, MockK | Apache-2.0 / EPL-1.0 | Test only |

Attribution: none of these require a notice in a distributed binary beyond their licence text being
included in the source distribution, which this repository satisfies.

## Runtime components

### Linux userland (optional, packaged per-ABI)

The app supports several ways of obtaining a Linux userland. Which one is active depends on what is
present on the device.

| Component | Licence | Redistribution |
|---|---|---|
| App-provided Linux userland | **Determined by the packaged image** | See below |
| Termux (reused if installed, never redistributed) | GPL-3.0 | Not bundled. The app only detects and reuses an existing installation. |

**The app-provided userland is not included in this source tree.** Whoever packages it must record its
licence here before release. This is a release blocker, not an oversight.

Candidate distributions and their licences, for reference only — none is currently bundled:

| Distribution | Licence | Notes |
|---|---|---|
| Debian | Multiple (GPL-2.0/3.0, MIT, BSD, GPL-2.0 with exceptions) | Substantial obligations; no mainline binary repository serves Android ABIs |
| Ubuntu | Same as Debian plus non-free components | As above |
| Alpine | GPL-2.0-only (busybox), MIT (musl) | musl is MIT; Alpine overall is GPL-2.0-only |
| Termux packages | GPL-3.0 | Termux itself is GPL-3.0 |

Whichever is chosen, redistribution obligations are substantial and the notices file must be completed
with the full licence texts of every bundled package. A userland containing GPL components makes the
combined work subject to GPL terms, which is compatible with this project's licence choice.

### PTY native library (optional)

| Component | Licence | Redistribution |
|---|---|---|
| `libopencodepty.so` (app-provided, wraps NDK `forkpty`) | Same as this project | Built from the app's own source |

Only Android NDK headers are used, which carry the BSD-3-Clause licence:

| Component | Licence | Notes |
|---|---|---|
| Android NDK | BSD-3-Clause (plus others) | Toolchain, not redistributed |

**`libopencodepty.so` is not built in this source tree.** When it is, the NDK licence notice must be
included here.

## OpenCode

| Component | Licence | Redistribution |
|---|---|---|
| OpenCode | See the upstream project | Not redistributed |

OpenCode is installed at runtime on the user's device, either through its own official installer or
through npm. **No OpenCode binary or source is bundled in the APK.** The app is a client of a
separately-licensed tool, and the installation is performed by OpenCode's own mechanism.

The endpoints this app calls (`/global/health`, `/session`, `/file`, `/provider`, `/event`, `/doc`, and
the rest) are used as documented by the project. No unofficial download site is used for binaries.

## Fonts and assets

| Component | Licence |
|---|---|
| Jetpack Material icons (bundled) | Apache-2.0 |
| `opencode_logo` image | Verify the source's licence before release |

## This project

The application source in this repository is the project's own work. Any bundled third-party code must
carry its licence header intact, and this file must remain accurate.

## Review checklist

Before a release:

1. Every bundled binary has an entry above with its exact licence.
2. Required attribution notices are shipped in the app (an in-app notices screen is the usual route).
3. No component with a restrictive licence is bundled in a way that violates its terms.
4. The `THIRD_PARTY_NOTICES` entry in the app's settings screen matches this file.
5. OpenCode remains runtime-installed, not bundled.