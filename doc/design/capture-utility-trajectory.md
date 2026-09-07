<!-- Copyright (C) 2026 Jacob Repp -->

# Content capture-utility trajectory

## Status

This is the trajectory for turning Minosoft into a **lightweight capture utility**
for the three-repo content pipeline (`Blockbench` producer → `content-forge`
orchestrator → `Minosoft` consumer). The goal is an in-situ review loop: a block or
texture family is produced upstream, Minosoft composes it, places it in a framed
scene, renders it, **captures a PNG**, and feeds that image back into the producer's
asset index so blocks and textures can be **reviewed and iterated in-situ** rather
than only inspected as flat tiles.

Evidence uses the vocabulary in [`../agents/README.md`](../agents/README.md):
**Observed** (supported by current source), **Verified** (observed + covered by a
repeatable check), **Target** (intended, not yet current), **Unknown**. Line
references are pointers, not contracts.

## Completion goal (North Star)

> A single command takes a **target** — one block, a family cohort, or a slice of the
> content queue — composes the `loose-content` overlay from `content-forge/out`, places
> each target on a deterministic review stage, renders it, and captures a tight,
> stable PNG. The capture's path + provenance are written back into the content-forge
> asset index against the same target, where it carries a review decision
> (approve / review / reject). The loop is idempotent (same inputs → pixel-identical
> capture) and ultimately runs **headless in CI**, gating visual regressions with a
> baseline compare.

Closure is reached when `produce → compose → place → capture → review` runs as one
command, re-running changes nothing, and a texture/model edit that shifts a tile
beyond tolerance fails a visual gate with a diff image.

## Phase 0 — Foundations: the capture primitives exist ✅

The pieces to grab a frame are already present and exercised in acceptance.

- **Framebuffer readback** — `OpenGlRenderSystem.readPixels()` wraps `glReadPixels`
  into an `RGB8` buffer (`src/main/java/.../system/opengl/OpenGlRenderSystem.kt:394`).
  *Observed.*
- **Screenshot pipeline** — `ScreenshotTaker.capture()` reads pixels into a
  `CapturedScreenshot`, and `store()` writes a PNG via `TextureUtil.dump`
  (`src/main/java/.../gui/rendering/util/ScreenshotTaker.kt:100`, `:88`). *Observed.*
- **One-shot preview** — `./play.sh content preview <asset>` resolves an asset against
  the content queue, places it, and captures a PNG (`util/play/Play.java:359`, body at
  `:443`). *Observed.*
- **Block placement + camera pose** — the `content.place-block-state-sculpture` debug op builds a
  compact state sculpture at a fixed stage and can teleport the camera pose
  (`util/play/Play.java:548`, `:657`; `ClientDebugChannel.placeLocalBlocks` →
  `LocalConnection.placeBlocks`). *Observed.*
- **Diff + crop** — `./play.sh screenshot compare` does a pixel diff of baseline vs
  actual and `screenshot crop` extracts a region (`util/play/Play.java:267`). *Observed.*
- **Scenario harness** — JSON scenarios place blocks + lights and capture
  (`acceptance/scenarios/content-place-blocks-lighting.json`). *Verified* (runs in
  acceptance).

**Closure (met):** a placed-block scene renders and a PNG is captured and diffable
through the acceptance harness.

## Phase 1 — Overlay ergonomics: point at `out/` in one flag

Make ingesting the producer handoff a first-class, one-flag operation with in-session
refresh, so the iterate loop is tight.

- Resource packs load from `profile.assets.resourcePacks` in reverse (priority) order
  (`src/main/java/.../assets/AssetsLoader.kt:42`; `AssetsC.kt:25`). An overlay is added
  today via `content compose --source TYPE=PATH` (`util/play/Play.java:399`) or a manual
  profile edit — there is **no** dedicated `loose-content` key. *Observed.*
- **Target:** a `loose-content` source type (or `loose-content.default` config key)
  that resolves to the content-forge `out/` tree and mounts it at the top of the pack
  stack, mirroring content-forge's documented handoff (`content-forge` README:
  `loose-content.default = <abs>/content-forge/out`).
- **Target:** re-ingest changed assets in-session via `ReloadCommand`
  (`src/main/java/.../terminal/commands/rendering/ReloadCommand.kt:20`) so a producer
  re-run is visible without a restart.

