<!-- Copyright (C) 2026 Jacob Repp -->

# Terrain consolidation implementation start

## Status

The Phase 0/Phase 1 contract boundary and the production Phase 2 through Phase
9 implementation slices from the
[terrain pipeline consolidation plan](../../design/terrain-pipeline-consolidation-plan.md)
form the current implementation checkpoint. Near and distant work now
share the process CPU service, semantic artifact and transactional region
storage boundaries, and bounded upload/publication rules. The consolidated
near-region and distant-hierarchy paths are the sole production route. The
retained multi-version, seam-motion, server/reconnect, visual, performance,
diagnostic-generation, reload, retirement, and post-retirement soak gates below
pass. Every checked L5 scenario now passes. The named L6 workload matrix,
including the quiescent 30-minute steady-state resource soak, passes on the
qualified Apple OpenGL driver. This completes the Phase 0 through Phase 9 plan.
A second-driver resource/reload run remains deferred portability evidence, not
a terrain-consolidation completion gate.

## Headless contract boundary

The dependency-clean `render-contracts` module now owns:

- immutable world, page, and complete build identities;
- bounded urgency/tenant-aware CPU scheduling, logical cancellation, a bounded
  completion mailbox, and fixed primitive runtime snapshots;
- checked absolute/page-relative coordinates, origin-rebased frame transforms,
  and the initial forward OpenGL depth declaration;
- the readiness-aware coverage lifecycle and deterministic snapshots;
- provider-neutral interoperability declarations and near/distant provider
  markers;
- immutable material-table generation metadata and explicit geometry-reuse
  decisions;
- the sole `UNIFIED` production mode after retirement of the rollout modes;
- one atomic, leased near+distant+material+shader pipeline generation;
- process, rendering-device, and world-epoch runtime scope contracts;
- checked world/device residency accounting;
- device-neutral submission serial and completion state;
- structured failure categories and event-gated retry/quarantine rules; and
- immutable distant authority/provenance records with deterministic atomic
  merge outcomes.

These contracts use Kotlin/JDK types only. They do not depend on the
application world model, GUI, Fabric, a window, LWJGL, or OpenGL.

The pipeline registry constructs and validates the complete candidate before
publication, leases one complete generation, and retires all candidate-owned
resources in reverse order only after the last lease closes. Candidate
construction failure preserves the active generation. The application-owned
`ProductionTerrainPipelineRegistry` now composes leases from the selected near
backend and shader pipeline with the stable distant provider, material table,
distinct physical layouts, declared views/passes, and render-graph generation.
Every production frame leases this complete generation and drives both near
preparation/submission and shader execution through its exact component
leases. Component replacement publishes one new complete generation; retired
backend and shader resources remain alive until the composite lease drains.

## Diagnostic schema freeze

The headless contract layer now defines version-one terrain diagnostic
capabilities, structured rejection codes, and one immutable
`TerrainDiagnosticSnapshot`. A snapshot aligns world, provider, pipeline,
material, coverage, page-registry, residency, and submission generations.

Page queries require the world epoch, an area or canonical-prefix selector, and
an explicit maximum of 1–1,024 results. Continuation cursors are opaque,
URL-safe, and bound to the query plus the selected domain's page-registry
generation. A changed selected registry, selector, world, or malformed cursor
rejects explicitly instead of continuing against mixed state; unrelated-domain
publication and ordinary frame advancement do not invalidate a continuation.

JDK-only canonical JSON encoders and three checked version-one fixtures freeze
capability, rejection, provider, identity, page, coverage, and snapshot field
semantics. A capability-scoped response envelope permits partial production
adoption without inventing unavailable state.

The client now registers `render.terrain-diagnostics`. Its initial
provider-only compatibility capture remains available to callers without the
consolidated owners, but the production operation captures the complete world,
provider, pipeline/material, coverage, page-registry, residency, and submission
shape under one render-thread backend lease. Production advertises every
version-one capability. Unknown selectors, wrong epochs, invalid bounds, and
stale/malformed/mismatched cursors return structured rejections.

The provider-neutral selection boundary now owns both near and distant view
publication. `TerrainSelectionPublication` generation-pins desired and active
page versions, retains last-known-good pages until exact replacements are
resident, and emits a bounded immutable per-view snapshot. Near region batching
now consumes that active selection instead of bypassing the shared owner;
distant main/shadow selection continues through the same contract. The complete
atomic diagnostic advertises `PUBLICATION_STATE` and `VISIBILITY_STATE` and
reports, per provider domain, storage/selection generations, resident/retained
counts, and desired/visible/missing/masked/drawn page counts. Exact page
membership remains behind the existing bounded page query rather than making
the summary unbounded.

A Java 25/Apple OpenGL 4.1 Bliss local-world run then confirmed the production
wire boundary. At frame 1,228 the complete snapshot advertised both new
capabilities with no unavailable subject. Near main/shadow each reported 66
desired, visible, drawn, resident, and retained pages, zero missing/masked
pages, stable selection generation 40, and storage generation 75. Distant
main/shadow each reported 687 desired/visible pages, 60 coverage-masked pages,
627 drawn pages, zero missing pages, and exactly 687 resident/retained pages.
The shared scheduler simultaneously reported zero queued, active, outstanding,
or completed-but-undrained work. The isolated client stopped cleanly; its
endpoint and lease were removed, no server was started, and no process or
transient presentation override remains.

## Package-direction ratchet

`TerrainArchitectureBoundaryTest` scans current main source and rejects:

- imports from `modding.loader.fabric` in `terrain` or
  `gui.rendering.terrain`; and
- any new Fabric implementation of renderer, terrain-backend, or
  terrain-provider interfaces.

The Sodium lifecycle implementation has moved into the neutral
`TerrainProviderRendererBridge`, and the Iris presentation lifecycle has moved
into the neutral `ShaderPipelineRendererBridge`; Fabric retains only the
exact-artifact builders, controllers, settings/events, and diagnostic
callbacks. The renderer/provider ratchet now has no allowlist entries.

Normalized detached distant columns, tiles, bounded tile storage, source
provenance, snapshots, renderer-facing diagnostic DTOs, and neutral
render-source/configuration seams live under `terrain.distant`. The renderer,
shader, planner, mesh builder, and coverage helpers now live under
`gui.rendering.terrain.distant`. Fabric imports those types inward for
exact-artifact activation, source ingestion/controller ownership, v1 protocol,
persistence, settings, debug translation, and renderer registration. The
architecture ratchet prevents those normalized declarations from returning to
Fabric and prevents the headless scheduler/mailbox types from returning to the
application rendering package.

This was a package-ownership move only. The owner ID, v1 persistence bytes,
network messages, tile planner, render phases, material/surface/source wire
values, and pixel behavior are unchanged. Shared scheduling, visibility,
residency/GPU ownership, and complete-provider publication remain later
consolidation work.

## Neutral near-provider ownership

The Sodium adapter still recognizes the exact artifact, supplies the existing
owner/implementation descriptor, exposes settings/debug translation, and
installs its renderer event hook. Provider selection and the concrete
chunk-renderer delegation now live under
`gui.rendering.terrain.near.provider` as `TerrainProviderSelection` and
`ChunkRendererNearTerrainBackend`.

The preserved descriptor values are:

- owner: `minosoft:sodium-compatible-terrain`;
- implementation: `sodium-0.5.8-adapter-minosoft-core`.

Focused tests protect those values, delegated frame operations, idempotent
close, use-after-close rejection, and adapter activation. This is an ownership
move only; it does not claim upstream Sodium algorithms or change rendering
output.

## Phase 2 identity and scheduler start

The JDK-only `TerrainBuildRuntime` now lives in `render-contracts` rather than
the application rendering package. It owns a bounded queue and completion
mailbox whose outstanding cap includes queued, running, and
completed-but-undrained work. Routed tenant completions retain that global
capacity until their owning render thread consumes or disposes them; pumping
another tenant can no longer release the slot early. Jobs carry the frozen
`TerrainBuildIdentity`, a stable tenant, an explicit urgency, and positive
estimated CPU/output cost. Count, estimated CPU backlog, and estimated output
bytes have independent process admission ceilings. Equal-urgency work rotates between
tenants, near work wins the ordinary tie, and a configured near burst reserves
bounded progress for queued distant work. Worker contexts remain reusable and
close with the runtime; cancellation remains logical and never interrupts a
worker that may already have advanced to another job.

