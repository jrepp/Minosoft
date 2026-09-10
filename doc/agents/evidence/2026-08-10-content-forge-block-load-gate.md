<!-- Copyright (C) 2026 Jacob Repp -->

# Trajectory: headless block-load gate + reliable content preview

Date: 2026-08-10. Handoff brief for the **Minosoft-code** session. Self-contained:
directions + measurable closure per task, grounded in `file:line`. Producer/consumer
context: **Blockbench+automation** produces content, **content-forge**
(`/Users/jrepp/d/content-forge`) orchestrates, **Minosoft** (this repo) consumes.
See `2026-08-08-asset-preview-trajectory.md` and `2026-08-10-offline-integration-assets.md`.

## Why this exists (the triggering session)

The content-forge blockstate lane (`producers/model/blockstates.mjs`, doc:
`content-forge/docs/blockstate-patterns.md`) was expanded from 6 → 11 multipart
patterns (fence/wall connections, stairs inner/outer corners, door
`facing×open×hinge×half`,
trapdoor `half/open`, button/lever `face×facing`, plus new `producers/model/shapes/`
helper geometry). We wanted to prove the **compiled + assembled** assets *load* in
Minosoft. What we could and could not do headlessly is the whole reason for this
trajectory:

**Observed (this session, wire already in place — `content-stacks/standalone.local.json`
→ `/Users/jrepp/d/content-forge/out`):**
- `./play.sh content compose --manifest standalone` → **17,096 files / 12 sources**
  assembled cleanly (loose-content merged).
- `./play.sh content queue --manifest standalone` → **all 966 blockstates + their
  block models `resolved`**; the only residual `generate` (264) are
  `assets/minecraft/models/item/*.json` — inventory icons, a different lane.
- `./play.sh content preview minecraft:oak_fence` → real `BlockLoader` parsed the
  blockstate + models and rendered a local-world block, **exit 0**, PNG written.

**The two gaps this trajectory closes:**
1. **No headless semantic block-load gate.** `ContentForgeAssetsIT`
   (`src/integration-test/kotlin/de/bixilon/minosoft/assets/ContentForgeAssetsIT.kt`)
   validates provenance + parses every JSON/PNG, but it never runs `BlockLoader`, so
   it does **not** prove blockstate→model→bake actually resolves and bakes. The only
   thing that exercises the real loader is the GUI `content preview` — one block per
   process launch. content-forge has no scriptable, GUI-free "the assembled blocks
   load" check.
2. **`content preview` captures before the world settles.** The preview run logged
   *"Terrain did not reach the preview idle boundary"* and screenshotted anyway, so
   the shot was poorly framed (the fence post was barely identifiable). The settle
   boundary is hardcoded and not tunable from the CLI.

## What already exists (do not rebuild)

- **`ContentForgeAssetsIT`** — `@Test(groups=["assets","external-assets"])`,
  skips unless `MINOSOFT_CONTENT_FORGE_ROOT` is set (`OfflineTestAssets.kt:23,39`,
  env `MINOSOFT_CONTENT_FORGE_ROOT`). Validates provenance targets exist + SHA-256
  match, parses all JSON/`.mcmeta`, opens every PNG header, requires non-empty
  blockstate/model/texture/blockbench lanes. **Structural only — no `BlockLoader`.**
- **`BlockLoader`** — `src/main/java/.../gui/rendering/models/loader/BlockLoader.kt`:
  `loadBlock` (`:44`) resolves `block/<name>.json` incl. `parent`; `loadState`
  (`:62`) reads `blockstates/<name>.json` → `DirectBlockModel`; `load` (`:80`)
  iterates registry blocks; `bake` (`:96`) bakes. Reads through
  `loader.context.session.assets` (`:41`).
- **`ModelTestUtil`** — `src/integration-test/kotlin/.../models/ModelTestUtil.kt`:
  `createLoader()` (`:43`) reflectively allocates a `ModelLoader`; `createAssets()`
  (`:52`) injects a `MemoryAssetsManager` (`:53`). Today it is fed **in-memory JSON
  strings**, not an on-disk pack — this is the extension point.
