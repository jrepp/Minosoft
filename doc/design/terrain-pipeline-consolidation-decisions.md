<!-- Copyright (C) 2026 Jacob Repp -->

# Terrain pipeline consolidation decisions

## Status

This record answers the architectural questions raised during review of
[`terrain-pipeline-consolidation-plan.md`](terrain-pipeline-consolidation-plan.md).
These decisions are prerequisites for freezing the Phase 1 interfaces.

The decisions describe target behavior. They do not claim that the current
renderer already implements it.

## D1: Terrain gets a standalone home

### Decision

Core terrain code is split by responsibility and dependency direction:

```text
de.bixilon.minosoft.terrain
├── model
│   ├── identity
│   ├── material
│   ├── page
│   └── coverage
├── runtime
│   ├── scheduling
│   ├── publication
│   ├── residency
│   └── telemetry
├── near
│   └── normalized near-terrain inputs and artifacts
└── distant
    ├── column runs
    ├── hierarchy
    ├── reduction
    ├── source authority
    └── store contracts

de.bixilon.minosoft.gui.rendering.terrain
├── scene
│   ├── pipeline generation
│   ├── frame snapshot
│   └── graph integration
├── near
│   ├── provider
│   ├── mesher
│   └── visibility
├── distant
│   ├── provider
│   ├── mesher
│   ├── selector
│   ├── shader
│   └── visibility
└── device
    ├── staging
    ├── regions
    ├── retirement
    └── batches

de.bixilon.minosoft.modding.loader.fabric
├── SodiumCompatibilityAdapter
├── DistantHorizonsCompatibilityAdapter
├── exact metadata/settings facades
├── Fabric event translators
└── Fabric payload transports
```

The first migration establishes these package boundaries inside the existing
root module. A later module extraction is allowed only after dependency
analysis proves that normalized terrain code no longer depends on GUI,
OpenGL, Fabric, `PlaySession`, or mutable world owners.

The intended eventual modules are:

- `terrain-core`: normalized models, headless runtime, reducers, persistence
  contracts, deterministic fixtures;
- `terrain-render`: scene/provider/device integration over rendering-system
  abstractions; and
- the existing application/Fabric layer: world adapters, exact mod probes,
  settings, and transports.

Package separation comes before Gradle-module separation so the migration does
not introduce a circular dependency between the current root world model and a
new child module.

### Enforced boundary

Code under `modding.loader.fabric` may:

- recognize an exact artifact;
- translate settings and events;
- negotiate a neutral profile;
- publish normalized inputs;
- transport bounded protocol messages; and
- expose a mod-specific debug facade.

It may not implement:

- `Renderer`, `WorldRenderer`, or a terrain provider;
- a terrain shader or mesh builder;
- page selection or visibility;
- scheduling, storage, upload, batching, or retirement;
- core source-authority or world-identity rules.

Core terrain and rendering packages may not import
`de.bixilon.minosoft.modding.loader.fabric`.

Phase 1 adds a focused architecture test for these forbidden edges. The test
initially checks source imports and implemented interfaces. Gradle module
dependencies become the stronger enforcement after module extraction.

### Consequence

`DistantHorizonsRendererHook`, its shader, mesh planner, mesh builder, and
render diagnostics move to neutral terrain-rendering packages.
`SodiumRendererHook` and `SodiumTerrainBackend` cease to be core rendering
concepts. The exact adapters retain their foreign names because they identify
the artifacts they translate.

## D2: Providers publish as one pipeline generation

### Decision

Near terrain, distant terrain, material tables, the shader pipeline, device
layouts, and render-graph passes are negotiated and published as one immutable
`TerrainPipelineGeneration`.

```text
TerrainPipelineGeneration
├── generation
├── near provider                     required
├── distant provider                  optional
├── material table generation
├── shader pipeline generation
├── near physical layout
├── distant physical layout           optional
├── declared views and material passes
├── render graph/resource generation
└── provider/device leases
```

The generation is built in two stages:

1. Candidate negotiation validates descriptors, layouts, views, materials,
   depth behavior, resources, and provider combinations without changing the
   active frame.
