# Model log

| # | Task | Model | Why | Outcome |
|---|---|---|---|---|
| 1 | Env survey, alias check, repo init, agent defs, secret scan | Opus 5.5 (orchestrator) | Orchestration and security-sensitive setup | done; scanner tested against fake key and local.properties |
| 2 | Security design: API-key storage, spend cap, MCP auth/transport (docs/design/security.md) | Fable 5.2 (architect) | Brief required hard security design | done; MCP spec 2026-07-28 verified, Kotlin MCP SDK Apache-2.0 (MIT legacy) |
| 3 | Timeline model + command API + undo design (docs/design/timeline.md) | Fable 5.2 (architect) | Brief: hard data-model/undo design | done; 24 properties in test plan |
| 4 | Phase 1 scaffold: Gradle 9.9.1, AGP 9.5.1, Kotlin 2.4.21, 8 modules, ktlint, CI, LICENSE | Sonnet 5.5 (worker) | Boilerplate/config | done; assembleDebug test ktlintCheck pass (re-verified by orchestrator) |
| 5 | LICENSE_AUDIT.md (43 artifacts, 22 transitive POMs, 309 coords scanned) | Haiku 5.5 (scout) | Reading license files | done; no GPL/AGPL/LGPL; verify items: MCP kotlin-sdk MIT→Apache transition, POMs without license blocks |
| 6 | Timeline engine + key store/spend guard (parallel) | Opus 5.5 (builder x2) | Main implementation | interrupted at ~22:0x by account 5-hour limit (HTTP 429, resets 22:10 IST); partial work kept on disk; resumed same agents after reset |
| 7 | Stop + relaunch timeline and key-store builders at effort medium, continuing from on-disk partial work | Opus 5.5 (builder, medium) | Owner asked to cut Opus token use for current tasks too | running |
| 8 | Key-store builder stopped again, queued | — | Owner: run tasks one at a time | queued after timeline |
| 9 | Finish :core:timeline (session/undo, snap, JSON, schemas, dispatcher, 65 tests) | Opus 5.5 (builder, medium) | Main implementation | done; 65 tests pass (43 example, 22 property), re-verified by orchestrator |
| 10 | Finish key store + spend guard (:core:ai SecretString/ApiKeyFormat/LedgerSpendGuard; :app AndroidKeystoreApiKeyStore, ledger store, backup exclusion) | Opus 5.5 (builder, medium) | Security-critical implementation | done; 75 core:ai + 18 app tests pass (re-verified). Keystore cipher device-untested |
| 11 | Property tests P15/P21 + trim-start rounding fix | Sonnet 5.5 (worker) | Repetitive tests + small fix | done first try; 67 tests; orchestrator confirmed the regression test fails on old code |
| 12 | Phase 3: :core:media probe/thumbnails, :core:render RenderPlan + Media3 CompositionMapper, PreviewController (CompositionPlayer), Media3ExportService | Opus 5.5 (builder, medium) | Media3 integration | done; 34 JVM tests, full build green (re-verified). All Media3 runtime code device-untested |
| 13 | Phase 4: adaptive UI shell (phone tabs + sheet, tablet 3-pane + splitters, shortcuts, right-click), stateless Timeline with gestures, EditorViewModel, import, autosave, sample, export dialog | Opus 5.5 (builder, medium) | Compose UI implementation | done; 41 new tests, full build green (re-verified). Device-untested |
| 14 | JVM screenshots (Roborazzi 1.79 + Robolectric 4.18, sdk 35), 48dp splitter hit area | Sonnet 5.5 (worker) | Test tooling + polish | done; 6 PNGs; orchestrator inspected 3 extra shots, real UI |
