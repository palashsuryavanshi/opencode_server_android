# Runtime

## Why a userland is needed

An Android app cannot `exec` a Linux binary. The application sandbox has no Linux filesystem, no
dynamic loader for ELF objects, and no `forkpty`. Everything the terminal, Git, Node and OpenCode need
has to come from a Linux userland the app controls.

This app therefore treats "which userland" as a pluggable decision rather than an assumption.

## The provider chain

`UserlandProviderRegistry` picks the first provider that supports the detected ABI:

| Order | Provider | Provides | Notes |
|---|---|---|---|
| 1 | `ProvisionedUserlandProvider` | Full Linux userland | Root at `runtime/userland/<triple>/`. Preferred. |
| 2 | `TermuxBridgeUserlandProvider` | Full Linux userland | Reuses an existing Termux install instead of downloading a second one. |
| 3 | `PlatformShellUserlandProvider` | Nothing beyond `sh` | Always available. Terminal works; developer tooling does not. |

If no provider supports the ABI, selection returns `isAvailable = false` and setup reports

> OpenCode is not currently available for this device/runtime.

with the detected architecture and the supported targets. That is a real outcome, not a fallback to
something broken.

## Architecture mapping (spec 2/12)

`TargetTripleResolver` is total and explicit:

| Android ABI | Triple |
|---|---|
| `arm64-v8a`, `aarch64*` | `aarch64-linux-android` |
| `armeabi-v7a` | `armv7a-linux-androideabi` |
| `x86_64` | `x86_64-linux-android` |
| `x86` | `i686-linux-android` |
| anything else | `null` |

`null` is the important row. An unmapped ABI produces no triple, so nothing is downloaded for it.
`ArchitectureMappingTest` asserts this.

OpenCode publishes builds for the 64-bit targets, so `OpenCodeManager.SUPPORTED_TARGETS` is
`{ARM64, x86_64}`. A 32-bit device gets an architecture-unavailable message naming its ABI, rather
than a confusing loader error.

`libc` is reported as Bionic on the host. A PRoot userland may present glibc internally; that
distinction is tracked separately so a loader mismatch is diagnosable.

## Directory layout (spec 56)

Everything lives under `filesDir/runtime/`, which is private to the app and survives process death:

```
runtime/
├── home/                 $HOME
│   ├── .config/opencode/      OPENCODE_CONFIG_DIR
│   ├── .local/share/opencode/ OpenCode data: auth.json, sessions, db
│   ├── .local/bin/
│   ├── .cache/
│   └── projects/              managed project roots
├── bin/                  first entry on PATH
├── tmp/                  $TMPDIR
├── logs/                 structured logs
├── dl/                   partial downloads
├── userland/<triple>/    extracted userland
├── state/                checkpoints, registration, settings, projects.json
└── backups/              config and backup archives
```

Nothing persistent lives in the cache directory. Android may delete it without warning, and losing
OpenCode's auth file would silently disconnect the user's provider.

## Environment (spec 54)

`EnvironmentBuilder` builds the child environment from an allowlist. Anything not named is not
inherited, which is what keeps Android-specific values from leaking into the Linux userland.

```
HOME      <runtime>/home
PWD       per-process working directory
PATH      <runtime>/bin:~/.local/bin:/usr/local/bin:/usr/bin:/bin:/system/bin
TMPDIR    <runtime>/tmp
SHELL     detected (bash preferred, sh fallback)
TERM      xterm-256color
LANG      en_US.UTF-8
OPENCODE_CONFIG_DIR   <runtime>/home/.config/opencode
OPENCODE_DATA_DIR     <runtime>/home/.local/share/opencode
OPENCODE_DISABLE_AUTOUPDATE   true   (the app owns updates)
OPENCODE_CLIENT       android
```

Secret-bearing variables (`OPENCODE_SERVER_PASSWORD`, `OPENCODE_SERVER_USERNAME`) are never stored on
the builder. `CredentialManager.processSecrets()` returns them for one spawn and the caller drops the
reference.

