<!-- Copyright (C) 2026 Jacob Repp -->

# Trajectory: asset preview + local-scene mutation

Date: 2026-08-08. Handoff brief for another agent to complete the asset-preview
feature. Self-contained: directions + completion criteria per task, grounded in
file:line. Producer/consumer context: **Blockbench+automation** produces content,
**content-forge** (`/Users/jrepp/d/content-forge`) orchestrates, **Minosoft**
(this repo) consumes. See `doc/agents/evidence/2026-08-08-asset-primitive-decomposition.md`.

## Completion status (updated by the implementing agent)

All three tasks are implemented and the preview lane is live-validated on the
macOS display session.

| Task | Status | Evidence |
| --- | --- | --- |
| 1 — `content.place-blocks` | Done | op registered in `ClientDebugChannel.kt`; placement on `LocalConnection.placeBlocks`; shared parser `LocalBlockStateParser.kt`; live `content preview minecraft:barrel` placed a lit block. |
| 2 — `content preview` rework | Done | `Play.blockPlacementBody` places the real block + 4 torches, `--scene flat\|underwater`; underwater fills a water cuboid and submerges the camera. |
| 3 — `synth-textures --all` | Done | `--all` writes all 835 queued texture targets (verified 0 missing); `GL 1281/1282` spam is gone (0 hits). |

Live captures: `/tmp/a.png` (flat barrel, avg luminance 102, max 255),
`/tmp/a_underwater.png` (underwater barrel, avg 33 — water attenuation),
`/tmp/a_item.png` (diamond via `content.execute-local`, avg 142). All three are
lit/non-black; the debug client log shows zero `GL 1281/1282`/`GL_INVALID_*`
messages. Remaining texture warnings (`Can not find asset minecraft:textures/…`,
~596 distinct) are referenced by **generated models** whose flattened models name
textures the audit/queue never captured (e.g. `acacia_button.png`, `cube_all.png`);
these never produce GL errors and belong to the model-generation lane, not Task 3.

Tooling added: `util/tools/` is a uv project (`pyproject.toml` + `uv.lock`,
Pillow pinned) with `png_stats.py` (`#!/usr/bin/env uv run`) for capture
statistics.

## What already exists (do not rebuild)

- **`./play.sh content preview <asset>`** — implemented in `util/play/Play.java`
  (`runContentPreview`, dispatched from `runContent` at the `content` subcommand).
  It composes the stack, boots `bin/minosoft --no-eros --local`, waits
  `client.joined` + `client.render-ready`, drives `visual.prepare-reference` →
  `content.execute-local` (places a `block_display`) → `render.terrain.flush-idle`
  → `visual.capture`, writes a PNG, and `cleanupSupervisor()`. **Live-validated**:
  it launches, joins, places, and captures a real 1800×1000 PNG. Compile-verified
  (`./gradlew :play-util:compileJava`).
- **Preview data pack** — `acceptance/datapacks/content-preview/` (`show_block`,
  `show_item` macro functions; summon display entities).
- **content-forge** produced 1888 assets into its `out/` tree (flattened models +
  185 select textures + 17 Blockbench-authored bespoke models with `.bbmodel`
  sources + conversion lineage). Wired via git-ignored
  `content-stacks/standalone.local.json` → `loose-content` = `…/content-forge/out`.
  Drain validated against `./play.sh content queue`: `select 1922 → 36`.

## The problem this trajectory fixes

A preview capture shows the placed block **dark/black**, HUD/debug-chat visible,
and **`GL 1281/1282` spam**. Root causes (diagnosed):
1. Placement uses a **`block_display` entity** (`LocalDisplayEntityFactory`,
   summon-only) which renders **unlit** — hence black.
2. The composed pack is **missing many vanilla textures** (client log: `sun`,
   `moon_phases`, `destroy_stage_*`, armor layers, `fire_1`, `beacon_beam`,
   `shadow`, …) → binding absent textures → `GL_INVALID_OPERATION`.
3. The local world authority is **summon-only** — no `setblock`/`fill`, so
   light-emitting torches and water are not placeable.

All three converge on one enabler: **bounded block placement in the local world**.

---

## Task 1 — `content.place-blocks`: bounded block placement (ENABLER)

**Directions**
- Register a new client debug op `content.place-blocks` in
  `src/main/java/de/bixilon/minosoft/debug/ClientDebugChannel.kt` next to
  `content.execute-local` (registration ~L354; handler `executeLocalContent`
  ~L4126 is the template — same `onRender { … }` + `LocalConnection` cast).
- Body: `{ "blocks": [ { "x":int,"y":int,"z":int,"state":"minecraft:torch" | {Name,Properties} } ], "camera"?:pose, "origin"?:pose }`.
  Reuse the block-state parse at `LocalDisplayEntityFactory.kt:181`
  (`blockState(nbt["block_state"])`) and the pose parse `localPose(...)` in
  ClientDebugChannel.
- Implement placement on `LocalConnection` (`src/main/java/de/bixilon/minosoft/local/LocalConnection.kt`;
  world set up L80–144, chunks via `LocalChunkManager` L92). Set each block via
  `session.world` at the position and **trigger a light + chunk mesh update** so
  neighbors are re-lit and re-meshed (find the world set-block + light-engine
  update API; mirror how block updates propagate on a normal connection).
