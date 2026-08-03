<!--
  Minosoft
  Copyright (C) 2026 Jacob Repp

  This program is free software: you can redistribute it and/or modify it under
  the terms of the GNU General Public License as published by the Free Software
  Foundation, either version 3 of the License, or (at your option) any later
  version.

  This program is distributed in the hope that it will be useful, but WITHOUT
  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
  FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along with
  this program. If not, see <https://www.gnu.org/licenses/>.
-->

# Recent terrain and visual root-cause test plan

## Status and objective

This is **Target** guidance for closing the two independent defects retained by
the [August 1 trajectory summary](../evidence/2026-08-01-recent-trajectory-summary.md):

1. Distant Horizons draws coarse, page-aligned foreground slabs across ready
   near terrain when Iris is disabled.
2. Iris with the managed Complementary pack produces block-aligned/stippled
   corruption and can wedge the Apple render thread even when DH is disabled.

The plan uses existing dependency-clean tests, the exact external-pack planner
gate, the repository scenario runner, trajectory leases, diagnosis bundles,
pose checkpoints, and version-one debug operations. Add only the smallest
missing diagnostic needed to distinguish an owner. Do not create a second
automation transport or treat a clean counter ledger as visual acceptance.

The qualified completion target remains Java 25, Minecraft 1.20.4, the managed
`diverse-medium-biomes-2026-08-01` world, and Apple M4 Max OpenGL 4.1. A second
driver remains portability follow-up rather than a blocker for these two fixes.

## Isolation matrix

Run each cell independently at the same restored pose. Do not diagnose the
combined cell until both single-owner cells pass.

| Iris | DH | Purpose | Required result |
| --- | --- | --- | --- |
| disabled | disabled | Built-in control | Responsive, ordinary near terrain |
| disabled | enabled | DH ownership reproduction | Currently shows slabs; final candidate must preserve the near foreground while still drawing distant terrain |
| enabled | disabled | Iris reproduction | Currently corrupts and may wedge; final candidate must render a stable checked scene and remain responsive |
| enabled | enabled | Final composition regression | Run only after both preceding cells pass; neither artifact may return |

This matrix preserves the summary's distinction: an Iris result says nothing
about the DH masking defect, and a DH result says nothing about Iris fullscreen
or material state.

## Repository scaffolds

| Need | Existing scaffold | How this plan uses it |
| --- | --- | --- |
| Headless coverage lifecycle and mask | `TerrainCoverageMaskingTest`, `TerrainCoverageTest`, `NearSurfaceCoverageTest` | Reproduce partial coarse-page overlap, transition age, replacement, epoch, and negative-coordinate cases before touching OpenGL |
| Bounded distant hierarchy selection | `DistantPageSelectorTest`, `DistantPageHierarchyTest` | Prove coverage-driven refinement stays balanced, bounded, deterministic, and hole-free |
| Exact Complementary planning | `IrisShaderPackPlannerTest` with `MINOSOFT_IRIS_TEST_PACK` and `MINOSOFT_IRIS_TEST_OPTIONS` | Keep the exact hash-pinned external archive out of the repository while testing its real plan and managed options |
| Iris target/frame contracts | `IrisOpenGlRenderTargetsTest`, `IrisFrameStateTest`, `IrisFrameSmoothingTest` | Pin initialization, ping-pong, clear, depth-copy, sampler mode, viewport/framebuffer restoration, and uniforms with synthetic Minosoft-authored fixtures |
| Real OpenGL test shape | `OpenGlIrisCustomResourceComputeTest` | Reuse hidden GLFW/context negotiation and typed GPU accounting for an opt-in OpenGL 4.1 target/pass fixture; do not require compute on Apple |
| Live state and pixels | `visual.prepare-reference`, `visual.capture`, `visual.sample`, `world.blocks.sample`, `render.substrate` | Rule out GUI, pose, embedding, fixture, and producer causes before renderer changes |
| Atomic terrain state | `render.terrain.flush-idle`, `render.terrain.summary`, `render.terrain.pages`, `render.terrain.coverage`, `mods.distanthorizons.render-diagnostics` | Correlate the frame-pinned near snapshot with selected and masked distant pages only at settled boundaries |
| Repeatable live checks | `play.sh scenario run` and existing terrain scenarios | Add focused same-pose DH and Iris cases, then reuse movement, reload, reconnect, dimension, seam, and soak regressions |
| Ownership and restoration | `lease`, `diagnose capture`, `checkpoint capture/mark/restore` | Bound client/server mutations, retain pre-failure evidence, and compare-and-restore the player pose |

