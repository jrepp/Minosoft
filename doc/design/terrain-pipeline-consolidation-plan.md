<!-- Copyright (C) 2026 Jacob Repp -->

# Terrain pipeline consolidation plan

## Status

This document is the implementation plan for consolidating Minosoft's near and
distant terrain rendering onto one standalone, Minosoft-owned low-level
runtime. It builds on
[`terrain-runtime-architecture.md`](terrain-runtime-architecture.md), the
render-substrate evidence maps, and the Distant Horizons/optimized-terrain
audit.

The binding answers to the architectural questions found during review are in
[`terrain-pipeline-consolidation-decisions.md`](terrain-pipeline-consolidation-decisions.md).
Those decisions refine the contracts and phase exit gates in this plan.

This is a delivery plan, not an assertion that the target architecture is
already implemented.

## Outcome

Minosoft will own one terrain runtime that can host multiple terrain providers
without making a compatibility mod the architectural owner.

The completed system will:

- use normalized, immutable world data and semantic render contracts;
- share scheduling, cancellation, staging, allocation, publication,
  visibility, batching, telemetry, and lifecycle rules across near and distant
  terrain;
- allow near and distant terrain to retain purpose-built page shapes, meshers,
  material semantics, and physical vertex encodings;
- export explicitly described render structures that a Sodium-compatible or
  Distant-Horizons-compatible adapter can select;
- keep exact mod names and artifact probes inside the Fabric compatibility
  layer;
- provide deterministic headless and live-driver validation at every layer;
- preserve multi-version and headless behavior; and
- scale by changed spatial pages rather than rebuilding or uploading a whole
  terrain domain.

## Architectural decisions

### Core code is provider-neutral

Classes in the core rendering and terrain-runtime packages must be named for
their responsibility, not for a compatibility artifact. Core names may include:

- `TerrainRuntime`
- `TerrainProvider`
- `NearTerrainProvider`
- `DistantTerrainProvider`
- `TerrainPage`
- `TerrainBuildIdentity`
- `TerrainCoverageSnapshot`
- `TerrainRegionStorage`
- `TerrainDrawBatch`
- `TerrainInteropDescriptor`

Names such as `SodiumCompatibilityAdapter` and
`DistantHorizonsCompatibilityAdapter` remain valid in the Fabric loader because
they identify exact foreign artifacts. Rendering classes named
`SodiumTerrainBackend` or `DistantHorizonsRendererHook` do not remain core
architectural concepts.

### One runtime does not mean one mesh format

Near terrain and distant terrain solve different problems:

- near terrain needs exact block models, tint, light, fluids, cutout and
  translucent ordering, block entities, and section connectivity;
- distant terrain needs hierarchical spatial selection, compact vertical
  material runs, aggressive geometric reduction, transition seams, and very
  large coverage.

They share the low-level runtime and publication rules, but may use different:

- page keys and page dimensions;
- snapshot payloads;
- mesh generators;
- vertex/index layouts;
- material partitions;
- visibility selectors; and
- draw batching policies.

Forcing both domains into the current near-terrain 84-byte vertex format or the
current distant-terrain 24-byte format is explicitly rejected.

### Compatibility is declared data

Compatibility is expressed through immutable descriptors rather than class
names:

```text
TerrainInteropDescriptor
├── provider capabilities
├── semantic vertex layout
├── material classes
├── supported views
├── staging/upload capabilities
├── shader inputs
├── lighting/tint semantics
└── optional foreign-profile identifiers
```

A compatibility adapter selects or configures a provider only when the exact
artifact contract can be mapped to this descriptor. The adapter does not own
the scheduler, mesher, allocator, visibility graph, or frame lifecycle.

### Publication is transactional and generation-pinned

Every candidate is built against a complete immutable identity. A candidate
may replace active geometry only after:

- its source world epoch is still active;
- its provider and shader-layout generations still match;
- its page request and model revisions are current;
- all CPU validation succeeds;
- all GPU allocation and upload work succeeds; and
- the replacement can be committed atomically.

Failure or cancellation preserves the last-known-good page. Retired ranges and
provider generations remain alive until their last frame lease closes.

### Testability is part of the runtime contract

Every state transition that matters to correctness must be observable through
one of:

- a pure immutable value;
- a deterministic headless snapshot;
- a bounded primitive counter or histogram;
- a typed debug response; or
- a retained acceptance artifact.

An agent must be able to establish why a page is absent, stale, masked,
queued, rejected, visible, uploaded, or retired without inferring it from one
screenshot.

## Target architecture

