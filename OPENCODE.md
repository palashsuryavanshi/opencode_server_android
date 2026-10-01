# OpenCode Integration

Everything this app does with OpenCode goes through OpenCode's own HTTP API. The app never
reimplements a capability it could call, and never talks to an LLM provider directly — OpenCode stays
the coding-agent backend.

## Installation (spec 11)

Two documented paths, chosen by what the detected runtime supports:

| Method | When | Mechanism |
|---|---|---|
| Official installer | Primary | `curl -fsSL https://opencode.ai/install`, written to a temp file then executed |
| npm | When Node is the better fit | `npm install -g opencode-ai` |

The installer URL is OpenCode's own domain. No third-party mirror is used.

Two deliberate choices in `DependencyManager.installViaRelease`:

- The script is downloaded to disk and executed as a file rather than piped straight into a shell, so
  the payload can be size-checked and inspected before it runs.
- Architecture arguments are passed explicitly (`--os android --arch <abi>`). If no compatible build
  exists the installer exits non-zero and the failure is reported as an architecture error, never as a
  success.

The npm package name lives in `DependencyCatalog` as `opencode-ai`, the name OpenCode documents.

**No version is hard-coded.** `OpenCodeManager.getInstalledVersion()` runs `opencode --version`; the
installed release is whatever the runtime reports.

## Capability discovery (spec 93/94)

`OpenCodeCapabilities.parse(helpText)` reads the installed build's `--help` output and records which
commands exist. The server mode follows from that:

| Available | Mode | Command |
|---|---|---|
| `serve` + `service` | `SERVE_OR_SERVICE` | `serve` |
| `serve` only | `SERVE` | `serve` |
| `service` only | `SERVICE` | `service start` |
| neither | `UNAVAILABLE` | — |

`serve` is preferred for Android because the app then owns the child process directly, which gives
reliable lifecycle control. Results are cached per version.

`OpenCodeManager.verify()` fails if the installed build does not expose `serve`, rather than starting a
process that cannot serve.

## Server startup (spec 17)

`OpenCodeServerManager.start()` runs a fixed twelve-stage sequence, and each stage is observable:

```
 1. validate the runtime
 2. validate the OpenCode install
 3. validate the project directory
 4. select a port
 5. load credentials
 6. start `opencode serve`
 7. capture stdout/stderr
 8. wait for readiness
 9. call GET /global/health
10. establish the API connection
11. establish the event stream
12. publish the state
```

The state becomes `RUNNING` at step 9 and nowhere earlier. A started process alone is not success.

### Port selection (spec 13)

`PortSelector` tries the configured port, then asks the OS for a free one from the dynamic range. The
actual bound endpoint is recorded in `OpenCodeConnection.baseUrl` and the frontend reads it from
`OpenCodeServerManager` — `http://localhost:4096` appears nowhere as an assumption.

The chosen port is only *reserved* for the moment it takes to spawn, so `verify()` confirms the port is
actually listening afterwards. When the user has explicitly pinned a port, a conflict is an error
rather than a silent change, because a silent move breaks any client they configured.

### Bind address (spec 14/50)

`127.0.0.1` by default. `0.0.0.0` only when the user enables LAN access, which the UI gates behind the
warning the spec asks for. CORS origins are added only when explicitly configured; `*` is never sent.

## Health check (spec 18)

```
GET /global/health  →  { "healthy": true, "version": "..." }
```

Called during startup (as the gate to `RUNNING`), on discovery, and by the one-tap health check. A
server that answers the socket but not this endpoint is reported as started-but-not-healthy, with the
log tail attached.

## API surface (spec 19/20)

`OpenCodeApiClient` maps to documented endpoints only. Every method maps to a row in OpenCode's server
documentation; nothing is invented.

| Area | Endpoints used |
|---|---|
| Health | `GET /global/health` |
| Project | `GET /project`, `GET /project/current` |
| Path / VCS | `GET /path`, `GET /vcs` |
| Sessions | `GET/POST /session`, `GET/PATCH/DELETE /session/{id}`, `GET /session/status`, `GET /session/{id}/children`, `GET /session/{id}/diff`, `POST /session/{id}/abort`, `POST /session/{id}/fork` |
| Messages | `GET/POST /session/{id}/message`, `GET /session/{id}/message/{messageID}`, `POST /session/{id}/prompt_async`, `POST /session/{id}/command`, `POST /session/{id}/shell` |
| Files | `GET /file`, `GET /file/content`, `GET /file/status`, `GET /find`, `GET /find/file`, `GET /find/symbol` |
| Providers | `GET /provider`, `GET /provider/auth`, `PUT /auth/{id}` |
| Config | `GET/PATCH /config`, `GET /config/providers` |
| Agents / commands | `GET /agent`, `GET /command` |
| Logging | `POST /log` |
| Spec | `GET /doc` |

Prompts use `POST /session/{id}/prompt_async`. The blocking `POST /message` endpoint is used only as a
fallback, and only when the async route returns 404 — a server release that lacks it.

Files are written through the runtime filesystem rather than the API, because the documented API is
read-only for content. That is the spec 92 hierarchy in action: API where it exists, runtime otherwise.

### Transport (spec 106/107)

Ordinary calls get a bounded read timeout. The SSE client gets `readTimeout(0)`, which is what keeps
`/event` open.

Retries are not automatic for everything. `ErrorCategory.isAutoRetryable` permits retry for connection
failures, timeouts, network problems and runtime errors. Authentication, invalid project, malformed
request, unsupported API and permission denied are never retried.

