<!--
  Minosoft
  Copyright (C) 2026 Jacob Repp

  This program is free software: you can redistribute it and/or modify it under
  the terms of the GNU General Public License as published by the Free Software
  Foundation, either version 3 of the License, or (at your option) any later
  version.
-->

# Recent terrain and visual trajectory summary

## Purpose

This is the compact handoff for the July 31 through August 1 terrain runs. It
separates accepted contracts from useful experiments and from live defects that
still reproduce. Read the linked records for exact counters and artifacts; do
not use raw `.run/` logs as durable evidence.

The qualified environment was Java 25.0.1, Minecraft 1.20.4, and Apple M4 Max
OpenGL 4.1 (`4.1 Metal - 90.5`). Multi-GPU and a second OpenGL driver are
explicitly deferred and do not block the current single-device goal.

## Trajectory outcomes

| Trajectory or workload | What worked | What did not or remains open |
| --- | --- | --- |
| `terrain-l5-base-near-2026-07-31` and `terrain-l5-optimized-near-2026-07-31` | Built-in and Sodium-compatible near providers passed their isolated profile gates. | These local-world lanes had no server endpoint and do not prove remote lifecycle behavior. |
| `terrain-l5-combined-transitions-2026-07-31` | Distant-only, fast seam movement, and populated shader/resource/view transitions passed with zero missing final pages, retired bytes, failed builds, and failed submissions. Moving `render.terrain.flush-idle` onto the render thread fixed the reproduced `glDeleteProgram` crash. | The original off-thread flush sequence caused a native OpenGL abort and must not be reintroduced. |
| `terrain-l6-quiescent-soak-2026-08-01` | The named workload matrix passed: fixed camera, invalidation, streaming, seam traversal, cold database load, generation fill, dimension transitions, shader reload, and a genuinely quiescent 30-minute soak. The soak retained 463 live GPU names with equal 37,986 create/delete deltas and no terrain failures. | The second-driver resource/reload baseline remains portability follow-up, not a completion gate. |
| Early `terrain-resource-soak-30m` | The 30-minute high-view-distance run was valid bounded fill and lifecycle evidence with no hard residency or submission failures. | It was not a steady-state leak baseline: distant residency continued growing from 2,408 to 5,209 pages. Use the later L6 quiescent soak for leak claims. |
| Same-address/world-name reconnect | Stable world fingerprints isolated distant stores, and stale semantic responses became nonfatal and nonpublishing. Network retirement and ledger cleanup passed. | The run first exposed store aliasing and a stale-response disconnect; both required code fixes before the accepted rerun. |
| `diverse-medium-biomes-2026-08-01` world creation | A fresh 2,048-block-diameter Terralith/Terratonic world generated, joined, saved, stopped, and inspected cleanly: 2,601 chunks, 841 full chunks, zero failed chunks, fourteen observed biomes, and terrain hash `6adf5a6897dda69d68e4a1e6f3b39a4526b13165ced0eac8af04ea245ab03489`. | No world-generation corruption was found. Active Anvil inspection must still use a stopped/saved boundary. |
| Diverse-medium DH hydration and network | The consolidated hierarchy, bounded selector, shared CPU service, angular hydrated frontier, persistence, and protocol lifecycle operated together. Representative settled runs had zero missing selected pages and zero distant allocation/upload failures. A 120-second request timeout matched the accepted server pace of one generated page per player per second. | Raising managed-server generation to two pages per tick previously tripped the watchdog. Keep the accepted one-page-per-second policy. Startup captures made before queues and missing-page counts drain are not visual evidence. |
| Diverse-medium Iris/Complementary | The independent lane now passes twice across fresh client generations plus a five-minute checked soak. The exact r5.8.1 fingerprint is retained, every required route advances, framebuffer checks are clean, fallback/rejection maps are empty, and the built-in path remains responsive after disablement. Live pass bisection found two generic host defects: fullscreen aliases inverted the rebased host model view, and a target that had ever generated mipmaps retained mipmapped minification after a later level-zero write. Player-relative fullscreen matrices and program-scoped mipmap filtering close both. | A second OpenGL driver remains portability follow-up. The trajectory remains persistently Iris-disabled as its verified restoration state, not because the accepted generation still fails. |
| Diverse-medium DH foreground A/B with Iris disabled | Three-state near coverage, partial-page refinement, fixed-camera near-coverage cache revision, and final draw masking now preserve uncovered children without drawing coarse foreground slabs over settled near terrain. The same-pose scenario passed twice across fresh client generations with real distant output, positive masked counts, zero missing pages, and no terrain failures. | No visual ownership defect remains in the qualified lane. Conservative coarse fallback is still required when partial children are unavailable; a clean counter ledger alone remains insufficient evidence. |