```text
Normalized world state
        |
        +--------------------+
        |                    |
 Near snapshot source   Distant data source
        |                    |
 NearTerrainProvider    DistantTerrainProvider
        |                    |
        +---------+----------+
                  |
           TerrainRuntime
     +------------+-------------+
     |            |             |
 scheduling   page registry   completion mailbox
 cancellation identity gates  fault accounting
     |            |             |
     +------------+-------------+
                  |
          TerrainUploadRuntime
     +------------+-------------+
     |            |             |
 staging      region storage  transactional publish
 budgets      allocation      lease retirement
     |            |             |
     +------------+-------------+
                  |
          TerrainSceneSnapshot
     +------------+-------------+
     |            |             |
 near coverage distant LOD    view visibility
 seam state    selection      main/shadow
     |            |             |
     +------------+-------------+
                  |
          TerrainDrawRuntime
     +------------+-------------+
     |            |             |
 batch cache   layout lease   semantic submission
 fallback loop multi-draw     graph phases
                  |
       ShaderPipeline / RenderGraph
```

The render graph remains the owner of phase order and render-target
dependencies. The terrain runtime owns terrain work and terrain draw-command
production; it does not become a second render graph.

## Core contracts

### `TerrainWorldIdentity`

Required fields:

- session/connection generation;
- normalized world key;
- world epoch, changed on every join, reload, or dimension replacement;
- dimension minimum/maximum height;
- content/model generation;
- optional stable persistence identity such as server identity plus world seed
  fingerprint, when available.

The world epoch is mandatory in in-memory build, coverage, persistence writer,
and network request state. A late result from an older epoch is rejected before
it reaches a provider.

### `TerrainPageKey`

The key contains:

- terrain domain (`NEAR` or `DISTANT`);
- provider-defined spatial level/detail;
- normalized x/y/z or x/z coordinate;
- world epoch.

Near pages initially map to section positions. Distant pages map to quadtree
sections or region tiles. Runtime maps may not use foreign packed-position
formats as their public key.

### `TerrainBuildIdentity`

The consolidated identity contains:

- `TerrainPageKey`;
- request revision;
- captured model revision;
- provider generation;
- semantic/physical layout generation;
- shader-material generation;
- coverage generation when the build depends on the near/distant seam;
- priority sequence; and
- optional source-data revision.

Identity comparison must be centralized. Providers do not each implement a
slightly different stale-result gate.

### `TerrainBuildJob`

A job contains:

- complete identity;
- detached immutable input;
- urgency class;
- estimated CPU effort;
- estimated output bytes;
- cancellation token; and
- provider-owned worker function using a reusable worker context.

The scheduler bounds queued, running, completed-but-undrained, and pending
upload work. CPU time and upload bytes have independent admission budgets.

### `TerrainMeshArtifact`

The provider-neutral envelope contains:

- build identity;
- provider-owned immutable vertex and index streams;
- exact checked byte counts;
- semantic material partitions;
- bounds;
- visibility/connectivity metadata where applicable;
- coverage contribution;
- debug digest;
- release/close ownership; and
- no live chunks, sections, mutable palettes, renderer callbacks, or OpenGL
  objects.

### `TerrainUploadPlan`

The render-thread plan contains:

- destination region and material;
- physical layout generation;
- required vertex/index ranges;
- staging operations;
- replacement table entries;
- old ranges to retire only after commit; and
- exact upload byte estimate.

Allocation and staging are prepared before the active page table changes.

### `TerrainCoverageSnapshot`

Coverage is spatial and readiness-aware. The minimum state model is:

- `ABSENT`
- `REQUESTED`
- `BUILDING`
- `UPLOAD_PENDING`
- `READY`
- `RETIRING`

Coverage must distinguish surface-relevant readiness from “some vertical
section in this chunk has a mesh.” It has its own revision, incremented only
when the spatial coverage result changes.

The immutable snapshot includes world epoch, revision, covered cells/pages,
transition ages, and provider generation. Near-to-distant handoff consumes this
snapshot; it does not inspect mutable loaded-mesh maps.

### `TerrainSceneSnapshot`

One snapshot is pinned for a frame and every terrain view. It includes:

- world and frame generation;
- camera and render origin;
- near coverage;
- selected distant pages/detail levels;
- seam and transition parameters;
- main and auxiliary view descriptors;
- shader/layout generation; and
- active provider leases.

Main and shadow terrain cannot independently observe two different coverage or
LOD generations in the same frame.

## Unified runtime components

### Scheduler

Implement one bounded scheduler with provider-defined queues over shared
capacity:

- near invalidations and camera-adjacent holes: immediate;
- seam pages needed to prevent a visible gap: immediate;
- visible distant-page refresh: next-frame;
- speculative distant generation and persistence compaction: deferred.

Prevent distant generation from starving near rebuilds, but reserve a small
bounded share so continuous near churn cannot permanently starve distant
coverage.

Use measured effort and bytes after enough observations exist. Until then use
fixed conservative estimates per provider and detail level.

### Completion and publication

All worker completions cross one mailbox. The render thread:

1. drains a bounded number of completions;
2. validates the centralized build identity;
3. constructs an upload plan;
4. rejects or defers work exceeding the current upload budget;
5. stages and uploads candidates;
6. atomically publishes page entries and coverage changes; and
7. retires old ranges behind generation/frame leases.