## Entry and recovery protocol

### Preflight

1. Use a Java 25 JDK and run `./play.sh status --json`. Refuse to mutate if an
   unexpected parent, client, server, external client, or endpoint is active.
2. Acquire `client` and shared `server-world` leases for
   `diverse-medium-biomes-2026-08-01`. Retain their returned tokens and use a TTL
   long enough for the named run; renew by releasing and reacquiring rather than
   assuming an expired lease still owns state.
3. Start the existing trajectory with `fabric-stack`; do not create, regenerate,
   inspect, or replace the dedicated-server world during these visual runs.
4. Through the Iris settings form, persist `enabled=false` in the trajectory
   option store and restart the client once. Do not hand-edit trajectory profile
   files. Verify `mods.iris.presentation` returns `enabled=false` and
   `installed=false` after restart. A debug-only disable is not this gate.
5. Verify the player is in the overworld at
   `32.6740054977249,79,-608.6999999880791`, yaw `115.75401`, pitch
   `4.5429916`, in creative mode. Capture a pose checkpoint before any movement.
6. Query `mods.distanthorizons.presentation` without changing it and require a
   null override plus the intended configured state. This gives `restore=true`
   one exact known state to restore.
7. Record the active fixture list. Ordinary `fabric-stack` must not contain an
   animated or checked-pixel overlay.
8. Capture one unmodified diagnosis bundle before `visual.prepare-reference`.
   Then prepare the reference, sample `client.player`, `client.world`, and a
   small loaded-only block box around and below the camera, and capture
   `render.substrate`.

Representative command shapes are:

```sh
./play.sh status --json
./play.sh lease acquire --scope client \
  --trajectory diverse-medium-biomes-2026-08-01 --ttl 2h
./play.sh lease acquire --scope server-world \
  --trajectory diverse-medium-biomes-2026-08-01 --ttl 2h
./play.sh dev --modpack fabric-stack \
  --trajectory diverse-medium-biomes-2026-08-01
./play.sh checkpoint capture \
  --trajectory diverse-medium-biomes-2026-08-01 \
  --output .run/checkpoints/diverse-medium-terrain-visual.json
./play.sh diagnose capture \
  --trajectory diverse-medium-biomes-2026-08-01 --visual --json
```

The launch is a supervised, long-running command; run the remaining commands
from another shell through the same compiled `play.sh` utility.

### Settled measurement boundary

Before every DH pixel judgment, require `render.terrain.flush-idle` with `ALL`
to report zero queued, outstanding, and active builds; zero completion depth;
zero pending uploads, submissions, and fences; and zero retired pages/bytes.
At the immediately following boundary, capture:

- the near coverage revision, lifecycle revision, state counts, and cells;
- `renderReadyNativeChunks`;
- selected main/shadow page counts and detail counts;
- `mainMaskedPages` and `shadowMaskedPages`;
- per-page main/shadow selected/masked state for the camera area;
- allocation, upload, build, and submission failures; and
- the prepared framebuffer.

Do not use a startup capture made while any queue or missing-page count is
draining. Use `metrics.snapshot` for repeated low-cost liveness polling and
`render.substrate` only at these boundaries.

### Iris-wedge recovery

Keep DH disabled throughout Iris diagnosis. Capture the built-in control and
all pre-enable diagnostics first. Give each Iris debug/capture request a bounded
deadline. If the endpoint stops answering:

1. record `./play.sh status --json` and preserve the last diagnosis/scenario
   artifacts;
2. do not queue further render-thread mutations into the wedged generation;
3. stop or let the supervisor replace only the client generation;
4. re-resolve the new endpoint and confirm the persistent Iris setting returned
   it to `enabled=false`; and
5. revalidate the pose, blocks, GUI, fixture list, and pipeline generation before
   continuing.

Do not raise managed-server generation pace, change the selected level, or
restart the shared server merely to recover the render thread.