The immutable runtime snapshot exposes queue, raw/routed completion,
outstanding, and active depths; count and estimate high-water marks and budgets;
CPU/output-specific admission rejections; and requested, admitted, rejected,
started, completed, cancelled, and failed counters. Completion draining now
requires an explicit positive bound. The production near queue drains a bounded
amount per frame and drains all retained completions during owned shutdown.

`World.terrainEpoch` advances at the existing reset/clear boundary, including
an empty world. Near section requests translate to checked near-domain page
keys carrying that epoch and now capture the request/model, exact selected
provider/layout component, exact selected shader-material component, coverage,
and priority identity. The production complete-pipeline registry pins those
component generations together for each frame, while build identities retain
the individual fields needed for precise mismatch classification.
Publication constructs the current identity and calls the centralized mismatch
function; an old-world, stale request/model, provider/layout, or shader-material
result cannot publish. The former application-local duplicate build identity
was removed.

The process-scoped `TerrainProcessBuildService` now gives near and distant
renderers typed tenant leases over one bounded scheduler and completion
mailbox. Per-worker provider contexts remain reusable; completion routing does
not invoke another world's publication callback; tenant shutdown cancels and
drains only owned work, disposes retained results, closes reusable contexts,
and permits reload to register the same tenant again. The distant renderer no
longer owns an executor or `CompletableFuture`, and its completions use the same
centralized identity mismatch gate as near terrain.

The dependency-clean `TerrainPageFailureRegistry` now owns bounded,
generationed page failure state and event-gated retry eligibility. The
production distant hierarchy replaces its raw attempt map with this registry:
transient source/artifact build failures back off monotonically, stop after
three attempts, retain last-known-good CPU/GPU pages, clear on changed source
or shader generation, and expose their typed category, phase, generation, and
retry rule through bounded page diagnostics. Near meshing uses the same owner
for snapshot, build, and render-thread publication failures. It retains the
failed request until monotonic backoff elapses, advances request identity on
each admitted retry, stops after three attempts, and clears quarantine on a
new build input, successful publication, unload, or world reset. This also
replaces the former snapshot-capture retry call that duplicate suppression
could consume before the failed request identity was retired.

Near page diagnostics now project the actual section registry rather than
reusing column-level seam coverage cells. Loaded, upload-pending, requested,
building, and quarantined section identities merge by their canonical page key;
a failed replacement retains `READY` when last-known-good geometry exists, and
otherwise reports `ABSENT` with the typed failure and retry rule. Near cursors
bind to the combined loaded/upload/meshing registry generation, while seam
coverage remains independently available in the coverage snapshot. Focused
tests pin a section-zero replacement and another failed vertical section
without inventing a column alias.

The deterministic cross-provider barrier now starts near and distant work in
the same shared service, advances the expected world epoch before release, and
proves both completions reject as `WORLD_EPOCH` without publication or retained
capacity. Together with the existing saturation, fairness, routed-mailbox,
owned-shutdown, context-reuse, and coherent runtime-mode tests, and the
architecture ratchet that pins both production routes to the process service,
this closes the Phase 2 exit gate. Render-thread upload admission remains
independently CPU/byte bounded at the storage boundary.

Production frame publication now also uses the dependency-clean complete
`TerrainPipelineRegistry` rather than a diagnostic-only selection clock. The
render-thread coordinator republishes only when the near/distant provider,
layout, material/shader, runtime mode, or graph identity changes. It pins the
exact near backend and world shader selected by that generation across async
preparation and graph execution, failure-tolerantly closes unfinished frame
lifecycle state, and releases complete-generation leases before renderer
unload. Terrain diagnostics report this authoritative generation. Focused near
and shader registry tests cover exact composite pinning and failed-frame
cleanup; the integration lifecycle test proves component retirement waits for
complete-generation replacement, and the architecture ratchet requires the
production frame and diagnostic paths to consume the complete registry.

## Phase 3 detached near-meshing start

Production solid and fluid geometry now read block state, neighbour opacity,
effective light, biome/tint inputs, and block-entity positions from a detached
`TerrainBuildSnapshot`. Ambient occlusion, smooth light, fluid height/culling,
and tint caches have snapshot-backed paths. Concrete block-entity renderers are
materialized only on the render thread after the complete build identity is
accepted. Focused mutation-after-capture tests prove that later block, light,
and fluid changes are not observed by the candidate, and the architecture
ratchet pins production solid/fluid dispatch to the snapshot overloads.
Entity-dependent block models receive a candidate-local entity reconstructed
from recursively detached NBT rather than the live section entity. Sign front/
back text now participates in that snapshot boundary. No live block entity is
retained by the snapshot or semantic artifact.

The dependency-clean `TerrainMeshArtifact` envelope owns immutable checked
vertex/index streams, semantic partitions, bounds, connectivity, coverage and
entity metadata, exact byte counts, deterministic versioned SHA-256 digests,
semantic difference classification, and idempotent release. `UNIFIED_COMPARE`
attaches this envelope to production near candidates and releases its duplicate
CPU bytes after legacy GPU upload or discard. The default `UNIFIED` route
consumes the same envelope through region storage directly. A
deterministic detached-versus-legacy solid matrix matches through the semantic
digest boundary for 1.7.10, 1.12.2, and 1.19.3 across opaque, tinted, explicit
light, detached-NBT meshed entity, renderer-entity, and neighbour-culling
fixtures. The same supported-version matrix now runs real registry water/lava
blocks through the detached production `FluidSectionMesher`, the production
water/lava model and tint branches, and deterministic headless physical texture
roles. Still water, flowing water, and still lava each retain an exact frozen
artifact digest, byte count, bounds, translucent semantic partition, and quad
topology. All three versions normalize to the same accepted signatures; the
fixture also proves the water-flow and water/lava roles remain distinct. The
semantic builders are explicitly dropped after the copied artifact is frozen,
so the comparison no longer relies on finalizer cleanup.

The Phase 3 exit gate now passes. The complete supported-version integration
matrix has no outstanding solid/fluid/model/tint/light/entity semantic delta,
and the Apple OpenGL 4.1 Bliss run exercised 66 region-backed near pages through
all main/shadow opaque, cutout, and translucent submissions. It reported zero
failed builds/uploads, zero missing publication pages, no stale or wrong-epoch
publication, and clean resource teardown.

## Phase 4 transactional region-storage start

The dependency-clean `terrain.runtime.storage` package now owns checked region
extents, separate deterministic vertex/index allocators, exact immutable upload
plans, atomic page-table replacement, material partitions, layout-generation
validation, CPU range leases, and device-submission retirement. Replacement
uploads always target newly allocated ranges; an allocation or upload failure
releases only the candidate and leaves the active page intact. Retired ranges
remain unavailable until every CPU lease closes and every associated device
serial reports complete. Device invalidation returns logical allocation
accounting to baseline. Cached region/material/view templates issue a fresh
lease per frame, and the contract includes a deterministic conventional draw
loop plus independent CPU-time/upload-byte budget selection.

The default built-in OpenGL adapter uses 8-by-32-by-8 section regions under a
fixed 16-region, 320 MiB device-allocation ceiling. Each region reserves 16 MiB
for vertices and 4 MiB for indices; the original 8+2 MiB split could saturate
one ordinary surface region while most of the process-wide ceiling remained
unused, permanently deferring the first completed upload. It uploads artifacts into
one vertex/index buffer pair per region, submits one multi-draw per non-empty
region/material/view batch when selected, retains an explicit per-command
base-vertex fallback, reuses one bounded 16 MiB ordinary-update staging buffer,
and fences each submitted terrain frame. Completed GL
fences permit range reuse; wait/fence failure remains pinned instead of being
treated as CPU-frame completion. Region-backed candidates discard their
superseded per-section VBOs while retaining block-entity lifecycle. Upload work
occurs outside the loading-queue lock and is admitted under both the existing
render-thread time budget and a fixed 16 MiB per-frame byte budget. Capacity or
region-count rejection falls back to the conventional per-section VBO for that
candidate while preserving any last published region page. This mixed
submission is intentional: a bounded region ceiling must degrade batching, not
leave visible terrain permanently queued.

