# Final review (architect, one pass)

Branch `overnight/build-20261113`, 2026-11-14. Review only: no source was changed, nothing was committed.
Method: read `docs/design/security.md`, `DECISIONS.md`, then the security, tool-surface, license and doc files
named in the brief. No tests were run for this review; every finding below was confirmed by reading the code
unless it is labelled PLAUSIBLE.

Paths are relative to the repo root. Abbreviations: `AI` = `core/ai/src/main/kotlin/dev/videoeditor/core/ai`,
`MCP` = `core/mcp/src/main/kotlin/dev/videoeditor/core/mcp`, `APP` = `app/src/main/kotlin/dev/videoeditor/app`,
`TL` = `core/timeline/src/main/kotlin/dev/videoeditor/timeline`.

## Summary

No blocker and no high-severity finding. The hard constraints hold in code: Console API key only
(`sk-ant-oat` rejected), key in the Android Keystore and never logged, no raw media sent, preview that must be
accepted and undoes in one step, hard spend cap, MCP on a local socket with mandatory pairing. Three medium
findings, the rest low. One constraint is only partly met: there is no cost estimate before a request is sent
(finding 2).

## Findings (most severe first)

### 1. medium: timed-out requests are not charged to the spend ledger

- Where: `AI/assistant/ClaudeEditAssistant.kt:189` (also the catch-all at `:195`), `AI/spend/LedgerSpendGuard.kt:100-106`.
- What: every `AnthropicIoException` releases the reservation with the comment "No response arrived". That
  exception also covers a read timeout or dropped connection after the request was sent, when the model may
  have generated output and Anthropic bills it. `release` also decrements `roundTripsThisInstruction`, so the
  12-round loop limit does not count the attempt.
- Scenario: on a slow link the 5-minute timeout fires mid-response. The ledger records $0 for a request that
  cost up to the worst case (about $0.16 on Opus 5.5 with 8,192 output tokens, plus input). Repeating the
  instruction repeats the loss, so real spend can pass the session and 30-day caps without the guard denying.
- Fix: release only when the request provably never left (connect failure). For any other I/O failure call
  `spendGuard.settle(reservation, config.model, null)`, as the cancellation path already does. If the SDK
  cannot tell the two apart, settle pessimistically for all of them.

### 2. medium: no cost estimate is shown before a request is sent

- Where: `APP/ai/AiEditController.kt` (`runInstruction`, `onResult`), `ui/shell/src/main/kotlin/dev/videoeditor/ui/shell/AiPanes.kt`.
- What: the brief requires a cost estimate plus a hard cap. The cap exists. The only cost text is after the
  fact ("This request: about $X") and the running session line. `reserve` computes a worst case, but it is
  never shown, and the user is not asked to confirm it.
- Scenario: a user on Fable 5.2 with 8 keyframes sends an instruction without seeing that it can cost several
  times the same request on Haiku 5.5.
- Fix: before the first `reserve`, compute `priceTable.priceFor(model).worstCase(estimate, maxTokens)` and show
  "Up to about $X for this request" in the AI panel (next to Send, or in the Working phase text). No new
  dialog is needed.

### 3. medium: an unexpected exception in a bridge connection crashes the app

- Where: `APP/mcp/McpBridgeController.kt:186`, `MCP/bridge/BridgeProtocolServer.kt:153-206` and `:334-340`.
- What: `serve` is launched in `viewModelScope` with no exception handler. `serve` catches only `IOException`;
  the request loop catches only `BridgeException`, `SerializationException` and `IllegalArgumentException`.
  Anything else is an uncaught coroutine exception, which on Android kills the process.
- Scenario (confirmed path): the user taps Allow on a pairing request, `DefaultPairingManager.redeem` calls
  `store.add` (`MCP/bridge/Pairing.kt:251`), and `KeystoreClientStore.add` calls `cipher.encrypt`, which throws
  a Keystore `ProviderException` on a device with a faulty Keystore. The app crashes. Any future
  `IllegalStateException` in a sink method does the same for a paired client.
- Not confirmed: I found no input an unpaired peer can send that triggers this. Malformed JSON, oversized
  first frames and bad base64 are all handled.
- Fix: in the loop add `catch (e: Exception)` (rethrow `CancellationException`) that replies with a generic
  internal-error response; in `serve` add the same catch around the whole body and close the connection.