**Closure:** `./play.sh content preview <target> --loose-content <path>` composes the
overlay at the correct priority; an acceptance scenario swaps one tile, reloads, and
re-captures to show the new pixels — no restart.

## Phase 2 — Framed capture stage: deterministic state review (in progress)

Turn the ad-hoc placement into a repeatable **review stage** and batch it over a
cohort.

- **Verified:** flat block previews now enumerate the active version registry rather
  than using hand-picked family samples. `BlockStateSculptureCatalog` orders every
  legal state, lays it out in deterministic pages of 1–64 isolated cells, and
  `content.place-block-state-sculpture` replaces any requested page for a fixed
  block/page size, including unused cells and context halves. The
  integration gate proves exact union/no duplicates and the known 1.20.4-scale
  surfaces: fence 32, stairs 80, door 64, wall 324, redstone wire 1,296.
- **Verified:** `content preview --state-page all|N --states-per-page N` captures the
  pages from one client generation. Every PNG has a JSON sidecar containing exact
  Java-format state keys/positions, catalog and PNG hashes, page bounds, capture
  metadata, settle controls, and content-stack fingerprint; a capture-set index
  makes the page union replayable. Waterlogged states remain covered but follow the
  unobstructed dry pages for useful material review.
- **Target:** tighten/crop the neutral stage automatically from the rendered bounds,
  rather than relying only on the current deterministic camera distance.
- **Target:** batch mode — iterate a **family cohort** or a **queue slice**, capture
  one framed tile per target, and emit an in-world **contact sheet** (the world-space
  analogue of content-forge's `texture-review.html`).
- Reuses `content.place-blocks` + camera pose and `screenshot crop`
  (`util/play/Play.java:267`).

**Closure:** one command captures a stable, cropped PNG per target for a cohort;
re-running is pixel-identical (camera + light are deterministic); the contact sheet
shows the family in-world. Per-block state capture and provenance are implemented;
cohort batching, automatic crop, and pixel-identity acceptance remain open.

## Phase 3 — Review feedback loop: close producer ↔ consumer

Write the capture back where governance lives.

- content-forge already indexes assets with `record-conversion / set-status /
  approve / review / reject` and a policy gate (`content-forge/scripts/db.mjs`,
  `db/policies.json`), and its `docs/trajectory.md` Phase 3 explicitly wants a preview
  **image path stored in the index** per target. This phase supplies those images.
- **Target:** a `content review` verb — capture a target, store the image path +
  provenance (producer, recipe, queue fingerprint) into the index against that target,
  and attach a decision. The in-isolation texture check stays in the producer
  (`content-forge/docs/texture-exemplars.md`); this is the **in-world** review.

**Closure:** every published target can carry an in-situ capture plus a decision in
the index; a "release" is approved against a visual review, not determinism alone.

## Phase 4 — Regression + headless CI: self-hosting

Gate visual regressions and remove the window requirement.

- **Target:** promote captures to visual-regression baselines gated by `screenshot
  compare` (`util/play/Play.java:267`); a change that shifts a tile beyond tolerance
  fails with a diff image.
- **Gap (Target / Unknown):** rendering currently needs a visible window. `--headless`
  disables rendering entirely (`src/main/java/.../terminal/arguments/ui/UiArgument.kt`;
  `doc/Headless.md`), and there is **no** offscreen GL context (EGL / pbuffer / CGL).
  Headless capture is the major lift — an offscreen context that still drives
  `readPixels`.

**Closure:** `capture → compare` runs headless in CI and is green; the visual gate
blocks a regression with an artifact; the whole loop is idempotent.

## How it composes with content-forge

```
Blockbench (producer)            content-forge (orchestrator)         Minosoft (consumer)
  author .bbmodel  ───┐          triage → produce → publish            compose loose-content
  texture recipes     ├────────► out/  (resource pack + provenance) ─► place on review stage
  exemplar goldens ───┘          asset index (db) ◄───── capture PNG + decision (Phase 3)
```

- Producer fidelity (family recipes + the exemplar/regression loop) is upstream:
  `content-forge/docs/texture-exemplars.md`.
- The pipeline-wide plan and closure gates: `content-forge/docs/trajectory.md`.
- This document owns the **consumer-side capture + in-world review** half of that loop.
