# Timeline engine design (`:core:timeline`)

Status: design, v1. Audience: the builder implementing `:core:timeline`, and the authors of the UI, the in-app AI layer and the MCP server, who all drive the editor through the command API defined here.

Package root: `dev.videoeditor.timeline`. Pure Kotlin/JVM. Dependencies: Kotlin stdlib and `kotlinx-serialization-json` (Apache-2.0). Tests: `kotest-runner-junit5`, `kotest-assertions-core`, `kotest-property` (Apache-2.0). No Android, no coroutines library (`suspend` is a language feature and is used only in the host interfaces).

## 0. Decisions at a glance

| # | Decision | Why |
|---|---|---|
| D1 | Time is `Long` microseconds, ranges are half-open `[start, end)` | Integer math, no drift; 24 h = 8.64e10 fits in a JS double, so MCP clients are safe |
| D2 | A clip stores timeline `startUs` + `durationUs` and source `sourceInUs` + `speed`. Source out is derived | The timeline is the exact domain, so split, trim and ripple never create 1 us gaps or overlaps |
| D3 | Every timeline-to-source conversion floors | Source ranges can shrink by up to 1 us but never grow, so "source inside asset" is closed under every command |
| D4 | IDs come from a counter stored in the project (`nextId`) | `execute(project, command)` is a pure function; previews, tests and re-execution give identical IDs |
| D5 | Commands are strict: overlap is rejected, never auto-pushed. Shifting other clips happens only with an explicit `ripple` flag or a range-cut command | Predictable for an AI and for MCP clients; no silent audio/caption desync |
| D6 | Per-clip `ripple` flags act on the same track only. All-track ripple is `RippleDeleteRange` / `ApplyCuts` | One well-defined meaning each; all-track ripple of a single clip is a range cut |
| D7 | Undo/redo is snapshot based (before/after immutable `Project`) | Correct by construction; structural sharing keeps it cheap; inverse commands for ripple and cuts are bug-prone |
| D8 | Drags use a session transaction with replace semantics. AI suggestions use a detached `EditPreview` | Both produce exactly one undo step; a pending AI suggestion does not lock or alter the live project |
| D9 | JSON Schemas are generated from serial descriptors and pinned by golden files | Cannot drift from the Kotlin classes; any change to the tool surface is a visible diff |
| D10 | One `ToolDispatcher` maps tool names to commands, queries and host calls | The in-app AI and the MCP server share one code path |

Deviation from the brief: the brief asked for an injected ID generator. D4 replaces it with a counter inside the project. This is stricter: there is no generator state outside the project to keep in sync, and undo restores the counter along with everything else.

## 1. Data model

### 1.1 Time

- Unit: microseconds, `Long`, field names end in `Us`.
- Ranges are half-open: a clip occupies `[startUs, endUs)` where `endUs = startUs + durationUs`. Two clips with `a.endUs == b.startUs` touch and do not overlap.
- Times are not quantized to frames. The UI snaps to a frame grid if it wants (`TimeMath.floorToFrame`). Renderers sample at frame times.

Constants (`Limits`):

| Constant | Value | Meaning |
|---|---|---|
| `MIN_CLIP_DURATION_US` | 10_000 | Shortest clip a command may create (10 ms) |
| `MAX_TIMELINE_US` | 86_400_000_000 | Latest allowed clip end, and longest asset (24 h) |
| `MIN_SPEED` / `MAX_SPEED` | 0.10 / 10.00 | |
| `MAX_VOLUME` | 2.0 | Minimum is 0.0 |
| `MIN_SCALE` / `MAX_SCALE` | 0.01 / 10.0 | |
| `MAX_TRANSLATE` | 2.0 | Absolute value, fraction of the frame |
| `MAX_CAPTION_CHARS` | 2000 | |
| `MIN_OUTPUT_DIM` / `MAX_OUTPUT_DIM` | 16 / 8192 | Width and height must also be even |
| `MIN_FPS` / `MAX_FPS` | 1 / 120 | |

### 1.2 Speed and the source mapping

Speed is a `Double` that is always an exact multiple of 0.01. Commands normalize incoming speed with `Math.round(speed * 100) / 100.0` and then range-check it. All arithmetic uses the integer `speedCenti = Math.round(speed * 100).toInt()` (10..1000), never floating point.

Definitions for a media clip `c` with `k = c.speedCenti`:

```
sourceDurationUs(c) = c.durationUs * k / 100                 (integer division, floor)
sourceOutUs(c)      = c.sourceInUs + sourceDurationUs(c)
sourceTimeAt(c, t)  = c.sourceInUs + (t - c.startUs) * k / 100    for startUs <= t < endUs
```

Rules, all in one direction:

1. Timeline to source: `floor(timelineUs * k / 100)`.
2. Source to timeline: `floor(sourceUs * 100 / k)`.
3. Signed offsets (trimming the start edge outward) use `Math.floorDiv`, which rounds toward negative infinity.

Consequences the builder can rely on (each is a property test in section 8):

- Split at `t`: `floor(a) + floor(b) <= floor(a + b)`, so the right piece's derived source out is never past the original's.
- Speed change: `newDuration = floor(sourceDuration * 100 / k')`, and `floor(newDuration * k' / 100) <= sourceDuration`.
- Start-edge trim by signed `delta`: `sourceIn' = sourceIn + floorDiv(delta * k, 100)`; the derived source out never exceeds the old one.
- So no command can push a source range outside its asset unless it explicitly extends the clip, and that case is checked.

Accepted cost: an edit can lose up to 1 us of source per operation (about 1/20 of a 48 kHz sample). Speed 1.0 is exact. Overflow is impossible: `MAX_TIMELINE_US * 1000 < 2^63`.

### 1.3 Entities

```
Project
  id, name, schemaVersion, nextId
  output: OutputSettings (width, height, frameRate)
  assets: List<MediaAsset>          media table
  tracks: List<Track>               index 0 is the bottom layer, last is on top
    Track: id, kind, name, clips    clips sorted by startUs
      Clip = MediaClip | CaptionClip
```

- `TrackKind`: `VIDEO`, `AUDIO`, `OVERLAY`, `CAPTION`.
- `MediaClip` lives on `VIDEO`, `OVERLAY` (asset must have video) or `AUDIO` (asset must have audio) tracks. A media clip on a `VIDEO` or `OVERLAY` track plays the asset's audio too, scaled by `volume`. On an `AUDIO` track only the audio is used and `transform` is ignored.
- `CaptionClip` lives only on `CAPTION` tracks and has no asset.
- `Clip` is a sealed interface, not one class with nullable fields. Timing commands (split, trim, move, delete, cuts) work on the interface; property commands match on the subtype.
- Project duration is a computed property, `max(clip.endUs)` or 0. It is not stored, so it cannot be wrong.
- Gaps are allowed on every track. No track is magnetic.
- An empty project (no tracks) is valid.

### 1.4 Transform

One transform type for media and caption clips.

| Field | Meaning | Default | Range |
|---|---|---|---|
| `translateX`, `translateY` | Offset of the clip centre from the frame centre, as a fraction of output width / height. +x right, +y down | 0.0 | [-2, 2] |
| `scale` | 1.0 = aspect-fit inside the output frame (media), natural size (captions) | 1.0 | [0.01, 10] |
| `rotationDeg` | Clockwise degrees, normalized to [0, 360) | 0.0 | finite |
| `opacity` | | 1.0 | [0, 1] |

`CaptionClip.transform` defaults to `translateY = 0.35` (lower third).

### 1.5 IDs

- Format: `asset_N`, `track_N`, `clip_N`. One shared counter, `Project.nextId`, starting at 1. Allocating an ID returns `"<prefix>_<nextId>"` and increments the counter.
- When one command allocates several IDs, the order is fixed and documented per command (section 3), so results are reproducible.
- `Project.id` is supplied by the app when it creates the project (a UUID is fine). It is opaque to this module.
- Undo restores `nextId`, so an ID can be reused after an undo. Consumers holding clip IDs (UI selection, a pending AI preview) must treat them as valid only for the session revision they were read at. `EditorSession.revision` exists for this (section 5).

## 2. Invariants

`ProjectValidator.validate(project)` returns every violation. A valid project has none. The executor runs the validator on every result; a violation there is a bug and is returned as `CommandError.Internal`, never thrown and never committed.

| Code | Invariant | On command input | On load |
|---|---|---|---|
| `ID_UNIQUE` | All asset, track and clip IDs are non-blank and unique across the project | n/a (IDs are allocated) | hard |
| `ID_COUNTER` | `nextId` is greater than the numeric suffix of every `prefix_N` ID, and `>= 1` | n/a | normalized: bumped |
| `CLIP_ORDER` | Clips in a track are sorted by `startUs` | maintained | normalized: sorted |
| `NO_OVERLAP` | For neighbours in a track, `a.endUs <= b.startUs` | hard | hard |
| `CLIP_TIME` | `startUs >= 0`, `durationUs >= MIN_CLIP_DURATION_US`, `endUs <= MAX_TIMELINE_US` | hard | hard |
| `CLIP_KIND` | `CAPTION` track holds only `CaptionClip`; other tracks only `MediaClip`; asset has video for `VIDEO`/`OVERLAY`, audio for `AUDIO` | hard | hard |
| `ASSET_REF` | `MediaClip.assetId` exists in `project.assets` | hard | hard |
| `SOURCE_RANGE` | `sourceInUs >= 0` and `sourceInUs + sourceDurationUs <= asset.durationUs` | hard | hard |
| `SPEED` | `speed == speedCenti / 100.0` and `10 <= speedCenti <= 1000` | normalized to 0.01, then hard range | hard |
| `VOLUME` | finite, `0 <= volume <= 2` | hard | hard |
| `TRANSFORM` | all fields finite and in range; `0 <= rotationDeg < 360` | rotation normalized mod 360, rest hard | hard |
| `CAPTION` | text non-blank after trim, at most 2000 chars; style fields in range | text trimmed, then hard | hard |
| `ASSET` | `0 < durationUs <= MAX_TIMELINE_US`; `hasVideo \|\| hasAudio`; `hasVideo` implies `width > 0 && height > 0`; `frameRate`, if present, is finite and > 0 | hard | hard |
| `OUTPUT` | width and height even and in [16, 8192]; frame rate in [1, 120] | hard | hard |
| `DURATION` | `project.durationUs == max clip end` | by construction | by construction |