2. Candidate construction creates every owned resource. Only a completely
   valid candidate replaces the active generation.

Failure closes the candidate in reverse ownership order and preserves the
active generation. A frame leases exactly one complete generation.

### Registry behavior

Replace the single-backend selection concept with a
`TerrainPipelineRegistry`. It permits:

- exactly one near provider;
- zero or one distant provider;
- exactly one material-table generation;
- exactly one shader-pipeline generation;
- one compatible device layout per selected terrain domain.

Adapters propose a profile or capability set. They do not independently
replace renderers. Multiple adapter changes in one pack activation are
collected into one candidate generation.

Structured rejection reports the provider, capability, layout, material,
view, or resource incompatibility. Unsupported combinations fail closed.

### Why

Independent registries could publish a new distant layout while the old shader
or near-coverage contract was still active. Atomic composition removes those
mixed-generation frames and gives the agent one generation to inspect.

## D3: Runtime ownership has three scopes

### Decision

“One runtime” means one implementation and policy, not one process-global
mutable object.

### `TerrainCpuService`

Process scope:

- owns reusable CPU worker threads;
- accepts jobs from multiple render contexts/worlds;
- enforces per-tenant and per-domain fairness;
- contains no GPU, world, page, or provider state;
- survives individual world and render-context replacement;
- closes only with application shutdown.

### `TerrainDeviceRuntime`

One per rendering device/context:

- owns GPU regions, staging rings, submission serials, fences, draw batches,
  and device-resource telemetry;
- owns the active `TerrainPipelineRegistry`;
- is invalidated completely on context loss/recreation;
- never retains a mutable world after its world-runtime lease closes.

### `TerrainWorldRuntime`

One per session world epoch:

- owns near and distant page registries;
- owns coverage and LOD selection;
- owns source authority, generation, persistence, and network request state;
- submits CPU jobs under a tenant token;
- closes on dimension/world replacement or disconnect;
- rejects every late result carrying the old epoch.

### Fairness

CPU admission is weighted by tenant and terrain domain. Every active rendered
world receives bounded capacity. Near/seam work has higher urgency, while a
reserved distant share prevents permanent starvation. No world may consume
unbounded completions or upload backlog.

## D4: One residency governor bounds total memory

### Decision

Add a `TerrainResidencyGovernor` per device plus per-world CPU/cache accounting.
Per-region capacity is not accepted as a total-memory bound.

The governor tracks:

- detached build snapshots;
- completed CPU mesh artifacts;
- decoded distant pages;
- persistence write snapshots;
- staging buffers;
- resident near GPU ranges;
- resident distant GPU ranges;
- retired-but-not-yet-reusable GPU ranges;
- batch/command storage.

Configuration supplies explicit absolute caps. Safe defaults are selected from
Phase 0 measurements and remain bounded even when VRAM size is unknown. The
runtime does not infer permission to consume all reported heap or VRAM.

Pools receive minimum reservations and may borrow unused capacity:

- near visible/seam-critical geometry has the strongest reservation;
- distant visible transition pages follow;
- visible far pages follow;
- speculative decoded/generated pages are first to yield;
- staging and retirement retain non-borrowable safety reserves.

### Pinning

A page/range is not evictable while:

- referenced by the active frame or an unsignaled GPU submission;
- participating in a committed seam transition;
- it is the last-known-good replacement target;
- it has an in-flight upload;
- it contains dirty data not durably stored;
- a bounded debug/acceptance lease explicitly pins it.

### Eviction order

Evict in this order:

1. expired speculative CPU artifacts;
2. non-visible decoded distant pages already durable;
3. non-visible far GPU pages by distance/detail/recency;
4. non-visible near regions outside the retained view;
5. visible lower-priority distant detail only through an explicit quality
   degradation step.

Dirty, in-flight, frame-leased, and seam-critical data is never silently
evicted.

### Pressure behavior

Allocation pressure first triggers eviction, then bounded quality degradation,
then defers new work. It does not discard active geometry. A request larger
than the complete configured pool is rejected with a structured permanent
reason.

Metrics expose every pool’s cap, resident/pinned/retired bytes, high-water
mark, evictions, deferrals, and hard failures.

