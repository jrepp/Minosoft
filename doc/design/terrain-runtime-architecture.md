<!-- Copyright (C) 2026 Jacob Repp -->

# Terrain runtime architecture

## Decision

Terrain architecture remains Minosoft-owned and implementation-neutral. The
optimized backend should use version-normalized world/model inputs and the
existing `TerrainBackend`, render-graph, shader-layout, generation-lease, and
headless contracts.

The highest-value sequence is:

1. make terrain builds immutable, versioned, cancellable transactions;
2. replace per-mesh GPU occlusion queries with mesher-produced CPU visibility
   metadata and stable section render lists;
3. replace one-buffer-per-section meshes with bounded region-owned storage,
   grouped uploads, and atomic range publication;
4. build cached multi-draw batches over those published region ranges;
5. use measured build effort and upload cost to schedule work within frame and
   memory budgets.

These improvements should benefit the built-in backend and any future optimized
backend. Compatibility-adapter identity must not define core rendering
architecture.

## Current baseline

| Area | Current behavior | Consequence |
| --- | --- | --- |
| Scheduler | `ChunkMeshingQueue` submits mutable-section work to a bounded terrain-owned `TerrainBuildRuntime`, caps active tasks by worker count and pending uploads by mesh count, and cancels cooperatively. Completed work crosses a mailbox and is filtered by request revision, terrain-backend generation, and shader-material generation before entering the loading queue. | Terrain no longer competes in the shared pool or cancels by interrupting a reused thread, but CPU effort and upload bytes still provide no backpressure and request revision is renderer-owned rather than a captured model revision. |
| Mesher | Each terrain worker reuses one `ChunkMesher.WorkerContext` and captures a detached block/light/biome/entity-metadata halo under a stable chunk lock order. Snapshot and live section revisions gate publication, and snapshot opacity produces directional connectivity. The existing solid/fluid model path still reads the live `ChunkSection` while producing geometry. | Visibility metadata is immutable and stale output cannot publish, but complete P0 isolation still requires porting canonical solid/fluid lighting, tint, and block-entity discovery to the snapshot view. |
| Visibility | `ChunkVisibilityManager` performs view-distance/frustum collection followed by deterministic synchronous traversal over mesher-produced six-face connectivity. Terrain `ChunkMesh` no longer creates samples-passed queries; absent connectivity includes a section conservatively. | Driver query stalls and per-mesh query objects are removed. Deterministic visual comparison scenes are still required before treating connectivity culling as accepted across all supported versions. |
| Batching | `submitTerrainCore` iterates every visible `ChunkMesh` and calls `draw`; each mesh owns its own VBO, optional index buffer, VAO, and query. | Draw calls, bindings, native objects, and list sorting scale with visible section/material pairs. |
| Upload | `MeshLoadingQueue` uploads individual meshes while holding its queue lock. It uses a fixed 3 ms moving/20 ms still time budget and creates/retires independent GPU buffers. | Upload cost is not predicted from bytes, producer access is blocked during GL work, and replacement churn fragments driver allocations. |

The generation-pinned `TerrainBackendRegistry`, semantic vertex-layout
declaration, render graph, render-thread resource accounting, and
last-known-good shader pipeline are the correct ownership foundation.

The scheduler/mailbox, detached capture, model-revision gate, candidate cache,
CPU connectivity/traversal, and headless region-runtime substrate are
implemented. P0 must not be reported complete until the canonical geometry
mesher consumes the detached snapshot rather than the live section. The
region/storage implementation is a validated headless reference path, not yet
the selected built-in backend's GL ownership path.

## Scheduler direction

Add a terrain-owned executor and immutable `TerrainBuildJob`. Each job should
contain:

- backend generation and section revision;
- material/resource-generation fingerprint;
- camera-relative urgency and submission sequence;
- estimated effort and output bytes once observations exist;
- a cooperative cancellation token; and
- a detached, bounded build snapshot.

Each worker owns reusable builders and caches. Completed results cross one
mailbox into the render thread, where only the newest result matching the
active backend, section, and resource generations may publish. Cancellation
sets logical state; it never interrupts a pooled thread that may already be
executing another job.