"Hard" means the command is rejected with a `CommandError` (or the load fails with `ProjectLoadError.Invalid`). "Normalized" means the value is silently corrected. The full list of input normalizations:

- speed rounded to 0.01
- `rotationDeg` reduced to [0, 360)
- caption text trimmed of leading and trailing whitespace
- `clipIds` lists de-duplicated, keeping first occurrence
- cut ranges sorted, merged and clamped (section 3.12)

Nothing else is normalized. In particular, positions and durations are never clamped by a command. The UI clamps before issuing a command using the pure helpers in section 3.15.

## 3. Commands

### 3.1 Common rules

- `Command` is a sealed interface, one `@Serializable` data class per operation. JSON discriminator `"type"`, snake_case names. Property names are camelCase in Kotlin and JSON.
- Execution is all or nothing. A failed command leaves no trace.
- Validation order is fixed so errors are deterministic: (1) argument shape and ranges, (2) entity lookup in field order, then list order, (3) kind compatibility, (4) time and source bounds, (5) overlap. The first failure is returned.
- An unknown ID anywhere in a list rejects the whole command (`NotFound`). Lists are never partially applied.
- An empty `clipIds` list is `InvalidArgument`.
- A command that validates but changes nothing returns `Success(changed = false)`. The session records no undo entry for it.
- "Later clips" in ripple rules always means clips on the same track whose `startUs >=` the stated point, evaluated on the pre-edit project.
- Any shift that would push a clip end past `MAX_TIMELINE_US` is `InvalidArgument("...", "exceeds maximum timeline length")`.
- `summary` is one deterministic English sentence. Times are formatted with `TimeMath.formatTimecode` as `m:ss.mmm` (`h:mm:ss.mmm` from one hour). It is meant for the undo menu, logs, the AI and MCP. A localized UI builds its own text from the command.

### 3.2 `ImportMedia` (`import_media`)

Registers an asset. The module never probes files; the app probes and issues this command.

Fields: `uri: String`, `durationUs: Long`, `hasVideo: Boolean`, `hasAudio: Boolean`, `width: Int? = null`, `height: Int? = null`, `frameRate: Double? = null`, `displayName: String? = null`.

- Validation: `uri` non-blank; invariant `ASSET`.
- If an asset with the same `uri` exists: `Success(changed = false)`, `createdIds = []`, summary names the existing ID. Metadata is not updated.
- Otherwise appends the asset with a new `asset_N`. `createdIds = [assetId]`.
- Errors: `InvalidArgument`.

### 3.3 `AddTrack` (`add_track`) and `RemoveTrack` (`remove_track`)

`AddTrack` fields: `kind: TrackKind`, `name: String? = null`, `index: Int? = null`.

- `index` null appends (top layer). Otherwise `0 <= index <= tracks.size`, else `InvalidArgument`.
- `name` null becomes `"<Kind> <n>"` where n is 1 + the count of existing tracks of that kind, e.g. `"Video 2"`.
- `createdIds = [trackId]`.

`RemoveTrack` fields: `trackId: String`, `deleteClips: Boolean = false`.

- Errors: `NotFound`; `TrackNotEmpty` if the track has clips and `deleteClips` is false.
- Assets are untouched.

### 3.4 `AddClip` (`add_clip`)

Fields: `trackId`, `assetId`, `startUs: Long? = null`, `sourceInUs: Long = 0`, `sourceOutUs: Long? = null`, `speed: Double = 1.0`, `volume: Double = 1.0`, `transform: Transform = Transform()`, `ripple: Boolean = false`.

- `startUs` null means append: the track's last clip end, or 0 for an empty track.
- `sourceOutUs` null means the asset's duration.
- Duration: `durationUs = (sourceOutUs - sourceInUs) * 100 / speedCenti`.
- Validation: `0 <= sourceInUs < sourceOutUs <= asset.durationUs` else `SourceOutOfRange`; `durationUs >= MIN_CLIP_DURATION_US` else `ClipTooShort`; `startUs >= 0`; track kind per `CLIP_KIND` (a `CAPTION` track is `TrackKindMismatch`; use `AddCaption`).
- `ripple = false`: the range `[startUs, startUs + durationUs)` must be free, else `Overlap`.
- `ripple = true` (insert): a clip that straddles `startUs` (`start < startUs < end`) is `Overlap`. Otherwise later clips (start `>= startUs`) shift right by `durationUs`.
- `createdIds = [clipId]`.

### 3.5 `SplitClip` (`split_clip`)

Fields: `clipIds: List<String>`, `atUs: Long`.

For each clip, in list order:

- Requires `clip.startUs + MIN_CLIP_DURATION_US <= atUs <= clip.endUs - MIN_CLIP_DURATION_US`, else `SplitOutOfRange(clipId, atUs, minUs, maxUs)`. If the clip is shorter than twice the minimum, `minUs > maxUs` and no split point is valid.
- Left piece keeps the ID: `durationUs = atUs - startUs`, everything else unchanged.
- Right piece gets a new ID: `startUs = atUs`, `durationUs = oldEnd - atUs`, `sourceInUs = old.sourceInUs + (atUs - old.startUs) * speedCenti / 100`. Speed, volume, transform, caption text and style are copied.
- `createdIds` holds the right-piece IDs in list order.

Split at a boundary (`atUs == startUs` or `endUs`) is rejected, not treated as a no-op. Reason: the command's contract is "one new clip per listed clip, returned in `createdIds`". Callers chain on that ID (split, then delete the right half). A silent no-op would break the chain; an error tells the caller a cut already exists there. For "split everything under the playhead" the UI lists only clips that strictly contain the playhead (`Queries.clipsAt`).

### 3.6 `TrimClip` (`trim_clip`)

Fields: `clipId: String`, `edge: ClipEdge` (`START` | `END`), `toUs: Long`, `ripple: Boolean = false`.

`toUs` is the timeline time, in pre-edit coordinates, that the edge moves to. Let `s`, `e` be the clip's old start and end, `k = speedCenti`.

| Case | New clip | Other clips on the track |
|---|---|---|
| `END`, no ripple | `durationUs = toUs - s` | unchanged; `Overlap` if `toUs` passes the next clip's start |
| `END`, ripple | `durationUs = toUs - s` | later clips (start `>= e`) shift by `toUs - e` |
| `START`, no ripple | `startUs = toUs`, `durationUs = e - toUs`, `sourceInUs += floorDiv((toUs - s) * k, 100)` | unchanged; `Overlap` if `toUs` is before the previous clip's end |
| `START`, ripple | `startUs = s` (unchanged), `durationUs = e - toUs`, `sourceInUs += floorDiv((toUs - s) * k, 100)` | later clips (start `>= e`) shift by `-(toUs - s)` |

Validation:

- New duration `>= MIN_CLIP_DURATION_US`, else `ClipTooShort`. This covers dragging an edge past the opposite edge.
- `toUs >= 0`.
- Media clips: new `sourceInUs >= 0` and new `sourceInUs + newDuration * k / 100 <= asset.durationUs`, else `SourceOutOfRange`. Caption clips have no source limit.
- `toUs` equal to the current edge: `Success(changed = false)`.

Trim below the minimum is rejected, not clamped. Reason: a command must do exactly what it says or fail. The UI calls `EditLimits.trimRange` and coerces the drag position into that range before issuing the command, so a drag sticks at the limit. An AI gets a precise error with the minimum.

### 3.7 `MoveClip` (`move_clip`)

Fields: `clipIds: List<String>`, `toStartUs: Long? = null`, `toTrackId: String? = null`. At least one of the two must be non-null.

- The listed clips move as a rigid group. `toStartUs` is the new start of the earliest clip in the group; `delta = toStartUs - min(startUs)` applies to all. Null means `delta = 0`.
- `toTrackId`: all listed clips must currently be on one track (else `InvalidArgument`) and all move to the target. Null keeps each clip on its own track.
- Algorithm: remove the group from its tracks, apply `delta` and the track change, then check each destination range against the clips that did not move. Clips in the group may therefore land where other group members used to be.
- Errors: any new `startUs < 0` is `InvalidArgument`; `TrackKindMismatch` if the target kind cannot hold a clip (`CLIP_KIND`); `Overlap` on any collision.
- Source range, speed and all properties are unchanged.

Move onto an occupied region is rejected, never pushed. Reasons: pushing changes clips the caller did not name; on one track it desyncs audio and captions from video; and an AI or MCP client cannot predict the result without simulating it. Insert-style moves are expressible as an explicit `Batch` (delete with ripple, then `AddClip` with ripple), where every shift is asked for. The UI uses `EditLimits.canPlace` and the snap helper to show whether a drop is legal while dragging.

### 3.8 `DeleteClips` (`delete_clips`)

Fields: `clipIds: List<String>`, `ripple: Boolean = false`.

- Removes the clips. Tracks and assets stay.
- `ripple = true`: on each track, every surviving clip moves left by the total duration of deleted clips on that track with `endUs <=` its `startUs`. Existing gaps are preserved; only the deleted clips' own space closes.
- Errors: `NotFound`.