### 4. low: the same MCP tool returns two result shapes, depending on the backend

- Where: `TL/schema/ToolDispatcher.kt:80` (offline and AI) versus `MCP/BridgeProjectBackend.kt:125` (phone).
- What: offline and AI return `{ok, summary, changed, createdIds, durationUs}`. Bridge mode returns
  `{ok, revision, undoDepth, durationUs, results:[{summary, changed, createdIds}]}`.
- Scenario: Claude splits a clip over the phone bridge and looks for `createdIds` at the top level, as the tool
  text describes; it is under `results[0]`, so the follow-up edit uses a wrong or missing id.
- Fix: in `BridgeProjectBackend.command`, lift `results[0]`'s `summary`, `changed` and `createdIds` to the top
  level and keep `revision` as an extra field.

### 5. low: error `type` values use two naming styles

- Where: `ToolDispatcher` uses `unknown_tool`, `malformed`, `not_available`; `MCP/BridgeProjectBackend.kt:70,130`
  and `MCP/FileProjectBackend.kt:76` use `PHONE_UNREACHABLE`, `STALE_REVISION`, `UNSUPPORTED`; `FileProjectBackend.kt:73`
  uses `unknown_tool` in the same file.
- Scenario: a client that switches on `error.type` needs two conventions for one field.
- Fix: pick lower snake case everywhere and map `BridgeErrors.name(code)` to it in one place.

### 6. low: one MCP tool call is one undo step, and there is no MCP batch or undo tool

- Where: `MCP/BridgeProjectBackend.kt:123` sends a single-command `command.apply` per tool call;
  `ToolRegistry.mcpV1` has no `batch`; `history.undo` exists on the bridge but no MCP tool calls it.
- What: the bridge commits a multi-command apply as one `commitPreview`, so "batch = one undo step" holds at
  the protocol level, but `vedit-mcp` never sends more than one command. A ten-call Claude Code edit leaves
  ten undo steps on the phone. AI accept is correctly one step (`AiEditController.accept`).
- Docs already list the MCP undo tool as not built. The batch gap is not stated.
- Fix: say so in `docs/mcp.md` and README now; later expose a `batch` tool limited to the allowlisted types.

### 7. low: `project.list` reports duration in milliseconds, everything else in microseconds

- Where: `MCP/bridge/SessionBridgeSink.kt:177` (`durationMs`).
- Fix: rename to `durationUs` and drop the division.

### 8. low: the apply idempotency cache ignores the request body

- Where: `MCP/bridge/BridgeProtocolServer.kt:422`.
- What: a second `command.apply` with a reused `requestId` returns the cached result even if its commands
  differ, so the second edit is silently dropped. `vedit-mcp` uses a fresh UUID per call, so this needs a
  different or buggy client.
- Fix: store a hash of `commands` with the cached entry and reject a mismatch with `COMMAND_REJECTED`.

### 9. low: a non-Anthropic exception leaves a reservation pending until restart

- Where: `AI/assistant/ClaudeEditAssistant.kt:171-198`.
- What: only SDK exception types are caught. Another `RuntimeException` from `auth.create` (for example from
  the key store) skips both `settle` and `release`. The amount stays reserved for the life of the process and
  is charged in full at the next start. This fails closed (over-counts), so it is not a cap bypass.
- Fix: add a final `catch (e: Exception)` that settles with `null` usage and rethrows.

### 10. low: one failed Keystore decrypt removes every paired client

- Where: `APP/mcp/KeystoreClientStore.kt:103-104`.
- What: any exception from `cipher.decrypt` calls `removeAll()`. A transient Keystore error during one auth
  attempt forces all clients to pair again. The API-key store separates transient from permanent failures;
  this store does not.
- Fix: reuse `classifyKeyFailure` and wipe only on a permanent failure.

### 11. low: the pairing mutex is held during the wrong-code delay

- Where: `MCP/bridge/Pairing.kt:238` (`delay` inside `mutex.withLock`); `cancelPairing` at `:187` mutates
  `offer` and `pending` without the mutex.
- What: a wrong code blocks `beginPairing`, `revoke` and `revokeAll` for one second, and cancel can race a
  redeem. Reaching this needs shell-UID access to the socket.
- Fix: move the delay outside the lock (the `redeeming` flag already holds the slot) and take the mutex in
  `cancelPairing`.

### 12. low: the secret scan does not know the bridge token format

