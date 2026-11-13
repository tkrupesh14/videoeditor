# Security design: BYO Anthropic API key and MCP bridge

Status: design, 2026-11-13. Scope: `:core:ai`, `:core:mcp`, and the Android glue in `:app`.
Companion doc: `docs/design/timeline.md` (command API). Where this doc says "Command JSON" it means the
serialized command types defined there; names used here (`revision`, `Command`) must be reconciled with it.

Hard constraints (repeated because they shape every decision below):

- The only Anthropic credential the app ever handles is a **user-supplied Anthropic Console API key**.
  No Claude.ai login, no subscription OAuth, no Claude Free/Pro/Max tokens. The key validator actively
  rejects subscription OAuth tokens (section A2.4).
- The only other integration surface is **MCP**.
- Permissive licenses only. No secrets in the repo.

Verified external facts (checked 2026-11-13):

| Fact | Finding | Source |
|---|---|---|
| Current MCP spec revision | **2026-07-28** | `modelcontextprotocol.io/specification/latest` (schema path `schema/2026-07-28/schema.ts`) |
| Standard transports | **stdio** and **Streamable HTTP**. HTTP+SSE (2024-11-05) is deprecated. | `/specification/2026-07-28/basic/transports` |
| Shape of 2026-07-28 | "Modern" era: **no `initialize` handshake, no protocol sessions, no GET stream**. Every request carries version and capabilities in `_meta`; servers must implement `server/discover`. Revisions up to 2025-11-25 are "legacy" (handshake based). | `/basic/versioning`, `/basic/transports/streamable-http` |
| MCP authorization | Optional. HTTP transports SHOULD use the OAuth 2.1 based flow; **stdio SHOULD NOT, and takes credentials from the environment**. | `/basic/authorization` |
| MCP Kotlin SDK license | `LICENSE` on `main` is **Apache-2.0**, with a preamble: the project is moving from MIT to Apache-2.0, and contributions not yet relicensed stay **MIT**. Both are permissive. GitHub reports SPDX `NOASSERTION` only because of that preamble. | `github.com/modelcontextprotocol/kotlin-sdk/blob/main/LICENSE` |
| MCP Kotlin SDK version | Latest release **0.16.0** (2026-10-27). `main` declares `LATEST_PROTOCOL_VERSION = "2025-11-25"`, so the SDK speaks the **legacy** era only. | `kotlin-sdk-core/.../types/common.kt` |

Consequence of the last two rows is decided in B7.

---

## Part A: BYO Anthropic API key

### A1. Storage

#### A1.1 Options

| | `EncryptedSharedPreferences` (androidx.security-crypto) | In-house AndroidKeyStore wrapper |
|---|---|---|
| Maintenance | Deprecated by Google; no further fixes | About 120 lines we own |
| Dependencies | security-crypto plus Tink | None (platform JCA only) |
| Failure behavior | Known to throw on keyset corruption and after backup restore; recovery is undocumented | Explicit, specified below |
| Fit | Encrypts a whole prefs file; we store one value | Exactly one value |

**Decision: in-house wrapper.** We store one short secret, the platform primitives are sufficient, and we need
defined behavior on key loss, which the deprecated library does not give us.

#### A1.2 Parameters

Key (generated once, lazily, on first save):

| Parameter | Value |
|---|---|
| Provider | `AndroidKeyStore` |
| Alias | `dev.videoeditor.apikey.v1` |
| Algorithm | AES, 256-bit |
| Purposes | `PURPOSE_ENCRYPT or PURPOSE_DECRYPT` |
| Block mode / padding | `BLOCK_MODE_GCM` / `ENCRYPTION_PADDING_NONE` |
| Randomized encryption | `setRandomizedEncryptionRequired(true)`; the Keystore generates the IV, we never supply one |
| StrongBox | API 28+: try `setIsStrongBoxBacked(true)`; on `StrongBoxUnavailableException` regenerate without it (TEE) |
| User authentication | `setUserAuthenticationRequired(false)` in v1 (see note) |
| Exportability | Not exportable (inherent to AndroidKeyStore) |

Cipher: `AES/GCM/NoPadding`, 12-byte IV read back from `cipher.iv`, 128-bit tag,
AAD = UTF-8 `"dev.videoeditor.apikey|v1"` (binds the ciphertext to its purpose, so a blob copied from another
slot fails authentication).

Stored record, in a dedicated plain `SharedPreferences` file `secure_ai.xml`, key `anthropic_api_key`:

```
base64(NO_WRAP)( 0x01 || iv[12] || ciphertext || tag[16] )
```

Also stored in clear in the same file: `key_hint` (last 4 characters, for display) and `saved_at`.
A dedicated file is used instead of DataStore so that the backup exclusion rule is one path.

Note on user authentication: binding the key to biometrics would invalidate it on every new enrollment and
would block AI calls whenever no prompt can be shown. The threat it addresses (someone holding the unlocked
phone) is better served by never displaying the key (A2.3). Revisit only if a "require unlock to use AI"
feature is requested.

#### A1.3 Key loss, corruption, restore

All of these are treated the same way: **wipe and ask again**. Never crash, never retry in a loop.

| Trigger | Detection | Result |
|---|---|---|
| Keystore key gone or invalidated (device restore, OEM keystore reset, lock-screen change on some devices) | `KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`, `KeyStoreException`, alias missing while a ciphertext exists | Delete ciphertext and alias; state `Missing(Invalidated)` |
| Ciphertext corrupt or from another install | `AEADBadTagException`, bad version byte, short blob | Same |
| Transient keystore failure | `ProviderException` without the above causes | State `Unavailable`; keep data; AI disabled until the next successful read |

UI for `Missing(Invalidated)`: "Your saved API key could not be read on this device. Enter it again."

Backup and device transfer. A Keystore key never leaves the device, so a restored ciphertext is garbage, and
the pairing records (B4) must not be cloned to a second phone either. Therefore:

- `android:allowBackup="false"` in the manifest for v1.
- **Also** ship `android:dataExtractionRules="@xml/data_extraction_rules"` (API 31+) excluding
  `sharedpref/secure_ai.xml`, `sharedpref/bridge_clients.xml` and `sharedpref/spend_ledger.xml` from both
  `<cloud-backup>` and `<device-transfer>`, and `android:fullBackupContent="@xml/backup_rules"` with the same
  excludes for API 26 to 30. Reason: for apps targeting API 31+, `allowBackup="false"` does not reliably stop
  device-to-device transfer, and the rules keep us safe if someone later enables backup for project files.

Uninstall removes both the prefs and the Keystore alias. "Remove key" in settings deletes ciphertext, hint,
and alias.