### 3.9 `RippleDeleteRange` (`ripple_delete_range`)

Fields: `startUs: Long`, `endUs: Long`, `trackIds: List<String>? = null`.

- Removes `[startUs, endUs)` from the named tracks (null = all tracks) and closes the gap, using the range-cut algorithm in 3.12 with a single range.
- Validation: `0 <= startUs < endUs`; `trackIds`, if given, non-empty and all found.
- With a subset of tracks, the other tracks do not move. That is the caller's stated intent.

### 3.10 `SetClipProperties` (`set_clip_properties`)

Fields: `clipIds: List<String>`, `speed: Double? = null`, `volume: Double? = null`, `transform: TransformPatch? = null`, `ripple: Boolean = false`. Null means unchanged. At least one of the three must be non-null.

- `transform` is a patch: only its non-null fields are replaced. It applies to media and caption clips.
- `volume` and `speed` apply to media clips only. If a listed clip is a caption and either is non-null: `InvalidArgument`.
- Speed change keeps `startUs`, `sourceInUs` and the source duration: `newDuration = (oldDuration * kOld / 100) * 100 / kNew` (two floors, in that order). `newDuration < MIN_CLIP_DURATION_US` is `ClipTooShort`.
- `ripple = false`: after all listed clips are resized, any collision is `Overlap`. A shorter clip leaves a gap.
- `ripple = true`: clips are processed in start order per track; later clips (start `>=` the old end of the resized clip) shift by `newDuration - oldDuration`, cumulatively.
- `ripple` has no effect when `speed` is null.

Note: speed 1.0 to 2.0 and back can lose 1 us of duration (two floors). Undo restores exactly.

### 3.11 `AddCaption` (`add_caption`) and `EditCaption` (`edit_caption`)

`AddCaption` fields: `captions: List<CaptionSpec>`, `trackId: String? = null`, `style: CaptionStyle = CaptionStyle()`, `transform: Transform = Transform(translateY = 0.35)`. `CaptionSpec` is `startUs: Long`, `endUs: Long`, `text: String`.

- Takes a list because the main caller is the AI adding a whole transcript: one command, one undo step, one validation pass.
- `trackId` null: the first `CAPTION` track in project order. If there is none, a new `CAPTION` track named `"Captions 1"` is appended on top.
- Validation per spec: `0 <= startUs`, `endUs - startUs >= MIN_CLIP_DURATION_US` (else `ClipTooShort`), text valid per `CAPTION`. The specs must not overlap each other or existing clips on the track, else `Overlap`. `captions` must be non-empty.
- ID order: the new track first if one is created, then clips in list order. `createdIds` is in that order.

`EditCaption` fields: `clipIds: List<String>`, `text: String? = null`, `style: CaptionStylePatch? = null`. At least one non-null.

- `text` with more than one clip ID is `InvalidArgument`.
- `style` is a patch applied to every listed clip ("make all captions yellow").
- A listed clip that is not a caption: `InvalidArgument`.
- Caption timing uses `TrimClip` / `MoveClip`; position uses `SetClipProperties.transform`.

### 3.12 `ApplyCuts` (`apply_cuts`) and the range-cut algorithm

Fields: `ranges: List<TimeRange>`.

The main AI edit: remove a set of time ranges from every track and close the gaps. All ranges are in the original, pre-edit timeline coordinates. The caller never has to account for earlier cuts shifting later ones.

Validation: `ranges` non-empty; each `0 <= startUs < endUs`, else `InvalidArgument`.

Normalization of the range list:

1. Sort by `startUs`.
2. Merge ranges that overlap or touch.
3. Clamp to `[0, project.durationUs)`; drop ranges that become empty.

If nothing remains: `Success(changed = false)`, summary `"No content in the given ranges"`.

Range-cut algorithm, shared with `RippleDeleteRange`. Let `removedBefore(t)` be the total length of the normalized cuts inside `[0, t)`. For each affected track in project order, for each clip `c` in start order:

1. Pieces = `[c.startUs, c.endUs)` minus the union of cuts, as a list of maximal intervals `[ps, pe)` in order.
2. Drop pieces shorter than `MIN_CLIP_DURATION_US`. This is the one place a command discards material it was not literally asked to remove; the summary reports the count.
3. For each remaining piece: `startUs = ps - removedBefore(ps)`, `durationUs = pe - ps`, `sourceInUs = c.sourceInUs + (ps - c.startUs) * speedCenti / 100`. Other properties are copied.
4. The first remaining piece keeps `c.id`. Each further piece gets a new ID. IDs are allocated in iteration order (track, clip, piece).
5. A clip with no remaining pieces is deleted.

Properties of the result:

- `t -> t - removedBefore(t)` is monotone, so `NO_OVERLAP` and `CLIP_ORDER` are preserved without re-sorting.
- All affected tracks shift by the same amounts, so cross-track sync is preserved. A dropped sliver leaves a gap shorter than the minimum on its own track and does not disturb alignment.
- Two pieces of one clip around a cut end up touching, as two separate clips.
- `createdIds` lists the new piece IDs in allocation order.

### 3.13 `SetProjectSettings` (`set_project_settings`)

Fields: `name: String? = null`, `width: Int? = null`, `height: Int? = null`, `frameRate: Int? = null`. At least one non-null. Validated against `OUTPUT`; `name` non-blank.

Not in the brief's list, added because output settings must be changeable and undoable through the same spine. Clips are not rescaled; transforms are relative to the frame.

### 3.14 `Batch` (`batch`)

Fields: `commands: List<Command>`, `label: String? = null`.

- Executes the commands in order, each against the result of the previous one. Every intermediate project must be valid.
- Atomic: the first failure at index `i` returns `BatchFailed(index = i, commandType, cause)` and nothing is applied.
- A nested `Batch` is `InvalidArgument("commands[i]", "nested batch")`. This keeps the JSON Schema flat.
- An empty list is `Success(changed = false)`.
- `createdIds` is the concatenation of the sub-results. `changed` is true if the final project differs from the input.
- `summary` is `label` if given, otherwise `"<n> edits: "` followed by the first three sub-summaries joined with `"; "`.
- Because IDs are deterministic, a later command in a batch may reference an ID created earlier in the same batch, provided the caller learned it by running the earlier commands on an `EditPreview` of the same base project. Callers must not guess IDs.

### 3.15 Helpers that are queries, not commands

All pure functions of a `Project`. None of them changes state or appears in the undo history.

Snapping (`Snapping`):

- `candidates(project, playheadUs, markersUs, excludeClipIds, trackIds)` returns snap points: time 0 (`TIMELINE_START`), the playhead if given, every marker, and the start and end of every clip not in `excludeClipIds` (the clips being dragged), limited to `trackIds` if given. Markers are UI state in v1 and are passed in.
- `snap(timeUs, candidates, thresholdUs)` returns the candidate with the smallest `|candidate - timeUs|` if that distance is `<= thresholdUs`, else the input unchanged. Ties break by kind priority `PLAYHEAD > MARKER > CLIP_START = CLIP_END > TIMELINE_START`, then by lower time.
- `snapMove(startUs, durationUs, candidates, thresholdUs)` snaps a dragged clip by either edge: it evaluates `snap(startUs)` and `snap(startUs + durationUs)`, takes the closer hit, and returns the adjusted start (never below 0).
- The UI converts its pixel threshold to microseconds using the zoom level.

Edit limits (`EditLimits`):

- `trimRange(project, clipId, edge, ripple)`: the inclusive range of `toUs` values `TrimClip` would accept, or null if the clip does not exist.
- `canPlace(project, trackId, startUs, durationUs, ignoreClipIds)`: whether the range is free on the track.
- `freeRangeAround(project, trackId, timeUs, ignoreClipIds)`: the gap containing `timeUs`, or null if occupied.

Multi-select is UI state. Commands take `clipIds` lists wherever that is meaningful (`split`, `move`, `delete`, `set_clip_properties`, `edit_caption`). `TrimClip` is single-clip because an edge target has no group meaning.

### 3.16 Errors

`CommandError` is a sealed, serializable interface. Every case has a stable `code` (its serial name) and a human-readable `message`.

| Case | Fields | Raised when |
|---|---|---|
| `Malformed` | `detail` | Command JSON does not decode |
| `InvalidArgument` | `field`, `reason` | Bad shape or range of an argument |
| `NotFound` | `entity` (`ASSET`/`TRACK`/`CLIP`), `id` | Unknown ID |
| `TrackKindMismatch` | `trackId`, `trackKind`, `reason` | Clip or asset cannot live on that track |
| `Overlap` | `trackId`, `startUs`, `endUs`, `conflictingClipIds` | Target range is occupied |
| `ClipTooShort` | `clipId?`, `durationUs`, `minDurationUs` | Result would be under the minimum |
| `SplitOutOfRange` | `clipId`, `atUs`, `minUs`, `maxUs` | Split point outside the valid interior |
| `SourceOutOfRange` | `clipId?`, `assetId`, `sourceInUs`, `sourceOutUs`, `assetDurationUs` | Source range leaves the asset |
| `TrackNotEmpty` | `trackId`, `clipCount` | `RemoveTrack` without `deleteClips` |
| `BatchFailed` | `index`, `commandType`, `cause` | A batch member failed |
| `TransactionInProgress` | | `execute`/`commitPreview` while a drag transaction is open |
| `StaleRevision` | `expected`, `actual` | `commitPreview` on a changed session |
| `Internal` | `detail` | Result failed `ProjectValidator` (a bug) |

Domain failures are always values. Exceptions are reserved for programmer errors in the session API (for example `updateTransaction` with no open transaction throws `IllegalStateException`).

## 4. Execution API