The queue lock must not be held during GPU upload.

### Region storage

Use bounded region-owned allocators with:

- checked size/range arithmetic;
- separate vertex/index arenas as required;
- layout-generation ownership;
- page-to-range tables;
- material partitions;
- fragmentation and high-water metrics;
- deterministic headless implementation;
- ordinary buffer-update implementation;
- optional persistent-mapped implementation; and
- conventional draw-loop fallback.

Region dimensions are selected with recorded workloads, not copied blindly
from a foreign renderer.

### Visibility and batching

Near terrain:

- deterministic CPU connectivity traversal;
- frustum and distance filtering;
- front-to-back opaque/cutout ordering;
- reverse translucent ordering;
- region/material/view batch caching.

Distant terrain:

- quadtree/detail selection;
- per-page main/shadow frustum filtering;
- transition-neighbor constraints;
- front-to-back opaque ordering;
- distant-water ordering appropriate to the selected shader route;
- region/detail/material/view batch caching.

Batch cache keys include all membership, range, layout, view, and generation
inputs. Missing connectivity/culling data falls back conservatively.

### Telemetry

Shared primitive telemetry is tagged by numeric domain/phase keys and reports:

- requested, admitted, started, completed, published, cancelled, stale,
  rejected, failed, and retired counts;
- queue/outstanding/completion/upload depths and high-water marks;
- worker count and current/cumulative utilization;
- captured input, produced output, staged, uploaded, resident, and retired
  bytes;
- region allocation, free space, fragmentation, and allocation failures;
- visible pages/sections, draw batches, draw commands, and vertices;
- coverage states and transition counts;
- queue wait, snapshot, build, selection, visibility, staging, upload, and
  submission histograms.

No unsampled per-page strings or maps are created on hot paths. Human-readable
page detail is produced from bounded snapshots only when requested.

## Near-terrain migration

### Immutable meshing

Port canonical solid and fluid meshing to consume `TerrainBuildSnapshot`
exclusively:

- block states and model resolution;
- neighbour/halo state;
- block and sky light;
- biome/tint samples;
- opacity and face culling;
- block-entity discovery;
- material classification.

The live `ChunkSection` is used to capture and validate revisions, never as
geometry input after the snapshot boundary.

### Provider ownership

Replace forwarding “compatibility backends” with a real
`NearTerrainProvider`. It owns:

- invalidation intake;
- snapshot capture;
- scheduling;
- worker contexts and mesh caches;
- region upload/publication;
- coverage publication;
- visibility and batches; and
- every main/shadow terrain submission.

The built-in and optimized profiles may initially select the same provider
implementation with different configuration/interop descriptors. A provider
must not claim independent optimized ownership until it owns the complete
path.

### Exported optimized structures

Export neutral structures needed for Sodium-style interoperability:

- compact semantic vertex stream description;
- material pass partitions;
- face-segmented ranges;
- section connectivity;
- region-relative positions;
- sprite/material usage metadata;
- draw-command batches; and
- exact layout/version descriptor.

Use capability names such as `COMPACT_VERTEX_ENCODING`,
`REGION_BATCHING`, `FACE_SEGMENTATION`, and `CPU_CONNECTIVITY`; do not embed a
foreign project name in core type names.

## Distant-terrain migration

### Data model

Replace the top-surface-only column with bounded vertical runs:

```text
DistantColumnRun
├── minimumY
├── height
├── block/material semantic ID
├── fluid semantic ID and level/class
├── block light
├── sky light
├── biome/tint input or resolved color generation
└── flags: opaque, emissive, cave/void, generated confidence
```

Runs are ordered top-to-bottom and bounded per column. Downsampling is
deterministic and preserves:

- topmost opaque and fluid surfaces;
- large material transitions;
- important emissive/light transitions;
- terrain extrema needed for cliffs;
- water beds where visible; and
- generation completeness/confidence.

The existing top-only tile may be decoded as a low-quality compatibility tier
during migration.

### Hierarchy and pages

Introduce a quadtree or equivalent hierarchical page index:

- fixed base source regions;
- provider-defined detail levels;
- independent dirty/source/render revisions;
- parent/child completeness;
- neighbour-detail constraints;
- per-page CPU artifact and GPU allocation;
- visible main/shadow lists; and
- bounded retrieval/generation state.

Camera movement changes selection and visibility. It does not rebuild all
distant geometry.

### Mesh generation

Generate true vertical faces from column runs instead of lowering relief and
inventing capped skirts as the primary representation. Mesh generation must:

- emit top, bottom where required, and four side directions;
- merge compatible quads greedily within a page;
- stitch adjacent detail levels deterministically;
- preserve fluid surfaces and beds independently;
- carry actual sky/block light and semantic material/tint data;
- produce compact provider-owned vertex streams; and
- expose diagnostic counts and a stable artifact digest.

