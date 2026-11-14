# Progress

Resume cold from here. Phase plan is in HANDOFF.md / the original brief (phases 1-8).

| Phase | Status | Model(s) | Notes |
|---|---|---|---|
| 0. Setup (agents, scan, tracking) | done | Opus 5.5 (orchestrator) | |
| 1. Repo + CI | done | Sonnet (worker) | AGP 9.5.1, Gradle 9.9.1, Kotlin 2.4.21, compileSdk 37 |
| 2. Timeline engine | done | Fable design, Opus (medium) build | 65 tests; entry points in DECISIONS/timeline.md |
| 3. Preview + export (Media3) | done (device-untested) | Opus (medium) | gaps in DECISIONS |
| 4. Responsive UI shell | done (device-untested); polish+screenshots next | Opus (medium) | |
| 5. AI feature A (BYO key) | 5a done; done: 5b-1 Claude client + 5b-2 Settings/AI panel/accept-reject/analysis (device-untested) | Fable design done; Opus building | docs/design/security.md Part A |
| 6. AI feature B (MCP) | done (phone side device-untested); offline mode verified with real Claude Code | Opus (medium) | docs/mcp.md |
| 7. Polish + docs | done (README, HANDOFF) | Sonnet (worker) | |
| 8. Final review | | | |

## Build
`export JAVA_HOME=$HOME/.local/jdk/jdk-17.0.21+9 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew build`

## Running now (2026-11-13, resumed 22:10 IST after account rate limit)
- (done) Haiku: LICENSE_AUDIT.md — clean, no copyleft
## Queue (strictly one task at a time)
1. (done) key store + spend guard
2. (done) property tests + trim rounding fix
3. (done) Phase 3 Media3 preview/export
4. (done) Phase 4 UI shell + screenshots + splitter polish
5. (done) Opus (medium): 5b-1 :core:ai Claude client (anthropic-java 3.8.0, mock-server tests)
5b. (done) Opus (medium): 5b-2 AI Settings + AI panel + accept/reject + analysis + Remove silences
6. (done) Opus (medium): :core:mcp + app bridge
6b. (done) Haiku: refresh LICENSE_AUDIT.md (anthropic-java 3.8.0, okhttp 4.12, mockwebserver added; MCP SDK not used; ktor maybe unused) + drop unused catalog entries
7. (done) Sonnet: docs/README/HANDOFF
8. (next) Fable: final review (one pass)
- Cleanup: drop unused security-crypto, ui-tooling-preview, ktor-client-okhttp from catalog once builders finish (avoid concurrent edits)