## Lane A: DH near/distant ownership

### A1. Freeze the failing CPU contract

Add a focused `TerrainCoverageMaskingTest` case with one detail-2 distant page
spanning 16 base chunks: 15 settled `READY` near cells and one absent or
`UPLOAD_PENDING` cell. Record the current result explicitly:

- the distant page is partially overlapped;
- masking the entire page would create a hole at the missing cell; and
- drawing the entire page covers 15 chunks already owned by near terrain.

Cover detail levels 0, 1, and 2; negative X/Z; exact page boundaries; a wrong
world epoch; overflow/fail-open input; new coverage aging; retained coverage
during replacement; and retirement. A wrong epoch, unsupported span, or absent
children must remain fail-open rather than hide terrain.

The test should expose a three-way relationship—`NONE`, `PARTIAL`, or `FULL`
near coverage—for a distant page. A boolean `drawDistant` alone cannot express
the defect.

### A2. Prove the solution below OpenGL

Prefer coverage-driven hierarchy refinement over changing the distant mesh:

1. A `PARTIAL` selected page is recursively refined while complete child pages
   are available.
2. `FULL` children age through the existing deterministic transition and are
   then masked.
3. `NONE` children remain drawable.
4. If a required child is unavailable, retain the coarser page and report the
   unresolved partial overlap; never hide it and expose a hole.

Put the GUI-independent relationship/refinement contract in `render-contracts`.
The application runtime supplies one frame-pinned `TerrainCoverageSnapshot`.
Do not move world, LWJGL, OpenGL, Fabric, or Minecraft application types into
that module.

Extend `DistantPageSelectorTest` with:

- 15-ready/one-missing and checkerboard partial spans;
- a fully ready span that collapses to masked base coverage;
- a no-near-coverage span that remains coarse;
- incomplete child availability and conservative fallback;
- main and shadow selections using the same pinned coverage snapshot;
- maximum selection/page budgets and `maximumAdjacentDetailDelta <= 1`;
- deterministic repeated selection at the same revision; and
- coverage revision changes that trigger reselection without changing distant
  source or mesh revisions.

The production selection cache must include the spatial near-coverage revision.
Today `SelectionInput` includes distant `dataRevision` but not near coverage;
without this input, a fixed camera can retain a stale coarse selection while
near ownership evolves. Use spatial coverage revision for reselection and keep
lifecycle/transition age in the later mask decision so harmless state churn
does not rebuild the hierarchy.

### A3. Application and diagnostic gate

Add a focused application test around the production selection-to-draw-plan
boundary. Given a frame-pinned partial span, assert:

- no selected drawable distant page overlaps a settled ready near base chunk;
- every uncovered base chunk remains covered by one drawable distant page;
- main and shadow derive from the same near snapshot;
- coverage-only reselection does not rebuild semantic page artifacts; and
- selection, draw-plan, and diagnostic masked counts agree.

Existing `render.terrain.pages` visibility records plus
`render.substrate.terrain.nearCoverage.cells` are sufficient for the first live
correlation. Add a bounded diagnostic field only if the correlation cannot be
made atomically; if added, evolve the versioned terrain diagnostic schema and
its frozen JSON fixtures instead of silently changing schema version 1.

### A4. Live same-pose acceptance

Create `acceptance/scenarios/terrain-dh-near-mask-diverse-medium.json` from the
existing terrain scenario vocabulary. Its precondition is persistently disabled
Iris. It must:

1. prepare the reference and verify the authoritative pose and local blocks;
2. enable DH, flush to the complete idle boundary, and capture terrain summary,
   camera-area page records, DH diagnostics, and a foreground plus distant crop;
3. disable only DH and recapture at the exact pose;
4. retain both named scenario screenshots and compare the foreground crops with
   the standalone `play.sh screenshot compare` command; require a changed
   distant crop plus positive distant draw diagnostics, so the test cannot pass
   by silently disabling all DH output; and
5. call `mods.distanthorizons.presentation` with `restore=true`, verify the
   override is null and configured state is active again, and restore ordinary
   reference presentation.

Run the completed scenario a second time after a supervised client-generation
replacement; client replacement is a lifecycle action outside the JSON scenario
step vocabulary.