## D5: GPU retirement uses device completion, not CPU frame completion

### Decision

Every submitted terrain frame receives a monotonically increasing
`TerrainSubmissionSerial`. After terrain commands are queued, the device
runtime associates the serial with a GL fence.

A replaced range moves through:

```text
ACTIVE -> RETIRED(serial) -> REUSABLE
```

It becomes reusable only after:

- no CPU generation/frame lease references it; and
- the device reports the retirement serial complete.

OpenGL 3.3 implementations use sync objects. The graphics abstraction exposes
submission completion without leaking GL handles into terrain-core.

### Staging

Persistent-mapped staging uses a bounded ring divided by submission serial.
Writers may not overwrite an unsignaled segment. Ordinary buffer-update
staging follows the same logical ownership even if the driver copies eagerly.

If no staging segment is available within the frame budget, upload work is
deferred. The render thread does not block indefinitely waiting for a fence.

### Context loss

Context loss invalidates all device allocations and fences at once. CPU/durable
page data remains owned by the world runtime and may repopulate a new device
generation.

### Tests

The headless device simulates delayed and out-of-order completion. Tests prove
that replaced ranges are not reused early, fence failure preserves accounting,
and context loss returns logical resources to baseline.

## D6: Distant data has deterministic authority and provenance

### Decision

Every distant page record contains:

- current world epoch;
- current authority;
- original provenance;
- source-data revision;
- completeness/generation status;
- capture timestamp or monotonic source sequence when meaningful;
- schema/detail level;
- semantic content digest.

Authority tiers are:

1. `OBSERVED`: exact currently loaded chunk/block observations;
2. `REMOTE_AUTHORITY`: data supplied by the connected authoritative server;
3. `LOCAL_AUTHORITY`: data generated by the authoritative local world;
4. `CACHE`: persisted or imported data without current authority.

Persisting a record changes its current authority to `CACHE` on a later
session, while retaining original provenance for diagnostics. Cached data
never outranks a current observation or authority response merely because it
originated from one previously.

### Merge rules

- Records from another world epoch never merge.
- A higher-authority complete column/run replaces a lower-authority value.
- A lower-authority value may fill a genuinely missing column/run but may not
  overwrite present higher-authority data.
- Equal-authority updates require a newer source revision/sequence.
- An equal-revision content mismatch is reported as a conflict and keeps the
  active value until the authority resolves it.
- Partial data merges retain per-column/run authority and completeness.
- Block mutation invalidates affected base data plus every derived
  parent/detail page.
- Parent pages retain the minimum completeness of the child data used to build
  them.

The merge produces one new immutable page revision or no change. Persistence,
meshing, and diagnostics consume that published revision.

## D7: Coordinates, origin rebasing, and depth are explicit

### Absolute coordinates

Terrain page math uses checked `Long` block coordinates internally. Protocol
and world boundaries validate Minecraft’s supported range before converting to
host `Int` positions.

Each GPU page has an integer block origin. Vertices are encoded page-relative,
with a bounded range validated by the layout descriptor.

### Camera-relative rendering

The frame snapshot carries:

- absolute camera position in `Double`;
- integer render origin;
- camera position relative to render origin;
- per-page origin relative to render origin.

Conversion to `Float` happens only after subtraction and finite/range checks.
Changing the render origin updates frame/page transforms and draw commands; it
does not rebuild unchanged CPU geometry.

### Depth convention

Every terrain pipeline generation declares:

- clip-space depth convention;
- near/far projection behavior;
- depth compare and write behavior for each material/view;
- distant-depth snapshot semantics;
- whether reversed depth is supported.

The initial production generation uses the existing forward OpenGL convention.
A provider or shader requiring a different convention is rejected unless the
complete pipeline generation supports it.

Near and distant shaders derive from one frame camera definition. Distant
depth conversion must be reversible and documented for shader-pack consumers.

### Validation

Fixtures cover:

- negative region/page boundaries;
- render-origin changes;
- maximum supported world coordinates;
- high and low world Y;
- extreme configured distant range;
- narrow/wide FOV and zoom;
- main/shadow projection agreement.