Skirts remain an explicit fallback for missing/incomplete neighbours, not the
normal representation of cliffs.

### Near/distant seam

Replace binary whole-chunk exclusion with:

- readiness-aware near coverage;
- cell/page-granular masking;
- a bounded overlap ring;
- deterministic dither or morph transition;
- transition age tied to frame/world generation;
- last-known-good fallback when near replacement fails; and
- identical seam state for main and shadow views.

Near terrain wins depth after it is ready. Distant terrain remains available
behind the transition until near coverage is committed.

### Persistence

Replace whole-database snapshots with page/region records:

- world identity and schema version in every database;
- dirty page keys rather than full tile-list copies;
- incremental atomic records or transactions;
- bounded write queue and coalescing;
- crash recovery;
- schema migration from the current top-only format;
- distance/recency/dirty-aware eviction;
- dirty pages pinned until durable; and
- deterministic database inspection tools.

SQLite or region files are both acceptable after a focused benchmark. The
choice must be hidden behind a headless `DistantTerrainStore` contract.

### Local and managed-server generation

Use one normalized column-run sampler for:

- explored native chunks;
- local-authority generation;
- managed server capture; and
- fixture/test generation.

Fluid detection uses semantic block/fluid state rather than identifier suffixes.
Server work uses bounded, deduplicated, cancellable generation requests and an
accepted server/worldgen execution boundary. It must not synchronously force
expensive chunk generation from the normal server tick.

### Network protocol v2

Every message includes:

- protocol/schema version;
- connection/world epoch;
- normalized level key;
- request ID;
- bounded page keys/detail levels;
- source-data/schema revision;
- bounded payload counts and byte lengths.

The client records exact expected pages per request. It rejects:

- wrong world or epoch;
- unknown request ID;
- positions not requested by that ID;
- duplicate completed pages;
- unsupported schema/detail;
- stale pages older than local data; and
- responses beyond negotiated bounds.

The server keys queues by player plus world epoch, validates the request level
at admission and completion, deduplicates page work, shares results for
identical page requests where safe, and cancels on dimension change or
disconnect.

### Shader and view integration

Preserve explicit render-graph phases:

1. distant opaque;
2. distant-depth snapshot when requested;
3. distant water;
4. near opaque/cutout;
5. remaining world phases;
6. near translucent at its declared boundary.

The shader pipeline negotiates distant and near physical layouts separately.
Both built-in and Iris-compatible routes consume the same semantic frame and
coverage snapshot.

Distant shadow work uses its own page visibility list, configurable distance,
and transition state. Water participates only when the selected shader/view
contract explicitly requests it.

## Compatibility adapter migration

### Fabric layer responsibilities

An exact artifact adapter may:

- validate metadata and dependency constraints;
- expose mod-specific settings labels;
- translate exact foreign settings into neutral provider configuration;
- register payload/world/chunk hooks;
- negotiate a `TerrainInteropDescriptor`;
- select a terrain provider/profile; and
- expose a mod-specific debug facade.

It may not define:

- core terrain data structures;
- build scheduling or cancellation;
- mesh allocation/upload ownership;
- visibility and batching;
- render phases;
- world identity semantics; or
- stale publication rules.

### Naming migration

Expected direction:

| Current concept | Target concept |
| --- | --- |
| `SodiumRendererHook` | thin adapter registration or no renderer hook |
| `SodiumTerrainController` | `TerrainProviderSelection` in core, adapter facade in Fabric |
| `SodiumTerrainBackend` | `NearTerrainProvider` implementation/profile |
| `DistantHorizonsRendererHook` | `DistantTerrainRenderer` |
| DH-owned render data types | `terrain.distant` normalized types |
| adapter-owned mesh planner | distant provider mesher |
| adapter-owned worker | shared `TerrainRuntime` scheduler |

Keep compatibility aliases temporarily only when required to avoid an
unreviewable flag-day change. New core references must use neutral names.

## Layered test and agent-validation architecture

### L0: contract and codec tests

Runs without a world, renderer, or worker thread.

Cover:

- build/world/page identity equality and stale ordering;
- checked allocation arithmetic;
- descriptor capability negotiation;
- vertical-run encode/decode/downsampling;
- protocol v2 malformed, oversized, duplicate, wrong-epoch, and trailing data;
- persistence schema and migration fixtures;
- coverage state transitions;
- deterministic page and mesh digests.

Every codec has checked-in golden fixtures and a canonical human-readable dump.

### L1: deterministic terrain-model tests

Runs headlessly with normalized synthetic worlds.

Fixture catalog includes:

- flat plane;
- chunk and section boundary;
- mountain/cliff/overhang;
- cave and enclosed camera;
- ocean/coastline/waterfall;
- water, kelp, bubble column, waterlogged block, lava, and custom fluid;
- biome tint boundary;
- emissive cave and day/night light gradients;
- missing/incomplete neighbour;
- mixed LOD detail transition;
- negative coordinates and minimum/maximum world heights.

