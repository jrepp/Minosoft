<!-- Copyright (C) 2026 Jacob Repp -->

# Render-churn performance correction

Date: 2026-07-31

## Scope and decisions

The performance correction follows a fixed-pose Apple M4 Max diagnosis whose
dominant accepted cause was avoidable resource and allocation churn rather than
an OpenGL 4.1 capability ceiling.

- Entity hitboxes are disabled for newly created profiles. An existing profile
  remains authoritative and is not silently rewritten.
- Each enabled hitbox feature owns one fixed-capacity indexed line mesh. Motion,
  rotation, velocity, and color interpolation update the loaded vertex buffer
  in place; they do not replace its VBO, index buffer, or VAO. Degenerate
  padding keeps the dynamic upload size invariant. The CPU primitive lists and
  builder wrapper are retained for the feature lifetime.
- `MeshedFeature` treats self-assignment as a no-op and retains loaded replaced
  meshes in an identity set until their render-queue unload executes. Terminal
  unload drains both the active mesh and every queued retired mesh, attempting
  all independently owned resources even when one cleanup fails.
- Terrain build telemetry uses a fixed enum and primitive atomic arrays for
  requested, started, and duplicate-suppressed counts. Invalidation entry
  points attribute visibility, LOD, resume, camera offset, manual reload,
  render setting, block, block entity, light, chunk data, neighbour, resource
  generation, stale retry, upload retry, and task retry causes.
- A request whose build inputs already match the queued or in-flight request is
  suppressed without replacing its latest identity. This prevents a stationary
  frame-side request from continuously making the running build stale.
- Terrain tint caches use identity-partitioned block/fluid tables and packed
  primitive block-position keys. Snapshot tint sampling precomputes its
  section-local offsets and weights and reads biomes by relative coordinates.
- The entity shadow, name-tag, and flame draw-state resource identifiers are
  process constants rather than per-draw allocations.

These contracts preserve the GUI-independent telemetry enum and snapshot in
`render-contracts`; no OpenGL, LWJGL, application, Minecraft, or window type was
introduced there.

## Automated evidence

The implementation passed:

- `./gradlew compileKotlin`
- `./gradlew :render-contracts:test --tests '*TerrainPerformanceTelemetryTest'`
- focused `HitboxFeatureTest` and `ChunkRendererTest` integration tests
- `./gradlew :test --tests '*TerrainTintCacheTest' -x :debug-core:test -x :render-contracts:test`
- `./gradlew test :render-contracts:test`
- `./gradlew integrationTest`
- `./gradlew clean assemble`
- `./gradlew assemble` after the final retained-builder refinement

The focused coverage asserts persistent hitbox mesh identity, self-assignment,
queued retirement cleanup, cause counters, and suppression of identical
in-flight terrain input. One broad integration invocation raced a concurrent
compile and could not initially load `MipmapTextureData`; an immediate isolated
rerun after compilation completed passed the full integration suite.

## Live evidence

A clean-build default trajectory ran on client PID 35244, generation 1, with
the built-in terrain provider, no client modpack or shader pack, and hitboxes
enabled by the existing profile. The framebuffer pose remained exactly
`(-749.6535799896006, 20, 737.8877024608646)`, yaw `49.672276`, pitch
`0.9242811`, in `minecraft:overworld`. The background limiter was not changed.

Between diagnosis bundles at `18:28:06Z` and `18:30:06Z`:

- 3,722 frames rendered.
- 13,213 GPU names were created and 13,198 deleted, or 3.55 created names per
  frame. This is about 92 percent below the diagnosed 44 names per frame; the
  live count increased by 15.
- The buffer/VAO creation deltas were 7,568/5,645. A 15-second allocation JFR
  contains hitbox geometry construction but no hitbox VBO/VAO construction
  stack; sampled remaining GPU mesh creation came from GUI and particle paths.
- No `Buffer has not been unloaded` warning appears in either bounded client
  log capture.
- Terrain queues and uploads were empty at both boundaries. Thirty-one builds
  started and succeeded, producing and uploading 35,427,168 bytes. Cause
  counters attribute 30 to live block changes and one to a light change; LOD,
  camera, reload, setting, retry, and unknown causes remained zero. The fixed
  player pose therefore did not produce an unattributed stationary build.

Artifacts are under the local, uncommitted
`.run/performance/recommended-fixes-2026-07-31/` directory, including the two
complete diagnosis bundles and `current-client-35244.jfr`.

## Limits

Several unrelated host processes consumed substantial CPU during this run, so
FPS and frame percentiles are not accepted as an absolute baseline. The
resource-name and attributed-work deltas remain useful because they are direct
cumulative counters for one process and fixed pose. Repeat the same workload
with other host workloads idle after the unrelated source edit is complete,
and require the broad Java 25 unit/integration gates before promoting this to a
complete performance baseline.
