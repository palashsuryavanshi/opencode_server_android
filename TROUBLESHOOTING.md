# Troubleshooting

## Reading the diagnostics screen

**Run Diagnostics** produces a row per subsystem with a remediation on every failure. **Copy
Diagnostics** produces a redacted text blob for a bug report: secrets are stripped and the runtime home
path collapses to `~`.

```
Runtime
✓
Shell
✓ bash
Git
✓ git version 2.44.0
Node
✓ v22.0.0
OpenCode
✓ 1.2.3
Server
✓ Running on http://127.0.0.1:4096
API
✓ Healthy
Event stream
✓ Connected
Project
✓ ~/projects/my-app
Provider
✓ Authenticated
```

A failing row looks like this:

```
✗ Provider

API key not configured.
```

## OpenCode won't start

The app checks these in order and names the first failure.

**Architecture.** The most common cause, and the one that cannot be worked around:

```
OpenCode is not currently available for this device/runtime.

Detected: ARM32
Required: A compatible Linux ARM64 runtime
```

OpenCode publishes builds for ARM64 and x86_64. A 32-bit device gets this message rather than a loader
error. Check Advanced details for the full ABI list.

**Binary compatibility.** If the architecture is supported but the install did not produce a working
binary, the diagnostics show:

```
Detected architecture: ARM64 (arm64-v8a)
Device ABIs: arm64-v8a, armeabi-v7a
libc: BIONIC
OpenCode installed: false
```

Run **Repair OpenCode**, which reinstalls and then re-verifies with `opencode --version`.

**Permissions.** If notifications are disabled the foreground service cannot show its status and
Android may kill the server. Grant notifications.

**PATH.** `which opencode` must resolve. The app's `runtime/bin` is first on `PATH`; if the binary
lives elsewhere, **Repair OpenCode** relinks it. Advanced details prints the effective `PATH`.

**Dependencies.** A missing shell or `curl` is reported by name. `curl` in particular is needed for the
installer path.

**Port.** The chosen port appears in Advanced details. A conflict on the configured port produces:

```
Port 4096 is already in use.
```

Either pick another port or let automatic selection choose a free one.

**Configuration.** The effective config is at `~/projects/../.config/opencode/opencode.json`, or use
**Reset OpenCode configuration** in Settings.

**Logs.** Advanced details on the server shows the last 120 lines of `opencode serve` output, tagged
`[out]` and `[err]`.

### Suggested fixes

| Symptom | Fix |
|---|---|
| Process exited before ready | Check the log tail, then Restart Server |
| Never bound a port | Check permissions and battery optimisation |
| Health check timed out | Restart Server; if it repeats, Repair OpenCode |
| Crashed after starting | Advanced details → Copy Diagnostics, then Report |

## Terminal issues

**No full-screen programs.** Advanced details → Terminal shows which backend is active. Without the
native `forkpty` library the app uses the pipe fallback, where `vim`, `less` and `top` do not render
correctly. Ordinary commands work normally.

**`command not found`.** The runtime may not be installed. Run **Run Diagnostics**; a missing shell or
PATH entry is reported per tool.

**A command hangs.** Tap **CTRL+C** in the special-key toolbar. This sends `SIGINT` to the shell, which
gives the child process a chance to clean up.

**Screen size is wrong after rotation or keyboard open.** The terminal is resized in place; the shell
is not restarted. If the display is still wrong, close and reopen the session.

## Server stops unexpectedly

Crash recovery captures stderr before anything else, so the diagnostics still contain the reason.

Automatic restart is bounded at 3 attempts. After that:

```
OpenCode failed to start repeatedly.
```

with diagnostics attached. This is deliberate — an infinite restart loop would drain the battery and
hide the cause.

## "Start the local server and try again"

`ECONNREFUSED` on the API calls means the server is not running. The server is independent of the app,
so this appears when:

- it was stopped
- Android killed the process (see battery restrictions below)
- it crashed

Check the Server tab. If it reports Running but the API still fails, the process is alive but not
serving — Advanced details → Health will say `unavailable` and the log tail will show why.

## OpenCode version is incompatible

```
OpenCode version is incompatible with this application.

This app was built for OpenCode 0.x (capability-probed) but version X is installed.
```

The app probes capabilities rather than comparing version strings, so this appears only when a
*required* capability is missing: health, session listing, session creation, messages, or events.

Optional capability failures degrade silently by design. If `/file/status` is unavailable, the Git panel
degrades while sessions keep working.