Request revision and model revision are different values. A request revision
orders renderer invalidations; a model revision proves which block, light,
biome, entity, and neighbour state was captured. Publication must check both.
`TerrainBackend.invalidate(TerrainSectionSnapshot, ...)` is not the build
snapshot seam: invalidation should carry position/reason into the selected
scheduler, and the scheduler should capture the complete build snapshot once it
owns a job.

Urgency should eventually distinguish same-frame, next-frame, and deferred
work. CPU scheduling capacity and upload byte/duration capacity are separate
budgets.

## Mesher direction

Expand the existing block-only `TerrainSectionSnapshot` into a bounded build
snapshot containing the section and neighbor halo required by:

- solid and fluid state;
- block and sky light;
- biome/tint sampling;
- culling and directional visibility;
- block-entity discovery; and
- material/model generation.

The snapshot must remain version-normalized and headless. The mesher should
return immutable material streams segmented by canonical face plus unassigned
geometry, directional visibility data, exact byte counts, block-entity
metadata, and its source generation/revision.

Snapshot capture must define a stable lock order across the section and halo,
capture every model revision under those locks, and retry or reject when the
set changes during capture. Copying the center section under one chunk lock and
reading neighbours later is not an immutable snapshot.

Do not introduce a second model pipeline. Existing Minosoft model, lighting,
tint, and material behavior remains canonical.

## Visibility direction

First implement deterministic synchronous CPU graph visibility over an
immutable section-state generation:

- record six-direction connectivity while meshing;
- traverse adjacent sections conservatively from the camera;
- combine connectivity with view distance and frustum rejection;
- produce immutable front-to-back opaque/cutout lists and reverse translucent
  lists; and
- fall back to frustum-only inclusion when connectivity is absent.

Remove samples-passed queries only after A/B captures prove there are no
false-negative holes, including a camera inside an opaque section and newly
opened geometry. Asynchronous traversal is a later optimization and requires a
generation-pinned safe-read snapshot plus deterministic cancellation.
Keep a temporary runtime comparison switch and deterministic capture scenes
until this gate passes; a subjective one-off screenshot is not acceptance.

## Batching direction

Introduce a `TerrainRegion` with benchmarked power-of-two dimensions. Store
section ranges per `TerrainMaterialClass` and face segment. Build a cached
multi-draw command list per region, view, and material after the shader
pipeline binds that semantic pass.

The physical vertex encoding remains negotiated per terrain/shader generation;
region batching must not hard-code the current 84-byte shader-compatible
layout. Keep a conventional loop fallback for dummy/headless systems and
drivers without the selected command capability.

Batch invalidation should be narrow and explicit:

- visible section membership changed;
- camera crossed a region-relative face-selection boundary;
- section range moved or changed;
- vertex/index buffer identity changed; or
- terrain/shader generation changed.

## Upload direction

Add:

- a staging interface with persistent-map and ordinary buffer-update
  implementations;
- one bounded allocator per active terrain region/material generation;
- a `TerrainUploadPlan` grouped by destination region and material;
- checked arithmetic for every byte/range allocation; and
- atomic section-table publication.

Prepare allocations and copies before replacing the active section entry. A
failure keeps old geometry. Retire old ranges only after replacement succeeds
and frame/backend leases permit it. Measure bytes and duration for every batch,
then feed those observations into scheduling.

Do not start with global multi-arena compaction or incremental
defragmentation. Add them only if recorded allocation traces show that bounded
region allocators cannot meet memory and upload targets.

## Prioritized delivery