- **`RenderTestLoader`** — boots the dummy renderer against
  `MINOSOFT_CONTENT_FORGE_ROOT` output and asserts two focused models. Precedent for
  mounting the content-forge dir under `DummyWindow`/`DummyRenderSystem`.
- **`content preview`** — `Play.java:445` (`runContentPreview`), dispatched at
  `:355`. Settle + capture at `:530-542`; usage string `:448`; `--distance`
  parsing `:473-487`.
- **Run harness** — TestNG `integrationTest`; JDK 25 required
  (`MINOSOFT_JDK25_ROOT=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`,
  or `JAVA_HOME` = same). macOS OpenGL runs need `-XstartOnFirstThread` /
  `MINOSOFT_OPENGL_TESSELLATION_TEST=true` (see `doc/contributing/TerrainTesting.md`).

## Completion status (updated by the implementing agent)

| Task | Status | Evidence |
| --- | --- | --- |
| 1 — Headless block-load gate (`ContentForgeBlockLoadIT`) | **Complete** | Java 25: 966 loaded + baked, 0 failures |
| 2 — Reliable `content preview` (settle + framing) | **Minosoft complete; producer wall limitation remains open** | strict sculpture captures show connected oak fence rails, real stair corners, and lower/upper door geometry with genuine upper cut-outs on one plank material |
| 3 — Item-model lane decision (optional) | **Decision recorded** | retain the consumer expectation; add trivial item parents in the producer lane |

---

## Captured Java Edition blockstate-definition contract