`render.substrate.terrain.regionStorage` reports active/retired pages and
bytes, separate arena allocation and high-water values, staging capacity,
publication/allocation/upload outcomes, batch-cache behavior, draw
batches/commands/vertices, and pending submission fences. Region storage now
also classifies every range-retirement hold: active CPU lease count, pending
submission count, failed submission count, device-invalidated submission
count, and retired-page counts for each hold reason. Completion polling remains
outside the storage monitor. Failed and device-invalidated serials stay pinned
and diagnosable rather than becoming reusable; an explicit context/device
invalidation is the only recovery boundary and increments a monotonic recovery
counter while returning every logical allocation and hold count to baseline.
Near `render.substrate` and distant hierarchy diagnostics aggregate the same
fixed counters. Focused fault tests cover pending-to-complete retirement,
failed submission pinning, device-invalidated pinning, and context recovery.
Submission collection now prunes completed serials from active as well as
retired allocations. This keeps memory and `pendingSubmissionCount` bounded by
the current in-flight page set rather than total rendered frames while retaining
failed/device-invalidated references for explicit recovery.
The render context now also owns one device runtime identity and one monotonic
submission sequencer shared by near and distant storage. Submission serials are
therefore unique across both terrain domains and can be combined into one
device diagnostic without inventing a provider-local identity; layout
replacement no longer masquerades as OpenGL context replacement.

`render.terrain-diagnostics` and the bounded `render.terrain.summary`,
`.pages`, `.coverage`, and `.page` operations return the complete atomic snapshot shape
from one render-thread capture rather than the former provider-only production
subset. The capture holds the selected near-provider lease and aligns world,
coverage, near/distant provider descriptors, independently negotiated layouts,
material/content generation, render graph, device residency, recovery holds,
and the shared submission clock. It also captures the shared process scheduler
and mailbox, including global admission/high-water counters and deterministic
per-tenant owner, accepting, active, outstanding, routed-completion, and worker-
context state. A composite generation clock advances for any
provider, layout, material, shader, graph, or runtime-mode selection change,
including changes that do not rebuild the render graph. The optional bounded
page window reads near section pages from the merged loaded/upload/meshing
registry and filters distant pages inside the hierarchy owner, stopping after
the requested count plus one lookahead record. Both domains include typed
failure/retry state when quarantined. Opaque continuations bind to the selected
domain's registry generation; the response uses the aggregate near+distant
generation only when no page domain is selected. Column-level seam cells remain
in the separate coverage snapshot, and no page detail is fabricated from the
older bounded adapter diagnostic.

Artifact digest schema 2 carries explicit triangle/quad topology through the
immutable stream, published page, cached draw command, and OpenGL submission.
The region adapter groups a material/view batch by topology and reports the
actual resulting device-batch count; the native-quad dummy renderer fixture
proves detached and legacy capture retain identical quad streams. The obsolete
application-local allocator, staging, batching, and reference pipeline were
removed. Their L3 coverage now executes the real shared scheduler, artifact,
transactional storage, coverage, visibility, batch, submission, and retirement
contracts, and an architecture ratchet prevents another application-local copy.

The first matched fixed-camera scaling comparison exposed a hot-path mistake:
near submission rebuilt the resident-version map once per material. The region
runtime now captures resident versions and active publication once per view per
frame. On the same isolated 1.20.4 local world, seed
`6072333650475958863`, pose `(0.5, 20, 0.5, yaw 0, pitch 0)`, 441 loaded
chunks, Bliss 2.1.0 generation, Apple OpenGL 4.1 device, and 600-sample
measurement window, unified submission measured median 83,292 ns and p95
233,625 ns. The release-boundary legacy route measured median 95,292 ns and p95
240,292 ns. Unified storage submitted exactly 24 device batches for 66 active
pages: four occupied regions times three material classes times two views. It
published 73 candidates, uploaded 19,395,216 bytes, retained 17,380,464 bytes,
and reported zero allocation or upload failures. The 73 upload observations are
below the 100-sample p95 minimum, so no upload-latency percentile claim is made.
The current preparation timer is also excluded: it spans asynchronous prepare
through later frame completion and therefore includes intervening hierarchy
work only in the unified run.

The second named workload moved the same debug-world camera 33 blocks forward
and then drained every queue. It produced 115 near upload observations: median
500 microseconds and p95 one millisecond by histogram bucket bound, maximum
2,043,542 ns, and 30,867,144 uploaded bytes. At the idle boundary 85 near pages
occupied four regions and submitted 21 device batches for 390 commands; the
distant hierarchy retained 2,380 selected pages in three physical storage
shards and submitted nine batches for 931 commands. Both domains reported zero
allocation/upload failures and zero queued, outstanding, or pending upload
work. Together with the fixed-camera result, transactional failure tests, and
resource-lifecycle suite, this closes the Phase 4 exit gate. The retained
measurements are in
`2026-07-31-terrain-near-fixed-camera-performance.json` and
`2026-07-31-terrain-streaming-performance.json`.

## Phase 5 readiness snapshot start

`TerrainCoverageTracker` now applies the complete dependency-clean
`ABSENT`/`REQUESTED`/`BUILDING`/`UPLOAD_PENDING`/`READY`/`RETIRING` lifecycle.
Its spatial revision changes only when contributed surface coverage changes,
while a separate lifecycle revision keeps state diagnostics current. Once a
page is ready, replacement requests, builds, upload deferral, and retries retain
last-known-good coverage until explicit retirement/removal. Immutable snapshots
carry deterministic cells, transition ages, contributed pages, world epoch,
provider generation, and both revisions.

`TerrainCoverageMasking` derives deterministic near/distant draw membership and
weights from the pinned cell age. A bounded transition overlaps initial near
readiness and reverses during retirement; the disabled rollback policy keeps
conservative full overlap instead of prematurely excluding distant geometry.

`TerrainSceneSnapshot` pins one camera/render origin, near coverage object,
distant-page selection, seam policy, layout/material generations,
and declared main/auxiliary views for a frame. View snapshots share the same
coverage and distant selection instances, preventing main and shadow from
recapturing different generations. The existing production near surface index
now delegates to the canonical lifecycle: requests, worker starts, completed CPU
artifacts, and full heightmap-required surface uploads publish
REQUESTED/BUILDING/UPLOAD_PENDING/READY without removing last-known-good
replacement coverage. Its spatial revision changes only when the ready chunk
set or owning world/provider generation changes.

The production hierarchy pins that immutable lifecycle snapshot once at frame
preparation. It keeps selected seam pages resident, then derives both main and
shadow draw lists from one `TerrainCoveragePageMask`. A distant page is hidden
only when every base near chunk it spans retains surface coverage. Missing
cells, a wrong epoch, or unsupported coordinate arithmetic fail open to the
distant page. An eight-frame stable page-coordinate threshold spatially
distributes the handoff; replacement `REQUESTED`/`BUILDING`/`UPLOAD_PENDING`
states retain the already-settled mask through last-known-good coverage. An
explicit removal reveals still-resident distant geometry on the next pinned
frame instead of waiting for a rebuild or upload. The legacy whole-domain route
retains conservative overlap as the rollback.

`render.substrate.terrain.nearCoverage` reports both revisions, state counts,
bounded cells, transition/coverage ages, and contributed ownership. The DH
hierarchy diagnostic additionally reports selected versus masked pages, both
coverage revisions, and the transition duration. Dependency-clean fixtures
cover complete and partial multi-cell pages, negative coordinates, wrong-world
fail-open behavior, deterministic transition, and replacement retention.

The named headless seam-motion sequence now drives one surface page through
request, build, upload, readiness, spatially thresholded handoff, failed
replacement/retry, retirement, and removal. Every frame is repeatable, settled
replacement retains the prior spatial age, the distant decision never
reappears after settling, and the paired near/distant decisions never both
become false.

At this initial slice, checked real-GL seam acceptance remained required. The
later built-in and Bliss checked-pixel scenarios below close that gate without
requiring a finer subpage representation.

## Phase 6 hierarchical distant-data start

