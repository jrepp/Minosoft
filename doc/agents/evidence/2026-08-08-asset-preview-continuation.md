<!-- Copyright (C) 2026 Jacob Repp -->

# Trajectory continuation: asset preview + local-scene mutation

Date: 2026-08-08. Continuation of
`doc/agents/evidence/2026-08-08-asset-preview-trajectory.md`. Records what the
implementing agent verified, what is still open, and the learnings/traps that
must carry forward. Producer/consumer context is unchanged: **Blockbench+
automation** produces content, **content-forge** (`/Users/jrepp/d/content-forge`)
orchestrates, **Minosoft** (this repo) consumes.

## Capstone closure

This section supersedes the earlier handoff status and open-item list below.

| Gate | Final result | Evidence |
| --- | --- | --- |
| Block-light propagation and visible illumination | **Passed** | The self-resetting lighting scenario sampled stone plus adjacent air at night. Before torch placement `blockLight` was `[0,0]`; afterward it was `[0,13]` (the opaque stone remains zero and the adjacent air carries the expected light). Under Bliss, the fresh no-torch capture averaged 22.9 luminance and the torch capture 33.4; the on-axis center changed from roughly 5–22 to 26–75. The passing report is `.run/acceptance/content-place-blocks-lighting-capstone-clean/report.json`; checked references are `acceptance/baselines/content-place-blocks/stone-{no-,with-}torch.png`. |
| Structured placement rejections | **Passed** | `content-place-blocks-rejections.json` passes with typed expected errors. Empty input, out-of-world Y, and an unknown state return `invalid_request`; 513 blocks returns `limit_exceeded`. The passing report is `.run/acceptance/content-place-blocks-rejections-capstone/report.json`. |
| GL 1281/1282 separation | **Isolated; clean relaunch no longer reproduces** | A temporary stage assertion caught the first 1281 in `OpenGlTextureArray.upload` at static-array `glTexImage3D`, before texture pixels, shader drawing, or block placement. The assertion was reverted. Two full Java 25 supervised generations and three isolated preview launches then had no GL 1281/1282, no Apple `GLD_TEXTURE_INDEX_2D_ARRAY is unloadable` warning, and no vertex-buffer lifecycle failure. There is no permanent speculative OpenGL patch; recurrence should be investigated from this allocation boundary. |
| Flat/underwater/item references | **Regenerated and rejected as asset baselines** | Clean captures are `.run/acceptance/asset-preview-capstone-clean/{barrel-flat,barrel-underwater,diamond-item}.png`. The scene, water, terrain lighting, and hand render, but the barrel is black and the diamond is absent. These images must not become acceptance baselines for the assets. |
| Residual model/texture lane | **Exact external boundary recorded** | The active stack takes `loose-content` from `/Users/jrepp/d/content-forge/out`, outside this workspace. Its barrel model is byte-identical to that output (SHA-256 `46b4df0d36b2ff833ab43e4395537aa97e71b8ea531747b528ea02dd86c2f759`) and still contains Blockbench `format_version`, short `block/barrel`, unit UVs, and no `particle`, despite the producer's `instantiate.mjs` promising normalized namespaced textures and particle data. The diamond texture exists, but its model resolves through `builtin/generated`; content-forge treats that parent as engine-hardcoded while Minosoft logs `Can not find item model minecraft:builtin/generated`. The remaining ~596 model-referenced texture warnings are in this same producer/model-contract lane; the 835/835 generated raster queue remains complete. |

Two additional consumer-side bugs were fixed during closure:

- `GUIRenderer` now applies `hud.enabled` to both `prepareDrawAsync()` and
  `draw()`. Gating only the visible draw left skipped HUD meshes in `PREPARING`
  and caused cleanup failures; clean generations have no such failures.
- `content preview` now selects the endpoint for the exact PID it launched.
  Selecting only by trajectory could mutate and capture a different concurrent
  client, which contaminated the first underwater/item references.

Every capture re-applied `visual.prepare-reference` immediately before the
screenshot. No transient GL assertion or renderer workaround remains.

## Earlier status at handoff (superseded)

