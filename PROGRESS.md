# Progress

Resume cold from here. Phase plan is in HANDOFF.md / the original brief (phases 1-8).

| Phase | Status | Model(s) | Notes |
|---|---|---|---|
| 0. Setup (agents, scan, tracking) | done | Opus 5.5 (orchestrator) | |
| 1. Repo + CI | done | Sonnet (worker) | AGP 9.5.1, Gradle 9.9.1, Kotlin 2.4.21, compileSdk 37 |
| 2. Timeline engine | done | Fable design, Opus (medium) build | 65 tests; entry points in DECISIONS/timeline.md |
| 3. Preview + export (Media3) | done (device-untested) | Opus (medium) | gaps in DECISIONS |
| 4. Responsive UI shell | done (device-untested); polish+screenshots next | Opus (medium) | |
| 5. AI feature A (BYO key) | 5a done (key store + spend guard); 5b (Messages client, UI) queued | Fable design done; Opus building | docs/design/security.md Part A |
| 6. AI feature B (MCP) | | | |
| 7. Polish + docs | | | |
| 8. Final review | | | |

## Build
`export JAVA_HOME=$HOME/.local/jdk/jdk-17.0.21+9 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew build`

## Running now (2026-11-13, resumed 22:10 IST after account rate limit)
- (done) Haiku: LICENSE_AUDIT.md — clean, no copyleft
## Queue (strictly one task at a time)
1. (done) key store + spend guard
2. (done) property tests + trim rounding fix
3. (done) Phase 3 Media3 preview/export
4. (done) Phase 4 UI shell. NEXT: Sonnet: Roborazzi+Robolectric JVM screenshot tests at phone (360dp) and tablet sizes, polish (48dp splitter touch area)
5. Opus (medium): :core:ai Messages API client + preview diff UI
6. Opus (medium): :core:mcp + app bridge
7. Sonnet: docs/README/HANDOFF
8. Fable: final review (one pass)
- Cleanup: drop unused security-crypto, ui-tooling-preview, ktor-client-okhttp from catalog once builders finish (avoid concurrent edits)
