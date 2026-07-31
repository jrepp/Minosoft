<!-- Copyright (C) 2026 Jacob Repp -->

# Simulation evidence map

## Boundary

Simulation advances session-owned state: session/world ticks, entity physics,
collision, input-driven movement, block/light updates, local chunk generation,
and other time-dependent transitions. Object definitions remain in the object-
model layer; drawing remains in graphics.

## Evidence map

| Status | Claim | Evidence |
| --- | --- | --- |
| Verified | `SessionTicker` owns one non-overlapping 20 Hz session cycle. Each cycle emits Fabric start, runs entity → world → random-display → extension tasks in registration order with per-task failure isolation, then emits Fabric end. Tasks registered during a cycle enter its next snapshot. | `SessionTicker`, `SessionTickRunner`, `SessionTickRunnerTest`, full unit/integration suites, and [live tick evidence](../evidence/2026-07-22-fabric-client-tick-events.md). |
| Observed | `World.tick()` advances chunks and the world border using session view/player state. | `data/world/World.kt`. |
| Observed | Entity physics is split into state, handlers, movement/collision parts, and submersion. | `physics/` and `data/physics/`. |
| Observed | Local worlds use generator and storage abstractions behind `LocalConnection`. | `local/generator/`, `local/storage/`, and `LocalChunkManager`. |
| Verified | Chunk/light and physics behavior has extensive integration coverage. | `src/integration-test/kotlin/de/bixilon/minosoft/data/world/chunk/` and physics tests. |
| Observed | Simulation and presentation are coupled in places. | World audio/particle ports and entity renderer fields cross the boundary. |
| Verified | The Tech Reborn local generator decodes 15 placed ore features, resolves height anchors against a `-64..319` dimension, selects stone/deepslate targets, and produces the same first-chunk placement count for repeated launches with the same seed and registry fingerprint. | `TechRebornGenerator`, `FabricWorldContentReader`, focused tests, and [live regeneration evidence](../evidence/2026-07-21-tech-reborn-worldgen.md). |
| Verified | A fresh dedicated-server world generated after Terralith datapack activation. An artifact-level NBT scan found `terralith:*` biome palette entries in all 625 persisted spawn-region chunks, including surface highlands/Yellowstone and five cave families. | `modpacks/fabric-stack/mods/terralith.pw.toml`, generated Anvil biome palettes, and [Terralith world-generation evidence](../evidence/2026-07-23-terralith-worldgen.md). |
| Verified | Local-player physics consumes an accumulated double-tap start-sprint action exactly once, then retains sprint through the ordinary forward/hunger/collision rules until a stop condition occurs. | `MovementInputActions`, `LocalPlayerPhysics`, `SprintIT`, and [double-tap sprint evidence](../evidence/2026-07-23-double-tap-sprint.md). |
| Verified | A fresh dedicated-server world enabled Tectonic's embedded Terratonic pack above Terralith. Terratonic overrides the density identifiers referenced by Terralith's overworld settings and routes them through Tectonic cliffs, caves, underground rivers, and Terralith extra-terrain inputs; 1,257 persisted chunks showed Terralith biomes and terrain from Y=57 to Y=270. | `modpacks/fabric-stack/mods/tectonic.pw.toml`, active `level.dat`, generated Anvil heightmaps, and [Terratonic evidence](../evidence/2026-07-23-terratonic-worldgen.md). |
| Verified | Optional DH-style unexplored generation preserves authority. Local sessions incrementally prepare a bounded 3x3 `ChunkBuilder` neighbourhood through the existing local `ChunkGenerator`, calculate detached directional block/skylight over fixed primitive storage, and publish only a complete center page without publishing native chunks. The configured per-tick budget counts actual generated chunks; a bounded LRU amortizes the halo across adjacent pages and resets with world epoch. Remote sessions request the managed Fabric authority. | `DistantUnexploredGenerator`, `DistantGeneratedChunkCache`, `DistantDetachedLighting`, `DistantWorldVerticalSampler`, `DistantHorizonsLodServer`, focused light/cache/integration tests, and the [terrain consolidation evidence](../evidence/2026-07-30-terrain-consolidation-start.md). |
| Verified | The deterministic `world-diverse-small-2026-07-28` server world uses Terralith plus Terratonic and a world-owned load function that waits for the first naturally spawned player, then permanently centers a 1,024-block-diameter border and world spawn at that safe land position. The initial origin-forced attempt exposed an ocean-column failure and remains recoverable as a backup. The corrected client joined at a solid Y=91 coastal-jungle spawn; live border round-trip reported center `(-794.5, 614.5)` and radius `512`. | `server.properties`, the world's `minosoft-small-world` datapack, `render.prepare-world-border`, checked screenshots, and [small diverse world evidence](../evidence/2026-07-28-small-diverse-world.md). |
| Verified | The Java play utility performs bounded, read-only `level.dat`/Anvil inspection and canonical terrain hashing. Same-seed A/B comparison requires equal seeds, sampled chunk coordinates, and canonical section/heightmap terrain; mismatches fail nonzero. | `Play.runWorldgen`, live pass/fail checks, and [automation evidence](../evidence/2026-07-23-automation-observability.md). |
| Verified | A local session owns one generation-leased datapack runtime on its `SessionTicker`. Locally captured attack/interact packets update the targeted interaction entity at that runtime tick and execute the corresponding Animated Java reward handler as the player; disconnect removes the tick task and closes the lease. | `LocalConnection`, `SessionDataPackRuntime`, `DataPackFunctionRuntime.executeAs`, `LocalDisplayEntityFactoryTest`, and [content-fidelity adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md). |

## Stable contracts

- Simulation state belongs to one play session and advances through an explicit
  tick/lifecycle owner.
- Keep deterministic inputs visible: tick order, time source, random source,
  protocol version, dimension, and current world state.
- Physics/model mutation can invalidate graphics, but graphics must not decide
  simulation truth.
- Chunk/light changes must preserve locking and notify all derived consumers.
- Hot reload must not swap simulation code midway through a mutation. Reload at
  a safe point or restart the affected session.
- Generated chunks must use block states from the session's validated registry
  snapshot; a mismatched content fingerprint fails before generation.
- A play-session tick never overlaps itself. Entity, world, presentation, and
  extension tasks observe a stable per-cycle order; one failed task does not
  suppress later tasks or the end-of-tick phase.

## Trajectory

The ordered `SessionTicker` cycle establishes the first simulation safe point:
registration and removal can be coordinated between cycles, and end-of-tick is
guaranteed after task execution begins. **Target:** add quiescence that pauses new
mod callbacks, drains the current cycle, snapshots explicitly supported mod
state, swaps the mod generation, and resumes. Core host-code hot swap remains a
developer tool, not a guarantee that arbitrary live simulation instances migrate
correctly.

A whole-client generation reload stops the ticker and discards simulation state.
Its initial continuity contract is reconnect/recreate, not transparent object
migration. This keeps the boundary deterministic and makes retained old-generation
objects a testable leak.

## Next evidence

1. Map randomness and wall-clock use that affects repeatability.
2. Classify state as authoritative, derived/cache, or presentation-only.
3. Add safe-point quiescence and prove an old-generation callback cannot cross
   the next cycle after its scope closes.

## Validation

- Physics unit tests and boundary-value cases
- Chunk, block/sky light, and world-update integration suites
- Local-world scenarios for deterministic no-network reproduction
- Reload acceptance: no callback from an old generation runs after quiescence

## References

- [Physics](../../Physics.md)
- [Rendering: Lighting](../../rendering/ReadMe.md#lighting)