```kotlin
val result = CommandExecutor.execute(project, command)
when (result) {
    is CommandResult.Success -> result.project   // new immutable project
    is CommandResult.Failure -> result.error     // sealed CommandError
}
```

- `CommandExecutor` is a stateless `object`. `execute` is a pure, deterministic function of its two arguments and is safe to call from any thread.
- `Success` carries the new project, `summary`, `changed`, and `createdIds`.
- Internally: one private handler function per command class in `command/internal/`, an exhaustive `when` in the executor, then `ProjectValidator.validate` on the result. Handlers return `CommandResult`; they do not throw for domain errors.
- Immutability gives structural sharing for free: an edit on one track copies that track's clip list and reuses every other track and every untouched clip object.

## 5. Undo, redo, transactions, preview

### 5.1 Snapshot, not inverse commands

Each history entry holds the project before and after.

- Correctness is by construction. Undo sets the state to `entry.before`; there is no inverse to get wrong. Inverses for `ApplyCuts`, ripple trims and sliver dropping would each need their own stored data and tests.
- Cost is low. Projects are immutable and share structure, so an entry costs roughly the changed track's clip list. 100 entries of a 1000-clip project are a few megabytes at worst.
- Coalescing a drag is trivial: keep the first `before`, replace `after`.
- The command is stored on the entry for labelling and logging only. It is never replayed.

History is in memory and is not persisted in v1.

### 5.2 `EditorSession`

The single mutable holder of editor state. The UI, the AI layer and the MCP server share one instance.

- State: `project`, `revision`, undo stack, redo stack, optional open transaction.
- `revision` starts at 0 and increases by 1 on every change of `project` (execute, undo, redo, transaction update, commit of a preview, `load`). It never decreases and is never reused.
- All public methods are `@Synchronized` on the session. Listeners are invoked synchronously on the calling thread, while the lock is held, in registration order. Listeners must be fast and must not call back into the session. The app's ViewModel adapts this to a `StateFlow`.

Rules:

1. `execute(command, origin)` runs the executor. On `Success(changed = true)` it pushes one entry and clears the redo stack. On failure or `changed = false` nothing changes, and the redo stack is kept.
2. One `execute` call is exactly one undo step. A `Batch` is one command and so one step.
3. `undo()` moves the top undo entry to the redo stack and sets `project = entry.before`. `redo()` is the mirror. Both return the entry, or null when there is nothing to do or a transaction is open.
4. Depth limit: `historyLimit`, default 100. When the undo stack exceeds it, the oldest entry is dropped.
5. Entry label: `Batch.label` if present, otherwise the result `summary`.
6. `load(project)` replaces the project and clears both stacks.

### 5.3 Transactions: coalescing a continuous drag

A drag (trim, move, a property slider) issues many commands but must be one undo step.

```
session.beginTransaction(label = "Trim clip")     // on gesture start
session.updateTransaction(TrimClip(id, END, t1))  // on every drag event
session.updateTransaction(TrimClip(id, END, t2))
session.commitTransaction()                       // on gesture end; cancelTransaction() on gesture cancel
```

- Replace semantics: every `updateTransaction(command)` executes against the transaction's base project (the project at `beginTransaction`), not against the previous update. All timing and property commands take absolute targets (`toUs`, `toStartUs`, absolute values), so a drag is always "base plus one command".
- A successful update sets `project` to the result and bumps `revision`, so the UI and the preview player follow the drag live.
- A failed update returns the failure and leaves `project` at the last successful update. The clip sticks at the last legal position.
- `commitTransaction()` pushes one entry (`before` = base, `after` = current, command = the last successful command) and clears redo. If no update succeeded or the result equals the base, nothing is pushed and it returns null.
- `cancelTransaction()` restores the base project.
- Only one transaction can be open. `beginTransaction` returns false if one already is. While one is open, `execute` and `commitPreview` return `Failure(TransactionInProgress)` and `undo`/`redo` return null. This is how an MCP call arriving mid-drag is handled: it is refused and the client retries.

Coalesce keys were considered and rejected: they need a timeout heuristic and can merge unrelated edits. The UI knows exactly when a gesture starts and ends.

### 5.4 `EditPreview`: the AI's diff before acceptance

A pending AI suggestion must not change the live project and must not block the user. It uses a detached preview instead of a transaction.

```
val preview = session.newPreview()            // captures project and revision
preview.submit(cmd1); preview.submit(cmd2)    // each AI tool call; results go back to the model
val diff = preview.diff()                     // shown to the user
session.commitPreview(preview, label = "Remove filler words")   // on accept; or just drop the preview on reject
```

- `EditPreview` holds `base`, `baseRevision`, a working `project`, and the list of successfully applied commands. `submit` runs the executor on the working project (accumulating, unlike a transaction). Failures are returned to the model and do not change the preview.
- The model sees real IDs from real execution, so multi-step edits (split, then delete the new clip) work without guessing.
- `commitPreview` checks `session.revision == preview.baseRevision`. If equal, it sets `project = preview.project` and pushes exactly one undo entry (`command = preview.toBatch(label)`, origin `AI`). If not, it returns `Failure(StaleRevision)`.
- On `StaleRevision` the AI layer either discards the suggestion or tries `session.execute(preview.toBatch(label))`. The re-run may fail or reference IDs that now mean something else, so the recommended UX is to make the timeline read-only while a suggestion is displayed, or to discard and ask again.
- A preview with no successful commands commits as a no-op and creates no entry.
- `EditPreview` and `EditorSession.asTarget(origin)` both implement `CommandTarget`, so the tool dispatcher does not care which one it is writing to. In-app AI tool calls go to a preview. MCP tool calls go straight to the session: each call is one undo step with origin `MCP`.

### 5.5 Diff structure

`ProjectDiff.between(before, after)` matches entities by ID.

- `clipsAdded`, `clipsRemoved`: `ClipSnapshot` (clip ID, track ID, timeline range, source range for media clips, a short label: asset display name or caption text).
- `clipsChanged`: `ClipChange(before, after, changedFields)` with fields `TRACK`, `START`, `DURATION`, `SOURCE_IN`, `SPEED`, `VOLUME`, `TRANSFORM`, `TEXT`, `STYLE`.
- `tracksAdded`, `tracksRemoved`, `assetsAdded`, `settingsChanged`, `durationBeforeUs`, `durationAfterUs`.
- Lists are ordered by track order, then start time.

A ripple marks every shifted clip as changed with `changedFields == {START}`; the UI can fold those into "N clips moved". A clip cut in the middle shows as one change (`DURATION`) plus one added clip (the right piece). To paint removed regions on the original timeline, the suggestion UI reads the `ApplyCuts` ranges from `preview.commands`; they are in original coordinates by definition.

## 6. JSON, schemas and tools

### 6.1 Serialization settings

One `Json` instance, `TimelineJson.json`:

```kotlin
Json { classDiscriminator = "type"; encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }
```

- Unknown keys are rejected everywhere. A command with a misspelled field fails with `Malformed`, which is useful feedback for a model.
- No serializable class may have a property named `type` (hence `Track.kind`).
- Doubles serialize in shortest round-trip form; equality survives a round trip.

### 6.2 Project file

```json
{
  "schemaVersion": 1,
  "id": "3f0c...",
  "name": "Trip",
  "nextId": 6,
  "output": { "width": 1920, "height": 1080, "frameRate": 30 },
  "assets": [
    { "id": "asset_1", "uri": "content://media/external/video/media/42", "durationUs": 12000000,
      "hasVideo": true, "hasAudio": true, "width": 1920, "height": 1080, "frameRate": 29.97 }
  ],
  "tracks": [
    { "id": "track_2", "kind": "VIDEO", "name": "Video 1", "clips": [
      { "type": "media", "id": "clip_4", "assetId": "asset_1", "startUs": 0, "durationUs": 5000000,
        "sourceInUs": 1000000, "speed": 1.0, "volume": 1.0,
        "transform": { "translateX": 0.0, "translateY": 0.0, "scale": 1.0, "rotationDeg": 0.0, "opacity": 1.0 } } ] },
    { "id": "track_3", "kind": "CAPTION", "name": "Captions 1", "clips": [
      { "type": "caption", "id": "clip_5", "startUs": 500000, "durationUs": 2000000, "text": "Hello",
        "style": { "fontSizeFrac": 0.05, "color": "#FFFFFFFF", "bold": false, "italic": false, "align": "CENTER" },
        "transform": { "translateX": 0.0, "translateY": 0.35, "scale": 1.0, "rotationDeg": 0.0, "opacity": 1.0 } } ] }
  ]
}
```

`TimelineJson.decodeProject(text)` steps:

1. Parse to `JsonObject`. Failure: `ProjectLoadError.Malformed`.
2. Read `schemaVersion` (required integer). Greater than `CURRENT_SCHEMA_VERSION`: `UnsupportedVersion(found, supported)`. Less than 1: `Malformed`.
3. Migration hook: while `version < CURRENT`, apply the registered `Migration` for `version`, which maps `JsonObject` to `JsonObject` and sets `schemaVersion = version + 1`. A missing step is `UnsupportedVersion`. v1 ships with an empty migration list; the mechanism and a test with a fake migration ship now.
4. Decode to `Project`. Failure: `Malformed`.
5. Normalize: sort each track's clips by `startUs`; raise `nextId` above every numeric ID suffix.
6. Validate. Any violation: `ProjectLoadError.Invalid(violations)`.

Migrations work on the JSON tree, so old Kotlin classes are never kept around. Project files contain local media URIs and nothing secret; no credential of any kind belongs in this model.

### 6.3 Command JSON

