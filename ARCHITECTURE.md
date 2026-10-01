# Architecture

## The rule this project is built around

The frontend must never know that a Linux process exists. It calls one interface,
[`LocalCodingBackend`](app/src/main/java/com/example/backend/core/LocalCodingBackend.kt), and the
backend decides what that means. This is the difference between "a native Android app that happens to
contain a development environment" and "a web wrapper around a terminal".

```
┌────────────────────────────────────────────────────────────────────────┐
│ Android UI (Jetpack Compose)                                           │
│   calls LocalCodingBackend, observes StateFlow / Flow<BackendEvent>     │
└──────────────────────────────┬─────────────────────────────────────────┘
                               │
┌──────────────────────────────▼─────────────────────────────────────────┐
│ LocalCodingBackend                                                     │
│   RealLocalBackend  (production)   MockBackend  (development)          │
└──┬────────────┬────────────┬────────────┬────────────┬─────────────────┘
   │            │            │            │            │
   ▼            ▼            ▼            ▼            ▼
Environment  Dependency  Project     Session      Terminal      OpenCode
Manager      Manager     Manager     Manager      Manager       Manager
                                                       │            │
                                                       ▼            ▼
                                              PtyHost          OpenCodeServerManager
                                              (forkpty)             │
                                                                    ▼
                                                          OpenCodeApiClient
                                                          (typed HTTP)
                                                                    │
                                                                    ▼
                                                          OpenCodeEventManager
                                                          (SSE + backoff)
```

## Layers

| Package | Responsibility |
|---|---|
| `backend.core` | Contracts, states, error taxonomy. No Android dependencies beyond `Context` in a few cases. |
| `backend.log` | `LogManager` and `SecretRedactor`. Every log line and every diagnostic string passes through the redactor. |
| `backend.runtime` | `RuntimeLayout` (paths), `ArchitectureDetector`, `EnvironmentBuilder`, `ProcessRunner`, `ProcessRegistry`, `EnvironmentManager`, `pty/*`. |
| `backend.userland` | `UserlandProvider` implementations and the `PackageManager` abstraction. |
| `backend.deps` | `DependencyCatalog` (the single declaration point) and `DependencyManager`. |
| `backend.install` | `SetupOrchestrator` — resumable first-run. |
| `backend.opencode` | `OpenCodeManager`, `OpenCodeConfigManager`, `OpenCodeCapabilities`. |
| `backend.server` | `PortSelector`, `OpenCodeServerManager`. |
| `backend.api` | `OpenCodeApiClient`, `HttpOpenCodeApiClient`, `ApiModels`, `ApiVersionGate`. |
| `backend.events` | `BackendEvent`, `OpenCodeEventMapper`, `OpenCodeEventManager`. |
| `backend.security` | `KeystoreCipher`, `CredentialManager`. |
| `backend.project` | `ProjectManager`, `ProjectStore`, `PathGuard`. |
| `backend.session` | `SessionManager`. |
| `backend.files` | `FileService` (large-file guard), `FileWatcher`. |
| `backend.git` | `GitService`, `PorcelainParser`. |
| `backend.diagnostics` | `DiagnosticsService` (health checks, troubleshooting). |
| `backend.monitor`, `backend.net` | `RuntimeMonitor`, `ConnectivityMonitor`. |
| `backend.service` | `OpenCodeRuntimeService` (foreground service), `BackendHolder`. |
| `backend.bridge` | Adapters projecting the backend onto the existing frontend service interfaces. |

## Why each decision looks the way it does

**`DependencyCatalog` is the only place tools are declared.** Adding a tool means adding one enum entry
and one catalogue entry. The install engine, the wizard, the repair flow and diagnostics all read from
it, so no install logic is duplicated.

**`ProcessRunner` never concatenates a command string.** `CommandSpec` takes an executable and a list
of arguments. `shell = true` exists for the installer and the terminal, and its arguments are
single-quoted so a value containing `;` or `$(...)` cannot escape. API calls never set it.

**Construction order is explicit.** The credential vault must exist before the environment builder,
which must exist before the process runner. The API client needs the server manager's endpoint and the
server manager needs an API client for its health check, so that cycle is broken with a lazy provider
rather than a mutable global.

**`SecretRedactor` runs on every write.** Literals registered at runtime are scrubbed in addition to
shape-based patterns, so a password with no distinctive prefix still cannot reach a log.

**`ApiVersionGate` probes rather than asserts.** It reads the version from `/global/health`, probes
each capability, and fetches the live OpenAPI spec from `/doc`. A version skew therefore degrades to
"some features work" instead of a crash.

## The three guarantees worth defending

1. **No fake success.** `OpenCode installed ✓` requires `opencode --version` to have returned. `Server
   running ✓` requires the process alive, the port listening, *and* `/global/health` reporting healthy.
   `API connected ✓` requires an actual request to have succeeded.

2. **No accidental exposure.** The server binds `127.0.0.1`. LAN binding requires explicit opt-in and a
   warning. CORS origins are explicit; `*` is never sent. The password is generated locally, stored in
   the Keystore, and referenced rather than passed around.

3. **No silent destructive action.** Removing a project unregisters it. Deleting files is a separate,
   explicitly confirmed call, and it refuses to delete anything outside the app sandbox.

## Event flow for a streamed prompt

```
user prompt
  → SessionManager.sendPrompt          POST /session/{id}/prompt_async
  → OpenCodeEventManager               SSE frame: message.part.updated {delta}
  → OpenCodeEventMapper                → BackendEvent.MessageDelta
  → RealLocalBackend._eventsFlow       SharedFlow with DROP_OLDEST
  → Compose
```

The HTTP request returns immediately. All incremental output arrives on the event stream, which is why
the UI never waits for a complete response.

## Testing

`app/src/test/` covers the pure logic: redaction, path traversal, porcelain parsing, event mapping,
scrollback bounds, key encoding, version constraints, checkpoint ordering, architecture mapping, and a
contract suite that asserts `MockBackend` behaves like the real backend. See
[TROUBLESHOOTING.md](TROUBLESHOOTING.md) for what is deliberately *not* covered there.