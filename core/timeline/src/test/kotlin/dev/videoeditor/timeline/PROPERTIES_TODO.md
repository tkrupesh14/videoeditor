# Planned but unimplemented property tests (`:core:timeline`)

Numbers refer to the P-table in `docs/design/timeline.md` section 8.2. Everything else in that table is
implemented in `PropertiesTest.kt` (P1-P14, P16-P20, P22-P24, plus a generator meta-test).

- **P15** `EditLimits.canPlace` is true exactly when the corresponding `AddClip` / `MoveClip` does not fail
  with `Overlap`. Needs a generator that derives an `AddClip` (asset, source range, speed) whose duration
  equals the probed duration.
- **P21** Schema conformance: every generated command, encoded, validates against its generated JSON Schema
  (subset validator: `type`, `properties`, `required`, `enum`, `items`, `minimum`, `additionalProperties`),
  and `required` equals the set of non-default properties. Needs a small test-only schema validator.
- **P16 (full form)** "undoing every entry returns the initial project" is checked; the explicit "history limit
  was not hit" branch with a small `historyLimit` and long random sequences is only covered by an example test.
- **P13 caveat** a START trim and trim back on a clip whose `sourceInUs == 0` can fail with
  `SourceOutOfRange` (floor rounding gives -1 us). The property accepts that case; decide whether the design
  should clamp, or whether `EditLimits.trimRange` should exclude such targets.
- **Arb.command biasing** commands are not yet biased toward `MAX_TIMELINE_US`-adjacent projects (no generated
  project comes close to 24 h), so the "exceeds maximum timeline length" paths are only reached by example tests.