## August 3 completion

The two defects were closed independently and then together. The checked
[completion record](2026-08-03-recent-terrain-visual-root-cause.json) retains
the exact root causes, scenario run IDs, shader fingerprint, Java 25 checks,
and final stopped/no-lease restoration state. The mandatory shader/resource
transition also exposed a separate entity-resource lifetime defect: skeletal
buffers were retired in `postPrepareDraw` while the same frame's Iris shadow
pass still retained them. Entity GPU retirement now drains in `postDraw` and
the transition regression passes.

## Important distinctions

The two visual failures had independent owners and retain separate acceptance:

1. Iris corruption was screen/material/fullscreen shaped, survived DH disable,
   and could wedge the render thread; its fullscreen-matrix and mipmap-filter
   fixes retain an independent checked lane.
2. DH foreground overlap reproduced with Iris already disabled and disappeared
   when only DH presentation was disabled; its coverage/refinement fix retains
   a separate same-pose checked lane.

The earlier conclusion that the observed Iris stippling was not caused by DH
remains true for that artifact. It must not be generalized to the later giant
foreground slabs.

The DH selector, mesher, network, persistence, lifecycle, and resource gates can
pass while the near/distant visual ownership boundary is still wrong. Zero
missing pages and zero allocation/upload failures are therefore necessary but
not sufficient visual evidence.

## Safest next-session start

1. Run `./play.sh status --json` and acquire exact client and `server-world`
   leases before mutation. The closeout state for this record is fully stopped,
   with no endpoint, server port, external client, or lease active.
2. Start `diverse-medium-biomes-2026-08-01` with `fabric-stack`. Its authoritative
   player restore pose is overworld `32.6740054977249,78,-608.6999999880791`,
   yaw `115.75401`, pitch `4.5429916`, creative mode.
3. The restored profile intentionally keeps Iris persistently disabled. Confirm
   `mods.iris.presentation` reports `enabled=false` and `installed=false`
   before a new experiment; enabling the now-qualified generation is an
   explicit new mutation, not restoration.
4. Clear transient GUI state, capture the framebuffer, sample player/world and
   loaded blocks, then inspect `render.substrate` in the prescribed order.
5. Before judging DH pixels, require build/upload queues and missing selected
   pages to reach zero. Record near coverage state counts, DH
   `renderReadyNativeChunks`, selected detail counts, and `mainMaskedPages` at
   the same frame boundary.
6. For regression work, capture DH enabled and disabled at the exact same pose
   and require the checked foreground/distant crops plus positive distant draw
   and masked-page evidence. Do not replace this with counter-only acceptance.
7. Restore the DH override, pose, GUI, and any fixture; release leases at
   handoff. Do not raise server generation pace or re-enable Iris as part of the
   DH fix.

## Durable records

- [Root-cause test plan for the two open visual defects](../backlog/recent-terrain-visual-root-cause-test-plan.md)
- [Terrain consolidation implementation and live follow-up](2026-07-30-terrain-consolidation-start.md)
- [L5 profile, movement, reload, and crash regression](2026-07-31-terrain-l5-profile-movement-reload.json)
- [L6 workload matrix and quiescent soak](2026-08-01-terrain-l6-workload-matrix.json)
- [Near fixed-camera performance](2026-07-31-terrain-near-fixed-camera-performance.json)
- [Streaming performance](2026-07-31-terrain-streaming-performance.json)
- [Same-name world reconnect](2026-08-01-terrain-l5-world-reconnect.json)
- [Medium diverse world](2026-08-01-medium-diverse-world.json)
- [Completed DH/Iris root-cause qualification](2026-08-03-recent-terrain-visual-root-cause.json)

Framebuffer captures used during the final A/B remained under `/tmp` and are
diagnostic-only, not durable checked-pixel references.