Each fixture asserts semantic streams, bounds, materials, light, connectivity,
coverage, and stable digests—not implementation-specific list iteration order.

### L2: runtime/concurrency/fault tests

Runs the real scheduler, mailbox, allocator, staging, and publication logic
with deterministic barriers and a fake clock.

Cover:

- latest-wins replacement;
- completion after invalidation;
- old-world completion after dimension change;
- cancellation before, during, and after build;
- queue saturation and provider fairness;
- upload budget deferral;
- allocation/staging/upload failure;
- shader/provider generation replacement;
- close during queued/running/completed work;
- failed replacement preserving old geometry;
- range and worker-context return to baseline;
- concurrent publish/snapshot/close;
- near coverage becoming ready during a distant build;
- repeated replace/retire with frame leases.

Fault injection is typed and test-scoped. Tests do not depend on sleeps except
for bounded watchdog assertions.

### L3: headless end-to-end runtime tests

Exercise:

```text
snapshot -> schedule -> mesh -> stage -> publish
         -> coverage -> visibility -> batch -> submit
```

Use dummy graphics resources and record immutable draw commands. Compare:

- legacy near output versus consolidated near output;
- low-quality legacy distant tiles versus migrated distant pages;
- near-only, distant-only, and combined scenes;
- main and shadow views;
- built-in and optimized interop descriptors;
- built-in and Iris-compatible layout negotiation.

The headless path must support a dual-build comparison mode that hashes both
candidates but publishes only the selected one. This provides agent-readable
parity evidence without double drawing or changing live state.

### L4: render-graph and real-GL integration tests

Run with the repository's dummy and real OpenGL fixtures.

Assert:

- stable pass order and resource dependencies;
- exact selected semantic/physical layouts;
- no duplicate provider submission;
- distant-depth snapshot timing;
- transactional GPU replacement;
- main/shadow frame snapshot agreement;
- draw/buffer/VAO/program counts returning to baseline after reload/unload;
- fallback path on systems without selected batching capability;
- shader reload while builds/uploads are outstanding;
- context recreation invalidating every GPU generation.

### L5: live acceptance scenarios

Add checked-in scenarios for:

- base near terrain;
- optimized near profile;
- distant terrain;
- combined near+distant;
- combined near+distant+Iris/Bliss;
- movement across the seam;
- partial native upload and replacement failure;
- teleport and fast camera movement;
- overworld/nether/end transitions;
- disconnect/reconnect to a same-named but different world;
- day/night and emissive caves;
- waterlogged/fluid coastline;
- shader toggle and reload;
- resource reload;
- main/shadow transition;
- bounded high-view-distance soak.

Every scenario records, before screenshots:

- exact endpoint and world epoch;
- player dimension/pose;
- loaded-only blocks around the camera;
- provider/layout/shader generations;
- page queue and upload state;
- near coverage and distant selection summaries;
- main/shadow draw-batch counts;
- resident GPU bytes and resource counts;
- fixture and presentation state.

Screenshots validate final pixels only after these invariants pass.

### L6: performance and soak acceptance

Define named repeatable workloads:

- steady camera, warm populated world;
- continuous nearby block invalidation;
- straight-line chunk streaming;
- fast seam traversal;
- distant database cold load;
- distant generation fill;
- dimension transition;
- shader reload with populated terrain;
- 30-minute steady-state resource soak.

Use interleaved baseline/candidate intervals, equal instrumentation state, and
at least 100 samples for p95 claims. Report median/p95 with queue depths,
utilization, bytes, visibility, batching, cancellation, and upload metrics.

Initial acceptance budgets:

- zero stale or wrong-epoch publications;
- zero visible page with an invalid generation;
- zero terrain GPU-resource growth after warm-up/reload/unload cycles;
- queues drain to zero at an idle boundary;
- no whole-distant-domain rebuild from one page or near-section change;
- no more than the existing telemetry overhead threshold of 10 percent plus
  100 microseconds;
- candidate p95 regressions require explicit review even when medians improve;
- draw submission scales with visible region/material/view batches, not
  section count;
- database writes scale with dirty pages, not retained database size.

Absolute CPU/upload/draw targets are established from baseline artifacts on the
named acceptance trajectory before the production backend switches.

### Required agent validation loop

An implementation agent follows the same loop for every phase:

1. resolve the exact source revision and record relevant dirty files;
2. select the smallest affected test layer;
3. capture a pre-change semantic/metric baseline;
4. make one ownership or behavior change;
5. run the lowest affected layer until deterministic;
6. run every lower layer that guards its dependencies;
7. inspect typed state and resource deltas;
8. advance to real-GL or live scenarios only after headless gates pass;
9. restore live state and verify endpoint/resource cleanup; and
10. update the evidence map with the accepted invariant and exact validation.

