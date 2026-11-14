# Blockers

- 2026-11-13 ~22:05 IST: **Account 5-hour usage limit hit** (HTTP 429, "resets 10:10pm Asia/Kolkata") while two Opus builders ran. Not a Fable cap, so no model fallback applies; it is account-wide. Resumed both agents from their saved state after the reset. If it recurs, phases stop committing mid-task and resume at the next reset; PROGRESS.md marks where.
- 2026-11-13 ~23:00 IST: **Push of 9769b67 hung twice** (no output; SSH auth and the secret scan were both fine). The third attempt, with SSH keepalive, succeeded. Repo-local `core.sshCommand` now sets ConnectTimeout=15 and ServerAliveInterval=5, so a stalled connection fails fast. Resolved.

## 2026-11-14 push of 8b37b2f: SSH banner timeout (resolved)
- Two pushes timed out connecting to github.com:22. Commit kept locally; a retry minutes later succeeded. No credentials or remotes changed.

## 2026-11-14 push of 9ae2f3d (Phase 7): SSH banner timeout (open)
- github.com:22 timing out again; commit kept locally (branch ahead 1). Retry after the final review. Launching the Fable review also failed twice on a transient permission-check timeout; retry when the network settles.