The common vanilla forms described by the
[Java Edition blockstates definition](https://minecraft.wiki/w/Blockstates_definition)
are mapped below and exercised in `BlockstateDefinitionSpecIT`. This is a format
contract, not a catalog of every block's registry properties. Forge/NeoForge custom
definition types and loaders are outside this vanilla boundary.

| Surface | Covered form |
| --- | --- |
| Root dispatch | `variants` object or `multipart` array tested independently. Rejection of both forms together is not covered; the current parser prefers `variants`. |
| Variant selector | A comma-separated `property=value` tuple; `""` is the empty selector. Properties irrelevant to model selection may be omitted. |
| Variant value | One configured-model object, or a non-empty array of configured-model objects for weighted random selection. |
| Configured model | Required `model` resource location; optional `x` and `y` rotations in 90-degree steps; optional `uvlock` boolean (default false); optional positive `weight` in arrays (default 1). |
| Multipart entry | Required `apply`, plus optional `when`; absent `when` is unconditional. `apply` has the same single/weighted forms as a variant value. |
| Direct multipart condition | Property entries are ANDed. One property may accept alternatives separated with `\|`. Values are the literal values of that block's Java state property. |
| Grouped multipart condition | `OR` and `AND` contain direct property-condition arrays. Arbitrary nesting is not covered. |
| Resolution | Blockstates live at `assets/<namespace>/blockstates/<path>.json`; `model` resolves below `assets/<namespace>/models/`, including model-parent resolution before bake. |

`BlockstateDefinitionSpecIT` covers both root forms, empty and partial selectors,
single and weighted values, all configured-model fields, unconditional multipart,
implicit AND, pipe alternatives, flat explicit `OR`/`AND`, and the corresponding
selection behavior. `ContentForgeBlockLoadIT` then applies that implementation to
the real assembled pack. The parser's `AndCondition` list decoder currently accepts only direct property terms; nested logical groups remain an unverified compatibility gap.

The visual wall check found a concrete producer violation of the literal-value row:
the 1.20.4 registry exposes wall sides as `none|low|tall`, but content-forge currently
emits `north/east/south/west == "true"`. Minosoft intentionally does not reinterpret
`true` as a wildcard for `low|tall`; the producer must emit `"low|tall"`. This is why
all wall models can resolve and bake while their side applies remain unreachable in
the live modern state.

## Task 1 — Headless block-load gate (the priority)

**Goal.** A GUI-free integration test that mounts a content-forge output tree and
runs the **real** `BlockLoader` across the block set, asserting every blockstate
loads, every referenced model resolves + bakes, with **zero** parse/bake/dangling
failures. This is the scriptable gate content-forge can run in its loop instead of
per-block GUI previews.

**Directions.**
- Add `src/integration-test/kotlin/de/bixilon/minosoft/assets/ContentForgeBlockLoadIT.kt`
  (group `external-assets`), skipping unless `MINOSOFT_CONTENT_FORGE_ROOT` is set —
  mirror the skip/`OfflineTestAssets.contentForgeRoot()` pattern
  (`ContentForgeAssetsIT.kt` top; `OfflineTestAssets.kt:39`).
- Mount the on-disk tree as an `AssetsManager` instead of `MemoryAssetsManager`. Two
  viable seams — pick the one that compiles cleanly under the dummy renderer:
  (a) generalize `ModelTestUtil.createAssets` (`ModelTestUtil.kt:52`) to accept a
  directory-backed manager, or (b) follow `RenderTestLoader`'s existing mount of
  `MINOSOFT_CONTENT_FORGE_ROOT` and hand its assets to a reflectively-built
  `BlockLoader` (`ModelTestUtil.createLoader`, `:43`).
- Drive `BlockLoader.load` (`:80`) + `BlockLoader.bake` (`:96`) — OR, to avoid the
  full registry dependency, iterate the blockstate files under
  `assets/minecraft/blockstates/` and call `loadState`/`loadBlock` (`:62`/`:44`) per
  target. Collect (not fail-fast) every failure into a list.
- Assert the failure list is empty; on failure print `blockstate -> reason` so
  content-forge sees exactly which pattern broke.

**Closure (measurable).** With `MINOSOFT_CONTENT_FORGE_ROOT=/Users/jrepp/d/content-forge/out`,
`./gradlew integrationTest --tests …ContentForgeBlockLoadIT` **passes**: all **966**
blockstates load and every referenced block model (incl. the new `_inner`/`_outer`/
`_side`/`_top`/`_double` helpers) bakes, **0** failures. Deliberately out of scope:
`models/item/*` (Task 3) and per-instance block-entity rotation (banner/sign/head are
single-part by design — see `content-forge/docs/blockstate-patterns.md`). Without the
env var the test skips (ordinary suite unchanged).

**Verification hooks the producer already gives you.** The content-forge lane self-
checks `0 dangling blockstate model refs`; this gate is the consumer-side counterpart
(refs not just present but *loadable + bakeable*). A useful cross-check: the
`content queue` audit already flips all 966 blockstate/model targets to `resolved`.

**Implemented evidence.** `OfflineTestAssets.createContentForge` gives the external
directory priority over the deterministic stand-in. `ContentForgeBlockLoadIT` walks
the bounded, non-symlink blockstate set, loads through the real `BlockLoader`, bakes
every referenced apply and every selectable registry state with dummy textures,
collects all errors, and audits missing blockstate/model resources. On Java 25.0.4
with the current output it printed: `loaded and baked 966 blockstates with 0 failures`.

## Task 2 — Reliable `content preview` (settle + framing)

**Goal.** `content preview <block>` produces a correctly-framed, fully-settled shot
so it is usable to *visually* verify a pattern (e.g. see the fence's 4 connection
arms, the stairs corner), instead of capturing mid-initialization.

**Directions.**
- The settle boundary is hardcoded at `Play.java:530-538`:
  `render.terrain.flush-idle` with `timeoutMs=10_000` (request timeout `15_000`),
  then `waitFrames(client, 24, 20_000)`, then capture (`:540`). On flush failure it
  only warns (`:536`) and shoots anyway.
- Add CLI knobs to `runContentPreview` (parse loop near `:473-487`, update usage
  `:448`): `--settle <ms>` (flush `timeoutMs`) and/or `--settle-frames <n>` (replace
  the literal `24`). Consider making a failed `flush-idle` **fail the command** under
  a `--strict` flag rather than silently capturing.
- Framing: block previews came out small/off-center. Check `blockPlacementBody`
  (referenced `Play.java:526`) camera/target vs `--distance` (`:473-487`); ensure the
  placed block is centered and near enough to read its geometry. Reuse the flat-scene
  camera precedent from `2026-08-08-asset-preview-trajectory.md`.

**Closure (measurable).** `./play.sh content preview minecraft:oak_fence --manifest
standalone --scene flat --settle 30000 --output /tmp/oak_fence.png` completes with
**no** "did not reach the preview idle boundary" warning, and the PNG clearly shows a
centered fence **with connection arms**. Re-run for `minecraft:oak_stairs` (corner
visible) and `minecraft:cobblestone_wall` (arms visible). Capture stats (non-black,
reasonable luminance) via `util/tools/png_stats.py` as in the prior trajectory.

**Implemented evidence.** The CLI now bounds `--settle` to the debug protocol's
30,000 ms maximum, supports `--settle-frames` (0..600), and makes the idle failure
fatal under `--strict`. Preview launches suppress the managed shader pack for a
deterministic authored-content reference, and `visual.prepare-reference` suppresses
HUD, transient entities/particles, and the first-person arm. The camera is aimed
mathematically at the block center from a closer three-quarter pose. Connected block
preview states are explicit, and the modern registry now retains fence and wall
direction properties rather than discarding them during PixLyzer ingestion.
The command repeats `visual.prepare-reference` at the capture boundary because the
settle/debug round trips can reopen the pause screen on focus-loss hosts, then reapplies
the real-block placement body to restore the deterministic camera pose after mouse reacquisition.
A sculpture replaces final cell values in one pass, including air for every unused
slot and both possible door-context halves. Replaying an unchanged page is
mutation-free at `World.set`; page order is independent for a fixed block/page size.
Item previews keep entity rendering enabled and retain the settled display entity
instead of rerunning its spawning function at the capture boundary.

Flat block previews now use `content.place-block-state-sculpture` and
`BlockStateSculptureCatalog` rather than a family-specific sample list. The active
version registry is authoritative: every legal state is sorted deterministically,
split into bounded pages (16 states by default, 64 maximum), and placed as an isolated
vertical contact sheet. Ordinary states use two-block spacing; vertical double-size
blocks use three-block rows and receive a matching opposite-half context block. A
complete `--state-page all` run proves
coverage through the ordered page union; `--state-page N` provides a fast focused
review. Underwater previews intentionally remain a single-block material test.

The state export uses Java resource-pack values, not Minosoft's internal normalized
enums: fence/pane booleans are `false|true`, stairs/trapdoors use `bottom|top`, and
doors/double plants retain `lower|upper`. Waterlogged states are ordered after their
dry counterparts so the fluid environment cannot hide the primary material page.
They remain in the catalog and capture set rather than being silently excluded.

Every page writes a sibling JSON manifest with exact state keys and positions,
one-based and zero-based page identifiers, total state/page counts, grid dimensions,
catalog SHA-256, PNG SHA-256, content-stack fingerprint, and settle controls. A
`*.capture-set.json` index records the selected page range and all page artifacts.
These are `.run/` review artifacts, not committed generated output.

Live Java 25 strict captures validated both dispatch forms at 1800×1000 with no
idle warning or transient UI. They also proved that structural load/bake success is
not visual fidelity: the first oak-stairs/fence captures used conspicuous diagnostic
textures, and the current `8ea5ba9fb3f5` composition still contains stand-in model
geometry even after the producer's texture exemplars became less conspicuous.

The first strict sculpture audit exposed stale generated helpers: fence arms and
stair inner/outer models had been overwritten by the general instantiator as
`cube_all`, while every helper name acquired an unrelated synthesized texture. The
producer fix routes helper suffixes explicitly, loads its durable helper-shape library
in both model passes, permits replacement of stale generated cube helpers, fails on a
missing routed shape, and maps derived wood geometry to the shared `<wood>_planks`
material.

Current strict sculpture captures after producer regeneration in
`.run/previews/block-state-evaluation/` (1800×1000, no idle warning, process cleaned
up after each):

- `oak-slab-sculpture.png` is the valid control: real oak-plank material and distinct
  bottom, top, and double geometry supplied by the higher-priority authored pack;
- `oak-fence-art-page-1.png` shows all 16 dry connection combinations with real
  posts and upper/lower rails; the second page covers the 16 waterlogged aliases;
- `oak-stairs-art-page-1.png` begins a five-page / 80-state set and visibly contains
  straight, inner, and outer geometry across bottom/top states;
- `oak-door-complete-states-001-of-004.png` begins a four-page / 64-state set. Every
  catalog state is shown as a complete two-block door using the shared oak-plank
  material, recessed lower panels, and a rail-and-stile upper half with two genuine
  cut-outs. The full capture set records 64 catalog states plus 64 matching context
  halves, all four facings, both hinge directions, and open/closed states;
- `cobblestone-wall-sculpture.png` renders posts only because the producer's
  connection predicates use boolean `"true"` while modern wall sides are
  `none|low|tall`.

The normal producer pipeline regenerated `out/`; it was not hand-edited and remains
an ignored artifact. The Java 25 `ContentForgeBlockLoadIT` then loaded and baked all
966 blockstates with zero failures. Minosoft's resource-id resolver also keeps known
sculpture families on the block path even when the only actionable queue entry is
their residual item-model gap (the failure exposed by `minecraft:oak_slab`).

## Task 3 — Item-model lane decision (optional, cross-repo)

**Goal.** Resolve the residual 264 `generate` = `assets/minecraft/models/item/*.json`
(inventory icons for generated blocks). Not a block-render gap.

**Directions / decision to record.** Either (a) the consumer audit should **not**
demand item models for content-forge-generated blocks (relax the item-model
expectation in the audit that emits these queue entries), or (b) content-forge grows
an item-model lane (most generated blocks want a trivial
`{"parent":"minecraft:block/<name>"}` item model). Decide and write the rationale
here; if (b), it is producer work in content-forge, not this repo.

**Decision.** Choose **(b)**. Keep the consumer expectation because generated blocks
are inventory-visible content and should have deterministic icons. The producer lane
should emit the ordinary trivial parent (`minecraft:block/<name>`) when no dedicated
item presentation is needed. The 264 current `generate` entries remain explicitly
producer-side work; this Minosoft change does not fabricate or commit them.

**Closure.** `content queue --manifest standalone` reports the intended `generate`
count for item models (0 if relaxed, or the produced set resolved), with the decision
recorded in this doc.

## Validation (copy/paste)

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export MINOSOFT_CONTENT_FORGE_ROOT=/Users/jrepp/d/content-forge/out

# Task 1 — headless block-load gate (0 failures over 966 blockstates)
./gradlew integrationTest --tests de.bixilon.minosoft.assets.ContentForgeBlockLoadIT

# Regression: structural gate still green
./gradlew integrationTest --tests de.bixilon.minosoft.assets.ContentForgeAssetsIT

# Common vanilla blockstate-definition forms + exhaustive registry-state paging
./gradlew integrationTest \
  --tests de.bixilon.minosoft.gui.rendering.models.BlockstateDefinitionSpecIT \
  --tests de.bixilon.minosoft.data.registries.blocks.state.ConnectedBlockStateIT \
  --tests de.bixilon.minosoft.debug.content.BlockStateSculptureCatalogIT

# Task 2 — reliable, well-framed preview (no idle-boundary warning)
./play.sh content preview minecraft:oak_fence --manifest standalone --scene flat --state-page all --states-per-page 16 --settle 30000 --settle-frames 36 --strict --output /tmp/oak_fence.png
./play.sh content preview minecraft:oak_stairs --manifest standalone --scene flat --state-page all --states-per-page 16 --settle 30000 --settle-frames 36 --strict --output /tmp/oak_stairs.png
./play.sh content preview minecraft:cobblestone_wall --manifest standalone --scene flat --state-page all --states-per-page 16 --settle 30000 --settle-frames 36 --strict --output /tmp/cobblestone_wall.png

uv run --project util/tools util/tools/png_stats.py /tmp/oak_fence.png
uv run --project util/tools util/tools/png_stats.py /tmp/oak_stairs.png
uv run --project util/tools util/tools/png_stats.py /tmp/cobblestone_wall.png
```

## Boundaries (intentional)

- Standing banner/sign/head blockstates are **single-part on purpose** (fine yaw is
  block-entity work). A load gate must not flag them as "missing rotation."
- `MINOSOFT_CONTENT_FORGE_ROOT` stays **explicit**: unset ⇒ external tests skip and
  the checked-in stand-in stays deterministic (`2026-08-10-offline-integration-assets.md`).
- Content-forge's `out/**` is a gitignored, deterministic artifact; regenerate it
  (`node producers/model/blockstates.mjs`, then `npm run publish` for `provenance.json`)
  — do not commit it, and do not edit content-forge from this session beyond
  regeneration.