The dependency-clean distant hierarchy now owns bounded top-down vertical runs
with semantic material, fluid, block/sky light, tint, confidence, and cave/
void/emissive flags. Columns have deterministic digests and a reducer that
retains extrema plus the first opaque/fluid surfaces while ranking material,
emissive, light, and cave transitions. A deterministic four-child page reducer
preserves vertical intervals and minimum child completeness.

`DistantPageHierarchyIndex` applies checked horizontal quadtree math, including
negative-coordinate floor division, and keeps independent source, dirty, and
render revisions. Source publication/removal, exact render-dependency
invalidation, bounded capacity rejection, stale render publication, parent
completeness, build lifecycle, and last-known-good artifact ownership are
atomic. A base-page mutation dirties only its ancestor chain and already-present
cardinal stitch neighbours. `DistantPageSelector` uses projected error, camera
height/FOV/zoom, quality and seam/coverage pressure, separate refine/coarsen
thresholds, bounded node/change work, uploaded-page availability, and a
one-level adjacent-detail constraint. Selection changes do not mutate source or
render revisions.

The selector indexes its previous selection by ancestor and detects adjacent
detail violations through aligned hierarchy neighbours rather than repeated
all-pairs scans. Balancing resolves every currently valid detail violation in
one deterministic pass and stops after 64 passes. The former single-violation
loop could sort and rescan the complete selection up to twice per indexed page
inside each of 24 page-budget quality trials, monopolizing the render thread
for more than a minute on a hydrated medium world. The production runtime
reselects only when its block-quantized
camera, viewport/FOV, seam distance, or CPU-page publication revision changes,
and captures one hierarchy snapshot per reselection. Upload promotion and fence
retirement still advance every frame. This removes the former whole-domain
selection copies and scans from an unchanged frame without turning camera
movement into geometry rebuilds.

The semantic page mesher emits page-relative observed top and four true
run-derived side directions. It subtracts occluding vertical intervals,
preserves fluid beds, material, actual block/sky light, resolved tint, flags,
and confidence, greedily merges compatible rectangles, splits mixed-detail
edges deterministically, compares the known runs of partial neighbours, and
does not turn missing coverage into a cliff, skirt, or unobserved lowest-run
underside. Cliff, cave, fluid/bed, light/tint, flat greedy, unknown-boundary,
and mixed-detail fixtures pin stable artifact digests and counts.
The completed headless geometry matrix additionally freezes a coastline with a
non-Minecraft custom fluid, independent bed/shore relief, two light pairs, and
a resolved custom biome tint at digest
`a1103f87f1200c6eb1dc86cdaa256b50d6d25dd72867a71bed217343c235365e`.

The existing top-only tile format has an explicit deterministic partial-quality
projection. It never infers fluids from identifier suffixes: production resolves
exact registry `FluidHolder` semantics and opacity before worker submission.
The default unified production route sends base and
derived parent pages through the shared process CPU service, maintains one
pending identity per page, rejects stale child/neighbour completions, uploads
and replaces one solid/water page transactionally, removes evicted hierarchy/
GPU ownership, and supplies independent stateful main/shadow page selectors.
Vertices remain page-relative; `uPageOffset` updates the draw transform after
render-origin movement without rebuilding CPU geometry. Bounded debug output
reports index/source/dirty revisions, state and detail counts, CPU/GPU/pending/
queued/selected totals, and up to 128 page records.

The hierarchy is selected by default and consumes native vertical pages when
available, with the partial top-only tier retained for v1 migration input. The
later checked mixed-detail runs and retained fixed-camera/streaming performance
records complement the complete headless cliff, cave, coast, custom-fluid,
tint, light, camera-selection, and bounded-invalidation matrix and close the
Phase 6 exit gate.

## Phase 7 vertical sampling and incremental-store start

`DistantVerticalSampler` is now the single dependency-clean voxel-to-run
normalization boundary. It trims unbounded exterior void, retains enclosed cave
intervals, coalesces equivalent semantics, and applies the same deterministic
64-run bound to every source. The Minecraft adapter records exact block/fluid
state (including waterlogged state), block and sky light, biome tint input,
opacity, luminance, generation confidence, and world epoch without retaining a
live chunk. Observed chunks and local generation publish a vertical page beside
the readable top-only compatibility tile; block mutations resample only affected
columns. The hierarchical candidate prefers native vertical pages and uses the
top-only projection only for v1 persistence/network input.

`DistantVerticalPageCodec` is the canonical bounded schema-v2 record encoding.
It round-trips page/world identity, completeness, source revision, and every run
semantic while bounding bytes, palettes, strings, columns, and runs. It rejects
truncation, old schemas, malformed fields, and trailing data. Checked-in binary
goldens pin the page and all four protocol-v2 message encodings; the page codec
also has a checked-in canonical semantic dump.
`DistantDirectoryTerrainStore` writes one atomic checksum-protected record per
dirty page under a world/level manifest. Recovery ignores interrupted temporary
records, restored pages are rekeyed to the active world epoch, and deterministic
inspection reports record, byte, and temporary counts. Its bounded coalescing
writer keeps a key dirty until its page is durable. The controller reads v1
snapshot storage once, migrates every missing retained tile into a partial v2
page, and thereafter writes only the page-oriented store; the v1 decoder remains
readable migration input but the whole snapshot is no longer a second writer.

Capacity now evicts the farthest and then least-recent record relative to the
active player while excluding every dirty pinned key; interrupted over-capacity
replacement is deterministically trimmed during recovery. The adapter exposes a
bounded `store-network-inspection` debug operation with schema/count/bytes/temp/
pinned/eviction state and protocol/pending/outstanding/request/receive state.

`DistantTerrainProtocolV2` is shared by client and managed server through
`render-contracts`. Every message carries protocol/data schema, connection and
world epoch, normalized level, and bounded type-specific fields. Requests carry
64-bit correlation, exact page keys/detail and minimum source revision;
responses carry independently bounded canonical page records. The client keeps
the exact expected set per request and atomically rejects wrong-world, unknown,
unrequested, duplicate, and unsupported-detail responses. A page made stale by
newer local or restored data is consumed as superseded without publication;
this preserves the request ledger for the remaining one-page server responses
instead of turning them into an unknown-request cascade. Dimension replacement
cancels the complete ledger. Timed-out v2 requests now remove their
exact page set, cancel the local ledger, and send a correlated wire cancellation;
a failed send rolls local admission back. The production channel negotiates v2;
v1 remains readable but the managed server no longer advertises or admits v1
generation work.

The managed server validates player, level, epoch, radius, detail, request and
queue bounds at admission and again before publication. It initiates bounded
FULL-chunk futures instead of synchronously forcing generation on the normal
tick, deduplicates and shares identical page work across players while rekeying
the result to each request epoch, caps global in-flight work, and suppresses
queued or in-flight publication after cancellation, dimension change, or
disconnect. Admission is atomic across the whole request, rejects an already
active request ID, validates cancellation world identity, honors the requested
minimum source revision, and retires its request remainder after every success,
failure, stale completion, or radius rejection. Its normalized capture carries semantic block/fluid state, fluid
level/class, block/sky light, biome input, opacity, luminance and generated
provenance through the same vertical sampler.

A headless authority-neutral fixture now proves identical normalized semantics
through native/local/server capture inputs, the network codec, and persisted
page restore. Client v2 world retirement cancels every exact outstanding
request before clearing the local ledger. A receive-side renegotiation or
terminal rejected response instead clears the superseded ledger without
re-entering the transport's outbound path with a redundant cancellation.
Cancellation send/failure counts, ordered decode-queue depth/bytes/drops, and
world resets are included in bounded network inspection. Large response decode
runs on one bounded serial I/O drain instead of the Netty event loop, while page
palette/material and per-page fluid classification caches bound repeated codec
work. Failed sends are best-effort and cannot retain a local request. The Java
integration fixture proves exact world/request correlation, full stale-stream
consumption without an unknown-request cascade, receive-path monitor
independence, and empty post-retirement ledgers.