Accept the fix only when queues and missing pages are zero, no allocation,
upload, build, or submission failure occurred, the foreground slabs are absent,
and distant terrain remains visibly active. Retain the scenario JSON/JUnit,
diagnostics, and reviewed crops; do not promote `/tmp` captures directly into a
checked baseline.

## Lane B: Iris/Complementary corruption and wedge

### B1. Reconfirm the exact immutable plan

Resolve `fabric-stack` through `play.sh modpack inspect` and pass the reported
Complementary r5.8.1 archive path to the existing external planner test. Use the
complete managed option string from
`modpacks/fabric-stack/shaderpacks/complementary-unbound.pw.toml`, not a reduced
historical profile.

```sh
MINOSOFT_IRIS_TEST_PACK=/absolute/path/to/ComplementaryUnbound_r5.8.1.zip \
MINOSOFT_IRIS_TEST_OPTIONS='RP_MODE=3;SHADOW_QUALITY=1;CLOUD_QUALITY=3;ANISOTROPIC_FILTER=8;TAA_MODE=0;TAA_SMOOTHING=4;TAA_JITTER=0;FXAA_STRENGTH=85;FXAA_TAA_INTERACTION=0;WAVING_FOLIAGE=false;WAVING_LEAVES=false;WATER_ALPHA_MULT=180;WATER_FOG_MULT=50;WATER_BUMPINESS=1.50;UNDERWATERCOLOR_R=110;UNDERWATERCOLOR_G=120;UNDERWATERCOLOR_B=130' \
./gradlew test --tests \
  de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlannerTest
```

Record the plan's ordered stages, logical/physical targets, clear policies,
flips, outputs, raw samplers, comparison samplers, frame inputs, and shadow
size. The existing passing contracts for first-generation `LOAD` clears, depth
framebuffer restoration, sampler comparison mode, `frameTimeSmooth`, and
`shadowMapResolution` remain regression gates; do not assume any one is still
the cause merely because it was recently changed.

### B2. Close the synthetic target/pass gap

Extend the existing Iris tests with Minosoft-authored synthetic shaders and an
opt-in hidden OpenGL 4.1 fixture. Do not copy Complementary shader source into
the repository. The fixture must execute and read back a small deterministic
pattern through:

- first-frame `LOAD` initialization and a second frame that preserves history;
- alternate/main ping-pong reads and writes across a fullscreen sequence;
- color and depth clears for normalized, integer, main, shadow, and DH targets;
- main, pre-translucent, before-hand, and shadow depth blits;
- raw depth sampling followed by comparison sampling and the reverse order;
- framebuffer, draw/read binding, viewport, active texture, blend, depth, and
  cull state restoration after both success and injected failure; and
- target resize and close with balanced typed GPU names.

At every synthetic pass, validate that no physical texture is simultaneously a
sampled input and attached output unless the declared API explicitly permits
that access. A pass alias or stale flip must fail before driver submission.

### B3. Find the first bad live pass

With DH disabled and the built-in control already captured, enable Iris for one
bounded window. Capture `render.substrate` immediately before and after the
artifact. Correlate the first corrupt frame with ordered graph passes,
`fullscreenProgramExecutions`, program outputs/samplers/comparison samplers,
depth snapshots, frame inputs, selected/fallback/rejected binds, and GPU counts.

Use existing producer suppression and authored option controls first. If they
still cannot identify the first bad boundary, add one generation-scoped,
acceptance-only Iris pass cutoff through the existing `ModDebugProvider` and
shared `DebugClient`. It must:

- stop after an ordered scene/shadow/prepare/deferred/composite/final boundary
  and present the current color through a bounded identity presenter;
- support binary search within the first failing fullscreen family;
- return prior/current state plus a generation-scoped restoration token;
- reset automatically when its Fabric scope closes;
- expose the active cutoff in `render.substrate`; and
- appear in `core.capabilities`, with focused validation of endpoint cleanup.

This diagnostic is not a permanent alternate renderer. Once the first failing
pass is known, reduce its resource/state interaction to the synthetic fixture,
write a focused failing test, and fix the owning planner, target, frame-state,
or graph boundary. Do not add a Complementary-specific source rewrite unless
the reduced test proves a pack-authored ABI difference that the generic Iris
contract must adapt.