## D8: Materials and textures are a leased generation

### Decision

Introduce an immutable `TerrainMaterialTableGeneration` containing:

- normalized semantic material IDs;
- block/model generation;
- texture/atlas layer bindings;
- sprite animation bindings;
- tint and biome-color rules;
- opacity/cutout/translucency classification;
- fluid and emissive semantics;
- shader-visible material metadata;
- physical encoding/remap data.

Build identities include the model/material-table generation when geometry,
face visibility, tint, or encoded attributes depend on it.

### Reuse rules

A layout may declare material indirection. With indirection, a compatible
table-only update may preserve geometry and replace the table generation.
Without indirection—or when model shape, tint baking, face culling, or vertex
attributes changed—the affected pages rebuild.

Candidate negotiation decides this before publication. Providers may not guess
that an atlas reload is geometry-compatible.

Artifacts retain semantic material references and an explicit table lease or
generation. No published page may reference a retired atlas or animation
binding.

## D9: Distant LOD selection uses screen-space error and hysteresis

### Decision

The distant selector computes a target detail from:

- projected page error/bounds;
- camera distance and height;
- field of view and zoom;
- configured quality/detail curve;
- data completeness;
- current near coverage and seam pressure.

Detail transitions use separate refine and coarsen thresholds. The coarsen
threshold is strictly more permissive, preventing oscillation around one
distance. Concrete default thresholds are set by Phase 0 fixtures and retained
as configuration evidence, not copied from another renderer.

Adjacent visible pages differ by at most one detail level. The selector has a
bounded per-frame node-visit/change budget. Unvisited nodes retain the previous
accepted selection conservatively.

### Movement

- Normal movement changes selection incrementally.
- High camera velocity biases prefetch in the movement direction without
  changing correctness priority.
- Teleport/dimension change creates a new world/frame epoch; old pages may
  remain as last-known-good only when they belong to the same world epoch.
- Zoom/FOV changes selection but do not force geometry rebuild when an
  appropriate page artifact already exists.
- Transition age is tied to the selected scene generation and resets when its
  source/coverage identity changes.

## D10: I/O and generation have independent budgets

### Decision

The runtime has separate bounded queues and budgets for:

- CPU meshing/reduction;
- GPU upload;
- persistence read bytes/operations;
- persistence write bytes/operations;
- decompression/compression bytes and CPU time;
- network requests, responses, and bytes;
- managed world-generation requests and in-flight chunks.

These queues use the same page identity and cancellation semantics but do not
share one undifferentiated budget.

Visible/seam retrieval outranks speculative generation. Persistence compaction
cannot delay a dirty-page durability write indefinitely. Managed generation
adapts to server tick/TPS evidence and never calls unsafe world APIs from an
arbitrary worker.

### Framing and integrity

Protocol and persistence records are independently framed with:

- schema/version;
- compressed and uncompressed byte lengths;
- bounded page/run counts;
- CRC32C for accidental corruption;
- semantic content digest where deduplication or conflict diagnosis requires
  it.

Uncompressed length is validated before allocation/decompression. A frame
cannot expand beyond its negotiated limit. Partial/chunked transfers identify
the request and page, and publish only after the complete page validates.

Repeated malformed or out-of-contract requests are rate-limited and reported;
they do not create unbounded logs or retry work.

## D11: Failures degrade predictably

### Failure classes

- `STALE` and `CANCELLED`: normal lifecycle outcomes; no retry penalty.
- `PRESSURE`: allocation/budget exhaustion; evict or defer.
- `TRANSIENT`: temporary I/O/device/service failure; bounded retry.
- `CONTENT`: malformed or internally invalid page/model data; quarantine that
  source revision.
- `PROVIDER`: invariant or repeated provider failure; reject or retire the
  candidate generation.
- `DEVICE_LOST`: invalidate the device generation and rebuild after recreation.

### Page state

Each page records its last bounded failure category, phase, generation, and
retry eligibility. It never retains an unbounded stack trace or error history.

Transient retries use bounded backoff. Content failures remain quarantined
until the source revision, schema, material generation, or provider generation
changes. Pressure failures retry only after the governor reports capacity.