#### A1.4 `ApiKeyStore` and `SecretString`

`:core:ai` owns the interface and stays pure JVM. `:app` provides `KeystoreApiKeyStore`.
Tests and the headless `:core:mcp` process (which never needs the key) use `InMemoryApiKeyStore`.

```kotlin
package dev.videoeditor.core.ai.key

/**
 * Holder for a secret. Deliberately a final class, NOT a value class and NOT a data class:
 * a value class is erased to String in JVM signatures (Java callers, reflection and serializers see
 * the raw value), and a data class generates copy/componentN/toString. Not Serializable, no
 * kotlinx @Serializable, no Parcelable.
 */
class SecretString private constructor(private val chars: CharArray) : AutoCloseable {
    /** The only way to read the value. Call sites are restricted by a lint rule (A2.1). */
    fun <T> use(block: (CharSequence) -> T): T
    /** Zeroes the backing array. Best effort; JVM strings made from it elsewhere are not covered. */
    override fun close()
    override fun toString(): String = "SecretString(REDACTED)"
    override fun equals(other: Any?): Boolean   // identity only
    override fun hashCode(): Int                // identity only
    companion object { fun of(value: CharSequence): SecretString }
}

sealed interface ApiKeyState {
    data class Present(val hint: String, val savedAtEpochMs: Long) : ApiKeyState   // hint = last 4 chars
    data class Missing(val reason: Reason) : ApiKeyState { enum class Reason { NeverSet, Removed, Invalidated } }
    data object Unavailable : ApiKeyState
}

interface ApiKeyStore {
    /** Observable for settings UI and for gating AI entry points. Never carries the key. */
    val state: kotlinx.coroutines.flow.StateFlow<ApiKeyState>

    /** Validates format (A2.4), encrypts, persists. Returns the format failure instead of throwing. */
    suspend fun save(key: SecretString): SaveResult

    /** Decrypts for one request. Null when state is not Present. Caller must close() the result. */
    suspend fun load(): SecretString?

    /** Deletes ciphertext, hint and the keystore alias. Idempotent. */
    suspend fun clear()
}

sealed interface SaveResult {
    data object Saved : SaveResult
    data class Rejected(val reason: ApiKeyFormat.Problem) : SaveResult
    data object StorageFailed : SaveResult
}
```

### A2. Leak prevention

#### A2.1 One path for the key

The key is read in exactly one place: `AnthropicAuth`, a request-phase hook in `:core:ai` that calls
`ApiKeyStore.load()` and appends the `x-api-key` header to that single request, then closes the
`SecretString`. Rules:

- Never put the key in `defaultRequest {}` or any long-lived client config object.
- Never pass the key as a `String` parameter across module APIs. Only `SecretString`.
- `SecretString.use` may be called only from `AnthropicAuth` and `KeystoreApiKeyStore`. Enforce with a
  detekt forbidden-method rule with those two files allowlisted.

The official Anthropic Java SDK (MIT, usable from Kotlin) is an alternative to hand-written ktor calls.
The choice belongs to the `:core:ai` design; every rule in this section applies to either.

#### A2.2 Logging, errors, telemetry

| Channel | Rule |
|---|---|
| ktor `Logging` plugin | **Not installed in release builds.** In debug: `level = LogLevel.HEADERS` at most (never `BODY`; bodies carry transcripts and frames) and `sanitizeHeader { it.equals("x-api-key", true) \|\| it.equals("authorization", true) }` |
| Exceptions | `:core:ai` never lets a ktor exception escape. It maps to `AiError(kind, httpStatus, anthropicErrorType, requestId, message)`. No request object, no headers, no URL with query. `AiError.message` is built from the response `error.message` only |
| Logcat / app logs | No logging of request headers or of settings state. The API key hint (last 4) is the most that may appear |
| Crash reporting, analytics | **None in v1.** If added later: no breadcrumbs from the settings or pairing screens, and a scrubber that drops anything matching `sk-ant-[A-Za-z0-9_-]{8,}` and `vedb1_[A-Za-z0-9_-]{8,}` |
| Process state | The key text field uses `remember`, **not** `rememberSaveable`; it must not enter the saved-instance Bundle |
| Repo | `scripts/secret-scan.sh` (already wired) must include the two patterns above |

Enforcement test ("canary test", `:core:ai`, runs in CI): drive a full request against a mock engine with a
canary key, with debug logging enabled, once for success and once for each error path (401, 429, 500,
timeout, malformed body). Capture all log output plus `toString()`, `message` and stack trace of every
thrown or returned error, and assert the canary never appears.

#### A2.3 Settings screen

- `FLAG_SECURE` is set while the API key screen **and** the pairing screen (B3) are visible, and cleared on
  leaving. This blocks screenshots, screen recording, casting and the recents thumbnail.
- The field is a password field: masked, `KeyboardType.Password`, autocorrect off,
  `IME_FLAG_NO_PERSONALIZED_LEARNING`, excluded from autofill save.
- After saving, the field is cleared and the screen shows only `sk-ant-…ab12`. **The stored key is never
  shown again and there is no "copy key" action.** To change it, the user enters a new one.
- Clipboard: pasting is the normal way in. Provide an explicit Paste button (one user-initiated clipboard
  read). After a successful save, call `ClipboardManager.clearPrimaryClip()` (API 28+; on 26 and 27 set an
  empty clip) and show "Clipboard cleared". The app never writes the key to the clipboard.

#### A2.4 Format check

Pure function in `:core:ai`, no I/O, no logging; it returns an enum, never the input.

```kotlin
object ApiKeyFormat {
    enum class Problem { Empty, HasWhitespace, WrongPrefix, SubscriptionToken, AdminKey, BadCharacters, BadLength }
    fun check(candidate: CharSequence): Problem?   // null = looks plausible
}
```

Rules, applied after trimming leading and trailing whitespace:

1. Starts with `sk-ant-oat` → `SubscriptionToken`. Message: "That is a Claude subscription token. This app
   only works with an API key from the Anthropic Console." (This is the hard constraint, enforced in code.)
2. Starts with `sk-ant-admin` → `AdminKey`. Message: "That is an Admin key. Create a regular API key instead."
3. Otherwise must start with `sk-ant-`, contain only `[A-Za-z0-9_-]`, length 40 to 512.

The check is deliberately loose about the rest of the format so that a new key generation does not lock
users out. Optional live check on save: `GET /v1/models` with the key (no token cost). 401 means invalid;
network failure means "saved, not verified". The result is shown, never logged.

#### A2.5 What is sent to Anthropic

Sent, per AI request:

| Data | Limits |
|---|---|
| Timeline JSON | Structure only: clip IDs, in/out points, tracks, captions. Asset references are opaque `assetId`s; **no file paths, content URIs, or original filenames** |
| Transcript | Text with timestamps, produced on device |
| Silence and scene-change timestamps | Numbers only |
| Keyframes | Off by default, separate toggle. At most 8 per request, long edge at most 512 px, JPEG re-encoded from decoded bitmaps (so no EXIF or location metadata) |
| The user's instruction | As typed |

Never sent: raw video or audio, full-resolution frames, file paths, contacts, device identifiers, location,
the MCP pairing data. Requests go only to `https://api.anthropic.com` with platform default TLS validation
(no custom trust manager, no user-configurable base URL in release builds).

If transcription is ever moved off device, that is a new data flow and needs its own disclosure.

Consent, shown once before the first AI request and available in settings afterwards:

> **AI editing sends data to Anthropic**
> To suggest edits, this app sends your project's timeline, the transcript of your clips, and the timestamps
> of silences and scene changes to Anthropic, using your own API key. If you turn on "Let AI see frames", a
> few small still images from your video are sent as well. Your video and audio files never leave this device.
> Anthropic handles this data under the terms of your Anthropic API account.
> **Uses your Anthropic API credits, not your Claude subscription.**
> [ Not now ] [ I agree ]

Consent is stored as a version number; changing what is sent bumps the version and asks again.

Prompt injection note: transcripts and captions are untrusted text. The model can act only through the
command API tools, every result is an undoable edit, tool loops are capped (A3.3), and no tool can read
files, reach the network, or export. That bounds the damage to "a bad edit the user can undo".

### A3. Spend control

#### A3.1 Model

`SpendGuard` is a reserve-then-settle ledger in integer **micro-USD** (`Long`; no floating point).
It is a client-side estimate and a safety net, not an invoice. Settings must say so and recommend the real
control: a dedicated API key in a Console workspace with its own spend limit.

Caps:

| Cap | Default | Range | Scope |
|---|---|---|---|
| Session cap | $1.00 | $0.10 to $50 | One AI session: starts when the AI panel is first used in a project, ends on "New session", project close, or 12 h idle. Persisted, so process death does not reset it |
| Rolling 30-day cap | $20.00 | $1 to $500, or off | All projects |

The ledger lives in `spend_ledger.xml` (not secret, but excluded from backup so restores cannot reset or
inflate it).

Prices come from a `PriceTable` data file in `:core:ai` (USD per million tokens, per model ID, with fields
for input, output, cache write, cache read, and any long-prompt tier). Values at the time of writing, to be
re-verified against the Anthropic pricing page when implemented and at each release:

| Model ID | Input | Output | Notes |
|---|---|---|---|
| `claude-opus-5-5` | $4.00 | $20.00 | cache read $0.20 |
| `claude-sonnet-5-5` | $2.00 | $10.00 | cache read $0.10 |
| `claude-haiku-5-5` | $0.10 | $0.50 | $0.50 / $2.50 for prompts above 100K tokens |

A model ID missing from the table is **refused** (fail closed), never priced at zero.

#### A3.2 Before each request

1. Estimate input tokens. Local upper estimate: `ceil(utf8Bytes(serialized request text) / 3)` plus a
   per-image allowance, times 1.2. If the resulting worst case exceeds half of the remaining budget, call
   `POST /v1/messages/count_tokens` and use the exact count instead.
2. `worstCase = inputTokens × inputPrice (highest applicable tier, uncached) + max_tokens × outputPrice`.
   Thinking tokens are billed as output and are bounded by `max_tokens`, so `max_tokens` is the true ceiling.
3. Under a mutex: if `spent + reserved + worstCase > cap` for either cap, deny. Otherwise add `worstCase` to
   `reserved` and return a `Reservation`. The mutex makes concurrent requests unable to jointly exceed a cap.

Every request in a tool-use loop goes through this, not only the first.

#### A3.3 After each request

- Settle with actual `usage`: `input_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`,
  `output_tokens`, each at its table price. Release the reservation.
- If the request failed before any response, release without charge. If a stream died after it started and
  no final usage arrived, **charge the full reservation** (pessimistic).
- If `spent >= cap` after settling, the guard becomes `Exhausted` and the next `reserve` denies.
- Independent loop limit: at most 12 model round trips per user instruction.

#### A3.4 At the cap

- The request is **not sent**. `reserve` returns `Denied`; nothing is retried automatically.
- A tool loop stops between iterations. Edits already applied stay applied; each is on the undo stack.
- UI: a sheet, "AI session limit reached ($1.00 used). Edits made so far are kept and can be undone."
  with actions: Raise limit (opens settings), New session, Stop. The cap is never raised silently.
- A running cost line is always visible in the AI panel: "This session: about $0.18 of $1.00".

```kotlin
package dev.videoeditor.core.ai.spend

@JvmInline value class MicroUsd(val value: Long)

data class Usage(
    val inputTokens: Long, val outputTokens: Long,
    val cacheCreationInputTokens: Long = 0, val cacheReadInputTokens: Long = 0,
)

data class SpendSnapshot(
    val sessionSpent: MicroUsd, val sessionCap: MicroUsd,
    val rollingSpent: MicroUsd, val rollingCap: MicroUsd?,
    val reserved: MicroUsd, val exhausted: Boolean,
)

sealed interface SpendDecision {
    data class Approved(val reservation: Reservation) : SpendDecision
    data class Denied(val reason: Reason, val snapshot: SpendSnapshot) : SpendDecision {
        enum class Reason { SessionCap, RollingCap, UnknownModel, LoopLimit }
    }
}

class Reservation internal constructor(val id: String, val worstCase: MicroUsd)

interface SpendGuard {
    val snapshot: kotlinx.coroutines.flow.StateFlow<SpendSnapshot>

    /** Call before every request. Atomic check-and-reserve of the worst case. */
    suspend fun reserve(modelId: String, estimatedInputTokens: Long, maxOutputTokens: Int): SpendDecision

    /** Call exactly once per approved reservation. usage == null means "stream died": charge worst case. */
    suspend fun settle(reservation: Reservation, modelId: String, usage: Usage?)

    /** Request never reached the API. */
    suspend fun release(reservation: Reservation)

    suspend fun startNewSession()
    suspend fun setCaps(sessionCap: MicroUsd, rollingCap: MicroUsd?)
}
```

### A4. UI disclosure

The exact sentence **"Uses your Anthropic API credits, not your Claude subscription."** appears, unshortened:

1. Directly under the API key field in settings.
2. In the consent dialog (A2.5).
3. In the AI panel's empty state, before the first request of a project.

The settings screen also links to the Console page for creating keys and to the usage page. There is no
"Sign in with Claude" affordance anywhere in the app.

---

## Part B: MCP

### B1. Architecture

Requirement: Claude Code (possibly on a remote VM) drives the editor on the user's phone, with mandatory
authentication, nothing exposed without it, localhost or tunnel only.

| Option | Assessment |
|---|---|
| (i) App hosts a small authenticated **editor bridge**; the standalone `:core:mcp` JVM process speaks MCP to the client and bridge protocol to the phone | **Chosen** |
| (ii) App hosts the MCP Streamable HTTP endpoint itself | Rejected for v1. Puts an HTTP server, Origin/Host defenses and (for real remote use) an OAuth resource server inside the APK, and ties the APK to MCP revision churn. The 2026-07-28 revision removed sessions and the handshake; with (i) that change touches only the desktop process |
| (iii) Cloud relay | Rejected. Needs hosted infrastructure and accounts; contradicts "runs on your phone, your key" |

Why (i): the phone-side attack surface is one tiny non-HTTP protocol; the MCP process is the same code that
serves offline mode (B8); stdio is the transport Claude Code handles best and needs no network listener.

```
Claude Code ──stdio (MCP)── vedit-mcp (:core:mcp, JVM) ──bridge protocol── adb forward ── phone app (:app)
                                                                                           └ :core:timeline command API → undo stack
```

### B2. Transport and reachability

#### B2.1 Phone side

- The bridge listens on an **Android abstract-namespace Unix socket** named `<applicationId>.bridge`
  (`dev.videoeditor.app.bridge`) via `LocalServerSocket`. **There is no listener on any IP interface**, not
  even `127.0.0.1`, so nothing on Wi-Fi or mobile networks can reach it. This is stricter than
  "bind 127.0.0.1" and is the default and only mode in v1.
- On accept, check `LocalSocket.getPeerCredentials().uid`: allow `2000` (shell, which is `adbd`) and the
  app's own UID (tests); close anything else immediately. Other apps on the phone are refused before
  they can send a byte. This is the same pattern Chrome and WebView use for their devtools sockets.
  It is defense in depth; the token is still mandatory.
- Fallback, debug builds only, off by default: `ServerSocket` bound to `127.0.0.1` on an ephemeral port
  with the same protocol. It loses the peer-UID check. It exists in case a device blocks adb access to
  app-owned abstract sockets (open risk R2).
- No foreground service in v1. The bridge listens only while "Allow MCP control" is on **and** the activity
  is started; `onStop` closes all sessions. While a client is connected the app holds
  `FLAG_KEEP_SCREEN_ON`. Clients reconnect transparently (the protocol has no session state to lose).
- "Allow MCP control" is off by default and resets to off on every cold start.

#### B2.2 Topologies

**T1, recommended: everything on the computer the phone is plugged into.**

```
adb forward tcp:47821 localabstract:dev.videoeditor.app.bridge
claude mcp add vedit -- vedit-mcp stdio
```

`adb forward` binds the computer's loopback only (do not use `adb -a`). USB is a physical link; wireless
debugging on Android 11+ is paired and TLS-protected by adb.

**T2: Claude Code on a remote VM.** `vedit-mcp` runs on the VM as Claude Code's stdio child. The laptop
relays:

```
laptop$ adb forward tcp:47821 localabstract:dev.videoeditor.app.bridge
laptop$ ssh -N -R /run/user/1000/vedit-bridge.sock:127.0.0.1:47821 user@vm     # preferred
laptop$ ssh -N -R 127.0.0.1:47821:127.0.0.1:47821 user@vm                      # fallback
vm$     vedit-mcp stdio --bridge unix:/run/user/1000/vedit-bridge.sock
```

The Unix-socket form is preferred: the VM endpoint is a file only that user can open, so other VM users
cannot connect or squat the port (needs `StreamLocalBindUnlink yes` on the VM's sshd to survive
reconnects). With the TCP form, `GatewayPorts` must stay at its default `no`. `vedit-mcp` (JDK 17) connects
to either via `UnixDomainSocketAddress` or TCP.

**T3: Claude app custom connectors. Not supported in v1.** Stated plainly:

- Connector traffic comes from Anthropic's servers, not from the user's device, so it needs a **publicly
  reachable HTTPS URL**. A phone behind adb has none; the user would have to run `vedit-mcp` in HTTP mode
  behind a public tunnel.
- Connectors authenticate with **OAuth** or not at all. An authless public URL would be unauthenticated
  remote control of the editor, which this design forbids. A static bearer token is not something we can
  rely on the connector UI to send.
- Doing it properly means `vedit-mcp` acting as an OAuth 2.1 resource server (RFC 9728 metadata, audience
  validation, RFC 8707) with an authorization server behind it. That is a project of its own.

Future path (not v1): Streamable HTTP mode in `vedit-mcp` + user-run tunnel + OAuth via an external
authorization server. The phone side would not change at all, which is the point of option (i).

#### B2.3 TLS

No TLS on the bridge in v1. Justification: the phone endpoint is not on a network; every hop is either
in-kernel (Unix socket, loopback), physical (USB), or already encrypted and authenticated (adb wireless
TLS, SSH). A self-signed certificate would add pinning UX and code without closing any path in the threat
model. The rule that makes this safe: **there is no LAN mode and no option to bind a routable address.**
If one is ever added, TLS 1.3 with a key pinned at pairing becomes mandatory for it.

Accepted residual risk: anyone who can use the user's adb connection can already control the whole phone,
so the bridge does not try to defend against the same-user account on the host. A *different* local user
on a shared host could squat a loopback TCP port before `adb forward`/`ssh -R` binds it and relay traffic;
the Unix-socket form in T2 removes that on the VM, mutual proof (B5) detects a fake phone after pairing,
and pairing itself should be done on T1.

#### B2.4 HTTP endpoints

The phone has none. `vedit-mcp` **ships stdio only in v1.** If Streamable HTTP mode is added, these are
requirements, not suggestions:

- Bind `127.0.0.1` only. Never `0.0.0.0` or `::`.
- `Host` must be exactly `127.0.0.1:<port>` or `localhost:<port>`; otherwise 403. (DNS rebinding.)
- `Origin`: if present and not in an explicit allowlist (empty by default), 403. The spec makes Origin
  validation a MUST for Streamable HTTP.
- `Authorization: Bearer` required on every request; never accepted in a query string; compared in
  constant time; 401 otherwise.