| Task | Status | Evidence / where it stands |
| --- | --- | --- |
| 1 — `content.place-blocks` | Implemented + reachable + placement verified; **light/render effect unconfirmed** | Op registered in `ClientDebugChannel.kt`; placement `LocalConnection.placeBlocks`; auto-loads touched chunks via `LocalChunkManager.ensureLoaded`; scenario requests pass assertions; `world.blocks.sample` shows the placed stone + 4 torches. |
| 2 — `content preview` rework | Implemented + captures non-black; **framing and HUD bugs fixed**; torch illumination still unconfirmed | `Play.blockPlacementBody` + `--scene flat\|underwater`; flat/underwater/item captures are lit; found+fixed a camera-yaw bug and a HUD-not-hidden bug (below). |
| 3 — `synth-textures --all` | Implemented; all queued targets generated; residual model-referenced warnings | `--all` writes all 835 queued texture targets (verified 0 missing in `out/`); `npm run publish` resealed; ~596 distinct `Can not find asset minecraft:textures/…` warnings remain from **generated models**, not queued textures (see Open items). |

Durable artifacts created this session:
- Scenarios: `acceptance/scenarios/content-place-blocks-lighting.json` (stone at
  `(0,19,0)` at night, then +4 torches at `(0±2,19,0)`,`(0,19,0±2)`),
  an exploratory `content-place-blocks-lighting-isolate.json` variant
  (stone vs stone+adjacent-glowstone), and
  `acceptance/scenarios/content-place-blocks-rejections.json` (expected-failure
  requests: empty list, out-of-range `y=9999`, unknown block, 513 blocks).
  Baselines under `acceptance/baselines/content-place-blocks/`.

Repository audit on 2026-08-10 visually rejected the exploratory isolate
captures because both contain visible FPS/HUD state and repeated OpenGL 1282
chat. They are not checked baselines. The clean, structurally corroborated
`stone-{no-,with-}torch.png` pair remains the accepted visual contract.
- Tooling: `util/tools/` is a uv project (`pyproject.toml` + `uv.lock`, Pillow
  pinned) with `png_stats.py` (`#!/usr/bin/env uv run`) for capture statistics
  (size, luminance buckets, `--grid`, `--region`, `--center`).

## Successes to carry forward

1. **`content.place-blocks` is robust end-to-end.** Registration → scenario
   `request` → `LocalConnection.placeBlocks` → `world[position]=state` places
   real blocks, and `LocalChunkManager.ensureLoaded` generates+registers a chunk
   synchronously so placement works even before the async chunk loader has run
   (the initial request failed with "not in a loaded chunk"; `ensureLoaded`
   fixed it). Bounds: 512 blocks / 64 distinct chunks → `limit_exceeded`.
2. **Preview framing bug (real fix): the camera yaw sign.** `EntityRotation.front`
   (`src/main/java/de/bixilon/minosoft/data/entities/EntityRotation.kt`) is
   `(sin(-yaw)*pitchCos, -sin(pitch), cos(-yaw)*pitchCos)`. yaw=45 faces **-x/+z**;
   the asset sits at **+x/+z** relative to the preview camera at `(0.5-back,·,0.5-back)`,
   so the old `yaw=45` pointed the camera AWAY from the asset (asset at/near the
   frame edge). Fixed to `yaw=-45` in both `Play.previewPlacementBody` and
   `Play.blockPlacementBody`. **Any new framing must be verified against `front`
   first**; a wrong-sign yaw puts the fixture off-frame and pixel analysis then
   measures the wrong region (this silently invalidated the first torch-lighting
   runs).
3. **HUD-not-hidden bug (real fix): `GUIRenderer.draw()` ignored `hud.enabled`.**
   `visual.prepare-reference hideHud:true` set `gui.hud.enabled=false`, but
   `GUIRenderer.draw()` (`gui/rendering/gui/GUIRenderer.kt`) called `hud.draw()`
   unconditionally, so the HUD (crosshair, hotbar, and the **internal chat**)
   always rendered over captures. Fixed by gating `hud.draw()` on `hud.enabled`.
   This is why captures looked "UI present". F1 (`enable_hud`) was also inert
   before this fix.