Local authority now prepares a complete 3x3 detached `ChunkBuilder`
neighbourhood before publishing the center page. `DistantDetachedLighting`
copies block luminance, directional propagation, and sky-height behavior into
fixed primitive arrays, propagates both channels without retaining Minecraft
objects, and samples only the center page. The one-chunk/16-block halo is larger
than the maximum non-zero light influence; sources outside it cannot affect the
result. Neighbourhood construction consumes the existing budget as actual
generated chunks, can publish no more than that budget per tick, and uses a
bounded 25-entry LRU so adjacent centers reuse six of nine chunks. World-epoch
change clears pending generation and cached builders. Generated pages now claim
`COMPLETE` only after this boundary succeeds. Core fixtures cover directional
blockers, filtered/direct sky, halo exclusion, and no-skylight dimensions; the
real-registry integration fixture proves a torch in the west halo contributes
block light to a center-page water run with the same filtered skylight boundary.

The first isolated managed-server qualification exposed a client freeze during
initial chunk delivery. The process stayed alive and the debug endpoint
incorrectly remained render-ready, but the server timed the player out after
30 seconds and the render frame stopped advancing. A JVM thread dump proved a
read-to-write lock upgrade: observed-page capture held `Chunk.lock` for reading
while `NoiseBiomeAccessor` attempted to populate the chunk biome cache through
the same lock's write side. Every affected packet worker parked in
`SectionDataProvider.set`, and the render thread subsequently parked behind one
of those chunk locks. Observed full-page and incremental-column capture now use
the reentrant write boundary; the top-only read-only projection retains the
read boundary. A bounded integration regression deliberately starts with an
empty noise-biome cache and requires observed capture to complete rather than
deadlock.

The corrected Java 25.0.1 distribution reconnected to the same isolated
1.20.4 server and remained connected beyond the original failure window. The
client advanced past 600 frames while the server retained one player. A live
Overworld -> Nether -> End -> Overworld cycle advanced terrain world epoch 1
through 4. Nether negotiated protocol v2 with 32 bounded pending pages and four
correlated request IDs before the next replacement. At the restored Overworld
boundary, bounded complete page queries returned 597 near and 678 distant
records, every record carried epoch 4, neither query required a continuation
cursor, both main and shadow publications reported zero missing pages, and the
shared CPU service drained to zero outstanding work. Server inspection drained
to zero queued, in-flight, and active-v2 requests. A fixed 10.38-second server
sample advanced from tick 8,780 to 8,988, approximately 20.0 TPS, while distant
request/tile totals remained stable at 11/45. The player was finally restored
to the recorded Overworld pose `(102.5, 72.0, 9.5, 0, 0)` with 20 health. The
supervisor then stopped cleanly, both leases were released, and endpoint/process
discovery was empty.

The clean reconnect then restored all 476 schema-v2 page records with no ignored
temporary record, retained the exact player pose and health, and advanced a new
rendering generation normally. It also exposed an ordering defect: the v2 hello
can arrive during configuration before the playable-world `JOINED` event, but
the controller retained only a v1 hello when replacing its provisional session
state. The retained negotiation slot now accepts only the two hello message
types and replays either one into the final state. Hot-reload generation 2 then
restored the same 476 records and negotiated protocol v2 on initial Overworld
login, with a 32-page bounded pending set and bounded correlated request ledger.
The Java integration lifecycle harness reproduces the hello-before-join order
and pins protocol version, page bound, and radius after state replacement.

This closes the Phase 7 exit gate. Live dimension and reconnect evidence proves
zero cross-world publication; the directory store and coalescing-writer tests
prove dirty-page cost and recovery; the named server sample meets the tick/TPS
gate; bounded codec/store fixtures reject corrupt, oversized, interrupted, and
old-schema inputs; and the authority-neutral parity fixture covers native,
local, managed-server, network, and persisted page semantics.

## Phase 8 shader/interoperability start

The production distant renderer now implements the neutral
`DistantTerrainProvider` contract with a stable compact physical-layout ID,
semantic-layout ID, opaque/water materials, block/sky-light and resolved-tint
semantics, main/shadow views, buffer-update upload, and the pinned DH profile.
Its provider generation is independent from the selected near provider; distant
build identities no longer borrow the near backend generation. Pipeline
candidates reject an aliased near/distant physical layout or any declared view
that either selected provider cannot serve.

Hierarchical results enter a bounded render-thread upload mailbox using the
shared independent CPU/byte budget contract. One oversized first item may
advance to avoid starvation, while page count remains capped per frame. Upload
failure retains last-known-good geometry, enters the existing bounded retry/
quarantine policy, and keeps the page dirty. Shader-generation replacement
cancels stale CPU/upload candidates and rebuilds every retained page without
dropping the installed generation. Partial page load failure releases the
complete candidate instead of publishing a mixed generation.

Existing graph and shader gates already pin distant opaque -> requested distant
depth snapshot -> distant water -> near opaque order, independent DH depth
targets, the dedicated `dh_shadow` route, built-in far projection, and the
optional executable Bliss `dh_terrain`/`dh_water` contract. The later real-GL
resource, reload, checked-pixel, and scaling continuations close the Phase 8
exit gate.

A client-only Apple OpenGL 4.1 run on the named
`terrain-consolidation-phase8-2026-07-31` trajectory exercised the Bliss
`dh_terrain`/`dh_shadow` routes and a shader reload. Near region uploads
converged to zero pending work with eight regions, 130 active pages,
23,961,600 resident bytes, and no allocation or upload failures. Reload
advanced the shader and graph generations, retained last-known-good pages while
their replacements built, resumed the requested distant routes, and returned
the observed shader-resource live counts to their pre-reload values. The run
also exposed an unacceptable hierarchical-route scaling result: roughly one
VAO and two buffers per distant page, hundreds of page draws per frame, and
about 1.7 FPS in the flat-world workload. Distant selected-page residency and
region/material/view batching were therefore required before default adoption;
the later continuation below replaces that resource model and records the
accepted no-property default run.

The first stop of that run found a native shutdown defect: session disconnect
could reach near-region `clear()` from the JVM shutdown hook after the OpenGL
context was no longer current, and its `glFinish` call aborted inside LWJGL.
Off-context teardown now abandons logical references without issuing any GL
call; render-thread teardown retains the fenced device cleanup. Repeating the
same client start and supervised stop completed disconnect, audio unload, debug
channel shutdown, and local-world shutdown without another native fatal error.

A later fence-drain run found a distinct context-ownership defect:
`OpenGlNearTerrainRegionRuntime.prepareFrame()` is invoked by
`ChunkRenderer.prepareDrawAsync()` on a rendering-pool worker, so polling a
submission fence there entered `glClientWaitSync` without a current context and
aborted the JVM. Near-region fence polling, retired-range collection, and empty
device closure now run at the context-current post-draw `finishFrame()`
boundary. The shared OpenGL completion adapter also checks its owning render
context before every native fence operation. The same trajectory then remained
healthy beyond 200 frames with zero distant pending fences, one current near
fence, zero retired pages, and no allocation/upload failures; supervised stop
left no live process and emitted no native fatal or context-guard failure.

## Unified production default and bounded hierarchy continuation

The three independent `minosoft.terrain.semantic-artifacts`,
`minosoft.terrain.region-storage`, and `minosoft.terrain.distant-hierarchy`
booleans were removed. At this checkpoint, `TerrainRuntimeSelection` pinned one
immutable process mode and retained a coherent comparison/rollback boundary;
focused and architecture tests pinned the default and rejected partial modes.
Phase 9 later removed this temporary switch and its legacy paths.
`render.substrate.terrain` continues to report the unified mode and each
effective route directly.

The distant hierarchy now has an atomic multi-source mutation whose complete
candidate is validated before one commit. It derives each affected ancestor
once, coalesces lineage/neighbour invalidation, rejects stale/conflicting/
over-capacity batches without partial publication, and retains immutable
snapshots for diagnostics while selection uses a synchronized short-lived
no-copy index view. The vertical reducer no longer constructs sequence,
grouping, string-key, or candidate-list intermediates in its hot interval loop.
Production separates bounded source ingestion/derivation from selected artifact
meshing, reorders pending work from camera and seam state, prunes source work to
the selected dependency closure, preserves last-known-good selection during a
replacement, and progressively publishes an empty/subset initial selection.
Source and artifact submissions have independent caps and share bounded
completion/upload drains. Distant logical region extents remain 32 by 1 by 32
pages. Dense logical regions use exact-fit, bounded overflow storage shards
under the existing global 32-device and 8+2 MiB per-shard limits; empty pages
consume no storage range. Fit prediction is non-mutating, cached batches key by
the exact physical storage owner, and diagnostics enumerate every shard.