- POST only with `Content-Type: application/json`; GET and DELETE return 405; no CORS headers are ever sent.
- On revision 2026-07-28: validate that `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` match the body
  (error `-32020`), per the spec.

### B3. Pairing

Pairing is possible only while the user has the **Pair a new client** screen open (`FLAG_SECURE` set).

1. The app generates an **8-digit code** from `SecureRandom` (uniform, rejection sampled), valid 120 s,
   single use, and shows it with the two commands from T1. It also shows a QR code of
   `vedit-pair://v1?sock=dev.videoeditor.app.bridge&port=47821&bridge=<bridgeId>&code=<code>`
   ("host" is always the adb-forwarded loopback, so the QR carries the socket name and suggested port).
   The typed code is the primary path, because the client is usually a terminal; `vedit-mcp pair --uri`
   accepts the QR's content for setups that can scan it.
2. The user runs `vedit-mcp pair` and types the code. The client connects and sends `bridge.pair`
   with the code, a client name (default `claude-code@<hostname>`) and a nonce.
3. The phone checks the code in constant time. If correct, it shows a dialog: **"Allow 'claude-code@mybox'
   to read and edit your projects?"** with the scopes (B6) as checkboxes, default Read + Edit.
   The code alone is not enough; the user must tap Allow within 60 s.
4. On Allow, the phone creates the client record and returns `clientId`, the **token** (shown to no one,
   sent once), `bridgeId`, and granted scopes. The code is consumed.
5. `vedit-mcp` writes `~/.config/vedit-mcp/credentials.json` (mode 0600, directory 0700; refuses to use a
   file with wider permissions). The path can be overridden with `VEDIT_MCP_CREDENTIALS`. This follows the
   MCP rule that stdio servers take credentials from the environment, and keeps the token out of
   `claude mcp add` command lines and shell history.

Limits:

| Limit | Value |
|---|---|
| Wrong codes per generated code | 5, then the code is burned; the user must tap "New code" |
| Burned codes | 3 in a row locks pairing for 10 minutes |
| Delay after each wrong code | 1 s before the reply |
| Concurrent pairing connections | 1 |
| Guess success per code | at most 5 in 10^8, and a correct guess still needs the user's Allow tap |
| Paired clients | at most 8 |

### B4. Tokens

| Item | Format |
|---|---|
| Token | `vedb1_` + base64url(32 bytes from `SecureRandom`), i.e. 256 bits of entropy |
| `clientId` | `c_` + base64url(16 random bytes); not secret |
| `bridgeId` | `b_` + base64url(16 random bytes), created at install; identifies this phone install |
| Verifier | `SHA-256("vedit-bridge-verifier-v1" ‖ 0x00 ‖ token)` |

"Device-bound" here means: one token per paired client, bound to that `clientId` and to this `bridgeId`,
revocable individually. It cannot be hardware-bound on the client; a pure-JVM process on an arbitrary host
has no portable non-exportable key store. Losing the credentials file means revoking that client.

Phone-side storage (`bridge_clients.xml`, excluded from backup): per client `{clientId, name, scopes,
createdAt, lastUsedAt, verifierBlob}`. **The token itself is never stored on the phone**; only the one-way
verifier, and that is additionally encrypted with an AndroidKeyStore AES-256-GCM key (alias
`dev.videoeditor.bridge.v1`, same parameters as A1.2, AAD `"bridge-verifier|" + clientId`).

Honest note: because authentication is challenge-response (B5), the verifier is what the phone checks
against, so a stolen verifier could authenticate to *this phone's* bridge. It is useless anywhere else, the
token cannot be recovered from it, and an attacker able to read app-private storage and use the Keystore
key already controls the app. The gain over sending the bearer token on every connection is that the token
never crosses the wire after pairing.

Lifecycle: no fixed expiry; a client unused for 90 days is revoked automatically. Revocation UI
(Settings → Connected clients): list with name, scopes, created, last used; **Revoke** per client and
**Revoke all**. Revoking closes that client's live sessions immediately. Losing the Keystore key
(restore, reset) invalidates all clients; the UI says "Pair again".

### B5. Session authentication and wire protocol

Framing: newline-delimited UTF-8 JSON, one JSON-RPC 2.0 shaped message per line, no embedded newlines
(the same framing the MCP spec recommends for byte-stream transports). It is not HTTP, so a browser that
reaches a forwarded port fails at the first line.

Pre-auth rules: the first client message must be `bridge.auth` or `bridge.pair`, at most 4096 bytes, within
5 s; anything else closes the socket with no reply. At most 4 unauthenticated connections at a time.
After auth: requests up to 1 MiB, responses up to 8 MiB (larger reads are paged).

Handshake:

```
S→C {"jsonrpc":"2.0","method":"bridge.challenge",
     "params":{"protocol":1,"bridgeId":"b_…","nonceS":"<b64url 32 bytes>","pairingOpen":false}}
C→S {"jsonrpc":"2.0","id":1,"method":"bridge.auth",
     "params":{"protocol":1,"clientId":"c_…","nonceC":"<b64url 32 bytes>","proof":"<b64url>",
               "client":{"name":"vedit-mcp","version":"0.1.0"}}}
S→C {"jsonrpc":"2.0","id":1,
     "result":{"serverProof":"<b64url>","scopes":["read","edit"],"commandSchema":1,"appVersion":"0.1.0"}}
```

```
proof       = HMAC-SHA256(verifier, "vedit-bridge-auth-v1"   ‖ 0x00 ‖ bridgeId ‖ 0x00 ‖ clientId ‖ 0x00 ‖ nonceS ‖ nonceC)
serverProof = HMAC-SHA256(verifier, "vedit-bridge-server-v1" ‖ 0x00 ‖ bridgeId ‖ 0x00 ‖ clientId ‖ 0x00 ‖ nonceC ‖ nonceS)
```

- **Constant time:** proofs and pairing codes are compared with `MessageDigest.isEqual`. For an unknown
  `clientId` the phone still computes an HMAC with a fixed dummy key before failing, so known and unknown
  IDs take the same time and return the same error.
- **Replay:** `nonceS` is fresh per connection and single use, so a captured `bridge.auth` is worthless.
- **Mutual:** the client verifies `serverProof` and checks `bridgeId` against its credentials file before
  sending anything else, so it will not hand project commands to an impostor endpoint.
- **Rate limit:** 5 failed auths per minute per `clientId` → 60 s refusal; 20 per minute overall → 60 s
  refusal of all unauthenticated connections.

Methods after authentication (params and result are JSON objects):

