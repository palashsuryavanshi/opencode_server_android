# Security

## Threat model

The server runs on the user's own device, so the realistic risks are not remote attackers. They are:

| Risk | Control |
|---|---|
| Another app on the device reaching the server | Loopback bind + HTTP basic auth with a Keystore-stored password |
| A user on the LAN reaching the server | Loopback by default; `0.0.0.0` requires explicit opt-in behind a warning |
| A credential leaking through a log or bug report | `SecretRedactor` on every log write and every diagnostic string |
| A credential leaking into a backup | PBKDF2 + AES-GCM encryption for archives containing credentials |
| A UI field reading or writing outside the project | `PathGuard` with canonical-path containment |
| A UI field injecting shell commands | Argument arrays; `shell = true` confined to the installer and the terminal |
| A crafted response crashing the client | Every parse failure becomes a `BackendError` |
| A symlink escaping a sandbox | Canonical paths, not string prefixes |

## Binding and authentication (spec 14/15/50)

The server binds `127.0.0.1`. `--hostname 0.0.0.0` is only passed when the user has enabled LAN access,
which the UI presents with the spec's warning and requires explicit confirmation.

`OPENCODE_SERVER_PASSWORD` is:

- generated locally with `SecureRandom` from a 32-character alphabet
- drawn from an alphabet excluding `0`, `O`, `1`, `l` and `I`, so it can be read aloud if ever needed
- stored in the Keystore via AES-GCM under a stable alias
- passed to the child process only through `CredentialManager.processSecrets()`, at spawn time
- never logged, never placed in a URL, never written to a plain file by the app

`OpenCodeConnection` carries a `credentialReference` (`keystore:OPENCODE_SERVER`), not the password.
The HTTP header is assembled per request from the vault.

## Credential storage (spec 27)

`KeystoreCipher` generates a non-exportable AES-256 key inside `AndroidKeyStore` using GCM. The app
cannot read the key material even with full process memory access.

Ciphertext lives in `runtime/state/credentials.vault`, one `id\u0000payload` per line. Writes go to a
temp file and are renamed into place, so an interrupted write cannot lose every credential.

What is deliberately **not** used:

- SharedPreferences for secrets (the user preference for mock mode is the only thing stored there)
- plain JSON
- plaintext SQLite
- logs, source, Git

Provider keys are handed straight to OpenCode's `PUT /auth/{id}` over loopback and registered with the
redactor. The app keeps no second copy; OpenCode's auth store is the single source of truth.

### UI masking

`SecretRedactor.maskForDisplay` produces `sk-••••••••••••••••9X2A`: a vendor prefix and a 4-character
suffix, nothing in between. A short value yields bullets only.

## Log redaction (spec 108)

Every write goes through `SecretRedactor`. It combines literal secrets registered at runtime with shape
patterns:

- `Authorization:` headers of any scheme
- `Bearer`, `token`, `api_key`, `secret`, `password`, `cookie` assignments
- `sk-`, `sk-ant-`, `gh[pousr]_`, `xox[baprs]-`, `AIza`, JWT patterns
- credentials embedded in URLs
- `-----BEGIN ... PRIVATE KEY-----` blocks
- `Cookie` / `Set-Cookie` headers

Registered literals are matched first, so a password with no distinctive shape is still scrubbed.
Short values (< 8 chars) are not registered, because redacting them would destroy ordinary log text.

Diagnostics additionally collapse the runtime home path to `~`.

`SecretRedactorTest` asserts on the *absence* of secrets, not the presence of placeholders.

## Path security (spec 83)

`PathGuard` models two distinct intents:

- `normalizeWithin(root, input)` — the operation is sandboxed. Uses canonical paths so a symlink
  pointing outside the root is caught.
- `normalizeAbsolute(input)` — the user deliberately chose this directory. Validated for existence,
  readability and NUL bytes, but not confined.

`rejectTraversal` rejects `..` in any project-relative path, including backslash-separated forms. It
rejects a path that climbs out *and back in*, because intent rather than outcome is what matters for a
UI-supplied value.

`PathGuardTest` covers the spec's own example (`../../something`) and the symlink case.

`MOCK_BACKEND` enforces the same rules as production, so a screen cannot be built against a laxer
contract.

## Command safety (spec 84)

`ProcessRunner.CommandSpec` takes an executable and a list of arguments. There is no "command string"
builder.

`shell = true` exists in exactly two places:

1. the installer, where the command and its arguments are both owned by app code
2. the terminal, which is the product

Even there, `buildShellCommand` single-quotes every argument, so a value containing spaces, `;` or
`$(...)` reaches the shell as a literal.

API calls, Git calls, health checks and version probes never set `shell`.

## Malformed input (spec 82)

- `HttpOpenCodeApiClient.parseBody` turns a non-2xx into a typed error via `classifyStatus`
- A deserialization failure becomes `BackendError.MalformedResponse`, never a crash
- `ApiModels` keeps union members as maps with typed accessors, so unknown values degrade
- `OpenCodeEventMapper` surfaces an unrecognised event type as an `Error` naming it, rather than
  dropping it silently

## Process termination (spec 85)

`ProcessRegistry.stopGracefully` sends `SIGTERM` to the single PID, waits, then `destroy()`, then
`SIGKILL`. It never kills a process tree. Terminal interrupts use `SIGINT` so the child can clean up.

Liveness is checked with `Process.isAlive`, never a stored flag, because Android reuses PIDs.

## Destructive actions (spec 35/58)

- No root, ever. No attempt to bypass Android restrictions.
- `removeProject` unregisters only; files stay on disk
- `deleteProjectFiles` requires `confirm = true` and refuses to delete anything outside the app sandbox
- `resetEnvironment` requires `confirm = true`, backs up configuration first, and keeps user projects
- Nothing destructive runs without a user action

## Backup archives (spec 90)

`exportBackup(includeCredentials = true)` encrypts the credential block with PBKDF2-HMAC-SHA256
(200,000 iterations) and AES-GCM under a passphrase. The salt is stored alongside the ciphertext.

`importBackup` deliberately does **not** restore credentials. A leaked archive must not grant API
access, so restoring settings cannot reintroduce a secret.

## LAN access warning (spec 50)

Enabling LAN access surfaces the spec's warning verbatim:

> LAN access exposes your OpenCode server to other devices on the same network.
> Only enable this if you understand the security implications.

and requires explicit confirmation. The bind change is applied by restarting the server, because a
running process cannot change its listen address.

## Not implemented

- **No encryption at rest for project files.** Projects are the user's own source code in their own
  directory; encrypting them would break the tools that need to read them.
- **No rootless sandbox escape.** If the runtime is compromised, the app's own sandbox is the boundary.
- **No certificate pinning.** The app talks to loopback and to OpenCode's own endpoints over the
  platform trust store.
- **`APP_SECURE` / network security config.** `usesCleartextTraffic="true"` is required because the
  server is plain HTTP on loopback. This permits cleartext to any host, which a production release
  should narrow to `127.0.0.1` and `localhost` via a network security configuration.