Two 30-second JFR samples isolated and removed the largest render-thread
ingestion allocations. Before the direct metadata scan, three GC pauses totaled
647 ms (median 192 ms, p95 271 ms) and `CollectionsKt.asSequence` accounted for
75.43 percent of sampled allocation. After it, three pauses totaled 396 ms
(median 124 ms, p95 160 ms); the sequence allocation disappeared and exposed
`TreeMap.addEntry` snapshot construction as the next dominant source. The
short-lived read view removes that full-tree selection copy. These are targeted
diagnostic samples, not a final cross-build performance acceptance result.

A clean Java 25 Apple OpenGL 4.1 client generation then ran the Bliss trajectory
without any terrain rollout property. `render.substrate` reported
`runtimeMode=unified` with semantic artifacts, region storage, and distant
hierarchy all selected. The flat 1.20.4 workload converged to 1,360 selected GPU
pages across detail levels 0 through 3, 23 distant regions, 983,760 resident
bytes, 46 device batches, 2,720 commands, zero queued/pending pages, zero
allocation/upload failures, and one current-frame submission fence. Near region
storage simultaneously retained 25 active pages in four regions with zero
retired bytes and zero allocation/upload failures.

A live Bliss reload advanced shader generation 1 to 2. During rebuild the same
1,360 distant GPU pages remained installed while the bounded source/artifact
queues refilled. They returned to zero pending/queued work, and the exact
pre-reload GPU ledger returned to 523 live names (216 buffers, 108 vertex
arrays, 56 textures, one renderbuffer, seven framebuffers, zero shaders, and
135 programs). Near and distant retired bytes returned to zero. Supervised stop
completed disconnect and audio teardown without a native fatal or context-
ownership failure; no client/server process, lease, or transient override
remained. This closes the default-selection and this named reload/resource
baseline, but does not substitute for diverse geometry, motion/seam, checked-
pixel, server, reconnect, or cross-driver gates. The sky-facing capture from the
same flat trajectory is explicitly excluded from visual acceptance.

The enabled page-mask continuation repeated that named Java 25/Apple OpenGL
4.1 Bliss trajectory after regenerating its isolated flat local world. At the
idle boundary, all 16,129 source tiles had produced 21,597 indexed pages,
20,924 CPU pages, and 1,744 selected GPU pages across detail levels zero through
three. Main and shadow selected the same 1,744 pages and independently reported
the same 112 masked pages from near coverage revision 114/lifecycle revision
1,076 with an eight-frame transition. Both hierarchy queues were zero; 23
distant regions retained 1,260,240 bytes with zero retired bytes, allocation
failures, or upload failures and one current-frame fence. The additional seam
fallback residency is 384 pages and 276,480 bytes above the prior 1,360-page
selection. Near storage simultaneously retained 114 pages in six regions with
zero retired bytes or failures. The total driver ledger was 529 live names:
220 buffers, 110 vertex arrays, 56 textures, one renderbuffer, seven
framebuffers, zero shaders, and 135 programs. Supervised stop again completed
cleanly, removed the endpoint and every process, and emitted no current-run
native fatal, context-ownership failure, or OpenGL error. This is lifecycle,
mask-agreement, bounded-residency, and cleanup evidence; the flat pose remains
excluded from checked-pixel seam acceptance.

The production diagnostic continuation used an isolated Java 25/Apple OpenGL
4.1 Bliss local-world trajectory. The summary advertised all version-one
capabilities with no unavailable subject. A five-page near window continued
from frame 401 to 404 at unchanged near registry generation 2,288 while the
distant hierarchy continued publishing; the second window began strictly after
the first cursor. A bounded distant prefix returned three real level-zero pages
with provider IDs, `READY` state, and build identities. A wrong epoch rejected
as `WRONG_WORLD_EPOCH`, while a continuation over the actively mutating distant
registry rejected as `STALE_CURSOR` with expected/actual generations. That run
exposed historical submission references accumulating on active allocations.
After the recovery fix, a second run at frame 225 reported 130 active near
pages, exactly 130 pending page/submission references, one GL fence, and zero
failures; the complete diagnostic reported 1,057 current references across both
domains instead of the prior frame-multiplied count. Both runs ended with no
process, endpoint, lease, server mutation, or transient presentation override.

A subsequent built-in OpenGL lifecycle run on the isolated
`terrain-consolidation-final-2026-07-31` debug-world trajectory found 43 terrain
resources surviving until context destruction: 28 buffers and 15 vertex
arrays. Backend close stopped the scheduler and pending uploads but did not
clear loaded section ownership, cached block-entity renderers, or the bounded
unload queue. It also attempted to retire near coverage through a terrain
registry whose generation store had already closed. Terminal terrain cleanup
now discards coverage without consulting that retired owner, clears loaded and
cached resources, drains every queued GPU release while the context is current,
and continues through later cleanup after an individual failure. The focused
integration fixture proves a loaded candidate reaches `UNLOADED` and leaves no
queued mesh; the complete unit/integration suites pass. Repeating the same
Apple OpenGL 4.1 workload with 67 active near pages and 13 block entities
emitted no live-resource warning, fatal entry, or exception at context
destruction. The client, endpoint, and lease were removed and no server was
started.

The complete-pipeline live continuation first exposed a Complementary Unbound
startup rejection before the new frame registry could execute. The resolved
common source declared `normals` and `specular` in both stages; distant fragment
sampling was neutralized, but the retained vertex declarations combined with a
same-named local lighting variable made the conservative contract inspection
report an unbound sampler. The distant transform now neutralizes companion
material sampling in both stages. It also converts the retained DH water
vertex `attribute` declaration to core-profile `in`, removing the subsequent
driver compile fallback. The exact r5.8.1 archive passes the planner with the
DH environment enabled. On Apple OpenGL 4.1, client generation 2 reached
render-ready with both `dh_terrain` and `dh_water` compiled and physically
submitted. A live shader reload advanced shader/material generation 1 to 2,
graph generation 17 to 18, and the authoritative complete terrain generation
0 to 1; last-known-good geometry remained installed and the shared scheduler
returned to zero queued, active, outstanding, and completion work. Supervised
stop emitted no fatal, exception, context, OpenGL, or live-resource warning,
and left no process, debug endpoint, lease, server mutation, or transient
reference suppression. The debug-world capture is excluded from checked-pixel
acceptance because the player pose is below the fixture surface and the first
capture intentionally retained the nearby world-border producer.

The checked-in `terrain-seam-built-in-macos-retina` and
`terrain-seam-bliss-macos-retina` scenarios then passed on Apple OpenGL 4.1 at
the fixed 3456-by-1910 seam pose. Built-in published 128 ready near pages and
2,483 distant pages with zero missing main/shadow pages; its exact 2,200-by-16
stripe matched SHA-256
`4c4c28615159776beabc8af2f76a7caef6429c913eb36b5f3b774e42f81e495b`,
zero near-black pixels, and full alpha. Bliss published matching near and
distant main/shadow selections with zero missing pages, two changing animated
stripe digests with zero near-black pixels and full alpha, and 2,606 GPU pages
across detail levels zero and one in 36 region batches with zero allocation or
upload failures. The retained primitive record is
`2026-07-31-terrain-seam-pixels.json`. Together with the exact route/reload and
resource-baseline evidence above, this closes the Phase 8 exit gate.

## Phase 9 production retirement

After the documented unified default and rollback comparison boundary, the
production rollout property and its legacy/compare modes were removed. Near
meshing now always emits the normalized artifact and selects transactional
region storage when OpenGL is available; the conventional per-section draw
loop remains the non-region fallback. The distant renderer now has exactly one
page-local hierarchical build/upload/storage path. Its old whole-domain tile
planner, coalescing worker, standalone mesh uploader, and their focused tests
were deleted. The near `ChunkMeshingCause` compatibility alias was removed in
favor of `TerrainBuildCause`. The checked read-only v1 store decoder and its
migration fixture remain. An architecture ratchet rejects the removed rollout
properties and retired production type declarations.