- Where: `scripts/secret-scan.sh:31-37` has `sk-ant-`, AWS and private-key patterns but not `vedb1_`.
- Scenario: a contributor commits a `credentials.json` from `vedit-mcp pair`; the hook passes it.
- Fix: add the pattern `vedb1_[A-Za-z0-9_-]{40,}`.

### 13. low, PLAUSIBLE: an unlisted `model` in the response charges the worst case every round

- Where: `AI/assistant/ClaudeEditAssistant.kt:204-205`, `AI/spend/PriceTable.kt`.
- What: settlement prices by the response's `model` string. If the API returns a dated id, or a fallback
  serves `claude-opus-5` or `claude-fable-5-1` (both in `FALLBACK_MODELS`, neither in the price table), the
  full reservation is charged. That over-counts, so a $1 session ends after about five rounds. If the
  fallback model is dearer than the requested one, the charge under-counts instead.
- Not verified: I could not check what the live API returns; the mock-server tests use table ids.
- Fix: add the two fallback ids to `PriceTable.DEFAULT`, and reserve at the highest price among the requested
  model and its possible fallbacks.

### 14. low: doc gaps (no false claims found)

- `HANDOFF.md` "Push status" still contains the placeholders `<<LAST_COMMIT>>` and `<<PUSH_STATUS>>`.
- The consent text (`APP/ai/AiEditController.kt:505`) says the transcript is sent, while transcripts are a
  stub. This over-discloses, so it is safe, but it will read as wrong to a user.
- README and HANDOFF otherwise match the code: device-untested and stubbed items are labelled as such.

## License

`LICENSE_AUDIT.md` covers every entry in `gradle/libs.versions.toml` and no module build file declares a
dependency outside the catalog. No GPL, LGPL or AGPL artifact and no ffmpeg. JUnit is EPL and test-only.
The audit's "verify" items (Jackson datatype modules, slf4j, guava, victools, ktlint-gradle) are permissive
under every reading. I did not re-resolve the Gradle dependency graph for this review.

## Checked and found sound

- Key handling: AES-256-GCM under an AndroidKeyStore key, StrongBox when present; `SecretString` redacts
  `toString`; the key is read in one place (`AnthropicAuth`) and sent only as `x-api-key`; SDK logging and
  retries are off; the key field uses `remember`, not `rememberSaveable`; FLAG_SECURE on the settings screen;
  `allowBackup="false"` and all three prefs files excluded from backup and device transfer. No `Log.*` call
  exists in the app, AI or MCP main sources.
- No subscription path: `sk-ant-oat` and admin keys are rejected by `ApiKeyFormat`; there is no OAuth code.
- Data sent to Anthropic: project JSON with asset URIs and display names blanked, transcript, silence and
  scene timestamps, and at most 8 JPEG keyframes of 512 px or less, only when the user turns frames on.
- Spend guard: reservation under a mutex and persisted before approval, unknown model refused, pending
  reservations charged in full after a crash, cancellation charged in full, ledger excluded from backup.
- Bridge: abstract Unix socket with peer-UID check (shell or own UID), no IP listener, off at every cold
  start, 8-digit code with 5 attempts and lockout, on-phone Allow, 256-bit token stored as an encrypted
  verifier, HMAC challenge-response with server proof, per-client and global throttling, scope checks,
  deny-by-default command allowlist (no `import_media`, no `batch`), asset URIs redacted, export gated by an
  on-phone prompt, revision check on apply.
- `vedit-mcp`: loopback-only TCP endpoint, credentials file written 0600 and refused if wider, offline
  `PathGuard` resolves symlinks and confines the project file to its directory; tools take no paths.

## Verdict

Safe to open the PR into `main` as a scaffold. Nothing here leaks the key or a token, bypasses pairing, or
sends raw media, and the docs do not overstate what works.

Fix before opening the PR:

- Finding 14, first bullet: fill in the two placeholders in `HANDOFF.md`, since it is the PR body.
- `main` does not exist on the remote yet; it has to be created before a PR can be opened (owner's call).

Fix before any release or real-key use, not required for the scaffold PR:

- Finding 1 (timeouts not charged) and finding 2 (no pre-send cost estimate): both are gaps against the
  owner's spend-control constraint.
- Finding 3 (bridge exception crashes the app), together with the on-device test pass already listed in
  HANDOFF.

The remaining findings can be tracked as follow-up issues.