| Method | Scope | Params | Result |
|---|---|---|---|
| `project.list` | read | none | `{projects:[{projectId,name,revision,durationMs}]}` |
| `project.get` | read | `{projectId?}` (default: the open project) | `{projectId, revision, project}` |
| `transcript.get` | read | `{projectId, clipId?, fromMs?, toMs?, cursor?}` | `{segments:[{clipId,startMs,endMs,text}], nextCursor?}` |
| `command.validate` | read | same as `command.apply` | `{ok, problems:[…]}`; changes nothing |
| `command.apply` | edit | `{projectId, baseRevision, requestId, label, commands:[<Command JSON>]}` | `{revision, undoDepth, results:[…]}` |
| `history.undo` | edit | `{projectId, requestId}` | `{revision}`; only if the top undo entry was made by this client |
| `export.request` | export | `{projectId, preset, requestId}` | `{exportId, state:"awaiting_confirmation"}` |
| `export.status` | export | `{exportId}` | `{state, progress?, error?}`; state ∈ `awaiting_confirmation, running, done, denied, failed` |
| `ping` | any | none | `{}` |

Notifications from the phone: `project.changed {projectId, revision, origin}` with origin ∈ `user, mcp, ai`;
`export.progress {exportId, progress}`; `session.closing {reason}` with reason ∈ `revoked, killed, app_stopped`.

Semantics that matter for safety:

- `commands` is a list of the serialized command types from `:core:timeline`, unchanged. The bridge adds
  no editing logic of its own.
- A `command.apply` batch is **atomic** and becomes **one undo entry**, labelled "Claude (MCP): <label>".
- `baseRevision` must equal the project's current revision, otherwise `STALE_REVISION` and nothing is
  applied. This stops a remote client from editing a timeline the user has changed since it last read.
- `requestId` (UUID) makes retries idempotent: the phone remembers the last 256 per client for 10 minutes
  and returns the original result instead of applying twice.
- Results never contain file paths or content URIs; assets are `assetId` plus a display name.

Errors use JSON-RPC `error` with application codes:

| Code | Name | Code | Name |
|---|---|---|---|
| 1001 | `UNAUTHENTICATED` | 1006 | `RATE_LIMITED` |
| 1002 | `FORBIDDEN_SCOPE` | 1007 | `NOT_FOUND` |
| 1003 | `STALE_REVISION` | 1008 | `PAYLOAD_TOO_LARGE` |
| 1004 | `COMMAND_REJECTED` (with per-command problems) | 1009 | `SESSION_CLOSED` |
| 1005 | `USER_DENIED` | 1010 | `UNSUPPORTED` (e.g. export in offline mode) |

### B6. Authorization, limits, and what the user sees

Scopes, chosen by the user in the pairing dialog: `read`, `edit`, `export`.

Allowed remote commands are a **deny-by-default allowlist** of command types: split, trim, move, delete
clip, add caption, apply cuts. A new command type in `:core:timeline` is not remotely callable until it is
added to the list. Never reachable over the bridge: importing media by path or URI, deleting or renaming
projects, any setting, the API key, spend caps, pairing and revocation.

Limits per client: 60 `command.apply` calls per minute, 200 commands per batch, caption text up to
2,000 characters, one export request pending at a time.

**Export requires confirmation in the app, every time.** `export.request` only raises a dialog
("'claude-code@mybox' wants to export 'Trip' as 1080p. Export?"). Export writes a large file, runs for
minutes and uses battery, and it is the one remote action undo cannot reverse. No "always allow" in v1.

What the user sees:

- A persistent chip in the editor's top bar whenever a client is connected: **"Claude connected:
  claude-code@mybox"** with a **Stop** button. It pulses on each applied batch.
- Each remote batch shows a snackbar "Claude: <label>" with **Undo**.
- **Stop** is the kill switch: it closes every session, stops the listener and turns "Allow MCP control"
  off. Tokens stay valid; reconnecting needs the user to switch it back on. Revocation is separate (B4).
- When "Allow MCP control" is on with no client connected, the chip reads "Waiting for Claude".

### B7. MCP server (`:core:mcp`)

**SDK decision: use the official Kotlin SDK**, `io.modelcontextprotocol:kotlin-sdk-server` 0.16.0, with its
stdio server transport. License is permissive (Apache-2.0, legacy contributions MIT; see the table at the
top). Its dependencies (ktor, kotlinx, kotlin-logging: Apache-2.0; slf4j: MIT) are permissive too.

Known gap: the SDK implements revisions up to **2025-11-25** (legacy, `initialize` handshake), while the
current spec is **2026-07-28**. The spec defines how dual-era clients detect a legacy server and fall
back, so v1 will run as a 2025-11-25 server. To keep this swappable, all SDK use sits behind one internal
interface (`McpFrontend`: register tools and resources, run on stdio), with tool logic in plain functions
that take and return JSON. If a target client turns out to be modern-only before the SDK catches up, the
fallback is a hand-rolled stdio server for 2026-07-28: newline-delimited JSON-RPC, `server/discover`,
`tools/list`, `tools/call`, `resources/list`, `resources/read`, `notifications/cancelled`, with version
and capabilities read from each request's `_meta`. Statelessness makes that small (roughly 300 lines).

stdio hygiene: stdout carries MCP messages only; all logs go to stderr; tokens and proofs are never logged.

Tools. Every mutating tool takes `project_id` (optional, default the open project) and returns the new
`revision`; `vedit-mcp` tracks the last revision it read and supplies `baseRevision` itself, re-reading and
reporting on `STALE_REVISION` rather than retrying blindly.

| Tool | Arguments | Bridge call | Read-only |
|---|---|---|---|
| `get_project` | | `project.get` | yes |
| `list_clips` | `track?` | `project.get`, projected to clips | yes |
| `get_transcript` | `clip_id?`, `from_ms?`, `to_ms?`, `cursor?` | `transcript.get` | yes |
| `split` | `clip_id`, `at_ms` | `command.apply` | no |
| `trim` | `clip_id`, `start_ms?`, `end_ms?` | `command.apply` | no |
| `move` | `clip_id`, `to_track?`, `to_ms` | `command.apply` | no |
| `delete` | `clip_id`, `ripple?` | `command.apply` | no |
| `add_caption` | `text`, `start_ms`, `end_ms`, `style?` | `command.apply` | no |
| `apply_cuts` | `cuts:[{start_ms,end_ms}]`, `ripple?`, `label?` | one `command.apply` batch (one undo entry) | no |
| `export` | `preset` | `export.request`, then `export.status` until terminal or 10 min | no |