The post-retirement `runtime-soak` scenario passed 180 cases over 904,123 ms on
the isolated `terrain-consolidation-phase9-soak-2026-07-31` trajectory with the
Bliss profile, exact generation-one client/server endpoints, and a fixed
overworld pose. All client/server status and state samples succeeded. The final
terrain boundary had zero queued, active, outstanding, rejected, cancelled, or
failed shared builds; zero pending near uploads; zero failed builds/uploads or
submissions; zero retired bytes/pages; and zero missing near/distant pages.
The server continued replacing distant pages during the interval, so the GPU
name delta is retained as an active-streaming observation rather than falsely
reported as an idle leak comparison. The primitive record is
`2026-07-31-terrain-runtime-soak.json`.

The checked `terrain-resource-soak-30m` scenario later passed 60 cases over
1,811,527 ms on the isolated Bliss/local-world trajectory. Near main and shadow
remained complete at 130 resident pages, shared builds reached 34,362 completed
with zero failures or rejections, and device accounting ended with zero retired
bytes, hard residency failures, failed submissions, near build failures, or
near upload failures. The client JFR and final client metrics were retained.
Distant residency continued growing from 2,408 to 5,209 pages throughout the
interval, so this closes the bounded high-view-distance/resource-lifecycle soak
but is deliberately classified as active-fill evidence, not the required
quiescent steady-state leak baseline. A newly desired distant page may be
temporarily absent during first publication; the checked predicate therefore
requires complete near views and populated distant views rather than falsely
requiring every not-yet-built distant page to be resident at every sample. The
durable primitive record is `2026-07-31-terrain-resource-soak-30m.json`.

Five additional checked L5 scenarios now cover base near, optimized near,
distant-only camera coverage, fast positive/negative seam traversal, shader
toggle/reload, content-resource reload, and main/shadow generation transition.
The base and repaired Sodium-only packs each published complete active near
coverage through their exact provider owner. The combined Bliss run moved the
camera `0.5 -> 512.5 -> -512.5 -> 0.5`, recovered the seam with zero retired
bytes, then advanced the complete pipeline/material/shader/render-graph
generations to `3/4/4/20`; near main/shadow each drew 130 pages and distant
main/shadow each drew 647 with zero missing pages, failed builds, or failed
submissions.

The first shader-transition attempt reproduced a native client abort:
`render.terrain.flush-idle` captured `terrainSelection()` on the debug
connection thread after Iris disable retired the old shader generation, causing
`glDeleteProgram` without a current OpenGL context. The operation now enters
through `onRenderAsync`; generation capture, polling, publication retirement,
and final diagnostic capture all run on the render thread. A source ratchet
protects that boundary, and the exact live transition passed after restart.
The primitive record is
`2026-07-31-terrain-l5-profile-movement-reload.json`.

The checked light/fluid material scenario passed as
`terrain-light-fluid-materials-2026-08-01T06-42-20-214112Z-92926`, and the
overworld/Nether/End/overworld lifecycle scenario passed as
`terrain-dimension-transitions-2026-08-01T06-45-41-755578Z-94373` with exact
epoch advancement, current-epoch page ownership, drained retirement, and the
original player pose restored. The same-address and same-level-name reconnect
gate then ran two isolated saved worlds with seeds `111111111` and `222222222`.
Both exposed `minecraft:overworld` at epoch one, while their stable persistence
identities differed. That gate found and closed two additional boundaries: the
hashed world seed now participates in the page-store identity, and well-formed
semantic v2 response rejections are counted/cancelled without escaping the
network handler or publishing a page. The second live run remained connected
while 25 late unknown-request responses were rejected nonfatally. The durable
record is `2026-08-01-terrain-l5-world-reconnect.json`.

Supervised stop exposed late schema-v2 pages racing the world reset epoch. The
old combined assertion rejected them correctly but emitted warning traces.
Publication now distinguishes malformed tile/page identity, which still
throws, from an otherwise valid stale world epoch, which returns without
mutating the store. The focused adapter and network lifecycle gates cover the
boundary. Graceful stop then left no process, endpoint, server port, external
client, lease, or transient override active.

The final managed-world DH follow-up moved PLAY heartbeat and ping handling off
the shared packet worker pool and onto the network event loop. This keeps the
bounded control replies independent of page hydration and terrain work. A
fresh `fabric-stack` client joined at 13:14:37 and remained healthy beyond the
former 120-second disconnect boundary while asynchronously hydrating 9,116
schema-v2 pages. The settled store held 9,151 records and 2,368,558,454 bytes
with zero temporary records, pins, or evictions. Network inspection reported
108 received pages, a drained zero-drop decode queue, zero wrong-world,
unknown-request, unrequested-page, duplicate-page, and unsupported-detail
responses, and 50 consumed superseded pages. The fixed-pose hierarchy had 498
selected main pages, zero missing pages, zero skirts, and zero distant
allocation or upload failures. A DH-disabled/enabled framebuffer A/B retained
the same screen-wide black stippling on clouds, water, near terrain, and the
player hand, so that separate Iris/material defect is not attributed to DH.

The same hydrated-world visual follow-up exposed the selector balancing cost
above in a live render-thread sample at `firstDetailViolation`. After batched
balancing and the fixed 64-pass ceiling, a fresh generation published 566 GPU
pages from 706 indexed pages, selected 488 main and 254 shadow pages with zero
missing pages, zero skirts, and zero distant allocation/upload failures. After
the startup/hydration boundary, 123 fixed-pose frames reported median 126.5 ms
and p95 281.0 ms; a later thread sample was in ordinary entity shader binding,
not distant selection. The focused selector suite also covers a 2,048-base-page
quality search with a 1,024-page ceiling and the one-level adjacency invariant.

## Validation

Java 25.0.1 was used throughout. The complete combined diff passed the
following serial checks:

```sh
./gradlew :render-contracts:test
./gradlew compileKotlin :test \
  --tests \
  'de.bixilon.minosoft.architecture.TerrainArchitectureBoundaryTest' \
  --tests \
  'de.bixilon.minosoft.debug.terrain.*' \
  --tests \
  'de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildRuntimeTest' \
  --tests \
  'de.bixilon.minosoft.terrain.distant.DistantLodDataTest' \
  --tests \
  'de.bixilon.minosoft.modding.loader.fabric.DistantHorizonsCompatibilityAdapterTest' \
  --tests \
  'de.bixilon.minosoft.gui.rendering.terrain.near.provider.ChunkRendererNearTerrainBackendTest' \
  --tests \
  'de.bixilon.minosoft.modding.loader.fabric.SodiumTerrainProviderDescriptorTest' \
  --tests \
  'de.bixilon.minosoft.modding.loader.fabric.FabricPackPreflightTest' \
  --tests \
  'de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalityCatalogTest'
./gradlew integrationTest \
  --tests 'de.bixilon.minosoft.terrain.runtime.TerrainWorldEpochTest'
./gradlew test
git diff --check
```

The dependency-clean scheduler suite additionally covers distant reservation
under near churn, equal-urgency tenant rotation, bounded completion drains and
primitive counters, independent estimated CPU/output admission, routed tenant
completion capacity, saturation rejection/cancellation, worker-context reuse,
logical cancellation, owned shutdown, and cleanup failure propagation. The
later bounded trajectories additionally passed focused diagnostic
operation/schema tests and 30 neutral-data, architecture, v1 persistence/
protocol, and distant-render tests before the combined gate. The Phase 6 start
additionally passed the complete `:render-contracts:test` suite plus focused
application compilation, architecture, top-only migration, and distant-render/
protocol regression gates. The later default-route acceptance below supersedes
the original lack of a real-GL hierarchy claim. The Phase-7 gate added
checked-in page/protocol
goldens, one-way top-only migration, authority-neutral network/store parity,
correlated client/server cancellation cleanup, and passed another complete
`./gradlew test` plus `git diff --check`.

The scheduler admission/diagnostic continuation passed the complete
`:render-contracts:test`, root `test`, and `integrationTest` suites plus the
focused diagnostic and architecture gates on Java 25.0.1.

The named L6 matrix now covers steady camera, continuous nearby block
invalidation, straight-line streaming, fast seam traversal, schema-v2 distant
database cold load, distant generation fill, dimension transition, and shader
reload with populated terrain. Its final 30-minute quiescent resource soak ran
59 cases over 1,816,252 milliseconds after 4,130 shared builds had drained. It
held 66 near and 2,861 distant resident pages, 60,702,288 resident bytes, zero
retired bytes, and zero build/upload/submission failures. OpenGL name creation
and deletion each advanced by 37,986 while the live-name count remained 463,
so the steady-state ledger had no growth. The durable primitive record is
`2026-08-01-terrain-l6-workload-matrix.json`. A second-driver resource/reload
qualification is retained as deferred portability work.

