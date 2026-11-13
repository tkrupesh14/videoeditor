# Planned but unimplemented property tests (`:core:timeline`)

Numbers refer to the P-table in `docs/design/timeline.md` section 8.2. Everything else in that table is
implemented in `PropertiesTest.kt` (P1-P24 except the full form of P16, plus a generator meta-test).

- **P16 (full form)** "undoing every entry returns the initial project" is checked; the explicit "history limit
  was not hit" branch with a small `historyLimit` and long random sequences is only covered by an example test.
- **Arb.command biasing** commands are not yet biased toward `MAX_TIMELINE_US`-adjacent projects (no generated
  project comes close to 24 h), so the "exceeds maximum timeline length" paths are only reached by example tests.