Tool annotations: `readOnlyHint` on the three read tools; `destructiveHint` on `delete` and `apply_cuts`.
`undo` (→ `history.undo`) is recommended as an eleventh tool so the model can back out its own last batch.
`export` returns promptly with "waiting for confirmation on the phone" if the user has not answered.

Resources (read-only): `vedit://project/{projectId}` (project JSON, `application/json`) and
`vedit://project/{projectId}/transcript` (plain text). No resource exposes media bytes.

### B8. Offline mode

`vedit-mcp stdio --project /path/to/project.json` edits a project file directly through the same
`:core:timeline` command API, with no phone. Used for tests and for Claude Code on a VM working on an
exported project file.

**Decision: no token in offline stdio mode.** There is no listener to authenticate: the process is started
by the user's own MCP client and can do nothing the invoking user could not do with a text editor. A token
would be ceremony without a security property. The rule is general: **every listener requires
authentication; a stdio child process does not.** (If HTTP mode ever serves offline projects, B2.4 applies
in full.)

Guards that do apply:

- Only the file given by `--project`, or files directly inside `--workspace <dir>`, can be opened. Paths
  are canonicalized; symlinks that leave the allowed location are refused. No tool takes a path argument.
- Same command allowlist, same atomic batches, same revision check as the bridge.
- Writes are atomic (temp file in the same directory, fsync, rename) under an exclusive file lock.
- Undo history is kept in a sidecar `project.json.undo`, so edits stay reversible across runs.
- `export` returns `UNSUPPORTED` (rendering needs the device). `get_transcript` works only if the project
  file embeds a transcript.

### B9. Interfaces

```kotlin
// ───── shared model, pure JVM (lives in :core:mcp, package dev.videoeditor.core.mcp.bridge) ─────

enum class BridgeScope { READ, EDIT, EXPORT }

data class PairedClient(
    val clientId: String, val name: String, val scopes: Set<BridgeScope>,
    val createdAtEpochMs: Long, val lastUsedAtEpochMs: Long?,
)

/** What `vedit-mcp pair` persists. toString() must redact token. */
class BridgeCredentials(val bridgeId: String, val clientId: String, val token: SecretToken, val scopes: Set<BridgeScope>)

class SecretToken private constructor(private val value: String) {
    fun <T> use(block: (String) -> T): T
    override fun toString(): String = "SecretToken(REDACTED)"
    companion object { fun of(value: String): SecretToken }
}

class BridgeException(val code: Int, val name: String, message: String) : Exception(message)
```

```kotlin
// ───── TokenVerifier: pure JVM, used by the app-side bridge; no Android types ─────

interface TokenVerifier {
    /** Fresh 32-byte server nonce for one connection. */
    fun newChallenge(): ByteArray

    /**
     * Checks a bridge.auth proof in constant time. Performs the same work for unknown clients.
     * Applies the auth rate limits. On success also returns the proof the server must send back.
     */
    suspend fun verify(
        clientId: String, nonceS: ByteArray, nonceC: ByteArray, proof: ByteArray,
    ): VerifyResult

    sealed interface VerifyResult {
        class Accepted(val client: PairedClient, val serverProof: ByteArray) : VerifyResult
        data object Rejected : VerifyResult          // one indistinguishable failure
        data class Throttled(val retryAfterMs: Long) : VerifyResult
    }
}
```

```kotlin
// ───── PairingManager: app side (:app), UI-facing ─────

interface PairingManager {
    val pairing: kotlinx.coroutines.flow.StateFlow<PairingState>
    val clients: kotlinx.coroutines.flow.StateFlow<List<PairedClient>>

    /** Called when the pairing screen opens or the user taps "New code". */
    suspend fun beginPairing(): PairingOffer
    /** Called when the pairing screen closes. Invalidates any live code. */
    fun cancelPairing()

    /** Called by BridgeServer on bridge.pair. Suspends until the user answers or 60 s pass. */
    suspend fun redeem(code: String, clientName: String): RedeemResult
    /** Called by the confirmation dialog. */
    fun answer(requestId: String, allow: Boolean, scopes: Set<BridgeScope>)

    suspend fun revoke(clientId: String)
    suspend fun revokeAll()
}

data class PairingOffer(val code: String, val qrUri: String, val expiresAtEpochMs: Long)

sealed interface PairingState {
    data object Closed : PairingState
    data class Offering(val offer: PairingOffer, val attemptsLeft: Int) : PairingState
    data class AwaitingApproval(val requestId: String, val clientName: String) : PairingState
    data class LockedOut(val untilEpochMs: Long) : PairingState
}

sealed interface RedeemResult {
    class Paired(val credentials: BridgeCredentials) : RedeemResult   // token leaves the phone here, once
    data object WrongCode : RedeemResult
    data object NotOffering : RedeemResult
    data object Denied : RedeemResult
    data class LockedOut(val retryAfterMs: Long) : RedeemResult
}
```

```kotlin
// ───── BridgeServer: app side (:app) ─────

interface BridgeServer {
    val state: kotlinx.coroutines.flow.StateFlow<BridgeState>

    /** Starts listening on the abstract socket. Requires "Allow MCP control" to be on. */
    suspend fun start()
    /** Kill switch: closes all sessions and the listener. Does not revoke tokens. */
    suspend fun stop(reason: StopReason = StopReason.USER)
    /** Closes live sessions of one client (used by revocation). */
    suspend fun disconnect(clientId: String)
}

enum class StopReason { USER, APP_STOPPED, REVOKED }

sealed interface BridgeState {
    data object Off : BridgeState
    data object Listening : BridgeState
    data class Connected(val sessions: List<BridgeSession>) : BridgeState
}

data class BridgeSession(val client: PairedClient, val connectedAtEpochMs: Long, val lastCommandLabel: String?)

/** The only thing BridgeServer may call to touch projects. Implemented in :app over :core:timeline. */
interface BridgeCommandSink {
    suspend fun listProjects(): JsonElement
    suspend fun getProject(projectId: String?): JsonElement
    suspend fun getTranscript(projectId: String, query: JsonObject): JsonElement
    suspend fun validate(projectId: String, commands: JsonArray): JsonElement
    suspend fun apply(projectId: String, baseRevision: Long, label: String, origin: String, commands: JsonArray): JsonElement
    suspend fun undo(projectId: String, originClientId: String): JsonElement
    suspend fun requestExport(projectId: String, preset: String, requestedBy: PairedClient): JsonElement
    suspend fun exportStatus(exportId: String): JsonElement
}
```