```json
{ "type": "split_clip", "clipIds": ["clip_4"], "atUs": 2500000 }
{ "type": "trim_clip", "clipId": "clip_4", "edge": "END", "toUs": 4000000, "ripple": true }
{ "type": "apply_cuts", "ranges": [ { "startUs": 1200000, "endUs": 1850000 }, { "startUs": 4000000, "endUs": 4300000 } ] }
{ "type": "batch", "label": "Tighten intro", "commands": [ { "type": "delete_clips", "clipIds": ["clip_9"], "ripple": true } ] }
```

`TimelineJson.decodeCommand` returns `CommandDecodeResult`; kotlinx exceptions are caught and turned into `CommandError.Malformed`. Fields with Kotlin defaults may be omitted.

### 6.4 JSON Schema: generated, with golden files

Decision: schemas are generated at runtime from `SerialDescriptor`s by a small in-module generator (`JsonSchemaGenerator`, about 150 lines), not hand-written.

- Hand-written constants for 14 commands will drift from the data classes; the first symptom would be a model sending arguments the decoder rejects.
- The generator has no third-party dependency. It needs `@OptIn(ExperimentalSerializationApi::class)` for descriptor annotations; the opt-in is confined to that one file.
- Every generated schema is pinned by a golden file in `src/test/resources/schemas/<tool>.json`. A test compares generator output with the file. Changing a command changes a checked-in schema, which shows up in review.

Annotations (both `@SerialInfo`):

- `@Desc("...")` on a class (tool or object description) or property.
- `@Min(n)` on an integer or number property, emitted as `minimum`.

Generator mapping, restricted to the subset `type`, `properties`, `required`, `enum`, `items`, `description`, `minimum`:

| Kotlin | Schema |
|---|---|
| `String` | `{"type":"string"}` |
| `Long`, `Int` | `{"type":"integer"}` |
| `Double` | `{"type":"number"}` |
| `Boolean` | `{"type":"boolean"}` |
| enum | `{"type":"string","enum":[...]}` |
| `List<T>` | `{"type":"array","items":<T>}` |
| nested data class | `{"type":"object","properties":{...},"required":[...]}` |
| nullable or defaulted property | same type, left out of `required` |
| `List<Command>` (in `Batch`) | `{"type":"array","items":{"type":"object","properties":{"type":{"type":"string","enum":[<all command types except batch>]}},"required":["type"]}}` |

- `required` lists exactly the properties with no default (`descriptor.isElementOptional(i) == false`).
- No `oneOf`, `$ref`, `additionalProperties`, `maximum` or nullable type unions. Upper bounds and units go in the description text.
- Every property that ends in `Us` must have a `@Desc` stating microseconds; a test enforces that every property of every command has a description.
- A tool's input schema is the command's object schema without the `type` discriminator, since the tool name carries it. The root is always `{"type":"object", ...}`, which is what both Claude's `input_schema` and MCP's `inputSchema` require.

Golden example, `split.json`:

```json
{
  "type": "object",
  "description": "Split clips at a timeline time. Each listed clip must strictly contain atUs. The left part keeps its id; the new right-part ids are returned in createdIds.",
  "properties": {
    "clipIds": { "type": "array", "items": { "type": "string" }, "description": "Ids of the clips to split." },
    "atUs": { "type": "integer", "minimum": 0, "description": "Timeline time of the cut, in microseconds (1 second = 1000000)." }
  },
  "required": ["clipIds", "atUs"]
}
```

### 6.5 Tool registry and name mapping

`ToolRegistry` holds one `ToolSpec` per tool: name, description, input schema, and what it maps to. `ToolRegistry.mcpV1` is the set the MCP server exposes in v1; the in-app AI picks its own subset from `ToolRegistry.all`.

| Tool name | Kind | Maps to | Arguments |
|---|---|---|---|
| `get_project` | query | `Queries.summary(project)`; `full = true` returns the full project JSON | `full?: boolean` |
| `list_clips` | query | `Queries.listClips(project, trackId, fromUs, toUs)` returning `ClipView` rows | `trackId?`, `fromUs?`, `toUs?` |
| `split` | command | `SplitClip` | `clipIds`, `atUs` |
| `trim` | command | `TrimClip` | `clipId`, `edge`, `toUs`, `ripple?` |
| `move` | command | `MoveClip` | `clipIds`, `toStartUs?`, `toTrackId?` |
| `delete` | command | `DeleteClips` | `clipIds`, `ripple?` |
| `add_caption` | command | `AddCaption` | `captions`, `trackId?`, `style?`, `transform?` |
| `apply_cuts` | command | `ApplyCuts` | `ranges` |
| `export` | host | `ExportService` | `fileName?`, `width?`, `height?`, `frameRate?`, `jobId?` |
| `get_transcript` | host + query | `TranscriptService`, then `TranscriptMapper` | `fromUs?`, `toUs?`, `wordLevel?` |

The remaining commands are registered under their command type name (`add_clip`, `ripple_delete_range`, `set_clip_properties`, `edit_caption`, `add_track`, `remove_track`, `import_media`, `set_project_settings`, `batch`) and are in `ToolRegistry.all` but not in `mcpV1`. Adding one to the MCP surface is a one-line change.

`export` and `get_transcript` are not timeline commands. They touch codecs, files and speech models, do not change the project, and are not undoable. They live behind two interfaces in `dev.videoeditor.timeline.host` that the app implements (Media3 export, on-device or user-configured transcription):

- `export` without `jobId` calls `ExportService.startExport(project, request)` and returns an `ExportStatus` with a job ID. With `jobId` it returns `ExportService.exportStatus(jobId)`. Sanitizing `fileName` and choosing the output location are the app's job.
- `get_transcript` asks `TranscriptService.transcript(assetId)` for each asset used by a media clip on a `VIDEO` or `AUDIO` track. Asset transcripts are in source time. `TranscriptMapper.toTimeline` maps them through the clips to timeline time, which is the coordinate system `apply_cuts` uses. This mapping is the reason the transcript path goes through this module.

Transcript mapping rule: for each media clip, a word is included when its `startUs` lies in `[sourceInUs, sourceOutUs)`. Timeline start is `clip.startUs + (word.startUs - sourceInUs) * 100 / speedCenti`; the end is mapped the same way and clamped to `clip.endUs`. Without `wordLevel`, included words are joined back into their segments (a segment with no word timings is included when it overlaps the source range, with times clamped). Output is sorted by timeline start and carries the `clipId`.

`ToolDispatcher.call(target, name, args)`:

- Command tools: add `"type"` to `args`, decode, `target.submit(command)`, return a `CommandResponse`.
- Query tools: run against `target.project`.
- Host tools: call the host interface with `target.project`.
- Unknown tool or undecodable arguments: an error response, never an exception.

Response shape for command tools, used as the Claude `tool_result` content and the MCP tool result text:

```json
{ "ok": true, "summary": "Split clip_4 at 0:02.500", "changed": true, "createdIds": ["clip_6"], "durationUs": 5000000 }
{ "ok": false, "error": { "type": "split_out_of_range", "message": "...", "clipId": "clip_4", "atUs": 0, "minUs": 10000, "maxUs": 4990000 } }
```

The AI layer authenticates to the Claude Messages API only with an Anthropic Console API key supplied by the user and held by the app. Nothing in this module reads, stores or serializes a key.

## 7. Package layout and public signatures

```
core/timeline/src/main/kotlin/dev/videoeditor/timeline/
  model/     Limits.kt TimeMath.kt TimeRange.kt Project.kt MediaAsset.kt Track.kt Clip.kt Transform.kt CaptionStyle.kt
  validate/  ProjectValidator.kt
  command/   Command.kt CommandError.kt CommandResult.kt CommandExecutor.kt
             internal/   one file per command handler, RangeCut.kt, TrackOps.kt (all `internal`)
  session/   EditorSession.kt EditPreview.kt CommandTarget.kt HistoryEntry.kt
  query/     Queries.kt Snapping.kt EditLimits.kt TranscriptMapper.kt
  diff/      ProjectDiff.kt
  json/      TimelineJson.kt Migration.kt
  schema/    SchemaAnnotations.kt JsonSchemaGenerator.kt ToolRegistry.kt ToolDispatcher.kt
  host/      ExportService.kt TranscriptService.kt
core/timeline/src/test/kotlin/dev/videoeditor/timeline/   mirrors the above, plus gen/Arbs.kt
core/timeline/src/test/resources/schemas/                 golden schema files
```

### 7.1 model