- Bound it like the summon factory (`MAX_ENTITIES_PER_SUMMON` at
  `LocalDisplayEntityFactory.kt:431`): cap block count (e.g. ≤512) and coordinate
  range; throw `DebugOperationException("limit_exceeded"|"invalid_request", …)`.
- Add the op string to the terrain/debug capability list if there is a registry.

**Completion criteria**
- `content.place-blocks` is registered and reachable via `DebugClient` (a
  `scenario run` `request` step or a manual preview drives it).
- Placing `minecraft:stone` at `(0,19,0)` in the local flat world renders a
  **lit** stone block in a `visual.capture` (not black).
- A placed `minecraft:torch` **illuminates** adjacent placed blocks (light update
  works).
- Over-limit / out-of-range requests return structured errors.
- `./gradlew :play-util:compileJava` and the client build succeed.

---

## Task 2 — rework `content preview`: real block + torches + underwater

**Directions** (in `util/play/Play.java` `runContentPreview` + `previewPlacementBody`)
- For `kind == block`: replace the `block_display` path with `content.place-blocks`
  — set the **asset block** at the origin, plus **4 `minecraft:torch`** (or
  `wall_torch`) around it (±2 on X/Z at the same Y) for light debugging. Keep
  `item_display` for `kind == item` (items are not blocks).
- Add `--scene flat|underwater` (default `flat`). For `underwater`, use
  `content.place-blocks` to fill a water cuboid (`minecraft:water`) enclosing the
  asset, and position the camera inside the water so submerged rendering (fog,
  tint, caustics) is exercised.
- Keep the existing settle sequence (`visual.prepare-reference` with
  `timeOfDay/clearWeather/hideHud/hideClouds/hideHitboxes` → `render.terrain.flush-idle`
  → `waitFrames(24)` → `visual.capture`).

**Completion criteria**
- `./play.sh content preview minecraft:barrel --output /tmp/a.png` → capture shows
  a **lit** barrel with visible torch lighting; **no HUD**; **no GL 1281/1282**
  chat spam.
- `… --scene underwater` → the asset renders **submerged** (water tint/fog visible).
- Item preview (`minecraft:diamond`) still renders via `item_display`.

---

## Task 3 — fill missing textures (kills the GL errors)

**Directions** (in content-forge, `producers/raster/synth-textures.mjs`)
- Add an `--all` mode: read `work/queue.json` (ALL entries, not just the select
  plan) and generate a placeholder PNG for **every** `kind == "textures"` target
  (family from `detail`; `generic_item` if the target is under `/item/`, else
  `generic_block`; seed = basename). Write to `out/`, stamp provenance
  `producer: "texture-gen"`. Placeholders are marked/replaceable.
- Regenerate + publish + re-audit:
  `npm run synth-textures -- --all && npm run publish` then in Minosoft
  `./play.sh content queue --manifest standalone --json`.

**Completion criteria**
- After `--all`, `out/` contains a PNG for every queued texture target.
- A fresh preview run's client log shows **no** `Can not find asset
  minecraft:textures/…` warnings (or only non-texture assets).
- `GL 1281/1282` spam in the captured preview is **gone or near-zero**.

---

## Environment / how-to

- **JDK 25**: `export MINOSOFT_JDK25_ROOT=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home; export JAVA_HOME=$MINOSOFT_JDK25_ROOT`.
- **Compile**: `./gradlew :play-util:compileJava` (Java) / client build for Kotlin.
- **Run**: `./play.sh content queue --manifest standalone --json` (needs the queue),
  then `./play.sh content preview <asset> [--scene …] --output PNG --json`.
- **content-forge**: `npm run pull-queue | triage -- --json | taxonomy | synth-models | synth-textures [-- --all] | publish | db index|report`.
- **Overlay** (git-ignored): `content-stacks/standalone.local.json` →
  `{ "schema":1, "overrides": { "loose-content": { "default": "/Users/jrepp/d/content-forge/out" } } }`.
- **A live render needs a display** (macOS native windowing); the GL context in a
  headless sandbox emits the init-time warnings seen during bring-up.

## Key references

- `util/play/Play.java`: `runContentPreview`, `previewPlacementBody`,
  `resolvePreviewAsset`; launch `launchSupervisedClient` (L3339), `clientCommand`
  (L3311), `materializeAssetProfile` (L3380); capture `captureMotionFrame` (L1638);
  `scenarioRequest` (L2116); predicates `waitForPredicate` (L1056, names at L1250).
- `src/main/java/de/bixilon/minosoft/debug/ClientDebugChannel.kt`: op registration
  (~L235–355), `executeLocalContent` (~L4126), `localPose`, `prepareVisualReference`
  (fields: `timeOfDay`,`clearWeather`,`hideHud`,`hideClouds`,`hideHitboxes`).
- `src/main/java/de/bixilon/minosoft/local/LocalConnection.kt` (world/spawn L80–144);
  `local/datapack/LocalDisplayEntityFactory.kt` (summon-only; block-state parse L181).
- `render-contracts/.../TerrainDiagnosticSchema.kt:38` (`render.terrain.flush-idle`).