Default command shape:

```sh
# L0-L2: focused contracts, models, runtime, and fault paths
JAVA_HOME=/path/to/java25 ./gradlew :test --tests '<focused-class>'

# L3: headless end-to-end terrain pipeline
JAVA_HOME=/path/to/java25 ./gradlew :test --tests '*Terrain*Pipeline*'

# L4: dummy/real graphics integration
JAVA_HOME=/path/to/java25 ./gradlew integrationTest

# L5-L6: exact live trajectory and retained artifacts
./play.sh status --json
./play.sh scenario run acceptance/scenarios/<terrain-scenario>.json \
  --trajectory <named-trajectory> --json
./play.sh status --json
```

The concrete focused class and scenario names are introduced with the owning
phase. Broad tests do not replace focused failure evidence.

Every completed phase produces a small evidence manifest containing:

- source commit and dirty-file scope;
- Java/runtime/driver identity where relevant;
- test commands and exit status;
- fixture/scenario identifiers;
- before/after semantic digests;
- before/after metric deltas;
- known exclusions;
- retained artifact paths; and
- live-state restoration result.

### Phase-to-layer validation matrix

| Phase | Mandatory layers | Advance only when |
| --- | --- | --- |
| 0: baseline | L0, L1, selected L4-L6 | fixtures, schemas, workload, and resource baselines are reproducible |
| 1: contracts/packages | L0-L3 | dependency direction is enforced and behavior/digests are unchanged |
| 2: scheduler/identity | L0-L3 | deterministic concurrency and fault tests prove stale/wrong-world rejection |
| 3: immutable near meshing | L0-L5 | semantic parity, multi-version fixtures, and representative pixels pass |
| 4: storage/batching | L0-L4, L6 | rollback/resource balance and measured scaling pass |
| 5: coverage/seam | L0-L6 | partial readiness, movement, shadow, and transition evidence pass |
| 6: distant hierarchy | L0-L6 | geometry fixtures, page-local work, and high-distance scaling pass |
| 7: store/network/generation | L0-L3, L5-L6 | corruption, epoch, parity, crash, and server-budget evidence pass |
| 8: shader/interoperability | L0-L6 | every accepted profile has route, resource, pixel, and reload evidence |
| 9: production/retirement | complete L0-L6 matrix | default path, soak, cleanup, documentation, and rollback boundary are accepted |

Failures stop at their lowest reproducing layer. An agent does not compensate
for a failed semantic or lifecycle test by updating a screenshot baseline.

## Agent-facing observability

Extend `metrics.snapshot` with low-cost aggregate terrain fields and
`render.substrate` with detailed boundary snapshots.

Add or evolve bounded operations:

- `render.terrain.summary`
- `render.terrain.pages`
- `render.terrain.coverage`
- `render.terrain.page`
- `render.terrain.flush-idle`
- `render.terrain.compare`
- `render.terrain.fault` for acceptance-only/test builds

Required behaviors:

- every response includes endpoint generation, frame, world epoch, provider
  generation, and shader/layout generation;
- page lists are bounded, spatially filtered, and sorted deterministically;
- page detail reports identity, state, source, revisions, ranges, visibility,
  coverage, rejection reason, and artifact digest;
- `flush-idle` waits for a named bounded condition rather than sleeping;
- `compare` runs a selected deterministic dual-build fixture and returns
  semantic/digest differences;
- fault injection returns a restoration token and cannot survive generation
  replacement or disconnect;
- capabilities advertise every operation and schema version;
- endpoint cleanup is verified after shutdown.

Durable conclusions go into evidence maps. Raw lifecycle logs and `.run/`
state remain untracked.

## Delivery plan

### Phase 0: baseline and contract freeze

Deliver:

- name the canonical workloads and fixtures;
- capture current near/distant semantic outputs, visual references, telemetry,
  and GPU-resource baselines;
- document exact current provider, shader, and layout generations;
- define executable fixtures and regression-test specifications for
  cross-dimension, stale-completion, partial-coverage, fluid-parity, and
  persistence-burst defects;
- define descriptor and debug JSON schemas.

Exit gate:

- baseline artifacts are reproducible;
- known defects have deterministic reproductions that are fixed in their
  owning slice before its test-first branch is merged;
- no architecture migration begins without a measurable comparison boundary.

Rollback:

- documentation/tests only; no runtime selection changes.

### Phase 1: neutral contracts and package boundaries

Deliver:

- move normalized distant data/render types out of the Fabric adapter package;
- introduce `TerrainProvider`, `NearTerrainProvider`,
  `DistantTerrainProvider`, and `TerrainInteropDescriptor`;
- rename renderer-facing compatibility classes to responsibility-based names;
- leave exact mod probes/settings/debug facades in Fabric;
- preserve temporary aliases where required.

Exit gate:

- core rendering packages do not import Fabric adapter classes;
- adapters depend inward on core contracts;
- focused registration, close, headless, and exact-artifact tests pass;
- no rendering behavior changes.

Rollback:

- aliases and old registration factories can restore the prior entry points.

### Phase 2: consolidated identity, scheduler, and mailbox

Deliver:

- world epoch and consolidated build identity;
- multi-provider bounded scheduler with fairness and separate CPU/upload
  admission;
- centralized stale/cancellation gates;
- one completion mailbox and lifecycle owner;
- deterministic fake-clock/barrier test harness.

Exit gate:

- wrong-world and stale completions cannot publish;
- shutdown drains/cancels every provider job;
- queue saturation is bounded and observable;
- existing near and distant workers run through the shared runtime behind a
  feature switch.

Rollback:

- select legacy per-provider schedulers without changing data formats.

### Phase 3: immutable near-terrain meshing

Deliver:

- solid/fluid/model/tint/light/entity meshing over detached snapshots only;
- candidate-only reusable caches;
- semantic mesh artifacts;
- dual-build digest comparison against the legacy mesher.

Exit gate:

- no geometry worker reads a live chunk/section;
- supported-version fixture outputs match or have reviewed intentional deltas;
- stale model revisions cannot publish;
- headless and real-GL near-terrain gates pass.

Rollback:

- publish legacy near artifacts while retaining comparison telemetry.

### Phase 4: shared staging, region storage, and batching

Deliver:

- production region allocators and staging;
- atomic page/range publication;
- layout-generation leases;
- cached region/material/view batches;
- conventional draw fallback;
- byte/time budgets and allocation telemetry.

Exit gate:

- failed allocation/upload preserves active geometry;
- all ranges/resources return to baseline;
- selected providers no longer own one VBO/draw per section material;
- draw and upload scaling pass the named workload gates.

Rollback:

- select per-section upload/submission while retaining the new artifact
  boundary.

### Phase 5: readiness-aware coverage and seam

Deliver:

- spatial near coverage state machine and revision;
- surface-relevant readiness;
- frame-pinned coverage snapshot;
- page/cell masking and overlap transition;
- main/shadow agreement;
- agent-readable coverage diagnostics.

Exit gate:

- partial vertical section uploads do not remove distant surface coverage;
- failed near replacement produces no hole;
- one section replacement without coverage change does not rebuild distant
  terrain;
- fixed movement scenarios show bounded, deterministic transitions.

Rollback:

- disable transition and use conservative overlap, never premature exclusion.

### Phase 6: hierarchical distant data and meshing

Deliver:

- vertical-run data model and deterministic reducer;
- hierarchical page index;
- per-page build/upload/publication;
- true vertical geometry, greedy merging, fluid/light/tint support;
- incomplete-neighbour fallback;
- independent main/shadow visibility and batches.

Exit gate:

- one page change rebuilds/uploads only affected pages and constrained
  neighbours;
- cliff, cave, coast, fluid, tint, and light fixtures pass;
- camera movement changes selection rather than rebuilding all geometry;
- top-only legacy data remains readable during migration.

Rollback:

- select low-quality top-only page meshing through the same page/runtime
  contracts.

### Phase 7: incremental persistence, generation, and protocol v2

Deliver:

- page-oriented store and migration;
- dirty-page writer and crash recovery;
- normalized local/server sampler;
- bounded managed-server generation;
- world-tagged request/response protocol with correlation and cancellation;
- store/network inspection tools.

Exit gate:

- dimension and reconnect tests prove zero cross-world publication;
- persistence cost follows dirty pages;
- server workload remains within the named tick/TPS gate;
- corrupt/oversized/old schema inputs fail safely;
- local, native, server, and persisted pages have semantic parity.

Rollback:

- disable network generation and retain local/explored page sources; preserve
  readable migrated storage.

### Phase 8: shader, shadow, and interoperability completion

Deliver:

- distinct negotiated near/distant layouts;
- complete built-in and Iris-compatible routes;
- distant depth, water, shadow distance/culling, and seam state;
- optimized interop descriptor and exported compact structures;
- reload/context-loss generation retirement.

Exit gate:

- all compiled terrain scene ABIs submit through declared routes;
- no fallback or duplicate draws in accepted profiles;
- shader reload with outstanding terrain work preserves last-known-good state;
- GPU resource counts return to baseline;
- Bliss and built-in visual scenarios pass.

Rollback:

- reject unsupported shader/provider candidate and retain the last-known-good
  generation.

### Phase 9: production switch and legacy retirement

Deliver:

- make the consolidated runtime the default;
- run extended matrix and soak gates;
- remove compatibility aliases and legacy workers/upload paths;
- update functionality catalog and evidence maps;
- retain database migration and conventional draw fallback.

Exit gate:

- every definition-of-done item below is satisfied;
- no active class or debug claim gives a foreign compatibility artifact
  ownership of Minosoft core rendering;