Repeated provider-wide invariant failures trip a circuit breaker for that
candidate profile. The active last-known-good generation remains selected, or
the built-in conservative profile is selected through a complete pipeline
transaction.

Debug operations can request a retry only for an exact page/generation and
return the prior state. They cannot clear world/provider failures globally
without an explicit generation replacement.

## D12: Translucency has a narrow first contract

### Decision

The first consolidated runtime supports:

- near opaque and cutout;
- distant opaque;
- distant water;
- near translucent;
- explicitly declared emissive/additive passes.

Main-view order remains:

1. distant opaque;
2. distant-depth snapshot if required;
3. distant water;
4. near opaque/cutout;
5. remaining opaque world producers;
6. near translucent at the graph-declared boundary.

Distant water uses distant depth and the seam mask so it cannot color through
ready near opaque terrain. Near water/translucent geometry remains the
authoritative close-range surface.

Distant water pages are sorted back-to-front by bounded page bins. Exact
cross-page/per-quad transparency sorting and weighted blended OIT are deferred.
Unsupported distant translucent block materials are reduced through an
explicit quality policy or rejected; they are not silently assigned an
arbitrary blend mode.

Water does not cast distant shadows unless the selected pipeline explicitly
declares and tests that behavior.

## D13: Migration has named modes and one authority

### Runtime modes

Only these modes are supported:

- `LEGACY`: current production data/build/upload/draw path;
- `UNIFIED_COMPARE`: unified headless/candidate builds and semantic comparison,
  but only the legacy path draws;
- `UNIFIED`: consolidated runtime owns build through submission.

There are no user-visible switches for arbitrary combinations such as legacy
scheduler plus unified storage plus legacy coverage. Internal phase switches
must name and validate a complete supported combination.

Mode changes publish one complete terrain pipeline generation and are
observable. They do not mutate an active frame.

### Store migration

The v2 distant store is created alongside the v1 file:

1. v1 remains read-only;
2. records migrate transactionally into v2;
3. v2 becomes the only writable authority after validation;
4. legacy rendering, if selected, consumes a low-quality projection from v2;
5. v1 is retained until Phase 9 acceptance and an explicit cleanup boundary.

The runtime never dual-writes two authoritative stores. After v2 becomes
authoritative, rollback means selecting a legacy renderer over a v2 projection,
not resuming v1 writes. New v2-only fidelity may be reduced in that projection
but is not destroyed.

The irreversible boundary and backup disposition appear in the migration
result and evidence manifest.

## D14: Agent validation is atomic, bounded, and reproducible

### Canonical digests

Terrain digests cover semantic data, not incidental buffer layout:

- page keys and material IDs use canonical numeric/string ordering;
- maps and sets are sorted;
- integer values use one endian/width encoding;
- floating values use an explicitly documented quantization or tolerance;
- layout-specific buffer digests are reported separately;
- digest schema/version is included in every response and golden.

### Property and fuzz tests

Deterministic seeded property tests cover:

- page/region coordinate boundaries;
- allocator allocate/release/replacement traces;
- vertical-run ordering/reduction;
- hierarchy neighbour constraints;
- coverage state transitions;
- protocol and persistence framing;
- malformed/truncated/oversized inputs;
- source-authority merges.

A failing seed becomes a checked-in fixture before the fix is accepted.
Unbounded random fuzzing is not part of normal CI.

### Debug snapshot consistency

Detailed terrain debug operations capture one immutable
`TerrainDiagnosticSnapshot` containing world, pipeline, material, coverage,
page-registry, residency, and submission generations. They do not assemble a
response from independently changing live maps.

Page-list operations require:

- world epoch;
- bounded spatial area or page-prefix selector;
- explicit maximum count;
- opaque continuation cursor tied to the diagnostic generation;
- deterministic order.

A stale cursor is rejected rather than silently continuing in another
generation.

### Idle boundary

`render.terrain.flush-idle` requires:

- target world epoch;
- target pipeline generation;
- named queues/phases to drain;
- bounded deadline.

The response reports whether the boundary was reached and exact remaining
queue/in-flight/fence/durability counts. Continuous unrelated mutation cannot
make the operation wait indefinitely.

