# Driving the editor from Claude Code (MCP)

`vedit-mcp` is a small [MCP](https://modelcontextprotocol.io) server that lets Claude Code read and edit a
project in the app. It runs on your computer as a stdio child process of Claude Code and talks to the phone
over USB through `adb`. The app never listens on Wi-Fi or any network interface. Design and threat model:
[docs/design/security.md](design/security.md), Part B.

Tools: `get_project`, `list_clips`, `split`, `trim`, `move`, `delete`, `add_caption`, `apply_cuts`, `export`,
`get_transcript`. Their input schemas are generated from the editor's command model, and every time is in
microseconds (1 s = 1000000). Read-only resources: `vedit://project/<id>` (project JSON),
`vedit://project/<id>/clips` and `vedit://project/<id>/transcript`.

## Build

Requires JDK 17 or newer, either on `PATH` or set in `JAVA_HOME`. The launcher needs it at run time too.

```sh
./gradlew :core:mcp:installDist
# launcher: core/mcp/build/install/vedit-mcp/bin/vedit-mcp
```

Copy or symlink `core/mcp/build/install/vedit-mcp` anywhere. The rest of this page writes `<path>` for the
directory holding `bin/vedit-mcp`.

## Connect to the phone (Claude Code on the same computer)

1. Plug the phone in with USB debugging on (or use Android 11+ wireless debugging) and open the app.
2. In the app: menu ⋮ → **Claude Code (MCP)** → switch on **Allow MCP control**. This setting is off after
   every restart, and the bridge only runs while the editor is on screen.
3. Forward the bridge socket to your computer's loopback:

   ```sh
   adb forward tcp:47821 localabstract:dev.videoeditor.app.bridge
   ```

   `adb forward` binds 127.0.0.1 only. Do not use `adb -a`.
4. Pair once. In the app, tap **Show pairing code**. On the computer, run:

   ```sh
   <path>/bin/vedit-mcp pair
   ```

   Type the 8-digit code. The phone then asks **Allow 'claude-code@yourhost' to read and edit your
   projects?** with scope checkboxes (Read and Edit by default; Export is opt-in). Tap **Allow** within 60 s.
   The code is valid for 2 minutes and works once. 5 wrong codes burn it, and 3 burned codes lock pairing for
   10 minutes.

   The credentials are written to `~/.config/vedit-mcp/credentials.json` with mode 0600 (directory 0700). If
   `XDG_CONFIG_HOME` is set, the file goes under it instead, and `VEDIT_MCP_CREDENTIALS=/some/file` overrides
   the path. vedit-mcp refuses a credentials file with wider permissions. The token never goes on a command
   line and is never logged. The phone keeps only a Keystore-encrypted one-way verifier, not the token.
5. Register the server with Claude Code:

   ```sh
   claude mcp add vedit -- <path>/bin/vedit-mcp stdio
   ```

   (`--bridge tcp:PORT` selects another forwarded port.)

Each session authenticates with an HMAC challenge-response, so the token never crosses the wire after
pairing. vedit-mcp also checks that the phone knows the token (mutual proof), so a fake endpoint cannot
pose as your phone.

While Claude is connected, the editor shows **Claude connected: <name>** under the top bar.
**Stop** is the kill switch: it disconnects every client and switches MCP control off. Each edit Claude
makes is a single undoable step labelled "Claude (MCP): …". While an in-app AI suggestion is pending, the
timeline is read-only, and Claude's edits are refused with an error that says why. **Exports always ask on
the phone first.** The `export` tool returns "waiting for confirmation", and Claude can check again with
`jobId`. Revoke clients one at a time or all at once under **Paired clients**. A client that has not been
used for 90 days is revoked automatically.

## Claude Code on a remote VM (`ssh -R`)

vedit-mcp runs on the VM as Claude Code's child process. Your laptop, which has the phone attached,
relays the bridge:

```sh
laptop$ adb forward tcp:47821 localabstract:dev.videoeditor.app.bridge
laptop$ ssh -N -R /run/user/1000/vedit-bridge.sock:127.0.0.1:47821 user@vm   # preferred: user-private socket
#   or: ssh -N -R 127.0.0.1:47821:127.0.0.1:47821 user@vm                    # TCP fallback (keep GatewayPorts no)
vm$ claude mcp add vedit -- <path>/bin/vedit-mcp stdio --bridge unix:/run/user/1000/vedit-bridge.sock
```

Pair from the VM with `vedit-mcp pair --bridge unix:/run/user/1000/vedit-bridge.sock`, or pair on the laptop
and copy the credentials file to the VM with mode 0600. The Unix-socket form needs `StreamLocalBindUnlink yes`
in the VM's sshd config so that it survives reconnects.

## Offline mode: edit a project file, no phone

```sh
<path>/bin/vedit-mcp sample-project /tmp/demo.json        # optional: write the built-in sample
claude mcp add vedit-offline -- <path>/bin/vedit-mcp stdio --project /tmp/demo.json
```

Offline mode edits the JSON file directly through the same command code the app uses. There is no listener
in this mode, so it needs no token. Writes are atomic: the server writes a temp file, fsyncs it and renames it
over the original, under a `<file>.lock` lock file. `--workspace DIR --project name.json` restricts the
server to files directly inside `DIR`, and symlinks that point outside are refused. `export` returns an
`UNSUPPORTED` error, because rendering needs the phone. `get_transcript` works only if a sidecar
`<file>.transcripts.json` (a JSON array of per-asset transcripts) exists.

Quick check without Claude:

```sh
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_clips","arguments":{}}}' \
  | <path>/bin/vedit-mcp stdio --project /tmp/demo.json
```

## Protocol versions

vedit-mcp is a small hand-written JSON-RPC 2.0 server over newline-delimited stdio. It supports:

- the `initialize` handshake for 2024-11-05, 2025-03-26, 2025-06-18 and 2025-11-25;
- the stateless 2026-07-28 style, where `server/discover` takes the place of the handshake and a version
  given in a request's `_meta` is checked.

stdout carries only protocol messages. Diagnostics go to stderr.

## Not supported in v1: Claude app custom connectors

Custom connectors in the Claude apps (claude.ai, desktop, mobile) are not supported. Connector traffic
comes from Anthropic's servers, not from your device, so a connector needs a **publicly reachable HTTPS URL**,
and it authenticates with **OAuth** or not at all. A phone behind adb has no public URL. An authless public
endpoint would let anyone on the internet control your editor, and a proper OAuth 2.1 resource server plus
authorization server is a project of its own. Use Claude Code with the stdio server described above. The app
also never signs in to a Claude.ai account or subscription. In-app AI uses your own Anthropic API key only.