```kotlin
package dev.videoeditor.timeline.model

object Limits {
    const val MIN_CLIP_DURATION_US: Long = 10_000
    const val MAX_TIMELINE_US: Long = 86_400_000_000
    const val MIN_SPEED_CENTI: Int = 10
    const val MAX_SPEED_CENTI: Int = 1000
    const val MAX_VOLUME: Double = 2.0
    const val MIN_SCALE: Double = 0.01
    const val MAX_SCALE: Double = 10.0
    const val MAX_TRANSLATE: Double = 2.0
    const val MAX_CAPTION_CHARS: Int = 2000
    const val MIN_OUTPUT_DIM: Int = 16
    const val MAX_OUTPUT_DIM: Int = 8192
    const val MIN_FPS: Int = 1
    const val MAX_FPS: Int = 120
    const val CURRENT_SCHEMA_VERSION: Int = 1
}

object TimeMath {
    fun normalizeSpeed(speed: Double): Double                        // round to 0.01
    fun speedCenti(speed: Double): Int
    fun timelineToSource(timelineDeltaUs: Long, speedCenti: Int): Long   // Math.floorDiv(delta * centi, 100)
    fun sourceToTimeline(sourceDeltaUs: Long, speedCenti: Int): Long     // Math.floorDiv(delta * 100, centi)
    fun sourceTimeAt(clip: MediaClip, timelineUs: Long): Long
    fun floorToFrame(timeUs: Long, frameRate: Int): Long
    fun normalizeRotation(deg: Double): Double                        // [0, 360)
    fun formatTimecode(timeUs: Long): String                          // "m:ss.mmm" or "h:mm:ss.mmm"
}

@Serializable
data class TimeRange(val startUs: Long, val endUs: Long) {
    val durationUs: Long
}

@Serializable
data class Project(
    val schemaVersion: Int = Limits.CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val nextId: Long = 1,
    val output: OutputSettings = OutputSettings(),
    val assets: List<MediaAsset> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val durationUs: Long                       // max clip end, 0 if no clips
    fun asset(id: String): MediaAsset?
    fun track(id: String): Track?
    fun clip(id: String): Clip?
    fun trackOf(clipId: String): Track?

    companion object {
        fun empty(id: String, name: String, output: OutputSettings = OutputSettings()): Project
    }
}

@Serializable
data class OutputSettings(val width: Int = 1920, val height: Int = 1080, val frameRate: Int = 30)

@Serializable
data class MediaAsset(
    val id: String,
    val uri: String,
    val durationUs: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val displayName: String? = null,
)

@Serializable
enum class TrackKind { VIDEO, AUDIO, OVERLAY, CAPTION }

@Serializable
data class Track(
    val id: String,
    val kind: TrackKind,
    val name: String,
    val clips: List<Clip> = emptyList(),
) {
    val endUs: Long                            // last clip end, 0 if empty
}

@Serializable
sealed interface Clip {
    val id: String
    val startUs: Long
    val durationUs: Long
    val transform: Transform
}

val Clip.endUs: Long
val Clip.range: TimeRange

@Serializable
@SerialName("media")
data class MediaClip(
    override val id: String,
    val assetId: String,
    override val startUs: Long,
    override val durationUs: Long,
    val sourceInUs: Long,
    val speed: Double = 1.0,
    val volume: Double = 1.0,
    override val transform: Transform = Transform(),
) : Clip {
    val speedCenti: Int
    val sourceDurationUs: Long                 // durationUs * speedCenti / 100
    val sourceOutUs: Long                      // sourceInUs + sourceDurationUs
}

@Serializable
@SerialName("caption")
data class CaptionClip(
    override val id: String,
    override val startUs: Long,
    override val durationUs: Long,
    val text: String,
    val style: CaptionStyle = CaptionStyle(),
    override val transform: Transform = Transform(translateY = 0.35),
) : Clip

@Serializable
data class Transform(
    val translateX: Double = 0.0,
    val translateY: Double = 0.0,
    val scale: Double = 1.0,
    val rotationDeg: Double = 0.0,
    val opacity: Double = 1.0,
)

@Serializable
data class TransformPatch(
    val translateX: Double? = null,
    val translateY: Double? = null,
    val scale: Double? = null,
    val rotationDeg: Double? = null,
    val opacity: Double? = null,
)

@Serializable
enum class TextAlign { START, CENTER, END }

@Serializable
data class CaptionStyle(
    val fontSizeFrac: Double = 0.05,           // fraction of output height, (0, 0.5]
    val color: String = "#FFFFFFFF",           // #AARRGGBB, upper case
    val backgroundColor: String? = null,       // #AARRGGBB or none
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: TextAlign = TextAlign.CENTER,
    val fontFamily: String? = null,            // null = app default
)

@Serializable
data class CaptionStylePatch(
    val fontSizeFrac: Double? = null,
    val color: String? = null,
    val backgroundColor: String? = null,       // "" clears the background
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val align: TextAlign? = null,
    val fontFamily: String? = null,            // "" resets to the app default
)
```

Colours must match `^#[0-9A-F]{8}$` (commands upper-case the input before checking).

### 7.2 validate

```kotlin
package dev.videoeditor.timeline.validate

enum class InvariantCode {
    ID_UNIQUE, ID_COUNTER, CLIP_ORDER, NO_OVERLAP, CLIP_TIME, CLIP_KIND, ASSET_REF,
    SOURCE_RANGE, SPEED, VOLUME, TRANSFORM, CAPTION, ASSET, OUTPUT,
}

data class Violation(val code: InvariantCode, val message: String, val ids: List<String> = emptyList())

object ProjectValidator {
    fun validate(project: Project): List<Violation>
    fun isValid(project: Project): Boolean
}
```

### 7.3 command

```kotlin
package dev.videoeditor.timeline.command

@Serializable
sealed interface Command

@Serializable enum class ClipEdge { START, END }

@Serializable @SerialName("import_media")
data class ImportMedia(
    val uri: String,
    val durationUs: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val displayName: String? = null,
) : Command

@Serializable @SerialName("add_track")
data class AddTrack(val kind: TrackKind, val name: String? = null, val index: Int? = null) : Command

@Serializable @SerialName("remove_track")
data class RemoveTrack(val trackId: String, val deleteClips: Boolean = false) : Command

@Serializable @SerialName("add_clip")
data class AddClip(
    val trackId: String,
    val assetId: String,
    val startUs: Long? = null,
    val sourceInUs: Long = 0,
    val sourceOutUs: Long? = null,
    val speed: Double = 1.0,
    val volume: Double = 1.0,
    val transform: Transform = Transform(),
    val ripple: Boolean = false,
) : Command

@Serializable @SerialName("split_clip")
data class SplitClip(val clipIds: List<String>, val atUs: Long) : Command

@Serializable @SerialName("trim_clip")
data class TrimClip(val clipId: String, val edge: ClipEdge, val toUs: Long, val ripple: Boolean = false) : Command

@Serializable @SerialName("move_clip")
data class MoveClip(val clipIds: List<String>, val toStartUs: Long? = null, val toTrackId: String? = null) : Command

@Serializable @SerialName("delete_clips")
data class DeleteClips(val clipIds: List<String>, val ripple: Boolean = false) : Command

@Serializable @SerialName("ripple_delete_range")
data class RippleDeleteRange(val startUs: Long, val endUs: Long, val trackIds: List<String>? = null) : Command

@Serializable @SerialName("set_clip_properties")
data class SetClipProperties(
    val clipIds: List<String>,
    val speed: Double? = null,
    val volume: Double? = null,
    val transform: TransformPatch? = null,
    val ripple: Boolean = false,
) : Command

@Serializable
data class CaptionSpec(val startUs: Long, val endUs: Long, val text: String)

@Serializable @SerialName("add_caption")
data class AddCaption(
    val captions: List<CaptionSpec>,
    val trackId: String? = null,
    val style: CaptionStyle = CaptionStyle(),
    val transform: Transform = Transform(translateY = 0.35),
) : Command

@Serializable @SerialName("edit_caption")
data class EditCaption(
    val clipIds: List<String>,
    val text: String? = null,
    val style: CaptionStylePatch? = null,
) : Command

@Serializable @SerialName("apply_cuts")
data class ApplyCuts(val ranges: List<TimeRange>) : Command

@Serializable @SerialName("set_project_settings")
data class SetProjectSettings(
    val name: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Int? = null,
) : Command

@Serializable @SerialName("batch")
data class Batch(val commands: List<Command>, val label: String? = null) : Command

/** Serial name of a command, e.g. "split_clip". */
val Command.typeName: String
```

Every command class and property also carries `@Desc`, and non-negative time fields carry `@Min(0)`; omitted above for readability.

```kotlin
@Serializable enum class EntityKind { ASSET, TRACK, CLIP }

@Serializable
sealed interface CommandError {
    val message: String

    @Serializable @SerialName("malformed")
    data class Malformed(val detail: String) : CommandError
    @Serializable @SerialName("invalid_argument")
    data class InvalidArgument(val field: String, val reason: String) : CommandError
    @Serializable @SerialName("not_found")
    data class NotFound(val entity: EntityKind, val id: String) : CommandError
    @Serializable @SerialName("track_kind_mismatch")
    data class TrackKindMismatch(val trackId: String, val trackKind: TrackKind, val reason: String) : CommandError
    @Serializable @SerialName("overlap")
    data class Overlap(val trackId: String, val startUs: Long, val endUs: Long, val conflictingClipIds: List<String>) : CommandError
    @Serializable @SerialName("clip_too_short")
    data class ClipTooShort(val clipId: String? = null, val durationUs: Long, val minDurationUs: Long) : CommandError
    @Serializable @SerialName("split_out_of_range")
    data class SplitOutOfRange(val clipId: String, val atUs: Long, val minUs: Long, val maxUs: Long) : CommandError
    @Serializable @SerialName("source_out_of_range")
    data class SourceOutOfRange(val clipId: String? = null, val assetId: String, val sourceInUs: Long, val sourceOutUs: Long, val assetDurationUs: Long) : CommandError
    @Serializable @SerialName("track_not_empty")
    data class TrackNotEmpty(val trackId: String, val clipCount: Int) : CommandError
    @Serializable @SerialName("batch_failed")
    data class BatchFailed(val index: Int, val commandType: String, val cause: CommandError) : CommandError
    @Serializable @SerialName("transaction_in_progress")
    data object TransactionInProgress : CommandError
    @Serializable @SerialName("stale_revision")
    data class StaleRevision(val expected: Long, val actual: Long) : CommandError
    @Serializable @SerialName("internal")
    data class Internal(val detail: String) : CommandError
}
```

`message` is a computed property on each case (not a constructor parameter). `TimelineJson.encodeError` adds it to the encoded object so tool results carry it.

```kotlin
sealed interface CommandResult {
    data class Success(
        val project: Project,
        val summary: String,
        val changed: Boolean = true,
        val createdIds: List<String> = emptyList(),
    ) : CommandResult

    data class Failure(val error: CommandError) : CommandResult
}

object CommandExecutor {
    fun execute(project: Project, command: Command): CommandResult
}
```

### 7.4 session