### Version compatibility (spec 21)

`ApiVersionGate` reads the version from `/global/health`, probes each capability, and fetches the live
OpenAPI spec from `/doc`. Results are cached until `invalidate()`.

Required capabilities: health, session listing, session creation, messages, events. If any is missing,
the result is an `ApiVersion` error carrying both the installed and expected versions plus the probe
notes — a clear message and an update option, not a crash.

Optional capabilities degrade individually. If `/file/status` is absent, the Git panel degrades while
sessions keep working.

## Events (spec 22/23/70)

`GET /event` is consumed by `OpenCodeEventManager`, which parses the SSE wire format directly. Keeping
the parser in-tree means the reconnect logic owns the whole lifecycle and the parse surface stays small
enough to audit.

Backoff is 1s → 2s → 5s → 10s, plus jitter so reinstalled apps on one network do not retry in
lockstep. Retrying stops as soon as the server is confirmed stopped, because a stopped server needs
`connect()`, not retries.

Backgrounding releases the socket; foregrounding re-establishes it.

### Event mapping

`OpenCodeEventMapper` translates documented events into the app's vocabulary:

| OpenCode event | App event |
|---|---|
| `server.connected` | `EventStreamConnected` |
| `message.part.updated` (text + `delta`) | `MessageDelta` |
| `message.part.updated` (text, no delta) | `MessageText` |
| `message.part.updated` (reasoning) | `Reasoning` |
| `message.part.updated` (tool pending/running) | `ToolStarted` |
| `message.part.updated` (tool completed/error) | `ToolOutput` |
| `message.part.updated` (patch) | `FileChanged` per file |
| `message.part.updated` (retry) | `Error` (retryable) |
| `file.edited`, `file.watcher.updated` | `FileChanged` / `WatcherChanged` |
| `session.status`, `session.idle` | `SessionStatusChanged` / `SessionIdle` |
| `message.updated` (with `finish`) | `MessageCompleted` |
| `session.error` | `Error` |
| `installation.update-available` | `ServerUpdateAvailable` |
| `lsp.*`, `todo.updated`, `command.executed`, `tui.*`, `pty.*`, `session.diff`, `message.removed` | ignored — listed explicitly so the choice is auditable |
| anything unrecognised | `Error` naming the type, so a server-side addition is visible rather than silently dropped |

The delta distinction matters. A frame with `delta` is an incremental append and must reach the UI
immediately; a frame without one is the full value and replaces what is on screen. Conflating the two
either drops deltas or duplicates text.

## Sessions and messages (spec 24/25)

`SessionManager` uses the HTTP API rather than the CLI, because the API returns typed JSON and
`opencode session` returns a human-readable table that would have to be parsed.

`messageCount` is reported as 0 from `listSessions` because the API does not provide it; the UI obtains
the real count by loading history. Reporting a fabricated count would violate spec 72's spirit in a
different subsystem.

## Providers (spec 26/78)

`listProviders()` reads `/provider`, which returns every provider with its `source` and the
`connected` list. The UI is driven from that, so no provider assumptions are hard-coded.

Saving a key calls `PUT /auth/{id}` with `{ "type": "api", "key": ... }` over loopback. The plaintext
is registered with `SecretRedactor` before the call, so it cannot appear in a log line afterwards, and
the app keeps no second copy — OpenCode's own auth store is the single source of truth.

Removing a key uses `opencode auth logout`, the documented CLI, because the HTTP API exposes no delete
route. That is the spec 92 fallback hierarchy.

Provider health is inferred from `/provider`'s `connected` list combined with connectivity. The app
does not call a provider endpoint to test it (spec 78).

## Configuration (spec 47)

The managed config lives at `$HOME/.config/opencode/opencode.json`, which the runtime sets through
`OPENCODE_CONFIG_DIR`. The app writes only two keys — `share: "manual"` (so transcripts are not
published automatically) and `autoupdate: "notify"` (the app owns updates) — plus the `$schema`
reference.

Edits go through `PATCH /config`, so OpenCode validates them. `OpenCodeConfigManager.supportedSettings()`
lists only five settings, and the UI must offer only those; presenting a setting the app cannot honour
would be worse than omitting it.

## Updates (spec 46/118)

`opencode upgrade` is invoked with `--help` first to confirm the subcommand exists in the installed
release. No version target is hard-coded.

Before an upgrade: configuration is copied to `backups/`, the current version is recorded. After: verify
`--version`, then run a health check. If verification fails, the previous version is reinstalled. If
rollback cannot produce a working binary, the failure is logged rather than left silent.

Updates are never automatic without explicit permission.

## Known gaps

- **No bundled userland payload.** `ProvisionedUserlandProvider` expects
  `runtime/userland/<triple>.tar.zst` to be present. Without it, setup reports that no userland is
  available for the ABI. This is a packaging decision left to the release build; see
  [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
- **No NDK build of `libopencodepty.so`.** `ForkPtyHost` and its JNI declarations are complete, but the
  `.so` is not built here. Without it the app uses the pipe fallback, which loses full-screen programs.
- **`/pty` endpoints are not used.** OpenCode exposes its own PTY management (`/pty`, `/pty/{id}/connect`,
  `PATCH /pty/{id}` with `size`). The app uses its own PTY for the terminal so that shell sessions are
  independent of OpenCode's lifecycle, which spec 63 requires.