Fix: **Update OpenCode** in Settings, or reinstall if the upgrade path is unavailable.

## Nothing works offline

This is expected and not a failure. Spec 45 requires the app to open and keep working without internet:

```
Local OpenCode server:
✓ Running

Terminal:
✓ Available

Internet:
✗ Offline

Provider:
⚠ Cannot reach provider
```

What works offline: terminal, files, Git, session history, and everything the server already has cached.
What does not: installing tools, and sending prompts (the provider call needs internet).

**"OpenCode offline" is never shown just because the internet is down.** The local server and the
provider are reported as separate signals.

## Battery and background execution

A running server consumes battery. The notification says so.

Android can kill background processes. If the server stops when you switch apps:

1. Settings → **Keep running**
2. Android Settings → Apps → this app → Battery → **Unrestricted**

`BackgroundExecutionAdvisor` reports the current state and says exactly what will happen without the
change. No promise of indefinite execution is made, because Android does not guarantee it.

Force-stopping the app or rebooting the device always stops the server.

## Reopening the app

Startup runs:

```
evaluate the environment
  → discover a running server
      → found and healthy: reconnect (do not start a second one)
      → not found: apply the start policy
```

If a previous run was interrupted, you will see:

```
Setup was interrupted after "Node ready". Checking installed components...
```

Setup resumes at the next incomplete checkpoint. Already-installed tools are not reinstalled.

## Setup gets stuck

Checkpoints persist to `runtime/state/setup_checkpoint`. "Repair" actions target one component:

| Action | Effect |
|---|---|
| Repair OpenCode | Reinstall and re-verify OpenCode |
| Repair Node.js | Reinstall Node |
| Repair Git | Reinstall Git |
| Repair Linux runtime | Re-materialise the userland |
| Reset OpenCode configuration | Rewrites `opencode.json`, keeps credentials and sessions |
| Reinstall dependencies | Re-runs setup with `force = true` |
| Reset development environment | Removes runtime files. **Keeps your projects.** Backs up configuration first. |

## Not enough storage

```
Not enough storage.

Required: ~310.0 MB
Available: 180.0 MB

Free up storage and try again.
```

The requirement is the sum of the catalogue's declared estimates for the remaining steps, not a
hard-coded number.

## APK installed but no Linux runtime

`ProvisionedUserlandProvider` expects a payload at `runtime/userland/<triple>.tar.zst`. **That payload is
not included in this source tree.** Without it:

```
The Linux runtime is not installed.
```

Two consequences:

- Without a runtime, Git, Node and OpenCode cannot be installed, so the server cannot start
- The terminal falls back to the Android shell, where ordinary commands work but developer tooling does
  not

Packaging the userland is a release-build step and is the main outstanding piece of work. The
architecture detection, compatibility checks and error reporting are complete and tested, so the app
degrades honestly in the meantime rather than pretending to work.

Similarly, `libopencodepty.so` is not built here. Without it the terminal uses pipes, which loses
full-screen programs but keeps every ordinary command working.

## Testing the frontend without a runtime

Set `USE_MOCK_BACKEND=true`:

```properties
# .env
USE_MOCK_BACKEND=true
```

or in `gradle.properties`:

```properties
USE_MOCK_BACKEND=true
```

`MockBackend` implements the same interface as the real backend, including the same error wording and
the same path rules. `MockBackendContractTest` asserts the contract holds, so a screen built against the
mock will not depend on behaviour production lacks.

Release builds always use the real backend regardless of this flag.

### Test scenarios (spec 81)

Settings → Developer → Test scenario:

| Scenario | What it exercises |
|---|---|
| Slow server startup | The progress states during startup |
| Server crash after start | Crash detection and diagnostics |
| API connection failure | The `ConnectionError` path |
| Dependency installation failure | The install-failure UI |
| OpenCode unavailable | The architecture-unavailable UI |
| Invalid API key | The provider-error UI |
| SSE disconnect | Reconnection and the reconnecting affordance |
| Terminal crash | Session-exit handling |
| Low storage | The storage-warning UI |
| Offline mode | The offline diagnostics |

The active scenario is logged, so a screenshot showing a failure can be identified as a test.

## Reporting a bug

1. **Run Diagnostics** → note which rows fail
2. **Copy Diagnostics** → paste into the report
3. Advanced details on the relevant screen → include the process, PID, port and log tail

Diagnostics are redacted. Secrets never appear in the copied text.