### B4. Iris acceptance

Create `acceptance/scenarios/iris-complementary-diverse-medium.json` with DH
explicitly disabled. The checked candidate must:

1. verify pose, blocks, fixture inventory, exact pack fingerprint, and managed
   options;
2. prepare the reference and wait for near queues to drain;
3. enable Iris and assert empty rejected/fallback maps plus expected fullscreen,
   terrain, hand, shadow, and final route advancement;
4. capture a reviewed, static-terrain crop that excludes sky, animated water,
   entities, and particles;
5. run a companion five-minute duration scenario whose complete repeated case
   uses idempotent Iris-enable, `metrics.snapshot`, and bounded `visual.sample`
   steps, with detailed `render.substrate` captured outside the loop only at the
   start and end; and
6. disable Iris, confirm the built-in path responds, and leave the persistent
   setting disabled until the scenario passes twice.

The first clean candidate capture is not enough to create a baseline. Review it
against the built-in control and the identified pass-level fix, then use
`--update-screenshots` once. A normal fresh-generation run must pass the checked
crop with documented thresholds. Replace the supervised client between the two
runs; client replacement is not a scenario step. Acceptance requires no
block-aligned or stippled corruption, no request timeout or wedged render
thread, no OpenGL fault, no fallback/rejection, stable live GPU counts after
retirement, and a responsive built-in path after disablement.

## Focused and broad verification order

Run the smallest gate after each change, then broaden:

```sh
./gradlew :render-contracts:test --tests \
  de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageMaskingTest
./gradlew :render-contracts:test --tests \
  de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageSelectorTest
./gradlew test --tests \
  de.bixilon.minosoft.gui.rendering.terrain.near.NearSurfaceCoverageTest
./gradlew test --tests \
  de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisOpenGlRenderTargetsTest
./gradlew test --tests \
  de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisFrameStateTest
./gradlew test --tests \
  de.bixilon.minosoft.architecture.TerrainArchitectureBoundaryTest
./gradlew :render-contracts:test
./gradlew test
./gradlew integrationTest
./gradlew assemble
```

Then run, in order:

1. the new DH same-pose scenario with Iris disabled;
2. `terrain-fast-seam-movement.json` and
   `terrain-seam-built-in-macos-retina.json`;
3. `terrain-distant-only.json`, `terrain-distant-database-cold-load.json`,
   `terrain-dimension-transitions.json`, and the same-name reconnect scenario;
4. the new Iris scenario with DH disabled;
5. `terrain-shader-resource-view-transition.json` and the Bliss seam scenario;
6. the combined Iris+DH cell; and
7. a quiescent resource soak only after all visual gates pass.

The shader/resource transition remains a mandatory crash regression because it
exercises `render.terrain.flush-idle` after shader retirement. The operation
must continue to capture and poll its generation on the render thread; no test
plan step may reintroduce off-thread OpenGL cleanup.

## Completion and handoff

Both defects are complete only when their focused test fails on the known-bad
behavior, passes on the fix, and the independent live cell passes twice from
fresh client generations. The combined cell and relevant existing regression
scenarios must also pass. Do not weaken this to zero missing pages, zero failed
uploads, one clean screenshot, or a responsive client alone.

At handoff:

1. if the pose moved, mark the checkpoint at the expected post-test pose, then
   restore it with compare-and-restore semantics while supplying the held
   `server-world` lease token;
2. restore the DH override to the preflight's verified null/configured state;
3. clear reference suppression and any acceptance cutoff/fault/canary;
4. leave Iris persistently disabled unless the Iris acceptance lane completed;
5. confirm exact trajectory, endpoint generation, pose, presentation, fixture,
   and queue state with `status --json` and final diagnostics;
6. gracefully stop/save if the named session is no longer needed;
7. release both exact lease tokens; and
8. record durable JSON/JUnit, reviewed crops, counters, checks, and restoration
   outcome in `doc/agents/evidence/`, without committing raw `.run/` logs.

Update the graphics, modding, debug-control-plane, and development-workflow maps
if an entry point, invariant, diagnostic operation, or recommended validation
changes while executing this plan.
