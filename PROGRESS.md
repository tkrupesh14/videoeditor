# Progress

Resume cold from here. Phase plan is in HANDOFF.md / the original brief (phases 1-8).

| Phase | Status | Model(s) | Notes |
|---|---|---|---|
| 0. Setup (agents, scan, tracking) | done | Opus 5.5 (orchestrator) | |
| 1. Repo + CI | done | Sonnet (worker) | AGP 9.5.1, Gradle 9.9.1, Kotlin 2.4.21, compileSdk 37 |
| 2. Timeline engine | done | Fable design, Opus (medium) build | 65 tests; entry points in DECISIONS/timeline.md |
| 3. Preview + export (Media3) | next | | |
| 4. Responsive UI shell | | | |
| 5. AI feature A (BYO key) | in progress (5a: key store + spend guard) | Fable design done; Opus building | docs/design/security.md Part A |
| 6. AI feature B (MCP) | | | |
| 7. Polish + docs | | | |
| 8. Final review | | | |

## Build
`export JAVA_HOME=$HOME/.local/jdk/jdk-17.0.21+9 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew build`

## Running now (2026-11-13, resumed 22:10 IST after account rate limit)
- (done) Haiku: LICENSE_AUDIT.md — clean, no copyleft
## Queue (strictly one task at a time)
1. Opus (medium): finish :core:ai key/spend (written, fixing compile) + :app Keystore store (not started)
2. Sonnet: remaining property tests (PROPERTIES_TODO.md) + fix trim-start rounding gap (see DECISIONS)
3. Opus (medium): Phase 3 Media3 preview/export
4. Opus (medium): Phase 4 UI shell, then Sonnet polish
5. Opus (medium): :core:ai Messages API client + preview diff UI
6. Opus (medium): :core:mcp + app bridge
7. Sonnet: docs/README/HANDOFF
8. Fable: final review (one pass)
- Cleanup: drop unused security-crypto, ui-tooling-preview, ktor-client-okhttp from catalog once builders finish (avoid concurrent edits)