| Priority | Slice | Exit evidence |
| --- | --- | --- |
| P0 | Immutable build snapshot, dedicated terrain executor, cooperative cancellation, revision/generation filtering, and per-worker reusable context. | Deterministic tests prove stale and cancelled outputs cannot publish, failed work preserves the loaded mesh, unload closes every task/context, and headless/multi-version meshing remains available. |
| P1 | Mesher-produced directional visibility plus synchronous CPU graph traversal and immutable ordered render lists. | Visual A/B scenes show no missing sections from inside/outside occluders; query allocation/submission reaches zero for terrain; visibility preparation p95 and list counts are recorded. |
| P1 | Bounded staging and region suballocation with atomic section publication. | Failed allocation/upload preserves old geometry; repeated replacement/unload returns buffers and ranges to baseline; upload bytes/duration and fragmentation are reported. |
| P1 | Region-owned material/facing ranges and cached multi-draw commands over published region storage. | Draw submission is bounded by visible region/material/view batches instead of section meshes; base and shader-pipeline layouts produce matching captures; dummy fallback remains deterministic. |
| P2 | Adaptive CPU/upload budgets and same-frame/next-frame/deferred urgency classes. | Recorded workloads remain within the configured frame/upload budget while nearby invalidations present promptly; cancellation, queue depth, worker utilization, output bytes, and upload p95 are observable. |
| Deferred | Async visibility, global multi-arena compaction, incremental defragmentation, and specialized translucent spatial sorting. | Adopt only after the synchronous/region substrate is accepted and a measured workload justifies each complexity increase. |

## Implementation status

`terrain/runtime` now contains executable, headless contracts for the full
data path:

- bounded worker execution, logical cancellation, and a completion mailbox;
- stable-order halo capture with a composite neighbour revision;
- deterministic connectivity generation and conservative synchronous graph
  traversal;
- checked bounded allocation, staging, rollback-safe atomic replacement, and
  fragmentation/upload metrics;
- generation-keyed region/material draw-batch caching with a conventional
  command-list fallback; and
- urgency ordering with independent CPU-time and upload-byte budgets.

The production chunk path also owns bounded performance observability:

- fixed primitive histograms cover queue wait, detached snapshot capture,
  meshing, worker busy time, synchronous visibility, and actual buffer upload;
- exact bounded gauges/counters report build and upload queue depth, configured
  and active workers, cumulative worker utilization, cancellations, stale and
  rejected work, failures, visible sections, and produced/uploaded bytes;
- `metrics.snapshot` exposes the compact summary and
  `render.substrate.terrain.productionRuntime` exposes bucket detail;
- `render.terrain-telemetry` provides a render-thread enable/disable boundary
  for identical-workload A/B checks and rejects changes until build/upload
  queues are idle; and
- shader draw-route diagnostics use preassigned numeric route IDs and bounded
  primitive counters. High-cardinality draw-state detail is sampled once per
  256 eligible events and formatted only when diagnostics are requested.

The focused regression gate alternates identical enabled and disabled
representative workloads and rejects a p95 increase above 10 percent plus a
100-microsecond noise allowance. This is a deterministic CI guard, not a
substitute for long-running device-specific frame captures.

The reference pipeline is intentionally independent of OpenGL and protocol
versions so its failure semantics can be tested end to end. Moving its ranges
into the selected backend still requires exposing canonical CPU vertex/index
streams from `ChunkMeshBuilder`; wrapping already-created per-section VBOs
would preserve the old ownership problem and is not an acceptable shortcut.

## Non-goals

- Do not make compatibility adapters define core terrain architecture.
- Do not run two terrain backends for the same graph view.
- Do not embed a protocol-version registry or foreign model layout in build
  snapshots.
- Do not begin with unsafe off-heap section tables or allocator
  defragmentation; compact primitive arrays and explicit checked arithmetic are
  sufficient for the first region backend.
- Do not claim optimized terrain ownership until the selected backend owns the
  scheduler, snapshot mesher, visibility lists, storage/upload, batching, and
  submission path rather than forwarding into `ChunkRenderer`.

## Source anchors

- `ChunkRenderer`, `ChunkMeshingQueue`, `MesherTaskManager`,
  `MeshLoadingQueue`, and `MeshUnloadingQueue`;
- `ChunkMesher`, `ChunkMeshes`, `ChunkMesh`, `LoadedMeshes`, and
  `VisibleMeshes`;
- `ChunkVisibilityManager`, `TerrainContracts`, `TerrainBackendRegistry`,
  `OpenGlVertexBuffer`, and `OpenGlGpuBuffer`.