`EnvironmentBuilder.set()` refuses to store a key that `SecretRedactor.isSensitiveKey` flags, and logs
the refusal.

## PATH management (spec 55)

The app-owned `bin/` is first, so an installed or repaired `opencode` always wins. `which` is resolved
two ways: directly against `PATH` entries, and then via a real `command -v` inside the runtime, because
a userland binary is not visible to the host JVM's `PATH`.

## PTY (spec 32/33)

Two implementations behind one interface:

| Factory | Full-screen | Resize | Mechanism |
|---|---|---|---|
| `ForkPtyHostFactory` | yes | yes | JNI `forkpty()` — a real controlling terminal |
| `PipePtyHostFactory` | no | partial | stdin/stdout/stderr pipes |

`PtyHostSelector` prefers the native one and logs which it chose. Degradation is visible in
`TerminalManager.describe()`, so Advanced details say "Compatibility terminal (pipes)" rather than the
app pretending full-screen support.

The pipe fallback exports `COLUMNS`/`LINES` on resize instead of issuing `TIOCSWINSZ`, and the terminal
screen still updates without restarting the shell.

`libopencodepty.so` is loaded lazily; a missing library is caught as `UnsatisfiedLinkError` and reported
through `ForkPtyHostFactory.unavailableReason()`. The shell does all parsing in both cases — nothing
reimplements shell grammar.

## Package managers (spec 9)

`PackageManagerDetector` probes for `pkg`, then `apt-get`, then `apk`, and uses the first that
resolves. A runtime with none yields `NoPackageManager`, whose `install()` returns a clear error rather
than a "command not found". That is what lets `DependencyManager` fall back to release binaries instead
of failing.

## Storage (spec 74)

`DependencyCatalog.requiredBytes()` sums the declared estimates rather than using a hard-coded figure.
The precheck runs before a large install and produces a message naming both the requirement and what is
actually available.

## Terminal sessions (spec 63/64)

Each session owns its own PTY, scrollback buffer and process. Closing one never affects another. A
daemon liveness thread reaps sessions whose shell exited, so the UI shows `EXITED` rather than a
terminal that looks alive but is not.

Resize calls `PtyHost.resize(cols, rows)` directly. The shell is never restarted to apply a new size.

## Background execution (spec 36/37/75)

`OpenCodeRuntimeService` is a foreground service with a `dataSync` type. Its notification always shows
the real bound endpoint, never a hard-coded port, and carries an "Open" and a "Stop Server" action.

Battery impact is stated in the notification subtitle rather than hidden. `BackgroundExecutionAdvisor`
reports whether the app is exempt from battery optimisation and tells the user what will happen if it
is not — no promise of indefinite execution.

`onTaskRemoved` honours the `StartPolicy`: `KEEP_RUNNING` leaves the service alive, everything else
stops the server.

## Process lifecycle (spec 39/85)

`ProcessRegistry` holds a handle to the live `Process` for every child, not just a PID. Liveness is
`Process.isAlive`, never a stored flag. Android reuses PIDs, so a stored PID alone is not evidence.

Stopping escalates: `SIGTERM` to the single PID, then `Process.destroy()`, then `SIGKILL` — scoped to
that one process. Nothing kills a process tree. `ProcessRegistry.stopCategory(OPENCODE_SERVER)` leaves
terminal sessions untouched (spec 62).

## App restart (spec 38/95)

`RealLocalBackend.onProcessStart()`:

```
evaluate environment
  → if setup complete: discoverRunningServer()
      → alive + listening + healthy?  reconnect, do not start a second server
      → otherwise?                    apply the start policy
  → else: SETUP_REQUIRED, and if a checkpoint exists, tell the user setup was interrupted
```

Discovery reads the persisted registration, verifies the process, checks the port, and requires a real
health response before adopting. A stale registration is deleted rather than trusted.