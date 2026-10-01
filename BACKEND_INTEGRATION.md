# Backend Integration

How to wire the existing frontend to `LocalCodingBackend`.

## 1. Choose the backend

```kotlin
val backend: LocalCodingBackend = BackendFactory.create(context)
```

`BackendFactory` reads `USE_MOCK_BACKEND` from the build config and `.env`. Release builds always
resolve to `RealLocalBackend` regardless of the flag.

To force a choice from Settings:

```kotlin
BackendFactory.setMockPreference(context, useMock = true)
```

## 2. Collect state

Four flows cover everything the UI needs:

```kotlin
// Spec 112 — the master state
val appState by backend.observeState().collectAsState()

// Spec 16 — the server
val server by backend.observeServerState().collectAsState()

// Spec 28 — projects
val projects by backend.observeProjects().collectAsState()

// Spec 49 — the connection, with no password
val connection by backend.observeConnection().collectAsState()
```

## 3. Subscribe to events

```kotlin
LaunchedEffect(Unit) {
    backend.subscribeToEvents().collect { event ->
        when (event) {
            // Spec 69 — append to the streaming buffer
            is BackendEvent.MessageDelta -> buffer.append(event.delta)
            is BackendEvent.MessageText  -> buffer.replace(event.text)
            is BackendEvent.ToolStarted  -> showToolSpinner(event.title)
            is BackendEvent.ToolOutput   -> finishTool(event.output, event.error)
            is BackendEvent.SessionIdle  -> setStatus(SessionStatus.IDLE)
            is BackendEvent.Error        -> showError(event.message)
            is BackendEvent.WatcherChanged -> refreshFileTree(event.change.path)
            else -> Unit
        }
    }
}
```

Rendering rules that matter:

- `MessageDelta` **appends**; `MessageText` **replaces**. Conflating them either drops or duplicates text.
- Never show `EventStreamDisconnected` as an error while `willRetry` is true — the client is reconnecting.
- `BackendEvent.Notice` is pre-worded for a snackbar. Use it as-is rather than re-phrasing.

## 4. Replace the existing service interfaces

The frontend already depends on six interfaces in `com.palash.opencodeserver.service`. Rather than rewrite them,
use the bridge:

```kotlin
class OpenCodeViewModel(
    private val backend: LocalCodingBackend = BackendFactory.create(context)
) : ViewModel() {

    private val services = BackendServiceBridge(backend).all()

    init {
        terminal = services.terminal
        agent = services.agent
        projects = services.projects
        sessions = services.sessions
        git = services.git
        files = services.files
        server = services.server
    }
}
```

The adapters are thin projections with no state of their own, so the backend stays the single source of
truth. This is what satisfies spec 121: the frontend never spawns a process or opens a socket.

## 5. First run

```kotlin
LaunchedEffect(Unit) {
    // Spec 38/95 — discovery happens inside this call
    if (backend is RealLocalBackend) backend.onProcessStart()

    val status = backend.getStatus()
    if (status.appState == AppState.SETUP_REQUIRED) {
        showWizard()
    }
}
```

Drive the wizard from `observeSetupProgress()`:

```kotlin
LaunchedEffect(Unit) {
    backend.observeSetupProgress().collect { progress ->
        headline = progress.headline
        detail = progress.detail
        percent = progress.percent
        // Spec 6 — raw commands go in the Advanced section only
        advancedCommands = progress.advancedCommands
        if (progress.isTerminal && progress.error != null) {
            showFailure(progress.error!!)
        }
    }
}
```

Then run it:

```kotlin
backend.setup(force = false)   // resumes from the last checkpoint
```

## 6. Server lifecycle

```kotlin
when (backend.observeState().value) {
    AppState.SETUP_REQUIRED  -> showWizard()
    AppState.INSTALLING      -> showProgress()
    AppState.READY           -> showStartButton()
    AppState.SERVER_STARTING -> showProgress()
    AppState.API_CONNECTED   -> showRunning()
    AppState.SERVER_ERROR    -> showCrash(server.value)
    AppState.OFFLINE         -> showOffline()
    AppState.DEGRADED        -> showDegraded()
    else -> Unit
}
```

Start and stop:

```kotlin
backend.startServer().onSuccess { info ->
    // info.baseUrl is the REAL bound endpoint. Never hard-code 4096.
    showEndpoint(info.baseUrl)
}.onFailure { error ->
    showError(error.userTitle, error.userMessage, error.technical)
}
```

When the server starts, promote it to a foreground service so it survives the Activity:

```kotlin
OpenCodeRuntimeService.start(context)
```

## 7. Terminal

```kotlin
val session = backend.openTerminal().valueOrNull() ?: return

// Raw bytes; the emulator handles escape sequences
LaunchedEffect(session.sessionId) {
    backend.subscribeToEvents().collect { event ->
        if (event is BackendEvent.TerminalOutput && event.sessionId == session.sessionId) {
            terminalEmulator.feed(event.text)
        }
    }
}
```

For the interactive path, use `TerminalManager` directly so you get `writeInput`, `writeSpecial`,
`resize` and `interrupt`:

```kotlin
// Spec 65 — CTRL+C
terminals.interrupt(session.sessionId)

// Spec 64 — resize in place on rotation or keyboard change
terminals.resize(session.sessionId, columns, rows)
```

And for one-shot commands:

```kotlin
backend.executeTerminalCommand("npm install").onSuccess {
    // Output arrived on the event stream as TerminalOutput.
}
```

## 8. Diagnostics

```kotlin
val report = backend.getDiagnostics()
show(report.checks)                 // one row per subsystem, each with a remediation
clipboard = (backend as? RealLocalBackend)?.redactedDiagnosticsText()
```

`redactedDiagnosticsText()` is already stripped of secrets and collapsed to `~` paths. Do not build a
diagnostics blob by hand — that is how secrets end up in bug reports.

## 9. Testing without a runtime

```properties
# .env or gradle.properties
USE_MOCK_BACKEND=true
```

Or exercise specific failures from Settings → Developer → Test scenario. `MockBackendContractTest`
asserts the mock honours the same contract, including the same error wording and the same path rules,
so screens built against it will not depend on behaviour production lacks.

## Gotchas

**Never trust a UI string as evidence.** Use `observeServerState().isRunning`, which requires the
process alive, the port listening, and a health response. `ServerInfo.state == RUNNING` alone is not
enough — the combined `isRunning` property is what checks all three.

**The port is dynamic.** Read `ServerInfo.port`. `4096` is the default, not a guarantee.

**Do not show offline as an error.** The local server works without internet. Use `getConnectivity()`,
which separates local server, terminal, internet and provider.

**Stream, don't await.** `sendMessage` returns immediately; the reply arrives on the event stream.
Blocking on it defeats streaming.

**Aborting is not the same as deleting.** `abortSession` stops generation; `deleteSession` removes it.

**Setup is resumable.** `setup(force = false)` is safe to call repeatedly. `force = true` reinstalls.

## Environment setup

The Linux userland payload is not in this tree, so a fresh install reports the runtime as unavailable
and the terminal falls back to the Android shell. That is a deliberate, honest degradation rather than
a broken state — see [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for what to package and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the licensing obligations that come with it.