```kotlin
// ───── BridgeClient: :core:mcp side ─────

sealed interface BridgeEndpoint {
    data class Tcp(val host: String = "127.0.0.1", val port: Int) : BridgeEndpoint   // host must be loopback
    data class UnixSocket(val path: java.nio.file.Path) : BridgeEndpoint
}

interface BridgeClient : AutoCloseable {
    /** One-time pairing. Returns credentials for the caller to persist with mode 0600. */
    suspend fun pair(endpoint: BridgeEndpoint, code: String, clientName: String): BridgeCredentials

    /** Connects, verifies bridgeId and serverProof, authenticates. Throws BridgeException on failure. */
    suspend fun connect(endpoint: BridgeEndpoint, credentials: BridgeCredentials)

    val notifications: kotlinx.coroutines.flow.Flow<BridgeNotification>

    suspend fun listProjects(): JsonObject
    suspend fun getProject(projectId: String? = null): JsonObject
    suspend fun getTranscript(projectId: String, clipId: String? = null, fromMs: Long? = null, toMs: Long? = null, cursor: String? = null): JsonObject
    suspend fun validate(projectId: String, baseRevision: Long, commands: JsonArray): JsonObject
    suspend fun apply(projectId: String, baseRevision: Long, requestId: String, label: String, commands: JsonArray): JsonObject
    suspend fun undo(projectId: String, requestId: String): JsonObject
    suspend fun requestExport(projectId: String, preset: String, requestId: String): JsonObject
    suspend fun exportStatus(exportId: String): JsonObject
}

data class BridgeNotification(val method: String, val params: JsonObject)

/**
 * What the MCP tools are written against. Two implementations: BridgeProjectBackend (phone, via
 * BridgeClient) and FileProjectBackend (offline mode, B8). Same method set as BridgeCommandSink.
 */
interface ProjectBackend
```

`BridgeEndpoint.Tcp` rejects any host that does not resolve to a loopback address; there is no flag to
override that.

### B10. Tests that must exist before this ships

- Canary test for the API key (A2.2) and an equivalent for bridge tokens across `:core:mcp` logs and errors.
- `ApiKeyFormat`: subscription-token and admin-key prefixes are rejected with their specific reasons.
- `SpendGuard`: concurrent reserves cannot exceed the cap; unknown model is denied; dead stream charges the
  reservation.
- Pairing: 5 wrong codes burn the code; lockout after 3 burned codes; expired and reused codes fail; a
  correct code without the Allow tap yields no token.
- Auth: replayed `bridge.auth` on a new connection fails; unknown and known `clientId` failures are
  indistinguishable; revoked client is disconnected and cannot reconnect.
- Framing: an HTTP request line as the first message closes the socket with no response bytes.
- Authorization: a non-allowlisted command type is rejected; `edit` without the scope is rejected; stale
  `baseRevision` applies nothing; repeated `requestId` applies once; export never starts without confirmation.
- Offline: path outside the workspace and symlink escapes are refused.

---

## Decision summary

1. **API key storage:** in-house AES-256-GCM wrapper on a non-exportable AndroidKeyStore key; ciphertext in a backup-excluded prefs file; on any key loss, wipe and ask again. `EncryptedSharedPreferences` is not used.
2. **Leak control:** the key exists only as `SecretString`, is read in one place, is never shown again after saving, and a CI canary test proves it stays out of logs and errors; subscription tokens are rejected by format.
3. **Spend:** reserve worst case (input estimate + `max_tokens`) before every request, settle with real usage after; at the cap the request is not sent and the user decides.
4. **MCP architecture:** the app hosts a minimal authenticated bridge on an abstract Unix socket (no IP listener), reached through `adb forward` and optionally SSH; `vedit-mcp` is a separate stdio MCP server built on the official Kotlin SDK (Apache-2.0/MIT) behind a swappable seam.
5. **Auth:** 8-digit one-time code plus an on-phone Allow tap yields a 256-bit per-client token; sessions use nonce-based HMAC challenge-response with mutual proof; only an encrypted one-way verifier is stored on the phone; per-client revocation and a kill switch.
6. **Scope of v1:** stdio only; no TLS because nothing is on a network; Claude app connectors are not supported (they need a public HTTPS URL and OAuth); offline file mode needs no token; export always needs confirmation on the phone.

## Threats and mitigations

| Threat | Mitigation |
|---|---|
| API key leaks through logs, exceptions or crash data | `SecretString`, single injection point, ktor logging off in release and sanitized in debug, mapped errors, canary test, no telemetry in v1 |
| API key read from device storage or a backup | Keystore-wrapped AES-GCM, backup and device-transfer exclusion, wipe on invalidation |
| Key captured from the screen or clipboard | `FLAG_SECURE`, masked field, never redisplayed, clipboard cleared after save, not in saved state |
| Runaway API spend (loop, large context, injected instructions) | Worst-case reservation before every request, session and 30-day caps, 12-round loop limit, fail closed on unknown model |
| Private media sent to Anthropic unexpectedly | Fixed payload list, no paths or raw media, keyframes opt-in and downscaled, versioned consent |
| Another app on the phone drives the editor | Abstract socket with peer-UID check, mandatory token, bridge off by default |
| LAN or internet attacker reaches the bridge | No IP listener on the phone; loopback-only forwards; no LAN mode exists |
| Web page attacks a forwarded port (CSRF, DNS rebinding) | Bridge is not HTTP and drops non-protocol first lines; any future HTTP mode enforces Host, Origin, bearer and JSON content type |
| Pairing code guessed or auth replayed | 5 attempts per code, lockouts, 120 s TTL, Allow tap required; per-connection nonces, constant-time checks, auth rate limits |
| Stolen or stale client credentials | Per-client tokens, revoke and revoke-all, 90-day idle expiry, visible connected-client chip |
| Malicious or mistaken remote edits (including prompt injection via transcript text) | Command allowlist, atomic undoable batches, revision check, rate and size limits, export confirmation |
| Port squatting by another user on a shared host or VM | Unix-socket SSH forward (user-private), mutual proof against a fake phone, pair on the local machine; residual risk documented in B2.3 |

## Open risks

- **R1.** Whether Claude Code falls back to a legacy (2025-11-25) stdio server has not been tested here; confirm in phase 6. Fallback is the hand-rolled 2026-07-28 server described in B7.
- **R2.** `adb forward … localabstract:` to an app-owned socket and the shell peer UID (2000) are unverified on real API 26 and API 37 devices (no emulator in this environment). Fallback is the debug-only loopback TCP listener.
- **R3.** Prices in A3.1 and the connector requirements in T3 must be re-checked against Anthropic's current documentation at implementation time.
- **R4.** Without a foreground service the bridge drops when the app leaves the foreground. Acceptable for v1; a service needs a foreground-service-type decision.