```kotlin
package dev.videoeditor.timeline.session

enum class Origin { USER, AI, MCP }

data class HistoryEntry(
    val label: String,
    val before: Project,
    val after: Project,
    val command: Command?,
    val origin: Origin,
)

data class EditorState(
    val project: Project,
    val revision: Long,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val undoLabel: String?,
    val redoLabel: String?,
    val transactionOpen: Boolean,
)

/** Something commands can be submitted to: the live session or a detached preview. */
interface CommandTarget {
    val project: Project
    fun submit(command: Command): CommandResult
}

class EditorSession(initial: Project, val historyLimit: Int = 100) {
    val state: EditorState
    val project: Project
    val revision: Long

    fun execute(command: Command, origin: Origin = Origin.USER): CommandResult
    fun undo(): HistoryEntry?
    fun redo(): HistoryEntry?

    fun beginTransaction(label: String, origin: Origin = Origin.USER): Boolean
    fun updateTransaction(command: Command): CommandResult      // throws IllegalStateException if none is open
    fun commitTransaction(): HistoryEntry?                      // null if nothing changed; throws if none is open
    fun cancelTransaction()                                     // no-op if none is open

    fun newPreview(): EditPreview
    fun commitPreview(preview: EditPreview, label: String, origin: Origin = Origin.AI): CommandResult

    fun load(project: Project)                                  // replaces the project, clears history
    fun asTarget(origin: Origin): CommandTarget                 // submit == execute(command, origin)
    fun addListener(listener: (EditorState) -> Unit): AutoCloseable
}

class EditPreview internal constructor(val base: Project, val baseRevision: Long) : CommandTarget {
    override val project: Project
    val commands: List<Command>                                 // successfully applied, in order
    val createdIds: List<String>
    override fun submit(command: Command): CommandResult
    fun diff(): ProjectDiff
    fun toBatch(label: String? = null): Batch
}
```

`EditPreview` is not thread-safe; one AI turn owns it.

### 7.5 query

```kotlin
package dev.videoeditor.timeline.query

@Serializable
data class TrackSummary(val id: String, val kind: TrackKind, val name: String, val clipCount: Int, val endUs: Long)

@Serializable
data class ProjectSummary(
    val id: String,
    val name: String,
    val durationUs: Long,
    val output: OutputSettings,
    val tracks: List<TrackSummary>,
    val assets: List<MediaAsset>,
)

@Serializable
data class ClipView(
    val id: String,
    val trackId: String,
    val trackKind: TrackKind,
    val startUs: Long,
    val endUs: Long,
    val durationUs: Long,
    val assetId: String? = null,
    val sourceInUs: Long? = null,
    val sourceOutUs: Long? = null,
    val speed: Double? = null,
    val volume: Double? = null,
    val text: String? = null,
)

object Queries {
    fun summary(project: Project): ProjectSummary
    fun listClips(project: Project, trackId: String? = null, fromUs: Long? = null, toUs: Long? = null): List<ClipView>
    fun clipAt(project: Project, trackId: String, timeUs: Long): Clip?
    /** Clips with startUs < timeUs < endUs, in track then start order. */
    fun clipsAt(project: Project, timeUs: Long): List<Clip>
}

enum class SnapKind { PLAYHEAD, MARKER, CLIP_START, CLIP_END, TIMELINE_START }

data class SnapPoint(val timeUs: Long, val kind: SnapKind, val clipId: String? = null)

data class SnapResult(val timeUs: Long, val snappedTo: SnapPoint?)

object Snapping {
    fun candidates(
        project: Project,
        playheadUs: Long? = null,
        markersUs: List<Long> = emptyList(),
        excludeClipIds: Set<String> = emptySet(),
        trackIds: Set<String>? = null,
    ): List<SnapPoint>

    fun snap(timeUs: Long, candidates: List<SnapPoint>, thresholdUs: Long): SnapResult
    fun snapMove(startUs: Long, durationUs: Long, candidates: List<SnapPoint>, thresholdUs: Long): SnapResult
}

object EditLimits {
    fun trimRange(project: Project, clipId: String, edge: ClipEdge, ripple: Boolean = false): LongRange?
    fun canPlace(project: Project, trackId: String, startUs: Long, durationUs: Long, ignoreClipIds: Set<String> = emptySet()): Boolean
    fun freeRangeAround(project: Project, trackId: String, timeUs: Long, ignoreClipIds: Set<String> = emptySet()): TimeRange?
}

@Serializable
data class TimelineTranscriptEntry(val clipId: String, val startUs: Long, val endUs: Long, val text: String)

@Serializable
data class TimelineTranscript(val entries: List<TimelineTranscriptEntry>, val missingAssetIds: List<String> = emptyList())

object TranscriptMapper {
    fun toTimeline(
        project: Project,
        transcripts: Map<String, AssetTranscript>,      // by asset id
        wordLevel: Boolean = false,
        fromUs: Long? = null,
        toUs: Long? = null,
    ): TimelineTranscript
}
```

### 7.6 diff

```kotlin
package dev.videoeditor.timeline.diff

enum class ClipField { TRACK, START, DURATION, SOURCE_IN, SPEED, VOLUME, TRANSFORM, TEXT, STYLE }

@Serializable
data class ClipSnapshot(
    val clipId: String,
    val trackId: String,
    val range: TimeRange,
    val sourceRange: TimeRange? = null,
    val label: String,
)

@Serializable
data class ClipChange(val before: ClipSnapshot, val after: ClipSnapshot, val changedFields: Set<ClipField>)

@Serializable
data class ProjectDiff(
    val clipsAdded: List<ClipSnapshot>,
    val clipsRemoved: List<ClipSnapshot>,
    val clipsChanged: List<ClipChange>,
    val tracksAdded: List<String>,
    val tracksRemoved: List<String>,
    val assetsAdded: List<String>,
    val settingsChanged: Boolean,
    val durationBeforeUs: Long,
    val durationAfterUs: Long,
) {
    val isEmpty: Boolean

    companion object {
        fun between(before: Project, after: Project): ProjectDiff
    }
}
```

### 7.7 json

```kotlin
package dev.videoeditor.timeline.json

interface Migration {
    val fromVersion: Int                                   // migrates fromVersion -> fromVersion + 1
    fun migrate(project: JsonObject): JsonObject
}

sealed interface ProjectLoadError {
    data class Malformed(val detail: String) : ProjectLoadError
    data class UnsupportedVersion(val found: Int, val supported: Int) : ProjectLoadError
    data class Invalid(val violations: List<Violation>) : ProjectLoadError
}

sealed interface ProjectLoadResult {
    data class Success(val project: Project, val migratedFrom: Int? = null) : ProjectLoadResult
    data class Failure(val error: ProjectLoadError) : ProjectLoadResult
}

sealed interface CommandDecodeResult {
    data class Success(val command: Command) : CommandDecodeResult
    data class Failure(val error: CommandError.Malformed) : CommandDecodeResult
}

object TimelineJson {
    val json: Json
    val migrations: List<Migration>                        // empty in v1

    fun encodeProject(project: Project): String
    fun decodeProject(text: String, migrations: List<Migration> = this.migrations): ProjectLoadResult

    fun encodeCommand(command: Command): JsonObject
    fun decodeCommand(element: JsonObject): CommandDecodeResult
    fun decodeCommand(text: String): CommandDecodeResult

    fun encodeError(error: CommandError): JsonObject       // includes "message"
}
```

### 7.8 schema

```kotlin
package dev.videoeditor.timeline.schema

@SerialInfo @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class Desc(val text: String)

@SerialInfo @Target(AnnotationTarget.PROPERTY)
annotation class Min(val value: Long)

object JsonSchemaGenerator {
    /** Object schema for a serializable class. `omit` lists top-level properties to leave out. */
    fun schemaFor(descriptor: SerialDescriptor, omit: Set<String> = emptySet()): JsonObject
}

enum class ToolKind { COMMAND, QUERY, HOST }

data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val kind: ToolKind,
    val commandType: String? = null,           // serial name, for COMMAND tools
)

object ToolRegistry {
    val all: List<ToolSpec>
    val mcpV1: List<ToolSpec>                  // the ten tools in section 6.5
    fun find(name: String): ToolSpec?
}

@Serializable
data class CommandResponse(
    val ok: Boolean,
    val summary: String? = null,
    val changed: Boolean? = null,
    val createdIds: List<String>? = null,
    val durationUs: Long? = null,
    val error: JsonObject? = null,
)

data class ToolCallResult(val isError: Boolean, val content: JsonObject)

class ToolDispatcher(
    private val exportService: ExportService?,
    private val transcriptService: TranscriptService?,
) {
    suspend fun call(target: CommandTarget, name: String, args: JsonObject): ToolCallResult
}
```

A null service makes its tool return an error result ("not available"), so tests and previews can run without a host.

### 7.9 host

```kotlin
package dev.videoeditor.timeline.host

@Serializable
data class ExportRequest(
    val fileName: String? = null,
    val width: Int? = null,                    // null = project output settings
    val height: Int? = null,
    val frameRate: Int? = null,
)

@Serializable enum class ExportState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

@Serializable
data class ExportStatus(
    val jobId: String,
    val state: ExportState,
    val progress: Double = 0.0,                // 0..1
    val outputUri: String? = null,
    val error: String? = null,
)

interface ExportService {
    suspend fun startExport(project: Project, request: ExportRequest): ExportStatus
    suspend fun exportStatus(jobId: String): ExportStatus?
}

@Serializable
data class TranscriptWord(val startUs: Long, val endUs: Long, val text: String)

@Serializable
data class TranscriptSegment(val startUs: Long, val endUs: Long, val text: String, val words: List<TranscriptWord> = emptyList())

@Serializable
data class AssetTranscript(val assetId: String, val language: String? = null, val segments: List<TranscriptSegment>)

interface TranscriptService {
    /** Transcript in the asset's own (source) time, or null if none is available. */
    suspend fun transcript(assetId: String): AssetTranscript?
}
```