After production retirement, `./gradlew :render-contracts:test test
integrationTest` and `git diff --check` passed on Java 25.0.1. The late-network
epoch follow-up additionally passed the focused distant-adapter unit gate and
the Java `DistantLodNetworkLifecycleTest` integration gate.

The Phase 8 acceptance used only the named client-local trajectory and restored
its reference presentation before stopping it. No client or server remains
running, its lease was released, and no transient reference/canary override is
active. The acceptance framebuffer is
`/tmp/terrain-consolidation-phase8-bliss.png` with SHA-256
`06124862cb7b92cfc4f11e2c579bc3a4be1f7552d8b27af9c352dbf2a38827a5`;
raw lifecycle logs remain untracked runtime state.

A later populated debug-world Bliss to built-in to Bliss transition exposed
that the disable path restored shader generation 2 without invalidating near
material generation 1. The old pages were correctly filtered, leaving no near
batches. Iris presentation removal now queues every near section with the
fixed `resourceGeneration` cause before rebuilding the graph. In the repeated
Apple OpenGL 4.1 run, complete terrain/shader/graph generations advanced
`0/1/17 -> 1/2/18 -> 2/3/19`. Built-in converged to 66 resident and 66 drawn
main pages with zero missing pages; restored Bliss converged to 66 drawn pages
in both main and shadow, 24 near device batches, and zero near/distant
allocation or upload failures. The 32-page logical distant region used three
physical shards for 2,976 selected pages without capacity failure. Both
framebuffers contain the expected debug-grid geometry, but remain visual
inspection artifacts rather than checked-pixel baselines. The retained
primitive record is `2026-07-31-terrain-shader-transition.json`.

The final built-in teardown continuation additionally passed complete
`./gradlew test integrationTest` and `git diff --check` on Java 25.0.1 before
the real-OpenGL rerun.

The Complementary DH startup correction passed the focused transformer suite
and the exact external archive planner gate with
`MINOSOFT_IRIS_TEST_DISTANT_HORIZONS=true` before the live generation and
reload checks above.

The provider-neutral visibility/publication continuation passed the complete
`:render-contracts:test`, root `test`, and `integrationTest` suites plus
`git diff --check` on Java 25.0.1. Focused coverage includes deterministic
selection generations and sorted bounded snapshots, exact-version promotion,
last-known-good retention, canonical version-one JSON/capabilities, complete
production diagnostic assembly, and an architecture ratchet requiring both
near and distant runtimes to desire, promote, and consume the shared active
selection.

After the per-view selection cache correction, the complete
`:render-contracts:test`, root `test`, and `integrationTest` suites and
`git diff --check` passed again on Java 25.0.1 before the matched live rerun.

The later topology/legacy-retirement slice passed `:render-contracts:test`, the
focused consolidated L3 and architecture tests, and the complete
`./gradlew integrationTest` suite on Java 25.0.1. The class-filtered TestNG
attempt was discarded because omitting the declared `block` dependency group
prevents suite construction; it did not execute or fail a test body.

The distant-selection scaling slice passed the focused dependency-clean
selector suite plus application Kotlin/Java compilation on Java 25. Its
hierarchy-indexed adjacency result remains protected by the existing mixed-
detail balancing fixture. The later matched fixed-camera and streaming records,
`2026-07-31-terrain-near-fixed-camera-performance.json` and
`2026-07-31-terrain-streaming-performance.json`, retain the accepted
steady-frame, movement, bounded upload, mixed-detail, and idle-drain evidence.

The enabled coverage-mask slice passed the focused
`TerrainCoverageMaskingTest`, the complete `:render-contracts:test` suite, the
complete root `:test` suite, the architecture and distant-adapter focused gate,
and `git diff --check` on Java 25.0.1. Its supervised live run used no terrain
rollout property and left no process, debug endpoint, server state, or lease.

The empty-Fabric-allowlist and client-ledger lifecycle slice passed the focused
architecture, Iris shader, Fabric preflight, distant-adapter, and Java
integration lifecycle gates on Java 25.0.1. The integration build now includes
the conventional `src/integration-test/java` source directory so the lifecycle
fixture can use the existing fully initialized session harness without widening
Kotlin-internal production APIs.

The expanded near semantic matrix passed through the standalone Java integration
entry point on Java 25.0.1. It exposed and then pinned the detached block-entity
NBT boundary instead of accepting missing entity-dependent model output as an
intentional delta.

The dev supervisor now stages a base-game candidate under a generation-specific
distribution before stopping the active client. This prevents `installDist`
from replacing lazily loaded classes beneath the old JVM; the isolated
destination gate verified that staging did not change the active distribution.
A bounded Bliss/local-world hot reload then moved generation 1 to generation 2,
with the candidate running entirely from the isolated distribution. The old
generation completed world/Fabric/audio teardown without the prior synthetic-
class linkage failure, generation 2 reached render-ready, and supervised stop
removed the client, endpoint, staged generation, and trajectory lease.

## Diverse-medium DH and visual follow-up

The `diverse-medium-biomes-2026-08-01` managed trajectory exercised the
populated authoritative world on Apple OpenGL 4.1. The DH hierarchy selected
479 resident main pages with zero missing pages and zero allocation/upload
failures. Its angular hydration frontier reported configured distance 128
chunks and effective distance 12 chunks while native storage held 453 pages
and the intentionally paced server had supplied 26 more. This prevents the
renderer from projecting or fogging beyond broadly hydrated angular coverage
without letting an isolated hole or far outlier collapse the view distance.
Network inspection retained 32 bounded pending requests, 28 sent and 26
received pages, with no response rejection or cancellation ledger entries.
The managed request timeout is 120 seconds because the accepted server policy
is one generated page per player per second.

The initial screen-wide stippling was not a DH failure: it survived disabling
DH and other scene producers, while the same world, pose, selected spruce log,
and nearby authoritative block sample rendered cleanly after disabling only
Iris presentation. A later fully hydrated A/B with Iris already disabled found
a separate DH defect: giant dark foreground slabs disappeared immediately when
only DH presentation was disabled. At that boundary DH reported 510 active main
pages, zero missing pages, zero allocation/upload failures, and 117 masked pages
while near coverage was still evolving. Treat this as a near/distant page-span
ownership defect; it does not overturn the earlier Iris stippling isolation.
The Iris investigation added deterministic first-generation
initialization for `LOAD` targets, framebuffer restoration after depth blits,
per-program raw-versus-comparison depth sampler state, reflected comparison
diagnostics, and bounded `frameTimeSmooth` and shadow-size uniform inputs.
These focused contracts pass, but the untouched Complementary generation still
produced block-aligned/stippled corruption and later wedged the render thread.
The affected trajectory therefore intentionally remains on the built-in
presentation. `/tmp/minosoft-base-renderer-original-2026-08-01.png` is the
clean same-pose framebuffer; Iris screenshots are diagnostic artifacts, not
acceptance references.

The authoritative player pose was restored to overworld
`32.6740054977249,79,-608.6999999880791`, yaw `115.75401`, pitch `4.5429916`.
That live sequence initially left the client and server running with DH enabled,
Iris presentation disabled, and no transient terrain or reference fixture. The
subsequent summary closeout stopped both processes and released both trajectory
leases; see the [recent trajectory summary](2026-08-01-recent-trajectory-summary.md).

## Implemented Phase 0 through Phase 9 boundaries

The retained semantic, visual, GPU-resource, workload, failure, checked-pixel,
motion, persistence, protocol, dimension, reconnect, reload, and scaling
records cover the implemented Phase 0 through Phase 9 boundaries. Production
boundaries consume the normalized
material, depth, residency, submission, publication, and structured-rejection
contracts, and the renderer/provider architecture allowlist is empty.

## Deferred portability qualification

- Retain a second-driver resource/reload baseline when another environment is
  available. This broadens portability evidence but does not block the
  completed Phase 0 through Phase 9 terrain-consolidation plan.