### Golden governance

Semantic and visual goldens update only through explicit repository commands.
Normal tests fail on a missing golden. An update writes:

- old/new digest or image;
- fixture and schema generation;
- source revision;
- command and environment;
- reviewable difference summary.

Agents do not update a visual golden when a lower semantic, lifecycle, pose, or
resource invariant fails.

### CI lanes

- Pull request: Java 25 L0-L3 focused and headless gates.
- Rendering integration: affected L4 dummy/real-GL gates on supported hosts.
- Nightly: multi-version fixture matrix, deterministic seeded properties, and
  selected live L5 scenarios.
- Scheduled/manual performance: matched L6 workloads, driver matrix, and soak.

Every lane has a bounded timeout and retained JSON/JUnit artifacts. Flaky tests
are fixed or explicitly quarantined with an owner and expiry; rerunning until
green is not acceptance.

## Phase amendments

### Phase 0 additions

- Establish process/device/world runtime scope fixtures.
- Capture CPU, staging, GPU, retired, and distant-cache memory baselines.
- Define canonical digest and diagnostic snapshot schemas.
- Define allowed runtime modes and the v1/v2 store migration boundary.
- Record coordinate/depth/FOV/zoom reference fixtures.

### Phase 1 additions

Freeze:

- package and eventual module dependency rules;
- `TerrainPipelineGeneration` and candidate registry;
- runtime scope interfaces;
- residency accounting categories;
- device submission/retirement abstraction;
- source-authority record and merge contract;
- coordinate/depth contract;
- material-table generation;
- capability and structured rejection schema.

Phase 1 is not complete while any renderer, shader, mesh builder, scheduler,
visibility owner, or GPU allocator remains architecturally owned by a Fabric
adapter.

### Phase 2 additions

- Implement scoped CPU tenants and fairness.
- Implement centralized failure categories and page retry/quarantine state.
- Add per-world queue and completion bounds.
- Add atomic diagnostic snapshots for runtime state.

### Phase 4 additions

- Implement the residency governor.
- Implement submission serials, fences, staging-ring ownership, and deferred
  range reuse.
- Prove context loss and delayed GPU completion.

### Phase 5 additions

- Make coverage and seam transitions part of the complete pipeline generation.
- Add cross-domain depth/translucency tests.

### Phase 6 additions

- Implement source-authority merge and hierarchical invalidation.
- Implement screen-space-error selection, hysteresis, movement/zoom behavior,
  and selection budgets.
- Ensure origin rebasing changes transforms rather than geometry.

### Phase 7 additions

- Implement independent I/O/network/worldgen budgets.
- Implement framed integrity checks and decompression limits.
- Migrate v1 alongside v2 and use v2 as the sole writer.
- Add corruption, partial-transfer, rate-limit, and rollback-projection tests.

### Phase 8 additions

- Complete material-table/resource reload behavior.
- Validate the complete near+distant+shader pipeline generation atomically.
- Prove device fences and material/layout leases retire cleanly through reload.

### Phase 9 additions

- Remove v1 only through an explicit accepted cleanup step.
- Remove unsupported internal feature-switch combinations.
- Keep conventional draw and v2-to-legacy projection fallbacks where required.

## Resolved review checklist

Before implementation begins, reviewers should be able to answer “yes” to:

- Does terrain rendering have a home outside the Fabric loader?
- Is one complete near+distant+shader generation selected atomically?
- Are CPU, device, and world runtime lifetimes distinct?
- Is total CPU/GPU/staging/cache memory bounded?
- Can a GPU range be reused only after device completion?
- Is every distant source conflict resolved deterministically?
- Can origin rebasing occur without remeshing?
- Are texture/model/material resources generation-owned?
- Does LOD selection avoid distance/FOV thrash?
- Are disk, compression, network, and worldgen independently bounded?
- Do repeated failures back off or quarantine?
- Is the supported translucency contract explicit?
- Can rollback occur without two authoritative stores?
- Are debug snapshots atomic and page queries bounded?
- Are semantic/visual goldens and CI lanes reproducible?

If any answer is “no,” the owning phase has not met its exit gate.
