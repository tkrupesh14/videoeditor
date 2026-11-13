# Blockers

- 2026-11-13 ~22:05 IST: **Account 5-hour usage limit hit** (HTTP 429, "resets 10:10pm Asia/Kolkata") while two Opus builders ran. Not a Fable cap, so no model fallback applies; it is account-wide. Resumed both agents from their saved state after the reset. If it recurs, phases stop committing mid-task and resume at the next reset; PROGRESS.md marks where.