- clean builds and live handoff leave no transient overrides or endpoint state.

Rollback:

- before legacy deletion, retain one release boundary with a safe runtime
  selection switch and documented data compatibility.

## Work-package dependency order

```text
P0 baselines
  -> neutral contracts
    -> world/build identity
      -> shared scheduler/mailbox
        -> immutable near mesher
          -> shared storage/upload/batching
            -> readiness coverage
              -> distant hierarchy/data/mesher
                -> persistence/generation/network
                  -> shader/shadow interop
                    -> production switch
```

Parallel work is allowed only after the contract it depends on is accepted.
For example, persistence and network codec work can proceed alongside GPU
storage after the distant page/data schema is frozen.

## Review boundaries

Keep changes reviewable:

- contract/naming moves;
- identity and scheduler;
- immutable near meshing;
- storage/staging;
- batching/submission;
- coverage/seam;
- distant data model;
- distant mesher/hierarchy;
- persistence;
- network/server generation;
- shader/view integration;
- debug/acceptance tooling;
- legacy removal.

Do not combine a mechanical package rename with geometry behavior changes.
Each behavior slice includes its focused tests and evidence-map update.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| A “unified” runtime becomes a lowest-common-denominator abstraction | Share lifecycle and resource mechanics; keep provider-owned snapshot, mesher, page, and layout types |
| Flag-day migration breaks an already dirty rendering tree | Use adapters and temporary aliases; migrate one ownership boundary per change |
| Dual-build validation doubles hot-path cost | Acceptance/debug only, bounded fixtures or sampled pages, never double draw |
| Region allocation introduces hard-to-reproduce holes | Atomic page-table publication, deterministic allocator traces, typed fault injection |
| Distant hierarchy creates cracks between detail levels | Neighbour constraints, deterministic transition fixtures, semantic edge diagnostics |
| Cross-world data survives a lifecycle boundary | World epoch in every key/job/message/store transaction and centralized publication validation |
| Observability changes the measured workload | Primitive counters, fixed histograms, explicit sampling, telemetry overhead A/B gate |
| Visual baselines hide state drift | Pose/world/coverage/generation assertions must pass before screenshots |
| Compatibility labels overstate behavior | Descriptor capabilities reflect implemented ownership; adapters report partial support until every required capability is present |
| Legacy and consolidated stores diverge during rollout | One-way versioned migration plus read-only legacy decoder; never write two authoritative stores |

## Definition of done

Architecture:

- Fabric compatibility packages contain probes and translations, not terrain
  runtime implementations.
- Near and distant providers run through one scheduler, completion, upload,
  storage, publication, lease, telemetry, and frame-snapshot substrate.
- Near and distant layouts remain independently negotiated.
- One spatial page change never requires a whole distant-domain rebuild.

Correctness:

- all geometry is derived from immutable normalized input;
- wrong-world, stale-generation, unsolicited, and cancelled results cannot
  publish;
- failed candidates preserve last-known-good geometry;
- near/distant handoff cannot create a hole from partial readiness;
- vertical terrain, fluid, light, tint, and shadow semantics pass fixtures.

Scalability:

- CPU and upload work are independently bounded;
- GPU objects and draw calls scale by region/material/view batches;
- persistence and network work scale by changed/requested pages;
- idle queues drain and resource counts return to baseline.

Testability:

- L0 through L4 run without manual gameplay;
- every lifecycle transition has deterministic success/failure coverage;
- agent-facing snapshots explain page, coverage, generation, and resource state;
- checked-in live scenarios cover transitions, reloads, dimensions, fluids,
  shaders, and soak;
- performance claims use matched workloads and retained JSON/JUnit artifacts.

Compatibility:

- built-in, optimized-compatible, distant-compatible, combined, and
  Iris/Bliss profiles negotiate explicitly and fail closed;
- supported Minecraft versions retain normalized behavior;
- headless operation remains available;
- exact foreign artifact names do not define core rendering ownership.

## First implementation slice

Begin with Phase 0 and Phase 1 only:

1. add the deterministic fixtures, test specifications, and first passing
   contract tests for lifecycle, coverage, and network identity;
2. freeze `TerrainWorldIdentity`, `TerrainBuildIdentity`,
   `TerrainInteropDescriptor`, and provider interfaces;
3. move renderer-facing distant data types to a neutral terrain package;
4. replace Sodium/DH-named renderer classes with neutral providers/renderers
   while keeping exact Fabric adapters;
5. add debug schema fixtures for provider, identity, page, and coverage state;
6. prove no behavioral or pixel change before starting scheduler or mesher
   migration.

This establishes the dependency direction and agent-visible test seam without
mixing it with the higher-risk runtime and geometry changes. A regression test
that exposes current behavior is merged with the owning fix; the accepted
branch does not remain red and does not encode a known defect as expected
behavior.