4. **Re-call `visual.prepare-reference` immediately before each screenshot.**
   `doc/agents/acceptance/scenarios.md` is explicit: on hosts that pause on
   focus-loss, the debug round trip reopens the pause/settings screen, so a
   single initial `prepare-reference` is not enough for multi-capture scenarios.
   Assert `{"/overlaysCleared":true}` and `/hudEnabled:false` on every re-call.
5. **Structured error visibility:** `Play.conciseError` now renders the
   `DebugClientException` code, e.g. `DebugClientException[limit_exceeded]: …`,
   so expected-failure scenario steps are readable in `report.json`.
6. **`world.blocks.sample` now also returns `blockLight`/`skyLight`** (flat arrays
   in `y,z,x` order, parallel to the palette `runs`), so light levels are
   diagnosable without adding a new op. `debugCompare` is unaffected (it reads
   `palette`/`runs` only).
7. **Live captures are lit/non-black.** Flat barrel avg luminance ~102 (max 255),
   underwater barrel ~33 (water attenuation visible), diamond item ~142. The
   original "black block" is gone with real-block placement.
8. **Task 3 texture coverage:** after `npm run synth-textures --all && npm run
   publish`, `out/` contains a PNG for every queued texture target (835/835) and
   the composed stack no longer misses the original trajectory list (`sun`,
   `moon_phases`, `destroy_stage_*`, armor layers, `fire_1`, `beacon_beam`,
   `shadow`).

## Earlier failures and traps (superseded where closed above)

1. **Torch/glowstone illumination was NOT visually confirmed under Bliss.** The
   placed torch/glowstone blocks are in the world (`world.blocks.sample`
   confirms them) and the light-engine code path is the normal one
   (`ChunkSection.set` → `ChunkLight.onBlockChange` → `SectionLight.traceBlockIncrease`,
   the same path `prepareTerrainMaterials`/`prepareBeacon` use), yet the stone's
   rendered luminance barely changed at night. Do NOT accept this as done:
   (a) first read `blockLight`/`skyLight` at the stone position after placing a
   torch to prove propagation at the data level; (b) if the data shows ~12 but
   the render does not brighten, the gap is in the mesh/lightmap/shader stage,
   likely influenced by the pinned Bliss pack's own night lighting — isolate by
   disabling the shader pack (see trap 3) before touching the mesher.
