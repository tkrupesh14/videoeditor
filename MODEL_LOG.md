# Model log

| # | Task | Model | Why | Outcome |
|---|---|---|---|---|
| 1 | Env survey, alias check, repo init, agent defs, secret scan | Opus 5.5 (orchestrator) | Orchestration and security-sensitive setup | done; scanner tested against fake key and local.properties |
| 2 | Security design: API-key storage, spend cap, MCP auth/transport (docs/design/security.md) | Fable 5.2 (architect) | Brief required hard security design | done; MCP spec 2026-07-28 verified, Kotlin MCP SDK Apache-2.0 (MIT legacy) |
| 3 | Timeline model + command API + undo design (docs/design/timeline.md) | Fable 5.2 (architect) | Brief: hard data-model/undo design | done; 24 properties in test plan |
| 4 | Phase 1 scaffold: Gradle 9.9.1, AGP 9.5.1, Kotlin 2.4.21, 8 modules, ktlint, CI, LICENSE | Sonnet 5.5 (worker) | Boilerplate/config | done; assembleDebug test ktlintCheck pass (re-verified by orchestrator) |