## 8. Test plan

### 8.1 Generators (`gen/Arbs.kt`)

- `Arb.project()`: valid by construction. 0 to 4 assets, 0 to 6 tracks of random kinds, each track filled left to right with random gaps and clips whose source ranges fit their assets; speeds drawn from `{0.1, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.33, 10.0}` plus random centi values. A meta-test asserts `ProjectValidator.isValid` on every generated project.
- `Arb.command(project)`: about 80% plausibly valid commands (real IDs, in-range times), 20% adversarial (unknown IDs, negative times, boundary values such as `atUs == startUs`, `MIN - 1`, zero-length ranges, speeds outside the range).
- `Arb.cutRanges(project)`: unsorted, overlapping, touching, and out-of-range ranges.
- Edge values to bias toward: 0, `MIN_CLIP_DURATION_US`, `MIN_CLIP_DURATION_US +/- 1`, clip starts and ends, asset duration, `MAX_TIMELINE_US`.

### 8.2 Properties

| # | Property |
|---|---|
| P1 | Closure: for any valid `p` and any `c`, `execute(p, c)` is a `Failure`, or a `Success` whose project is valid. Never `Internal`, never an exception |
| P2 | Determinism: `execute(p, c) == execute(p, c)`; `p` is structurally unchanged afterwards |
| P3 | `changed == (result.project != p)` |
| P4 | `Batch(cs)` equals the left fold of `execute` over `cs` when all succeed, and fails with `BatchFailed(i)` where `i` is the first failing index |
| P5 | Split: clip count +1; `left.endUs == right.startUs == atUs`; `left.durationUs + right.durationUs == old.durationUs`; `right.sourceOutUs <= old.sourceOutUs`; left keeps the ID; all other clips identical |
| P6 | Content preservation under cuts: for a random original time `t` outside all cuts and inside a surviving piece, `sourceTimeAt` in the result at `t - removedBefore(t)` is within 1 us of `sourceTimeAt` in the original, on the same asset |
| P7 | `ApplyCuts(rs)` gives the same project for any permutation of `rs` and for `rs` with ranges split into touching sub-ranges |
| P8 | `ApplyCuts(rs)` equals applying `RippleDeleteRange` for each normalized range in descending start order, comparing projects with clip IDs erased |
| P9 | Cuts: `newDuration <= oldDuration - removedBefore(oldDuration)`; clips ending at or before the first cut are identical |
| P10 | Source never grows: after `SplitClip`, `TrimClip` inward, `SetClipProperties(speed)`, `ApplyCuts`, every resulting clip's source range is inside the source range of the clip it came from |
| P11 | `DeleteClips(ripple = true)`: survivor order is preserved; each track's sum of clip durations drops by exactly the deleted durations on that track; gaps between consecutive survivors with nothing deleted between them are unchanged |
| P12 | Move there and back (both succeeding) restores the project exactly |
| P13 | Trim an edge and trim it back restores timeline start and duration exactly, and `sourceInUs` within 1 us |
| P14 | `EditLimits.trimRange` agrees with the executor: `TrimClip` succeeds for both range ends and fails one step outside each |
| P15 | `EditLimits.canPlace` is true exactly when the corresponding `AddClip`/`MoveClip` does not fail with `Overlap` |
| P16 | Undo: after any sequence of executes, undoing every entry returns the initial project (structural equality) when the history limit was not hit; undo then redo is the identity; `revision` strictly increases |
| P17 | History: stack size never exceeds `historyLimit`; a failed or unchanged command adds no entry and keeps redo; a changing command clears redo |
| P18 | Transaction: `begin; update(c1..cn); commit` leaves the same project as `execute(last successful ci)` on the base and adds exactly one entry (or none if unchanged); `cancel` restores the base and adds none |
| P19 | Preview: after `commitPreview`, `session.project == preview.project` with exactly one new entry; any intervening session change gives `StaleRevision` and no change |
| P20 | JSON round trip: `decodeProject(encodeProject(p)) == p`; same for every generated command and every `CommandError` |
| P21 | Schema conformance: every generated command, encoded, validates against its generated schema using a test-only validator for the subset; `required` equals the set of non-default properties |
| P22 | Diff: `between(p, p).isEmpty`; `between(a, b).clipsAdded` and `between(b, a).clipsRemoved` have the same IDs; for a successful execute, clip IDs in `createdIds` are a subset of `clipsAdded` |
| P23 | Snap: the result is the input or a candidate within `thresholdUs`; no other candidate is strictly closer |
| P24 | Load normalization: shuffling clip order within tracks and lowering `nextId` in the JSON still loads to the original project |

### 8.3 Example tests

Time math:
- 1.0x is exact; 3.33x and 0.1x tables for `timelineToSource` / `sourceToTimeline`; negative delta uses floor toward negative infinity.
- `normalizeSpeed(1.4999999) == 1.5`; 0.094 is rejected, 0.096 normalizes to 0.10.

Split:
- Middle of a 2x clip: exact `sourceInUs` of the right piece.
- At `startUs`, at `endUs`, at `startUs + MIN - 1`: `SplitOutOfRange` with the right bounds; at `startUs + MIN`: success.
- A clip of `2 * MIN - 1`: no valid split point.
- A caption clip: text and style copied.
- Two clips at one playhead: two new IDs in list order.

Trim:
- All four cases in the 3.6 table with concrete numbers, including ripple shifting two later clips and not an earlier one.
- Extend past the asset end: `SourceOutOfRange`. Extend start below source 0: `SourceOutOfRange`.
- Extend into a neighbour: `Overlap` with the neighbour's ID. Touching the neighbour exactly: success.
- Below minimum: `ClipTooShort`. Same edge position: `changed = false`.

Move:
- To free space; onto an occupied range: `Overlap`; touching: success.
- Two adjacent clips shifted right by less than their combined length (they land on their own old positions): success.
- Video clip to an audio track with and without asset audio; any media clip to a caption track: `TrackKindMismatch`.
- Clips from two tracks with `toTrackId`: `InvalidArgument`.

Delete and cuts:
- `DeleteClips` with and without ripple on a track with gaps.
- `ApplyCuts` on a three-track project (video, audio, captions): one cut inside a clip, one spanning two clips, one covering a whole clip; assert every clip's start, duration, source in and ID.
- A cut leaving a 5 ms remnant: remnant dropped, summary mentions it.
- Ranges entirely past the project end: `changed = false`.
- `RippleDeleteRange` with a track subset leaves other tracks untouched.

Other commands:
- `AddClip` append, explicit start, ripple insert, straddling insert point, wrong track kind, duration under minimum at 10x.
- `SetClipProperties`: speed 1.0 to 2.0 halves the duration; with ripple the followers shift; without ripple a slowdown into a neighbour is `Overlap`; speed on a caption is `InvalidArgument`; transform patch changes only opacity; rotation 450 becomes 90.
- `AddCaption`: creates a caption track when none exists and reports IDs in order; overlapping specs are rejected; whitespace-only text is rejected.
- `EditCaption`: text on two clips is rejected; style patch on many clips; `backgroundColor = ""` clears.
- `ImportMedia` twice with the same URI: second is a no-op naming the first ID.
- `RemoveTrack` non-empty with and without `deleteClips`.
- `Batch`: split then delete the created clip using the ID learned from a preview; failure at index 1 leaves the project untouched; nested batch rejected.

Session:
- Undo label and redo label follow the stack; limit of 3 drops the oldest.
- `execute` during a transaction: `TransactionInProgress`. `updateTransaction` without `begin`: `IllegalStateException`.
- A failing update mid-drag keeps the last good state.
- Listener receives one state per change, in order.

JSON, schema, tools:
- The project JSON in 6.2 loads and re-encodes byte-identically (after key ordering is fixed by declaration order).
- `schemaVersion` 2: `UnsupportedVersion`. Missing: `Malformed`. A fake v0 to v1 migration runs via the `migrations` parameter.
- Unknown field in a command: `Malformed`.
- Golden schema files match for every tool in `ToolRegistry.all`; every property has a description; every `mcpV1` name matches `^[a-zA-Z0-9_-]{1,64}$`.
- `ToolDispatcher`: `split` on a session and on a preview; unknown tool; `export` with a null service; `get_transcript` with a fake service over a 2x clip and a trimmed clip, checking mapped times.

## 9. Extension points (not in v1)

- Keyframes: replace scalar `Transform` fields and `volume` with an animated-value type; commands and the schema version change, the executor structure does not.
- Transitions: an overlap between neighbours owned by a transition object; relaxes `NO_OVERLAP` for that pair only.
- Effects: `effects: List<Effect>` on `MediaClip`.
- Markers in the project (`AddMarker` / `RemoveMarker`); today they are UI state passed to `Snapping`.
- Still-image assets (unbounded source duration), track mute/lock/hide, `RemoveAsset`, linked audio/video clips, non-ripple "lift" cuts, a persisted command log.

Each of these adds a schema version and a `Migration`; none requires changing the execute, session or snapshot design.

## Amendment (2026-11-13): START-edge trim rounding

Exception to "every timeline→source conversion floors": a START-edge trim computes the source delta by rounding **toward zero** instead of flooring, so a trim and its exact inverse cancel at any speed. Example: at speed 3.33, flooring gave +3 µs forward and −4 µs back, leaving sourceIn at −1 µs. `sourceIn` is still clamped to be ≥ 0 and the asset-range check is unchanged. The regression test is `CommandExamplesTest` (Trim round-trip at speeds 1.0, 3.33, 0.5), and property P13 asserts `sourceInUs` is restored exactly.