2. **GL 1281/1282 errors every frame in the freshly built distribution.** The
   client log's per-generation segmentation shows these appear when the client
   distribution is rebuilt with the tree's pre-existing uncommitted renderer
   changes (RenderContext.kt, ArmRenderer.kt, IrisWorldShaderPipeline.kt,
   TextureUtil.kt, etc.), NOT from the asset-preview changes (they start at
   connect, before any placement; the Task-1/2/3 code is inert at startup). They
   surface as internal-chat spam that pollutes captures — the HUD-gating fix now
   hides them from screenshots, but the errors themselves are an independent,
   pre-existing renderer problem to diagnose separately. The trajectory's own
   note ("GL context in a headless sandbox emits the init-time warnings seen
   during bring-up") acknowledges some of this.
3. **A vanilla (no-modpack) launch is NOT viable in this environment.** A client
   without the fabric pack/content stack dies at startup with
   `LocalAssetUnavailableException: Local asset index e5335…` (it needs the
   vanilla index assets, which are not present here). So "disable the shader
   pack" cannot be done by launching without the pack; use
   `MINOSOFT_SHADER_PACK=` (empty) only to mean "pack default", and to truly
   disable shaders you must drive Iris off via the debug plane or a launch
   override — confirm `render.substrate`/the `IRIS_HOOK_INVOKED … shaderPack=`
   log line actually changed.
4. **Pixel measurement must target a verified region.** Before drawing lighting
   conclusions, confirm the fixture is actually in the frame (the yaw bug made
   the first runs measure terrain/sky). Locate the fixture by its distinct
   pixels (e.g., glowstone's bright cells) or by sampling a block-visible camera
   pose that `front` math says is on-axis.
5. **Background client launches die with bash-tool timeouts.** `nohup … &` inside
   a long-running bash call can be reaped when the tool times out. Launch with a
   subshell `(nohup … &)`, return immediately, then poll `ps`/`status --json` in
   short separate commands. Stop cleanly with `./play.sh stop client --trajectory
   default` and kill any leftover `de.bixilon.minosoft.Minosoft` PIDs.
6. **`acceptance/baselines/content-place-blocks/*.png` are contaminated.** They
   were overwritten across several runs (old framing, UI-present, pre-fix). Any
   visual claim must regenerate them with the fixed framing + re-clear and check
   the scenario's `report.json` step results before analyzing.
7. **The `.venv` under `util/tools/` must stay git-ignored** (added to
   `.gitignore`); `uv.lock` + `pyproject.toml` + the tool script are the tracked
   artifacts.

## Remaining external work

The Minosoft content-placement and scene-mutation gates are closed. The only
remaining asset work is to repair and republish content-forge's model output,
then regenerate the three rejected preview references. That requires authority
for `/Users/jrepp/d/content-forge`, which was inspected read-only and left
clean. If GL 1281/1282 recurs, start at static texture-array allocation with a
fresh Java 25 generation; do not attribute it to placement without evidence.

## Reproducible validation

```sh
export MINOSOFT_JDK25_ROOT=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export JAVA_HOME=$MINOSOFT_JDK25_ROOT

# client (leave running between commands):
(nohup ./play.sh dev client --local-world --world-generator flat --world-seed 1 \
  --modpack distant-horizons-bliss --content-stack standalone \
  --trajectory default > .run/scenario-client.log 2>&1 &)

./play.sh scenario run acceptance/scenarios/content-place-blocks-lighting.json \
  --update-screenshots --artifacts .run/acceptance/content-place-blocks-lighting --json
./play.sh debug blocks 0 19 0 1 19 0 --trajectory default --json   # includes blockLight/skyLight

./play.sh content preview minecraft:barrel --output /tmp/a.png --json
./play.sh content preview minecraft:barrel --scene underwater --output /tmp/b.png --json
./play.sh content preview minecraft:diamond --output /tmp/c.png --json

uv run --project util/tools util/tools/png_stats.py /tmp/a.png \
  --grid 12x6 --center 7x7
```

Stop with `./play.sh stop client --trajectory default`, then confirm both
`status --json` and `debug endpoints --json` are empty rather than assuming a
PID exited.

## Final live handoff

The capstone intentionally leaves no client or server running. Final
`./play.sh status --json` reported null client/server/parent PIDs and every
readiness flag false; `./play.sh debug endpoints --json` returned `[]`, and
`./play.sh lease status --json` reported no leases. The last isolated preview
process exited normally, so no transient reference, canary, GL assertion, or
shader override remains active.

Final Java 25 checks passed:

- `./gradlew :play-util:installDist compileKotlin`
- `./gradlew :test --tests de.bixilon.minosoft.dev.PlayUtilityTest`
- `./gradlew :play-util:test`
- both live placement scenarios named in the capstone table
- `git diff --check`

## Key references

- `src/main/java/de/bixilon/minosoft/local/LocalConnection.kt` (`placeBlocks`,
  `LocalBlockPlacement`), `local/LocalChunkManager.kt` (`ensureLoaded`),
  `local/datapack/LocalBlockStateParser.kt` (shared parse, reused by
  `LocalDisplayEntityFactory`), `debug/ClientDebugChannel.kt` (`content.place-blocks`,
  `placeLocalBlocks`, `blockState`, `world.blocks.sample` + `blockLight`/`skyLight`).
- `util/play/Play.java` (`runContentPreview`, `blockPlacementBody`,
  `previewPlacementBody`, `--scene`, `conciseError`).
- `gui/rendering/gui/GUIRenderer.kt` (`hud.enabled` gating — new fix),
  `gui/hud/elements/chat/{ChatElement,InternalChatElement}.kt` (internal chat
  renders via the Skippable GUI stage, not gated by `hideHud` before the fix).
- `doc/agents/acceptance/scenarios.md` (focus-loss pause + re-clear requirement).
- `doc/agents/evidence/2026-08-08-asset-preview-trajectory.md` (task brief).
